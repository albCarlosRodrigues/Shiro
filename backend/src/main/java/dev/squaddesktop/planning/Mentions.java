package dev.squaddesktop.planning;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.squaddesktop.agents.Agent;
import dev.squaddesktop.agents.AgentRole;

/**
 * Menções @agente na sala de planejamento. Cada agente tem um apelido sem espaços nem acentos, derivado do nome
 * ("Operador Java/Spring" vira {@code @OperadorJavaSpring}); os papéis também valem ({@code @comandante},
 * {@code @prd}/{@code @requisitos}, {@code @adr}/{@code @arquitetura}).
 */
public final class Mentions {

    private static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([\\p{L}0-9_]{2,60})");

    private static final Map<String, AgentRole> ROLE_ALIASES = Map.of(
            "comandante", AgentRole.COMMANDER, "commander", AgentRole.COMMANDER,
            "prd", AgentRole.REQUIREMENTS, "requisitos", AgentRole.REQUIREMENTS,
            "adr", AgentRole.ARCHITECTURE, "arquitetura", AgentRole.ARCHITECTURE, "arquiteto", AgentRole.ARCHITECTURE);

    private Mentions() {
    }

    /** Apelido usado nas menções: palavras do nome sem acentos, em CamelCase. */
    public static String handle(String name) {
        String plain = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder out = new StringBuilder();
        for (String word : plain.split("[^A-Za-z0-9]+")) {
            if (!word.isEmpty()) {
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.isEmpty() ? "Agente" : out.toString();
    }

    private static String key(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** Agentes mencionados no texto, na ordem da primeira menção, sem repetição. */
    public static List<Agent> find(String text, List<Agent> squad) {
        LinkedHashSet<Agent> found = new LinkedHashSet<>();
        if (text == null) {
            return List.of();
        }
        Matcher m = MENTION.matcher(text);
        while (m.find()) {
            String token = key(m.group(1));
            Agent match = null;
            for (Agent agent : squad) {
                if (key(handle(agent.name())).equals(token)) {
                    match = agent;
                    break;
                }
            }
            if (match == null && ROLE_ALIASES.containsKey(token)) {
                AgentRole role = ROLE_ALIASES.get(token);
                match = squad.stream().filter(a -> a.role() == role).findFirst().orElse(null);
            }
            if (match != null) {
                found.add(match);
            }
        }
        return new ArrayList<>(found);
    }
}
