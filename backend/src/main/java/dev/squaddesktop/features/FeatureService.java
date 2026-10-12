package dev.squaddesktop.features;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import dev.squaddesktop.agents.AgentService;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.persistence.Tx;
import dev.squaddesktop.plans.DependencyGraph;
import dev.squaddesktop.plans.PlanContent;
import dev.squaddesktop.plans.PlanService;
import dev.squaddesktop.runs.ProjectSignals;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Ids;
import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.shared.Texts;

/**
 * Sprints e features do projeto: cadastro, edição enquanto não estão em execução, checklists e aplicação de um
 * plano aprovado. As mudanças de estado ligadas à execução ficam em {@link FeatureRunner}.
 */
public class FeatureService {

    /** Resultado de aplicar um plano aprovado. {@code skipped} lista códigos que não foram alterados por estarem em andamento. */
    public record ApplyResult(String planId, int created, int updated, List<String> skipped, List<Feature> features) {
    }

    /** Dados editáveis de uma feature. Listas null mantêm o valor atual na edição. */
    public record FeatureDraft(String code, String title, String description, String priority, String specialty, String ownerAgentId,
            String sprintId, List<Feature.Criterion> acceptanceCriteria, List<Feature.Task> tasks, List<String> dependsOn) {
    }

    /** Visão do quadro: sprints, features e o estado da fila do projeto. */
    public record Overview(String projectId, List<Sprint> sprints, List<Feature> features, Map<String, List<String>> blockedBy,
            boolean queuePaused, String pauseReason) {
    }

    /** Detalhe para a revisão da entrega. */
    public record FeatureDetail(Feature feature, List<FeatureStep> steps, List<FeatureReview> reviews, List<String> blockedBy) {
    }

    static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,15}");
    static final int MAX_ITEMS = 40;

    private final Database database;
    private final Clock clock;
    private final AgentService agents;
    private final ProjectSignals signals;
    final FeatureRepository repository;

    public FeatureService(Database database, Clock clock, JsonCodec json, AgentService agents, ProjectSignals signals) {
        this.database = database;
        this.clock = clock;
        this.agents = agents;
        this.signals = signals;
        this.repository = new FeatureRepository(json);
    }

    /* ---------- consulta ---------- */

    public List<Feature> list(String projectId) {
        return database.read(tx -> repository.byProject(tx, projectId));
    }

    public List<Sprint> sprints(String projectId) {
        return database.read(tx -> repository.sprints(tx, projectId));
    }

    public Feature get(String id) {
        return database.read(tx -> repository.find(tx, id)).orElseThrow(() -> ApiException.notFound("Feature", id));
    }

    public Overview overview(String projectId) {
        return database.read(tx -> {
            if (tx.scalarLong("SELECT COUNT(*) FROM project WHERE id = ?", projectId) == 0) {
                throw ApiException.notFound("Projeto", projectId);
            }
            List<Feature> features = repository.byProject(tx, projectId);
            return new Overview(projectId, repository.sprints(tx, projectId), features, blockedBy(features),
                    repository.paused(tx, projectId), repository.pauseReason(tx, projectId));
        });
    }

    public FeatureDetail detail(String id) {
        return database.read(tx -> {
            Feature feature = repository.find(tx, id).orElseThrow(() -> ApiException.notFound("Feature", id));
            List<Feature> all = repository.byProject(tx, feature.projectId());
            return new FeatureDetail(feature, repository.steps(tx, id), repository.reviews(tx, id),
                    blockedBy(all).getOrDefault(id, List.of()));
        });
    }

    /** Para cada feature, os códigos das dependências que ainda não foram aprovadas. */
    static Map<String, List<String>> blockedBy(List<Feature> features) {
        Map<String, Feature> byId = new HashMap<>();
        features.forEach(f -> byId.put(f.id(), f));
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Feature f : features) {
            List<String> blocking = new ArrayList<>();
            for (String dep : f.dependsOn()) {
                Feature d = byId.get(dep);
                if (d != null && d.status() != FeatureStatus.APPROVED) {
                    blocking.add(d.code());
                }
            }
            if (!blocking.isEmpty()) {
                out.put(f.id(), blocking);
            }
        }
        return out;
    }

    /* ---------- edição ---------- */

    public Feature create(String projectId, FeatureDraft draft) {
        String now = now();
        Feature created = database.write(tx -> {
            if (tx.scalarLong("SELECT COUNT(*) FROM project WHERE id = ?", projectId) == 0) {
                throw ApiException.notFound("Projeto", projectId);
            }
            String code = Texts.trimToNull(draft.code());
            if (code == null) {
                code = nextCode(tx, projectId);
            }
            Feature feature = new Feature(Ids.newId(), projectId, sprintIn(tx, projectId, draft.sprintId()), null, checkCode(tx, projectId, code, null),
                    Texts.requireText(draft.title(), "título", 200), Texts.optionalText(draft.description(), "descrição", 20_000),
                    priority(draft.priority()), Texts.optionalText(draft.specialty(), "especialidade", 80), owner(draft.ownerAgentId()),
                    criteria(draft.acceptanceCriteria()), tasks(draft.tasks()), FeatureStatus.PLANNED, 0,
                    repository.nextPosition(tx, projectId), null, null, null, null, List.of(), now, now);
            repository.insert(tx, feature);
            List<String> deps = dependencies(tx, feature, draft.dependsOn());
            repository.setDependencies(tx, feature.id(), deps);
            return repository.find(tx, feature.id()).orElseThrow();
        });
        signals.changed(projectId, "features", created.id());
        return created;
    }

    public Feature update(String id, FeatureDraft draft) {
        String now = now();
        Feature updated = database.write(tx -> {
            Feature current = repository.find(tx, id).orElseThrow(() -> ApiException.notFound("Feature", id));
            if (!current.status().editable()) {
                throw ApiException.conflict("feature_locked",
                        "A feature " + current.code() + " está " + label(current.status()) + "; ela só pode ser editada quando planejada, com falha ou cancelada.");
            }
            String code = draft.code() == null ? current.code() : checkCode(tx, current.projectId(), Texts.requireText(draft.code(), "código", 16), id);
            Feature next = new Feature(id, current.projectId(),
                    draft.sprintId() == null ? current.sprintId() : sprintIn(tx, current.projectId(), draft.sprintId()), current.planId(), code,
                    draft.title() == null ? current.title() : Texts.requireText(draft.title(), "título", 200),
                    draft.description() == null ? current.description() : Texts.optionalText(draft.description(), "descrição", 20_000),
                    draft.priority() == null ? current.priority() : priority(draft.priority()),
                    draft.specialty() == null ? current.specialty() : Texts.optionalText(draft.specialty(), "especialidade", 80),
                    draft.ownerAgentId() == null ? current.ownerAgentId() : owner(draft.ownerAgentId()),
                    draft.acceptanceCriteria() == null ? current.acceptanceCriteria() : criteria(draft.acceptanceCriteria()),
                    draft.tasks() == null ? current.tasks() : tasks(draft.tasks()),
                    current.status(), current.revision(), current.position(), current.workdir(), current.branch(), current.baseCommit(),
                    current.statusNote(), current.dependsOn(), current.createdAt(), now);
            repository.update(tx, next);
            if (draft.dependsOn() != null) {
                repository.setDependencies(tx, id, dependencies(tx, next, draft.dependsOn()));
            }
            return repository.find(tx, id).orElseThrow();
        });
        signals.changed(updated.projectId(), "features", id);
        return updated;
    }

    /** Remove uma feature que ainda não começou. Recusa quando outras dependem dela. */
    public void delete(String id) {
        Feature removed = database.write(tx -> {
            Feature current = repository.find(tx, id).orElseThrow(() -> ApiException.notFound("Feature", id));
            if (!current.status().editable()) {
                throw ApiException.conflict("feature_locked", "Só é possível excluir features planejadas, com falha ou canceladas.");
            }
            List<String> dependents = repository.byProject(tx, current.projectId()).stream()
                    .filter(f -> f.dependsOn().contains(id)).map(Feature::code).toList();
            if (!dependents.isEmpty()) {
                throw ApiException.conflict("feature_has_dependents",
                        "Outras features dependem desta: " + String.join(", ", dependents) + ". Remova a dependência antes.",
                        Map.of("dependents", dependents));
            }
            repository.delete(tx, id);
            return current;
        });
        signals.changed(removed.projectId(), "features", id);
    }

    /** Marca um critério de aceite como atendido ou não. Vale em qualquer estado, inclusive durante a revisão. */
    public Feature setCriterion(String id, int index, boolean met) {
        return toggle(id, index, met, true);
    }

    public Feature setTask(String id, int index, boolean done) {
        return toggle(id, index, done, false);
    }

    private Feature toggle(String id, int index, boolean value, boolean criterion) {
        String now = now();
        Feature updated = database.write(tx -> {
            Feature current = repository.find(tx, id).orElseThrow(() -> ApiException.notFound("Feature", id));
            List<Feature.Criterion> criteria = new ArrayList<>(current.acceptanceCriteria());
            List<Feature.Task> tasks = new ArrayList<>(current.tasks());
            int size = criterion ? criteria.size() : tasks.size();
            if (index < 0 || index >= size) {
                throw ApiException.invalid("invalid_index", "Item inexistente.");
            }
            if (criterion) {
                criteria.set(index, new Feature.Criterion(criteria.get(index).text(), value));
            } else {
                tasks.set(index, new Feature.Task(tasks.get(index).text(), value));
            }
            Feature next = current.withChecklists(criteria, tasks, now);
            repository.update(tx, next);
            return next;
        });
        signals.changed(updated.projectId(), "features", id);
        return updated;
    }

    /* ---------- sprints ---------- */

    public Sprint createSprint(String projectId, String name, String goal) {
        String now = now();
        Sprint sprint = database.write(tx -> {
            if (tx.scalarLong("SELECT COUNT(*) FROM project WHERE id = ?", projectId) == 0) {
                throw ApiException.notFound("Projeto", projectId);
            }
            String clean = Texts.requireText(name, "nome", 80);
            if (repository.sprintByName(tx, projectId, clean).isPresent()) {
                throw ApiException.conflict("sprint_exists", "Já existe uma sprint com esse nome.");
            }
            Sprint s = new Sprint(Ids.newId(), projectId, clean, Texts.optionalText(goal, "objetivo", 2_000),
                    repository.nextSprintPosition(tx, projectId), now, now);
            repository.insertSprint(tx, s);
            return s;
        });
        signals.changed(projectId, "features", sprint.id());
        return sprint;
    }

    public Sprint updateSprint(String id, String name, String goal) {
        String now = now();
        Sprint sprint = database.write(tx -> {
            Sprint current = repository.sprint(tx, id).orElseThrow(() -> ApiException.notFound("Sprint", id));
            String clean = name == null ? current.name() : Texts.requireText(name, "nome", 80);
            Optional<Sprint> same = repository.sprintByName(tx, current.projectId(), clean);
            if (same.isPresent() && !same.get().id().equals(id)) {
                throw ApiException.conflict("sprint_exists", "Já existe uma sprint com esse nome.");
            }
            Sprint next = new Sprint(id, current.projectId(), clean, goal == null ? current.goal() : Texts.optionalText(goal, "objetivo", 2_000),
                    current.position(), current.createdAt(), now);
            repository.updateSprint(tx, next);
            return next;
        });
        signals.changed(sprint.projectId(), "features", id);
        return sprint;
    }

    /** Remove a sprint; as features dela ficam sem sprint. */
    public void deleteSprint(String id) {
        Sprint sprint = database.write(tx -> {
            Sprint current = repository.sprint(tx, id).orElseThrow(() -> ApiException.notFound("Sprint", id));
            repository.deleteSprint(tx, id);
            return current;
        });
        signals.changed(sprint.projectId(), "features", id);
    }

    /* ---------- plano aprovado ---------- */

    /**
     * Cria ou atualiza sprints (pelo nome) e features (pelo código) a partir de um plano já validado. Roda na
     * transação de aprovação do plano. Features fora dos estados editáveis ficam como estão.
     */
    public ApplyResult applyPlan(Tx tx, String projectId, String planId, PlanContent content, Map<String, String> ownersByCode) {
        String now = now();
        Map<String, String> sprintIds = new HashMap<>();
        for (PlanContent.SprintItem item : content.sprints()) {
            String name = Texts.trimToNull(item.name());
            if (name == null) {
                continue;
            }
            Optional<Sprint> existing = repository.sprintByName(tx, projectId, name);
            if (existing.isPresent()) {
                Sprint s = existing.get();
                if (!Texts.isBlank(item.goal())) {
                    repository.updateSprint(tx, new Sprint(s.id(), projectId, s.name(), item.goal().strip(), s.position(), s.createdAt(), now));
                }
                sprintIds.put(key(name), s.id());
            } else {
                Sprint s = new Sprint(Ids.newId(), projectId, Texts.truncate(name, 80), Texts.orEmpty(item.goal()).strip(),
                        repository.nextSprintPosition(tx, projectId), now, now);
                repository.insertSprint(tx, s);
                sprintIds.put(key(name), s.id());
            }
        }

        int created = 0;
        int updated = 0;
        List<String> skipped = new ArrayList<>();
        Map<String, String> idsByCode = new HashMap<>();
        repository.byProject(tx, projectId).forEach(f -> idsByCode.put(key(f.code()), f.id()));
        List<Map.Entry<String, PlanContent.FeatureItem>> touched = new ArrayList<>();

        for (PlanContent.FeatureItem item : content.features()) {
            String sprintId = null;
            if (!Texts.isBlank(item.sprint())) {
                sprintId = sprintIds.get(key(item.sprint()));
                if (sprintId == null) {
                    Sprint s = repository.sprintByName(tx, projectId, item.sprint().strip()).orElse(null);
                    if (s == null) {
                        s = new Sprint(Ids.newId(), projectId, Texts.truncate(item.sprint().strip(), 80), "",
                                repository.nextSprintPosition(tx, projectId), now, now);
                        repository.insertSprint(tx, s);
                    }
                    sprintId = s.id();
                    sprintIds.put(key(item.sprint()), sprintId);
                }
            }
            String priority = Optional.ofNullable(PlanService.normalizePriority(item.priority())).orElse("MEDIUM");
            String owner = ownersByCode.get(item.code());
            Optional<Feature> existing = repository.byCode(tx, projectId, item.code());
            if (existing.isPresent() && !existing.get().status().editable()) {
                skipped.add(existing.get().code());
                continue;
            }
            if (existing.isPresent()) {
                Feature f = existing.get();
                Map<String, Boolean> met = new HashMap<>();
                f.acceptanceCriteria().forEach(c -> met.put(c.text().strip(), c.met()));
                Map<String, Boolean> done = new HashMap<>();
                f.tasks().forEach(t -> done.put(t.text().strip(), t.done()));
                Feature next = new Feature(f.id(), projectId, sprintId, planId, f.code(), Texts.truncate(item.title().strip(), 200),
                        Texts.orEmpty(item.description()).strip(), priority, Texts.truncate(Texts.orEmpty(item.specialty()).strip(), 80),
                        owner != null ? owner : f.ownerAgentId(),
                        item.criteria().stream().map(t -> new Feature.Criterion(t.strip(), met.getOrDefault(t.strip(), false))).toList(),
                        item.tasks().stream().map(t -> new Feature.Task(t.strip(), done.getOrDefault(t.strip(), false))).toList(),
                        f.status(), f.revision(), f.position(), f.workdir(), f.branch(), f.baseCommit(), f.statusNote(), f.dependsOn(),
                        f.createdAt(), now);
                repository.update(tx, next);
                updated++;
                touched.add(Map.entry(f.id(), item));
            } else {
                Feature f = new Feature(Ids.newId(), projectId, sprintId, planId, item.code().strip(), Texts.truncate(item.title().strip(), 200),
                        Texts.orEmpty(item.description()).strip(), priority, Texts.truncate(Texts.orEmpty(item.specialty()).strip(), 80), owner,
                        item.criteria().stream().map(t -> new Feature.Criterion(t.strip(), false)).toList(),
                        item.tasks().stream().map(t -> new Feature.Task(t.strip(), false)).toList(),
                        FeatureStatus.PLANNED, 0, repository.nextPosition(tx, projectId), null, null, null, null, List.of(), now, now);
                repository.insert(tx, f);
                idsByCode.put(key(f.code()), f.id());
                created++;
                touched.add(Map.entry(f.id(), item));
            }
        }
        for (Map.Entry<String, PlanContent.FeatureItem> entry : touched) {
            List<String> deps = new ArrayList<>();
            for (String code : entry.getValue().dependsOn()) {
                String depId = idsByCode.get(key(code));
                if (depId != null && !depId.equals(entry.getKey())) {
                    deps.add(depId);
                }
            }
            repository.setDependencies(tx, entry.getKey(), deps);
        }
        ensureAcyclic(tx, projectId);
        return new ApplyResult(planId, created, updated, skipped, repository.byProject(tx, projectId));
    }

    /** Avisa a interface depois que a transação de aprovação terminou. */
    public void announce(String projectId) {
        signals.changed(projectId, "features", null);
    }

    /* ---------- validação ---------- */

    private List<String> dependencies(Tx tx, Feature feature, List<String> requested) {
        if (requested == null) {
            return feature.dependsOn();
        }
        List<Feature> all = repository.byProject(tx, feature.projectId());
        Map<String, Feature> byId = new HashMap<>();
        Map<String, Feature> byCode = new HashMap<>();
        all.forEach(f -> {
            byId.put(f.id(), f);
            byCode.put(key(f.code()), f);
        });
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String ref : requested) {
            if (Texts.isBlank(ref)) {
                continue;
            }
            Feature dep = byId.containsKey(ref) ? byId.get(ref) : byCode.get(key(ref));
            if (dep == null) {
                throw ApiException.invalid("dependency_unknown", "Dependência não encontrada neste projeto: " + ref + ".");
            }
            if (dep.id().equals(feature.id())) {
                throw ApiException.invalid("dependency_self", "Uma feature não pode depender de si mesma.");
            }
            out.add(dep.id());
        }
        DependencyGraph graph = new DependencyGraph();
        for (Feature f : all) {
            graph.add(f.id(), f.id().equals(feature.id()) ? List.copyOf(out) : f.dependsOn());
        }
        if (!all.stream().anyMatch(f -> f.id().equals(feature.id()))) {
            graph.add(feature.id(), List.copyOf(out));
        }
        List<List<String>> cycles = graph.cycles();
        if (!cycles.isEmpty()) {
            Map<String, String> codes = new HashMap<>();
            all.forEach(f -> codes.put(f.id(), f.code()));
            codes.put(feature.id(), feature.code());
            List<String> path = cycles.get(0).stream().map(n -> codes.getOrDefault(n, n)).toList();
            throw ApiException.invalid("dependency_cycle", "Essa dependência cria um ciclo: " + String.join(" → ", path) + ".");
        }
        return List.copyOf(out);
    }

    private void ensureAcyclic(Tx tx, String projectId) {
        List<Feature> all = repository.byProject(tx, projectId);
        DependencyGraph graph = new DependencyGraph();
        all.forEach(f -> graph.add(f.id(), f.dependsOn()));
        if (!graph.cycles().isEmpty()) {
            throw ApiException.invalid("dependency_cycle", "O plano cria dependências circulares com features existentes.");
        }
    }

    private String checkCode(Tx tx, String projectId, String code, String selfId) {
        if (!CODE.matcher(code).matches()) {
            throw ApiException.invalid("invalid_code", "Código inválido: use até 16 letras, números, ponto, hífen ou sublinhado.");
        }
        Optional<Feature> same = repository.byCode(tx, projectId, code);
        if (same.isPresent() && !same.get().id().equals(selfId)) {
            throw ApiException.conflict("code_taken", "Já existe uma feature com o código " + code + ".");
        }
        return code;
    }

    private String nextCode(Tx tx, String projectId) {
        int n = (int) tx.scalarLong("SELECT COUNT(*) FROM feature WHERE project_id = ?", projectId) + 1;
        while (repository.byCode(tx, projectId, "F" + n).isPresent()) {
            n++;
        }
        return "F" + n;
    }

    private String sprintIn(Tx tx, String projectId, String sprintId) {
        String id = Texts.trimToNull(sprintId);
        if (id == null) {
            return null;
        }
        Sprint sprint = repository.sprint(tx, id).orElseThrow(() -> ApiException.notFound("Sprint", id));
        if (!sprint.projectId().equals(projectId)) {
            throw ApiException.invalid("sprint_other_project", "A sprint é de outro projeto.");
        }
        return id;
    }

    private String owner(String agentId) {
        String id = Texts.trimToNull(agentId);
        if (id == null) {
            return null;
        }
        return agents.get(id).id();
    }

    private static String priority(String raw) {
        String p = PlanService.normalizePriority(raw);
        if (p == null) {
            throw ApiException.invalid("invalid_priority", "Prioridade deve ser alta, média ou baixa.");
        }
        return p;
    }

    private static List<Feature.Criterion> criteria(List<Feature.Criterion> raw) {
        List<Feature.Criterion> out = new ArrayList<>();
        for (Feature.Criterion c : raw == null ? List.<Feature.Criterion>of() : raw) {
            if (c != null && !Texts.isBlank(c.text())) {
                out.add(new Feature.Criterion(Texts.truncate(c.text().strip(), 500), c.met()));
            }
        }
        if (out.size() > MAX_ITEMS) {
            throw ApiException.invalid("too_many_items", "Use no máximo " + MAX_ITEMS + " critérios.");
        }
        return out;
    }

    private static List<Feature.Task> tasks(List<Feature.Task> raw) {
        List<Feature.Task> out = new ArrayList<>();
        for (Feature.Task t : raw == null ? List.<Feature.Task>of() : raw) {
            if (t != null && !Texts.isBlank(t.text())) {
                out.add(new Feature.Task(Texts.truncate(t.text().strip(), 500), t.done()));
            }
        }
        if (out.size() > MAX_ITEMS) {
            throw ApiException.invalid("too_many_items", "Use no máximo " + MAX_ITEMS + " tarefas.");
        }
        return out;
    }

    static String label(FeatureStatus status) {
        return switch (status) {
            case PLANNED -> "planejada";
            case QUEUED -> "na fila";
            case IN_PROGRESS -> "em execução";
            case IN_REVIEW -> "aguardando revisão";
            case APPROVED -> "aprovada";
            case FAILED -> "com falha";
            case CANCELLED -> "cancelada";
        };
    }

    private static String key(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    }

    private String now() {
        return clock.instant().toString();
    }
}
