package com.ballknowers.draftsim.historical;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an import run did, in the shape contracts/api.md's amendment box promises:
 * {@code {seasons, failedSeasons, unmatchedFantasyRelevant, uncoveredCollisions, emptyPositions, ...}}.
 *
 * <p>Single writer (the import thread). {@link #snapshot()} copies into plain maps, so a progress read never
 * sees a half-built entry. Every map is mutable and nullable-safe on purpose: nothing here is built with
 * {@code Map.of}, because a null value (a player with no team, say) must not throw in a JSON path.
 */
public final class ImportReport {

    private final String part;
    private final List<Map<String, Object>> seasons = new ArrayList<>();
    private final List<Map<String, Object>> failedSeasons = new ArrayList<>();
    private final List<Map<String, Object>> unmatchedFantasyRelevant = new ArrayList<>();
    private final List<Map<String, Object>> uncoveredCollisions = new ArrayList<>();
    private final List<Map<String, Object>> emptyPositions = new ArrayList<>();
    private final Map<String, Object> notes = new LinkedHashMap<>();
    private final Map<String, Integer> teamCodeMismatches = new LinkedHashMap<>();
    private String error;

    public ImportReport(String part) {
        this.part = part;
    }

    public synchronized void season(Map<String, Object> entry) {
        seasons.add(entry);
    }

    public synchronized void failed(int seasonStart, String source, List<String> reasons) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seasonStart", seasonStart);
        m.put("source", source);
        m.put("reasons", reasons);
        failedSeasons.add(m);
    }

    public synchronized void unmatched(Map<String, Object> entry) {
        unmatchedFantasyRelevant.add(entry);
    }

    public synchronized void collision(Map<String, Object> entry) {
        uncoveredCollisions.add(entry);
    }

    public synchronized void emptyPosition(Map<String, Object> entry) {
        emptyPositions.add(entry);
    }

    public synchronized void note(String key, Object value) {
        notes.put(key, value);
    }

    public synchronized void teamMismatch(String boxCode, String seasonTeams, int rows) {
        teamCodeMismatches.merge(boxCode + " -> " + seasonTeams, rows, Integer::sum);
    }

    public synchronized void error(String message) {
        this.error = message;
    }

    public synchronized boolean hasFailures() {
        return !failedSeasons.isEmpty() || error != null;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("part", part);
        m.put("seasons", new ArrayList<>(seasons));
        m.put("failedSeasons", new ArrayList<>(failedSeasons));
        m.put("unmatchedFantasyRelevant", new ArrayList<>(unmatchedFantasyRelevant));
        m.put("uncoveredCollisions", new ArrayList<>(uncoveredCollisions));
        m.put("emptyPositions", new ArrayList<>(emptyPositions));
        m.put("teamCodeMismatches", new LinkedHashMap<>(teamCodeMismatches));
        m.put("notes", new LinkedHashMap<>(notes));
        m.put("error", error);
        return m;
    }
}
