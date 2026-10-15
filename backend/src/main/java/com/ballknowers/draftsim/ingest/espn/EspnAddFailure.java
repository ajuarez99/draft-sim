package com.ballknowers.draftsim.ingest.espn;

/**
 * A refusal the ESPN add/claim flow raises itself (as opposed to {@link EspnException}, which is what
 * ESPN or its payload did). Carries the contract's error {@code code} and HTTP status; the message is
 * the code, never anything from the request.
 */
public class EspnAddFailure extends RuntimeException {

    private final String code;
    private final int status;

    public EspnAddFailure(String code, int status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
