package dev.squaddesktop.persistence;

/** Falha de acesso ao banco local. Não é esperada em uso normal; a camada web responde 500. */
public class StoreException extends RuntimeException {

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public StoreException(String message) {
        super(message);
    }
}
