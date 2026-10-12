package dev.squaddesktop.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.squaddesktop.agents.AgentService;
import dev.squaddesktop.claude.ClaudeEnvironment;
import dev.squaddesktop.claude.ClaudeStatus;
import dev.squaddesktop.claude.ConnectionTest;
import dev.squaddesktop.config.SquadPaths;
import dev.squaddesktop.config.SquadSettings;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.projects.ProjectService;
import dev.squaddesktop.runs.RunOrchestrator;
import dev.squaddesktop.settings.AppSettings;
import dev.squaddesktop.settings.SettingsService;
import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.system.AppLifecycle;
import dev.squaddesktop.system.SampleData;
import dev.squaddesktop.workspace.UiStateStore;

@RestController
@RequestMapping("/api")
public class SystemController {

    public record Health(String status, String version) {
    }

    /** Resumo para a tela inicial e para o diagnóstico. */
    public record SystemInfo(String version, String dataDir, String database, String platform, String javaVersion,
            AppSettings settings, ClaudeStatus claude, long projects, long agents, boolean firstRun) {
    }

    public record ActiveWork(boolean active, int runs, RunOrchestrator.QueueState queue) {
    }

    private final SquadSettings squad;
    private final SquadPaths paths;
    private final SettingsService settings;
    private final ClaudeEnvironment claude;
    private final ProjectService projects;
    private final AgentService agents;
    private final RunOrchestrator runs;
    private final UiStateStore uiState;
    private final SampleData sampleData;
    private final AppLifecycle lifecycle;
    private final JsonCodec json;

    public SystemController(SquadSettings squad, SquadPaths paths, SettingsService settings, ClaudeEnvironment claude,
            ProjectService projects, AgentService agents, RunOrchestrator runs, UiStateStore uiState, SampleData sampleData,
            AppLifecycle lifecycle, JsonCodec json) {
        this.squad = squad;
        this.paths = paths;
        this.settings = settings;
        this.claude = claude;
        this.projects = projects;
        this.agents = agents;
        this.runs = runs;
        this.uiState = uiState;
        this.sampleData = sampleData;
        this.lifecycle = lifecycle;
        this.json = json;
    }

    @GetMapping("/health")
    public Health health() {
        return new Health("UP", squad.appVersion());
    }

    @GetMapping("/system/info")
    public SystemInfo info() {
        long projectCount = projects.count();
        long agentCount = agents.count();
        return new SystemInfo(squad.appVersion(), paths.dataDir().toString(), paths.database().toString(),
                System.getProperty("os.name") + " " + System.getProperty("os.arch"), Runtime.version().toString(),
                settings.get(), claude.statusNow(), projectCount, agentCount, projectCount == 0 && agentCount == 0);
    }

    /** Situação atual sem bloquear; com {@code wait=true}, espera a detecção terminar. */
    @GetMapping("/system/claude")
    public ClaudeStatus claude(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean wait) {
        return wait ? claude.status() : claude.statusNow();
    }

    @PostMapping("/system/claude/refresh")
    public ClaudeStatus refreshClaude() {
        return claude.refresh();
    }

    /** Faz uma chamada real e mínima ao Claude Code. Só roda quando a pessoa pede. */
    @PostMapping("/system/claude/test")
    public ConnectionTest.Result testClaude() {
        return ConnectionTest.run(claude, json, paths.scratch().resolve("teste-de-conexao"));
    }

    @GetMapping("/settings")
    public AppSettings getSettings() {
        return settings.get();
    }

    @PutMapping("/settings")
    public AppSettings updateSettings(@RequestBody SettingsRequest request) {
        AppSettings current = settings.get();
        return settings.update(new AppSettings(request.claudePath(), request.defaultModel(),
                request.maxConcurrentRuns() == null ? current.maxConcurrentRuns() : request.maxConcurrentRuns(),
                request.runTimeoutMinutes() == null ? current.runTimeoutMinutes() : request.runTimeoutMinutes(), request.maxBudgetUsd(),
                request.demoMode() == null ? current.demoMode() : request.demoMode(),
                request.isolatedFeatures() == null ? current.isolatedFeatures() : request.isolatedFeatures()));
    }

    /** Preferências enviadas pela interface. Campos booleanos e numéricos ausentes mantêm o valor atual. */
    public record SettingsRequest(String claudePath, String defaultModel, Integer maxConcurrentRuns, Integer runTimeoutMinutes,
            Double maxBudgetUsd, Boolean demoMode, Boolean isolatedFeatures) {
    }

    @GetMapping("/ui-state")
    public Map<String, String> uiState() {
        return uiState.all();
    }

    @GetMapping(value = "/ui-state/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> uiState(@PathVariable String key) {
        String value = uiState.get(key);
        return value == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(value);
    }

    @PutMapping(value = "/ui-state/{key}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveUiState(@PathVariable String key, @RequestBody String value) {
        uiState.put(key, value);
    }

    @GetMapping("/system/active-work")
    public ActiveWork activeWork() {
        int unfinished = runs.unfinished().size();
        return new ActiveWork(unfinished > 0, unfinished, runs.queueState());
    }

    /** Encerramento ordenado pedido pelo shell desktop: cancela execuções, encerra processos e fecha o banco. */
    @PostMapping("/system/shutdown")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void shutdown() {
        lifecycle.requestShutdown();
    }

    @PostMapping("/system/starter-agents")
    public SampleData.Starter starterAgents() {
        return sampleData.ensureStarterAgents();
    }

    @PostMapping("/system/sample-project")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectController.ProjectView sampleProject() {
        Project project = sampleData.loadSampleProject();
        return ProjectController.ProjectView.of(project);
    }
}
