package dev.squaddesktop.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.squaddesktop.runs.AgentEvent;
import dev.squaddesktop.testsupport.TestJson;

/** Usa saídas reais do Claude Code 2.1.293 (anonimizadas) em src/test/resources/fixtures/claude. */
class StreamJsonParserTest {

    @Test
    void respostaSimplesComTextoParcialEUso() throws IOException {
        StreamJsonParser parser = new StreamJsonParser(TestJson.CODEC, false);
        List<AgentEvent> events = parseFixture(parser, "texto-simples.jsonl");

        assertThat(events.get(0)).isInstanceOf(AgentEvent.Started.class);
        AgentEvent.Started started = (AgentEvent.Started) events.get(0);
        assertThat(started.claudeSessionId()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(started.model()).isEqualTo("claude-haiku-5-5");
        assertThat(started.cliVersion()).isEqualTo("2.1.293");

        assertThat(events).filteredOn(AgentEvent.TextDelta.class::isInstance).extracting(e -> ((AgentEvent.TextDelta) e).text())
                .containsExactly("pronto");
        assertThat(events).filteredOn(AgentEvent.Message.class::isInstance).extracting(e -> ((AgentEvent.Message) e).text())
                .containsExactly("pronto");
        AgentEvent.Usage usage = (AgentEvent.Usage) events.stream().filter(AgentEvent.Usage.class::isInstance).findFirst().orElseThrow();
        assertThat(usage.costUsd()).isPositive();
        assertThat(usage.inputTokens()).isEqualTo(2L);
        assertThat(usage.outputTokens()).isEqualTo(5L);
        assertThat(usage.numTurns()).isEqualTo(1);

        assertThat(parser.result()).isNotNull();
        assertThat(parser.result().error()).isFalse();
        assertThat(parser.result().resultText()).isEqualTo("pronto");
        assertThat(parser.denials()).isEmpty();
    }

    @Test
    void ferramentaResultadoENegacaoSemDuplicar() throws IOException {
        StreamJsonParser parser = new StreamJsonParser(TestJson.CODEC, false);
        List<AgentEvent> events = parseFixture(parser, "ferramenta-e-negacao.jsonl");

        List<AgentEvent.ToolCall> calls = events.stream().filter(AgentEvent.ToolCall.class::isInstance).map(AgentEvent.ToolCall.class::cast).toList();
        assertThat(calls).extracting(AgentEvent.ToolCall::name).containsExactly("Read", "Write");
        assertThat(calls.get(0).input()).containsKey("file_path");

        List<AgentEvent.ToolResult> results = events.stream().filter(AgentEvent.ToolResult.class::isInstance).map(AgentEvent.ToolResult.class::cast).toList();
        assertThat(results).hasSize(2);
        assertThat(results.get(0).name()).isEqualTo("Read");
        assertThat(results.get(0).content()).contains("Arquivo de exemplo");
        assertThat(results.get(0).error()).isFalse();
        assertThat(results.get(1).error()).isTrue();

        List<AgentEvent.PermissionDenied> denials = events.stream().filter(AgentEvent.PermissionDenied.class::isInstance)
                .map(AgentEvent.PermissionDenied.class::cast).toList();
        assertThat(denials).hasSize(1);
        assertThat(denials.get(0).toolName()).isEqualTo("Write");
        assertThat(denials.get(0).input()).containsEntry("content", "ok");
        assertThat(denials.get(0).suggestedRule()).isEqualTo("Write");
        assertThat(parser.denials()).hasSize(1);

        assertThat(events).filteredOn(AgentEvent.Thinking.class::isInstance).isNotEmpty();
        assertThat(parser.result().numTurns()).isEqualTo(3);
    }

    @Test
    void linhasDesconhecidasSaoIgnoradasELixoViraConsole() {
        StreamJsonParser parser = new StreamJsonParser(TestJson.CODEC, false);
        assertThat(parser.accept("{\"type\":\"tipo_que_ainda_nao_existe\",\"x\":1}")).isEmpty();
        assertThat(parser.accept("")).isEmpty();
        List<AgentEvent> out = parser.accept("Aviso: algo aconteceu");
        assertThat(out).singleElement().isInstanceOf(AgentEvent.Console.class);
    }

    @Test
    void naoInventaCustoQuandoAusente() {
        StreamJsonParser parser = new StreamJsonParser(TestJson.CODEC, false);
        List<AgentEvent> out = parser.accept("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\"}");
        assertThat(out).noneMatch(AgentEvent.Usage.class::isInstance);
        assertThat(parser.result().costUsd()).isNull();
    }

    @Test
    void resultadoDeFerramentaGrandeETruncado() {
        StreamJsonParser parser = new StreamJsonParser(TestJson.CODEC, false);
        String big = "x".repeat(StreamJsonParser.MAX_TOOL_RESULT_CHARS + 50);
        List<AgentEvent> out = parser.accept("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":[{\"type\":\"text\",\"text\":\"" + big + "\"}]}]}}");
        AgentEvent.ToolResult result = (AgentEvent.ToolResult) out.get(0);
        assertThat(result.truncated()).isTrue();
        assertThat(result.content()).hasSize(StreamJsonParser.MAX_TOOL_RESULT_CHARS);
    }

    @Test
    void textoDeSubagenteNaoEntraNaConversaPrincipal() {
        StreamJsonParser parser = new StreamJsonParser(TestJson.CODEC, false);
        assertThat(parser.accept("{\"type\":\"stream_event\",\"parent_tool_use_id\":\"pai\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"oi\"}}}")).isEmpty();
        List<AgentEvent> out = parser.accept("{\"type\":\"assistant\",\"parent_tool_use_id\":\"pai\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"relatório\"}]}}");
        assertThat(((AgentEvent.Message) out.get(0)).parentToolUseId()).isEqualTo("pai");
    }

    private static List<AgentEvent> parseFixture(StreamJsonParser parser, String name) throws IOException {
        List<AgentEvent> events = new ArrayList<>();
        try (InputStream in = StreamJsonParserTest.class.getResourceAsStream("/fixtures/claude/" + name)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                events.addAll(parser.accept(line));
            }
        }
        return events;
    }
}
