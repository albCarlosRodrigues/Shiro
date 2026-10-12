package dev.squaddesktop.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @TempDir
    Path dir;

    @Test
    void aplicaMigracoesUmaVezEConfereChecksum() {
        try (Database db = new Database(dir.resolve("a.db"))) {
            Migrator migrator = new Migrator(db, Clock.systemUTC());
            assertThat(migrator.migrate()).hasSize(Migrator.MIGRATIONS.size());
            assertThat(migrator.migrate()).isEmpty();
            assertThat(migrator.currentVersion()).isEqualTo(migrator.latestVersion());
            db.writeVoid(tx -> tx.update("UPDATE schema_migration SET checksum = 'outro' WHERE version = 1"));
            assertThatThrownBy(migrator::migrate).isInstanceOf(StoreException.class).hasMessageContaining("já foi aplicada com outro conteúdo");
        }
    }

    @Test
    void divideComandosPorPontoEVirgulaNoFimDaLinha() {
        List<String> statements = Migrator.splitStatements("""
                -- comentário
                CREATE TABLE a (x TEXT DEFAULT 'a;b');

                CREATE INDEX i ON a (x);
                """);
        assertThat(statements).containsExactly("CREATE TABLE a (x TEXT DEFAULT 'a;b')", "CREATE INDEX i ON a (x)");
    }

    @Test
    void escritaComFalhaDesfazTudo() {
        try (Database db = new Database(dir.resolve("b.db"))) {
            db.writeVoid(tx -> tx.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT NOT NULL)"));
            assertThatThrownBy(() -> db.writeVoid(tx -> {
                tx.update("INSERT INTO t (v) VALUES (?)", "ok");
                tx.update("INSERT INTO t (v) VALUES (?)", (Object) null);
            })).isInstanceOf(StoreException.class);
            assertThat(db.<Long>read(tx -> tx.scalarLong("SELECT COUNT(*) FROM t"))).isZero();
        }
    }

    @Test
    void escritasConcorrentesSaoSerializadasSemPerda() throws Exception {
        try (Database db = new Database(dir.resolve("c.db"))) {
            db.writeVoid(tx -> tx.execute("CREATE TABLE contador (id INTEGER PRIMARY KEY, n INTEGER NOT NULL)"));
            db.writeVoid(tx -> tx.update("INSERT INTO contador (id, n) VALUES (1, 0)"));
            int threads = 8;
            int each = 50;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < each; j++) {
                        // leitura e escrita na mesma transação: sem a serialização, incrementos se perderiam
                        db.writeVoid(tx -> {
                            long n = tx.scalarLong("SELECT n FROM contador WHERE id = 1");
                            tx.update("UPDATE contador SET n = ? WHERE id = 1", n + 1);
                        });
                        db.read(tx -> tx.scalarLong("SELECT n FROM contador WHERE id = 1"));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();
            assertThat(db.<Long>read(tx -> tx.scalarLong("SELECT n FROM contador WHERE id = 1"))).isEqualTo((long) threads * each);
        }
    }

    @Test
    void leituraNaoEnxergaEscritaAindaNaoConfirmadaDeOutraThread() throws Exception {
        try (Database db = new Database(dir.resolve("d.db"))) {
            db.writeVoid(tx -> tx.execute("CREATE TABLE t (v TEXT)"));
            CountDownLatch inserted = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            Thread writer = Thread.ofPlatform().start(() -> db.writeVoid(tx -> {
                tx.update("INSERT INTO t (v) VALUES ('x')");
                inserted.countDown();
                try {
                    checked.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            inserted.await();
            assertThat(db.<Long>read(tx -> tx.scalarLong("SELECT COUNT(*) FROM t"))).isZero();
            checked.countDown();
            writer.join();
            assertThat(db.<Long>read(tx -> tx.scalarLong("SELECT COUNT(*) FROM t"))).isEqualTo(1);
        }
    }

    @Test
    void geraCopiaConsistente() {
        Path copy = dir.resolve("copias").resolve("copia.db");
        try (Database db = new Database(dir.resolve("e.db"))) {
            new Migrator(db, Clock.systemUTC()).migrate();
            db.writeVoid(tx -> tx.update("INSERT INTO app_setting (key, value, updated_at) VALUES ('k', 'v', 'agora')"));
            db.snapshotTo(copy);
        }
        assertThat(Files.exists(copy)).isTrue();
        try (Database restored = new Database(copy)) {
            assertThat(restored.<String>read(tx -> tx.scalarString("SELECT value FROM app_setting WHERE key = 'k'"))).isEqualTo("v");
        }
    }
}
