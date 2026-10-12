package dev.squaddesktop.features;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estados de uma feature e as transições permitidas.
 *
 * <pre>
 * PLANNED ──autorizar──► QUEUED ──etapa inicia──► IN_PROGRESS ──passagem final──► IN_REVIEW ──aprovar──► APPROVED
 *    ▲                     │ ▲                        │   │                          │
 *    │                     │ └──── ajustes pedidos ───┼───┼──────────────────────────┘
 *    │                     ▼                          ▼   ▼
 *    └──── reautorizar ── CANCELLED ◄── cancelar ─────┘  FAILED ── reautorizar ──► QUEUED
 * </pre>
 *
 * Só a aprovação humana conclui a feature e libera as que dependem dela. Uma falha nunca é repetida sozinha.
 */
public enum FeatureStatus {
    PLANNED, QUEUED, IN_PROGRESS, IN_REVIEW, APPROVED, FAILED, CANCELLED;

    private static final Set<FeatureStatus> AUTHORIZABLE = EnumSet.of(PLANNED, FAILED, CANCELLED);
    private static final Set<FeatureStatus> EDITABLE = EnumSet.of(PLANNED, FAILED, CANCELLED);

    /** Pode receber autorização de execução. */
    public boolean authorizable() {
        return AUTHORIZABLE.contains(this);
    }

    /** Campos do plano (título, critérios, responsável, dependências) podem ser alterados. */
    public boolean editable() {
        return EDITABLE.contains(this);
    }

    public boolean canTransitionTo(FeatureStatus next) {
        return switch (this) {
            case PLANNED -> next == QUEUED || next == CANCELLED;
            case QUEUED -> next == IN_PROGRESS || next == CANCELLED || next == FAILED || next == PLANNED;
            case IN_PROGRESS -> next == IN_REVIEW || next == FAILED || next == CANCELLED;
            case IN_REVIEW -> next == APPROVED || next == QUEUED;
            case FAILED, CANCELLED -> next == QUEUED || next == PLANNED;
            case APPROVED -> false;
        };
    }
}
