package dev.squaddesktop.features;

import java.util.Map;

/**
 * Uma etapa de execução: um agente trabalhando na feature, com a passagem que ele deixou.
 *
 * @param handoff passagem estruturada (objetivo, contexto, entregas, arquivos, pendências, critérios de validação,
 *                testes); null enquanto a etapa roda
 */
public record FeatureStep(String id, String featureId, int seq, int revision, String agentId, String sessionId, String runId, String reason,
        String status, Map<String, Object> handoff, String createdAt, String updatedAt) {
}
