package dev.squaddesktop.claude;

/**
 * Situação do Claude Code neste computador. Separa três coisas que costumam ser confundidas:
 * encontrar o executável, ele responder (versão) e a conta estar conectada.
 *
 * @param authenticated true/false conforme {@code claude auth status}; null quando o comando não pôde ser consultado.
 *                      Estar conectado não garante que uma chamada funcione: use o teste de conexão para isso.
 */
public record ClaudeStatus(
        boolean found,
        boolean usable,
        String executable,
        String source,
        String version,
        Boolean authenticated,
        String authMethod,
        ClaudeCapabilities capabilities,
        String problemCode,
        String problem,
        String checkedAt) {
}
