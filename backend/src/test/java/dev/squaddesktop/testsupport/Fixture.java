package dev.squaddesktop.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import dev.squaddesktop.agents.Agent;
import dev.squaddesktop.agents.AgentService;
import dev.squaddesktop.agents.AgentTemplates;
import dev.squaddesktop.demo.DemoRunExecutor;
import dev.squaddesktop.documents.DocumentService;
import dev.squaddesktop.features.FeatureRunner;
import dev.squaddesktop.features.FeatureService;
import dev.squaddesktop.features.GitWorkspace;
import dev.squaddesktop.planning.PlanningService;
import dev.squaddesktop.planning.ProposalService;
import dev.squaddesktop.plans.PlanService;
import dev.squaddesktop.runs.ProjectSignals;
import dev.squaddesktop.squads.Squad;
import dev.squaddesktop.squads.SquadDraft;
import dev.squaddesktop.squads.SquadMembers;
import java.util.function.BooleanSupplier;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.projects.ProjectDraft;
import dev.squaddesktop.projects.ProjectService;
import dev.squaddesktop.runs.EventBus;
import dev.squaddesktop.runs.EventStore;
import dev.squaddesktop.runs.RunExecutor;
import dev.squaddesktop.runs.RunOrchestrator;
import dev.squaddesktop.sessions.Session;
import dev.squaddesktop.sessions.SessionService;
import dev.squaddesktop.settings.AppSettings;
import dev.squaddesktop.settings.SettingsService;
import dev.squaddesktop.squads.SquadService;

/** Núcleo completo sobre um banco temporário, para testes de integração sem Spring. */
public final class Fixture implements AutoCloseable {

    public final Path dir;
    public final Database database;
    public final Clock clock = Clock.systemUTC();
    public final SettingsService settings;
    public final DocumentService documents;
    public final ProjectService projects;
    public final AgentService agents;
    public final SquadService squads;
    public final SessionService sessions;
    public final EventStore events;
    public final EventBus bus = new EventBus();
    public final RunOrchestrator runs;
    public final ProjectSignals signals;
    public final SquadMembers members;
    public final GitWorkspace git = new GitWorkspace();
    public final FeatureService features;
    public final PlanService plans;
    public final ProposalService proposals;
    public final FeatureRunner featureRunner;
    public final PlanningService planning;

    public Fixture(Path dir, RunExecutor realExecutor) {
        this.dir = dir;
        this.database = TestDb.create(dir);
        this.settings = new SettingsService(database, clock);
        this.documents = new DocumentService(database, clock);
        this.projects = new ProjectService(database, clock, documents, dir.resolve("dados"));
        this.agents = new AgentService(database, TestJson.CODEC, clock);
        this.squads = new SquadService(database, agents, clock);
        this.sessions = new SessionService(database, clock);
        this.events = new EventStore(database);
        this.runs = new RunOrchestrator(database, clock, TestJson.CODEC, settings, projects, agents, sessions, documents,
                realExecutor, new DemoRunExecutor(Duration.ofMillis(1)), events, bus, dir.resolve("rascunhos"));
        this.signals = new ProjectSignals(bus, clock, TestJson.CODEC);
        this.members = new SquadMembers(squads, agents);
        this.features = new FeatureService(database, clock, TestJson.CODEC, agents, signals);
        this.plans = new PlanService(database, clock, TestJson.CODEC, projects, members, features, signals);
        this.proposals = new ProposalService(database, clock, documents, signals);
        this.featureRunner = new FeatureRunner(database, clock, TestJson.CODEC, features, projects, agents, members, sessions, runs,
                settings, git, signals, dir.resolve("worktrees"));
        this.planning = new PlanningService(database, clock, TestJson.CODEC, projects, agents, members, sessions, runs, settings,
                proposals, plans, signals);
        planning.reconcile();
        featureRunner.reconcile();
    }

    /** Squad completa a partir dos modelos: comandante, requisitos, arquitetura, operadores Java e QA. */
    public Squad squad() {
        Agent commander = agents.createFromTemplate("commander");
        Agent requirements = agents.createFromTemplate("requirements");
        Agent architecture = agents.createFromTemplate("architecture");
        Agent java = agents.createFromTemplate("operator-java");
        Agent qa = agents.createFromTemplate("operator-qa");
        return squads.create(new SquadDraft("Squad de teste", null, commander.id(), requirements.id(), architecture.id(),
                List.of(java.id(), qa.id())));
    }

    /** Projeto com pasta e squad. */
    public Project projectWithSquad(String name) throws Exception {
        Squad squad = squad();
        Path folder = Files.createDirectories(dir.resolve("pastas").resolve(name));
        return projects.create(new ProjectDraft(name, null, folder.toString(), null, squad.id()));
    }

    /** Espera a condição ficar verdadeira (até 10 s). */
    public static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Tempo esgotado esperando: " + what);
    }

    public void isolatedFeatures(boolean on) {
        AppSettings s = settings.get();
        settings.update(new AppSettings(s.claudePath(), s.defaultModel(), s.maxConcurrentRuns(), s.runTimeoutMinutes(), s.maxBudgetUsd(), s.demoMode(), on));
    }

    public Project project(String name) throws Exception {
        Path folder = Files.createDirectories(dir.resolve("pastas").resolve(name));
        return projects.create(new ProjectDraft(name, null, folder.toString(), null, null));
    }

    public Agent operator() {
        return agents.create(AgentTemplates.find("operator-java").orElseThrow().draft());
    }

    public Session session(Project project, Agent agent) {
        return sessions.create(project.id(), agent.id(), null);
    }

    public void demoMode(boolean on) {
        AppSettings s = settings.get();
        settings.update(new AppSettings(s.claudePath(), s.defaultModel(), s.maxConcurrentRuns(), s.runTimeoutMinutes(), s.maxBudgetUsd(), on, s.isolatedFeatures()));
    }

    public void maxConcurrent(int n) {
        AppSettings s = settings.get();
        settings.update(new AppSettings(s.claudePath(), s.defaultModel(), n, s.runTimeoutMinutes(), s.maxBudgetUsd(), s.demoMode(), s.isolatedFeatures()));
    }

    public List<String> eventTypes(String runId) {
        return events.byRun(runId).stream().map(e -> e.type()).toList();
    }

    @Override
    public void close() {
        runs.close();
        database.close();
    }
}
