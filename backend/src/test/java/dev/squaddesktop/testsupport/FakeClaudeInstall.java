package dev.squaddesktop.testsupport;

import java.nio.file.Path;
import java.util.List;

import dev.squaddesktop.claude.ClaudeInstallation;

public final class FakeClaudeInstall {

    private FakeClaudeInstall() {
    }

    /**
     * Linha de comando que inicia a {@link FakeClaude} com o mesmo Java e classpath dos testes. A saída é forçada para
     * UTF-8, como a do Claude Code: no Windows, um processo com saída redirecionada usaria a página de código do sistema.
     */
    public static ClaudeInstallation installation() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ClaudeInstallation("fake-claude", List.of(java, "-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"), FakeClaude.class.getName()),
                "teste", null);
    }
}
