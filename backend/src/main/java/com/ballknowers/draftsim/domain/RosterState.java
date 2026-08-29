package com.ballknowers.draftsim.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable per-seat state during a single simulation run. One instance per seat
 * per run; never shared across runs.
 */
public final class RosterState {

    private final List<BoardEntry> picks = new ArrayList<>();
    private final Map<Position, List<BoardEntry>> byPosition = new EnumMap<>(Position.class);

    public void add(BoardEntry e) {
        picks.add(e);
        byPosition.computeIfAbsent(e.position(), k -> new ArrayList<>()).add(e);
    }

    /** Players at a position, best board value first. */
    public List<BoardEntry> at(Position pos) {
        List<BoardEntry> l = byPosition.get(pos);
        if (l == null) return List.of();
        List<BoardEntry> copy = new ArrayList<>(l);
        copy.sort((a, b) -> Double.compare(a.adp(), b.adp()));
        return copy;
    }

    public int count(Position pos) {
        List<BoardEntry> l = byPosition.get(pos);
        return l == null ? 0 : l.size();
    }

    public List<BoardEntry> picks() {
        return List.copyOf(picks);
    }

    public int size() {
        return picks.size();
    }

    public RosterState copy() {
        RosterState c = new RosterState();
        for (BoardEntry e : picks) c.add(e);
        return c;
    }
}
