package dev.squaddesktop.runs;

import static dev.squaddesktop.runs.RunStatus.CANCELLED;
import static dev.squaddesktop.runs.RunStatus.CANCELLING;
import static dev.squaddesktop.runs.RunStatus.COMPLETED;
import static dev.squaddesktop.runs.RunStatus.FAILED;
import static dev.squaddesktop.runs.RunStatus.INTERRUPTED;
import static dev.squaddesktop.runs.RunStatus.QUEUED;
import static dev.squaddesktop.runs.RunStatus.RUNNING;
import static dev.squaddesktop.runs.RunStatus.STARTING;
import static dev.squaddesktop.runs.RunStatus.TIMED_OUT;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunStatusTest {

    @Test
    void caminhoFeliz() {
        assertThat(QUEUED.canTransitionTo(STARTING)).isTrue();
        assertThat(STARTING.canTransitionTo(RUNNING)).isTrue();
        assertThat(RUNNING.canTransitionTo(COMPLETED)).isTrue();
    }

    @Test
    void cancelamentoPassaPorCancelling() {
        assertThat(RUNNING.canTransitionTo(CANCELLING)).isTrue();
        assertThat(RUNNING.canTransitionTo(CANCELLED)).isFalse();
        assertThat(CANCELLING.canTransitionTo(CANCELLED)).isTrue();
        assertThat(CANCELLING.canTransitionTo(COMPLETED)).isFalse();
        assertThat(QUEUED.canTransitionTo(CANCELLED)).isTrue();
    }

    @Test
    void estadosFinaisNaoMudam() {
        for (RunStatus terminal : new RunStatus[] {COMPLETED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED}) {
            assertThat(terminal.terminal()).isTrue();
            for (RunStatus next : RunStatus.values()) {
                assertThat(terminal.canTransitionTo(next)).as(terminal + " -> " + next).isFalse();
            }
        }
    }

    @Test
    void qualquerEstadoAbertoPodeSerInterrompido() {
        for (RunStatus open : new RunStatus[] {QUEUED, STARTING, RUNNING, CANCELLING}) {
            assertThat(open.canTransitionTo(INTERRUPTED)).isTrue();
        }
    }

    @Test
    void filaNaoPulaParaFalha() {
        assertThat(QUEUED.canTransitionTo(FAILED)).isFalse();
        assertThat(QUEUED.canTransitionTo(RUNNING)).isFalse();
    }
}
