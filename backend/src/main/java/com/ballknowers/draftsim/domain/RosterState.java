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
    // Kept sorted by adp ascending (best board value first) on insert, since
    // each list holds at most ~15 entries: an insertion-sort add() is cheaper
    // than the sort-on-every-read this replaced, and at() becomes a direct
    // return instead of a copy+sort on every one of the ~25M calls a full run
    // makes into it.
    private final Map<Position, List<BoardEntry>> byPosition = new EnumMap<>(Position.class);

    public void add(BoardEntry e) {
        picks.add(e);
        List<BoardEntry> l = byPosition.computeIfAbsent(e.position(), k -> new ArrayList<>());
        int i = l.size();
        while (i > 0 && l.get(i - 1).adp() > e.adp()) i--;
        l.add(i, e);
    }

    /**
     * Players at a position, best board value first. The returned list is the
     * live backing list, not a copy -- callers must not mutate it.
     */
    public List<BoardEntry> at(Position pos) {
        List<BoardEntry> l = byPosition.get(pos);
        return l == null ? List.of() : l;
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
