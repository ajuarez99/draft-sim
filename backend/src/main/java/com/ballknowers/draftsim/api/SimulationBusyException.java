package com.ballknowers.draftsim.api;

/** Every simulation permit is in use. Mapped to 429 + Retry-After by {@link ErrorHandler}. */
public class SimulationBusyException extends RuntimeException {
    public static final int RETRY_AFTER_SECONDS = 2;

    public SimulationBusyException() {
        super("The simulator is busy with other runs right now. Try again in a couple of seconds.");
    }
}
