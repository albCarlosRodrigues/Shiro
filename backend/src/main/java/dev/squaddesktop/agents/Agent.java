package dev.squaddesktop.agents;

import java.util.List;

/**
 * Definição de um agente.
 *
 * @param permissionMode  modo de permissão do Claude Code; null usa o padrão do tipo de execução
 * @param tools           ferramentas nativas disponíveis; null mantém o conjunto padrão do Claude Code
 * @param allowedTools    regras liberadas sem confirmação (sintaxe de permissão do Claude Code)
 * @param disallowedTools regras negadas
 * @param sample          criado pelos dados de exemplo
 */
public record Agent(
        String id,
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
        String templateKey,
        boolean sample,
        String archivedAt,
        String createdAt,
        String updatedAt) {

    public boolean archived() {
        return archivedAt != null;
    }
}
