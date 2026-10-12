package dev.squaddesktop.agents;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import dev.squaddesktop.persistence.Row;
import dev.squaddesktop.persistence.Tx;
import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.shared.JsonValues;

class AgentRepository {

    private static final String COLUMNS = """
            id, name, role, specialty, icon, color, responsibility, personality, instructions, guidelines, model, effort,
            permission_mode, tools, allowed_tools, disallowed_tools, template_key, sample, archived_at, created_at, updated_at""";

    private final JsonCodec json;

    AgentRepository(JsonCodec json) {
        this.json = json;
    }

    List<Agent> list(Tx tx, boolean includeArchived) {
        return tx.query("""
                SELECT %s FROM agent WHERE (? = 1 OR archived_at IS NULL)
                ORDER BY CASE role WHEN 'COMMANDER' THEN 0 WHEN 'REQUIREMENTS' THEN 1 WHEN 'ARCHITECTURE' THEN 2 ELSE 3 END, lower(name)"""
                .formatted(COLUMNS), this::map, includeArchived ? 1 : 0);
    }

    Optional<Agent> find(Tx tx, String id) {
        return tx.queryOne("SELECT %s FROM agent WHERE id = ?".formatted(COLUMNS), this::map, id);
    }

    void insert(Tx tx, Agent a) {
        tx.update("""
                INSERT INTO agent (id, name, role, specialty, icon, color, responsibility, personality, instructions, guidelines,
                                   model, effort, permission_mode, tools, allowed_tools, disallowed_tools, template_key, sample,
                                   archived_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                a.id(), a.name(), a.role(), a.specialty(), a.icon(), a.color(), a.responsibility(), a.personality(),
                a.instructions(), a.guidelines(), a.model(), a.effort(), a.permissionMode(),
                a.tools() == null ? null : json.write(a.tools()), json.write(a.allowedTools()), json.write(a.disallowedTools()),
                a.templateKey(), a.sample(), a.archivedAt(), a.createdAt(), a.updatedAt());
    }

    void update(Tx tx, Agent a) {
        tx.update("""
                UPDATE agent
                SET name = ?, role = ?, specialty = ?, icon = ?, color = ?, responsibility = ?, personality = ?, instructions = ?,
                    guidelines = ?, model = ?, effort = ?, permission_mode = ?, tools = ?, allowed_tools = ?, disallowed_tools = ?,
                    template_key = ?, archived_at = ?, updated_at = ?
                WHERE id = ?""",
                a.name(), a.role(), a.specialty(), a.icon(), a.color(), a.responsibility(), a.personality(), a.instructions(),
                a.guidelines(), a.model(), a.effort(), a.permissionMode(),
                a.tools() == null ? null : json.write(a.tools()), json.write(a.allowedTools()), json.write(a.disallowedTools()),
                a.templateKey(), a.archivedAt(), a.updatedAt(), a.id());
    }

    void delete(Tx tx, String id) {
        tx.update("DELETE FROM agent WHERE id = ?", id);
    }

    long sessionCount(Tx tx, String agentId) {
        return tx.scalarLong("SELECT COUNT(*) FROM session WHERE agent_id = ?", agentId);
    }

    long count(Tx tx) {
        return tx.scalarLong("SELECT COUNT(*) FROM agent");
    }

    private Agent map(Row row) throws SQLException {
        String tools = row.str("tools");
        return new Agent(row.str("id"), row.str("name"), AgentRole.valueOf(row.str("role")), row.strOrEmpty("specialty"),
                row.str("icon"), row.str("color"), row.strOrEmpty("responsibility"), row.strOrEmpty("personality"),
                row.strOrEmpty("instructions"), row.strOrEmpty("guidelines"), row.str("model"), row.str("effort"),
                row.str("permission_mode"), tools == null ? null : strings(tools), strings(row.str("allowed_tools")),
                strings(row.str("disallowed_tools")), row.str("template_key"), row.bool("sample"), row.str("archived_at"),
                row.str("created_at"), row.str("updated_at"));
    }

    private List<String> strings(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return JsonValues.strings(java.util.Map.of("v", json.parse(raw)), "v");
    }
}
