package dev.squaddesktop.claude;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Sugere a regra de permissão mais estreita que cobriria uma chamada negada. A pessoa confere antes de aplicar. */
public final class PermissionRules {

    /** Comandos cujo primeiro argumento é um subcomando; a regra sugerida fixa os dois primeiros termos. */
    private static final Set<String> WITH_SUBCOMMAND = Set.of("git", "npm", "npx", "pnpm", "yarn", "mvn", "gradle", "docker",
            "cargo", "go", "dotnet", "pip", "python", "node");
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_./:@=+-]{1,60}");
    private static final Pattern SHELL_OPERATORS = Pattern.compile("[;&|`$<>(){}\\r\\n]");

    private PermissionRules() {
    }

    /**
     * @return regra no formato do Claude Code (por exemplo {@code Bash(npm run *)} ou {@code Write}),
     *         ou null quando não há uma sugestão segura
     */
    public static String suggest(String toolName, Map<String, Object> input) {
        if (toolName == null || !toolName.matches("[A-Za-z][A-Za-z0-9_]{0,79}")) {
            return null;
        }
        if (!"Bash".equals(toolName) && !"PowerShell".equals(toolName)) {
            return toolName;
        }
        Object raw = input == null ? null : input.get("command");
        if (!(raw instanceof String command) || command.isBlank() || hasOperators(command)) {
            return null;
        }
        String[] tokens = command.strip().split("\\s+");
        if (!SAFE_TOKEN.matcher(tokens[0]).matches()) {
            return null;
        }
        if (tokens.length > 1 && WITH_SUBCOMMAND.contains(tokens[0]) && SAFE_TOKEN.matcher(tokens[1]).matches()
                && !tokens[1].startsWith("-")) {
            return toolName + "(" + tokens[0] + " " + tokens[1] + " *)";
        }
        return toolName + "(" + tokens[0] + " *)";
    }

    /**
     * Encadeamento, redirecionamento ou substituição fora de aspas tornam o comando composto. Parênteses e símbolos
     * dentro de aspas simples são só texto (por exemplo, {@code python3 -c 'print(1)'}); em aspas duplas, {@code $} e
     * crase ainda executam, então contam como operadores.
     */
    static boolean hasOperators(String command) {
        StringBuilder outside = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote == 0) {
                if (c == '\'' || c == '"') {
                    quote = c;
                    outside.append(' ');
                } else {
                    outside.append(c);
                }
            } else if (c == quote) {
                quote = 0;
            } else if (quote == '"' && (c == '$' || c == '`' || c == '\\')) {
                return true;
            }
        }
        return quote != 0 || SHELL_OPERATORS.matcher(outside).find();
    }
}
