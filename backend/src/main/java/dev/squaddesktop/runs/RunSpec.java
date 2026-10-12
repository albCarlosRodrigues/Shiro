package dev.squaddesktop.runs;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Tudo o que um executor precisa para rodar uma etapa. Montado pela orquestração a partir do agente,
 * do projeto e das preferências; o executor não consulta o banco.
 *
 * @param systemPrompt     instruções finais do agente, anexadas ao prompt de sistema
 * @param tools            ferramentas nativas disponíveis; null mantém o conjunto padrão
 * @param resumeSessionId  sessão do Claude Code a retomar; null inicia uma nova com {@code newSessionId}
 * @param maxBudgetUsd     teto de gasto; null não limita
 */
public record RunSpec(
        String runId,
        RunKind kind,
        Path cwd,
        String prompt,
        String systemPrompt,
        String model,
        String effort,
        String permissionMode,
        List<String> tools,
        List<String> allowedTools,
        List<String> disallowedTools,
        String resumeSessionId,
        String newSessionId,
        Double maxBudgetUsd,
        Duration timeout,
        String agentName) {
}
