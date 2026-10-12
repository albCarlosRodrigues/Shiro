package dev.squaddesktop.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aplica as migrações versionadas de {@code db/migration} em ordem, uma transação por arquivo.
 *
 * <p>Convenções: os arquivos estão listados em {@link #MIGRATIONS}; uma migração já aplicada nunca é
 * editada (o checksum é conferido na inicialização); comandos terminam em ponto e vírgula no fim da
 * linha; não há gatilhos, para que essa divisão simples seja suficiente.
 */
public final class Migrator {

    /** Ordem de aplicação. Acrescente novas migrações ao final. */
    public static final List<String> MIGRATIONS = List.of(
            "V001__base.sql",
            "V002__planejamento.sql");

    public record Applied(int version, String name) {
    }

    private final Database database;
    private final Clock clock;
    private final String resourceRoot;

    public Migrator(Database database, Clock clock) {
        this(database, clock, "db/migration/");
    }

    Migrator(Database database, Clock clock, String resourceRoot) {
        this.database = database;
        this.clock = clock;
        this.resourceRoot = resourceRoot;
    }

    /** Versão mais alta registrada, ou 0 num banco novo. */
    public int currentVersion() {
        ensureTable();
        return database.read(tx -> tx.scalarLong("SELECT COALESCE(MAX(version), 0) FROM schema_migration")).intValue();
    }

    public int latestVersion() {
        return versionOf(MIGRATIONS.get(MIGRATIONS.size() - 1));
    }

    public List<Applied> migrate() {
        return migrate(MIGRATIONS);
    }

    List<Applied> migrate(List<String> names) {
        ensureTable();
        Map<Integer, String> recorded = new TreeMap<>();
        database.read(tx -> tx.query("SELECT version, checksum FROM schema_migration ORDER BY version",
                row -> recorded.put(row.integer("version"), row.str("checksum"))));

        List<Applied> applied = new ArrayList<>();
        int previous = 0;
        for (String name : names) {
            int version = versionOf(name);
            if (version <= previous) {
                throw new StoreException("Migrações fora de ordem: " + name);
            }
            previous = version;
            String sql = load(name);
            String checksum = sha256(sql);
            String known = recorded.get(version);
            if (known != null) {
                if (!known.equals(checksum)) {
                    throw new StoreException("A migração " + name + " já foi aplicada com outro conteúdo. "
                            + "Migrações aplicadas não podem ser editadas; crie uma nova versão.");
                }
                continue;
            }
            database.writeVoid(tx -> {
                for (String statement : splitStatements(sql)) {
                    tx.execute(statement);
                }
                tx.update("INSERT INTO schema_migration (version, name, checksum, applied_at) VALUES (?, ?, ?, ?)",
                        version, name, checksum, clock.instant().toString());
            });
            applied.add(new Applied(version, name));
        }
        return applied;
    }

    private void ensureTable() {
        database.writeVoid(tx -> tx.execute("""
                CREATE TABLE IF NOT EXISTS schema_migration (
                    version    INTEGER PRIMARY KEY,
                    name       TEXT NOT NULL,
                    checksum   TEXT NOT NULL,
                    applied_at TEXT NOT NULL
                )"""));
    }

    private String load(String name) {
        String resource = resourceRoot + name;
        try (InputStream in = Migrator.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new StoreException("Migração não encontrada no classpath: " + resource);
            }
            // Normaliza quebras de linha para o checksum não depender do sistema que gerou o pacote.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new StoreException("Falha ao ler a migração " + resource, e);
        }
    }

    static int versionOf(String name) {
        int sep = name.indexOf("__");
        if (!name.startsWith("V") || sep < 2) {
            throw new StoreException("Nome de migração inválido (esperado V001__descricao.sql): " + name);
        }
        return Integer.parseInt(name.substring(1, sep));
    }

    /** Divide o script em comandos: um comando termina em ';' no fim da linha. Linhas de comentário são ignoradas. */
    static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String statement = current.toString().strip();
                statements.add(statement.substring(0, statement.length() - 1));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().strip());
        }
        return statements;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
