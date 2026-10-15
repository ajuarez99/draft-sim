package com.ballknowers.draftsim.ingest.espn;

/**
 * Every failure the ESPN ingest can raise, as a fixed {@link #code()}. Spec 035 plan F14: these
 * carry codes and field names only. Never a request, a header, a cookie or a response body, so
 * nothing that catches one can leak a credential by printing it.
 */
public abstract sealed class EspnException extends RuntimeException
        permits EspnAccessDenied, EspnNotFound, EspnHttpError, EspnShapeChanged, EspnUnsupported {

    private final String code;

    protected EspnException(String code, String message) {
        // No cause, and writableStackTrace stays on: a stack trace holds no request data.
        super(message, null);
        this.code = code;
    }

    /** A stable machine-readable code, safe to store in {@code league_refresh.last_failure}. */
    public String code() {
        return code;
    }
}
