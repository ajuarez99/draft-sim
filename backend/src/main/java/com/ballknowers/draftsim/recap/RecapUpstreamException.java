package com.ballknowers.draftsim.recap;

/** The model API failed (not a bad answer). The message never contains the key or any header. */
public class RecapUpstreamException extends RuntimeException {

    public enum Kind { RATE_LIMITED_UPSTREAM, API_ERROR }

    private final Kind kind;

    public RecapUpstreamException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
