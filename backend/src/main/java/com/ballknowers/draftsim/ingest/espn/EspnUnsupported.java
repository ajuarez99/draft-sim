package com.ballknowers.draftsim.ingest.espn;

/**
 * An ESPN league the app refuses to model, e.g. category scoring ({@code UNSUPPORTED_SCORING}).
 * {@code detail} is a short value from the league's settings (the scoring type name), safe to show.
 */
public final class EspnUnsupported extends EspnException {

    public static final String UNSUPPORTED_SCORING = "UNSUPPORTED_SCORING";

    private final String detail;

    public EspnUnsupported(String code, String detail) {
        super(code, code + ": " + detail);
        this.detail = detail;
    }

    public String detail() {
        return detail;
    }
}
