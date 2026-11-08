package com.ballknowers.draftsim.historical;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the {@link HistoricalSport} for a sport code (spec 056 T009). An unknown or missing code is an
 * {@link IllegalArgumentException}, which the API answers with 400: there is no default sport, because a defaulted
 * sport asserts a rule (memory: optional-params-that-encode-rules).
 */
@Component
public class HistoricalSports {

    private final Map<String, HistoricalSport> byCode = new LinkedHashMap<>();

    public HistoricalSports(List<HistoricalSport> sports) {
        for (HistoricalSport s : sports) {
            if (byCode.put(s.code(), s) != null) {
                throw new IllegalStateException("two HistoricalSport beans for " + s.code());
            }
        }
    }

    public HistoricalSport get(String code) {
        HistoricalSport s = code == null ? null : byCode.get(code);
        if (s == null) {
            throw new IllegalArgumentException("sport must be one of " + byCode.keySet() + ", got " + code);
        }
        return s;
    }
}
