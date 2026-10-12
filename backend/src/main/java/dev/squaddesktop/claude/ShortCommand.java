package dev.squaddesktop.claude;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Roda um comando curto (versão, ajuda, estado de login) e captura a saída, com tempo limite. */
final class ShortCommand {

    record Result(boolean started, Integer exitCode, String stdout, String stderr, String error) {
        boolean ok() {
            return started && exitCode != null && exitCode == 0;
        }
    }

    private static final int MAX_BYTES = 512 * 1024;

    private ShortCommand() {
    }

    static Result run(List<String> command, Duration timeout) {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            return new Result(false, null, "", "", e.getMessage());
        }
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread outReader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), out));
        Thread errReader = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), err));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                ProcessTrees.kill(process);
                return new Result(true, null, out.toString(), err.toString(), "tempo esgotado");
            }
            outReader.join(2_000);
            errReader.join(2_000);
            return new Result(true, process.exitValue(), out.toString(), err.toString(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ProcessTrees.kill(process);
            return new Result(true, null, out.toString(), err.toString(), "interrompido");
        }
    }

    private static void drain(InputStream in, StringBuilder target) {
        try (in) {
            byte[] bytes = in.readNBytes(MAX_BYTES);
            synchronized (target) {
                target.append(new String(bytes, StandardCharsets.UTF_8));
            }
            in.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // processo encerrado no meio da leitura
        }
    }

    static java.io.File nullDevice() {
        return new java.io.File(ClaudeLocator.isWindows() ? "NUL" : "/dev/null");
    }
}
