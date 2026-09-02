package com.ballknowers.draftsim.sport;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * Football need model.
 *
 * Starting slots are rigid and positional value drops sharply, so need is the
 * marginal improvement to expected starting-lineup value from adding a player,
 * expressed as a fraction of that player's own value.
 *
 * Value is derived from board position, not from projected points: we have no
 * projection source wired up. That is a real approximation — the board encodes
 * the market's cross-positional view, which is close to but not the same as
 * expected fantasy points. Swap {@link #value} for a projection lookup when one
 * exists and nothing else here has to change.
 */
@Component
public class FootballRules implements SportRules {

    private static final int POSITIONS = Position.values().length;

    private final ScoringProperties.Sport cfg;

    // value() is a pure function of (adp, valueDecay) -- valueDecay is fixed
    // for the process lifetime, so memoizing by adp removes every Math.exp()
    // call after the first time a given adp is seen. This is the fallback
    // used by callers that go through the value()/startingLineupValue()
    // surface directly (tests, anything outside the hot simulation path); the
    // hot path (DraftSimulator/PickScorer) instead uses DraftContext's own
    // identity-keyed cache passed in as `valueOf`, which avoids this map's
    // Double-boxing-as-a-key cost entirely (§B2b).
    private final Map<Double, Double> valueCache = new ConcurrentHashMap<>();

    public FootballRules(ScoringProperties props) {
        this.cfg = props.football();
    }

    @Override
    public Sport sport() {
        return Sport.NFL;
    }

    @Override
    public double value(BoardEntry entry) {
        return valueCache.computeIfAbsent(entry.adp(), adp -> Math.exp(-adp / cfg.valueDecay()));
    }

    /**
     * Everything {@link #rosterNeed} needs to score any candidate against
     * {@code roster} in O(1): the roster's current total, and, per position,
     * whether a new player would (a) simply fill an empty starter slot, (b)
     * have to beat the current weakest starter to take his place, sending him
     * to compete for FLEX, or (c) go straight to FLEX contention itself --
     * plus the FLEX pool's own current state, since a displaced starter and a
     * new candidate both compete for it the same way.
     *
     * Adding one player can only ever displace, at most, the single weakest
     * dedicated starter at his position (into FLEX contention) and/or the
     * single weakest FLEX starter overall -- never more, since only one
     * player is being added. That is what makes this O(1) rather than a
     * re-walk of the whole roster per candidate.
     */
    private record Lineup(
            double total,
            double[] weakestStarterByPos,   // value of have(pos)[slots-1], NaN if not applicable
            int[] slotsByPos,
            int[] countByPos,
            boolean flexFull,
            double weakestFlexValue,        // meaningful only if flexFull
            ToDoubleFunction<BoardEntry> valueOf
    ) {}

    @Override
    public Object prepareLineup(RosterState roster, LeagueSettings settings, ToDoubleFunction<BoardEntry> valueOf) {
        double total = 0;
        List<BoardEntry> flexPool = new ArrayList<>();
        double[] weakestStarter = new double[POSITIONS];
        Arrays.fill(weakestStarter, Double.NaN);
        int[] slotsByPos = new int[POSITIONS];
        int[] countByPos = new int[POSITIONS];

        for (Map.Entry<Position, Integer> e : settings.dedicatedStarters().entrySet()) {
            Position pos = e.getKey();
            int slots = e.getValue();
            List<BoardEntry> have = roster.at(pos);
            slotsByPos[pos.ordinal()] = slots;
            countByPos[pos.ordinal()] = have.size();
            for (int i = 0; i < have.size(); i++) {
                if (i < slots) {
                    double v = valueOf.applyAsDouble(have.get(i));
                    total += v;
                    if (i == slots - 1) weakestStarter[pos.ordinal()] = v;
                } else if (pos.isFlexEligible()) {
                    flexPool.add(have.get(i));
                }
            }
        }

        flexPool.sort((a, b) -> Double.compare(valueOf.applyAsDouble(b), valueOf.applyAsDouble(a)));
        int flexSlots = settings.flexSlots();
        for (int i = 0; i < Math.min(flexSlots, flexPool.size()); i++) {
            total += valueOf.applyAsDouble(flexPool.get(i));
        }

        boolean flexFull = flexPool.size() >= flexSlots;
        double weakestFlex = (flexFull && flexSlots > 0)
                ? valueOf.applyAsDouble(flexPool.get(flexSlots - 1))
                : Double.NaN;

        return new Lineup(total, weakestStarter, slotsByPos, countByPos, flexFull, weakestFlex, valueOf);
    }

    @Override
    public double lineupValue(Object lineup) {
        return ((Lineup) lineup).total();
    }

    @Override
    public double rosterNeed(BoardEntry candidate, Object lineupObj) {
        Lineup lin = (Lineup) lineupObj;
        double own = lin.valueOf().applyAsDouble(candidate);
        if (own <= 0) return cfg.benchFloor();

        Position pos = candidate.position();
        int ord = pos.ordinal();
        int slots = lin.slotsByPos()[ord];
        int n = lin.countByPos()[ord];

        double delta;
        if (n < slots) {
            // An empty dedicated slot at this position: this player fills it
            // outright, whatever his own value -- the rest of the position's
            // starters (if any) are unaffected either way.
            delta = own;
        } else {
            double starterDelta;
            double overflowCandidate;   // whichever of {this player, the starter he'd bump} competes for FLEX
            if (slots >= 1 && own > lin.weakestStarterByPos()[ord]) {
                double displaced = lin.weakestStarterByPos()[ord];
                starterDelta = own - displaced;
                overflowCandidate = displaced;
            } else {
                starterDelta = 0;
                overflowCandidate = own;
            }

            double flexDelta;
            if (pos.isFlexEligible()) {
                if (!lin.flexFull()) {
                    flexDelta = overflowCandidate;
                } else if (overflowCandidate > lin.weakestFlexValue()) {
                    flexDelta = overflowCandidate - lin.weakestFlexValue();
                } else {
                    flexDelta = 0;
                }
            } else {
                flexDelta = 0;
            }
            delta = starterDelta + flexDelta;
        }

        double captured = Math.max(0.0, Math.min(1.0, delta / own));
        // Depth is not worthless: byes, injuries, and upside all make a bench
        // player worth something. benchFloor keeps late-round picks from
        // scoring at exactly zero need.
        return cfg.benchFloor() + (1.0 - cfg.benchFloor()) * captured;
    }

    /**
     * Greedy assignment: fill dedicated slots with the best player at each
     * position, then fill FLEX from whatever RB/WR/TE are left. Greedy is
     * optimal here because FLEX accepts a superset of the dedicated slots it
     * competes with, so no dedicated slot ever wants a player FLEX took.
     *
     * Not on the simulation hot path (see {@link #prepareLineup}); kept for
     * direct callers and tests that just want "what is this roster worth."
     */
    @Override
    public double startingLineupValue(RosterState roster, LeagueSettings settings) {
        double total = 0;
        List<BoardEntry> flexPool = new ArrayList<>();

        for (Map.Entry<Position, Integer> e : settings.dedicatedStarters().entrySet()) {
            Position pos = e.getKey();
            int slots = e.getValue();
            List<BoardEntry> have = roster.at(pos);
            for (int i = 0; i < have.size(); i++) {
                if (i < slots) {
                    total += value(have.get(i));
                } else if (pos.isFlexEligible()) {
                    flexPool.add(have.get(i));
                }
            }
        }

        flexPool.sort((a, b) -> Double.compare(value(b), value(a)));
        int flex = settings.flexSlots();
        for (int i = 0; i < Math.min(flex, flexPool.size()); i++) {
            total += value(flexPool.get(i));
        }
        return total;
    }

    @Override
    public boolean isDraftable(BoardEntry entry, int round) {
        Integer min = cfg.earliestRound().get(entry.position().name());
        return min == null || round >= min;
    }

    @Override
    public boolean isEligible(Player player, String rosterSlot) {
        if ("BN".equals(rosterSlot)) return true;
        if ("FLEX".equals(rosterSlot)) {
            return player.positions().stream().anyMatch(Position::isFlexEligible);
        }
        return player.positions().stream().anyMatch(p -> p.name().equals(rosterSlot));
    }
}
