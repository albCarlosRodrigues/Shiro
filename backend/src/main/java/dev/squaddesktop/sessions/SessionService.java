package dev.squaddesktop.sessions;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.persistence.Row;
import dev.squaddesktop.persistence.Tx;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Ids;
import dev.squaddesktop.shared.Texts;

public class SessionService {

    /** Consulta feita antes de excluir ou reiniciar o contexto: a sessão tem execução em andamento? */
    public interface ActiveWorkProbe {
        boolean hasActiveRun(String sessionId);
    }

    public static final int MAX_MESSAGE_CHARS = 200_000;

    private static final String COLUMNS = "id, project_id, agent_id, title, claude_session_id, position, archived_at, created_at, updated_at, purpose, feature_id";

    private final Database database;
    private final Clock clock;
    private volatile ActiveWorkProbe activeWork = sessionId -> false;

    public SessionService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public void setActiveWorkProbe(ActiveWorkProbe probe) {
        this.activeWork = probe;
    }

    public List<Session> listByProject(String projectId, boolean includeArchived) {
        return database.read(tx -> tx.query(
                "SELECT %s FROM session WHERE project_id = ? AND purpose <> 'PLANNING' AND (? = 1 OR archived_at IS NULL) ORDER BY position, created_at".formatted(COLUMNS),
                SessionService::map, projectId, includeArchived ? 1 : 0));
    }

    /** Sessões mais recentes de todos os projetos ativos, para a tela inicial. */
    public List<Session> recent(int limit) {
        return database.read(tx -> tx.query("""
                SELECT s.id, s.project_id, s.agent_id, s.title, s.claude_session_id, s.position, s.archived_at, s.created_at, s.updated_at,
                       s.purpose, s.feature_id
                FROM session s JOIN project p ON p.id = s.project_id
                WHERE s.archived_at IS NULL AND p.archived_at IS NULL AND s.purpose <> 'PLANNING'
                ORDER BY s.updated_at DESC LIMIT ?""",
                SessionService::map, Math.max(1, Math.min(limit, 50))));
    }

    public Session get(String id) {
        return database.read(tx -> find(tx, id)).orElseThrow(() -> ApiException.notFound("Sessão", id));
    }

    public Session create(String projectId, String agentId, String title) {
        return create(projectId, agentId, title, Session.CHAT, null);
    }

    /**
     * Sessão interna: a vez de um agente na sala de planejamento (uma por agente e projeto, reaproveitada para o
     * agente manter o contexto) ou uma etapa de feature.
     */
    public Session systemSession(String projectId, String agentId, String purpose, String featureId, String title) {
        Optional<Session> existing = database.read(tx -> tx.queryOne(
                "SELECT %s FROM session WHERE project_id = ? AND agent_id = ? AND purpose = ? AND COALESCE(feature_id, '') = ? AND archived_at IS NULL"
                        .formatted(COLUMNS),
                SessionService::map, projectId, agentId, purpose, featureId == null ? "" : featureId));
        return existing.orElseGet(() -> create(projectId, agentId, title, purpose, featureId));
    }

    private Session create(String projectId, String agentId, String title, String purpose, String featureId) {
        String now = now();
        return database.write(tx -> {
            String projectName = tx.scalarString("SELECT name FROM project WHERE id = ? AND archived_at IS NULL", projectId);
            if (projectName == null) {
                throw ApiException.invalid("unknown_project", "O projeto não existe ou está arquivado.");
            }
            String agentName = tx.scalarString("SELECT name FROM agent WHERE id = ? AND archived_at IS NULL", agentId);
            if (agentName == null) {
                throw ApiException.invalid("unknown_agent", "O agente não existe ou está arquivado.");
            }
            String cleanTitle = Texts.isBlank(title) ? agentName : Texts.requireText(title, "título", 120);
            int position = (int) tx.scalarLong("SELECT COALESCE(MAX(position), -1) + 1 FROM session WHERE project_id = ?", projectId);
            Session session = new Session(Ids.newId(), projectId, agentId, cleanTitle, null, position, null, now, now, purpose, featureId);
            tx.update("""
                    INSERT INTO session (id, project_id, agent_id, title, claude_session_id, position, archived_at, created_at, updated_at,
                                         purpose, feature_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    session.id(), projectId, agentId, cleanTitle, null, position, null, now, now, purpose, featureId);
            return session;
        });
    }

    public Session rename(String id, String title) {
        String cleanTitle = Texts.requireText(title, "título", 120);
        return database.write(tx -> {
            Session c = find(tx, id).orElseThrow(() -> ApiException.notFound("Sessão", id));
            String now = now();
            tx.update("UPDATE session SET title = ?, updated_at = ? WHERE id = ?", cleanTitle, now, id);
            return c.withTitle(cleanTitle, now);
        });
    }

    /**
     * Esquece a sessão do Claude Code: a próxima mensagem começa um contexto novo.
     * O histórico de mensagens do aplicativo é mantido.
     */
    public Session resetContext(String id) {
        requireIdle(id, "reiniciar o contexto");
        return database.write(tx -> {
            Session c = find(tx, id).orElseThrow(() -> ApiException.notFound("Sessão", id));
            String now = now();
            tx.update("UPDATE session SET claude_session_id = NULL, updated_at = ? WHERE id = ?", now, id);
            appendMessage(tx, id, null, Message.Role.SYSTEM, "Contexto reiniciado. A próxima mensagem começa uma conversa nova com o agente.", now);
            return c.withoutClaudeSession(now);
        });
    }

    public void delete(String id) {
        requireIdle(id, "excluir a sessão");
        database.writeVoid(tx -> {
            find(tx, id).orElseThrow(() -> ApiException.notFound("Sessão", id));
            tx.update("DELETE FROM session WHERE id = ?", id);
        });
    }

    public List<Message> messages(String sessionId) {
        return database.read(tx -> tx.query(
                "SELECT id, session_id, run_id, role, content, seq, created_at FROM message WHERE session_id = ? ORDER BY seq",
                SessionService::mapMessage, sessionId));
    }

    /* ---------- uso interno pela orquestração de execuções ---------- */

    public Message appendMessage(Tx tx, String sessionId, String runId, Message.Role role, String content, String now) {
        int seq = (int) tx.scalarLong("SELECT COALESCE(MAX(seq), 0) + 1 FROM message WHERE session_id = ?", sessionId);
        Message message = new Message(Ids.newId(), sessionId, runId, role, content, seq, now);
        tx.update("INSERT INTO message (id, session_id, run_id, role, content, seq, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                message.id(), sessionId, runId, role, content, seq, now);
        tx.update("UPDATE session SET updated_at = ? WHERE id = ?", now, sessionId);
        return message;
    }

    public void rememberClaudeSession(Tx tx, String sessionId, String claudeSessionId) {
        tx.update("UPDATE session SET claude_session_id = ? WHERE id = ?", claudeSessionId, sessionId);
    }

    private void requireIdle(String sessionId, String action) {
        if (activeWork.hasActiveRun(sessionId)) {
            throw ApiException.conflict("session_busy", "Há uma execução em andamento nesta sessão. Cancele-a antes de " + action + ".");
        }
    }

    private static Optional<Session> find(Tx tx, String id) {
        return tx.queryOne("SELECT %s FROM session WHERE id = ?".formatted(COLUMNS), SessionService::map, id);
    }

    private static Session map(Row row) throws SQLException {
        return new Session(row.str("id"), row.str("project_id"), row.str("agent_id"), row.str("title"),
                row.str("claude_session_id"), row.integer("position"), row.str("archived_at"), row.str("created_at"), row.str("updated_at"),
                row.str("purpose"), row.str("feature_id"));
    }

    private static Message mapMessage(Row row) throws SQLException {
        return new Message(row.str("id"), row.str("session_id"), row.str("run_id"), Message.Role.valueOf(row.str("role")),
                row.str("content"), row.integer("seq"), row.str("created_at"));
    }

    private String now() {
        return clock.instant().toString();
    }
}
