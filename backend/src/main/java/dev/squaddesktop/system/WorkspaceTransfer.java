package dev.squaddesktop.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.persistence.Migrator;
import dev.squaddesktop.persistence.Tx;
import dev.squaddesktop.runs.RunOrchestrator;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.shared.JsonValues;

/**
 * Exportação e importação do workspace (projetos, agentes, squads, documentos, conversas, planos e features) e cópias
 * de segurança do banco. Preferências da máquina (caminho do Claude Code, estado da interface) não viajam.
 *
 * <p>Importar substitui o workspace inteiro, numa única transação, depois de gravar uma cópia do banco atual. O
 * arquivo é validado antes: formato, versão do esquema, tabelas e colunas conhecidas. As pastas de código dos
 * projetos nunca são tocadas.
 */
public class WorkspaceTransfer {

    public static final String FORMAT = "squad-desktop-workspace";
    public static final int FORMAT_VERSION = 1;

    /** Ordem de exportação e inserção (pais antes dos filhos). */
    static final List<String> TABLES = List.of("project_group", "agent", "squad", "squad_operator", "project", "document",
            "document_version", "session", "run", "message", "run_event", "planning_message", "document_proposal", "plan", "sprint",
            "feature", "feature_dependency", "feature_step", "feature_review", "feature_queue");

    /** Contagens mostradas na confirmação. */
    private static final Map<String, String> COUNTED = Map.of("project_group", "groups", "project", "projects", "agent", "agents",
            "squad", "squads", "document", "documents", "session", "sessions", "message", "messages", "plan", "plans", "sprint", "sprints",
            "feature", "features");

    public record BackupEntry(String name, String path, String reason, long bytes, String createdAt) {
    }

    public record ImportCheck(boolean ok, List<String> problems, List<String> warnings, Map<String, Integer> counts, String exportedAt,
            String appVersion) {
    }

    public record ImportResult(BackupEntry backup, Map<String, Integer> counts) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Database database;
    private final Clock clock;
    private final Path backups;
    private final String appVersion;
    private final RunOrchestrator runs;

    public WorkspaceTransfer(Database database, Clock clock, Path backups, String appVersion, RunOrchestrator runs) {
        this.database = database;
        this.clock = clock;
        this.backups = backups;
        this.appVersion = appVersion;
        this.runs = runs;
    }

    /* ---------- exportação ---------- */

    public Map<String, Object> export() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", FORMAT);
        out.put("version", FORMAT_VERSION);
        out.put("schemaVersion", schemaVersion());
        out.put("appVersion", appVersion);
        out.put("exportedAt", clock.instant().toString());
        Map<String, Object> tables = new LinkedHashMap<>();
        database.read(tx -> {
            for (String table : TABLES) {
                List<String> columns = columns(tx, table);
                String select = "SELECT " + String.join(", ", columns.stream().map(WorkspaceTransfer::quote).toList()) + " FROM " + table;
                List<Map<String, Object>> rows = tx.query(select, row -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (String column : columns) {
                        values.put(column, row.object(column));
                    }
                    return values;
                });
                tables.put(table, rows);
            }
            return null;
        });
        out.put("tables", tables);
        return out;
    }

    /* ---------- importação ---------- */

    public ImportCheck validate(Object payload) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (!(payload instanceof Map<?, ?>)) {
            return new ImportCheck(false, List.of("O arquivo não é uma exportação do Squad Desktop."), List.of(), counts, null, null);
        }
        if (!FORMAT.equals(JsonValues.str(payload, "format"))) {
            problems.add("O arquivo não é uma exportação do Squad Desktop.");
        }
        Integer version = JsonValues.intValue(payload, "version");
        if (version == null || version != FORMAT_VERSION) {
            problems.add("Versão de formato não suportada: " + version + ".");
        }
        Integer schema = JsonValues.intValue(payload, "schemaVersion");
        int current = schemaVersion();
        if (schema == null || schema > current) {
            problems.add("O arquivo foi gerado por uma versão mais nova do Squad Desktop (esquema " + schema + "; este aceita até " + current + ").");
        } else if (schema < current) {
            warnings.add("O arquivo é de uma versão anterior (esquema " + schema + "); campos novos receberão valores padrão.");
        }
        Map<String, Object> tables = JsonValues.map(payload, "tables");
        if (tables == null) {
            problems.add("O arquivo não contém tabelas.");
        } else {
            database.read(tx -> {
                for (Map.Entry<String, Object> entry : tables.entrySet()) {
                    if (!TABLES.contains(entry.getKey())) {
                        warnings.add("Tabela desconhecida ignorada: " + entry.getKey() + ".");
                        continue;
                    }
                    if (!(entry.getValue() instanceof List<?> rows)) {
                        problems.add("Tabela " + entry.getKey() + " em formato inválido.");
                        continue;
                    }
                    Set<String> known = new LinkedHashSet<>(columns(tx, entry.getKey()));
                    Set<String> unknown = new LinkedHashSet<>();
                    for (Object row : rows) {
                        if (!(row instanceof Map<?, ?> m)) {
                            problems.add("Linha inválida na tabela " + entry.getKey() + ".");
                            break;
                        }
                        for (Object key : m.keySet()) {
                            if (!known.contains(String.valueOf(key))) {
                                unknown.add(String.valueOf(key));
                            }
                        }
                    }
                    if (!unknown.isEmpty()) {
                        problems.add("Colunas desconhecidas em " + entry.getKey() + ": " + String.join(", ", unknown) + ".");
                    }
                    String label = COUNTED.get(entry.getKey());
                    if (label != null) {
                        counts.put(label, rows.size());
                    }
                }
                return null;
            });
        }
        return new ImportCheck(problems.isEmpty(), problems, warnings, counts, JsonValues.str(payload, "exportedAt"),
                JsonValues.str(payload, "appVersion"));
    }

    /** Substitui o workspace pelo conteúdo do arquivo, depois de validar e de copiar o banco atual. */
    public ImportResult importWorkspace(Object payload) {
        ImportCheck check = validate(payload);
        if (!check.ok()) {
            throw new ApiException(ApiException.Kind.INVALID, "import_invalid", "O arquivo foi recusado: " + String.join(" ", check.problems()),
                    Map.of("problems", check.problems()));
        }
        if (runs.hasActiveWork()) {
            throw ApiException.conflict("active_work", "Há execuções em andamento ou na fila. Cancele-as ou aguarde antes de importar.");
        }
        BackupEntry backup = snapshot("antes-de-importar");
        Map<String, Object> tables = JsonValues.map(payload, "tables");
        String now = clock.instant().toString();
        database.writeVoid(tx -> {
            tx.execute("PRAGMA defer_foreign_keys = ON");
            for (int i = TABLES.size() - 1; i >= 0; i--) {
                tx.update("DELETE FROM " + TABLES.get(i));
            }
            tx.update("DELETE FROM planning_round");
            for (String table : TABLES) {
                Object raw = tables.get(table);
                if (!(raw instanceof List<?> rows)) {
                    continue;
                }
                Set<String> known = new LinkedHashSet<>(columns(tx, table));
                for (Object item : rows) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) item;
                    List<String> cols = row.keySet().stream().filter(known::contains).toList();
                    if (cols.isEmpty()) {
                        continue;
                    }
                    String sql = "INSERT INTO " + table + " (" + String.join(", ", cols.stream().map(WorkspaceTransfer::quote).toList())
                            + ") VALUES (" + String.join(", ", cols.stream().map(c -> "?").toList()) + ")";
                    tx.update(sql, cols.stream().map(c -> plain(row.get(c))).toArray());
                }
            }
            // nada importado volta a rodar sozinho
            tx.update("""
                    UPDATE run SET status = 'INTERRUPTED', finished_at = COALESCE(finished_at, ?),
                           failure_message = COALESCE(failure_message, 'Importada sem desfecho; não foi retomada.')
                    WHERE status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLING')""", now);
            tx.update("UPDATE feature SET status = 'FAILED', status_note = 'Importada durante a execução; autorize de novo se quiser continuar.' WHERE status = 'IN_PROGRESS'");
            tx.update("UPDATE feature_step SET status = 'CANCELLED' WHERE status = 'RUNNING'");
            tx.update("UPDATE feature SET workdir = NULL, branch = NULL WHERE workdir IS NOT NULL");
            tx.update("""
                    INSERT INTO feature_queue (project_id, paused, reason, updated_at)
                    SELECT DISTINCT project_id, 1, 'Workspace importado. Retome a fila quando quiser continuar.', ? FROM feature WHERE status = 'QUEUED'
                    ON CONFLICT (project_id) DO UPDATE SET paused = 1, reason = excluded.reason, updated_at = excluded.updated_at""", now);
        });
        return new ImportResult(backup, check.counts());
    }

    /* ---------- cópias do banco ---------- */

    public List<BackupEntry> backups() {
        if (!Files.isDirectory(backups)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(backups)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".db")).map(this::entry)
                    .sorted((a, b) -> b.createdAt().compareTo(a.createdAt())).limit(50).toList();
        } catch (IOException e) {
            throw ApiException.unavailable("backup_list_failed", "Não foi possível listar as cópias: " + e.getMessage());
        }
    }

    public BackupEntry snapshot(String prefix) {
        Path target = backups.resolve(prefix + "-" + STAMP.format(clock.instant()) + ".db");
        database.snapshotTo(target);
        return entry(target);
    }

    private BackupEntry entry(Path file) {
        String name = file.getFileName().toString();
        String reason = name.startsWith("antes-de-importar") ? "before-import"
                : name.startsWith("antes-da-migracao") ? "migration"
                : name.startsWith("manual") ? "manual" : "other";
        try {
            return new BackupEntry(name, file.toString(), reason, Files.size(file), Files.getLastModifiedTime(file).toInstant().toString());
        } catch (IOException e) {
            return new BackupEntry(name, file.toString(), reason, 0, "");
        }
    }

    /* ---------- apoio ---------- */

    private int schemaVersion() {
        return new Migrator(database, clock).currentVersion();
    }

    private static List<String> columns(Tx tx, String table) {
        return tx.query("SELECT name FROM pragma_table_info(?) ORDER BY cid", row -> row.str("name"), table);
    }

    private static Object plain(Object value) {
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return String.valueOf(value);
        }
        return value;
    }

    private static String quote(String column) {
        return "\"" + column.replace("\"", "\"\"") + "\"";
    }
}
