package io.popdesk.e2e.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class Json {

    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {}

    public static JsonNode parse(String text) {
        return MAPPER.readTree(text);
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }
}
