package dev.squaddesktop.web;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.squaddesktop.runs.EventBus;
import dev.squaddesktop.runs.EventStore;
import dev.squaddesktop.runs.StoredEvent;
import jakarta.annotation.PreDestroy;

/**
 * Transmissão de eventos de execução por SSE.
 *
 * <p>Ao conectar (ou reconectar com {@code Last-Event-ID}), o cliente recebe os eventos gravados depois do
 * cursor e em seguida os novos, sem lacunas: a assinatura é feita antes da leitura do histórico e o que chega
 * nesse intervalo é descartado se já foi enviado. Cada conexão tem sua fila e sua thread de envio, para um
 * cliente lento não atrasar as execuções.
 */
@RestController
@RequestMapping("/api/events")
public class EventStreamController {

    private static final int REPLAY_LIMIT = 2_000;
    private static final StoredEvent HEARTBEAT = new StoredEvent(-1, null, null, "heartbeat", "{}", null);

    private final EventBus bus;
    private final EventStore store;
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("sse-heartbeat").factory());

    public EventStreamController(EventBus bus, EventStore store) {
        this.bus = bus;
        this.store = store;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(value = "after", required = false) Long after) {
        long cursor = parse(lastEventId, after == null ? -1 : after);
        SseEmitter emitter = new SseEmitter(0L);
        BlockingQueue<StoredEvent> queue = new LinkedBlockingQueue<>(10_000);
        EventBus.Subscription subscription = bus.subscribe(event -> {
            if (!queue.offer(event)) {
                emitter.completeWithError(new IOException("cliente lento demais; reconecte para retomar"));
            }
        });
        var beat = heartbeats.scheduleAtFixedRate(() -> queue.offer(HEARTBEAT), 15, 15, TimeUnit.SECONDS);
        Thread sender = Thread.ofVirtual().name("sse-sender").start(() -> {
            long sent = cursor < 0 ? store.lastId() : cursor;
            try {
                emitter.send(SseEmitter.event().name("hello").data("{\"cursor\":" + sent + "}", MediaType.APPLICATION_JSON));
                if (cursor >= 0) {
                    List<StoredEvent> missed = store.after(cursor, REPLAY_LIMIT);
                    for (StoredEvent event : missed) {
                        send(emitter, event);
                        sent = event.id();
                    }
                }
                while (!Thread.currentThread().isInterrupted()) {
                    StoredEvent event = queue.take();
                    if (event == HEARTBEAT) {
                        emitter.send(SseEmitter.event().comment("ping"));
                        continue;
                    }
                    if (event.id() > 0 && event.id() <= sent) {
                        continue;
                    }
                    send(emitter, event);
                    if (event.id() > 0) {
                        sent = event.id();
                    }
                }
            } catch (IOException | IllegalStateException e) {
                // o cliente fechou a conexão (janela recarregada, app encerrando): só encerra este envio
                emitter.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                subscription.close();
                beat.cancel(false);
            }
        });
        Runnable cleanup = () -> {
            subscription.close();
            beat.cancel(false);
            sender.interrupt();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        return emitter;
    }

    private static void send(SseEmitter emitter, StoredEvent event) throws IOException {
        String envelope = "{\"id\":" + event.id() + ",\"runId\":" + quote(event.runId()) + ",\"sessionId\":" + quote(event.sessionId())
                + ",\"type\":" + quote(event.type()) + ",\"at\":" + quote(event.createdAt()) + ",\"data\":" + event.payload() + "}";
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name("run").data(envelope, MediaType.APPLICATION_JSON);
        if (event.id() > 0) {
            builder.id(Long.toString(event.id()));
        }
        emitter.send(builder);
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static long parse(String header, long fallback) {
        if (header == null || header.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(header.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @PreDestroy
    void stop() {
        heartbeats.shutdownNow();
    }
}
