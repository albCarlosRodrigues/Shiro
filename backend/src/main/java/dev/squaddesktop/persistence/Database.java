package dev.squaddesktop.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Acesso ao arquivo SQLite local.
 *
 * <p>O SQLite aceita um único escritor por vez. Em vez de deixar conexões concorrentes disputarem o
 * bloqueio do arquivo (e falharem com SQLITE_BUSY), todas as escritas passam por <em>uma</em> conexão,
 * serializada por um lock justo, em transações curtas. Leituras usam um pequeno conjunto de conexões
 * somente leitura; com o diário em modo WAL elas não bloqueiam nem são bloqueadas pelo escritor.
 */
public final class Database implements AutoCloseable {

    private final Path file;
    private final ReentrantLock writeLock = new ReentrantLock(true);
    private final Connection writer;
    private final BlockingQueue<Connection> idleReaders;
    private final AtomicInteger readersCreated = new AtomicInteger();
    private final int maxReaders;
    private final ThreadLocal<Tx> currentWrite = new ThreadLocal<>();
    private volatile boolean closed;

    public Database(Path file) {
        this(file, 4);
    }

    public Database(Path file, int maxReaders) {
        this.file = file.toAbsolutePath();
        this.maxReaders = Math.max(1, maxReaders);
        this.idleReaders = new ArrayBlockingQueue<>(this.maxReaders);
        try {
            Path parent = this.file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = open(false);
            this.writer.setAutoCommit(false);
        } catch (SQLException | IOException e) {
            throw new StoreException("Não foi possível abrir o banco em " + this.file, e);
        }
    }

    public Path file() {
        return file;
    }

    /** Executa uma transação de escrita. Chamadas aninhadas na mesma thread participam da transação externa. */
    public <T> T write(Function<Tx, T> work) {
        ensureOpen();
        Tx outer = currentWrite.get();
        if (outer != null) {
            return work.apply(outer);
        }
        writeLock.lock();
        try {
            Tx tx = new Tx(writer, true);
            currentWrite.set(tx);
            try {
                T result = work.apply(tx);
                writer.commit();
                return result;
            } catch (RuntimeException | Error e) {
                rollbackQuietly();
                throw e;
            } catch (SQLException e) {
                rollbackQuietly();
                throw new StoreException("Falha ao confirmar a transação", e);
            } finally {
                currentWrite.remove();
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void writeVoid(java.util.function.Consumer<Tx> work) {
        write(tx -> {
            work.accept(tx);
            return null;
        });
    }

    /** Executa leituras. Dentro de uma escrita em andamento na mesma thread, enxerga os dados ainda não confirmados. */
    public <T> T read(Function<Tx, T> work) {
        ensureOpen();
        Tx outer = currentWrite.get();
        if (outer != null) {
            return work.apply(outer);
        }
        Connection reader = borrowReader();
        try {
            return work.apply(new Tx(reader, false));
        } finally {
            if (!idleReaders.offer(reader)) {
                closeQuietly(reader);
            }
        }
    }

    /** Gera uma cópia consistente do banco (inclui o que ainda está no WAL). O destino não pode existir. */
    public void snapshotTo(Path target) {
        ensureOpen();
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new StoreException("Não foi possível criar a pasta de " + target, e);
        }
        String literal = target.toAbsolutePath().toString().replace("'", "''");
        // Conexão própria, fora de transação: VACUUM INTO não roda dentro de transação nem em conexão somente leitura.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("VACUUM INTO '" + literal + "'");
        } catch (SQLException e) {
            throw new StoreException("Falha ao gerar a cópia do banco em " + target, e);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        writeLock.lock();
        try {
            try (var statement = writer.createStatement()) {
                writer.commit();
                writer.setAutoCommit(true);
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException ignored) {
                // o fechamento prossegue mesmo sem o checkpoint
            }
            closeQuietly(writer);
        } finally {
            writeLock.unlock();
        }
        Connection reader;
        while ((reader = idleReaders.poll()) != null) {
            closeQuietly(reader);
        }
    }

    private Connection borrowReader() {
        Connection reader = idleReaders.poll();
        if (reader != null) {
            return reader;
        }
        if (readersCreated.incrementAndGet() <= maxReaders) {
            try {
                return open(true);
            } catch (SQLException e) {
                readersCreated.decrementAndGet();
                throw new StoreException("Não foi possível abrir conexão de leitura", e);
            }
        }
        readersCreated.decrementAndGet();
        try {
            return idleReaders.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("Leitura interrompida enquanto aguardava conexão", e);
        }
    }

    private Connection open(boolean readOnly) throws SQLException {
        Properties props = new Properties();
        props.setProperty("foreign_keys", "true");
        props.setProperty("busy_timeout", "5000");
        if (!readOnly) {
            props.setProperty("journal_mode", "WAL");
            props.setProperty("synchronous", "NORMAL");
            props.setProperty("transaction_mode", "IMMEDIATE");
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file, props);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            if (readOnly) {
                statement.execute("PRAGMA query_only = ON");
            } else {
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA synchronous = NORMAL");
            }
        }
        return connection;
    }

    private void rollbackQuietly() {
        try {
            writer.rollback();
        } catch (SQLException ignored) {
            // nada a fazer: a exceção original é a que interessa
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // fechamento em melhor esforço
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new StoreException("O banco já foi fechado");
        }
    }
}
