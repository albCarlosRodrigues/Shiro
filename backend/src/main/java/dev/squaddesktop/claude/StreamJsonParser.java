package dev.squaddesktop.claude;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.squaddesktop.runs.AgentEvent;
import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.shared.JsonValues;
import dev.squaddesktop.shared.Texts;

/**
 * Converte a saída {@code --output-format stream-json} do Claude Code (uma linha JSON por evento) no
 * contrato interno de eventos. Uma instância por execução: guarda o que precisa para correlacionar
 * chamadas de ferramenta, resultados e negações.
 *
 * <p>É tolerante por desenho: tipos de linha desconhecidos são ignorados e linhas que não são JSON viram
 * saída de console, porque o formato ganha tipos novos a cada versão da CLI.
 */
public final class StreamJsonParser {

    /** Resumo da linha {@code result}. Campos nulos não foram informados pela CLI. */
    public record ResultInfo(String subtype, boolean error, String resultText, Double costUsd, Long inputTokens,
            Long outputTokens, Long cacheReadTokens, Long cacheCreationTokens, Integer numTurns, Long durationMs,
            Integer apiErrorStatus, String terminalReason) {
    }

    static final int MAX_TOOL_RESULT_CHARS = 8_000;
    static final int MAX_CONSOLE_CHARS = 4_000;

    private final JsonCodec json;
    private final boolean resumed;
    private final Map<String, String> toolNames = new HashMap<>();
    private final Map<String, Map<String, Object>> toolInputs = new HashMap<>();
    private final Set<String> reportedDenials = new HashSet<>();
    private final List<Map<String, Object>> denials = new ArrayList<>();
    private String sessionId;
    private String assistantError;
    private ResultInfo result;

    public StreamJsonParser(JsonCodec json, boolean resumed) {
        this.json = json;
        this.resumed = resumed;
    }

    public String sessionId() {
        return sessionId;
    }

    public ResultInfo result() {
        return result;
    }

    /** Categoria de erro informada numa mensagem do assistente (por exemplo, authentication_failed), se houve. */
    public String assistantError() {
        return assistantError;
    }

    /** Negações de permissão observadas, no formato persistido em {@code run.permission_denials}. */
    public List<Map<String, Object>> denials() {
        return List.copyOf(denials);
    }

    public List<AgentEvent> accept(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        Object parsed;
        try {
            parsed = json.parse(line);
        } catch (IllegalArgumentException e) {
            return List.of(new AgentEvent.Console("stdout", Texts.truncate(line, MAX_CONSOLE_CHARS)));
        }
        if (!(parsed instanceof Map<?, ?>)) {
            return List.of(new AgentEvent.Console("stdout", Texts.truncate(line, MAX_CONSOLE_CHARS)));
        }
        String type = JsonValues.strOr(parsed, "", "type");
        return switch (type) {
            case "system" -> system(parsed);
            case "stream_event" -> streamEvent(parsed);
            case "assistant" -> assistant(parsed);
            case "user" -> user(parsed);
            case "result" -> result(parsed);
            default -> List.of();
        };
    }

    private List<AgentEvent> system(Object event) {
        String subtype = JsonValues.strOr(event, "", "subtype");
        switch (subtype) {
            case "init" -> {
                sessionId = JsonValues.str(event, "session_id");
                return List.of(new AgentEvent.Started(sessionId, JsonValues.str(event, "model"), JsonValues.str(event, "cwd"),
                        JsonValues.str(event, "permissionMode"), JsonValues.strings(event, "tools"),
                        JsonValues.str(event, "claude_code_version"), resumed));
            }
            case "permission_denied" -> {
                return denial(JsonValues.str(event, "tool_name"), JsonValues.str(event, "tool_use_id"), null,
                        JsonValues.str(event, "message"));
            }
            case "api_retry" -> {
                Integer attempt = JsonValues.intValue(event, "attempt");
                Integer max = JsonValues.intValue(event, "max_retries");
                String error = JsonValues.strOr(event, "unknown", "error");
                return List.of(new AgentEvent.Notice("warning", "api_retry",
                        "A API falhou (" + error + "); nova tentativa " + (attempt == null ? "?" : attempt)
                                + (max == null ? "" : " de " + max) + "."));
            }
            case "thinking_tokens" -> {
                return List.of(new AgentEvent.Thinking(JsonValues.intValue(event, "estimated_tokens")));
            }
            case "compact_boundary" -> {
                return List.of(new AgentEvent.Notice("info", "compacted", "O contexto da conversa foi compactado pelo Claude Code."));
            }
            default -> {
                return List.of();
            }
        }
    }

    private List<AgentEvent> streamEvent(Object event) {
        if (JsonValues.str(event, "parent_tool_use_id") != null) {
            return List.of();
        }
        if (!"content_block_delta".equals(JsonValues.str(event, "event", "type"))) {
            return List.of();
        }
        if ("text_delta".equals(JsonValues.str(event, "event", "delta", "type"))) {
            String text = JsonValues.str(event, "event", "delta", "text");
            return text == null || text.isEmpty() ? List.of() : List.of(new AgentEvent.TextDelta(text));
        }
        return List.of();
    }

    private List<AgentEvent> assistant(Object event) {
        String parent = JsonValues.str(event, "parent_tool_use_id");
        String error = JsonValues.str(event, "error");
        if (error != null) {
            assistantError = error;
        }
        List<AgentEvent> out = new ArrayList<>();
        for (Object block : JsonValues.list(event, "message", "content")) {
            String blockType = JsonValues.strOr(block, "", "type");
            if ("text".equals(blockType)) {
                String text = JsonValues.str(block, "text");
                if (text != null && !text.isBlank()) {
                    out.add(new AgentEvent.Message(text, parent, null));
                }
            } else if ("tool_use".equals(blockType)) {
                String id = JsonValues.str(block, "id");
                String name = JsonValues.strOr(block, "ferramenta", "name");
                Map<String, Object> input = JsonValues.map(block, "input");
                if (id != null) {
                    toolNames.put(id, name);
                    toolInputs.put(id, input == null ? Map.of() : input);
                }
                out.add(new AgentEvent.ToolCall(id, name, input == null ? Map.of() : input, parent));
            }
        }
        return out;
    }

    private List<AgentEvent> user(Object event) {
        List<AgentEvent> out = new ArrayList<>();
        for (Object block : JsonValues.list(event, "message", "content")) {
            if (!"tool_result".equals(JsonValues.str(block, "type"))) {
                continue;
            }
            String id = JsonValues.str(block, "tool_use_id");
            String content = textOf(JsonValues.at(block, "content"));
            boolean truncated = content.length() > MAX_TOOL_RESULT_CHARS;
            out.add(new AgentEvent.ToolResult(id, id == null ? null : toolNames.get(id),
                    truncated ? content.substring(0, MAX_TOOL_RESULT_CHARS) : content, JsonValues.bool(block, "is_error"), truncated));
        }
        return out;
    }

    private List<AgentEvent> result(Object event) {
        if (sessionId == null) {
            sessionId = JsonValues.str(event, "session_id");
        }
        result = new ResultInfo(
                JsonValues.str(event, "subtype"),
                JsonValues.bool(event, "is_error"),
                JsonValues.str(event, "result"),
                JsonValues.doubleValue(event, "total_cost_usd"),
                JsonValues.longValue(event, "usage", "input_tokens"),
                JsonValues.longValue(event, "usage", "output_tokens"),
                JsonValues.longValue(event, "usage", "cache_read_input_tokens"),
                JsonValues.longValue(event, "usage", "cache_creation_input_tokens"),
                JsonValues.intValue(event, "num_turns"),
                JsonValues.longValue(event, "duration_ms"),
                JsonValues.intValue(event, "api_error_status"),
                JsonValues.str(event, "terminal_reason"));
        List<AgentEvent> out = new ArrayList<>();
        for (Object denial : JsonValues.list(event, "permission_denials")) {
            out.addAll(denial(JsonValues.str(denial, "tool_name"), JsonValues.str(denial, "tool_use_id"),
                    JsonValues.map(denial, "tool_input"), null));
        }
        if (result.costUsd() != null || result.inputTokens() != null || result.outputTokens() != null
                || result.numTurns() != null || result.durationMs() != null) {
            out.add(new AgentEvent.Usage(result.inputTokens(), result.outputTokens(), result.cacheReadTokens(),
                    result.cacheCreationTokens(), result.costUsd(), result.numTurns(), result.durationMs()));
        }
        return out;
    }

    /** A mesma negação chega duas vezes (evento de sistema e lista final); só a primeira vira evento. */
    private List<AgentEvent> denial(String toolName, String toolUseId, Map<String, Object> input, String reason) {
        String key = toolUseId != null ? toolUseId : toolName + "#" + denials.size();
        if (!reportedDenials.add(key)) {
            return List.of();
        }
        String name = toolName != null ? toolName : (toolUseId == null ? "ferramenta" : toolNames.getOrDefault(toolUseId, "ferramenta"));
        Map<String, Object> effectiveInput = input != null ? input : (toolUseId == null ? Map.of() : toolInputs.getOrDefault(toolUseId, Map.of()));
        String rule = PermissionRules.suggest(name, effectiveInput);
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("toolName", name);
        if (toolUseId != null) {
            record.put("toolUseId", toolUseId);
        }
        record.put("input", effectiveInput);
        if (rule != null) {
            record.put("suggestedRule", rule);
        }
        denials.add(record);
        return List.of(new AgentEvent.PermissionDenied(name, toolUseId, effectiveInput, Texts.truncate(reason, 600), rule));
    }

    private static String textOf(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> blocks) {
            StringBuilder out = new StringBuilder();
            for (Object block : blocks) {
                String text = JsonValues.str(block, "text");
                if (text != null) {
                    if (!out.isEmpty()) {
                        out.append('\n');
                    }
                    out.append(text);
                } else if (JsonValues.str(block, "type") != null) {
                    out.append('[').append(JsonValues.str(block, "type")).append(']');
                }
            }
            return out.toString();
        }
        return "";
    }
}
