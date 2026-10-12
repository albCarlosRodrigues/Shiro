package dev.squaddesktop.config;

import dev.squaddesktop.shared.JsonCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** {@link JsonCodec} sobre o Jackson 3 configurado pelo Spring Boot. */
public final class JacksonJsonCodec implements JsonCodec {

    private final JsonMapper mapper;

    public JacksonJsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON ausente");
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("JSON inválido: " + e.getOriginalMessage(), e);
        }
    }

    @Override
    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Falha ao gerar JSON", e);
        }
    }
}
