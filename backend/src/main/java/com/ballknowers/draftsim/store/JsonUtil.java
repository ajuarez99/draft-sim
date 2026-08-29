package com.ballknowers.draftsim.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public final class JsonUtil {

    private static final ObjectMapper M = new ObjectMapper();

    private JsonUtil() {}

    public static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("json write failed", e);
        }
    }

    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return M.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("json read failed: " + json, e);
        }
    }
}
