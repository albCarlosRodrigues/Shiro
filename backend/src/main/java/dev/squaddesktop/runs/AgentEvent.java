package dev.squaddesktop.runs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contrato interno de eventos de uma execução. É o que o backend persiste e transmite à interface,
 * independentemente do formato que o Claude Code usa na saída (ver docs/contrato-de-eventos.md).
 *
 * <p>Campos opcionais ausentes na origem ficam de fora do evento: nenhum valor é inventado.
 */
public sealed interface AgentEvent {

    /** Nome estável do tipo, usado no SSE e na coluna {@code run_event.type}. */
    String type();

    /** Dados do evento em tipos simples, prontos para virar JSON. */
    Map<String, Object> data();

    /** A execução entrou na fila. */
    record Queued(String kind, String access, boolean demo) implements AgentEvent {
        public String type() {
            return "run.queued";
        }

        public Map<String, Object> data() {
            return map("kind", kind, "access", access, "demo", demo);
        }
    }

    /** O processo iniciou e o Claude Code informou a sessão. */
    record Started(String claudeSessionId, String model, String cwd, String permissionMode, List<String> tools,
            String cliVersion, boolean resumed) implements AgentEvent {
        public String type() {
            return "run.started";
        }

        public Map<String, Object> data() {
            return map("claudeSessionId", claudeSessionId, "model", model, "cwd", cwd, "permissionMode", permissionMode,
                    "tools", tools, "cliVersion", cliVersion, "resumed", resumed);
        }
    }

    /** Trecho incremental do texto que o agente está escrevendo. */
    record TextDelta(String text) implements AgentEvent {
        public String type() {
            return "text.delta";
        }

        public Map<String, Object> data() {
            return map("text", text);
        }
    }

    /** O agente está raciocinando; não carrega o conteúdo do raciocínio. */
    record Thinking(Integer estimatedTokens) implements AgentEvent {
        public String type() {
            return "thinking";
        }

        public Map<String, Object> data() {
            return map("estimatedTokens", estimatedTokens);
        }
    }

    /** Mensagem completa do agente. {@code messageId} é o id da mensagem gravada na conversa, quando há. */
    record Message(String text, String parentToolUseId, String messageId) implements AgentEvent {
        public String type() {
            return "message";
        }

        public Map<String, Object> data() {
            return map("text", text, "parentToolUseId", parentToolUseId, "messageId", messageId);
        }

        public Message withMessageId(String id) {
            return new Message(text, parentToolUseId, id);
        }
    }

    record ToolCall(String toolUseId, String name, Map<String, Object> input, String parentToolUseId) implements AgentEvent {
        public String type() {
            return "tool.call";
        }

        public Map<String, Object> data() {
            return map("toolUseId", toolUseId, "name", name, "input", input, "parentToolUseId", parentToolUseId);
        }
    }

    record ToolResult(String toolUseId, String name, String content, boolean error, boolean truncated) implements AgentEvent {
        public String type() {
            return "tool.result";
        }

        public Map<String, Object> data() {
            return map("toolUseId", toolUseId, "name", name, "content", content, "error", error, "truncated", truncated);
        }
    }

    /** O Claude Code negou o uso de uma ferramenta por falta de permissão. */
    record PermissionDenied(String toolName, String toolUseId, Map<String, Object> input, String reason,
            String suggestedRule) implements AgentEvent {
        public String type() {
            return "permission.denied";
        }

        public Map<String, Object> data() {
            return map("toolName", toolName, "toolUseId", toolUseId, "input", input, "reason", reason, "suggestedRule", suggestedRule);
        }
    }

    /** Consumo informado pelo Claude Code ao fim da execução. Só inclui o que ele reportou. */
    record Usage(Long inputTokens, Long outputTokens, Long cacheReadTokens, Long cacheCreationTokens, Double costUsd,
            Integer numTurns, Long durationMs) implements AgentEvent {
        public String type() {
            return "usage";
        }

        public Map<String, Object> data() {
            return map("inputTokens", inputTokens, "outputTokens", outputTokens, "cacheReadTokens", cacheReadTokens,
                    "cacheCreationTokens", cacheCreationTokens, "costUsd", costUsd, "numTurns", numTurns, "durationMs", durationMs);
        }
    }

    /** Aviso relevante para quem acompanha a execução (nova tentativa de API, recurso ignorado, limite atingido). */
    record Notice(String level, String code, String text) implements AgentEvent {
        public String type() {
            return "notice";
        }

        public Map<String, Object> data() {
            return map("level", level, "code", code, "text", text);
        }
    }

    /** Linha bruta para a aba Console: stderr, linha não reconhecida do stdout ou o comando executado. */
    record Console(String stream, String text) implements AgentEvent {
        public String type() {
            return "console";
        }

        public Map<String, Object> data() {
            return map("stream", stream, "text", text);
        }
    }

    /** Fim da execução. {@code status} é o estado final ({@link RunStatus}). */
    record Finished(String status, String resultText, Integer exitCode, String failureCode, String failureMessage,
            int permissionDenials) implements AgentEvent {
        public String type() {
            return switch (status) {
                case "COMPLETED" -> "run.finished";
                case "CANCELLED" -> "run.cancelled";
                case "INTERRUPTED" -> "run.interrupted";
                default -> "run.failed";
            };
        }

        public Map<String, Object> data() {
            return map("status", status, "resultText", resultText, "exitCode", exitCode, "failureCode", failureCode,
                    "failureMessage", failureMessage, "permissionDenials", permissionDenials);
        }
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                out.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return out;
    }
}
