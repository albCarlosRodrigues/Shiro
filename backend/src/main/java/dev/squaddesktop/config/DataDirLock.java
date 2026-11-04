package dev.squaddesktop.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Trava exclusiva da pasta de dados: impede que dois serviços do Shiro usem o mesmo banco ao mesmo tempo
 * (por exemplo, o app reaberto enquanto o serviço anterior ainda encerra).
 *
 * <p>A trava é do sistema operacional ({@link FileChannel#tryLock()}): se o processo morrer, ela é liberada
 * sozinha. O arquivo {@code shiro.lock} fica na pasta e nunca é apagado, para não abrir brecha entre apagar e
 * recriar. O shell desktop reconhece as marcas {@link #WAITING_MARK} e {@link #BUSY_MARK} no log.
 */
public final class DataDirLock implements AutoCloseable {

    /** Marca no log enquanto espera a sessão anterior soltar a pasta. */
    public static final String WAITING_MARK = "[pasta-de-dados-em-uso]";
    /** Marca no log quando a espera acabou e a pasta continua ocupada. */
    public static final String BUSY_MARK = "[pasta-de-dados-ocupada]";

    static final String FILE_NAME = "shiro.lock";
    private static final Logger LOG = Logger.getLogger(DataDirLock.class.getName());
    private static final long POLL_MILLIS = 200;

    private final FileChannel channel;
    private final FileLock lock;

    private DataDirLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Pega a trava, esperando até {@code wait} se outra sessão ainda estiver com ela. */
    public static DataDirLock acquire(Path dataDir, Duration wait) {
        Path file = dataDir.resolve(FILE_NAME);
        FileChannel channel;
        try {
            Files.createDirectories(dataDir);
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível abrir a trava da pasta de dados " + file + ": " + e.getMessage(), e);
        }
        long start = System.nanoTime();
        long deadline = start + wait.toNanos();
        boolean warned = false;
        try {
            while (true) {
                FileLock lock = tryLock(channel);
                if (lock != null) {
                    if (warned) {
                        LOG.info("Pasta de dados liberada pela sessão anterior depois de "
                                + Duration.ofNanos(System.nanoTime() - start).toMillis() + " ms.");
                    }
                    return new DataDirLock(channel, lock);
                }
                if (!warned) {
                    LOG.warning(WAITING_MARK + " Outra sessão do Shiro ainda usa a pasta de dados " + dataDir
                            + "; aguardando até " + wait.toSeconds() + " s.");
                    warned = true;
                }
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(BUSY_MARK + " A pasta de dados " + dataDir
                            + " continua em uso por outra sessão do Shiro depois de " + wait.toSeconds() + " s.");
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(channel);
            throw new IllegalStateException("Espera pela trava da pasta de dados interrompida.", e);
        } catch (RuntimeException e) {
            closeQuietly(channel);
            throw e;
        }
    }

    private static FileLock tryLock(FileChannel channel) {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            // outra parte deste mesmo processo já tem a trava (só acontece em testes)
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao travar a pasta de dados: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // o fechamento do canal libera de qualquer forma
        }
        closeQuietly(channel);
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // nada a fazer
        }
    }
}
