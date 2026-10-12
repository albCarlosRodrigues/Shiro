package dev.squaddesktop.claude;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Encerramento de um processo e de tudo o que ele iniciou. */
public final class ProcessTrees {

    private ProcessTrees() {
    }

    /**
     * Encerra a árvore do processo. Primeiro pede o término (no Unix é SIGTERM, que o Claude Code trata
     * encerrando os comandos em andamento); depois de uma curta espera, força o que ainda estiver vivo.
     * No Windows o primeiro pedido já é forçado.
     */
    public static void kill(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroy();
        descendants.forEach(ProcessHandle::destroy);
        try {
            if (process.waitFor(2, TimeUnit.SECONDS)) {
                descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
