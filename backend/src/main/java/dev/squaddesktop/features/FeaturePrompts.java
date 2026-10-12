package dev.squaddesktop.features;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.squaddesktop.agents.Agent;
import dev.squaddesktop.planning.Mentions;
import dev.squaddesktop.shared.Texts;

/** Monta a mensagem de cada etapa de execução e normaliza a passagem ({@code squad-entrega}) que o agente devolve. */
public final class FeaturePrompts {

    /** Campos da passagem, na ordem em que a revisão os mostra. */
    public static final List<String> HANDOFF_LISTS = List.of("entregas", "arquivos", "pendencias", "criterios_validacao", "testes");

    private FeaturePrompts() {
    }

    public record StepContext(Feature feature, String sprintName, List<Feature> approvedDependencies, Agent agent, int stepNumber,
            String reason, String workdir, String branch, Agent previousAgent, Map<String, Object> previousHandoff,
            String reviewComment, List<Agent> squad) {
    }

    public static String step(StepContext c) {
        Feature f = c.feature();
        StringBuilder out = new StringBuilder();
        out.append("# Feature ").append(f.code()).append(": ").append(f.title()).append("\n\n");
        out.append("Etapa ").append(c.stepNumber()).append(" — ").append(c.reason()).append('\n');
        out.append("Prioridade: ").append(priority(f.priority()));
        if (!Texts.isBlank(f.specialty())) {
            out.append(" · Especialidade: ").append(f.specialty());
        }
        if (c.sprintName() != null) {
            out.append(" · Sprint: ").append(c.sprintName());
        }
        out.append('\n');
        out.append("Pasta de trabalho desta etapa: `").append(c.workdir()).append('`');
        if (c.branch() != null) {
            out.append(" (worktree isolado na branch `").append(c.branch()).append("`; não troque de branch)");
        }
        out.append("\n");

        if (!Texts.isBlank(f.description())) {
            out.append("\n## Descrição\n").append(f.description().strip()).append('\n');
        }
        if (!f.acceptanceCriteria().isEmpty()) {
            out.append("\n## Critérios de aceite\n");
            f.acceptanceCriteria().forEach(cr -> out.append("- ").append(cr.text()).append('\n'));
        }
        if (!f.tasks().isEmpty()) {
            out.append("\n## Tarefas\n");
            f.tasks().forEach(t -> out.append("- [").append(t.done() ? 'x' : ' ').append("] ").append(t.text()).append('\n'));
        }
        if (!c.approvedDependencies().isEmpty()) {
            out.append("\n## Já entregue e aprovado (dependências)\n");
            c.approvedDependencies().forEach(d -> out.append("- ").append(d.code()).append(": ").append(d.title()).append('\n'));
        }
        if (c.reviewComment() != null) {
            out.append("\n## Ajustes pedidos na revisão humana\n").append(c.reviewComment().strip()).append('\n');
            out.append("Corrija esses pontos antes de qualquer outra coisa.\n");
        }
        if (c.previousHandoff() != null) {
            out.append("\n## Passagem recebida");
            if (c.previousAgent() != null) {
                out.append(" de ").append(c.previousAgent().name());
            }
            out.append('\n').append(render(c.previousHandoff()));
        }
        List<Agent> others = c.squad().stream().filter(a -> !a.id().equals(c.agent().id())).toList();
        if (!others.isEmpty()) {
            out.append("\n## Squad (para passar a vez, se necessário)\n");
            others.forEach(a -> out.append("- @").append(Mentions.handle(a.name())).append(" — ").append(a.role().label())
                    .append(Texts.isBlank(a.specialty()) ? "" : ", " + a.specialty().strip()).append('\n'));
        }
        out.append("""

                ## Como encerrar esta etapa
                Faça o trabalho desta etapa na pasta indicada. Rode os testes ou verificações que fizerem sentido e diga o \
                que realmente executou. Não invente resultados. A integração final depende da revisão humana.

                Termine a resposta com exatamente um bloco no formato abaixo (JSON válido, sem comentários):

                ```squad-entrega
                {"objetivo": "o que esta etapa buscava", "contexto": "decisões e informações importantes", \
                "entregas": ["o que foi feito"], "arquivos": ["caminhos alterados"], "pendencias": ["o que falta"], \
                "criterios_validacao": ["como verificar cada critério"], "testes": ["comando executado e resultado"], \
                "proximo": null, "mensagem_proximo": ""}
                ```

                Use "proximo" com o @apelido de outro agente da squad só quando ele precisar continuar esta feature \
                (por exemplo, testes ou revisão técnica) e explique em "mensagem_proximo" o que ele deve fazer. \
                Deixe null para enviar a feature à revisão humana.
                """);
        return out.toString();
    }

    /** Converte o bloco em um mapa com campos conhecidos; textos longos são cortados. */
    public static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("objetivo", text(first(raw, "objetivo", "objective", "goal"), 2_000));
        out.put("contexto", text(first(raw, "contexto", "context"), 6_000));
        out.put("entregas", list(raw, "entregas", "deliverables"));
        out.put("arquivos", list(raw, "arquivos", "files"));
        out.put("pendencias", list(raw, "pendencias", "pendências", "pending"));
        out.put("criterios_validacao", list(raw, "criterios_validacao", "critérios_validação", "validation"));
        out.put("testes", list(raw, "testes", "tests"));
        out.put("proximo", text(first(raw, "proximo", "próximo", "next"), 120));
        out.put("mensagem_proximo", text(first(raw, "mensagem_proximo", "next_message"), 4_000));
        return out;
    }

    /** Passagem montada quando o agente terminou sem o bloco: guarda o texto final para a revisão. */
    public static Map<String, Object> fallback(String resultText) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("objetivo", "");
        out.put("contexto", Texts.truncate(Texts.orEmpty(resultText).strip(), 6_000));
        for (String key : HANDOFF_LISTS) {
            out.put(key, List.of());
        }
        out.put("proximo", "");
        out.put("mensagem_proximo", "");
        out.put("sem_bloco", true);
        return out;
    }

    static String render(Map<String, Object> handoff) {
        StringBuilder out = new StringBuilder();
        line(out, "Objetivo", handoff.get("objetivo"));
        line(out, "Contexto", handoff.get("contexto"));
        for (String key : HANDOFF_LISTS) {
            Object value = handoff.get(key);
            if (value instanceof List<?> items && !items.isEmpty()) {
                out.append("- ").append(title(key)).append(":\n");
                items.forEach(i -> out.append("  - ").append(i).append('\n'));
            }
        }
        line(out, "Para você", handoff.get("mensagem_proximo"));
        return out.toString();
    }

    private static void line(StringBuilder out, String label, Object value) {
        if (value instanceof String s && !s.isBlank()) {
            out.append("- ").append(label).append(": ").append(s.strip()).append('\n');
        }
    }

    private static String title(String key) {
        return switch (key) {
            case "entregas" -> "Entregas";
            case "arquivos" -> "Arquivos";
            case "pendencias" -> "Pendências";
            case "criterios_validacao" -> "Critérios de validação";
            case "testes" -> "Testes";
            default -> key;
        };
    }

    private static String priority(String p) {
        return switch (p == null ? "" : p) {
            case "HIGH" -> "alta";
            case "LOW" -> "baixa";
            default -> "média";
        };
    }

    private static Object first(Map<String, Object> raw, String... keys) {
        for (String k : keys) {
            if (raw.containsKey(k)) {
                return raw.get(k);
            }
        }
        return null;
    }

    private static String text(Object value, int max) {
        if (value == null) {
            return "";
        }
        return Texts.truncate(String.valueOf(value).strip(), max);
    }

    private static List<String> list(Map<String, Object> raw, String... keys) {
        Object value = first(raw, keys);
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> m) {
                    // por exemplo {"comando": "...", "resultado": "..."}
                    List<String> parts = new ArrayList<>();
                    List<Object> fields = new ArrayList<>(m.keySet());
                    fields.sort(java.util.Comparator.comparingInt(FeaturePrompts::keyRank));
                    fields.forEach(k -> parts.add(String.valueOf(m.get(k))));
                    out.add(Texts.truncate(String.join(" → ", parts), 1_000));
                } else if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(Texts.truncate(String.valueOf(item).strip(), 1_000));
                }
                if (out.size() >= 60) {
                    break;
                }
            }
        } else if (value instanceof String s && !s.isBlank()) {
            out.add(Texts.truncate(s.strip(), 1_000));
        }
        return out;
    }

    static String nextReference(Map<String, Object> handoff) {
        Object value = handoff == null ? null : handoff.get("proximo");
        String s = value == null ? null : Texts.trimToNull(String.valueOf(value));
        return s == null || s.equalsIgnoreCase("null") || s.equalsIgnoreCase("nenhum") ? null : s;
    }

    /** Ordem estável para itens estruturados (por exemplo, comando antes do resultado). */
    private static int keyRank(Object key) {
        int i = List.of("comando", "command", "nome", "name", "descricao", "description", "resultado", "result", "status").indexOf(String.valueOf(key));
        return i < 0 ? 100 : i;
    }
}
