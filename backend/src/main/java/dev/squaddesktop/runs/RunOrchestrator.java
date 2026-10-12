package dev.squaddesktop.runs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.squaddesktop.agents.Agent;
import dev.squaddesktop.agents.AgentService;
import dev.squaddesktop.agents.InstructionComposer;
import dev.squaddesktop.documents.DocumentService;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.projects.ProjectService;
import dev.squaddesktop.sessions.Message;
import dev.squaddesktop.sessions.Session;
import dev.squaddesktop.sessions.SessionService;
import dev.squaddesktop.settings.AppSettings;
import dev.squaddesktop.settings.SettingsService;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Ids;
import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.shared.Texts;

/**
 * Fila e ciclo de vida das execuções.
 *
 * <p>Regras de agendamento: no máximo {@code maxConcurrentRuns} execuções ao mesmo tempo; uma execução por
 * sessão; e só uma execução que escreve ({@link RunAccess#WRITE}) por pasta de projeto. Pausar a fila
 * impede que novas execuções comecem; cancelar encerra os processos de uma execução específica.
 *
 * <p>Uma falha nunca é repetida automaticamente: execuções com efeito em arquivos só voltam a rodar por
 * pedido explícito. Execuções que estavam em andamento quando o aplicativo caiu são marcadas como
 * interrompidas na inicialização seguinte, sem reinício silencioso.
 */
public class RunOrchestrator implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RunOrchestrator.class.getName());

    /** Ferramentas de leitura que conversas e planejamento podem usar. */
    static final Set<String> READ_ONLY_TOOLS = Set.of("Read", "Glob", "Grep", "WebSearch", "WebFetch");
    static final List<String> DEFAULT_READ_ONLY_TOOLS = List.of("Read", "Glob", "Grep");

    public record Submission(Message message, Run run) {
    }

    public record QueueState(boolean paused, int queued, int running, int maxConcurrent) {
    }

    /** Avisado quando uma execução chega a um estado final (concluída, falhou, cancelada, interrompida). */
    @FunctionalInterface
    public interface RunListener {
        void finished(Run run);
    }

    private final List<RunListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final Database database;
    private final Clock clock;
    private final JsonCodec json;
    private final SettingsService settings;
    private final ProjectService projects;
    private final AgentService agents;
    private final SessionService sessions;
    private final DocumentService documents;
    private final RunExecutor realExecutor;
    private final RunExecutor demoExecutor;
    private final EventStore store;
    private final EventBus bus;
    private final Path scratchRoot;
    private final RunRepository repository;
    private final Object schedulingLock = new Object();
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker;
    private volatile boolean paused;
    private volatile boolean closing;

    public RunOrchestrator(Database database, Clock clock, JsonCodec json, SettingsService settings, ProjectService projects,
            AgentService agents, SessionService sessions, DocumentService documents, RunExecutor realExecutor,
            RunExecutor demoExecutor, EventStore store, EventBus bus, Path scratchRoot) {
        this.database = database;
        this.clock = clock;
        this.json = json;
        this.settings = settings;
        this.projects = projects;
        this.agents = agents;
        this.sessions = sessions;
        this.documents = documents;
        this.realExecutor = realExecutor;
        this.demoExecutor = demoExecutor;
        this.store = store;
        this.bus = bus;
        this.scratchRoot = scratchRoot;
        this.repository = new RunRepository(json);
        this.ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("run-delta-flush").factory());
        this.ticker.scheduleAtFixedRate(this::flushStaleDeltas, 80, 80, TimeUnit.MILLISECONDS);
        projects.setActiveWorkProbe(projectId -> database.read(tx -> repository.projectBusy(tx, projectId)));
        sessions.setActiveWorkProbe(sessionId -> database.read(tx -> repository.sessionBusy(tx, sessionId)));
        settings.onChange(changed -> dispatch());
    }

    public void addListener(RunListener listener) {
        listeners.add(listener);
    }

    private void notifyFinished(String runId) {
        if (listeners.isEmpty()) {
            return;
        }
        Run run;
        try {
            run = get(runId);
        } catch (RuntimeException e) {
            return;
        }
        for (RunListener listener : listeners) {
            try {
                listener.finished(run);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Falha ao processar o fim da execução " + runId, e);
            }
        }
    }

    /* ---------- comandos ---------- */

    /** Registra a mensagem da pessoa e enfileira a execução correspondente. */
    public Submission submit(String sessionId, String content, RunKind kind) {
        return submit(sessionId, content, kind, null);
    }

    /**
     * Variante usada pela execução de features e pela sala de planejamento.
     *
     * @param cwdOverride pasta de trabalho diferente da do projeto (por exemplo, o worktree isolado da feature)
     */
    public Submission submit(String sessionId, String content, RunKind kind, Path cwdOverride) {
        String text = Texts.requireText(content, "mensagem", SessionService.MAX_MESSAGE_CHARS);
        RunKind effectiveKind = kind == null ? RunKind.CHAT : kind;
        if (closing) {
            throw ApiException.unavailable("closing", "O aplicativo está sendo encerrado.");
        }
        AppSettings prefs = settings.get();
        Session session = sessions.get(sessionId);
        if (session.archivedAt() != null) {
            throw ApiException.invalid("archived_session", "Esta sessão está arquivada.");
        }
        Project project = projects.get(session.projectId());
        if (project.archived()) {
            throw ApiException.invalid("archived_project", "O projeto está arquivado. Desarquive-o para conversar com os agentes.");
        }
        Agent agent = agents.get(session.agentId());
        if (agent.archived()) {
            throw ApiException.invalid("archived_agent", "O agente desta sessão foi arquivado.");
        }
        boolean folderOk = cwdOverride != null ? Files.isDirectory(cwdOverride) : ProjectService.folderExists(project);
        if (effectiveKind == RunKind.EXECUTION && cwdOverride != null && !folderOk) {
            throw ApiException.invalid("folder_required", "A pasta de trabalho da feature não foi encontrada: " + cwdOverride + ".");
        }
        if (effectiveKind == RunKind.EXECUTION && !folderOk) {
            throw ApiException.invalid("folder_required", project.folderPath() == null
                    ? "Para executar, defina a pasta do projeto."
                    : "A pasta do projeto não foi encontrada: " + project.folderPath() + ".");
        }
        String cwd = cwdOverride != null && folderOk ? cwdOverride.toString()
                : folderOk ? project.folderPath() : scratchRoot.resolve(project.id()).toString();
        String now = now();
        Run run = new Run(Ids.newId(), sessionId, project.id(), agent.id(), effectiveKind, effectiveKind.access(), RunStatus.QUEUED,
                prefs.demoMode(), text, cwd, List.of(), null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, List.of(), now, null, null);
        Message message = database.write(tx -> {
            if (repository.sessionBusy(tx, sessionId)) {
                throw ApiException.conflict("session_busy",
                        "Esta sessão já tem uma execução em andamento. Aguarde o fim ou cancele-a antes de enviar outra mensagem.");
            }
            repository.insert(tx, run);
            return sessions.appendMessage(tx, sessionId, run.id(), Message.Role.USER, text, now);
        });
        publish(run, new AgentEvent.Queued(effectiveKind.name(), run.access().name(), run.demo()));
        dispatch();
        return new Submission(message, get(run.id()));
    }

    /** Cancela uma execução: tira da fila ou encerra o processo e tudo o que ele iniciou. Idempotente. */
    public Run cancel(String runId) {
        Run run = get(runId);
        if (run.status().terminal()) {
            return run;
        }
        synchronized (schedulingLock) {
            ActiveRun current = active.get(runId);
            if (current == null) {
                boolean removed = database.write(tx -> {
                    boolean changed = repository.transition(tx, runId, RunStatus.QUEUED, RunStatus.CANCELLED);
                    if (changed) {
                        repository.finish(tx, runId, RunStatus.CANCELLED,
                                new RunOutcome(null, false, null, null, "Cancelada antes de começar.", List.of()), null, List.of(), now());
                        sessions.appendMessage(tx, run.sessionId(), runId, Message.Role.SYSTEM, "Execução cancelada antes de começar.", now());
                    }
                    return changed;
                });
                if (removed) {
                    publish(run, new AgentEvent.Finished(RunStatus.CANCELLED.name(), null, null, null, "Cancelada antes de começar.", 0));
                    notifyFinished(runId);
                }
                return get(runId);
            }
            database.writeVoid(tx -> {
                if (!repository.transition(tx, runId, RunStatus.RUNNING, RunStatus.CANCELLING)) {
                    repository.transition(tx, runId, RunStatus.STARTING, RunStatus.CANCELLING);
                }
            });
            current.recorder.accept(new AgentEvent.Notice("info", "cancelling", "Cancelando: encerrando o processo do agente."));
            current.control.cancel();
        }
        return get(runId);
    }

    public void pauseQueue() {
        paused = true;
    }

    public void resumeQueue() {
        paused = false;
        dispatch();
    }

    /* ---------- consultas ---------- */

    public Run get(String runId) {
        return database.read(tx -> repository.find(tx, runId)).orElseThrow(() -> ApiException.notFound("Execução", runId));
    }

    public List<Run> bySession(String sessionId, int limit) {
        return database.read(tx -> repository.bySession(tx, sessionId, Math.max(1, Math.min(limit, 500))));
    }

    public List<Run> unfinished() {
        return database.read(repository::unfinished);
    }

    public QueueState queueState() {
        int queued = database.read(repository::queued).size();
        return new QueueState(paused, queued, active.size(), settings.get().maxConcurrentRuns());
    }

    public boolean hasActiveWork() {
        return !active.isEmpty() || !database.read(repository::unfinished).isEmpty();
    }

    /* ---------- inicialização e encerramento ---------- */

    /**
     * Marca como interrompidas as execuções que ficaram sem desfecho (o aplicativo caiu ou foi fechado).
     * Nenhuma é reiniciada: a pessoa decide se envia de novo.
     *
     * @return quantas execuções foram marcadas
     */
    public int recoverInterrupted() {
        List<Run> stale = database.read(repository::unfinished);
        for (Run run : stale) {
            String message = run.status() == RunStatus.QUEUED
                    ? "Execução interrompida: o aplicativo foi encerrado antes de ela começar. Nada foi retomado automaticamente."
                    : "Execução interrompida: o aplicativo foi encerrado durante a execução. Nada foi retomado automaticamente; confira a pasta do projeto antes de pedir de novo.";
            database.writeVoid(tx -> {
                tx.update("UPDATE run SET status = 'INTERRUPTED' WHERE id = ?", run.id());
                repository.finish(tx, run.id(), RunStatus.INTERRUPTED,
                        new RunOutcome(null, false, null, FailureCodes.APP_CLOSED, message, run.command()), null, run.permissionDenials(), now());
                repository.deleteTextDeltas(tx, run.id());
                sessions.appendMessage(tx, run.sessionId(), run.id(), Message.Role.SYSTEM, message, now());
            });
            publish(run, new AgentEvent.Finished(RunStatus.INTERRUPTED.name(), null, null, FailureCodes.APP_CLOSED, message,
                    run.permissionDenials().size()));
            notifyFinished(run.id());
        }
        return stale.size();
    }

    /**
     * Encerra as execuções em andamento (com seus processos) e para de iniciar novas.
     * As execuções ficam como interrompidas; as que estavam na fila são marcadas na próxima inicialização.
     */
    public void shutdown(Duration grace) {
        closing = true;
        List<ActiveRun> running = new ArrayList<>(active.values());
        running.forEach(run -> run.control.cancel());
        long deadline = System.nanoTime() + grace.toNanos();
        for (ActiveRun run : running) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    run.thread.join(Duration.ofNanos(remaining));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Override
    public void close() {
        shutdown(Duration.ofSeconds(8));
        ticker.shutdownNow();
    }

    /* ---------- agendamento ---------- */

    void dispatch() {
        if (paused || closing) {
            return;
        }
        synchronized (schedulingLock) {
            int limit = settings.get().maxConcurrentRuns();
            for (Run run : database.read(repository::queued)) {
                if (active.size() >= limit) {
                    break;
                }
                boolean sameSession = active.values().stream().anyMatch(a -> a.run.sessionId().equals(run.sessionId()));
                boolean folderTaken = run.access() == RunAccess.WRITE && active.values().stream()
                        .anyMatch(a -> a.run.access() == RunAccess.WRITE && samePath(a.run.cwd(), run.cwd()));
                if (sameSession || folderTaken) {
                    continue;
                }
                if (database.write(tx -> repository.transition(tx, run.id(), RunStatus.QUEUED, RunStatus.STARTING))) {
                    start(run);
                }
            }
        }
    }

    private void start(Run run) {
        ActiveRun handle = new ActiveRun(run, new RunControl(), new Recorder(run));
        active.put(run.id(), handle);
        handle.thread = Thread.ofVirtual().name("run-" + run.id()).unstarted(() -> execute(handle));
        handle.thread.start();
    }

    private void execute(ActiveRun handle) {
        Run run = handle.run;
        RunOutcome outcome;
        try {
            RunSpec spec = buildSpec(run);
            Files.createDirectories(spec.cwd());
            RunExecutor executor = run.demo() ? demoExecutor : realExecutor;
            outcome = executor.execute(spec, handle.recorder::accept, handle.control);
        } catch (ApiException e) {
            outcome = RunOutcome.failure(null, e.code(), e.getMessage(), List.of());
        } catch (Exception | Error e) {
            LOG.log(Level.SEVERE, "Falha inesperada na execução " + run.id(), e);
            outcome = RunOutcome.failure(null, FailureCodes.INTERNAL, "Erro interno ao executar: " + e.getMessage(), List.of());
        }
        try {
            finish(handle, outcome);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Falha ao registrar o fim da execução " + run.id(), e);
        } finally {
            active.remove(run.id());
            if (!closing) {
                notifyFinished(run.id());
            }
            dispatchQuietly();
        }
    }

    /** Agenda a próxima execução depois de uma terminar; durante o encerramento o banco pode já estar fechado. */
    private void dispatchQuietly() {
        try {
            dispatch();
        } catch (RuntimeException e) {
            if (!closing) {
                LOG.log(Level.WARNING, "Falha ao agendar a próxima execução", e);
            }
        }
    }

    RunSpec buildSpec(Run run) {
        AppSettings prefs = settings.get();
        Agent agent = agents.get(run.agentId());
        Project project = projects.get(run.projectId());
        Session session = sessions.get(run.sessionId());
        boolean conversation = run.kind() != RunKind.EXECUTION;
        String systemPrompt = InstructionComposer.compose(agent, project, documents.list(project.id()));
        String prompt = InstructionComposer.turnPrompt(
                conversation ? InstructionComposer.Mode.CONVERSATION : InstructionComposer.Mode.EXECUTION, run.prompt());

        String permissionMode;
        List<String> tools;
        List<String> allowed;
        if (conversation) {
            permissionMode = "plan".equals(agent.permissionMode()) ? "plan" : "dontAsk";
            tools = agent.tools() == null ? DEFAULT_READ_ONLY_TOOLS
                    : agent.tools().stream().filter(READ_ONLY_TOOLS::contains).toList();
            allowed = List.of();
        } else {
            permissionMode = agent.permissionMode() == null ? "acceptEdits" : agent.permissionMode();
            tools = agent.tools();
            allowed = agent.allowedTools();
        }
        String resume = run.demo() ? null : session.claudeSessionId();
        return new RunSpec(run.id(), run.kind(), Path.of(run.cwd()), prompt, systemPrompt,
                agent.model() != null ? agent.model() : prefs.defaultModel(), agent.effort(), permissionMode, tools, allowed,
                agent.disallowedTools(), resume, resume == null ? UUID.randomUUID().toString() : null, prefs.maxBudgetUsd(),
                Duration.ofMinutes(prefs.runTimeoutMinutes()), agent.name());
    }

    private void finish(ActiveRun handle, RunOutcome outcome) {
        Run run = handle.run;
        handle.recorder.flushDelta();
        RunStatus status;
        RunOutcome effective = outcome;
        if (handle.control.cancelled()) {
            status = closing ? RunStatus.INTERRUPTED : RunStatus.CANCELLED;
            effective = new RunOutcome(outcome.exitCode(), false, null, closing ? FailureCodes.APP_CLOSED : null,
                    closing ? "Execução interrompida porque o aplicativo foi encerrado." : "Execução cancelada por você.", outcome.command());
        } else if (outcome.timedOut()) {
            status = RunStatus.TIMED_OUT;
        } else if (outcome.failureCode() != null) {
            status = RunStatus.FAILED;
        } else {
            status = RunStatus.COMPLETED;
        }
        List<Map<String, Object>> denials = handle.recorder.denials();
        RunOutcome toStore = effective;
        RunStatus finalStatus = status;
        String now = now();
        database.writeVoid(tx -> {
            tx.update("UPDATE run SET status = ? WHERE id = ?", finalStatus, run.id());
            repository.finish(tx, run.id(), finalStatus, toStore, handle.recorder.usage(), denials, now);
            repository.deleteTextDeltas(tx, run.id());
            String note = systemNote(finalStatus, toStore, denials.size());
            if (note != null) {
                sessions.appendMessage(tx, run.sessionId(), run.id(), Message.Role.SYSTEM, note, now);
            }
        });
        handle.recorder.accept(new AgentEvent.Finished(finalStatus.name(), toStore.resultText(), toStore.exitCode(),
                toStore.failureCode(), toStore.failureMessage(), denials.size()));
    }

    private static String systemNote(RunStatus status, RunOutcome outcome, int denials) {
        return switch (status) {
            case COMPLETED -> denials == 0 ? null
                    : "Concluída com " + denials + (denials == 1 ? " permissão negada" : " permissões negadas")
                            + ". Veja a aba Atividade para liberar a ferramenta e pedir de novo.";
            case CANCELLED -> "Execução cancelada.";
            case TIMED_OUT -> outcome.failureMessage();
            case INTERRUPTED -> outcome.failureMessage();
            case FAILED -> "Falha na execução: " + Texts.orEmpty(outcome.failureMessage());
            default -> null;
        };
    }

    private void flushStaleDeltas() {
        for (ActiveRun run : active.values()) {
            try {
                run.recorder.flushIfStale();
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Falha ao gravar texto parcial", e);
            }
        }
    }

    private StoredEvent publish(Run run, AgentEvent event) {
        String payload = json.write(event.data());
        StoredEvent stored = event instanceof AgentEvent.Thinking
                ? new StoredEvent(0, run.id(), run.sessionId(), event.type(), payload, now())
                : store.append(run.id(), run.sessionId(), event.type(), payload, now());
        bus.publish(stored);
        return stored;
    }

    private static boolean samePath(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return a.equalsIgnoreCase(b);
        }
    }

    private String now() {
        return clock.instant().toString();
    }

    /* ---------- estado de uma execução em andamento ---------- */

    private static final class ActiveRun {
        final Run run;
        final RunControl control;
        final Recorder recorder;
        volatile Thread thread;

        ActiveRun(Run run, RunControl control, Recorder recorder) {
            this.run = run;
            this.control = control;
            this.recorder = recorder;
        }
    }

    /**
     * Recebe os eventos do executor, aplica os efeitos no banco (sessão do Claude, mensagens da conversa)
     * e os grava e transmite. Junta os trechos de texto em lotes curtos para não gravar um registro por palavra.
     */
    private final class Recorder {
        private final Run run;
        private final StringBuilder pendingText = new StringBuilder();
        private final List<Map<String, Object>> denials = new ArrayList<>();
        private long lastFlush = System.nanoTime();
        private AgentEvent.Usage usage;

        Recorder(Run run) {
            this.run = run;
        }

        synchronized void accept(AgentEvent event) {
            if (event instanceof AgentEvent.TextDelta delta) {
                pendingText.append(delta.text());
                if (pendingText.length() >= 600 || System.nanoTime() - lastFlush > 80_000_000L) {
                    flushDelta();
                }
                return;
            }
            flushDelta();
            AgentEvent toPublish = event;
            switch (event) {
                case AgentEvent.Started started -> database.writeVoid(tx -> {
                    repository.markRunning(tx, run.id(), started.claudeSessionId(), started.model(), started.cliVersion(), now());
                    if (!run.demo() && started.claudeSessionId() != null) {
                        sessions.rememberClaudeSession(tx, run.sessionId(), started.claudeSessionId());
                    }
                });
                case AgentEvent.Message message when message.parentToolUseId() == null -> {
                    Message stored = database.write(tx -> sessions.appendMessage(tx, run.sessionId(), run.id(),
                            Message.Role.AGENT, message.text(), now()));
                    toPublish = message.withMessageId(stored.id());
                }
                case AgentEvent.PermissionDenied denied -> {
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("toolName", denied.toolName());
                    if (denied.toolUseId() != null) {
                        record.put("toolUseId", denied.toolUseId());
                    }
                    record.put("input", denied.input());
                    if (denied.suggestedRule() != null) {
                        record.put("suggestedRule", denied.suggestedRule());
                    }
                    denials.add(record);
                }
                case AgentEvent.Usage reported -> usage = reported;
                default -> {
                    // demais eventos só são gravados e transmitidos
                }
            }
            publish(run, toPublish);
        }

        synchronized void flushIfStale() {
            if (!pendingText.isEmpty() && System.nanoTime() - lastFlush > 80_000_000L) {
                flushDelta();
            }
        }

        synchronized void flushDelta() {
            lastFlush = System.nanoTime();
            if (pendingText.isEmpty()) {
                return;
            }
            String text = pendingText.toString();
            pendingText.setLength(0);
            publish(run, new AgentEvent.TextDelta(text));
        }

        synchronized List<Map<String, Object>> denials() {
            return List.copyOf(denials);
        }

        synchronized AgentEvent.Usage usage() {
            return usage;
        }
    }
}
