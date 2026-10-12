package dev.squaddesktop.planning;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.squaddesktop.agents.Agent;
import dev.squaddesktop.agents.AgentRole;
import dev.squaddesktop.agents.AgentService;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.persistence.Row;
import dev.squaddesktop.persistence.Tx;
import dev.squaddesktop.plans.PlanService;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.projects.ProjectService;
import dev.squaddesktop.runs.ProjectSignals;
import dev.squaddesktop.runs.Run;
import dev.squaddesktop.runs.RunKind;
import dev.squaddesktop.runs.RunOrchestrator;
import dev.squaddesktop.runs.RunStatus;
import dev.squaddesktop.sessions.Message;
import dev.squaddesktop.sessions.Session;
import dev.squaddesktop.sessions.SessionService;
import dev.squaddesktop.settings.SettingsService;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Ids;
import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.shared.JsonValues;
import dev.squaddesktop.shared.Texts;
import dev.squaddesktop.squads.SquadMembers;

/**
 * Sala de planejamento: a pessoa conversa com a squad do projeto. Cada mensagem abre uma rodada; falam os agentes
 * mencionados (ou o comandante, sem menção), um por vez, e quem fala pode chamar outro com @apelido. Os agentes
 * rodam com permissões de leitura: propõem mudanças em documentos e planos em blocos estruturados, que viram
 * propostas e rascunhos para a pessoa revisar. Nada aqui inicia implementação.
 */
public class PlanningService implements RunOrchestrator.RunListener {

    /** Falas de agentes por rodada. */
    static final int MAX_TURNS = 6;
    static final int TRANSCRIPT_MESSAGES = 30;
    static final int TRANSCRIPT_CHARS = 4_000;

    public record PlanningMessage(String id, String projectId, int seq, String authorType, String agentId, String content,
            List<String> mentions, List<Map<String, Object>> attachments, String runId, String roundId, String createdAt) {
    }

    public record Round(String id, String projectId, String status, List<String> pending, List<String> spoken, String currentRun,
            String createdAt, String updatedAt) {
    }

    public record Participant(String agentId, String name, String handle, String role, String roleLabel, String specialty, String icon,
            String color, boolean commander) {
    }

    public record Room(String projectId, List<Participant> participants, List<PlanningMessage> messages, Round round, boolean hasMore) {
    }

    private final Database database;
    private final Clock clock;
    private final JsonCodec json;
    private final ProjectService projects;
    private final AgentService agents;
    private final SquadMembers members;
    private final SessionService sessions;
    private final RunOrchestrator runs;
    private final SettingsService settings;
    private final ProposalService proposals;
    private final PlanService plans;
    private final ProjectSignals signals;
    private final Object lock = new Object();
    private volatile boolean ready;

    public PlanningService(Database database, Clock clock, JsonCodec json, ProjectService projects, AgentService agents,
            SquadMembers members, SessionService sessions, RunOrchestrator runs, SettingsService settings, ProposalService proposals,
            PlanService plans, ProjectSignals signals) {
        this.database = database;
        this.clock = clock;
        this.json = json;
        this.projects = projects;
        this.agents = agents;
        this.members = members;
        this.sessions = sessions;
        this.runs = runs;
        this.settings = settings;
        this.proposals = proposals;
        this.plans = plans;
        this.signals = signals;
        runs.addListener(this);
    }

    /** Na inicialização: rodadas que ficaram abertas são encerradas, sem retomar falas. */
    public void reconcile() {
        synchronized (lock) {
            List<Round> open = database.read(tx -> tx.query(
                    "SELECT * FROM planning_round WHERE status = 'ACTIVE'", this::mapRound));
            for (Round round : open) {
                database.writeVoid(tx -> {
                    closeRound(tx, round.id(), "STOPPED");
                    appendSystem(tx, round.projectId(), round.id(), "A rodada foi interrompida porque o aplicativo foi encerrado. Envie uma nova mensagem para continuar.");
                });
            }
            ready = true;
        }
    }

    /* ---------- consulta ---------- */

    public Room room(String projectId, Integer beforeSeq, int limit) {
        Project project = projects.get(projectId);
        int size = Math.max(1, Math.min(limit, 300));
        List<PlanningMessage> page = database.read(tx -> tx.query("""
                SELECT * FROM (SELECT * FROM planning_message WHERE project_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?) ORDER BY seq""",
                this::mapMessage, projectId, beforeSeq == null ? Integer.MAX_VALUE : beforeSeq, size + 1));
        boolean more = page.size() > size;
        List<PlanningMessage> messages = more ? page.subList(1, page.size()) : page;
        return new Room(projectId, participants(project), List.copyOf(messages), activeRound(projectId).orElse(null), more);
    }

    private List<Participant> participants(Project project) {
        List<Agent> squad = members.of(project);
        Optional<Agent> commander = squad.stream().filter(a -> a.role() == AgentRole.COMMANDER).findFirst();
        return squad.stream().map(a -> new Participant(a.id(), a.name(), Mentions.handle(a.name()), a.role().name(), a.role().label(),
                a.specialty(), a.icon(), a.color(), commander.map(c -> c.id().equals(a.id())).orElse(false))).toList();
    }

    /* ---------- comandos ---------- */

    /** Mensagem da pessoa. Abre uma rodada com os agentes mencionados, ou com o comandante. */
    public Room post(String projectId, String content) {
        String text = Texts.requireText(content, "mensagem", 40_000);
        Project project = projects.get(projectId);
        if (project.archived()) {
            throw ApiException.invalid("archived_project", "O projeto está arquivado.");
        }
        List<Agent> squad = members.requireOf(project);
        synchronized (lock) {
            if (activeRound(projectId).isPresent()) {
                throw ApiException.conflict("round_active", "A squad ainda está respondendo. Aguarde ou interrompa a rodada antes de enviar outra mensagem.");
            }
            List<Agent> mentioned = Mentions.find(text, squad);
            List<Agent> speakers = new ArrayList<>(mentioned);
            if (speakers.isEmpty()) {
                speakers.add(squad.stream().filter(a -> a.role() == AgentRole.COMMANDER).findFirst().orElse(squad.get(0)));
            }
            String now = now();
            Round round = new Round(Ids.newId(), projectId, "ACTIVE", speakers.stream().map(Agent::id).toList(), List.of(), null, now, now);
            database.writeVoid(tx -> {
                tx.update("""
                        INSERT INTO planning_round (id, project_id, status, pending, spoken, current_run, created_at, updated_at)
                        VALUES (?, ?, 'ACTIVE', ?, '[]', NULL, ?, ?)""", round.id(), projectId, json.write(round.pending()), now, now);
                insertMessage(tx, projectId, "USER", null, text, mentioned.stream().map(Agent::id).toList(), List.of(), null, round.id());
            });
            nextTurn(round.id());
        }
        signals.changed(projectId, "planning", null);
        return room(projectId, null, 120);
    }

    /** Interrompe a rodada: cancela a fala em andamento e descarta as próximas. */
    public Room stop(String projectId) {
        String runToCancel = null;
        synchronized (lock) {
            Optional<Round> round = activeRound(projectId);
            if (round.isPresent()) {
                runToCancel = round.get().currentRun();
                database.writeVoid(tx -> {
                    closeRound(tx, round.get().id(), "STOPPED");
                    appendSystem(tx, projectId, round.get().id(), "Rodada interrompida por você.");
                });
            }
        }
        if (runToCancel != null) {
            runs.cancel(runToCancel);
        }
        signals.changed(projectId, "planning", null);
        return room(projectId, null, 120);
    }

    /* ---------- turnos ---------- */

    private void nextTurn(String roundId) {
        Round round = database.read(tx -> tx.queryOne("SELECT * FROM planning_round WHERE id = ?", this::mapRound, roundId)).orElse(null);
        if (round == null || !"ACTIVE".equals(round.status())) {
            return;
        }
        if (round.pending().isEmpty() || round.spoken().size() >= MAX_TURNS) {
            database.writeVoid(tx -> {
                if (!round.pending().isEmpty()) {
                    appendSystem(tx, round.projectId(), roundId, "Limite de " + MAX_TURNS + " falas por rodada atingido. Envie uma mensagem para continuar.");
                }
                closeRound(tx, roundId, "DONE");
            });
            return;
        }
        String agentId = round.pending().get(0);
        List<String> pending = new ArrayList<>(round.pending().subList(1, round.pending().size()));
        Project project = projects.get(round.projectId());
        Agent agent;
        try {
            agent = agents.get(agentId);
        } catch (ApiException e) {
            database.writeVoid(tx -> updateRound(tx, roundId, pending, round.spoken(), null));
            nextTurn(roundId);
            return;
        }
        List<Agent> squad = members.of(project);
        Session session = sessions.systemSession(project.id(), agent.id(), Session.PLANNING, null, "Sala de planejamento");
        boolean fresh = session.claudeSessionId() == null || settings.get().demoMode();
        String prompt = prompt(project, agent, squad, fresh);
        try {
            RunOrchestrator.Submission submission = runs.submit(session.id(), prompt, RunKind.PLANNING);
            List<String> spoken = new ArrayList<>(round.spoken());
            spoken.add(agent.id());
            database.writeVoid(tx -> updateRound(tx, roundId, pending, spoken, submission.run().id()));
        } catch (ApiException e) {
            database.writeVoid(tx -> {
                appendSystem(tx, project.id(), roundId, agent.name() + " não pôde responder: " + e.getMessage());
                closeRound(tx, roundId, "STOPPED");
            });
        }
    }

    @Override
    public void finished(Run run) {
        if (run.kind() != RunKind.PLANNING) {
            return;
        }
        String projectId;
        synchronized (lock) {
            Optional<Round> found = database.read(tx -> tx.queryOne("SELECT * FROM planning_round WHERE current_run = ?", this::mapRound, run.id()));
            if (found.isEmpty()) {
                return;
            }
            Round round = found.get();
            projectId = round.projectId();
            Agent agent = safeAgent(run.agentId());
            String name = agent == null ? "O agente" : agent.name();
            if (run.status() == RunStatus.COMPLETED) {
                record(round, run, agent);
                if (ready && "ACTIVE".equals(round.status())) {
                    nextTurn(round.id());
                }
            } else {
                String message = switch (run.status()) {
                    case CANCELLED -> name + ": fala cancelada.";
                    case INTERRUPTED -> name + ": fala interrompida porque o aplicativo foi encerrado.";
                    case TIMED_OUT -> name + " passou do tempo máximo e foi encerrado.";
                    default -> name + " falhou: " + Texts.orEmpty(run.failureMessage());
                };
                database.writeVoid(tx -> {
                    tx.update("UPDATE planning_round SET current_run = NULL, updated_at = ? WHERE id = ?", now(), round.id());
                    appendSystem(tx, round.projectId(), round.id(), message + (run.status() == RunStatus.CANCELLED ? "" : " A rodada foi encerrada; nada é repetido automaticamente."));
                    if ("ACTIVE".equals(round.status())) {
                        closeRound(tx, round.id(), "STOPPED");
                    }
                });
            }
        }
        signals.changed(projectId, "planning", null);
    }

    /** Grava a fala do agente e transforma os blocos em propostas, planos e novas chamadas. */
    private void record(Round round, Run run, Agent agent) {
        String text = finalText(run);
        Blocks.Parsed parsed = Blocks.parse(text, json);
        List<Map<String, Object>> attachments = new ArrayList<>();
        String messageId = Ids.newId();
        for (Blocks.Block block : parsed.blocks()) {
            if (block.data() == null) {
                attachments.add(attachment("warning", null, "Bloco squad-" + block.kind() + " ignorado", block.error()));
                continue;
            }
            try {
                switch (block.kind()) {
                    case Blocks.PROPOSAL -> {
                        StringBuilder problem = new StringBuilder();
                        Optional<ProposalService.Proposal> p = proposals.createFromAgent(round.projectId(), run.agentId(), messageId, block.data(), problem);
                        attachments.add(p.<Map<String, Object>>map(x -> attachment("proposal", x.id(), x.title(), x.summary()))
                                .orElseGet(() -> attachment("warning", null, "Proposta ignorada", problem.toString())));
                    }
                    case Blocks.PLAN -> {
                        PlanService.Plan plan = plans.createFromAgent(round.projectId(), run.agentId(), messageId, block.data());
                        long errors = plan.problems().stream().filter(pr -> pr.blocking()).count();
                        attachments.add(attachment("plan", plan.id(), "Plano " + plan.number(),
                                plan.content().features().size() + " features" + (errors == 0 ? "" : " · " + errors + " pendências")));
                    }
                    default -> attachments.add(attachment("warning", null, "Bloco squad-" + block.kind() + " ignorado",
                            "Esse tipo de bloco não é usado na sala de planejamento."));
                }
            } catch (ApiException e) {
                attachments.add(attachment("warning", null, "Bloco squad-" + block.kind() + " recusado", e.getMessage()));
            }
        }
        String content = parsed.text().isBlank() && attachments.isEmpty() ? "(sem resposta)" : parsed.text();
        Project project = projects.get(round.projectId());
        List<Agent> squad = members.of(project);
        List<Agent> called = Mentions.find(parsed.text(), squad).stream()
                .filter(a -> agent == null || !a.id().equals(agent.id())).toList();
        Round current = database.read(tx -> tx.queryOne("SELECT * FROM planning_round WHERE id = ?", this::mapRound, round.id())).orElse(round);
        List<String> pending = new ArrayList<>(current.pending());
        for (Agent a : called) {
            if (!pending.contains(a.id()) && !current.spoken().contains(a.id())) {
                pending.add(a.id());
            }
        }
        database.writeVoid(tx -> {
            insertMessageWithId(tx, messageId, round.projectId(), "AGENT", run.agentId(), content, called.stream().map(Agent::id).toList(),
                    attachments, run.id(), round.id());
            updateRound(tx, round.id(), pending, current.spoken(), null);
        });
    }

    private String finalText(Run run) {
        if (run.resultText() != null && !run.resultText().isBlank()) {
            return run.resultText();
        }
        List<Message> messages = sessions.messages(run.sessionId());
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (run.id().equals(m.runId()) && m.role() == Message.Role.AGENT) {
                return m.content();
            }
        }
        return "";
    }

    /* ---------- prompt ---------- */

    String prompt(Project project, Agent self, List<Agent> squad, boolean fresh) {
        List<PlanningMessage> all = database.read(tx -> tx.query("""
                SELECT * FROM (SELECT * FROM planning_message WHERE project_id = ? ORDER BY seq DESC LIMIT ?) ORDER BY seq""",
                this::mapMessage, project.id(), TRANSCRIPT_MESSAGES));
        List<PlanningMessage> transcript = all;
        if (!fresh) {
            int lastOwn = -1;
            for (PlanningMessage m : all) {
                if ("AGENT".equals(m.authorType()) && self.id().equals(m.agentId())) {
                    lastOwn = m.seq();
                }
            }
            int cut = lastOwn;
            transcript = all.stream().filter(m -> m.seq() > cut).toList();
        }
        boolean commander = self.role() == AgentRole.COMMANDER;
        StringBuilder out = new StringBuilder();
        out.append("# Sala de planejamento — ").append(project.name()).append("\n\n");
        out.append("Você é @").append(Mentions.handle(self.name())).append(commander ? ". Você é o comandante desta squad.\n" : ".\n");
        out.append("\n## Participantes\n- Pessoa (quem decide e aprova)\n");
        for (Agent a : squad) {
            out.append("- @").append(Mentions.handle(a.name())).append(" — ").append(a.role().label());
            if (!Texts.isBlank(a.specialty())) {
                out.append(", ").append(a.specialty().strip());
            }
            out.append('\n');
        }
        out.append(fresh ? "\n## Conversa (mais recentes)\n" : "\n## Novas mensagens desde a sua última fala\n");
        for (PlanningMessage m : transcript) {
            out.append("\n**").append(author(m, squad)).append("**: ").append(Texts.truncate(m.content().strip(), TRANSCRIPT_CHARS)).append('\n');
            for (Map<String, Object> att : m.attachments()) {
                out.append("  (anexo: ").append(JsonValues.str(att, "title")).append(" — ").append(Texts.orEmpty(JsonValues.str(att, "detail")))
                        .append(")\n");
            }
        }
        out.append("""

                ## Sua vez
                Responda ao que foi dirigido a você, de forma objetiva. Isto é planejamento: não crie nem altere arquivos e \
                não comece a implementação. A pessoa revisa e aprova tudo.

                Para propor mudança em um documento do projeto, inclua no fim da resposta um bloco:
                ```squad-proposta
                {"documento": "briefing|visao|escopo|fora_do_escopo|glossario|arquitetura|adr|documento", "titulo": "...", \
                "conteudo": "documento completo em Markdown", "resumo": "o que mudou e por quê"}
                ```
                Para chamar outro agente da squad, escreva o @apelido dele no texto; ele fala em seguida.
                """);
        if (commander) {
            out.append("""

                    Como comandante, quando houver informação suficiente, proponha o plano de features em um bloco:
                    ```squad-plano
                    {"resumo": "...", "sprints": [{"nome": "Sprint 1", "objetivo": "..."}], "features": [{"codigo": "F1", \
                    "titulo": "...", "descricao": "...", "prioridade": "alta|media|baixa", "especialidade": "...", \
                    "responsavel": "@Apelido", "sprint": "Sprint 1", "criterios": ["critério verificável"], "tarefas": ["..."], \
                    "depende_de": ["códigos"]}]}
                    ```
                    Cada feature precisa de responsável da squad e critérios de aceite verificáveis; não crie dependências circulares.
                    """);
        }
        return out.toString();
    }

    private static String author(PlanningMessage m, List<Agent> squad) {
        return switch (m.authorType()) {
            case "USER" -> "Pessoa";
            case "SYSTEM" -> "Sistema";
            default -> squad.stream().filter(a -> a.id().equals(m.agentId())).findFirst()
                    .map(a -> "@" + Mentions.handle(a.name())).orElse("Agente");
        };
    }

    /* ---------- persistência ---------- */

    private Optional<Round> activeRound(String projectId) {
        return database.read(tx -> tx.queryOne("SELECT * FROM planning_round WHERE project_id = ? AND status = 'ACTIVE' ORDER BY created_at DESC LIMIT 1",
                this::mapRound, projectId));
    }

    private void updateRound(Tx tx, String roundId, List<String> pending, List<String> spoken, String currentRun) {
        tx.update("UPDATE planning_round SET pending = ?, spoken = ?, current_run = ?, updated_at = ? WHERE id = ?",
                json.write(pending), json.write(spoken), currentRun, now(), roundId);
    }

    private void closeRound(Tx tx, String roundId, String status) {
        tx.update("UPDATE planning_round SET status = ?, pending = '[]', updated_at = ? WHERE id = ? AND status = 'ACTIVE'", status, now(), roundId);
    }

    private void appendSystem(Tx tx, String projectId, String roundId, String text) {
        insertMessage(tx, projectId, "SYSTEM", null, text, List.of(), List.of(), null, roundId);
    }

    private void insertMessage(Tx tx, String projectId, String authorType, String agentId, String content, List<String> mentions,
            List<Map<String, Object>> attachments, String runId, String roundId) {
        insertMessageWithId(tx, Ids.newId(), projectId, authorType, agentId, content, mentions, attachments, runId, roundId);
    }

    private void insertMessageWithId(Tx tx, String id, String projectId, String authorType, String agentId, String content,
            List<String> mentions, List<Map<String, Object>> attachments, String runId, String roundId) {
        int seq = (int) tx.scalarLong("SELECT COALESCE(MAX(seq), 0) + 1 FROM planning_message WHERE project_id = ?", projectId);
        tx.update("""
                INSERT INTO planning_message (id, project_id, seq, author_type, agent_id, content, mentions, attachments, run_id, round_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, projectId, seq, authorType, agentId, content, json.write(mentions), json.write(attachments), runId, roundId, now());
    }

    private static Map<String, Object> attachment(String type, String id, String title, String detail) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        if (id != null) {
            out.put("id", id);
        }
        out.put("title", title);
        out.put("detail", Texts.orEmpty(detail));
        return out;
    }

    private Round mapRound(Row row) throws SQLException {
        return new Round(row.str("id"), row.str("project_id"), row.str("status"), JsonValues.strings(json.parse(row.str("pending"))),
                JsonValues.strings(json.parse(row.str("spoken"))), row.str("current_run"), row.str("created_at"), row.str("updated_at"));
    }

    @SuppressWarnings("unchecked")
    private PlanningMessage mapMessage(Row row) throws SQLException {
        List<Map<String, Object>> attachments = new ArrayList<>();
        for (Object item : JsonValues.list(json.parse(row.str("attachments")))) {
            if (item instanceof Map<?, ?> m) {
                attachments.add((Map<String, Object>) m);
            }
        }
        return new PlanningMessage(row.str("id"), row.str("project_id"), row.integer("seq"), row.str("author_type"), row.str("agent_id"),
                row.str("content"), JsonValues.strings(json.parse(row.str("mentions"))), attachments, row.str("run_id"), row.str("round_id"),
                row.str("created_at"));
    }

    private Agent safeAgent(String id) {
        try {
            return id == null ? null : agents.get(id);
        } catch (ApiException e) {
            return null;
        }
    }

    private String now() {
        return clock.instant().toString();
    }
}
