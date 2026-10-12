package dev.squaddesktop.agents;

/** Modelo editável oferecido pelo Estúdio do Agente. Criar um agente a partir dele copia os campos de {@link #draft}. */
public record AgentTemplate(String key, String label, String summary, AgentDraft draft) {
}
