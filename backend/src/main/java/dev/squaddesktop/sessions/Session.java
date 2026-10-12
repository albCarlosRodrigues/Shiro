package dev.squaddesktop.sessions;

/**
 * Conversa de um agente dentro de um projeto; corresponde a um painel da área de trabalho.
 *
 * @param claudeSessionId sessão do Claude Code usada para retomar o contexto; null até a primeira execução
 * @param purpose         {@code CHAT} (aberta pela pessoa), {@code PLANNING} (vez do agente na sala de planejamento)
 *                        ou {@code FEATURE} (etapa de execução de uma feature)
 * @param featureId       feature da etapa, quando {@code purpose} é FEATURE
 */
public record Session(
        String id,
        String projectId,
        String agentId,
        String title,
        String claudeSessionId,
        int position,
        String archivedAt,
        String createdAt,
        String updatedAt,
        String purpose,
        String featureId) {

    public static final String CHAT = "CHAT";
    public static final String PLANNING = "PLANNING";
    public static final String FEATURE = "FEATURE";

    Session withTitle(String title, String updatedAt) {
        return new Session(id, projectId, agentId, title, claudeSessionId, position, archivedAt, createdAt, updatedAt, purpose, featureId);
    }

    Session withoutClaudeSession(String updatedAt) {
        return new Session(id, projectId, agentId, title, null, position, archivedAt, createdAt, updatedAt, purpose, featureId);
    }
}
