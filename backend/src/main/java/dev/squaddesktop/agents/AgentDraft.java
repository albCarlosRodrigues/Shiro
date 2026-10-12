package dev.squaddesktop.agents;

import java.util.List;

/** Campos editáveis no Estúdio do Agente. */
public record AgentDraft(
        String name,
        AgentRole role,
        String specialty,
        String icon,
        String color,
        String responsibility,
        String personality,
        String instructions,
        String guidelines,
        String model,
        String effort,
        String permissionMode,
        List<String> tools,
        List<String> allowedTools,
        List<String> disallowedTools,
        String templateKey) {
}
