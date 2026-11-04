package dev.squaddesktop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class DataDirLockTest {

    @TempDir
    Path dir;

    @Test
    void pegaATravaECriaOArquivo() {
        try (DataDirLock lock = DataDirLock.acquire(dir.resolve("dados"), Duration.ofSeconds(1))) {
            assertThat(lock).isNotNull();
            assertThat(Files.exists(dir.resolve("dados").resolve(DataDirLock.FILE_NAME))).isTrue();
        }
    }

    @Test
    void desisteDepoisDoPrazoSeAPastaContinuaOcupada() {
        try (DataDirLock first = DataDirLock.acquire(dir, Duration.ofSeconds(1))) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> DataDirLock.acquire(dir, Duration.ofMillis(600)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(DataDirLock.BUSY_MARK);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(600));
        }
    }

    @Test
    void esperaASessaoAnteriorSoltarAPasta() throws Exception {
        DataDirLock first = DataDirLock.acquire(dir, Duration.ofSeconds(1));
        CompletableFuture<DataDirLock> second = CompletableFuture.supplyAsync(() -> DataDirLock.acquire(dir, Duration.ofSeconds(10)));
        Thread.sleep(500);
        assertThat(second).as("a segunda sessão espera enquanto a primeira tem a trava").isNotDone();
        first.close();
        try (DataDirLock acquired = second.get(5, TimeUnit.SECONDS)) {
            assertThat(acquired).isNotNull();
        }
    }

    @Test
    void liberaAoFecharParaAProximaSessao() {
        DataDirLock.acquire(dir, Duration.ofSeconds(1)).close();
        try (DataDirLock again = DataDirLock.acquire(dir, Duration.ofMillis(200))) {
            assertThat(again).isNotNull();
        }
    }
}
