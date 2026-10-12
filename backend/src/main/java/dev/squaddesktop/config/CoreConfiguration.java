package dev.squaddesktop.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import dev.squaddesktop.agents.AgentService;
import dev.squaddesktop.claude.ClaudeEnvironment;
import dev.squaddesktop.claude.ClaudeLocator;
import dev.squaddesktop.claude.ClaudeRunExecutor;
import dev.squaddesktop.claude.ProcessLauncher;
import dev.squaddesktop.demo.DemoRunExecutor;
import dev.squaddesktop.documents.DocumentService;
import dev.squaddesktop.features.FeatureRunner;
import dev.squaddesktop.features.FeatureService;
import dev.squaddesktop.features.GitWorkspace;
import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.persistence.Migrator;
import dev.squaddesktop.planning.PlanningService;
import dev.squaddesktop.planning.ProposalService;
import dev.squaddesktop.plans.PlanService;
import dev.squaddesktop.projects.ProjectService;
import dev.squaddesktop.runs.EventBus;
import dev.squaddesktop.runs.EventStore;
import dev.squaddesktop.runs.ProjectSignals;
import dev.squaddesktop.runs.RunOrchestrator;
import dev.squaddesktop.sessions.SessionService;
import dev.squaddesktop.settings.SettingsService;
import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.squads.SquadMembers;
import dev.squaddesktop.squads.SquadService;
import dev.squaddesktop.system.SampleData;
import dev.squaddesktop.system.WorkspaceTransfer;
import dev.squaddesktop.workspace.UiStateStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * Monta o núcleo. As classes de regra não dependem do Spring: são criadas aqui, o que mantém os testes
 * de regra rápidos e sem contexto de aplicação.
 */
@Configuration(proxyBeanMethods = false)
public class CoreConfiguration {

    private static final Logger LOG = Logger.getLogger(CoreConfiguration.class.getName());

    @Bean
    SquadSettings squadSettings(Environment env) {
        return SquadSettings.from(env);
    }

    @Bean
    SquadPaths squadPaths(SquadSettings settings) {
        SquadPaths paths = SquadPaths.resolve(settings.dataDir());
        paths.create();
        return paths;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JsonCodec jsonCodec(JsonMapper mapper) {
        return new JacksonJsonCodec(mapper);
    }

    /** Abre o banco e aplica as migrações; antes de migrar um banco existente, guarda uma cópia em backups/. */
    @Bean(destroyMethod = "close")
    Database database(SquadPaths paths, Clock clock) {
        boolean existed = java.nio.file.Files.exists(paths.database());
        Database database = new Database(paths.database());
        Migrator migrator = new Migrator(database, clock);
        int current = migrator.currentVersion();
        if (existed && current > 0 && current < migrator.latestVersion()) {
            java.nio.file.Path copy = paths.backups().resolve("antes-da-migracao-v" + current + "-" + clock.millis() + ".db");
            database.snapshotTo(copy);
            LOG.info("Cópia do banco antes da migração: " + copy);
        }
        List<Migrator.Applied> applied = migrator.migrate();
        if (!applied.isEmpty()) {
            LOG.info("Migrações aplicadas: " + applied);
        }
        return database;
    }

    @Bean
    SettingsService settingsService(Database database, Clock clock) {
        return new SettingsService(database, clock);
    }

    @Bean
    UiStateStore uiStateStore(Database database, JsonCodec json, Clock clock) {
        return new UiStateStore(database, json, clock);
    }

    @Bean
    DocumentService documentService(Database database, Clock clock) {
        return new DocumentService(database, clock);
    }

    @Bean
    ProjectService projectService(Database database, Clock clock, DocumentService documents, SquadPaths paths) {
        return new ProjectService(database, clock, documents, paths.dataDir());
    }

    @Bean
    AgentService agentService(Database database, JsonCodec json, Clock clock) {
        return new AgentService(database, json, clock);
    }

    @Bean
    SquadService squadService(Database database, AgentService agents, Clock clock) {
        return new SquadService(database, agents, clock);
    }

    @Bean
    SessionService sessionService(Database database, Clock clock) {
        return new SessionService(database, clock);
    }

    @Bean
    ClaudeEnvironment claudeEnvironment(SettingsService settings, JsonCodec json, Clock clock) {
        return new ClaudeEnvironment(new ClaudeLocator(), settings, json, clock);
    }

    @Bean
    EventStore eventStore(Database database) {
        return new EventStore(database);
    }

    @Bean
    EventBus eventBus() {
        return new EventBus();
    }

    @Bean(destroyMethod = "close")
    RunOrchestrator runOrchestrator(Database database, Clock clock, JsonCodec json, SettingsService settings,
            ProjectService projects, AgentService agents, SessionService sessions, DocumentService documents,
            ClaudeEnvironment claude, EventStore store, EventBus bus, SquadPaths paths, SquadSettings squad) {
        return new RunOrchestrator(database, clock, json, settings, projects, agents, sessions, documents,
                new ClaudeRunExecutor(claude, json, ProcessLauncher.SYSTEM, paths.temp()),
                new DemoRunExecutor(Duration.ofMillis(squad.demoStepMillis())), store, bus, paths.scratch());
    }

    @Bean
    SampleData sampleData(Database database, AgentService agents, SquadService squads, ProjectService projects,
            DocumentService documents, SessionService sessions) {
        return new SampleData(database, agents, squads, projects, documents, sessions);
    }

    @Bean
    ProjectSignals projectSignals(EventBus bus, Clock clock, JsonCodec json) {
        return new ProjectSignals(bus, clock, json);
    }

    @Bean
    SquadMembers squadMembers(SquadService squads, AgentService agents) {
        return new SquadMembers(squads, agents);
    }

    @Bean
    GitWorkspace gitWorkspace() {
        return new GitWorkspace();
    }

    @Bean
    FeatureService featureService(Database database, Clock clock, JsonCodec json, AgentService agents, ProjectSignals signals) {
        return new FeatureService(database, clock, json, agents, signals);
    }

    @Bean
    PlanService planService(Database database, Clock clock, JsonCodec json, ProjectService projects, SquadMembers members,
            FeatureService features, ProjectSignals signals) {
        return new PlanService(database, clock, json, projects, members, features, signals);
    }

    @Bean
    ProposalService proposalService(Database database, Clock clock, DocumentService documents, ProjectSignals signals) {
        return new ProposalService(database, clock, documents, signals);
    }

    @Bean
    FeatureRunner featureRunner(Database database, Clock clock, JsonCodec json, FeatureService features, ProjectService projects,
            AgentService agents, SquadMembers members, SessionService sessions, RunOrchestrator runs, SettingsService settings,
            GitWorkspace git, ProjectSignals signals, SquadPaths paths) {
        return new FeatureRunner(database, clock, json, features, projects, agents, members, sessions, runs, settings, git, signals,
                paths.dataDir().resolve("worktrees"));
    }

    @Bean
    PlanningService planningService(Database database, Clock clock, JsonCodec json, ProjectService projects, AgentService agents,
            SquadMembers members, SessionService sessions, RunOrchestrator runs, SettingsService settings, ProposalService proposals,
            PlanService plans, ProjectSignals signals) {
        return new PlanningService(database, clock, json, projects, agents, members, sessions, runs, settings, proposals, plans, signals);
    }

    @Bean
    WorkspaceTransfer workspaceTransfer(Database database, Clock clock, SquadPaths paths, SquadSettings squad, RunOrchestrator runs) {
        return new WorkspaceTransfer(database, clock, paths.backups(), squad.appVersion(), runs);
    }
}
