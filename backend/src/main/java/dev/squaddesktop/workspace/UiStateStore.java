package dev.squaddesktop.workspace;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.JsonCodec;

/** Guarda o estado da interface (abas, larguras, disposição dos painéis) como JSON por chave. */
public class UiStateStore {

    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final int MAX_CHARS = 256 * 1024;

    private final Database database;
    private final JsonCodec json;
    private final Clock clock;

    public UiStateStore(Database database, JsonCodec json, Clock clock) {
        this.database = database;
        this.json = json;
        this.clock = clock;
    }

    /** @return o JSON salvo, ou null quando a chave nunca foi gravada */
    public String get(String key) {
        requireKey(key);
        return database.read(tx -> tx.scalarString("SELECT value FROM ui_state WHERE key = ?", key));
    }

    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        database.read(tx -> tx.query("SELECT key, value FROM ui_state ORDER BY key", row -> out.put(row.str("key"), row.str("value"))));
        return out;
    }

    public void put(String key, String value) {
        requireKey(key);
        if (value == null || value.length() > MAX_CHARS) {
            throw ApiException.invalid("invalid_state", "Estado de interface ausente ou grande demais.");
        }
        try {
            json.parse(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("invalid_state", "O estado de interface precisa ser JSON válido.");
        }
        database.writeVoid(tx -> tx.update("""
                INSERT INTO ui_state (key, value, updated_at) VALUES (?, ?, ?)
                ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at""",
                key, value, clock.instant().toString()));
    }

    private static void requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ApiException.invalid("invalid_key", "Chave de estado inválida.");
        }
    }
}
