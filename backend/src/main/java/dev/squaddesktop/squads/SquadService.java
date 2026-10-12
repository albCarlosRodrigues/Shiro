package dev.squaddesktop.squads;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import dev.squaddesktop.agents.Agent;
import dev.squaddesktop.agents.AgentRole;
import dev.squaddesktop.agents.AgentService;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.persistence.Row;
import dev.squaddesktop.persistence.Tx;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Ids;
import dev.squaddesktop.shared.Texts;

public class SquadService implements AgentService.UsageProbe {

    private static final String COLUMNS = """
            id, name, description, commander_id, requirements_id, architecture_id, sample, archived_at, created_at, updated_at""";

    private final Database database;
    private final AgentService agents;
    private final Clock clock;

    public SquadService(Database database, AgentService agents, Clock clock) {
        this.database = database;
        this.agents = agents;
        this.clock = clock;
        agents.setUsageProbe(this);
    }

    public List<Squad> list(boolean includeArchived) {
        return database.read(tx -> {
            List<Squad> bare = tx.query("SELECT %s FROM squad WHERE (? = 1 OR archived_at IS NULL) ORDER BY lower(name)".formatted(COLUMNS),
                    row -> map(row, List.of()), includeArchived ? 1 : 0);
            List<Squad> out = new ArrayList<>();
            for (Squad squad : bare) {
                out.add(withOperators(tx, squad));
            }
            return out;
        });
    }

    public Squad get(String id) {
        return database.read(tx -> find(tx, id)).orElseThrow(() -> ApiException.notFound("Squad", id));
    }

    public Squad create(SquadDraft draft) {
        return create(draft, false);
    }

    public Squad create(SquadDraft draft, boolean sample) {
        SquadDraft d = validate(draft);
        String now = now();
        Squad squad = new Squad(Ids.newId(), d.name(), d.description(), d.commanderId(), d.requirementsId(), d.architectureId(),
                d.operatorIds(), sample, null, now, now);
        database.writeVoid(tx -> {
            tx.update("""
                    INSERT INTO squad (id, name, description, commander_id, requirements_id, architecture_id, sample, archived_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    squad.id(), squad.name(), squad.description(), squad.commanderId(), squad.requirementsId(),
                    squad.architectureId(), squad.sample(), null, now, now);
            replaceOperators(tx, squad.id(), squad.operatorIds());
        });
        return squad;
    }

    public Squad update(String id, SquadDraft draft) {
        SquadDraft d = validate(draft);
        return database.write(tx -> {
            Squad current = find(tx, id).orElseThrow(() -> ApiException.notFound("Squad", id));
            String now = now();
            tx.update("""
                    UPDATE squad SET name = ?, description = ?, commander_id = ?, requirements_id = ?, architecture_id = ?, updated_at = ?
                    WHERE id = ?""",
                    d.name(), d.description(), d.commanderId(), d.requirementsId(), d.architectureId(), now, id);
            replaceOperators(tx, id, d.operatorIds());
            return new Squad(id, d.name(), d.description(), d.commanderId(), d.requirementsId(), d.architectureId(),
                    d.operatorIds(), current.sample(), current.archivedAt(), current.createdAt(), now);
        });
    }

    /** Exclui a squad. Os projetos que a usavam ficam sem squad; os agentes continuam cadastrados. */
    public void delete(String id) {
        database.writeVoid(tx -> {
            find(tx, id).orElseThrow(() -> ApiException.notFound("Squad", id));
            tx.update("DELETE FROM squad WHERE id = ?", id);
        });
    }

    @Override
    public List<String> activeSquadsUsing(String agentId) {
        return database.read(tx -> tx.query("""
                SELECT DISTINCT s.name FROM squad s
                LEFT JOIN squad_operator o ON o.squad_id = s.id
                WHERE s.archived_at IS NULL
                  AND (s.commander_id = ? OR s.requirements_id = ? OR s.architecture_id = ? OR o.agent_id = ?)
                ORDER BY lower(s.name)""",
                row -> row.str("name"), agentId, agentId, agentId, agentId));
    }

    public long count() {
        return database.read(tx -> tx.scalarLong("SELECT COUNT(*) FROM squad"));
    }

    /** Confere a composição: cada assento com um agente ativo do papel certo, ao menos um operador, sem repetições. */
    SquadDraft validate(SquadDraft draft) {
        if (draft == null) {
            throw ApiException.invalid("required", "Envie os dados da squad.");
        }
        String name = Texts.requireText(draft.name(), "nome", 120);
        String description = Texts.optionalText(draft.description(), "descrição", 2_000);
        requireSeat(draft.commanderId(), AgentRole.COMMANDER, "comandante");
        requireSeat(draft.requirementsId(), AgentRole.REQUIREMENTS, "especialista em requisitos");
        requireSeat(draft.architectureId(), AgentRole.ARCHITECTURE, "especialista em arquitetura");
        LinkedHashSet<String> operators = new LinkedHashSet<>();
        if (draft.operatorIds() != null) {
            for (String operatorId : draft.operatorIds()) {
                if (Texts.isBlank(operatorId)) {
                    continue;
                }
                if (!operators.add(operatorId)) {
                    throw ApiException.invalid("duplicate_operator", "Um operador aparece mais de uma vez na squad.");
                }
                requireSeat(operatorId, AgentRole.OPERATOR, "operador");
            }
        }
        if (operators.isEmpty()) {
            throw ApiException.invalid("missing_operator", "A squad precisa de ao menos um operador.");
        }
        if (operators.size() > 12) {
            throw ApiException.invalid("too_many_operators", "Uma squad aceita até 12 operadores.");
        }
        return new SquadDraft(name, description, draft.commanderId(), draft.requirementsId(), draft.architectureId(), List.copyOf(operators));
    }

    private void requireSeat(String agentId, AgentRole role, String seat) {
        if (Texts.isBlank(agentId)) {
            throw ApiException.invalid("missing_seat", "Escolha o " + seat + " da squad.");
        }
        Agent agent;
        try {
            agent = agents.get(agentId);
        } catch (ApiException e) {
            throw ApiException.invalid("unknown_agent", "O agente escolhido como " + seat + " não existe mais.");
        }
        if (agent.archived()) {
            throw ApiException.invalid("archived_agent", "O agente \"" + agent.name() + "\" está arquivado e não pode ocupar um assento.");
        }
        if (agent.role() != role) {
            throw ApiException.invalid("wrong_role", "O agente \"" + agent.name() + "\" tem o papel " + agent.role().label()
                    + " e não pode ser " + seat + ".");
        }
    }

    private static void replaceOperators(Tx tx, String squadId, List<String> operatorIds) {
        tx.update("DELETE FROM squad_operator WHERE squad_id = ?", squadId);
        int position = 0;
        for (String operatorId : operatorIds) {
            tx.update("INSERT INTO squad_operator (squad_id, agent_id, position) VALUES (?, ?, ?)", squadId, operatorId, position++);
        }
    }

    private static Optional<Squad> find(Tx tx, String id) {
        return tx.queryOne("SELECT %s FROM squad WHERE id = ?".formatted(COLUMNS), row -> map(row, List.of()), id)
                .map(squad -> withOperators(tx, squad));
    }

    private static Squad withOperators(Tx tx, Squad s) {
        List<String> operators = tx.query("SELECT agent_id FROM squad_operator WHERE squad_id = ? ORDER BY position",
                row -> row.str("agent_id"), s.id());
        return new Squad(s.id(), s.name(), s.description(), s.commanderId(), s.requirementsId(), s.architectureId(), operators,
                s.sample(), s.archivedAt(), s.createdAt(), s.updatedAt());
    }

    private static Squad map(Row row, List<String> operators) throws SQLException {
        return new Squad(row.str("id"), row.str("name"), row.strOrEmpty("description"), row.str("commander_id"),
                row.str("requirements_id"), row.str("architecture_id"), operators, row.bool("sample"), row.str("archived_at"),
                row.str("created_at"), row.str("updated_at"));
    }

    private String now() {
        return clock.instant().toString();
    }
}
