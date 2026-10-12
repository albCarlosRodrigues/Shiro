package dev.squaddesktop.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Executável de teste que imita {@code claude} sem custo. O comportamento é escolhido por palavras no prompt:
 * FALHA, TRAVAR, NETO (inicia um processo filho e trava), LIXO (linhas que não são JSON), NEGAR (permissão negada),
 * LOGIN (falha de autenticação). Sem palavra-chave, responde com sucesso.
 *
 * <p>Variáveis: FAKE_ARGS_FILE grava os argumentos recebidos; FAKE_PID_FILE grava os pids (processo e neto).
 */
public final class FakeClaude {

    private FakeClaude() {
    }

    public static void main(String[] args) throws Exception {
        List<String> list = List.of(args);
        if (list.contains("--sleeper")) {
            Thread.sleep(120_000);
            return;
        }
        if (list.contains("--version")) {
            System.out.println("9.9.9 (Claude Code)");
            return;
        }
        if (list.contains("--help")) {
            System.out.println("""
                    Usage: claude [options]
                      --effort <level>                      Effort level (low, medium, high)
                      --include-partial-messages            Include partial message chunks
                      --max-budget-usd <amount>             Maximum dollar amount
                      --model <model>                       Model alias (e.g. 'opus', or 'sonnet')
                      --permission-mode <mode>              Permission mode (choices: "acceptEdits", "dontAsk", "plan")
                      --permission-prompts <target>         Who answers permission prompts
                      --resume [value]                      Resume a conversation
                      --session-id <uuid>                   Use a specific session ID
                      --tools <tools...>                    Specify the list of available tools
                    """);
            return;
        }
        if (list.size() >= 2 && list.get(0).equals("auth") && list.get(1).equals("status")) {
            boolean out = "0".equals(System.getenv("FAKE_LOGGED_IN"));
            System.out.println("{\"loggedIn\": " + !out + ", \"authMethod\": \"claude.ai\"}");
            System.exit(out ? 1 : 0);
        }
        String argsFile = System.getenv("FAKE_ARGS_FILE");
        if (argsFile != null) {
            Files.writeString(Path.of(argsFile), String.join("\n", list), StandardCharsets.UTF_8);
        }
        String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        String session = list.contains("--resume") ? list.get(list.indexOf("--resume") + 1)
                : list.contains("--session-id") ? list.get(list.indexOf("--session-id") + 1) : "sessao-falsa";
        emit("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"" + session + "\",\"model\":\"modelo-falso\",\"cwd\":"
                + quote(Path.of("").toAbsolutePath().toString()) + ",\"permissionMode\":\"dontAsk\",\"tools\":[\"Read\"],\"claude_code_version\":\"9.9.9\"}");

        if (prompt.contains("NETO")) {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process child = new ProcessBuilder(java, "-Dstdout.encoding=UTF-8", "-cp", System.getProperty("java.class.path"), FakeClaude.class.getName(), "--sleeper")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String pidFile = System.getenv("FAKE_PID_FILE");
            if (pidFile != null) {
                Files.writeString(Path.of(pidFile), ProcessHandle.current().pid() + "\n" + child.pid(), StandardCharsets.UTF_8);
            }
            emit("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"t-neto\",\"name\":\"Bash\",\"input\":{\"command\":\"sleep 120\"}}]},\"parent_tool_use_id\":null}");
            Thread.sleep(120_000);
            return;
        }
        if (prompt.contains("TRAVAR")) {
            Thread.sleep(120_000);
            return;
        }
        if (prompt.contains("LIXO")) {
            System.out.println("isto não é json");
            System.err.println("aviso no stderr");
        }
        if (prompt.contains("LOGIN")) {
            emit("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"Invalid API key · Please run /login\",\"session_id\":\"" + session + "\"}");
            System.exit(1);
        }
        if (prompt.contains("FALHA")) {
            System.err.println("erro simulado");
            emit("{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,\"result\":\"falha simulada\",\"session_id\":\"" + session + "\"}");
            System.exit(1);
        }
        if (prompt.contains("NEGAR")) {
            emit("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"t-w\",\"name\":\"Bash\",\"input\":{\"command\":\"npm run build\"}}]},\"parent_tool_use_id\":null}");
            emit("{\"type\":\"system\",\"subtype\":\"permission_denied\",\"tool_name\":\"Bash\",\"tool_use_id\":\"t-w\",\"message\":\"negado\"}");
            emit("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t-w\",\"content\":\"negado\",\"is_error\":true}]}}");
        }
        List<String> words = new ArrayList<>(List.of("Olá, ", "tudo ", "certo."));
        for (String word : words) {
            emit("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":" + quote(word) + "}},\"parent_tool_use_id\":null}");
        }
        emit("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Olá, tudo certo.\"}]},\"parent_tool_use_id\":null}");
        String denials = prompt.contains("NEGAR") ? "[{\"tool_name\":\"Bash\",\"tool_use_id\":\"t-w\",\"tool_input\":{\"command\":\"npm run build\"}}]" : "[]";
        emit("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"Olá, tudo certo.\",\"total_cost_usd\":0.0042,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5},\"num_turns\":1,\"duration_ms\":12,\"session_id\":\"" + session
                + "\",\"permission_denials\":" + denials + "}");
    }

    private static void emit(String json) throws IOException {
        System.out.println(json);
        System.out.flush();
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
