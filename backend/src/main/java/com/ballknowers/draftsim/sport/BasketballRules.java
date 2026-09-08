package com.ballknowers.draftsim.sport;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * Basketball need model. See claude/multi-sport-and-rebrand.md, the
 * "So 3c is, in full" section (§3c) and Phase 4, for the full derivation --
 * this class is an implementation of a design that was measured, not guessed:
 * {@code claude/scripts/nba-cascade-length.py} and
 * {@code claude/scripts/nba-greedy-optimality.py}.
 *
 * <p>Football's lineup model does not port: a basketball player can be
 * eligible at several positions (66% of players actually drafted in the real
 * 2025 Ball Knowers league are), and the nine starting slots
 * ({@code PG,SG,G,SF,PF,F,C,UTIL,UTIL}) nest three tiers of eligibility
 * rather than football's single dedicated-slot-then-FLEX shape.
 * "Best starting lineup" is therefore a maximum-weight independent set of a
 * <b>transversal matroid</b> (slots are the ground set; a set of players is
 * independent iff it has a perfect matching into distinct slots), and every
 * slot is worth exactly the same, so the matroid greedy theorem makes a
 * single value-sorted pass -- keep each player whose addition leaves the kept
 * set matchable, stop at nine -- PROVABLY optimal. No matching solver is
 * needed for that part, and none is used.
 *
 * <p>{@link #rosterNeed} is the part football's shape genuinely does not
 * answer: for a candidate who cannot join the current starting lineup, the
 * matroid's unique-circuit property names exactly which currently-seated
 * players he could displace (the fundamental circuit his addition would
 * create), and delta is his value minus the CHEAPEST of them -- picking any
 * other member of that circuit is also valid but not optimal. That circuit
 * depends only on the candidate's eligibility mask (one of at most 32 over
 * {PG,SG,SF,PF,C}), never on which specific player carries it, so the whole
 * table -- can-this-mask-join, and-if-not-whom-does-it-cheapest-evict -- is
 * precomputed once per pick in {@link #prepareLineup} and {@link #rosterNeed}
 * is an O(1) read off it plus the candidate's own value, matching the
 * {@code FootballRules} shape despite the different underlying math.
 */
@Component
public class BasketballRules implements SportRules {

    /**
     * Starting slots, in Sleeper's own {@code roster_positions} order for the
     * real Ball Knowers NBA league (verified live 2026-09-08 against
     * {@code GET /league/1229352720222134272}). Fixed and hardcoded rather
     * than derived from {@link LeagueSettings#rosterPositions()} -- the whole
     * lineup model (§3c) was proven against exactly this shape, not an
     * arbitrary one, and this repo's one basketball league does not vary it.
     * Bench capacity alone is read from {@code settings}, the same way
     * football reads {@link LeagueSettings#benchSlots()}, since that part is
     * genuinely generic.
     */
    private enum Slot { PG, SG, G, SF, PF, F, C, UTIL_1, UTIL_2 }

    private static final Slot[] SLOTS = Slot.values();
    private static final int NUM_SLOTS = SLOTS.length;   // 9

    /** This sport's own positions, in a fixed bit order -- PG=0,SG=1,SF=2,PF=3,C=4. */
    private static final List<Position> NBA_POS = Position.forSport(Sport.NBA);
    private static final Map<Position, Integer> POSITION_BIT = new EnumMap<>(Position.class);
    static {
        int i = 0;
        for (Position p : NBA_POS) POSITION_BIT.put(p, i++);
    }
    private static final int NUM_MASKS = 1 << NBA_POS.size();   // 32

    /** Which position-bits each starting slot accepts, as a bitmask over the 5 NBA positions. */
    private static final int[] SLOT_ACCEPTS = new int[NUM_SLOTS];
    static {
        SLOT_ACCEPTS[Slot.PG.ordinal()] = bit(Position.PG);
        SLOT_ACCEPTS[Slot.SG.ordinal()] = bit(Position.SG);
        SLOT_ACCEPTS[Slot.G.ordinal()] = bit(Position.PG) | bit(Position.SG);
        SLOT_ACCEPTS[Slot.SF.ordinal()] = bit(Position.SF);
        SLOT_ACCEPTS[Slot.PF.ordinal()] = bit(Position.PF);
        SLOT_ACCEPTS[Slot.F.ordinal()] = bit(Position.SF) | bit(Position.PF);
        SLOT_ACCEPTS[Slot.C.ordinal()] = bit(Position.C);
        int anyNba = bit(Position.PG) | bit(Position.SG) | bit(Position.SF) | bit(Position.PF) | bit(Position.C);
        SLOT_ACCEPTS[Slot.UTIL_1.ordinal()] = anyNba;
        SLOT_ACCEPTS[Slot.UTIL_2.ordinal()] = anyNba;
    }

    private static int bit(Position p) {
        return 1 << POSITION_BIT.get(p);
    }

    /** Which {@code Slot} KIND (for the isDraftable/isEligible "is a slot type full" check) each of the 9 starting slots is. */
    private static String kindName(Slot s) {
        return switch (s) {
            case UTIL_1, UTIL_2 -> "UTIL";
            default -> s.name();
        };
    }

    private final ScoringProperties.SportScoring cfg;

    // Same reasoning as FootballRules.valueCache: value() is a pure function
    // of (adp, valueDecay), which is fixed for the process lifetime.
    private final Map<Double, Double> valueCache = new ConcurrentHashMap<>();

    public BasketballRules(ScoringProperties props) {
        this.cfg = props.basketball();
    }

    @Override
    public Sport sport() {
        return Sport.NBA;
    }

    @Override
    public double value(BoardEntry entry) {
        return valueCache.computeIfAbsent(entry.adp(), adp -> Math.exp(-adp / cfg.valueDecay()));
    }

    /**
     * @param mask this candidate's eligibility bitmask over {PG,SG,SF,PF,C} -- 0 if none of his
     *             {@link Player#positions()} are basketball positions (defensive; real NBA board
     *             entries always have at least one).
     */
    private static int maskOf(Player player) {
        int m = 0;
        for (Position p : player.positions()) {
            Integer bit = POSITION_BIT.get(p);
            if (bit != null) m |= (1 << bit);
        }
        return m;
    }

    /**
     * The result of {@link #prepareLineup}: the greedy-optimal starting nine
     * (or fewer, if the roster is too small or too eligibility-constrained to
     * fill all nine), plus a 32-entry table -- one slot per possible
     * eligibility mask -- answering rosterNeed's question in O(1): can a
     * candidate with this mask join the kept set (he starts, at his own
     * value), or, if not, what is the value of the cheapest player in the
     * unique circuit his addition would create (whom he'd have to beat to
     * evict)?
     */
    private record Lineup(
            double total,
            boolean[] maskCanJoin,          // size NUM_MASKS
            double[] maskEvictValue,        // size NUM_MASKS; NaN where maskCanJoin is true or no evictable player exists
            Map<String, Integer> countByKind,
            Map<String, Integer> capacityByKind,
            boolean benchFull,
            ToDoubleFunction<BoardEntry> valueOf
    ) {}

    /**
     * Standard Kuhn augmenting-path search, one-to-one with
     * {@code claude/scripts/nba-greedy-optimality.py}'s {@code _try_kuhn}
     * (verified there against a brute-force oracle over 168 real roster
     * states and 4000 synthetic ones -- see that script and the class
     * javadoc). {@code masks[i]} is the eligibility mask of whichever
     * currently-kept player occupies index {@code i}; {@code placingMask} is
     * the mask of whoever we are currently trying to seat (a real kept player
     * being bumped, or the original candidate). Mutates {@code assign} ONLY
     * on a successful path (so a failed call leaves it untouched -- required
     * for the mask-probe use below, which must not disturb the real lineup).
     */
    private static boolean tryAssign(int placingId, int placingMask, int[] assign, int[] masks, boolean[] seenSlot) {
        for (int s = 0; s < NUM_SLOTS; s++) {
            if ((SLOT_ACCEPTS[s] & placingMask) == 0 || seenSlot[s]) continue;
            seenSlot[s] = true;
            int occupant = assign[s];
            if (occupant == -1 || tryAssign(occupant, masks[occupant], assign, masks, seenSlot)) {
                assign[s] = placingId;
                return true;
            }
        }
        return false;
    }

    @Override
    public Object prepareLineup(RosterState roster, LeagueSettings settings, ToDoubleFunction<BoardEntry> valueOf) {
        List<BoardEntry> sorted = new ArrayList<>(roster.picks());
        sorted.sort((a, b) -> Double.compare(valueOf.applyAsDouble(b), valueOf.applyAsDouble(a)));

        int[] assign = new int[NUM_SLOTS];
        Arrays.fill(assign, -1);
        List<BoardEntry> kept = new ArrayList<>(NUM_SLOTS);
        List<Integer> keptMasksList = new ArrayList<>(NUM_SLOTS);

        // Matroid greedy: sort by value descending, keep each player whose
        // addition leaves the kept set matchable, stop at nine. Provably
        // optimal -- see the class javadoc and nba-greedy-optimality.py.
        // Deliberately does NOT stop scanning on the first player who does
        // not fit: a lower-valued player later in `sorted` may still be
        // matchable even though a higher-valued one was not (e.g. a 5th
        // centre cannot join four already-kept centres, but a guard right
        // behind him in value can).
        for (BoardEntry e : sorted) {
            if (kept.size() == NUM_SLOTS) break;
            int mask = maskOf(e.player());
            if (mask == 0) continue;   // no recognized position; cannot occupy any slot
            int[] trial = assign.clone();
            boolean[] seen = new boolean[NUM_SLOTS];
            int newIdx = kept.size();
            int[] masksArr = toIntArray(keptMasksList);
            if (tryAssign(newIdx, mask, trial, masksArr, seen)) {
                assign = trial;
                kept.add(e);
                keptMasksList.add(mask);
            }
        }

        double total = 0;
        for (BoardEntry e : kept) total += valueOf.applyAsDouble(e);

        int[] keptMasks = toIntArray(keptMasksList);
        boolean[] maskCanJoin = new boolean[NUM_MASKS];
        double[] maskEvictValue = new double[NUM_MASKS];
        for (int m = 0; m < NUM_MASKS; m++) {
            int[] trial = assign.clone();
            boolean[] seen = new boolean[NUM_SLOTS];
            boolean ok = tryAssign(kept.size(), m, trial, keptMasks, seen);
            if (ok) {
                maskCanJoin[m] = true;
                maskEvictValue[m] = Double.NaN;
            } else {
                maskCanJoin[m] = false;
                double min = Double.POSITIVE_INFINITY;
                boolean any = false;
                for (int s = 0; s < NUM_SLOTS; s++) {
                    if (seen[s] && assign[s] != -1) {
                        double v = valueOf.applyAsDouble(kept.get(assign[s]));
                        if (v < min) { min = v; any = true; }
                    }
                }
                maskEvictValue[m] = any ? min : Double.NaN;
            }
        }

        Map<String, Integer> countByKind = new HashMap<>();
        Map<String, Integer> capacityByKind = new HashMap<>();
        for (Slot s : SLOTS) {
            String kind = kindName(s);
            capacityByKind.merge(kind, 1, Integer::sum);
            countByKind.putIfAbsent(kind, 0);
            if (assign[s.ordinal()] != -1) countByKind.merge(kind, 1, Integer::sum);
        }

        boolean benchFull = (roster.size() - kept.size()) >= settings.benchSlots();

        return new Lineup(total, maskCanJoin, maskEvictValue, countByKind, capacityByKind, benchFull, valueOf);
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
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

        int mask = maskOf(candidate.player());
        double delta;
        if (mask != 0 && lin.maskCanJoin()[mask]) {
            delta = own;
        } else {
            double evict = (mask == 0) ? Double.NaN : lin.maskEvictValue()[mask];
            delta = Double.isNaN(evict) ? 0.0 : Math.max(0.0, own - evict);
        }

        double captured = Math.max(0.0, Math.min(1.0, delta / own));
        // Depth is not worthless: byes, injuries and upside all make a bench
        // player worth something -- same reasoning as FootballRules.
        return cfg.benchFloor() + (1.0 - cfg.benchFloor()) * captured;
    }

    /**
     * Not on the simulation hot path (see {@link #prepareLineup}); delegates
     * to it so "what is this roster's starting lineup worth" has exactly one
     * implementation of the matroid-greedy logic rather than two that could
     * drift apart.
     */
    @Override
    public double startingLineupValue(RosterState roster, LeagueSettings settings) {
        return lineupValue(prepareLineup(roster, settings, this::value));
    }

    /**
     * Sleeper's {@code enforce_position_limits} lock (§3b), finally wired in
     * for the sport it actually binds for: undraftable iff every roster slot
     * KIND {@code entry} is eligible for, bench included, is already full.
     * Same bitmask/count shape as {@code FootballRules.hasOpenSlot} -- kept
     * as its own implementation rather than factored together with it, since
     * football's is keyed by {@code Position} (one dedicated slot family plus
     * FLEX) while basketball's is keyed by roster-slot KIND (G/F/UTIL nest
     * multiple positions) -- forcing one shared shape would touch football's
     * already-verified method for no shared benefit.
     *
     * <p>{@code round}/{@code totalRounds} are unread: basketball has no
     * "kickers go last" equivalent (§ latestRounds is an empty map), so
     * there is no round-window gate to apply here at all.
     */
    @Override
    public boolean isDraftable(BoardEntry entry, Object lineupObj, int round, int totalRounds) {
        Lineup lin = (Lineup) lineupObj;
        Player player = entry.player();
        if (!lin.benchFull() && isEligible(player, "BN")) return true;
        for (Slot s : SLOTS) {
            String kind = kindName(s);
            int count = lin.countByKind().getOrDefault(kind, 0);
            int capacity = lin.capacityByKind().getOrDefault(kind, 0);
            if (count < capacity && isEligible(player, kind)) return true;
        }
        return false;
    }

    @Override
    public boolean isEligible(Player player, String rosterSlot) {
        if ("BN".equals(rosterSlot)) return true;
        return switch (rosterSlot) {
            case "PG" -> player.positions().contains(Position.PG);
            case "SG" -> player.positions().contains(Position.SG);
            case "SF" -> player.positions().contains(Position.SF);
            case "PF" -> player.positions().contains(Position.PF);
            case "C" -> player.positions().contains(Position.C);
            case "G" -> player.positions().stream().anyMatch(p -> p == Position.PG || p == Position.SG);
            case "F" -> player.positions().stream().anyMatch(p -> p == Position.SF || p == Position.PF);
            case "UTIL" -> maskOf(player) != 0;
            default -> false;
        };
    }
}
