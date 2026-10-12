package dev.squaddesktop.features;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.squaddesktop.agents.Agent;
import dev.squaddesktop.agents.AgentService;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.planning.Blocks;
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
import dev.squaddesktop.shared.Texts;
import dev.squaddesktop.squads.SquadMembers;

/**
 * Executa features autorizadas: escolhe a próxima da fila (dependências aprovadas, prioridade), roda as etapas pelos
 * agentes da squad com passagens registradas e leva a feature à revisão humana.
 *
 * <ul>
 * <li>Nada roda sem autorização explícita; aprovar um plano não autoriza execução.</li>
 * <li>Sem o modo isolado há um único escritor por projeto: uma feature por vez, na pasta do projeto.</li>
 * <li>No modo isolado cada feature usa um git worktree com branch própria, e a aprovação integra com merge.</li>
 * <li>Uma falha nunca é repetida sozinha; cancelar não é falha; pausar a fila não cancela o que já roda.</li>
 * <li>Só a aprovação humana conclui a feature e libera as que dependem dela.</li>
 * <li>Depois de um reinício, filas com features pendentes ficam pausadas até a pessoa retomar.</li>
 * </ul>
 */
public class FeatureRunner implements RunOrchestrator.RunListener {

    private static final Logger LOG = Logger.getLogger(FeatureRunner.class.getName());

    /** Etapas encadeadas por passagem antes de a feature ir obrigatoriamente para a revisão. */
    static final int MAX_CHAIN = 4;

    public record ChangesView(boolean available, String reason, String workdir, String branch, List<GitWorkspace.Change> files) {
    }

    private final Database database;
    private final Clock clock;
    private final JsonCodec json;
    private final FeatureService features;
    private final FeatureRepository repository;
    private final ProjectService projects;
    private final AgentService agents;
    private final SquadMembers members;
    private final SessionService sessions;
    private final RunOrchestrator runs;
    private final SettingsService settings;
    private final GitWorkspace git;
    private final ProjectSignals signals;
    private final Path worktreesRoot;
    private final Object lock = new Object();
    private volatile boolean ready;

    public FeatureRunner(Database database, Clock clock, JsonCodec json, FeatureService features, ProjectService projects,
            AgentService agents, SquadMembers members, SessionService sessions, RunOrchestrator runs, SettingsService settings,
            GitWorkspace git, ProjectSignals signals, Path worktreesRoot) {
        this.database = database;
        this.clock = clock;
        this.json = json;
        this.features = features;
        this.repository = features.repository;
        this.projects = projects;
        this.agents = agents;
        this.members = members;
        this.sessions = sessions;
        this.runs = runs;
        this.settings = settings;
        this.git = git;
        this.signals = signals;
        this.worktreesRoot = worktreesRoot;
        runs.addListener(this);
    }

    /* ---------- inicialização ---------- */

    /**
     * Chamado na inicialização, depois de as execuções sem desfecho serem marcadas como interrompidas. Pausa as filas
     * com features pendentes: nada recomeça sem a pessoa pedir.
     */
    public void reconcile() {
        synchronized (lock) {
            for (Feature f : database.read(tx -> repository.byStatus(tx, FeatureStatus.IN_PROGRESS))) {
                Optional<FeatureStep> step = database.read(tx -> repository.runningStep(tx, f.id()));
                Run run = step.map(s -> s.runId() == null ? null : safeRun(s.runId())).orElse(null);
                if (run == null || run.status().terminal()) {
                    String now = now();
                    database.writeVoid(tx -> {
                        step.ifPresent(s -> repository.finishStep(tx, s.id(), "CANCELLED", null, now));
                        repository.update(tx, f.withStatus(FeatureStatus.FAILED,
                                "Interrompida: o aplicativo foi encerrado durante a execução. Confira a pasta e autorize de novo se quiser continuar.", now));
                    });
                }
            }
            Set<String> pending = new HashSet<>();
            database.read(tx -> repository.byStatus(tx, FeatureStatus.QUEUED)).forEach(f -> pending.add(f.projectId()));
            String now = now();
            for (String projectId : pending) {
                database.writeVoid(tx -> repository.setPaused(tx, projectId, true,
                        "O aplicativo foi reiniciado com features na fila. Retome a fila quando quiser continuar.", now));
            }
            ready = true;
        }
    }

    /* ---------- comandos ---------- */

    /** Autoriza a execução de uma feature: ela entra na fila e começa quando as dependências estiverem aprovadas. */
    public Feature authorize(String featureId) {
        Feature result;
        synchronized (lock) {
            Feature f = features.get(featureId);
            if (!f.status().authorizable()) {
                throw ApiException.conflict("feature_not_authorizable", "A feature " + f.code() + " está " + FeatureService.label(f.status()) + ".");
            }
            checkRunnable(f);
            String now = now();
            Feature queued = f.withStatus(FeatureStatus.QUEUED, waitingNote(f), now);
            database.writeVoid(tx -> repository.update(tx, queued));
            result = queued;
        }
        signals.changed(result.projectId(), "features", featureId);
        schedule(result.projectId());
        return features.get(featureId);
    }

    /** Autoriza todas as features planejadas do projeto que têm responsável. Devolve quantas entraram na fila. */
    public int authorizeAll(String projectId) {
        int count = 0;
        List<String> problems = new ArrayList<>();
        synchronized (lock) {
            for (Feature f : features.list(projectId)) {
                if (f.status() != FeatureStatus.PLANNED) {
                    continue;
                }
                try {
                    checkRunnable(f);
                } catch (ApiException e) {
                    problems.add(f.code() + ": " + e.getMessage());
                    continue;
                }
                String now = now();
                database.writeVoid(tx -> repository.update(tx, f.withStatus(FeatureStatus.QUEUED, waitingNote(f), now)));
                count++;
            }
        }
        if (count == 0 && !problems.isEmpty()) {
            throw ApiException.invalid("nothing_authorized", "Nenhuma feature pôde ser autorizada. " + String.join(" ", problems));
        }
        signals.changed(projectId, "features", null);
        schedule(projectId);
        return count;
    }

    public void pause(String projectId) {
        projects.get(projectId);
        database.writeVoid(tx -> repository.setPaused(tx, projectId, true, "Fila pausada por você. O que já está rodando continua.", now()));
        signals.changed(projectId, "features", null);
    }

    public void resume(String projectId) {
        projects.get(projectId);
        database.writeVoid(tx -> repository.setPaused(tx, projectId, false, null, now()));
        signals.changed(projectId, "features", null);
        schedule(projectId);
    }

    /** Cancela: tira da fila ou encerra a etapa em andamento. Não desfaz alterações já feitas na pasta. */
    public Feature cancel(String featureId) {
        String runToCancel = null;
        Feature f;
        synchronized (lock) {
            f = features.get(featureId);
            if (f.status() == FeatureStatus.QUEUED) {
                Feature cancelled = f.withStatus(FeatureStatus.CANCELLED, "Removida da fila por você.", now());
                database.writeVoid(tx -> repository.update(tx, cancelled));
            } else if (f.status() == FeatureStatus.IN_PROGRESS) {
                runToCancel = database.read(tx -> repository.runningStep(tx, featureId)).map(FeatureStep::runId).orElse(null);
                if (runToCancel == null) {
                    Feature cancelled = f.withStatus(FeatureStatus.CANCELLED, "Cancelada por você.", now());
                    database.writeVoid(tx -> repository.update(tx, cancelled));
                }
            } else {
                throw ApiException.conflict("feature_not_cancellable", "Só é possível cancelar features na fila ou em execução.");
            }
        }
        if (runToCancel != null) {
            runs.cancel(runToCancel); // o fim da execução marca a feature como cancelada
        }
        signals.changed(f.projectId(), "features", featureId);
        return features.get(featureId);
    }

    /** Aprova a entrega. No modo isolado, registra o commit da feature e integra com merge; conflito impede a aprovação. */
    public Feature approve(String featureId, String comment) {
        Feature f;
        synchronized (lock) {
            f = features.get(featureId);
            if (f.status() != FeatureStatus.IN_REVIEW) {
                throw ApiException.conflict("feature_not_in_review", "Só é possível aprovar features aguardando revisão.");
            }
            String note = "Aprovada por você.";
            boolean cleanup = false;
            if (f.branch() != null && f.workdir() != null) {
                Project project = projects.get(f.projectId());
                Path repo = Path.of(project.folderPath());
                Path worktree = Path.of(f.workdir());
                GitWorkspace.MergeResult merge;
                try {
                    git.commitAll(worktree, "feat(" + f.code() + "): " + f.title());
                    merge = git.merge(repo, f.branch(), "Integra a feature " + f.code() + ": " + f.title());
                } catch (GitWorkspace.GitException e) {
                    throw ApiException.conflict("merge_failed", e.getMessage());
                }
                if (!merge.merged()) {
                    String reason = merge.message();
                    Feature noted = f.withStatus(FeatureStatus.IN_REVIEW, "Integração pendente: " + reason, now());
                    database.writeVoid(tx -> repository.update(tx, noted));
                    signals.changed(f.projectId(), "features", featureId);
                    throw ApiException.conflict("merge_conflict",
                            reason + " O merge foi desfeito; resolva na branch " + f.branch() + " ou peça ajustes ao agente.",
                            Map.of("conflicts", merge.conflicts(), "branch", f.branch()));
                }
                note = "Aprovada por você e integrada (" + Texts.truncate(merge.commit(), 10) + ").";
                cleanup = true;
            }
            String now = now();
            Feature approved = (cleanup ? f.withWork(null, null, f.baseCommit(), now) : f).withStatus(FeatureStatus.APPROVED, note, now);
            Feature finalF = f;
            database.writeVoid(tx -> {
                repository.update(tx, approved);
                repository.insertReview(tx, new FeatureReview(Ids.newId(), featureId, finalF.revision(), "APPROVED",
                        Texts.orEmpty(Texts.trimToNull(comment)), now));
            });
            if (cleanup) {
                git.cleanup(Path.of(projects.get(f.projectId()).folderPath()), Path.of(f.workdir()), f.branch(), true);
            }
        }
        signals.changed(f.projectId(), "features", featureId);
        schedule(f.projectId());
        return features.get(featureId);
    }

    /** Pede ajustes: a feature volta para a fila com o comentário, e o responsável retoma o trabalho. */
    public Feature requestChanges(String featureId, String comment) {
        String text = Texts.requireText(comment, "comentário", 8_000);
        Feature f;
        synchronized (lock) {
            f = features.get(featureId);
            if (f.status() != FeatureStatus.IN_REVIEW) {
                throw ApiException.conflict("feature_not_in_review", "Só é possível pedir ajustes em features aguardando revisão.");
            }
            String now = now();
            Feature requeued = f.withRevision(f.revision() + 1, now).withStatus(FeatureStatus.QUEUED,
                    "Ajustes pedidos na revisão " + (f.revision() + 1) + ".", now);
            Feature finalF = f;
            database.writeVoid(tx -> {
                repository.insertReview(tx, new FeatureReview(Ids.newId(), featureId, finalF.revision(), "CHANGES_REQUESTED", text, now));
                repository.update(tx, requeued);
            });
        }
        signals.changed(f.projectId(), "features", featureId);
        schedule(f.projectId());
        return features.get(featureId);
    }

    /** Arquivos alterados desde a linha de base da feature. */
    public ChangesView changes(String featureId) {
        Feature f = features.get(featureId);
        Path dir = workdirOf(f);
        if (dir == null) {
            return new ChangesView(false, "O projeto não tem pasta definida.", null, null, List.of());
        }
        if (f.baseCommit() == null) {
            return new ChangesView(false, git.isRepository(dir)
                    ? "Ainda não há linha de base: a feature não começou a executar."
                    : "A pasta não é um repositório git; confira as alterações pela passagem do agente.", dir.toString(), f.branch(), List.of());
        }
        try {
            return new ChangesView(true, null, dir.toString(), f.branch(), git.changes(dir, f.baseCommit()));
        } catch (GitWorkspace.GitException e) {
            return new ChangesView(false, e.getMessage(), dir.toString(), f.branch(), List.of());
        }
    }

    public String diff(String featureId, String path) {
        Feature f = features.get(featureId);
        Path dir = workdirOf(f);
        if (dir == null || f.baseCommit() == null) {
            throw ApiException.invalid("diff_unavailable", "Não há linha de base para comparar.");
        }
        try {
            return git.diff(dir, f.baseCommit(), Texts.requireText(path, "arquivo", 1_000));
        } catch (GitWorkspace.GitException e) {
            throw ApiException.unavailable("git_failed", e.getMessage());
        }
    }

    /* ---------- agendamento ---------- */

    /** Inicia as features que podem começar agora neste projeto. */
    public void schedule(String projectId) {
        if (!ready) {
            return;
        }
        synchronized (lock) {
            Project project;
            try {
                project = projects.get(projectId);
            } catch (ApiException e) {
                return;
            }
            if (project.archived() || database.read(tx -> repository.paused(tx, projectId))) {
                return;
            }
            List<Feature> all = features.list(projectId);
            boolean isolated = isolated(project);
            int limit = isolated ? Math.max(1, settings.get().maxConcurrentRuns()) : 1;
            long running = all.stream().filter(f -> f.status() == FeatureStatus.IN_PROGRESS).count();
            Map<String, Feature> byId = new HashMap<>();
            all.forEach(f -> byId.put(f.id(), f));
            Map<String, Integer> order = new HashMap<>();
            for (int i = 0; i < all.size(); i++) {
                order.put(all.get(i).id(), i);
            }
            List<Feature> candidates = all.stream().filter(f -> f.status() == FeatureStatus.QUEUED)
                    .sorted(Comparator.comparing((Feature f) -> sprintRank(f, all)).thenComparing(Feature::priorityRank)
                            .thenComparing(f -> order.get(f.id())))
                    .toList();
            for (Feature f : candidates) {
                List<String> waiting = f.dependsOn().stream().map(byId::get)
                        .filter(d -> d != null && d.status() != FeatureStatus.APPROVED).map(Feature::code).toList();
                if (!waiting.isEmpty()) {
                    String note = "Aguardando aprovação de " + String.join(", ", waiting) + ".";
                    if (!note.equals(f.statusNote())) {
                        database.writeVoid(tx -> repository.update(tx, f.withStatus(FeatureStatus.QUEUED, note, now())));
                    }
                    continue;
                }
                if (running >= limit) {
                    String note = isolated ? "Na fila: limite de execuções simultâneas atingido." : "Na fila: uma feature por vez nesta pasta.";
                    if (!note.equals(f.statusNote())) {
                        database.writeVoid(tx -> repository.update(tx, f.withStatus(FeatureStatus.QUEUED, note, now())));
                    }
                    continue;
                }
                if (startFirstStep(f, project, isolated)) {
                    running++;
                }
            }
        }
        signals.changed(projectId, "features", null);
    }

    private int sprintRank(Feature f, List<Feature> all) {
        if (f.sprintId() == null) {
            return Integer.MAX_VALUE;
        }
        List<String> seen = new ArrayList<>();
        for (Feature x : all) {
            if (x.sprintId() != null && !seen.contains(x.sprintId())) {
                seen.add(x.sprintId());
            }
        }
        return seen.indexOf(f.sprintId());
    }

    /** Primeira etapa (ou retomada após ajustes/falha): o responsável da feature. */
    private boolean startFirstStep(Feature f, Project project, boolean isolated) {
        Feature current = f;
        String now = now();
        try {
            if (isolated && current.workdir() == null) {
                String branch = "squad/" + current.code().toLowerCase(Locale.ROOT);
                GitWorkspace.Worktree wt = git.worktree(Path.of(project.folderPath()),
                        worktreesRoot.resolve(project.id()).resolve(current.code().toLowerCase(Locale.ROOT)), branch);
                current = current.withWork(wt.path().toString(), wt.branch(), wt.baseTree(), now);
            } else if (!isolated && current.baseCommit() == null && project.folderPath() != null
                    && git.isRepository(Path.of(project.folderPath()))) {
                current = current.withWork(null, null, git.snapshot(Path.of(project.folderPath())), now);
            }
        } catch (GitWorkspace.GitException e) {
            fail(current, "Não foi possível preparar a pasta de trabalho: " + e.getMessage());
            return false;
        }
        Feature prepared = current;
        database.writeVoid(tx -> repository.update(tx, prepared));

        String reviewComment = null;
        if (prepared.revision() > 0) {
            List<FeatureReview> reviews = database.read(tx -> repository.reviews(tx, prepared.id()));
            reviewComment = reviews.stream().filter(r -> "CHANGES_REQUESTED".equals(r.decision())).reduce((a, b) -> b)
                    .map(FeatureReview::comment).orElse(null);
            List<FeatureStep> steps = database.read(tx -> repository.steps(tx, prepared.id()));
            boolean alreadyStartedRevision = steps.stream().anyMatch(s -> s.revision() == prepared.revision());
            if (alreadyStartedRevision) {
                reviewComment = null; // retomada depois de falha: o comentário já foi entregue
            }
        }
        String reason = prepared.revision() > 0 && reviewComment != null ? "Ajustes pedidos na revisão" : "Início da feature";
        if (prepared.statusNote() != null && prepared.statusNote().startsWith("Interrompida")) {
            reason = "Retomada depois de interrupção";
        }
        return startStep(prepared, project, prepared.ownerAgentId(), reason, null, null, reviewComment);
    }

    private boolean startStep(Feature f, Project project, String agentId, String reason, Agent previousAgent,
            Map<String, Object> previousHandoff, String reviewComment) {
        Agent agent;
        try {
            agent = agents.get(agentId);
        } catch (ApiException e) {
            fail(f, "O agente responsável não existe mais. Escolha outro e autorize de novo.");
            return false;
        }
        if (agent.archived()) {
            fail(f, "O agente " + agent.name() + " foi arquivado. Escolha outro responsável e autorize de novo.");
            return false;
        }
        Path workdir = workdirOf(f);
        if (workdir == null) {
            fail(f, "O projeto não tem pasta definida.");
            return false;
        }
        List<Feature> all = features.list(f.projectId());
        List<Feature> deps = all.stream().filter(x -> f.dependsOn().contains(x.id())).toList();
        String sprintName = f.sprintId() == null ? null
                : database.read(tx -> repository.sprint(tx, f.sprintId())).map(Sprint::name).orElse(null);
        int seq = database.read(tx -> repository.nextStepSeq(tx, f.id()));
        List<Agent> squad = members.of(project);
        String prompt = FeaturePrompts.step(new FeaturePrompts.StepContext(f, sprintName, deps, agent, seq, reason, workdir.toString(),
                f.branch(), previousAgent, previousHandoff, reviewComment, squad));
        Session session = sessions.systemSession(f.projectId(), agent.id(), Session.FEATURE, f.id(),
                Texts.truncate(f.code() + " · " + f.title(), 120));
        RunOrchestrator.Submission submission;
        try {
            submission = runs.submit(session.id(), prompt, RunKind.EXECUTION, f.workdir() == null ? null : workdir);
        } catch (ApiException e) {
            fail(f, e.getMessage());
            return false;
        }
        String now = now();
        FeatureStep step = new FeatureStep(Ids.newId(), f.id(), seq, f.revision(), agent.id(), session.id(), submission.run().id(),
                reason, "RUNNING", null, now, now);
        database.writeVoid(tx -> {
            repository.insertStep(tx, step);
            repository.update(tx, f.withStatus(FeatureStatus.IN_PROGRESS, "Etapa " + seq + ": " + agent.name() + " trabalhando.", now));
        });
        return true;
    }

    /* ---------- fim das etapas ---------- */

    @Override
    public void finished(Run run) {
        if (run.kind() != RunKind.EXECUTION) {
            return;
        }
        String projectId;
        synchronized (lock) {
            Optional<FeatureStep> found = database.read(tx -> repository.stepByRun(tx, run.id()));
            if (found.isEmpty() || !"RUNNING".equals(found.get().status())) {
                return;
            }
            FeatureStep step = found.get();
            Feature f = features.get(step.featureId());
            projectId = f.projectId();
            String now = now();
            switch (run.status()) {
                case COMPLETED -> completed(f, step, run);
                case CANCELLED -> database.writeVoid(tx -> {
                    repository.finishStep(tx, step.id(), "CANCELLED", null, now);
                    repository.update(tx, f.withStatus(FeatureStatus.CANCELLED,
                            "Execução cancelada. Alterações já feitas continuam na pasta; autorize de novo para continuar.", now));
                });
                case INTERRUPTED -> database.writeVoid(tx -> {
                    repository.finishStep(tx, step.id(), "CANCELLED", null, now);
                    repository.update(tx, f.withStatus(FeatureStatus.FAILED,
                            "Interrompida: o aplicativo foi encerrado durante a execução. Confira a pasta e autorize de novo se quiser continuar.", now));
                });
                default -> database.writeVoid(tx -> {
                    repository.finishStep(tx, step.id(), "FAILED", null, now);
                    repository.update(tx, f.withStatus(FeatureStatus.FAILED,
                            "Falhou na etapa " + step.seq() + ": " + Texts.orEmpty(run.failureMessage()) + " Nada é repetido automaticamente.", now));
                });
            }
        }
        signals.changed(projectId, "features", null);
        schedule(projectId);
    }

    private void completed(Feature f, FeatureStep step, Run run) {
        String text = finalText(run);
        Blocks.Parsed parsed = Blocks.parse(text, json);
        List<Blocks.Block> blocks = parsed.of(Blocks.HANDOFF);
        Map<String, Object> handoff;
        String warning = null;
        if (!blocks.isEmpty() && blocks.get(blocks.size() - 1).data() != null) {
            handoff = FeaturePrompts.normalize(blocks.get(blocks.size() - 1).data());
        } else {
            handoff = FeaturePrompts.fallback(parsed.text());
            warning = blocks.isEmpty() ? "O agente não deixou a passagem estruturada." : blocks.get(blocks.size() - 1).error() + ".";
        }
        if (!run.permissionDenials().isEmpty()) {
            handoff.put("permissoes_negadas", run.permissionDenials().size());
        }
        String now = now();
        database.writeVoid(tx -> repository.finishStep(tx, step.id(), "DONE", handoff, now));

        Project project = projects.get(f.projectId());
        String nextRef = warning == null ? FeaturePrompts.nextReference(handoff) : null;
        if (nextRef != null && chainLength(f) < MAX_CHAIN) {
            List<Agent> squad = members.of(project);
            Optional<Agent> next = members.resolve(nextRef, squad).filter(a -> squad.stream().anyMatch(s -> s.id().equals(a.id())));
            if (next.isPresent() && !next.get().id().equals(step.agentId())) {
                Agent previous = safeAgent(step.agentId());
                startStep(f, project, next.get().id(), "Passagem de " + (previous == null ? "outro agente" : previous.name()),
                        previous, handoff, null);
                return;
            }
        }
        StringBuilder note = new StringBuilder("Aguardando sua revisão.");
        if (warning != null) {
            note.append(' ').append(warning);
        }
        if (nextRef != null && chainLength(f) >= MAX_CHAIN) {
            note.append(" Limite de ").append(MAX_CHAIN).append(" passagens seguidas atingido.");
        }
        if (!run.permissionDenials().isEmpty()) {
            note.append(' ').append(run.permissionDenials().size()).append(run.permissionDenials().size() == 1
                    ? " permissão negada na última etapa." : " permissões negadas na última etapa.");
        }
        database.writeVoid(tx -> repository.update(tx, f.withStatus(FeatureStatus.IN_REVIEW, note.toString(), now)));
    }

    /** Etapas concluídas em sequência na revisão atual (para limitar passagens em cadeia). */
    private int chainLength(Feature f) {
        List<FeatureStep> steps = database.read(tx -> repository.steps(tx, f.id()));
        int n = 0;
        for (int i = steps.size() - 1; i >= 0; i--) {
            FeatureStep s = steps.get(i);
            if (s.revision() != f.revision() || !"DONE".equals(s.status())) {
                break;
            }
            n++;
        }
        return n;
    }

    private String finalText(Run run) {
        if (run.resultText() != null && run.resultText().contains("```squad-")) {
            return run.resultText();
        }
        List<Message> messages = sessions.messages(run.sessionId());
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (run.id().equals(m.runId()) && m.role() == Message.Role.AGENT && m.content().contains("```squad-")) {
                return m.content();
            }
        }
        return run.resultText();
    }

    /* ---------- apoio ---------- */

    private void checkRunnable(Feature f) {
        if (f.ownerAgentId() == null) {
            throw ApiException.invalid("owner_required", "Defina o agente responsável pela feature " + f.code() + " antes de autorizar.");
        }
        Agent owner = safeAgent(f.ownerAgentId());
        if (owner == null || owner.archived()) {
            throw ApiException.invalid("owner_required", "O responsável pela feature " + f.code() + " não está disponível.");
        }
        Project project = projects.get(f.projectId());
        if (project.archived()) {
            throw ApiException.invalid("archived_project", "O projeto está arquivado.");
        }
        if (!ProjectService.folderExists(project)) {
            throw ApiException.invalid("folder_required", project.folderPath() == null
                    ? "Defina a pasta do projeto antes de autorizar execuções."
                    : "A pasta do projeto não foi encontrada: " + project.folderPath() + ".");
        }
    }

    private boolean isolated(Project project) {
        if (!settings.get().isolatedFeatures() || project.folderPath() == null) {
            return false;
        }
        Path folder = Path.of(project.folderPath());
        return git.isRepository(folder) && git.hasCommits(folder);
    }

    private Path workdirOf(Feature f) {
        if (f.workdir() != null) {
            return Path.of(f.workdir());
        }
        Project project = projects.get(f.projectId());
        return project.folderPath() == null ? null : Path.of(project.folderPath());
    }

    private String waitingNote(Feature f) {
        return "Autorizada; aguardando a vez na fila.";
    }

    private void fail(Feature f, String message) {
        String now = now();
        database.writeVoid(tx -> repository.update(tx, f.withStatus(FeatureStatus.FAILED, message, now)));
        LOG.log(Level.INFO, "Feature " + f.code() + " não pôde iniciar: " + message);
    }

    private Run safeRun(String runId) {
        try {
            return runs.get(runId);
        } catch (ApiException e) {
            return null;
        }
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
