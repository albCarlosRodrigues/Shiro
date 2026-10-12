package dev.squaddesktop.features;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.squaddesktop.persistence.Row;
import dev.squaddesktop.persistence.Tx;
import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.shared.JsonValues;

/** Acesso às tabelas de sprints, features, dependências, etapas e revisões. */
class FeatureRepository {

    private static final String COLUMNS = """
            id, project_id, sprint_id, plan_id, code, title, description, priority, specialty, owner_agent_id,
            acceptance_criteria, tasks, status, revision, position, workdir, branch, base_commit, status_note, created_at, updated_at""";

    private final JsonCodec json;

    FeatureRepository(JsonCodec json) {
        this.json = json;
    }

    /* ---------- features ---------- */

    List<Feature> byProject(Tx tx, String projectId) {
        Map<String, List<String>> deps = dependencies(tx, projectId);
        return tx.query("""
                SELECT %s FROM feature f WHERE project_id = ?
                ORDER BY COALESCE((SELECT position FROM sprint s WHERE s.id = f.sprint_id), 1e9), position, code""".formatted(COLUMNS),
                row -> map(row, deps), projectId);
    }

    Optional<Feature> find(Tx tx, String id) {
        Optional<String> projectId = tx.queryOne("SELECT project_id FROM feature WHERE id = ?", row -> row.str("project_id"), id);
        if (projectId.isEmpty()) {
            return Optional.empty();
        }
        Map<String, List<String>> deps = dependencies(tx, projectId.get());
        return tx.queryOne("SELECT %s FROM feature WHERE id = ?".formatted(COLUMNS), row -> map(row, deps), id);
    }

    Optional<Feature> byCode(Tx tx, String projectId, String code) {
        Map<String, List<String>> deps = dependencies(tx, projectId);
        return tx.queryOne("SELECT %s FROM feature WHERE project_id = ? AND lower(code) = lower(?)".formatted(COLUMNS),
                row -> map(row, deps), projectId, code);
    }

    void insert(Tx tx, Feature f) {
        tx.update("""
                INSERT INTO feature (id, project_id, sprint_id, plan_id, code, title, description, priority, specialty, owner_agent_id,
                                     acceptance_criteria, tasks, status, revision, position, workdir, branch, base_commit, status_note,
                                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                f.id(), f.projectId(), f.sprintId(), f.planId(), f.code(), f.title(), f.description(), f.priority(), f.specialty(),
                f.ownerAgentId(), json.write(f.acceptanceCriteria()), json.write(f.tasks()), f.status(), f.revision(), f.position(),
                f.workdir(), f.branch(), f.baseCommit(), f.statusNote(), f.createdAt(), f.updatedAt());
    }

    void update(Tx tx, Feature f) {
        tx.update("""
                UPDATE feature SET sprint_id = ?, plan_id = ?, code = ?, title = ?, description = ?, priority = ?, specialty = ?,
                       owner_agent_id = ?, acceptance_criteria = ?, tasks = ?, status = ?, revision = ?, position = ?, workdir = ?,
                       branch = ?, base_commit = ?, status_note = ?, updated_at = ?
                WHERE id = ?""",
                f.sprintId(), f.planId(), f.code(), f.title(), f.description(), f.priority(), f.specialty(), f.ownerAgentId(),
                json.write(f.acceptanceCriteria()), json.write(f.tasks()), f.status(), f.revision(), f.position(), f.workdir(),
                f.branch(), f.baseCommit(), f.statusNote(), f.updatedAt(), f.id());
    }

    void setDependencies(Tx tx, String featureId, List<String> dependsOnIds) {
        tx.update("DELETE FROM feature_dependency WHERE feature_id = ?", featureId);
        for (String dep : dependsOnIds) {
            tx.update("INSERT OR IGNORE INTO feature_dependency (feature_id, depends_on_id) VALUES (?, ?)", featureId, dep);
        }
    }

    void delete(Tx tx, String id) {
        tx.update("DELETE FROM feature WHERE id = ?", id);
    }

    int nextPosition(Tx tx, String projectId) {
        return (int) tx.scalarLong("SELECT COALESCE(MAX(position), -1) + 1 FROM feature WHERE project_id = ?", projectId);
    }

    /** Ids das features de que cada feature depende, por projeto. */
    Map<String, List<String>> dependencies(Tx tx, String projectId) {
        Map<String, List<String>> out = new HashMap<>();
        tx.query("""
                SELECT d.feature_id, d.depends_on_id FROM feature_dependency d JOIN feature f ON f.id = d.feature_id
                WHERE f.project_id = ? ORDER BY d.depends_on_id""",
                row -> out.computeIfAbsent(row.str("feature_id"), k -> new ArrayList<>()).add(row.str("depends_on_id")), projectId);
        return out;
    }

    private Feature map(Row row, Map<String, List<String>> deps) throws SQLException {
        String id = row.str("id");
        return new Feature(id, row.str("project_id"), row.str("sprint_id"), row.str("plan_id"), row.str("code"), row.str("title"),
                row.strOrEmpty("description"), row.str("priority"), row.strOrEmpty("specialty"), row.str("owner_agent_id"),
                criteria(row.str("acceptance_criteria")), tasks(row.str("tasks")), FeatureStatus.valueOf(row.str("status")),
                row.integer("revision"), row.integer("position"), row.str("workdir"), row.str("branch"), row.str("base_commit"),
                row.str("status_note"), List.copyOf(deps.getOrDefault(id, List.of())), row.str("created_at"), row.str("updated_at"));
    }

    private List<Feature.Criterion> criteria(String raw) {
        List<Feature.Criterion> out = new ArrayList<>();
        for (Object item : JsonValues.list(Map.of("v", json.parse(raw)), "v")) {
            if (item instanceof Map<?, ?> m && m.get("text") instanceof String text) {
                out.add(new Feature.Criterion(text, Boolean.TRUE.equals(m.get("met"))));
            }
        }
        return out;
    }

    private List<Feature.Task> tasks(String raw) {
        List<Feature.Task> out = new ArrayList<>();
        for (Object item : JsonValues.list(Map.of("v", json.parse(raw)), "v")) {
            if (item instanceof Map<?, ?> m && m.get("text") instanceof String text) {
                out.add(new Feature.Task(text, Boolean.TRUE.equals(m.get("done"))));
            }
        }
        return out;
    }

    /* ---------- sprints ---------- */

    List<Sprint> sprints(Tx tx, String projectId) {
        return tx.query("SELECT id, project_id, name, goal, position, created_at, updated_at FROM sprint WHERE project_id = ? ORDER BY position, name",
                FeatureRepository::mapSprint, projectId);
    }

    Optional<Sprint> sprint(Tx tx, String id) {
        return tx.queryOne("SELECT id, project_id, name, goal, position, created_at, updated_at FROM sprint WHERE id = ?",
                FeatureRepository::mapSprint, id);
    }

    Optional<Sprint> sprintByName(Tx tx, String projectId, String name) {
        return tx.queryOne("SELECT id, project_id, name, goal, position, created_at, updated_at FROM sprint WHERE project_id = ? AND lower(name) = lower(?)",
                FeatureRepository::mapSprint, projectId, name);
    }

    void insertSprint(Tx tx, Sprint s) {
        tx.update("INSERT INTO sprint (id, project_id, name, goal, position, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                s.id(), s.projectId(), s.name(), s.goal(), s.position(), s.createdAt(), s.updatedAt());
    }

    void updateSprint(Tx tx, Sprint s) {
        tx.update("UPDATE sprint SET name = ?, goal = ?, position = ?, updated_at = ? WHERE id = ?", s.name(), s.goal(), s.position(), s.updatedAt(), s.id());
    }

    int nextSprintPosition(Tx tx, String projectId) {
        return (int) tx.scalarLong("SELECT COALESCE(MAX(position), -1) + 1 FROM sprint WHERE project_id = ?", projectId);
    }

    private static Sprint mapSprint(Row row) throws SQLException {
        return new Sprint(row.str("id"), row.str("project_id"), row.str("name"), row.strOrEmpty("goal"), row.integer("position"),
                row.str("created_at"), row.str("updated_at"));
    }

    /* ---------- etapas e revisões ---------- */

    List<FeatureStep> steps(Tx tx, String featureId) {
        return tx.query("""
                SELECT id, feature_id, seq, revision, agent_id, session_id, run_id, reason, status, handoff, created_at, updated_at
                FROM feature_step WHERE feature_id = ? ORDER BY seq""", this::mapStep, featureId);
    }

    Optional<FeatureStep> stepByRun(Tx tx, String runId) {
        return tx.queryOne("""
                SELECT id, feature_id, seq, revision, agent_id, session_id, run_id, reason, status, handoff, created_at, updated_at
                FROM feature_step WHERE run_id = ?""", this::mapStep, runId);
    }

    void insertStep(Tx tx, FeatureStep s) {
        tx.update("""
                INSERT INTO feature_step (id, feature_id, seq, revision, agent_id, session_id, run_id, reason, status, handoff, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                s.id(), s.featureId(), s.seq(), s.revision(), s.agentId(), s.sessionId(), s.runId(), s.reason(), s.status(),
                s.handoff() == null ? null : json.write(s.handoff()), s.createdAt(), s.updatedAt());
    }

    void finishStep(Tx tx, String stepId, String status, Map<String, Object> handoff, String now) {
        tx.update("UPDATE feature_step SET status = ?, handoff = ?, updated_at = ? WHERE id = ?",
                status, handoff == null ? null : json.write(handoff), now, stepId);
    }

    int stepsInRevision(Tx tx, String featureId, int revision) {
        return (int) tx.scalarLong("SELECT COUNT(*) FROM feature_step WHERE feature_id = ? AND revision = ?", featureId, revision);
    }

    Optional<FeatureStep> runningStep(Tx tx, String featureId) {
        return tx.queryOne("""
                SELECT id, feature_id, seq, revision, agent_id, session_id, run_id, reason, status, handoff, created_at, updated_at
                FROM feature_step WHERE feature_id = ? AND status = 'RUNNING' ORDER BY seq DESC LIMIT 1""", this::mapStep, featureId);
    }

    /* ---------- fila por projeto ---------- */

    boolean paused(Tx tx, String projectId) {
        return tx.scalarLong("SELECT COUNT(*) FROM feature_queue WHERE project_id = ? AND paused = 1", projectId) > 0;
    }

    String pauseReason(Tx tx, String projectId) {
        return tx.queryOne("SELECT reason FROM feature_queue WHERE project_id = ? AND paused = 1", row -> row.str("reason"), projectId).orElse(null);
    }

    void setPaused(Tx tx, String projectId, boolean paused, String reason, String now) {
        tx.update("""
                INSERT INTO feature_queue (project_id, paused, reason, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (project_id) DO UPDATE SET paused = excluded.paused, reason = excluded.reason, updated_at = excluded.updated_at""",
                projectId, paused ? 1 : 0, paused ? reason : null, now);
    }

    List<Feature> byStatus(Tx tx, FeatureStatus status) {
        List<String> projects = tx.query("SELECT DISTINCT project_id FROM feature WHERE status = ?", row -> row.str("project_id"), status.name());
        List<Feature> out = new ArrayList<>();
        for (String projectId : projects) {
            byProject(tx, projectId).stream().filter(f -> f.status() == status).forEach(out::add);
        }
        return out;
    }

    void deleteSprint(Tx tx, String id) {
        tx.update("UPDATE feature SET sprint_id = NULL WHERE sprint_id = ?", id);
        tx.update("DELETE FROM sprint WHERE id = ?", id);
    }

    int nextStepSeq(Tx tx, String featureId) {
        return (int) tx.scalarLong("SELECT COALESCE(MAX(seq), 0) + 1 FROM feature_step WHERE feature_id = ?", featureId);
    }

    @SuppressWarnings("unchecked")
    private FeatureStep mapStep(Row row) throws SQLException {
        String raw = row.str("handoff");
        Map<String, Object> handoff = null;
        if (raw != null) {
            Object parsed = json.parse(raw);
            handoff = parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        }
        return new FeatureStep(row.str("id"), row.str("feature_id"), row.integer("seq"), row.integer("revision"), row.str("agent_id"), row.str("session_id"),
                row.str("run_id"), row.strOrEmpty("reason"), row.str("status"), handoff, row.str("created_at"), row.str("updated_at"));
    }

    List<FeatureReview> reviews(Tx tx, String featureId) {
        return tx.query("SELECT id, feature_id, revision, decision, comment, created_at FROM feature_review WHERE feature_id = ? ORDER BY created_at",
                row -> new FeatureReview(row.str("id"), row.str("feature_id"), row.integer("revision"), row.str("decision"),
                        row.strOrEmpty("comment"), row.str("created_at")), featureId);
    }

    void insertReview(Tx tx, FeatureReview r) {
        tx.update("INSERT INTO feature_review (id, feature_id, revision, decision, comment, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                r.id(), r.featureId(), r.revision(), r.decision(), r.comment(), r.createdAt());
    }
}
