package dev.squaddesktop.system;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import dev.squaddesktop.claude.ClaudeEnvironment;
import dev.squaddesktop.config.SquadPaths;
import dev.squaddesktop.config.SquadSettings;
import dev.squaddesktop.features.FeatureRunner;
import dev.squaddesktop.planning.PlanningService;
import dev.squaddesktop.runs.RunOrchestrator;
import dev.squaddesktop.shared.JsonCodec;

/**
 * Inicialização e encerramento do serviço local:
 * recuperação de execuções interrompidas, anúncio de prontidão para o shell desktop, vigia do processo pai
 * e encerramento ordenado (execuções e processos do Claude Code primeiro, banco por último).
 */
@Component
public class AppLifecycle {

    private static final Logger LOG = Logger.getLogger(AppLifecycle.class.getName());

    private final RunOrchestrator runs;
    private final SquadPaths paths;
    private final SquadSettings settings;
    private final JsonCodec json;
    private final Environment environment;
    private final ApplicationContext context;
    private final ClaudeEnvironment claude;
    private final FeatureRunner featureRunner;
    private final PlanningService planning;
    private volatile int port;

    public AppLifecycle(RunOrchestrator runs, SquadPaths paths, SquadSettings settings, JsonCodec json, Environment environment,
            ApplicationContext context, ClaudeEnvironment claude, FeatureRunner featureRunner, PlanningService planning) {
        this.featureRunner = featureRunner;
        this.planning = planning;
        this.claude = claude;
        this.runs = runs;
        this.paths = paths;
        this.settings = settings;
        this.json = json;
        this.environment = environment;
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        cleanTemp();
        claude.detectInBackground();
        int interrupted = runs.recoverInterrupted();
        if (interrupted > 0) {
            LOG.warning(interrupted + " execução(ões) sem desfecho foram marcadas como interrompidas.");
        }
        // depois das execuções: rodadas abertas são encerradas e filas com features pendentes ficam pausadas
        planning.reconcile();
        featureRunner.reconcile();
        port = Integer.parseInt(environment.getProperty("local.server.port", "0"));
        writeReadyFile();
        watchParent();
        LOG.info("Squad Desktop pronto em http://127.0.0.1:" + port + " (dados em " + paths.dataDir() + ")");
    }

    public int port() {
        return port;
    }

    /** Pedido de encerramento vindo do shell desktop: cancela execuções e fecha o contexto fora da thread da requisição. */
    public void requestShutdown() {
        Thread.ofPlatform().name("squad-shutdown").start(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            runs.shutdown(Duration.ofSeconds(8));
            int code = org.springframework.boot.SpringApplication.exit(context, () -> 0);
            System.exit(code);
        });
    }

    private void writeReadyFile() {
        if (settings.readyFile() == null) {
            return;
        }
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("port", port);
        ready.put("pid", ProcessHandle.current().pid());
        ready.put("version", settings.appVersion());
        ready.put("dataDir", paths.dataDir().toString());
        try {
            Path target = Path.of(settings.readyFile());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, json.write(ready), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOG.severe("Não foi possível gravar o arquivo de prontidão: " + e.getMessage());
        }
    }

    /** Se o shell desktop terminar sem avisar, o serviço encerra as execuções e sai, para não deixar processos órfãos. */
    private void watchParent() {
        if (settings.parentPid() == null) {
            return;
        }
        ProcessHandle.of(settings.parentPid()).ifPresentOrElse(
                parent -> parent.onExit().thenRun(() -> {
                    LOG.warning("O processo do aplicativo terminou; encerrando o serviço local.");
                    requestShutdown();
                }),
                () -> {
                    LOG.warning("Processo pai " + settings.parentPid() + " não encontrado; encerrando.");
                    requestShutdown();
                });
    }

    private void cleanTemp() {
        try (Stream<Path> files = Files.list(paths.temp())) {
            files.filter(Files::isRegularFile).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // arquivo em uso por outra instância; fica para a próxima limpeza
                }
            });
        } catch (IOException ignored) {
            // pasta temporária ausente: nada a limpar
        }
    }
}
