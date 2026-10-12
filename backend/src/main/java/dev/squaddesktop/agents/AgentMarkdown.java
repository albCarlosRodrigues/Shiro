package dev.squaddesktop.agents;

import java.util.List;

import dev.squaddesktop.shared.Texts;

/**
 * Exporta a definição do agente em Markdown com cabeçalho YAML, no formato dos subagentes do
 * Claude Code ({@code .claude/agents/<nome>.md}), para reaproveitamento fora do aplicativo.
 */
public final class AgentMarkdown {

    private AgentMarkdown() {
    }

    public static String export(Agent agent) {
        StringBuilder out = new StringBuilder();
        out.append("---\n");
        out.append("name: ").append(slug(agent.name())).append('\n');
        out.append("description: ").append(yamlString(describe(agent))).append('\n');
        if (agent.tools() != null && !agent.tools().isEmpty()) {
            out.append("tools: ").append(String.join(", ", agent.tools())).append('\n');
        }
        if (agent.model() != null) {
            out.append("model: ").append(agent.model()).append('\n');
        }
        if (agent.effort() != null) {
            out.append("effort: ").append(agent.effort()).append('\n');
        }
        if (agent.permissionMode() != null) {
            out.append("permissionMode: ").append(agent.permissionMode()).append('\n');
        }
        out.append("---\n\n");
        out.append(InstructionComposer.compose(agent, null, List.of()).strip()).append('\n');
        if (!agent.allowedTools().isEmpty() || !agent.disallowedTools().isEmpty()) {
            out.append("\n<!-- Permissões configuradas no Squad Desktop\n");
            agent.allowedTools().forEach(rule -> out.append("permitir: ").append(rule).append('\n'));
            agent.disallowedTools().forEach(rule -> out.append("negar: ").append(rule).append('\n'));
            out.append("-->\n");
        }
        return out.toString();
    }

    public static String fileName(Agent agent) {
        return slug(agent.name()) + ".md";
    }

    static String slug(String name) {
        String normalized = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = normalized.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return slug.isEmpty() ? "agente" : slug;
    }

    private static String describe(Agent agent) {
        String base = Texts.isBlank(agent.responsibility()) ? agent.role().label() : agent.responsibility().strip();
        return Texts.truncate(base.replaceAll("\\s+", " "), 300);
    }

    private static String yamlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
