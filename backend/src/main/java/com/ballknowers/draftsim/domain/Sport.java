package com.ballknowers.draftsim.domain;

public enum Sport {
    NFL("nfl"),
    NBA("nba");   // seam only; not implemented in v1

    private final String code;
    Sport(String code) { this.code = code; }
    public String code() { return code; }

    /** Inverse of {@link #code()}, for reading the {@code sport} column back off a row. */
    public static Sport fromCode(String code) {
        for (Sport s : values()) {
            if (s.code.equals(code)) return s;
        }
        throw new IllegalArgumentException("unknown sport code: " + code);
    }
}
