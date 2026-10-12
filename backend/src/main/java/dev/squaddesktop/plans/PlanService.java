package dev.squaddesktop.plans;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import dev.squaddesktop.agents.Agent;
import dev.squaddesktop.features.Feature;
import dev.squaddesktop.features.FeatureService;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.persistence.Row;
import dev.squaddesktop.persistence.Tx;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.projects.ProjectService;
import dev.squaddesktop.runs.ProjectSignals;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Ids;
import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.shared.JsonValues;
import dev.squaddesktop.shared.Texts;
import dev.squaddesktop.squads.SquadMembers;

/**
 * Planos de features. Um plano chega como rascunho (do comandante ou criado pela pessoa), é validado a cada
 * alteração e só vira sprints e features quando a pessoa aprova. Aprovar não inicia nenhuma execução.
 */
public class PlanService {

    /** Plano com o conteúdo estruturado e o resultado da última validação. */
    public record Plan(String id, String projectId, int number, String status, String authorType, String agentId, String messageId,
            String summary, PlanContent content, List<Problem> problems, String createdAt, String updatedAt, String approvedAt) {

        public boolean approvable() {
            return "DRAFT".equals(status) && problems.stream().noneMatch(Problem::blocking);
        }
    }

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,15}");
    private static final int MAX_FEATURES = 80;

    private final Database database;
    private final Clock clock;
    private final JsonCodec json;
    private final ProjectService projects;
    private final SquadMembers members;
    private final FeatureService features;
    private final ProjectSignals signals;

    public PlanService(Database database, Clock clock, JsonCodec json, ProjectService projects, SquadMembers members, FeatureService features,
            ProjectSignals signals) {
        this.signals = signals;
        this.database = database;
        this.clock = clock;
        this.json = json;
        this.projects = projects;
        this.members = members;
        this.features = features;
    }

    public List<Plan> list(String projectId) {
        return database.read(tx -> tx.query("""
                SELECT id, project_id, number, status, author_type, agent_id, message_id, summary, content, validation, created_at, updated_at, approved_at
                FROM plan WHERE project_id = ? ORDER BY number DESC""", this::map, projectId));
    }

    public Plan get(String id) {
        return find(id).orElseThrow(() -> ApiException.notFound("Plano", id));
    }

    private Optional<Plan> find(String id) {
        return database.read(tx -> tx.queryOne("""
                SELECT id, project_id, number, status, author_type, agent_id, message_id, summary, content, validation, created_at, updated_at, approved_at
                FROM plan WHERE id = ?""", this::map, id));
    }

    /** Rascunho vindo do bloco {@code squad-plano} de um agente. */
    public Plan createFromAgent(String projectId, String agentId, String messageId, Map<String, Object> data) {
        return insert(projectId, "AGENT", agentId, messageId, normalize(data));
    }

    /** Rascunho em branco para a pessoa preencher. */
    public Plan createBlank(String projectId) {
        projects.get(projectId);
        return insert(projectId, "USER", null, null, new PlanContent("", List.of(), List.of()));
    }

    public Plan update(String id, PlanContent content) {
        Plan plan = get(id);
        requireDraft(plan);
        PlanContent clean = clean(content);
        List<Problem> problems = validate(plan.projectId(), clean);
        String now = now();
        database.writeVoid(tx -> tx.update("UPDATE plan SET summary = ?, content = ?, validation = ?, updated_at = ? WHERE id = ?",
                Texts.orEmpty(clean.summary()), json.write(clean), json.write(problems), now, id));
        signals.changed(plan.projectId(), "plans", id);
        return get(id);
    }

    public Plan discard(String id) {
        Plan plan = get(id);
        requireDraft(plan);
        database.writeVoid(tx -> tx.update("UPDATE plan SET status = 'DISCARDED', updated_at = ? WHERE id = ?", now(), id));
        signals.changed(plan.projectId(), "plans", id);
        return get(id);
    }

    /**
     * Aprova o plano: cria ou atualiza sprints e features (estado Planejada) e grava as dependências. Recusa enquanto
     * houver erros. Features já em execução, revisão ou concluídas não são alteradas e viram aviso no resultado.
     */
    public FeatureService.ApplyResult approve(String id) {
        Plan plan = get(id);
        requireDraft(plan);
        List<Problem> problems = validate(plan.projectId(), plan.content());
        if (problems.stream().anyMatch(Problem::blocking)) {
            database.writeVoid(tx -> tx.update("UPDATE plan SET validation = ?, updated_at = ? WHERE id = ?", json.write(problems), now(), id));
            throw new ApiException(ApiException.Kind.INVALID, "plan_invalid",
                    "O plano tem pendências que impedem a aprovação. Corrija os itens marcados.", Map.of("problems", problems));
        }
        Project project = projects.get(plan.projectId());
        List<Agent> squad = members.of(project);
        Map<String, String> owners = new HashMap<>();
        for (PlanContent.FeatureItem item : plan.content().features()) {
            members.resolve(item.owner(), squad).ifPresent(agent -> owners.put(item.code(), agent.id()));
        }
        String now = now();
        FeatureService.ApplyResult applied = database.write(tx -> {
            FeatureService.ApplyResult result = features.applyPlan(tx, plan.projectId(), plan.id(), plan.content(), owners);
            tx.update("UPDATE plan SET status = 'APPROVED', approved_at = ?, updated_at = ? WHERE id = ?", now, now, id);
            return result;
        });
        features.announce(plan.projectId());
        signals.changed(plan.projectId(), "plans", id);
        return applied;
    }

    /* ---------- validação ---------- */

    /** Valida o conteúdo contra a squad e as features que já existem no projeto. */
    public List<Problem> validate(String projectId, PlanContent content) {
        Project project = projects.get(projectId);
        List<Agent> squad = members.of(project);
        List<Feature> existing = features.list(projectId);
        List<Problem> problems = new ArrayList<>();
        if (squad.isEmpty()) {
            problems.add(Problem.error("squad_required", null, "squad", "O projeto não tem squad: escolha uma para que as features tenham responsáveis."));
        }
        if (content.features().isEmpty()) {
            problems.add(Problem.error("empty_plan", null, "features", "O plano não tem features."));
        }
        if (content.features().size() > MAX_FEATURES) {
            problems.add(Problem.error("too_many_features", null, "features", "Um plano aceita até " + MAX_FEATURES + " features; divida em sprints e planos menores."));
        }
        Set<String> sprintNames = new HashSet<>();
        for (PlanContent.SprintItem sprint : content.sprints()) {
            if (Texts.isBlank(sprint.name())) {
                problems.add(Problem.error("sprint_name", null, "sprints", "Há uma sprint sem nome."));
            } else if (!sprintNames.add(sprint.name().strip().toLowerCase(Locale.ROOT))) {
                problems.add(Problem.error("sprint_duplicate", null, "sprints", "A sprint " + sprint.name() + " aparece mais de uma vez."));
            }
        }
        Set<String> existingSprints = new HashSet<>();
        features.sprints(projectId).forEach(s -> existingSprints.add(s.name().toLowerCase(Locale.ROOT)));

        Map<String, PlanContent.FeatureItem> byCode = new LinkedHashMap<>();
        for (PlanContent.FeatureItem f : content.features()) {
            String code = Texts.trimToNull(f.code());
            String label = code == null ? "(sem código)" : code;
            if (code == null || !CODE.matcher(code).matches()) {
                problems.add(Problem.error("code_invalid", label, "code", "Feature " + label + ": use um código curto, como F01."));
            } else if (byCode.putIfAbsent(code.toUpperCase(Locale.ROOT), f) != null) {
                problems.add(Problem.error("code_duplicate", code, "code", "O código " + code + " aparece mais de uma vez no plano."));
            }
            if (Texts.isBlank(f.title())) {
                problems.add(Problem.error("title_missing", label, "title", "Feature " + label + ": falta o título."));
            }
            if (Texts.isBlank(f.description())) {
                problems.add(Problem.warning("description_missing", label, "description", "Feature " + label + ": sem descrição; o agente terá só o título."));
            }
            if (f.criteria().stream().allMatch(Texts::isBlank)) {
                problems.add(Problem.error("criteria_missing", label, "criteria", "Feature " + label + ": informe ao menos um critério de aceitação verificável."));
            }
            if (f.tasks().stream().allMatch(Texts::isBlank)) {
                problems.add(Problem.warning("tasks_missing", label, "tasks", "Feature " + label + ": sem tarefas detalhadas."));
            }
            if (normalizePriority(f.priority()) == null) {
                problems.add(Problem.error("priority_invalid", label, "priority", "Feature " + label + ": prioridade deve ser alta, média ou baixa."));
            }
            if (Texts.isBlank(f.owner())) {
                problems.add(Problem.error("owner_missing", label, "owner", "Feature " + label + ": falta o agente responsável."));
            } else {
                Optional<Agent> owner = members.resolve(f.owner(), squad);
                if (owner.isEmpty()) {
                    problems.add(Problem.error("owner_unknown", label, "owner", "Feature " + label + ": o responsável \"" + f.owner() + "\" não foi encontrado."));
                } else if (squad.stream().noneMatch(a -> a.id().equals(owner.get().id()))) {
                    problems.add(Problem.warning("owner_outside_squad", label, "owner", "Feature " + label + ": " + owner.get().name() + " não faz parte da squad do projeto."));
                }
            }
            if (Texts.isBlank(f.specialty())) {
                problems.add(Problem.warning("specialty_missing", label, "specialty", "Feature " + label + ": sem especialidade."));
            }
            if (!Texts.isBlank(f.sprint()) && !sprintNames.contains(f.sprint().strip().toLowerCase(Locale.ROOT))
                    && !existingSprints.contains(f.sprint().strip().toLowerCase(Locale.ROOT))) {
                problems.add(Problem.warning("sprint_new", label, "sprint", "Feature " + label + ": a sprint " + f.sprint() + " será criada."));
            }
        }

        Map<String, Feature> existingByCode = new HashMap<>();
        for (Feature f : existing) {
            existingByCode.put(f.code().toUpperCase(Locale.ROOT), f);
        }
        DependencyGraph graph = new DependencyGraph();
        for (Feature f : existing) {
            if (!byCode.containsKey(f.code().toUpperCase(Locale.ROOT))) {
                List<String> deps = new ArrayList<>();
                for (String depId : f.dependsOn()) {
                    existing.stream().filter(e -> e.id().equals(depId)).findFirst().ifPresent(e -> deps.add(e.code().toUpperCase(Locale.ROOT)));
                }
                graph.add(f.code().toUpperCase(Locale.ROOT), deps);
            }
        }
        for (Map.Entry<String, PlanContent.FeatureItem> entry : byCode.entrySet()) {
            List<String> deps = new ArrayList<>();
            for (String dep : entry.getValue().dependsOn()) {
                String d = Texts.trimToNull(dep);
                if (d == null) {
                    continue;
                }
                String key = d.toUpperCase(Locale.ROOT);
                if (key.equals(entry.getKey())) {
                    problems.add(Problem.error("dependency_self", entry.getValue().code(), "dependsOn", "Feature " + entry.getValue().code() + " depende de si mesma."));
                } else if (!byCode.containsKey(key) && !existingByCode.containsKey(key)) {
                    problems.add(Problem.error("dependency_unknown", entry.getValue().code(), "dependsOn",
                            "Feature " + entry.getValue().code() + " depende de " + d + ", que não existe no plano nem no projeto."));
                } else {
                    deps.add(key);
                }
            }
            graph.add(entry.getKey(), deps);
            Feature current = existingByCode.get(entry.getKey());
            if (current != null && !current.status().editable()) {
                problems.add(Problem.warning("feature_locked", current.code(), "code",
                        "Feature " + current.code() + " já está em andamento ou concluída e não será alterada por este plano."));
            }
        }
        for (List<String> cycle : graph.cycles()) {
            problems.add(Problem.error("dependency_cycle", cycle.get(0), "dependsOn", "Dependência circular: " + String.join(" → ", cycle) + "."));
        }
        return problems;
    }

    /* ---------- conversão ---------- */

    /** Aceita as chaves em português (como o comandante escreve) ou em inglês (como a interface envia). */
    public static PlanContent normalize(Map<String, Object> data) {
        String summary = first(data, "resumo", "summary");
        List<PlanContent.SprintItem> sprints = new ArrayList<>();
        for (Object item : JsonValues.list(data, "sprints")) {
            if (item instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> s = (Map<String, Object>) m;
                sprints.add(new PlanContent.SprintItem(first(s, "nome", "name"), first(s, "objetivo", "goal")));
            }
        }
        List<PlanContent.FeatureItem> features = new ArrayList<>();
        for (Object item : JsonValues.list(data, "features")) {
            if (item instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> f = (Map<String, Object>) m;
                features.add(new PlanContent.FeatureItem(first(f, "codigo", "code"), first(f, "titulo", "title"),
                        first(f, "descricao", "description"), first(f, "prioridade", "priority"), first(f, "especialidade", "specialty"),
                        first(f, "responsavel", "owner"), first(f, "sprint", "sprint"), strings(f, "criterios", "criteria"),
                        strings(f, "tarefas", "tasks"), strings(f, "depende_de", "dependsOn")));
            }
        }
        return new PlanContent(summary, sprints, features);
    }

    /** HIGH, MEDIUM ou LOW a partir do que o agente ou a pessoa escreveu; null quando não reconhece. */
    public static String normalizePriority(String raw) {
        String p = raw == null ? "" : java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").strip().toUpperCase(Locale.ROOT);
        return switch (p) {
            case "HIGH", "ALTA", "P1", "1" -> "HIGH";
            case "", "MEDIUM", "MEDIA", "P2", "2" -> "MEDIUM";
            case "LOW", "BAIXA", "P3", "3" -> "LOW";
            default -> null;
        };
    }

    private static String first(Map<String, Object> data, String pt, String en) {
        Object value = data.containsKey(pt) ? data.get(pt) : data.get(en);
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static List<String> strings(Map<String, Object> data, String pt, String en) {
        Object value = data.containsKey(pt) ? data.get(pt) : data.get(en);
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    out.add(s.strip());
                } else if (item instanceof Map<?, ?> m && m.get("text") instanceof String s && !s.isBlank()) {
                    out.add(s.strip());
                }
            }
        } else if (value instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,;\\n]")) {
                if (!part.isBlank()) {
                    out.add(part.strip());
                }
            }
        }
        return out;
    }

    private static PlanContent clean(PlanContent content) {
        if (content == null) {
            throw ApiException.invalid("required", "Envie o conteúdo do plano.");
        }
        List<PlanContent.SprintItem> sprints = content.sprints() == null ? List.of()
                : content.sprints().stream().map(s -> new PlanContent.SprintItem(Texts.orEmpty(s.name()).strip(), Texts.orEmpty(s.goal()).strip())).toList();
        List<PlanContent.FeatureItem> features = content.features() == null ? List.of() : content.features().stream()
                .map(f -> new PlanContent.FeatureItem(Texts.orEmpty(f.code()).strip(), Texts.orEmpty(f.title()).strip(),
                        Texts.orEmpty(f.description()).strip(), Texts.orEmpty(f.priority()).strip(), Texts.orEmpty(f.specialty()).strip(),
                        Texts.orEmpty(f.owner()).strip(), Texts.orEmpty(f.sprint()).strip(), nonBlank(f.criteria()), nonBlank(f.tasks()),
                        nonBlank(f.dependsOn())))
                .toList();
        return new PlanContent(Texts.truncate(Texts.orEmpty(content.summary()).strip(), 4000), sprints, features);
    }

    private static List<String> nonBlank(List<String> list) {
        return list == null ? List.of() : list.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList();
    }

    private Plan insert(String projectId, String authorType, String agentId, String messageId, PlanContent content) {
        PlanContent clean = clean(content);
        List<Problem> problems = validate(projectId, clean);
        String now = now();
        String id = Ids.newId();
        database.writeVoid(tx -> {
            int number = (int) tx.scalarLong("SELECT COALESCE(MAX(number), 0) + 1 FROM plan WHERE project_id = ?", projectId);
            tx.update("""
                    INSERT INTO plan (id, project_id, number, status, author_type, agent_id, message_id, summary, content, validation, created_at, updated_at)
                    VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?)""",
                    id, projectId, number, authorType, agentId, messageId, Texts.orEmpty(clean.summary()), json.write(clean),
                    json.write(problems), now, now);
        });
        signals.changed(projectId, "plans", id);
        return get(id);
    }

    private static void requireDraft(Plan plan) {
        if (!"DRAFT".equals(plan.status())) {
            throw ApiException.conflict("plan_not_draft", "Este plano já foi " + ("APPROVED".equals(plan.status()) ? "aprovado" : "descartado") + ".");
        }
    }

    @SuppressWarnings("unchecked")
    private Plan map(Row row) throws SQLException {
        Object content = json.parse(row.str("content"));
        PlanContent parsed = content instanceof Map<?, ?> m ? normalize((Map<String, Object>) m) : new PlanContent("", List.of(), List.of());
        List<Problem> problems = new ArrayList<>();
        for (Object item : JsonValues.list(Map.of("v", json.parse(row.str("validation"))), "v")) {
            if (item instanceof Map<?, ?> m) {
                problems.add(new Problem(str(m, "severity"), str(m, "code"), str(m, "feature"), str(m, "field"), str(m, "message")));
            }
        }
        return new Plan(row.str("id"), row.str("project_id"), row.integer("number"), row.str("status"), row.str("author_type"),
                row.str("agent_id"), row.str("message_id"), row.strOrEmpty("summary"), parsed, problems, row.str("created_at"),
                row.str("updated_at"), row.str("approved_at"));
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private String now() {
        return clock.instant().toString();
    }
}
