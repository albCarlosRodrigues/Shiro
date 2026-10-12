package dev.squaddesktop.claude;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import dev.squaddesktop.runs.AgentEvent;
import dev.squaddesktop.runs.FailureCodes;
import dev.squaddesktop.runs.RunControl;
import dev.squaddesktop.runs.RunExecutor;
import dev.squaddesktop.runs.RunOutcome;
import dev.squaddesktop.runs.RunSpec;
import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.shared.Texts;

/**
 * Executa uma etapa com o Claude Code instalado ({@code claude -p}).
 *
 * <p>O processo é iniciado sem shell, com argumentos separados e diretório de trabalho explícito; o prompt
 * vai pela entrada padrão; stdout e stderr são consumidos em paralelo para o processo nunca travar com o
 * buffer cheio. A autenticação é a que a pessoa já configurou no Claude Code: nada de credenciais passa por aqui.
 */
public final class ClaudeRunExecutor implements RunExecutor {

    private static final int STDERR_TAIL = 4_000;

    private final ClaudeEnvironment environment;
    private final JsonCodec json;
    private final ProcessLauncher launcher;
    private final Path tempDir;

    public ClaudeRunExecutor(ClaudeEnvironment environment, JsonCodec json, ProcessLauncher launcher, Path tempDir) {
        this.environment = environment;
        this.json = json;
        this.launcher = launcher;
        this.tempDir = tempDir;
    }

    @Override
    public RunOutcome execute(RunSpec spec, Consumer<AgentEvent> sink, RunControl control) {
        ClaudeEnvironment.Snapshot snapshot = environment.snapshot();
        ClaudeStatus status = snapshot.status();
        if (!status.found()) {
            return RunOutcome.failure(null, FailureCodes.CLI_NOT_FOUND, status.problem(), List.of());
        }
        if (!status.usable()) {
            return RunOutcome.failure(null, FailureCodes.CLI_UNSUPPORTED, status.problem(), List.of());
        }
        if (Boolean.FALSE.equals(status.authenticated())) {
            return RunOutcome.failure(null, FailureCodes.NOT_AUTHENTICATED, status.problem(), List.of());
        }
        if (!Files.isDirectory(spec.cwd())) {
            return RunOutcome.failure(null, FailureCodes.FOLDER_MISSING,
                    "A pasta de trabalho não existe: " + spec.cwd() + ". Corrija a pasta do projeto e tente de novo.", List.of());
        }

        Path promptFile = null;
        List<String> display = List.of();
        try {
            if (!Texts.isBlank(spec.systemPrompt())) {
                Files.createDirectories(tempDir);
                promptFile = tempDir.resolve("run-" + spec.runId() + ".md");
                Files.writeString(promptFile, spec.systemPrompt(), StandardCharsets.UTF_8);
            }
            ClaudeCommandBuilder.Command built = ClaudeCommandBuilder.build(spec, status.capabilities(), promptFile);
            List<String> command = new ArrayList<>(snapshot.installation().launcher());
            command.addAll(built.args());
            display = ClaudeCommandBuilder.forDisplay(snapshot.installation().launcher(), built.args(), promptFile);
            sink.accept(new AgentEvent.Console("command", String.join(" ", display)));
            for (String skipped : built.skipped()) {
                sink.accept(new AgentEvent.Notice("warning", "capability_missing",
                        "O Claude Code " + Texts.orEmpty(status.version()) + " não oferece: " + skipped + "."));
            }
            return run(spec, command, display, sink, control);
        } catch (IOException e) {
            return RunOutcome.failure(null, FailureCodes.SPAWN_FAILED,
                    "Não foi possível iniciar o Claude Code: " + e.getMessage(), display);
        } finally {
            deleteQuietly(promptFile);
        }
    }

    private RunOutcome run(RunSpec spec, List<String> command, List<String> display, Consumer<AgentEvent> sink, RunControl control)
            throws IOException {
        Process process = launcher.start(command, spec.cwd());
        control.onCancel(() -> ProcessTrees.kill(process));

        StreamJsonParser parser = new StreamJsonParser(json, spec.resumeSessionId() != null);
        StringBuilder stderrTail = new StringBuilder();
        AtomicBoolean timedOut = new AtomicBoolean();

        Thread stdin = Thread.ofVirtual().name("claude-stdin-" + spec.runId()).start(() -> {
            try (OutputStream out = process.getOutputStream()) {
                out.write(spec.prompt().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // o processo saiu antes de ler todo o prompt; o código de saída e o stderr contam o motivo
            }
        });
        Thread stdout = Thread.ofVirtual().name("claude-stdout-" + spec.runId()).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    for (AgentEvent event : parser.accept(line)) {
                        sink.accept(event);
                    }
                }
            } catch (IOException ignored) {
                // fluxo fechado pelo encerramento do processo
            }
        });
        Thread stderr = Thread.ofVirtual().name("claude-stderr-" + spec.runId()).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderrTail) {
                        stderrTail.append(line).append('\n');
                        if (stderrTail.length() > STDERR_TAIL * 2) {
                            stderrTail.delete(0, stderrTail.length() - STDERR_TAIL);
                        }
                    }
                    if (!line.isBlank()) {
                        sink.accept(new AgentEvent.Console("stderr", Texts.truncate(line, StreamJsonParser.MAX_CONSOLE_CHARS)));
                    }
                }
            } catch (IOException ignored) {
                // fluxo fechado pelo encerramento do processo
            }
        });

        Integer exitCode = null;
        try {
            if (process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                exitCode = process.exitValue();
            } else {
                timedOut.set(true);
                ProcessTrees.kill(process);
            }
            // Os leitores terminam quando os fluxos fecham; um neto que herdou o stdout pode segurá-los abertos.
            stdout.join(5_000);
            stderr.join(2_000);
            stdin.join(1_000);
            if (stdout.isAlive() || stderr.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                closeQuietly(process);
                stdout.join(2_000);
            }
            if (exitCode == null && !process.isAlive()) {
                exitCode = process.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ProcessTrees.kill(process);
            return RunOutcome.failure(null, FailureCodes.INTERNAL, "A execução foi interrompida.", display);
        }

        if (control.cancelled()) {
            return new RunOutcome(exitCode, false, null, null, null, display);
        }
        if (timedOut.get()) {
            return RunOutcome.timeout(display);
        }
        String tail;
        synchronized (stderrTail) {
            tail = Texts.tail(stderrTail.toString().strip(), STDERR_TAIL);
        }
        return classify(exitCode, parser, tail, display);
    }

    /** Traduz o desfecho do processo num resultado com código estável e mensagem que aponta a saída. */
    static RunOutcome classify(Integer exitCode, StreamJsonParser parser, String stderrTail, List<String> display) {
        StreamJsonParser.ResultInfo result = parser.result();
        boolean cleanExit = exitCode != null && exitCode == 0;
        if (result != null && !result.error() && cleanExit) {
            return RunOutcome.success(exitCode, result.resultText(), display);
        }
        String detail = result != null && !Texts.isBlank(result.resultText()) ? result.resultText() : stderrTail;
        String lower = Texts.orEmpty(detail).toLowerCase(Locale.ROOT);
        String assistantError = Texts.orEmpty(parser.assistantError());
        Integer api = result == null ? null : result.apiErrorStatus();
        String subtype = result == null ? "" : Texts.orEmpty(result.subtype());

        if (assistantError.equals("authentication_failed") || assistantError.equals("oauth_org_not_allowed")
                || (api != null && (api == 401 || api == 403)) || lower.contains("/login") || lower.contains("not logged in")
                || lower.contains("invalid api key") || lower.contains("authentication_error")) {
            return RunOutcome.failure(exitCode, FailureCodes.NOT_AUTHENTICATED,
                    "O Claude Code recusou a autenticação. Abra um terminal, rode \"claude\" para entrar na conta e tente de novo."
                            + suffix(detail), display);
        }
        if (assistantError.equals("rate_limit") || (api != null && api == 429)) {
            return RunOutcome.failure(exitCode, FailureCodes.RATE_LIMITED,
                    "O limite de uso da conta foi atingido. Aguarde a renovação do limite e tente de novo." + suffix(detail), display);
        }
        if (assistantError.equals("billing_error") || assistantError.equals("account_on_hold")) {
            return RunOutcome.failure(exitCode, FailureCodes.BILLING,
                    "A conta do Claude tem uma pendência de cobrança. Resolva-a no site da Anthropic e tente de novo." + suffix(detail), display);
        }
        if (subtype.contains("max_turns")) {
            return RunOutcome.failure(exitCode, FailureCodes.MAX_TURNS,
                    "A execução atingiu o número máximo de turnos antes de concluir." + suffix(detail), display);
        }
        if (subtype.contains("budget") || lower.contains("budget limit")) {
            return RunOutcome.failure(exitCode, FailureCodes.BUDGET_EXCEEDED,
                    "A execução atingiu o orçamento máximo configurado e foi encerrada pelo Claude Code." + suffix(detail), display);
        }
        if (lower.contains("no conversation found") || lower.contains("session not found")) {
            return RunOutcome.failure(exitCode, FailureCodes.SESSION_NOT_FOUND,
                    "O Claude Code não encontrou a conversa anterior desta sessão. Reinicie o contexto da sessão e envie de novo." + suffix(detail), display);
        }
        if (result == null && cleanExit) {
            return RunOutcome.failure(exitCode, FailureCodes.NO_RESULT,
                    "O Claude Code terminou sem informar um resultado." + suffix(detail), display);
        }
        return RunOutcome.failure(exitCode, FailureCodes.CLI_ERROR,
                "O Claude Code terminou com erro" + (exitCode == null ? "" : " (código " + exitCode + ")") + "." + suffix(detail), display);
    }

    private static String suffix(String detail) {
        String clean = Texts.trimToNull(detail);
        return clean == null ? "" : " Detalhe: " + Texts.truncate(clean, 1_200);
    }

    private static void closeQuietly(Process process) {
        try {
            process.getInputStream().close();
            process.getErrorStream().close();
        } catch (IOException ignored) {
            // nada a fazer
        }
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // arquivo temporário; a limpeza da pasta tmp na inicialização cobre o resto
            }
        }
    }
}
