package com.ballknowers.draftsim.engine;

/** A simulation was stopped part-way: its client went away, or a newer run replaced it. */
public class SimulationCancelledException extends RuntimeException {
    public SimulationCancelledException() {
        super("This simulation was replaced by a newer one, or its client disconnected.");
    }
}
