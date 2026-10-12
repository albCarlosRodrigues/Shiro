package dev.squaddesktop.runs;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estados de uma execução e as transições permitidas.
 *
 * <pre>
 * QUEUED ──► STARTING ──► RUNNING ──► COMPLETED | FAILED | TIMED_OUT
 *    │           │           │
 *    │           └───────────┴──► CANCELLING ──► CANCELLED
 *    └──► CANCELLED
 * qualquer estado não final ──► INTERRUPTED   (o aplicativo encerrou; nada é retomado sozinho)
 * </pre>
 */
public enum RunStatus {
    QUEUED, STARTING, RUNNING, CANCELLING, COMPLETED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED;

    private static final Set<RunStatus> TERMINAL = EnumSet.of(COMPLETED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED);

    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    /** Ocupa uma vaga de execução (tem ou está prestes a ter um processo). */
    public boolean active() {
        return this == STARTING || this == RUNNING || this == CANCELLING;
    }

    public boolean canTransitionTo(RunStatus next) {
        if (terminal() || next == this) {
            return false;
        }
        if (next == INTERRUPTED) {
            return true;
        }
        return switch (this) {
            case QUEUED -> next == STARTING || next == CANCELLED;
            case STARTING -> next == RUNNING || next == CANCELLING || next == FAILED || next == TIMED_OUT || next == COMPLETED;
            case RUNNING -> next == CANCELLING || next == COMPLETED || next == FAILED || next == TIMED_OUT;
            case CANCELLING -> next == CANCELLED;
            default -> false;
        };
    }
}
