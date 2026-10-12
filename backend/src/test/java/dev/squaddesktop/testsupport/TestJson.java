package dev.squaddesktop.testsupport;

import dev.squaddesktop.config.JacksonJsonCodec;
import dev.squaddesktop.shared.JsonCodec;
import tools.jackson.databind.json.JsonMapper;

public final class TestJson {

    public static final JsonCodec CODEC = new JacksonJsonCodec(JsonMapper.builder().build());

    private TestJson() {
    }
}
