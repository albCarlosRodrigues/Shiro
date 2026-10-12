package dev.squaddesktop.runs;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.squaddesktop.persistence.Row;
import dev.squaddesktop.persistence.Tx;
import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.shared.JsonValues;

class RunRepository {

    private static final String COLUMNS = """
            id, session_id, project_id, agent_id, kind, access, status, demo, prompt, cwd, command, claude_session_id, model,
            cli_version, exit_code, failure_code, failure_message, result_text, cost_usd, input_tokens, output_tokens,
            cache_read_tokens, cache_creation_tokens, num_turns, duration_ms, permission_denials, queued_at, started_at, finished_at""";

    private static final String UNFINISHED = "('QUEUED', 'STARTING', 'RUNNING', 'CANCELLING')";

    private final JsonCodec json;

    RunRepository(JsonCodec json) {
        this.json = json;
    }

    void insert(Tx tx, Run r) {
        tx.update("""
                INSERT INTO run (id, session_id, project_id, agent_id, kind, access, status, demo, prompt, cwd, queued_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                r.id(), r.sessionId(), r.projectId(), r.agentId(), r.kind(), r.access(), r.status(), r.demo(), r.prompt(),
                r.cwd(), r.queuedAt());
    }

    Optional<Run> find(Tx tx, String id) {
        return tx.queryOne("SELECT %s FROM run WHERE id = ?".formatted(COLUMNS), this::map, id);
    }

    List<Run> bySession(Tx tx, String sessionId, int limit) {
        return tx.query("""
                SELECT %s FROM (SELECT rowid AS seq_no, * FROM run WHERE session_id = ? ORDER BY queued_at DESC, rowid DESC LIMIT ?)
                ORDER BY queued_at, seq_no""".formatted(COLUMNS), this::map, sessionId, limit);
    }

    List<Run> queued(Tx tx) {
        return tx.query("SELECT %s FROM run WHERE status = 'QUEUED' ORDER BY queued_at, rowid".formatted(COLUMNS), this::map);
    }

    List<Run> unfinished(Tx tx) {
        return tx.query("SELECT %s FROM run WHERE status IN %s ORDER BY queued_at, rowid".formatted(COLUMNS, UNFINISHED), this::map);
    }

    boolean sessionBusy(Tx tx, String sessionId) {
        return tx.scalarLong("SELECT COUNT(*) FROM run WHERE session_id = ? AND status IN " + UNFINISHED, sessionId) > 0;
    }

    boolean projectBusy(Tx tx, String projectId) {
        return tx.scalarLong("SELECT COUNT(*) FROM run WHERE project_id = ? AND status IN " + UNFINISHED, projectId) > 0;
    }

    /** Troca o estado somente se o atual for o esperado. @return true quando a troca aconteceu */
    boolean transition(Tx tx, String id, RunStatus from, RunStatus to) {
        if (!from.canTransitionTo(to)) {
            return false;
        }
        return tx.update("UPDATE run SET status = ? WHERE id = ? AND status = ?", to, id, from) == 1;
    }

    void markRunning(Tx tx, String id, String claudeSessionId, String model, String cliVersion, String startedAt) {
        tx.update("""
                UPDATE run SET status = CASE WHEN status = 'STARTING' THEN 'RUNNING' ELSE status END,
                               claude_session_id = ?, model = ?, cli_version = ?, started_at = COALESCE(started_at, ?)
                WHERE id = ?""",
                claudeSessionId, model, cliVersion, startedAt, id);
    }

    void finish(Tx tx, String id, RunStatus status, RunOutcome outcome, AgentEvent.Usage usage,
            List<Map<String, Object>> denials, String finishedAt) {
        tx.update("""
                UPDATE run
                SET status = ?, exit_code = ?, failure_code = ?, failure_message = ?, result_text = ?, command = ?,
                    cost_usd = ?, input_tokens = ?, output_tokens = ?, cache_read_tokens = ?, cache_creation_tokens = ?,
                    num_turns = ?, duration_ms = ?, permission_denials = ?, finished_at = ?
                WHERE id = ?""",
                status, outcome.exitCode(), outcome.failureCode(), outcome.failureMessage(), outcome.resultText(),
                outcome.command() == null ? null : json.write(outcome.command()),
                usage == null ? null : usage.costUsd(), usage == null ? null : usage.inputTokens(),
                usage == null ? null : usage.outputTokens(), usage == null ? null : usage.cacheReadTokens(),
                usage == null ? null : usage.cacheCreationTokens(), usage == null ? null : usage.numTurns(),
                usage == null ? null : usage.durationMs(), json.write(denials), finishedAt, id);
    }

    void deleteTextDeltas(Tx tx, String runId) {
        tx.update("DELETE FROM run_event WHERE run_id = ? AND type = 'text.delta'", runId);
    }

    @SuppressWarnings("unchecked")
    private Run map(Row row) throws SQLException {
        String command = row.str("command");
        List<Map<String, Object>> denials = new ArrayList<>();
        String rawDenials = row.str("permission_denials");
        if (rawDenials != null && !rawDenials.isBlank()) {
            for (Object item : JsonValues.list(Map.of("v", json.parse(rawDenials)), "v")) {
                if (item instanceof Map<?, ?> m) {
                    denials.add((Map<String, Object>) m);
                }
            }
        }
        return new Run(row.str("id"), row.str("session_id"), row.str("project_id"), row.str("agent_id"),
                RunKind.valueOf(row.str("kind")), RunAccess.valueOf(row.str("access")), RunStatus.valueOf(row.str("status")),
                row.bool("demo"), row.str("prompt"), row.str("cwd"),
                command == null ? List.of() : JsonValues.strings(Map.of("v", json.parse(command)), "v"),
                row.str("claude_session_id"), row.str("model"), row.str("cli_version"), row.integerOrNull("exit_code"),
                row.str("failure_code"), row.str("failure_message"), row.str("result_text"), row.dblOrNull("cost_usd"),
                row.lngOrNull("input_tokens"), row.lngOrNull("output_tokens"), row.lngOrNull("cache_read_tokens"),
                row.lngOrNull("cache_creation_tokens"), row.integerOrNull("num_turns"), row.lngOrNull("duration_ms"),
                denials, row.str("queued_at"), row.str("started_at"), row.str("finished_at"));
    }
}
