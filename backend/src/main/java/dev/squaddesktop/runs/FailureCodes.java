package dev.squaddesktop.runs;

/** Códigos estáveis de falha de execução. A interface os usa para explicar o problema e indicar a saída. */
public final class FailureCodes {

    public static final String CLI_NOT_FOUND = "claude_not_found";
    public static final String CLI_UNSUPPORTED = "claude_unsupported";
    public static final String NOT_AUTHENTICATED = "claude_not_authenticated";
    public static final String SPAWN_FAILED = "spawn_failed";
    public static final String TIMEOUT = "timeout";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String BILLING = "billing";
    public static final String BUDGET_EXCEEDED = "budget_exceeded";
    public static final String MAX_TURNS = "max_turns";
    public static final String SESSION_NOT_FOUND = "claude_session_not_found";
    public static final String FOLDER_MISSING = "folder_missing";
    public static final String CLI_ERROR = "claude_error";
    public static final String NO_RESULT = "no_result";
    public static final String INTERNAL = "internal_error";
    public static final String APP_CLOSED = "app_closed";

    private FailureCodes() {
    }
}
