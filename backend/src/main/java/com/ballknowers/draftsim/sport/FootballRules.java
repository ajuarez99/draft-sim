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

    // An array WIDTH, not a denominator or a display-facing iteration --
    // Position.ordinal() is global across both sports, so this stays sized to
    // the full Position.values().length (11) rather than football's own 6.
    // Sizing it to forSport(NFL).size() would need a dense per-sport index
    // instead of the ordinal directly, for five unused doubles saved; not
    // worth it. See claude/multi-sport-and-rebrand.md Phase 3a and
    // Position.forSport's javadoc.
    private static final int POSITIONS = Position.values().length;

    private final ScoringProperties.SportScoring cfg;

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
        // weights.yml lives outside the jar, so a copy predating the move from
        // earliestRound to latestRounds is a real thing to boot against. Fail
        // here, loudly, rather than NPE on the first pick of the first
        // simulation -- and never silently degrade to "no gate", which would
        // put kickers on the board at 1.01 and look like a model problem.
        if (cfg.latestRounds() == null) {
            throw new IllegalStateException(
                    "draftsim.scoring.football.latestRounds is missing from weights.yml. "
                            + "It replaced earliestRound: the value is now how many rounds from "
                            + "the END of the draft a position opens up, so the old "
                            + "{K: 13, DEF: 12} of a 15-round league becomes {K: 3, DEF: 4}.");
        }
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
            boolean benchFull,               // §3b's eligibility lock; see hasOpenSlot
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
        int startersFilled = 0;   // dedicated slots actually occupied; §3b's benchFull needs this

        for (Map.Entry<Position, Integer> e : settings.dedicatedStarters().entrySet()) {
            Position pos = e.getKey();
            int slots = e.getValue();
            List<BoardEntry> have = roster.at(pos);
            slotsByPos[pos.ordinal()] = slots;
            countByPos[pos.ordinal()] = have.size();
            startersFilled += Math.min(have.size(), slots);
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

        // Bench absorbs whatever dedicated + FLEX did not: everyone on the
        // roster who isn't one of the startersFilled/flexFilled players is
        // sitting there, whether or not their position is FLEX-eligible (a
        // second QB has nowhere else to go). Purely additive over the fields
        // above -- rosterNeed/lineupValue never read it, so this cannot move
        // anything football's simulation already produces.
        int flexFilled = Math.min(flexPool.size(), flexSlots);
        boolean benchFull = (roster.size() - startersFilled - flexFilled) >= settings.benchSlots();

        return new Lineup(total, weakestStarter, slotsByPos, countByPos, flexFull, weakestFlex, benchFull, valueOf);
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
    public boolean isDraftable(BoardEntry entry, Object lineup, int round, int totalRounds) {
        // §3b: this gate does not need roster shape -- FLEX/BN already accept
        // anyone, so the round-window gate is what keeps kickers and defenses
        // off an early roster, and no roster of this shape can ever exhaust
        // every slot a non-K/DEF player is eligible for before it exhausts
        // FLEX and BN first (see FootballRulesTest for the arithmetic, and
        // hasOpenSlot below for the check itself). `lineup` is intentionally
        // unread -- do not thread it in here without re-verifying the
        // bit-identical baseline this was checked against.
        Integer window = cfg.latestRounds().get(entry.position().name());
        if (window == null) return true;
        // rounds remaining, counting this one: round 13 of 15 has 3 left.
        return (totalRounds - round + 1) <= window;
    }

    @Override
    public boolean isEligible(Player player, String rosterSlot) {
        if ("BN".equals(rosterSlot)) return true;
        if ("FLEX".equals(rosterSlot)) {
            return player.positions().stream().anyMatch(Position::isFlexEligible);
        }
        return player.positions().stream().anyMatch(p -> p.name().equals(rosterSlot));
    }

    /**
     * Sleeper's {@code enforce_position_limits} lock
     * (claude/multi-sport-and-rebrand.md §3b): true iff some roster slot
     * {@code player} is eligible for, bench included, still has a vacancy in
     * {@code lineup}. A bitmask/count test, not a matching problem -- Sleeper
     * only asks "is there an open slot this fits", never "can a legal lineup
     * still be fielded afterward" (the stronger question {@link #rosterNeed}
     * answers, §3c).
     *
     * <p>Not called by {@link #isDraftable}: football's {@code latestRounds}
     * gate already keeps a simulated roster from ever reaching a state this
     * would reject, and wiring a previously-inactive gate into the real
     * decision path is exactly the kind of change that could move which
     * candidate gets picked -- which the bit-identical baseline this phase
     * must not disturb cannot absorb. Exposed and tested on its own so
     * basketball's FLEX/UTIL tiers -- where that argument stops holding --
     * have it ready in Phase 4 instead of needing to invent it under
     * deadline.
     *
     * @param lineup this seat's {@link #prepareLineup} result.
     */
    public boolean hasOpenSlot(Player player, Object lineup) {
        Lineup lin = (Lineup) lineup;
        if (!lin.benchFull() && isEligible(player, "BN")) return true;
        for (Position p : Position.forSport(player.sport())) {
            int ord = p.ordinal();
            if (lin.slotsByPos()[ord] > 0
                    && lin.countByPos()[ord] < lin.slotsByPos()[ord]
                    && isEligible(player, p.name())) {
                return true;
            }
        }
        return !lin.flexFull() && isEligible(player, "FLEX");
    }
}
