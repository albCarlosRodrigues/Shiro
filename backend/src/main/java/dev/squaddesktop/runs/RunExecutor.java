package dev.squaddesktop.runs;

import java.util.function.Consumer;

/** Roda uma etapa até o fim, emitindo eventos no contrato interno. Há um executor real (Claude Code) e um de demonstração. */
public interface RunExecutor {

    /**
     * Bloqueia até a execução terminar, ser cancelada ou estourar o tempo.
     * Não lança exceções para falhas esperadas: devolve-as em {@link RunOutcome}.
     */
    RunOutcome execute(RunSpec spec, Consumer<AgentEvent> sink, RunControl control);

    /** Indica que as execuções deste executor são simuladas. */
    default boolean demo() {
        return false;
    }
}
