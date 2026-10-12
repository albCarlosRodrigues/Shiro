package dev.squaddesktop.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ClaudeCapabilitiesTest {

    @Test
    void detectaRecursosPelaAjudaReal() throws IOException {
        ClaudeCapabilities caps = ClaudeCapabilities.detect("2.1.263 (Claude Code)", help("help-2.1.263.txt"));
        assertThat(caps.version()).isEqualTo("2.1.263");
        assertThat(caps.partialMessages()).isTrue();
        assertThat(caps.permissionPrompts()).isTrue();
        assertThat(caps.effort()).isTrue();
        assertThat(caps.maxBudget()).isTrue();
        assertThat(caps.sessionId()).isTrue();
        assertThat(caps.resume()).isTrue();
        assertThat(caps.toolsFlag()).isTrue();
        assertThat(caps.systemPromptSnapshot()).isTrue();
        assertThat(caps.effortLevels()).containsExactly("low", "medium", "high", "xhigh", "max");
        assertThat(caps.modelAliases()).contains("opus", "sonnet").doesNotContain("claude");
        assertThat(caps.permissionModes()).contains("acceptEdits", "dontAsk", "plan", "manual");
    }

    @Test
    void ajudaDeOutraVersaoTambemFunciona() throws IOException {
        ClaudeCapabilities caps = ClaudeCapabilities.detect("2.1.293 (Claude Code)", help("help-2.1.293.txt"));
        assertThat(caps.permissionPrompts()).isTrue();
        assertThat(caps.effortLevels()).contains("high");
    }

    @Test
    void semAjudaUsaVersaoMinimaDocumentada() {
        assertThat(ClaudeCapabilities.detect("2.1.300", "").permissionPrompts()).isTrue();
        assertThat(ClaudeCapabilities.detect("2.1.200", "").permissionPrompts()).isFalse();
        assertThat(ClaudeCapabilities.detect(null, "")).isEqualTo(ClaudeCapabilities.MINIMAL);
    }

    @Test
    void comparaVersoes() {
        assertThat(ClaudeCapabilities.atLeast("2.1.259", 2, 1, 259)).isTrue();
        assertThat(ClaudeCapabilities.atLeast("2.1.258", 2, 1, 259)).isFalse();
        assertThat(ClaudeCapabilities.atLeast("3.0.0", 2, 9, 9)).isTrue();
        assertThat(ClaudeCapabilities.parseVersion("Claude Code v2.10.3 beta")).isEqualTo("2.10.3");
    }

    private static String help(String name) throws IOException {
        try (InputStream in = ClaudeCapabilitiesTest.class.getResourceAsStream("/fixtures/claude/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
