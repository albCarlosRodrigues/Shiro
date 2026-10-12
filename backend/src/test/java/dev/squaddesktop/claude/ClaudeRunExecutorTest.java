package dev.squaddesktop.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.runs.AgentEvent;
import dev.squaddesktop.runs.FailureCodes;
import dev.squaddesktop.runs.RunControl;
import dev.squaddesktop.runs.RunKind;
import dev.squaddesktop.runs.RunOutcome;
import dev.squaddesktop.runs.RunSpec;
import dev.squaddesktop.settings.SettingsService;
import dev.squaddesktop.testsupport.FakeClaudeInstall;
import dev.squaddesktop.testsupport.TestDb;
import dev.squaddesktop.testsupport.TestJson;

/** Executa o adaptador real contra a CLI falsa ({@link dev.squaddesktop.testsupport.FakeClaude}): processos de verdade, sem custo. */
class ClaudeRunExecutorTest {

    @TempDir
    Path dir;

    private Path workDir;
    private Path argsFile;
    private Path pidFile;
    private ClaudeRunExecutor executor;
    private Database database;
    private final List<AgentEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        workDir = Files.createDirectories(dir.resolve("projeto com espaço e acentuação"));
        argsFile = dir.resolve("args.txt");
        pidFile = dir.resolve("pids.txt");
        database = TestDb.create(dir);
        SettingsService settings = new SettingsService(database, Clock.systemUTC());
        ClaudeEnvironment environment = new ClaudeEnvironment(path -> Optional.of(FakeClaudeInstall.installation()), settings,
                TestJson.CODEC, Clock.systemUTC());
        ProcessLauncher launcher = (command, cwd) -> {
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
            builder.environment().putAll(Map.of("FAKE_ARGS_FILE", argsFile.toString(), "FAKE_PID_FILE", pidFile.toString()));
            return builder.start();
        };
        executor = new ClaudeRunExecutor(environment, TestJson.CODEC, launcher, dir.resolve("tmp"));
    }

    /** Fecha o banco antes de a pasta temporária ser apagada (no Windows, arquivo aberto não pode ser apagado). */
    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void sucessoTransmiteEventosEUsaPromptPelaEntradaPadrao() throws Exception {
        RunOutcome outcome = executor.execute(spec("Olá, agente"), events::add, new RunControl());
        assertThat(outcome.failureCode()).isNull();
        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.resultText()).isEqualTo("Olá, tudo certo.");
        assertThat(events).anyMatch(AgentEvent.Started.class::isInstance);
        assertThat(events).filteredOn(AgentEvent.TextDelta.class::isInstance).hasSize(3);
        assertThat(events).filteredOn(AgentEvent.Usage.class::isInstance).singleElement()
                .extracting(e -> ((AgentEvent.Usage) e).costUsd()).isEqualTo(0.0042);
        List<String> args = Files.readAllLines(argsFile);
        assertThat(args).doesNotContain("Olá, agente");
        assertThat(args).containsSubsequence("--permission-mode", "acceptEdits");
        assertThat(args).contains("--append-system-prompt-file");
        assertThat(Files.list(dir.resolve("tmp")).count()).as("arquivo temporário de instruções removido").isZero();
    }

    @Test
    void falhaDaCliViraCodigoEstavelComDetalhe() {
        RunOutcome outcome = executor.execute(spec("FALHA por favor"), events::add, new RunControl());
        assertThat(outcome.failureCode()).isEqualTo(FailureCodes.CLI_ERROR);
        assertThat(outcome.failureMessage()).contains("falha simulada");
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.Console c && c.stream().equals("stderr"));
    }

    @Test
    void falhaDeLoginEhIdentificada() {
        RunOutcome outcome = executor.execute(spec("LOGIN"), events::add, new RunControl());
        assertThat(outcome.failureCode()).isEqualTo(FailureCodes.NOT_AUTHENTICATED);
    }

    @Test
    void linhasQueNaoSaoJsonVaoParaOConsole() {
        RunOutcome outcome = executor.execute(spec("LIXO"), events::add, new RunControl());
        assertThat(outcome.failureCode()).isNull();
        assertThat(events).anyMatch(e -> e instanceof AgentEvent.Console c && c.text().equals("isto não é json"));
    }

    @Test
    void negacaoDePermissaoSugereRegra() {
        executor.execute(spec("NEGAR"), events::add, new RunControl());
        AgentEvent.PermissionDenied denied = events.stream().filter(AgentEvent.PermissionDenied.class::isInstance)
                .map(AgentEvent.PermissionDenied.class::cast).findFirst().orElseThrow();
        assertThat(denied.suggestedRule()).isEqualTo("Bash(npm run *)");
        assertThat(events).filteredOn(AgentEvent.PermissionDenied.class::isInstance).hasSize(1);
    }

    @Test
    @Timeout(30)
    void processoTravadoEstouraOTempo() {
        RunSpec spec = new RunSpec("run-t", RunKind.EXECUTION, workDir, "TRAVAR", "instruções", null, null, "acceptEdits", null,
                List.of(), List.of(), null, "s", null, Duration.ofSeconds(2), "Agente");
        long started = System.nanoTime();
        RunOutcome outcome = executor.execute(spec, events::add, new RunControl());
        assertThat(outcome.timedOut()).isTrue();
        assertThat(outcome.failureCode()).isEqualTo(FailureCodes.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    @Timeout(40)
    void cancelarEncerraOProcessoEOsNetos() throws Exception {
        RunControl control = new RunControl();
        Thread canceller = Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < 200 && (!Files.exists(pidFile) || Files.readAllLines(pidFile).size() < 2); i++) {
                    Thread.sleep(50);
                }
                control.cancel();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        long started = System.nanoTime();
        RunOutcome outcome = executor.execute(spec("NETO"), events::add, control);
        canceller.join();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
        assertThat(outcome.timedOut()).isFalse();
        List<String> pids = Files.readAllLines(pidFile);
        for (String pid : pids) {
            Optional<ProcessHandle> handle = ProcessHandle.of(Long.parseLong(pid.strip()));
            if (handle.isPresent()) {
                handle.get().onExit().get(10, TimeUnit.SECONDS);
            }
            assertThat(ProcessHandle.of(Long.parseLong(pid.strip())).map(ProcessHandle::isAlive).orElse(false))
                    .as("processo " + pid + " encerrado").isFalse();
        }
    }

    @Test
    void pastaInexistenteNaoIniciaProcesso() {
        RunSpec spec = new RunSpec("run-x", RunKind.EXECUTION, dir.resolve("nao-existe"), "oi", "", null, null, null, null,
                List.of(), List.of(), null, "s", null, Duration.ofMinutes(1), "Agente");
        RunOutcome outcome = executor.execute(spec, events::add, new RunControl());
        assertThat(outcome.failureCode()).isEqualTo(FailureCodes.FOLDER_MISSING);
        assertThat(argsFile).doesNotExist();
    }

    private RunSpec spec(String prompt) {
        return new RunSpec("run-" + Math.abs(prompt.hashCode()), RunKind.EXECUTION, workDir, prompt, "Você é um agente de teste.",
                null, null, "acceptEdits", null, List.of("Bash(npm run *)"), List.of(), null, "11111111-1111-1111-1111-111111111111",
                null, Duration.ofSeconds(30), "Agente");
    }
}
