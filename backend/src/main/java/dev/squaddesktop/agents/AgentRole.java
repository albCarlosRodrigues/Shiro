package dev.squaddesktop.agents;

/** Papel do agente dentro de uma squad. */
public enum AgentRole {
    COMMANDER("Comandante"),
    REQUIREMENTS("Requisitos e PRD"),
    ARCHITECTURE("Arquitetura e ADR"),
    OPERATOR("Operador");

    private final String label;

    AgentRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
