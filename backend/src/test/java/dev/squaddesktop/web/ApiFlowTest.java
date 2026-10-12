package dev.squaddesktop.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.squaddesktop.shared.JsonValues;
import dev.squaddesktop.testsupport.TestJson;

/** Sobe o serviço completo numa porta livre, com banco temporário e modo demonstração, e percorre a API por HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// fecha o contexto (e o banco) antes de apagar a pasta temporária: no Windows, arquivos abertos não podem ser apagados
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(90)
class ApiFlowTest {

    static final String TOKEN = "token-de-teste-1234567890";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("squad.data-dir", () -> dataDir.resolve("dados").toString());
        registry.add("squad.auth-token", () -> TOKEN);
        registry.add("squad.demo-step-ms", () -> "5");
        registry.add("squad.allowed-origins", () -> "http://localhost:4200");
        registry.add("squad.ready-file", () -> dataDir.resolve("pronto.json").toString());
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void acessoExigeTokenHostLocalEOrigemConhecida() throws Exception {
        assertThat(send("GET", "/api/health", null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/health", null, Map.of("Authorization", "Bearer outro")).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/health", null, auth()).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/health", null, Map.of("Authorization", "Bearer " + TOKEN, "Origin", "https://site-malicioso.example")).statusCode())
                .isEqualTo(403);
        assertThat(send("GET", "/api/health", null, Map.of("Authorization", "Bearer " + TOKEN, "Origin", "http://localhost:4200")).statusCode())
                .isEqualTo(200);
        // com cookie, métodos que alteram dados exigem o cabeçalho do aplicativo
        assertThat(send("POST", "/api/groups", "{\"name\":\"g\"}", Map.of("Cookie", "squad_token=" + TOKEN, "Content-Type", "application/json")).statusCode())
                .isEqualTo(403);
        assertThat(send("POST", "/api/groups", "{\"name\":\"g\"}", Map.of("Cookie", "squad_token=" + TOKEN, "Content-Type", "application/json",
                "X-Squad-Client", "1")).statusCode()).isEqualTo(201);
        assertThat(LocalAccessFilter.loopbackHost("evil.example:80")).isFalse();
        assertThat(LocalAccessFilter.loopbackHost("127.0.0.1:" + port)).isTrue();
    }

    @Test
    void arquivoDeProntidaoAnunciaPortaSemOToken() throws Exception {
        String ready = Files.readString(dataDir.resolve("pronto.json"));
        assertThat(JsonValues.intValue(TestJson.CODEC.parse(ready), "port")).isEqualTo(port);
        assertThat(ready).doesNotContain(TOKEN);
    }

    @Test
    void fluxoCompletoEmModoDemonstracaoComEventosAoVivo() throws Exception {
        json("PUT", "/api/settings", "{\"maxConcurrentRuns\":2,\"runTimeoutMinutes\":5,\"demoMode\":true}", 200);
        Object starter = json("POST", "/api/system/starter-agents", null, 200);
        String commanderId = JsonValues.str(starter, "squad", "commanderId");
        Path folder = Files.createDirectories(dataDir.resolve("projetos").resolve("Loja Ágil"));
        Object project = json("POST", "/api/projects", TestJson.CODEC.write(Map.of("name", "Loja Ágil", "folderPath", folder.toString())), 201);
        String projectId = JsonValues.str(project, "project", "id");
        assertThat(JsonValues.bool(project, "folderExists")).isTrue();
        Object session = json("POST", "/api/sessions", TestJson.CODEC.write(Map.of("projectId", projectId, "agentId", commanderId)), 201);
        String sessionId = JsonValues.str(session, "id");

        List<String> received = new CopyOnWriteArrayList<>();
        HttpRequest sse = HttpRequest.newBuilder(uri("/api/events")).header("Authorization", "Bearer " + TOKEN).GET().build();
        CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> stream = http.sendAsync(sse, HttpResponse.BodyHandlers.ofLines());
        CompletableFuture<Void> reader = stream.thenAccept(response -> response.body().forEach(line -> {
            received.add(line);
            if (line.contains("\"type\":\"run.finished\"")) {
                throw new StopReading();
            }
        }));
        Thread.sleep(300);

        Object submission = json("POST", "/api/sessions/" + sessionId + "/messages",
                "{\"content\":\"Qual é o plano?\",\"mode\":\"CHAT\"}", 202);
        String runId = JsonValues.str(submission, "run", "id");
        try {
            reader.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(StopReading.class);
        }
        assertThat(received).anyMatch(l -> l.startsWith("data:") && l.contains("\"type\":\"run.started\""));
        assertThat(received).anyMatch(l -> l.startsWith("data:") && l.contains("\"type\":\"text.delta\""));
        assertThat(received).anyMatch(l -> l.startsWith("id:"));

        Object run = json("GET", "/api/runs/" + runId, null, 200);
        assertThat(JsonValues.str(run, "status")).isEqualTo("COMPLETED");
        assertThat(JsonValues.bool(run, "demo")).isTrue();
        Object messages = json("GET", "/api/sessions/" + sessionId + "/messages", null, 200);
        assertThat(JsonValues.list(Map.of("v", messages), "v")).hasSize(2);

        // reconexão com Last-Event-ID: o histórico gravado é reenviado antes dos eventos novos
        List<String> replayed = new CopyOnWriteArrayList<>();
        HttpRequest replayRequest = HttpRequest.newBuilder(uri("/api/events")).header("Authorization", "Bearer " + TOKEN)
                .header("Last-Event-ID", "0").GET().build();
        CompletableFuture<Void> replay = http.sendAsync(replayRequest, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> response.body().forEach(line -> {
                    replayed.add(line);
                    if (line.contains("\"type\":\"run.finished\"")) {
                        throw new StopReading();
                    }
                }));
        try {
            replay.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(StopReading.class);
        }
        assertThat(replayed).anyMatch(l -> l.contains("\"type\":\"run.queued\""));
        assertThat(replayed).as("trechos de texto foram compactados após o fim").noneMatch(l -> l.contains("\"type\":\"text.delta\""));
    }

    @Test
    void estadoDaInterfaceEExportacaoDeAgente() throws Exception {
        assertThat(send("PUT", "/api/ui-state/workspace.layout", "{\"mode\":\"grid\",\"panels\":[1,2]}", jsonAuth()).statusCode()).isEqualTo(204);
        HttpResponse<String> saved = send("GET", "/api/ui-state/workspace.layout", null, auth());
        assertThat(saved.body()).contains("\"grid\"");
        assertThat(send("PUT", "/api/ui-state/workspace.layout", "isto não é json", jsonAuth()).statusCode()).isEqualTo(400);
        assertThat(send("GET", "/api/ui-state/nunca-gravado", null, auth()).statusCode()).isEqualTo(204);

        Object agent = json("POST", "/api/agents", "{\"templateKey\":\"operator-angular\"}", 201);
        HttpResponse<String> md = send("GET", "/api/agents/" + JsonValues.str(agent, "id") + "/export", null, auth());
        assertThat(md.statusCode()).isEqualTo(200);
        assertThat(md.headers().firstValue("Content-Disposition").orElse("")).contains("operador-angular.md");
        assertThat(md.body()).startsWith("---");
    }

    @Test
    void errosTemCodigoEstavel() throws Exception {
        HttpResponse<String> missing = send("GET", "/api/projects/nao-existe", null, auth());
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("\"code\":\"not_found\"");
        HttpResponse<String> invalid = send("POST", "/api/projects", "{\"name\":\"\"}", jsonAuth());
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("\"code\":\"required\"");
        HttpResponse<String> broken = send("POST", "/api/projects", "{", jsonAuth());
        assertThat(broken.statusCode()).isEqualTo(400);
    }

    @Test
    void diagnosticoDoClaudeNaoQuebraSemInstalacao() throws Exception {
        json("PUT", "/api/settings", TestJson.CODEC.write(Map.of("claudePath", dataDir.resolve("nao-existe").toString(),
                "maxConcurrentRuns", 2, "runTimeoutMinutes", 5, "demoMode", true)), 200);
        Object status = json("GET", "/api/system/claude", null, 200);
        assertThat(JsonValues.bool(status, "usable")).isFalse();
        assertThat(JsonValues.str(status, "problem")).isNotBlank();
        Object info = json("GET", "/api/system/info", null, 200);
        assertThat(JsonValues.str(info, "dataDir")).contains("dados");
    }

    static final class StopReading extends RuntimeException {
    }

    private Object json(String method, String path, String body, int expectedStatus) throws Exception {
        HttpResponse<String> response = send(method, path, body, body == null ? auth() : jsonAuth());
        assertThat(response.statusCode()).as(method + " " + path + " -> " + response.body()).isEqualTo(expectedStatus);
        return response.body().isEmpty() ? null : TestJson.CODEC.parse(response.body());
    }

    private HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static Map<String, String> auth() {
        return Map.of("Authorization", "Bearer " + TOKEN);
    }

    private static Map<String, String> jsonAuth() {
        return Map.of("Authorization", "Bearer " + TOKEN, "Content-Type", "application/json");
    }
}
