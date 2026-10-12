package dev.squaddesktop.claude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.shared.Texts;

/**
 * Teste de conexão pedido pela pessoa: uma chamada real e mínima ao Claude Code, sem ferramentas e com um
 * único turno, para confirmar que a autenticação funciona de ponta a ponta. Consome uma fração mínima da cota.
 */
public final class ConnectionTest {

    public record Result(boolean ok, String message, String model, Double costUsd, long elapsedMs) {
    }

    private ConnectionTest() {
    }

    public static Result run(ClaudeEnvironment environment, JsonCodec json, Path workDir) {
        ClaudeEnvironment.Snapshot snapshot = environment.snapshot();
        if (!snapshot.status().usable()) {
            return new Result(false, snapshot.status().problem(), null, null, 0);
        }
        long started = System.nanoTime();
        List<String> command = new ArrayList<>(snapshot.installation().launcher());
        command.addAll(List.of("-p", "--output-format", "stream-json", "--verbose", "--max-turns", "1", "--tools", ""));
        if (snapshot.status().capabilities().permissionPrompts()) {
            command.addAll(List.of("--permission-prompts", "none"));
        }
        command.addAll(List.of("--permission-mode", "dontAsk"));
        try {
            Files.createDirectories(workDir);
            ProcessBuilder builder = new ProcessBuilder(command).directory(workDir.toFile());
            Process process = builder.start();
            try (var in = process.getOutputStream()) {
                in.write("Responda apenas com a palavra: ok".getBytes(StandardCharsets.UTF_8));
            }
            StreamJsonParser parser = new StreamJsonParser(json, false);
            StringBuilder errors = new StringBuilder();
            Thread err = Thread.ofVirtual().start(() -> {
                try {
                    errors.append(new String(process.getErrorStream().readNBytes(16_000), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // processo encerrado
                }
            });
            String model = null;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    for (var event : parser.accept(line)) {
                        if (event instanceof dev.squaddesktop.runs.AgentEvent.Started s) {
                            model = s.model();
                        }
                    }
                }
            }
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                ProcessTrees.kill(process);
                return new Result(false, "O Claude Code não respondeu em 90 segundos.", model, null, elapsed(started));
            }
            err.join(Duration.ofSeconds(2));
            var outcome = ClaudeRunExecutor.classify(process.exitValue(), parser, Texts.tail(errors.toString(), 2_000), List.of());
            var result = parser.result();
            if (outcome.failureCode() != null) {
                return new Result(false, outcome.failureMessage(), model, result == null ? null : result.costUsd(), elapsed(started));
            }
            return new Result(true, "Conexão confirmada: o Claude Code respondeu.", model, result == null ? null : result.costUsd(), elapsed(started));
        } catch (IOException e) {
            return new Result(false, "Não foi possível iniciar o Claude Code: " + e.getMessage(), null, null, elapsed(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "Teste interrompido.", null, null, elapsed(started));
        }
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
