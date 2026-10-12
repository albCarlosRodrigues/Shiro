package dev.squaddesktop.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.squaddesktop.runs.RunKind;
import dev.squaddesktop.runs.RunSpec;

class ClaudeCommandBuilderTest {

    private static final ClaudeCapabilities FULL = new ClaudeCapabilities("2.1.263", true, true, true, true, true, true, true, true, true, true,
            List.of("opus", "sonnet"), List.of("low", "medium", "high"), List.of("acceptEdits", "dontAsk", "plan"));

    @Test
    void execucaoNovaComTodasAsOpcoes() {
        RunSpec spec = spec(null, "nova-sessao", List.of("Bash(npm run *)", "Bash(git diff *)"), "high", 2.5);
        Path instructions = Path.of("tmp", "instrucoes.md").toAbsolutePath();
        ClaudeCommandBuilder.Command command = ClaudeCommandBuilder.build(spec, FULL, instructions);
        assertThat(command.args()).containsSubsequence("-p", "--output-format", "stream-json", "--verbose");
        assertThat(command.args()).containsSubsequence("--permission-prompts", "none");
        assertThat(command.args()).containsSubsequence("--permission-mode", "acceptEdits");
        assertThat(command.args()).containsSubsequence("--session-id", "nova-sessao");
        assertThat(command.args()).containsSubsequence("--allowedTools", "Bash(npm run *),Bash(git diff *)");
        assertThat(command.args()).containsSubsequence("--max-budget-usd", "2.50");
        assertThat(command.args()).containsSubsequence("--effort", "high");
        assertThat(command.args()).containsSubsequence("--append-system-prompt-file", instructions.toString(), "--system-prompt-snapshot", "off");
        assertThat(command.args()).doesNotContain("--resume", "--dangerously-skip-permissions", "bypassPermissions");
        assertThat(command.skipped()).isEmpty();
    }

    @Test
    void retomadaUsaResumeEListaVaziaDeFerramentas() {
        RunSpec base = spec("sessao-anterior", null, List.of(), null, null);
        RunSpec spec = new RunSpec(base.runId(), RunKind.CHAT, base.cwd(), base.prompt(), base.systemPrompt(), null, null, "dontAsk",
                List.of(), List.of(), List.of(), "sessao-anterior", null, null, base.timeout(), "Agente");
        ClaudeCommandBuilder.Command command = ClaudeCommandBuilder.build(spec, FULL, null);
        assertThat(command.args()).containsSubsequence("--resume", "sessao-anterior");
        assertThat(command.args()).containsSubsequence("--tools", "");
        assertThat(command.args()).doesNotContain("--session-id", "--allowedTools", "--append-system-prompt-file");
    }

    @Test
    void recursoAusenteViraAvisoENaoFlag() {
        ClaudeCapabilities old = new ClaudeCapabilities("2.0.10", true, false, false, false, false, true, true, false, true, false,
                List.of(), List.of(), List.of());
        ClaudeCommandBuilder.Command command = ClaudeCommandBuilder.build(spec(null, "s", List.of(), "high", 1.0), old, null);
        assertThat(command.args()).doesNotContain("--effort", "--max-budget-usd", "--permission-prompts", "--include-partial-messages");
        assertThat(command.skipped()).hasSize(3);
    }

    @Test
    void exibicaoEscondeArquivoTemporario() {
        Path file = Path.of("tmp", "x.md").toAbsolutePath();
        List<String> display = ClaudeCommandBuilder.forDisplay(List.of("/usr/bin/claude"), List.of("-p", "--append-system-prompt-file", file.toString()), file);
        assertThat(display).containsExactly("claude", "-p", "--append-system-prompt-file", "<instruções do agente>");
        // partes do lançador que não são caminhos válidos no Windows (classpath com ";" e ":") não podem quebrar a exibição
        List<String> launcher = ClaudeCommandBuilder.forDisplay(List.of("C:\\Java\\bin\\java.exe", "-cp", "C:\\a.jar;C:\\b\\c.jar", "Main"), List.of("-p"), null);
        assertThat(launcher).containsExactly("java.exe", "-cp", "c.jar", "Main", "-p");
    }

    private static RunSpec spec(String resume, String newSession, List<String> allowed, String effort, Double budget) {
        return new RunSpec("run-1", RunKind.EXECUTION, Path.of("."), "faça", "instruções", "sonnet", effort, "acceptEdits", null, allowed,
                List.of(), resume, newSession, budget, Duration.ofMinutes(5), "Agente");
    }
}
