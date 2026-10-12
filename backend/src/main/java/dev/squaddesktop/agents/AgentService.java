package dev.squaddesktop.agents;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Ids;
import dev.squaddesktop.shared.JsonCodec;

public class AgentService {

    /** Onde o agente está em uso. Implementado pelas squads, para barrar exclusões e trocas de papel que as quebrariam. */
    public interface UsageProbe {
        /** Nomes das squads ativas que têm este agente em algum assento. */
        List<String> activeSquadsUsing(String agentId);
    }

    private final Database database;
    private final Clock clock;
    private final AgentRepository repository;
    private volatile UsageProbe usage = agentId -> List.of();

    public AgentService(Database database, JsonCodec json, Clock clock) {
        this.database = database;
        this.clock = clock;
        this.repository = new AgentRepository(json);
    }

    public void setUsageProbe(UsageProbe probe) {
        this.usage = probe;
    }

    public List<Agent> list(boolean includeArchived) {
        return database.read(tx -> repository.list(tx, includeArchived));
    }

    public Agent get(String id) {
        return database.read(tx -> repository.find(tx, id)).orElseThrow(() -> ApiException.notFound("Agente", id));
    }

    public Agent create(AgentDraft draft) {
        return create(draft, false);
    }

    public Agent create(AgentDraft draft, boolean sample) {
        AgentDraft d = AgentRules.validate(draft);
        String now = now();
        Agent agent = fromDraft(Ids.newId(), d, sample, null, now, now);
        database.writeVoid(tx -> repository.insert(tx, agent));
        return agent;
    }

    public Agent createFromTemplate(String templateKey) {
        AgentTemplate template = AgentTemplates.find(templateKey)
                .orElseThrow(() -> ApiException.notFound("Modelo de agente", templateKey));
        return create(template.draft());
    }

    public Agent update(String id, AgentDraft draft) {
        AgentDraft d = AgentRules.validate(draft);
        return database.write(tx -> {
            Agent current = repository.find(tx, id).orElseThrow(() -> ApiException.notFound("Agente", id));
            if (current.role() != d.role()) {
                List<String> squads = usage.activeSquadsUsing(id);
                if (!squads.isEmpty()) {
                    throw ApiException.conflict("agent_in_use",
                            "Este agente ocupa um assento em " + describe(squads) + ". Troque-o na squad antes de mudar o papel.",
                            Map.of("squads", squads));
                }
            }
            Agent updated = fromDraft(id, d, current.sample(), current.archivedAt(), current.createdAt(), now());
            repository.update(tx, updated);
            return updated;
        });
    }

    public Agent duplicate(String id) {
        Agent source = get(id);
        String now = now();
        Agent copy = new Agent(Ids.newId(), copyName(source.name()), source.role(), source.specialty(), source.icon(), source.color(),
                source.responsibility(), source.personality(), source.instructions(), source.guidelines(), source.model(),
                source.effort(), source.permissionMode(), source.tools(), source.allowedTools(), source.disallowedTools(),
                source.templateKey(), false, null, now, now);
        database.writeVoid(tx -> repository.insert(tx, copy));
        return copy;
    }

    /** Acrescenta uma regra de permissão liberada, sem duplicar. Usado quando a pessoa libera uma ferramenta negada. */
    public Agent allowTool(String id, String rule) {
        if (!AgentRules.isValidRule(rule)) {
            throw ApiException.invalid("invalid_tool", "Regra de ferramenta inválida.");
        }
        return database.write(tx -> {
            Agent c = repository.find(tx, id).orElseThrow(() -> ApiException.notFound("Agente", id));
            if (c.allowedTools().contains(rule)) {
                return c;
            }
            List<String> allowed = new ArrayList<>(c.allowedTools());
            allowed.add(rule);
            List<String> denied = c.disallowedTools().stream().filter(r -> !r.equals(rule)).toList();
            Agent updated = new Agent(c.id(), c.name(), c.role(), c.specialty(), c.icon(), c.color(), c.responsibility(),
                    c.personality(), c.instructions(), c.guidelines(), c.model(), c.effort(), c.permissionMode(), c.tools(),
                    allowed, denied, c.templateKey(), c.sample(), c.archivedAt(), c.createdAt(), now());
            repository.update(tx, updated);
            return updated;
        });
    }

    public enum Removal { DELETED, ARCHIVED }

    /**
     * Remove o agente. Recusa enquanto ele ocupar assento em squad ativa. Um agente que já tem conversas é
     * arquivado em vez de apagado, para o histórico continuar legível.
     */
    public Removal delete(String id) {
        List<String> squads = usage.activeSquadsUsing(id);
        if (!squads.isEmpty()) {
            throw ApiException.conflict("agent_in_use",
                    "Este agente ocupa um assento em " + describe(squads) + ". Substitua-o na squad antes de excluir.",
                    Map.of("squads", squads));
        }
        return database.write(tx -> {
            Agent c = repository.find(tx, id).orElseThrow(() -> ApiException.notFound("Agente", id));
            boolean referenced = repository.sessionCount(tx, id) > 0
                    || tx.scalarLong("SELECT COUNT(*) FROM squad WHERE commander_id = ? OR requirements_id = ? OR architecture_id = ?", id, id, id) > 0
                    || tx.scalarLong("SELECT COUNT(*) FROM squad_operator WHERE agent_id = ?", id) > 0;
            if (!referenced) {
                repository.delete(tx, id);
                return Removal.DELETED;
            }
            repository.update(tx, new Agent(c.id(), c.name(), c.role(), c.specialty(), c.icon(), c.color(), c.responsibility(),
                    c.personality(), c.instructions(), c.guidelines(), c.model(), c.effort(), c.permissionMode(), c.tools(),
                    c.allowedTools(), c.disallowedTools(), c.templateKey(), c.sample(), now(), c.createdAt(), now()));
            return Removal.ARCHIVED;
        });
    }

    public long count() {
        return database.read(repository::count);
    }

    private static Agent fromDraft(String id, AgentDraft d, boolean sample, String archivedAt, String createdAt, String updatedAt) {
        return new Agent(id, d.name(), d.role(), d.specialty(), d.icon(), d.color(), d.responsibility(), d.personality(),
                d.instructions(), d.guidelines(), d.model(), d.effort(), d.permissionMode(), d.tools(), d.allowedTools(),
                d.disallowedTools(), d.templateKey(), sample, archivedAt, createdAt, updatedAt);
    }

    private static String copyName(String name) {
        String suffix = " (cópia)";
        return name.length() + suffix.length() > 80 ? name.substring(0, 80 - suffix.length()) + suffix : name + suffix;
    }

    private static String describe(List<String> squads) {
        return squads.size() == 1 ? "\"" + squads.get(0) + "\"" : squads.size() + " squads (" + String.join(", ", squads) + ")";
    }

    private String now() {
        return clock.instant().toString();
    }
}
