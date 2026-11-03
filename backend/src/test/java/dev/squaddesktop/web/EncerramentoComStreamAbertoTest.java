package dev.squaddesktop.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import dev.squaddesktop.SquadDesktopApplication;

/**
 * Regressão do fechamento lento (REL-004): com o stream SSE aberto, o graceful shutdown esperava 8 s pela
 * requisição assíncrona e o Tomcat mais ~2 s. O encerramento tem de completar os emissores antes disso.
 */
@Timeout(60)
class EncerramentoComStreamAbertoTest {

    static final String TOKEN = "token-de-teste-encerramento-1234567890";

    @TempDir
    Path dir;

    @Test
    void encerraEmMenosDeCincoSegundosComStreamAberto() throws Exception {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(SquadDesktopApplication.class)
                .properties("server.port=0",
                        "squad.data-dir=" + dir.resolve("dados"),
                        "squad.auth-token=" + TOKEN,
                        "squad.ready-file=" + dir.resolve("pronto.json"))
                .run();
        boolean closed = false;
        try {
            int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/events"))
                    .header("Authorization", "Bearer " + TOKEN).GET().build();
            HttpResponse<InputStream> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            assertThat(waitForHello(reader)).as("o stream precisa estar de fato aberto antes do encerramento").isTrue();
            CompletableFuture<Void> streamEnded = CompletableFuture.runAsync(() -> drain(reader));

            long start = System.nanoTime();
            context.close();
            closed = true;
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(took).as("encerramento com SSE aberto").isLessThan(Duration.ofSeconds(5));
            streamEnded.get(5, TimeUnit.SECONDS);
        } finally {
            if (!closed) {
                context.close();
            }
        }
    }

    private static boolean waitForHello(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains("hello")) {
                return true;
            }
        }
        return false;
    }

    private static void drain(BufferedReader reader) {
        try {
            while (reader.readLine() != null) {
                // só consome até o servidor fechar o stream
            }
        } catch (IOException ignored) {
            // conexão encerrada pelo servidor
        }
    }
}
