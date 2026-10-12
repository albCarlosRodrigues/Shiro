package dev.squaddesktop.runs;

import java.sql.SQLException;
import java.util.List;

import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.persistence.Row;

/** Histórico dos eventos de execução. O id crescente serve de cursor para retomar a transmissão. */
public class EventStore {

    private final Database database;

    public EventStore(Database database) {
        this.database = database;
    }

    public StoredEvent append(String runId, String sessionId, String type, String payload, String createdAt) {
        long id = database.write(tx -> tx.insert(
                "INSERT INTO run_event (run_id, session_id, type, payload, created_at) VALUES (?, ?, ?, ?, ?)",
                runId, sessionId, type, payload, createdAt));
        return new StoredEvent(id, runId, sessionId, type, payload, createdAt);
    }

    /** Eventos gravados depois do cursor, em ordem, de todas as sessões. */
    public List<StoredEvent> after(long afterId, int limit) {
        return database.read(tx -> tx.query(
                "SELECT id, run_id, session_id, type, payload, created_at FROM run_event WHERE id > ? ORDER BY id LIMIT ?",
                EventStore::map, afterId, clamp(limit)));
    }

    /** Os eventos mais recentes de uma sessão, devolvidos em ordem cronológica. */
    public List<StoredEvent> recentBySession(String sessionId, int limit) {
        return database.read(tx -> tx.query("""
                SELECT id, run_id, session_id, type, payload, created_at FROM (
                    SELECT id, run_id, session_id, type, payload, created_at FROM run_event
                    WHERE session_id = ? ORDER BY id DESC LIMIT ?
                ) ORDER BY id""",
                EventStore::map, sessionId, clamp(limit)));
    }

    public List<StoredEvent> byRun(String runId) {
        return database.read(tx -> tx.query(
                "SELECT id, run_id, session_id, type, payload, created_at FROM run_event WHERE run_id = ? ORDER BY id",
                EventStore::map, runId));
    }

    public long lastId() {
        return database.read(tx -> tx.scalarLong("SELECT COALESCE(MAX(id), 0) FROM run_event"));
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 5_000));
    }

    private static StoredEvent map(Row row) throws SQLException {
        return new StoredEvent(row.lng("id"), row.str("run_id"), row.str("session_id"), row.str("type"), row.str("payload"),
                row.str("created_at"));
    }
}
