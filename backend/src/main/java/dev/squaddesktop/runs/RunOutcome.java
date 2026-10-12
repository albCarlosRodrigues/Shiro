package dev.squaddesktop.runs;

import java.util.List;

/**
 * Resultado bruto devolvido por um executor. A orquestração decide o estado final a partir dele.
 *
 * @param exitCode       código de saída do processo; null quando não houve processo ou ele não terminou
 * @param failureCode    código estável quando a execução falhou; null em caso de sucesso
 * @param command        linha de comando usada, para o histórico (sem o prompt)
 */
public record RunOutcome(
        Integer exitCode,
        boolean timedOut,
        String resultText,
        String failureCode,
        String failureMessage,
        List<String> command) {

    public static RunOutcome success(Integer exitCode, String resultText, List<String> command) {
        return new RunOutcome(exitCode, false, resultText, null, null, command);
    }

    public static RunOutcome failure(Integer exitCode, String code, String message, List<String> command) {
        return new RunOutcome(exitCode, false, null, code, message, command);
    }

    public static RunOutcome timeout(List<String> command) {
        return new RunOutcome(null, true, null, FailureCodes.TIMEOUT, "A execução passou do tempo máximo configurado e foi encerrada.", command);
    }
}
