package dev.squaddesktop.shared;

import java.util.Map;

/**
 * Erro de regra de negócio ou de validação com código estável.
 * A camada web o converte em resposta HTTP; a interface usa o código para decidir a mensagem e a ação.
 */
public class ApiException extends RuntimeException {

    public enum Kind { NOT_FOUND, INVALID, CONFLICT, UNAVAILABLE, FORBIDDEN }

    private final Kind kind;
    private final String code;
    private final transient Map<String, Object> details;

    public ApiException(Kind kind, String code, String message) {
        this(kind, code, message, Map.of());
    }

    public ApiException(Kind kind, String code, String message, Map<String, Object> details) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException notFound(String what, String id) {
        return new ApiException(Kind.NOT_FOUND, "not_found", what + " não encontrado: " + id);
    }

    public static ApiException invalid(String code, String message) {
        return new ApiException(Kind.INVALID, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(Kind.CONFLICT, code, message);
    }

    public static ApiException conflict(String code, String message, Map<String, Object> details) {
        return new ApiException(Kind.CONFLICT, code, message, details);
    }

    public static ApiException unavailable(String code, String message) {
        return new ApiException(Kind.UNAVAILABLE, code, message);
    }
}
