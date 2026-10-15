package com.ballknowers.draftsim.ingest.espn;

/** Any other non-200 from ESPN, or a transport failure (status 0). The code is {@code ESPN_HTTP_<status>}. */
public final class EspnHttpError extends EspnException {

    private final int status;

    public EspnHttpError(int status) {
        super("ESPN_HTTP_" + status, "ESPN_HTTP_" + status);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
