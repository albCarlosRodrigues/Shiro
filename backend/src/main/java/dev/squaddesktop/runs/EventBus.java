package dev.squaddesktop.runs;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Distribui os eventos aos assinantes conectados (as transmissões SSE). Um assinante com erro não afeta os demais. */
public class EventBus {

    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private final CopyOnWriteArrayList<Consumer<StoredEvent>> subscribers = new CopyOnWriteArrayList<>();

    public Subscription subscribe(Consumer<StoredEvent> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    public void publish(StoredEvent event) {
        for (Consumer<StoredEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException ignored) {
                // a transmissão desse assinante falhou; ele mesmo se remove ao detectar a queda
            }
        }
    }

    public int subscriberCount() {
        return subscribers.size();
    }
}
