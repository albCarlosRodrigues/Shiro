package dev.squaddesktop.runs;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.squaddesktop.shared.JsonCodec;

/**
 * Avisos transitórios de que algo do projeto mudou (features, planos, sala de planejamento, propostas).
 * A interface recarrega a parte indicada; nada é gravado, e ao reconectar ela recarrega tudo.
 */
public class ProjectSignals {

    public static final String TYPE = "project.changed";

    private final EventBus bus;
    private final Clock clock;
    private final JsonCodec json;

    public ProjectSignals(EventBus bus, Clock clock, JsonCodec json) {
        this.bus = bus;
        this.clock = clock;
        this.json = json;
    }

    /** @param scope features, plans, planning ou proposals */
    public void changed(String projectId, String scope, String id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("projectId", projectId);
        data.put("scope", scope);
        if (id != null) {
            data.put("id", id);
        }
        bus.publish(new StoredEvent(0, null, null, TYPE, json.write(data), clock.instant().toString()));
    }
}
