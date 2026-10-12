package dev.squaddesktop.runs;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Canal de cancelamento entre a orquestração e o executor de uma execução. */
public final class RunControl {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Runnable> hook = new AtomicReference<>();

    public boolean cancelled() {
        return cancelled.get();
    }

    /** Registrado pelo executor: encerra o processo em andamento. Roda de imediato se o cancelamento já foi pedido. */
    public void onCancel(Runnable action) {
        hook.set(action);
        if (cancelled.get()) {
            runHook();
        }
    }

    /** Pede o cancelamento. Idempotente. */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            runHook();
        }
    }

    private void runHook() {
        Runnable action = hook.getAndSet(null);
        if (action != null) {
            try {
                action.run();
            } catch (RuntimeException ignored) {
                // o cancelamento é em melhor esforço; a orquestração ainda marca a execução
            }
        }
    }
}
