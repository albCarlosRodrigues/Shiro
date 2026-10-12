package dev.squaddesktop.runs;

/**
 * Evento como foi gravado e é transmitido.
 *
 * @param id      cursor crescente; 0 para eventos transitórios, que não são gravados nem reenviados
 * @param payload dados do evento em JSON
 */
public record StoredEvent(long id, String runId, String sessionId, String type, String payload, String createdAt) {
}
