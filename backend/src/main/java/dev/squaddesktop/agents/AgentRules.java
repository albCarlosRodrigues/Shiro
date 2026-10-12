package dev.squaddesktop.agents;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import dev.squaddesktop.settings.SettingsService;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Colors;
import dev.squaddesktop.shared.Texts;

/** Validação dos campos de um agente. As listas aceitas refletem o que o Claude Code documenta. */
public final class AgentRules {

    /**
     * Modos de permissão que o aplicativo oferece. {@code bypassPermissions} fica de fora de propósito:
     * nenhuma execução iniciada pelo Squad Desktop ignora as verificações de permissão.
     */
    public static final List<String> PERMISSION_MODES = List.of("default", "acceptEdits", "plan", "dontAsk", "auto");

    public static final List<String> EFFORT_LEVELS = List.of("low", "medium", "high", "xhigh", "max");

    public static final Set<String> ICONS = Set.of("agent", "commander", "requirements", "architecture", "frontend",
            "backend", "database", "qa", "devops", "docs", "security", "mobile");

    /** Nome de ferramenta, com regra opcional entre parênteses: Read, Bash(npm test *), mcp__servidor__ferramenta. */
    private static final Pattern TOOL_RULE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,79}(\\([^()\\r\\n]{1,200}\\))?");
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,79}");

    private AgentRules() {
    }

    public static AgentDraft validate(AgentDraft d) {
        if (d == null) {
            throw ApiException.invalid("required", "Envie os dados do agente.");
        }
        if (d.role() == null) {
            throw ApiException.invalid("required", "Escolha o papel do agente.");
        }
        String model = Texts.trimToNull(d.model());
        if (model != null && !SettingsService.isValidModel(model)) {
            throw ApiException.invalid("invalid_model", "Nome de modelo inválido: use um apelido (por exemplo, sonnet) ou o nome completo.");
        }
        String effort = Texts.trimToNull(d.effort());
        if (effort != null && !EFFORT_LEVELS.contains(effort)) {
            throw ApiException.invalid("invalid_effort", "Nível de esforço inválido.");
        }
        String mode = Texts.trimToNull(d.permissionMode());
        if (mode != null && !PERMISSION_MODES.contains(mode)) {
            throw ApiException.invalid("invalid_permission_mode", "Modo de permissão não oferecido pelo Squad Desktop.");
        }
        String icon = Texts.trimToNull(d.icon());
        if (icon == null || !ICONS.contains(icon)) {
            icon = "agent";
        }
        return new AgentDraft(
                Texts.requireText(d.name(), "nome", 80),
                d.role(),
                Texts.optionalText(d.specialty(), "especialidade", 120).strip(),
                icon,
                Colors.require(d.color(), Colors.pick(d.role().ordinal())),
                Texts.optionalText(d.responsibility(), "responsabilidade", 4_000),
                Texts.optionalText(d.personality(), "personalidade", 4_000),
                Texts.optionalText(d.instructions(), "instruções", 60_000),
                Texts.optionalText(d.guidelines(), "diretrizes", 60_000),
                model, effort, mode,
                d.tools() == null ? null : toolNames(d.tools()),
                toolRules(d.allowedTools(), "permitidas"),
                toolRules(d.disallowedTools(), "negadas"),
                Texts.trimToNull(d.templateKey()));
    }

    static List<String> toolNames(List<String> raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String item : raw) {
            String tool = Texts.trimToNull(item);
            if (tool == null) {
                continue;
            }
            if (!TOOL_NAME.matcher(tool).matches()) {
                throw ApiException.invalid("invalid_tool", "Nome de ferramenta inválido: " + Texts.truncate(tool, 60));
            }
            out.add(tool);
        }
        return List.copyOf(out);
    }

    public static List<String> toolRules(List<String> raw, String label) {
        if (raw == null) {
            return List.of();
        }
        if (raw.size() > 100) {
            throw ApiException.invalid("too_many_tools", "Há regras de ferramentas " + label + " demais (máximo de 100).");
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String item : raw) {
            String rule = Texts.trimToNull(item);
            if (rule == null) {
                continue;
            }
            if (!TOOL_RULE.matcher(rule).matches()) {
                throw ApiException.invalid("invalid_tool", "Regra de ferramenta inválida: " + Texts.truncate(rule, 60)
                        + ". Use o formato Ferramenta ou Ferramenta(padrão), por exemplo Bash(npm test *).");
            }
            out.add(rule);
        }
        return new ArrayList<>(out);
    }

    public static boolean isValidRule(String rule) {
        return rule != null && TOOL_RULE.matcher(rule).matches();
    }
}
