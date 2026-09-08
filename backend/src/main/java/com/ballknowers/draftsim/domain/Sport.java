package com.ballknowers.draftsim.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Sport {
    NFL("nfl"),
    NBA("nba");   // seam only; not implemented in v1

    private final String code;
    Sport(String code) { this.code = code; }

    /**
     * Also the JSON wire form ({@code @JsonValue}), so that a {@code DraftSummary}
     * or {@code LeagueRow} serialized with Jackson matches the lowercase code the
     * {@code ?sport=} query parameter takes and the {@code league.sport} column
     * stores, instead of drifting to the enum constant's {@code name()}.
     */
    @JsonValue
    public String code() { return code; }

    /** Inverse of {@link #code()}, for reading the {@code sport} column back off a row. */
    public static Sport fromCode(String code) {
        for (Sport s : values()) {
            if (s.code.equals(code)) return s;
        }
        throw new IllegalArgumentException("unknown sport code: " + code);
    }

    /**
     * Jackson's deserialization entry point -- delegates to {@link #fromCode} so a
     * malformed request body fails with the same {@code IllegalArgumentException}
     * (and thus the same 400, not a 500) as an unknown {@code ?sport=} query param.
     */
    @JsonCreator
    public static Sport fromJson(String code) {
        return fromCode(code);
    }
}
