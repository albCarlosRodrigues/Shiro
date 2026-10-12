package dev.squaddesktop.runs;

import java.util.List;
import java.util.Map;

/**
 * Uma execução de um agente.
 *
 * @param command           argumentos usados na chamada ao Claude Code, sem o conteúdo do prompt
 * @param costUsd           estimativa informada pelo Claude Code; null quando ele não informou
 * @param permissionDenials ferramentas negadas durante a execução
 */
public record Run(
        String id,
        String sessionId,
        String projectId,
        String agentId,
        RunKind kind,
        RunAccess access,
        RunStatus status,
        boolean demo,
        String prompt,
        String cwd,
        List<String> command,
        String claudeSessionId,
        String model,
        String cliVersion,
        Integer exitCode,
        String failureCode,
        String failureMessage,
        String resultText,
        Double costUsd,
        Long inputTokens,
        Long outputTokens,
        Long cacheReadTokens,
        Long cacheCreationTokens,
        Integer numTurns,
        Long durationMs,
        List<Map<String, Object>> permissionDenials,
        String queuedAt,
        String startedAt,
        String finishedAt) {
}
