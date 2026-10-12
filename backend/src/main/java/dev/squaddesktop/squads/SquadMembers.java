package dev.squaddesktop.squads;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.squaddesktop.agents.Agent;
import dev.squaddesktop.agents.AgentRole;
import dev.squaddesktop.agents.AgentService;
import dev.squaddesktop.planning.Mentions;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Texts;

/** Membros da squad de um projeto e a resolução de um responsável escrito por extenso ("Operador Java/Spring", "@QA"…). */
public class SquadMembers {

    private final SquadService squads;
    private final AgentService agents;

    public SquadMembers(SquadService squads, AgentService agents) {
        this.squads = squads;
        this.agents = agents;
    }

    /** Membros ativos, na ordem: comandante, requisitos, arquitetura, operadores. Vazio quando o projeto não tem squad. */
    public List<Agent> of(Project project) {
        if (project.squadId() == null) {
            return List.of();
        }
        Squad squad;
        try {
            squad = squads.get(project.squadId());
        } catch (ApiException e) {
            return List.of();
        }
        List<Agent> out = new ArrayList<>();
        for (String id : squad.memberIds()) {
            try {
                Agent agent = agents.get(id);
                if (agent.archivedAt() == null) {
                    out.add(agent);
                }
            } catch (ApiException ignored) {
                // agente removido: a squad será corrigida quando for editada
            }
        }
        return out;
    }

    public List<Agent> requireOf(Project project) {
        List<Agent> members = of(project);
        if (members.isEmpty()) {
            throw ApiException.invalid("squad_required", "Escolha a squad do projeto antes de planejar ou executar.");
        }
        return members;
    }

    public Optional<Agent> commander(Project project) {
        return of(project).stream().filter(a -> a.role() == AgentRole.COMMANDER).findFirst();
    }

    /**
     * Encontra o agente por id, nome, apelido de menção, especialidade ou papel. Procura primeiro na squad e depois
     * nos demais agentes ativos.
     */
    public Optional<Agent> resolve(String reference, List<Agent> squad) {
        String ref = Texts.trimToNull(reference);
        if (ref == null) {
            return Optional.empty();
        }
        String key = key(ref.startsWith("@") ? ref.substring(1) : ref);
        List<List<Agent>> pools = List.of(squad, agents.list(false));
        for (List<Agent> pool : pools) {
            for (Agent a : pool) {
                if (a.id().equals(ref) || key(a.name()).equals(key) || key(Mentions.handle(a.name())).equals(key)) {
                    return Optional.of(a);
                }
            }
        }
        for (Agent a : squad) {
            if (!a.specialty().isBlank() && key(a.specialty()).equals(key)) {
                return Optional.of(a);
            }
        }
        for (Agent a : squad) {
            if (a.role() == AgentRole.OPERATOR && key(a.name()).contains(key) && key.length() >= 2) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    private static String key(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }
}
