package com.ballknowers.draftsim.ingest.espn;

/** ESPN answered 401: the league is private and the request carried no (or a rejected) credential. */
public final class EspnAccessDenied extends EspnException {

    public static final String CODE = "ESPN_ACCESS_DENIED";

    public EspnAccessDenied() {
        super(CODE, CODE);
    }
}
