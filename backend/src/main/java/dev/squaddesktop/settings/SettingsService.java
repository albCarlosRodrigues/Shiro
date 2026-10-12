package dev.squaddesktop.settings;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.Texts;

public class SettingsService {

    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9._:\\-\\[\\]]{1,80}");

    private final Database database;
    private final Clock clock;
    private final CopyOnWriteArrayList<Consumer<AppSettings>> listeners = new CopyOnWriteArrayList<>();
    private volatile AppSettings cached;

    public SettingsService(Database database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    public AppSettings get() {
        AppSettings current = cached;
        if (current == null) {
            current = load();
            cached = current;
        }
        return current;
    }

    public AppSettings update(AppSettings requested) {
        AppSettings clean = validate(requested);
        String now = clock.instant().toString();
        database.writeVoid(tx -> {
            put(tx, "claude.path", clean.claudePath(), now);
            put(tx, "claude.defaultModel", clean.defaultModel(), now);
            put(tx, "runs.maxConcurrent", String.valueOf(clean.maxConcurrentRuns()), now);
            put(tx, "runs.timeoutMinutes", String.valueOf(clean.runTimeoutMinutes()), now);
            put(tx, "runs.maxBudgetUsd", clean.maxBudgetUsd() == null ? null : String.valueOf(clean.maxBudgetUsd()), now);
            put(tx, "demo.enabled", String.valueOf(clean.demoMode()), now);
            put(tx, "features.isolated", String.valueOf(clean.isolatedFeatures()), now);
        });
        cached = clean;
        listeners.forEach(listener -> listener.accept(clean));
        return clean;
    }

    public void onChange(Consumer<AppSettings> listener) {
        listeners.add(listener);
    }

    public static boolean isValidModel(String model) {
        return model != null && MODEL.matcher(model).matches();
    }

    private AppSettings validate(AppSettings s) {
        if (s == null) {
            throw ApiException.invalid("required", "Envie as preferências.");
        }
        String path = Texts.trimToNull(s.claudePath());
        if (path != null && path.length() > 1000) {
            throw ApiException.invalid("too_long", "O caminho do Claude Code é longo demais.");
        }
        String model = Texts.trimToNull(s.defaultModel());
        if (model != null && !isValidModel(model)) {
            throw ApiException.invalid("invalid_model", "Nome de modelo inválido: use um apelido (por exemplo, sonnet) ou o nome completo.");
        }
        if (s.maxConcurrentRuns() < 1 || s.maxConcurrentRuns() > 8) {
            throw ApiException.invalid("invalid_concurrency", "O limite de execuções simultâneas deve ficar entre 1 e 8.");
        }
        if (s.runTimeoutMinutes() < 1 || s.runTimeoutMinutes() > 720) {
            throw ApiException.invalid("invalid_timeout", "O tempo máximo de execução deve ficar entre 1 e 720 minutos.");
        }
        Double budget = s.maxBudgetUsd();
        if (budget != null && (budget.isNaN() || budget <= 0 || budget > 1000)) {
            throw ApiException.invalid("invalid_budget", "O orçamento por execução deve ser maior que zero e de até 1000 dólares.");
        }
        return new AppSettings(path, model, s.maxConcurrentRuns(), s.runTimeoutMinutes(), budget, s.demoMode(), s.isolatedFeatures());
    }

    private AppSettings load() {
        Map<String, String> values = new HashMap<>();
        database.read(tx -> tx.query("SELECT key, value FROM app_setting", row -> values.put(row.str("key"), row.str("value"))));
        AppSettings d = AppSettings.DEFAULTS;
        return new AppSettings(
                values.get("claude.path"),
                values.get("claude.defaultModel"),
                parseInt(values.get("runs.maxConcurrent"), d.maxConcurrentRuns()),
                parseInt(values.get("runs.timeoutMinutes"), d.runTimeoutMinutes()),
                parseDouble(values.get("runs.maxBudgetUsd")),
                Boolean.parseBoolean(values.getOrDefault("demo.enabled", "false")),
                Boolean.parseBoolean(values.getOrDefault("features.isolated", "false")));
    }

    private static void put(dev.squaddesktop.persistence.Tx tx, String key, String value, String now) {
        if (value == null) {
            tx.update("DELETE FROM app_setting WHERE key = ?", key);
        } else {
            tx.update("""
                    INSERT INTO app_setting (key, value, updated_at) VALUES (?, ?, ?)
                    ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at""",
                    key, value, now);
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Double parseDouble(String value) {
        try {
            return value == null ? null : Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
