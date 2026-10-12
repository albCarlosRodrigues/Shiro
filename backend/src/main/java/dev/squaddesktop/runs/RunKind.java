package dev.squaddesktop.runs;

/**
 * Natureza da execução. CHAT e PLANNING analisam e propõem com permissões de leitura;
 * só EXECUTION, pedida explicitamente, pode alterar arquivos do projeto.
 */
public enum RunKind {
    CHAT, PLANNING, EXECUTION;

    public RunAccess access() {
        return this == EXECUTION ? RunAccess.WRITE : RunAccess.READ_ONLY;
    }
}
