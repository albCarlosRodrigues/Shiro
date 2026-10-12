package dev.squaddesktop.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeLocatorTest {

    @TempDir
    Path dir;

    @Test
    void encontraNoPathComEspacosEAcentos() throws Exception {
        Path bin = Files.createDirectories(dir.resolve("Programas Úteis").resolve("bin"));
        Path claude = Files.createFile(bin.resolve("claude"));
        ClaudeLocator locator = new ClaudeLocator(Map.of("PATH", "/nao/existe" + File.pathSeparator + bin), dir, false);
        ClaudeInstallation found = locator.locate(null).orElseThrow();
        assertThat(found.executable()).isEqualTo(claude.toString());
        assertThat(found.launcher()).containsExactly(claude.toString());
        assertThat(found.source()).isEqualTo("PATH");
        assertThat(found.usable()).isTrue();
    }

    @Test
    void caminhoConfiguradoInexistenteViraProblemaEmVezDeTroca() throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Files.createFile(bin.resolve("claude"));
        ClaudeLocator locator = new ClaudeLocator(Map.of("PATH", bin.toString()), dir, false);
        ClaudeInstallation found = locator.locate(dir.resolve("errado").toString()).orElseThrow();
        assertThat(found.usable()).isFalse();
        assertThat(found.problem()).contains("não aponta");
    }

    @Test
    void atalhoDoNpmNoWindowsViraNodeMaisCli() throws Exception {
        Path npm = Files.createDirectories(dir.resolve("npm"));
        Files.createFile(npm.resolve("claude.cmd"));
        Files.createFile(npm.resolve("node.exe"));
        Path cli = Files.createDirectories(npm.resolve("node_modules/@anthropic-ai/claude-code")).resolve("cli.js");
        Files.createFile(cli);
        ClaudeLocator locator = new ClaudeLocator(Map.of("PATH", npm.toString()), dir, true);
        ClaudeInstallation found = locator.locate(null).orElseThrow();
        assertThat(found.launcher()).containsExactly(npm.resolve("node.exe").toString(), cli.toString());
    }

    @Test
    void atalhoSemCliConhecidoNaoEUsado() throws Exception {
        Path npm = Files.createDirectories(dir.resolve("npm"));
        Files.createFile(npm.resolve("claude.cmd"));
        ClaudeLocator locator = new ClaudeLocator(Map.of("PATH", npm.toString()), dir, true);
        ClaudeInstallation found = locator.locate(null).orElseThrow();
        assertThat(found.usable()).isFalse();
    }

    @Test
    void procuraNosLocaisConhecidos() throws Exception {
        Path local = Files.createDirectories(dir.resolve(".local").resolve("bin"));
        Files.createFile(local.resolve("claude"));
        ClaudeLocator locator = new ClaudeLocator(Map.of("PATH", ""), dir, false);
        assertThat(locator.locate(null)).get().extracting(ClaudeInstallation::source).isEqualTo("local conhecido");
    }

    @Test
    void semInstalacao() {
        assertThat(new ClaudeLocator(Map.of("PATH", ""), dir, false).locate(null)).isEmpty();
    }
}
