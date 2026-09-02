package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.sport.SportRules;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a simulation run needs, assembled once and shared read-only across
 * all iterations. Nothing in here is mutated during a run.
 *
 * A plain class rather than a record so it can carry derived, precomputed state
 * ({@link #byId()}, {@link #completedAt}) alongside the inputs it was built
 * from -- computed once here rather than once per iteration
 * (§B3, board-first-layout-and-pick-latency.md). The public constructor keeps
 * the same parameter list a record would have had, so existing call sites and
 * tests that build one positionally are unaffected.
 */
public final class DraftContext {

    private final List<BoardEntry> board;                  // sorted by board position
    private final LeagueSettings settings;
    private final Map<Integer, ManagerProfile> profileBySlot;
    private final PositionalPriors priors;
    private final SportRules rules;
    private final ScoringProperties.Sport cfg;
    private final List<Integer> completedPickNumbers;      // picks already made (resume mode)
    private final Map<Integer, Long> completedPicks;        // pickNo -> player id

    // Derived once at construction, shared read-only by every DraftSimulator
    // iteration and by MonteCarloRunner.aggregate() -- previously rebuilt
    // from scratch in both places (DraftSimulator.java:47-48, once per
    // iteration; MonteCarloRunner.java:84-85, once more on top of that).
    private final Map<Long, BoardEntry> byId;
    private final long[] completedByPick;                   // index = pickNo, 0 = not completed
    // Identity-keyed (IdentityHashMap: reference equality, not BoardEntry's
    // record-generated content equals()/hashCode()) -- every BoardEntry the
    // simulation ever touches is one of these same ~600 instances, so a
    // lookup here costs one identity hash and no Double-boxing-as-a-key,
    // unlike a plain HashMap<Double,Double> keyed by adp (§B2b). Built once,
    // read-only after construction: safe for unsynchronized concurrent reads
    // across every iteration's virtual thread.
    private final Map<BoardEntry, Double> boardValue;

    public DraftContext(
            List<BoardEntry> board,
            LeagueSettings settings,
            Map<Integer, ManagerProfile> profileBySlot,
            PositionalPriors priors,
            SportRules rules,
            ScoringProperties.Sport cfg,
            List<Integer> completedPickNumbers,
            Map<Integer, Long> completedPicks
    ) {
        this.board = board;
        this.settings = settings;
        this.profileBySlot = profileBySlot;
        this.priors = priors;
        this.rules = rules;
        this.cfg = cfg;
        this.completedPickNumbers = completedPickNumbers;
        this.completedPicks = completedPicks;

        Map<Long, BoardEntry> byIdBuild = new HashMap<>(board.size() * 2);
        for (BoardEntry e : board) byIdBuild.put(e.player().id(), e);
        this.byId = byIdBuild;

        // rules.value() is a pure function of the entry alone (see
        // FootballRules.value()'s doc) -- delegate to it rather than
        // duplicating its formula here, so this stays correct if a future
        // sport's value() depends on more than adp.
        Map<BoardEntry, Double> boardValueBuild = new IdentityHashMap<>(board.size() * 2);
        for (BoardEntry e : board) boardValueBuild.put(e, rules.value(e));
        this.boardValue = boardValueBuild;

        int total = settings.teams() * settings.rounds();
        long[] arr = new long[total + 1];
        completedPicks.forEach((pickNo, playerId) -> {
            if (pickNo != null && pickNo >= 0 && pickNo <= total && playerId != null) {
                arr[pickNo] = playerId;
            }
        });
        this.completedByPick = arr;
    }

    public List<BoardEntry> board() { return board; }
    public LeagueSettings settings() { return settings; }
    public Map<Integer, ManagerProfile> profileBySlot() { return profileBySlot; }
    public PositionalPriors priors() { return priors; }
    public SportRules rules() { return rules; }
    public ScoringProperties.Sport cfg() { return cfg; }
    public List<Integer> completedPickNumbers() { return completedPickNumbers; }
    public Map<Integer, Long> completedPicks() { return completedPicks; }

    public int totalPicks() {
        return settings.teams() * settings.rounds();
    }

    public ManagerProfile profileFor(int slot) {
        return profileBySlot.getOrDefault(slot, ManagerProfile.neutral(-1, "seat " + slot));
    }

    /** {@code board}, indexed by player id. Built once; do not mutate. */
    public Map<Long, BoardEntry> byId() {
        return byId;
    }

    /** The player id completed at {@code pickNo}, or 0 if none is recorded. */
    public long completedAt(int pickNo) {
        return (pickNo >= 0 && pickNo < completedByPick.length) ? completedByPick[pickNo] : 0L;
    }

    /** {@link SportRules#value}, precomputed once per board entry at construction. */
    public double valueOf(BoardEntry e) {
        Double v = boardValue.get(e);
        return v != null ? v : rules.value(e);   // fallback: an entry outside this request's board
    }
}
