package com.ballknowers.draftsim.domain;

public enum Sport {
    NFL("nfl"),
    NBA("nba");   // seam only; not implemented in v1

    private final String code;
    Sport(String code) { this.code = code; }
    public String code() { return code; }
}
