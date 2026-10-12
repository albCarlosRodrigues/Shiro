package dev.squaddesktop.claude;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.squaddesktop.runs.FailureCodes;
import dev.squaddesktop.settings.SettingsService;
import dev.squaddesktop.shared.JsonCodec;
import dev.squaddesktop.shared.JsonValues;
import dev.squaddesktop.shared.Texts;

/** Detecta e guarda a situação do Claude Code instalado. Refaz a detecção quando o caminho configurado muda. */
public class ClaudeEnvironment {

    private final java.util.function.Function<String, Optional<ClaudeInstallation>> finder;
    private final SettingsService settings;
    private final JsonCodec json;
    private final Clock clock;
    private volatile Snapshot snapshot;
    private volatile long detectedAtNanos;

    /** Uma detecção que falhou é refeita sozinha depois deste intervalo (o executável pode estar se atualizando). */
    static final long RETRY_FAILED_NANOS = 15_000_000_000L;

    /** Situação e instalação obtidas na mesma detecção. */
    public record Snapshot(ClaudeStatus status, ClaudeInstallation installation) {
    }

    public ClaudeEnvironment(ClaudeLocator locator, SettingsService settings, JsonCodec json, Clock clock) {
        this(locator::locate, settings, json, clock);
    }

    /** @param finder recebe o caminho configurado (ou null) e devolve a instalação encontrada; os testes injetam uma CLI controlada */
    public ClaudeEnvironment(java.util.function.Function<String, Optional<ClaudeInstallation>> finder, SettingsService settings,
            JsonCodec json, Clock clock) {
        this.finder = finder;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
        settings.onChange(changed -> snapshot = null);
    }

    public ClaudeStatus status() {
        return snapshot().status();
    }

    private final java.util.concurrent.atomic.AtomicBoolean detecting = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Situação sem esperar: devolve a última detecção (ou "verificando") e, se preciso, detecta em segundo plano.
     * Usado pelas telas, para que um Claude Code lento (atualizando, por exemplo) não trave a interface.
     */
    public ClaudeStatus statusNow() {
        Snapshot current = snapshot;
        if (current != null && !stale(current)) {
            return current.status();
        }
        detectInBackground();
        return current != null ? current.status()
                : new ClaudeStatus(false, false, null, null, null, null, null, ClaudeCapabilities.MINIMAL, CHECKING,
                        "Verificando o Claude Code…", clock.instant().toString());
    }

    /** Código de {@link ClaudeStatus#problemCode()} enquanto a primeira detecção não terminou. */
    public static final String CHECKING = "checking";

    public void detectInBackground() {
        if (detecting.compareAndSet(false, true)) {
            Thread.ofVirtual().name("claude-detect").start(() -> {
                try {
                    snapshot();
                } finally {
                    detecting.set(false);
                }
            });
        }
    }

    public Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current == null || stale(current)) {
            synchronized (this) {
                current = snapshot;
                if (current == null || stale(current)) {
                    current = detect();
                    snapshot = current;
                    detectedAtNanos = System.nanoTime();
                }
            }
        }
        return current;
    }

    private boolean stale(Snapshot current) {
        return !current.status().usable() && System.nanoTime() - detectedAtNanos > RETRY_FAILED_NANOS;
    }

    public synchronized ClaudeStatus refresh() {
        snapshot = detect();
        detectedAtNanos = System.nanoTime();
        return snapshot.status();
    }

    private Snapshot detect() {
        String now = clock.instant().toString();
        Optional<ClaudeInstallation> located = finder.apply(settings.get().claudePath());
        if (located.isEmpty()) {
            return new Snapshot(new ClaudeStatus(false, false, null, null, null, null, null, ClaudeCapabilities.MINIMAL,
                    FailureCodes.CLI_NOT_FOUND,
                    "O Claude Code não foi encontrado. Instale-o ou informe o caminho do executável nas preferências.", now), null);
        }
        ClaudeInstallation installation = located.get();
        if (!installation.usable()) {
            return new Snapshot(new ClaudeStatus(true, false, installation.executable(), installation.source(), null, null, null,
                    ClaudeCapabilities.MINIMAL, FailureCodes.CLI_UNSUPPORTED, installation.problem(), now), installation);
        }
        ShortCommand.Result version = ShortCommand.run(with(installation, "--version"), Duration.ofSeconds(20));
        String parsed = ClaudeCapabilities.parseVersion(version.stdout());
        // a primeira chamada pode coincidir com uma atualização automática do Claude Code: tenta de novo, com espera crescente
        for (int attempt = 1; attempt <= 2 && (!version.ok() || parsed == null); attempt++) {
            pause(1_500L * attempt);
            version = ShortCommand.run(with(installation, "--version"), Duration.ofSeconds(20));
            parsed = ClaudeCapabilities.parseVersion(version.stdout());
        }
        if (!version.ok() || parsed == null) {
            String detail = Texts.truncate(Texts.orEmpty(Texts.trimToNull(version.stderr()) != null ? version.stderr()
                    : version.error() != null ? version.error() : version.exitCode() == null ? "" : "código de saída " + version.exitCode()).strip(), 300);
            return new Snapshot(new ClaudeStatus(true, false, installation.executable(), installation.source(), null, null, null,
                    ClaudeCapabilities.MINIMAL, FailureCodes.CLI_UNSUPPORTED,
                    "O executável foi encontrado, mas não respondeu a --version" + (detail.isEmpty() ? "." : ": " + detail), now), installation);
        }
        ShortCommand.Result help = ShortCommand.run(with(installation, "--help"), Duration.ofSeconds(20));
        ClaudeCapabilities capabilities = ClaudeCapabilities.detect(parsed, help.ok() ? help.stdout() : "");

        Boolean authenticated = null;
        String authMethod = null;
        ShortCommand.Result auth = ShortCommand.run(with(installation, "auth", "status"), Duration.ofSeconds(20));
        if (auth.started() && auth.exitCode() != null) {
            try {
                Object parsedAuth = json.parse(auth.stdout());
                authenticated = JsonValues.bool(parsedAuth, "loggedIn");
                authMethod = JsonValues.str(parsedAuth, "authMethod");
            } catch (IllegalArgumentException e) {
                // versões antigas não têm "auth status" em JSON: o código de saída ainda distingue conectado de desconectado
                authenticated = auth.exitCode() == 0 ? Boolean.TRUE : (auth.exitCode() == 1 ? Boolean.FALSE : null);
            }
        }
        String problemCode = Boolean.FALSE.equals(authenticated) ? FailureCodes.NOT_AUTHENTICATED : null;
        String problem = problemCode == null ? null
                : "O Claude Code está instalado, mas sem conta conectada. Abra um terminal, rode \"claude\" e conclua o login.";
        return new Snapshot(new ClaudeStatus(true, true, installation.executable(), installation.source(), parsed, authenticated,
                authMethod, capabilities, problemCode, problem, now), installation);
    }

    private static List<String> with(ClaudeInstallation installation, String... args) {
        List<String> command = new ArrayList<>(installation.launcher());
        command.addAll(List.of(args));
        return command;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
