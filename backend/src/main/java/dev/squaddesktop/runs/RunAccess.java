package dev.squaddesktop.runs;

/** Se a execução pode escrever na pasta do projeto. Só uma execução WRITE por pasta roda de cada vez. */
public enum RunAccess {
    READ_ONLY, WRITE
}
