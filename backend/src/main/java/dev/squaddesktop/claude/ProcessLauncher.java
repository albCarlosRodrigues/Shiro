package dev.squaddesktop.claude;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Inicia o processo do Claude Code. Existe como interface para os testes trocarem a CLI por um executável controlado. */
@FunctionalInterface
public interface ProcessLauncher {

    Process start(List<String> command, Path cwd) throws IOException;

    /** Implementação real: {@link ProcessBuilder} com argumentos separados, sem shell, no diretório indicado. */
    ProcessLauncher SYSTEM = (command, cwd) -> {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(false);
        return builder.start();
    };
}
