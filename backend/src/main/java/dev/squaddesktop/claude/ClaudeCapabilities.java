package dev.squaddesktop.claude;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recursos da CLI instalada. O adaptador só usa uma flag quando ela consta aqui; o que faltar vira aviso
 * na execução em vez de erro de linha de comando.
 *
 * <p>A detecção combina o texto de {@code claude --help} com as versões mínimas publicadas na documentação,
 * porque o {@code --help} não lista todas as flags.
 */
public record ClaudeCapabilities(
        String version,
        boolean streamJson,
        boolean partialMessages,
        boolean permissionPrompts,
        boolean effort,
        boolean maxBudget,
        boolean sessionId,
        boolean resume,
        boolean toolsFlag,
        boolean appendSystemPromptFile,
        boolean systemPromptSnapshot,
        List<String> modelAliases,
        List<String> effortLevels,
        List<String> permissionModes) {

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    /** Capacidades supostas quando a versão é desconhecida: só o conjunto mínimo documentado para {@code claude -p}. */
    public static final ClaudeCapabilities MINIMAL = new ClaudeCapabilities(null, true, false, false, false, false, false, true,
            false, false, false, List.of(), List.of(), List.of("default", "acceptEdits", "plan"));

    public static ClaudeCapabilities detect(String versionText, String helpText) {
        String version = parseVersion(versionText);
        String help = helpText == null ? "" : helpText;
        if (version == null && help.isBlank()) {
            return MINIMAL;
        }
        boolean hasHelp = !help.isBlank();
        boolean modern = atLeast(version, 2, 0, 0);
        return new ClaudeCapabilities(
                version,
                !hasHelp || help.contains("stream-json"),
                help.contains("--include-partial-messages") || (!hasHelp && modern),
                help.contains("--permission-prompts") || atLeast(version, 2, 1, 259),
                help.contains("--effort"),
                help.contains("--max-budget-usd"),
                help.contains("--session-id") || (!hasHelp && modern),
                !hasHelp || help.contains("--resume"),
                help.contains("--tools ") || help.contains("--tools\n"),
                modern,
                help.contains("--system-prompt-snapshot") || atLeast(version, 2, 1, 257),
                modelAliases(help),
                effortLevels(help),
                permissionModes(help));
    }

    public static String parseVersion(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = VERSION.matcher(text);
        return m.find() ? m.group() : null;
    }

    public static boolean atLeast(String version, int major, int minor, int patch) {
        if (version == null) {
            return false;
        }
        Matcher m = VERSION.matcher(version);
        if (!m.find()) {
            return false;
        }
        int[] have = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
        int[] want = {major, minor, patch};
        for (int i = 0; i < 3; i++) {
            if (have[i] != want[i]) {
                return have[i] > want[i];
            }
        }
        return true;
    }

    /** Apelidos de modelo citados na ajuda de {@code --model}, por exemplo: (e.g. 'fable', 'opus', or 'sonnet'). */
    static List<String> modelAliases(String help) {
        String section = optionText(help, "--model <model>");
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("'([a-z]{2,20})'").matcher(section);
        while (m.find()) {
            if (!out.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return List.copyOf(out);
    }

    static List<String> effortLevels(String help) {
        String section = optionText(help, "--effort <level>");
        Matcher m = Pattern.compile("\\(([a-z]+(?:,\\s*[a-z]+)+)\\)").matcher(section);
        if (!m.find()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String level : m.group(1).split(",")) {
            out.add(level.strip());
        }
        return List.copyOf(out);
    }

    static List<String> permissionModes(String help) {
        String section = optionText(help, "--permission-mode <mode>");
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([A-Za-z]+)\"").matcher(section);
        while (m.find()) {
            if (!out.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return List.copyOf(out);
    }

    /** Texto de ajuda de uma opção: do nome dela até a próxima linha que começa uma opção. */
    private static String optionText(String help, String option) {
        int start = help.indexOf(option);
        if (start < 0) {
            return "";
        }
        Matcher next = Pattern.compile("\\n\\s{2,4}-{1,2}[a-zA-Z]").matcher(help);
        int end = next.find(start + option.length()) ? next.start() : help.length();
        return help.substring(start, end).replaceAll("\\s+", " ");
    }
}
