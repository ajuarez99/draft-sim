package com.ballknowers.draftsim.ingest.espn;

/** ESPN answered 404: no such league (or season) for that game. */
public final class EspnNotFound extends EspnException {

    public static final String CODE = "ESPN_NOT_FOUND";

    public EspnNotFound() {
        super(CODE, CODE);
    }
}
