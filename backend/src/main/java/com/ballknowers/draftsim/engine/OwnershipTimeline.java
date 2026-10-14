package com.ballknowers.draftsim.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Who owned a player in which week, and what each side of a trade got out of it
 * (specs/033-trade-verdicts, research R1-R4, R11 and plan-review F1-F6).
 *
 * <p><b>Pure.</b> No Spring, no database: {@link TradeVerdictService} loads the rows and hands
 * them in, so the tests feed literal fixtures copied from the real tables.
 *
 * <p>The idea, in the order the rules apply:
 * <ol>
 *   <li><b>R1 attribution.</b> A player's week belongs to the roster that STARTED him. If nobody
 *       started him, it belongs to the single roster listing him in {@code players_points}. Two
 *       listings and no starter is a <i>duplicate</i>: held by both, flagged, never started,
 *       and never summed across rosters.</li>
 *   <li><b>R2 stretch.</b> A maximal run of consecutive weeks one roster holds him. A gap ends it.</li>
 *   <li><b>R3 window.</b> For trade T (stored leg L = the week it was PROPOSED, not accepted),
 *       receiver R and player P: the first week W >= L that R holds P. Before W, P may only sit on
 *       a giving roster of T (or on nobody), else he went somewhere else and T has no window. The
 *       window is the stretch starting at W.</li>
 *   <li><b>Counted weeks.</b> Only FINAL weeks. The first
 *       window week is dropped when R did not start him in it (F4, narrowed: a listed-but-unstarted
 *       first week can be a boundary week reflecting a roster change made after that week's games);
 *       every later week counts. See {@link #countedWeeks}.</li>
 * </ol>
 *
 * <p>A window that does not exist (or counts no week) gets a stated cause, never a silent zero.
 */
public final class OwnershipTimeline {

    private OwnershipTimeline() {}

    // ------------------------------------------------------------------ inputs

    /** One roster's scored week: points per listed player, and the players it started. */
    public record WeekRow(int week, int rosterId, Map<String, Double> points, Set<String> starters) {}

    /** A completed roster move. {@code adds}: player to receiving roster; {@code drops}: player to giving roster. */
    public record Move(String txId, String type, int leg, Instant acceptedAt,
                       Map<String, Integer> adds, Map<String, Integer> drops) {}

    /** Sleeper's pick payload: {@code originalRosterId} is the original owner, {@code ownerRosterId} the new one. */
    public record PickRef(int season, int round, int originalRosterId, int ownerRosterId) {}

    public record FaabRef(int senderRosterId, int receiverRosterId, int amount) {}

    /** A trade, with everything it moved. Its {@code txId} must also appear in {@link Inputs#moves()}. */
    public record Trade(String txId, int leg, Instant acceptedAt,
                        Map<String, Integer> adds, Map<String, Integer> drops,
                        List<PickRef> picks, List<FaabRef> faab, boolean assetsLoaded) {}

    public record DraftedPick(int round, int slot, int pickNo, Long managerId, String playerKey) {}

    /**
     * @param moves          completed moves of the season, ordered by acceptance time then id
     * @param finalWeeks     weeks that may count ({@code ScoredWeeks}, never a second reading of last_scored_leg)
     * @param slotToManager  the season draft's slot -> manager
     * @param draftComplete  pick resolution needs a finished draft
     */
    public record Inputs(int season, List<WeekRow> weeks, List<Move> moves, List<Trade> trades,
                         List<DraftedPick> draftedPicks, boolean draftComplete,
                         Map<Integer, Long> slotToManager, Map<Integer, Long> managerByRoster,
                         Set<Integer> finalWeeks, boolean seasonComplete) {}

    // ------------------------------------------------------------------ outputs

    public enum Status {
        COUNTED, NO_WEEK_YET, UNDONE, LEFT_BEFORE_PLAYING, NEVER_PLAYED_FOR_THEM,
        NOT_DRAFTED_YET, PICK_NOT_MATCHED,
        /** The receiver already owned him before the trade's leg, so the trade did not move him (F8). */
        ALREADY_HELD
    }

    public enum State { WINNER, EVEN, NO_WEEK_YET, UNDONE, RESTORED, NONE_COUNTED, NOT_COMPARABLE }

    public sealed interface Asset permits PlayerAsset, PickAsset, FaabAsset {}

    /**
     * @param firstWeek     first counted week, null unless COUNTED
     * @param countedWeeks  the weeks behind the totals, ascending; empty unless COUNTED
     * @param duplicateWeeks counted weeks in which two rosters listed him and neither started him
     * @param startedPoints UNROUNDED: a side sums these, and rounding each asset first drifts the total
     * @param windowFirst   the R3 window itself (null when there is none): every week of the
     *                      receiver's stretch, counted or not. Not on the wire; it is how a
     *                      reader can see that a leading unstarted week was kept, not dropped.
     */
    public record PlayerAsset(String playerKey, Status status, Integer firstWeek, Integer lastWeek,
                              double startedPoints, double benchPoints,
                              List<Integer> countedWeeks, List<Integer> duplicateWeeks,
                              Integer windowFirst, Integer windowLast) implements Asset {}

    /** {@code player} is null unless the pick resolved to a drafted player. */
    public record PickAsset(int season, int round, int originalRosterId, Status status,
                            Integer pickNo, PlayerAsset player) implements Asset {}

    public record FaabAsset(int amount, int fromRosterId) implements Asset {}

    public record Side(int rosterId, List<Asset> assets, double startedTotal, double benchTotal,
                       int weeksCounted) {}

    /** {@code winnerRosterId} null unless WINNER; {@code margin} null unless WINNER or EVEN. */
    public record Verdict(State state, Integer winnerRosterId, Double margin, int weeksCounted,
                          boolean isFinal) {}

    /** {@code sides} ranked by started total, descending. */
    public record Evaluated(String txId, int leg, Instant acceptedAt, boolean assetsLoaded,
                            Verdict verdict, List<Side> sides) {}

    /** Below this a margin reads as even (data-model.md). */
    static final double EVEN_BELOW = 0.05;

    // ------------------------------------------------------------------ evaluation

    public static List<Evaluated> evaluate(Inputs in) {
        Weeks weeks = new Weeks(in.weeks());
        List<Evaluated> out = new ArrayList<>();
        for (Trade t : in.trades()) out.add(evaluate(in, weeks, t));
        return out;
    }

    private static Evaluated evaluate(Inputs in, Weeks weeks, Trade t) {
        List<Move> later = laterMoves(in.moves(), t);
        Set<Integer> givers = giversOf(t);

        // Received assets per roster, in a stable order: players, picks, FAAB.
        Map<Integer, List<Asset>> bySide = new LinkedHashMap<>();
        t.adds().forEach((player, toRoster) -> bySide.computeIfAbsent(toRoster, k -> new ArrayList<>())
                .add(playerAsset(in, weeks, t, toRoster, player, givers, later)));
        for (PickRef p : t.picks()) {
            bySide.computeIfAbsent(p.ownerRosterId(), k -> new ArrayList<>())
                    .add(pickAsset(in, weeks, t, p, later));
        }
        for (FaabRef f : t.faab()) {
            bySide.computeIfAbsent(f.receiverRosterId(), k -> new ArrayList<>())
                    .add(new FaabAsset(f.amount(), f.senderRosterId()));
        }

        List<Side> sides = new ArrayList<>();
        bySide.forEach((rosterId, assets) -> sides.add(side(rosterId, assets)));
        // Ranked by what they started, best first; roster id only to make ties deterministic.
        sides.sort(Comparator.comparingDouble(Side::startedTotal).reversed()
                .thenComparingInt(Side::rosterId));

        return new Evaluated(t.txId(), t.leg(), t.acceptedAt(), t.assetsLoaded(),
                verdict(sides, in.seasonComplete()), List.copyOf(sides));
    }

    /** The rosters that gave something up in {@code t}: a player's old roster, or a FAAB sender. */
    private static Set<Integer> giversOf(Trade t) {
        Set<Integer> givers = new LinkedHashSet<>(t.drops().values());
        for (FaabRef f : t.faab()) givers.add(f.senderRosterId());
        return givers;
    }

    private static Side side(int rosterId, List<Asset> assets) {
        double started = 0, bench = 0;
        Set<Integer> weeks = new TreeSet<>();
        for (Asset a : assets) {
            PlayerAsset p = a instanceof PlayerAsset pa ? pa : a instanceof PickAsset k ? k.player() : null;
            if (p == null) continue;
            started += p.startedPoints();
            bench += p.benchPoints();
            weeks.addAll(p.countedWeeks());
        }
        return new Side(rosterId, List.copyOf(assets), round1(started), round1(bench), weeks.size());
    }

    private static Verdict verdict(List<Side> ranked, boolean seasonComplete) {
        Set<Integer> union = new TreeSet<>();
        for (Side s : ranked) {
            for (Asset a : s.assets()) {
                PlayerAsset p = a instanceof PlayerAsset pa ? pa : a instanceof PickAsset k ? k.player() : null;
                if (p != null) union.addAll(p.countedWeeks());
            }
        }
        int weeksCounted = union.size();

        boolean comparable = ranked.size() >= 2 && ranked.stream().allMatch(OwnershipTimeline::hasPlayerLikeAsset);
        if (!comparable) return new Verdict(State.NOT_COMPARABLE, null, null, weeksCounted, seasonComplete);
        if (weeksCounted == 0) {
            // Plan-review F7: say WHY nothing counted. UNDONE if every player-like asset was reversed,
            // NO_WEEK_YET if any is still waiting on a week, else NONE_COUNTED.
            boolean allUndone = true, anyNoWeekYet = false, allHeldOrUndone = true, anyHeld = false;
            for (Side s : ranked) {
                for (Asset a : s.assets()) {
                    PlayerAsset p = a instanceof PlayerAsset pa ? pa : a instanceof PickAsset k ? k.player() : null;
                    if (p == null) continue;
                    if (p.status() != Status.UNDONE) allUndone = false;
                    if (p.status() != Status.UNDONE && p.status() != Status.ALREADY_HELD) allHeldOrUndone = false;
                    if (p.status() == Status.ALREADY_HELD) anyHeld = true;
                    if (p.status() == Status.NO_WEEK_YET) anyNoWeekYet = true;
                }
            }
            // F8: every asset was already on the receiver (or reversed): the trade restored the status quo.
            if (allHeldOrUndone && anyHeld) return new Verdict(State.RESTORED, null, null, weeksCounted, seasonComplete);
            State st = allUndone ? State.UNDONE : anyNoWeekYet ? State.NO_WEEK_YET : State.NONE_COUNTED;
            return new Verdict(st, null, null, weeksCounted, seasonComplete);
        }

        // Unrounded totals decide EVEN: the sides' rounded totals are display values.
        double margin = rawStarted(ranked.get(0)) - rawStarted(ranked.get(1));
        if (Math.abs(margin) < EVEN_BELOW) {
            return new Verdict(State.EVEN, null, round1(margin), weeksCounted, seasonComplete);
        }
        return new Verdict(State.WINNER, ranked.get(0).rosterId(), round1(margin), weeksCounted, seasonComplete);
    }

    private static double rawStarted(Side s) {
        double sum = 0;
        for (Asset a : s.assets()) {
            PlayerAsset p = a instanceof PlayerAsset pa ? pa : a instanceof PickAsset k ? k.player() : null;
            if (p != null) sum += p.startedPoints();
        }
        return sum;
    }

    /** A PLAYER asset, or a PICK that resolved to a drafted player (data-model.md rule 1). */
    private static boolean hasPlayerLikeAsset(Side s) {
        for (Asset a : s.assets()) {
            if (a instanceof PlayerAsset) return true;
            if (a instanceof PickAsset k && k.player() != null) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ one received player

    private static PlayerAsset playerAsset(Inputs in, Weeks weeks, Trade t, int receiver, String player,
                                           Set<Integer> givers, List<Move> later) {
        Window w = window(weeks, receiver, player, t.leg(), givers);
        // A traded player's first listed week may be a boundary week: skip it if he was never started.
        return resolve(in, weeks, t, receiver, player, w, true, later);
    }

    private static PickAsset pickAsset(Inputs in, Weeks weeks, Trade t, PickRef p, List<Move> later) {
        // A future (or unfinished) draft has no player yet; an old season's pick should not exist.
        if (p.season() > in.season() || !in.draftComplete()) {
            return new PickAsset(p.season(), p.round(), p.originalRosterId(), Status.NOT_DRAFTED_YET, null, null);
        }
        if (p.season() < in.season()) {
            return new PickAsset(p.season(), p.round(), p.originalRosterId(), Status.PICK_NOT_MATCHED, null, null);
        }
        Long originalManager = in.managerByRoster().get(p.originalRosterId());
        Integer slot = null;
        if (originalManager != null) {
            for (Map.Entry<Integer, Long> e : in.slotToManager().entrySet()) {
                if (originalManager.equals(e.getValue())) { slot = e.getKey(); break; }
            }
        }
        DraftedPick drafted = null;
        if (slot != null) {
            for (DraftedPick d : in.draftedPicks()) {
                if (d.round() == p.round() && d.slot() == slot) { drafted = d; break; }
            }
        }
        Long receiverManager = in.managerByRoster().get(p.ownerRosterId());
        // Never a guess: the drafter must be the manager of the roster that received the pick.
        if (drafted == null || drafted.playerKey() == null || receiverManager == null
                || !receiverManager.equals(drafted.managerId())) {
            return new PickAsset(p.season(), p.round(), p.originalRosterId(), Status.PICK_NOT_MATCHED, null, null);
        }
        // A drafted player's window is his first stretch on the receiving roster (normally week 1).
        Window w = window(weeks, p.ownerRosterId(), drafted.playerKey(), 0, null);
        PlayerAsset player = resolve(in, weeks, t, p.ownerRosterId(), drafted.playerKey(), w, false, later);
        // Code review R2: the pick reads as its drafted player does (COUNTED, NO_WEEK_YET, LEFT_BEFORE_PLAYING, ...),
        // never COUNTED over a player who was dropped before he scored.
        return new PickAsset(p.season(), p.round(), p.originalRosterId(), player.status(), drafted.pickNo(), player);
    }

    /** Counts the window, or names why there is nothing to count. */
    private static PlayerAsset resolve(Inputs in, Weeks weeks, Trade t, int receiver, String player,
                                       Window w, boolean dropFirstWeekIfNotStarted, List<Move> later) {
        // F8, before any counting or cause logic: he was already the receiver's, so this trade moved nothing.
        if (w != null && w.alreadyHeld()) {
            return new PlayerAsset(player, Status.ALREADY_HELD, null, null, 0, 0, List.of(), List.of(),
                    w.firstWeek(), w.lastWeek());
        }
        Counted c = w == null ? null
                : countedWeeks(in, weeks, receiver, player, w, dropFirstWeekIfNotStarted);
        if (c != null && !c.weeks().isEmpty()) {
            return new PlayerAsset(player, Status.COUNTED, c.weeks().getFirst(), c.weeks().getLast(),
                    c.started(), c.bench(), List.copyOf(c.weeks()), List.copyOf(c.duplicates()),
                    w.firstWeek(), w.lastWeek());
        }
        Status cause = cause(in, t, receiver, player, later, w != null && w.hasNonFinalWeek(in.finalWeeks()));
        return new PlayerAsset(player, cause, null, null, 0, 0, List.of(), List.of(),
                w == null ? null : w.firstWeek(), w == null ? null : w.lastWeek());
    }

    /**
     * Why a received player has nothing to count, in the order that keeps each label true (F2):
     * <ol>
     *   <li>the first later move that takes him off the receiver decides: UNDONE if it is a trade or
     *       commissioner move returning him to a giving roster of this trade, else LEFT_BEFORE_PLAYING
     *       (a drop, free-agent churn, a trade elsewhere);</li>
     *   <li>NO_WEEK_YET: the season is not complete and no final week has covered the trade yet;</li>
     *   <li>NEVER_PLAYED_FOR_THEM: everything else.</li>
     * </ol>
     */
    private static Status cause(Inputs in, Trade t, int receiver, String player, List<Move> later,
                                boolean windowHasNonFinalWeek) {
        Set<Integer> givers = giversOf(t);
        // Only the FIRST move after the trade that takes him off the receiver decides (amended after live
        // verification). UNDONE iff that move is a trade or commissioner move putting him on a giving roster
        // of this trade; a drop, waiver/free-agent churn or a trade elsewhere is LEFT_BEFORE_PLAYING. Scanning
        // for any later return mislabelled 1036 and 1162: they were dropped first, re-added by the giver later.
        for (Move m : later) {
            Integer from = m.drops().get(player);
            if (from == null || from != receiver) continue;
            Integer to = m.adds().get(player);
            boolean reversal = ("TRADE".equals(m.type()) || "COMMISSIONER".equals(m.type()))
                    && to != null && givers.contains(to);
            return reversal ? Status.UNDONE : Status.LEFT_BEFORE_PLAYING;
        }
        // Code review R1: in a season still being played, a player nothing has taken off the receiver may still
        // be on that roster right now (e.g. a trade accepted after week N's games, with week N+1 not stored yet),
        // so he has not "never played for them", whatever his window looks like.
        if (!in.seasonComplete()) return Status.NO_WEEK_YET;
        return Status.NEVER_PLAYED_FOR_THEM;
    }

    /** Moves accepted after {@code t}: by position in the ordered list, else by acceptance time. */
    private static List<Move> laterMoves(List<Move> moves, Trade t) {
        for (int i = 0; i < moves.size(); i++) {
            if (moves.get(i).txId().equals(t.txId())) return moves.subList(i + 1, moves.size());
        }
        List<Move> out = new ArrayList<>();
        for (Move m : moves) {
            if (m.acceptedAt() != null && t.acceptedAt() != null && m.acceptedAt().isAfter(t.acceptedAt())) out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ attribution, stretches, windows

    /**
     * Who holds {@code player} in one week. {@code startedBy} is set when a roster started him
     * (the week is then that roster's alone); otherwise {@code rosters} is every roster listing
     * him, and {@code duplicate} when that is two or more.
     */
    private record Holding(Integer startedBy, Set<Integer> rosters, boolean duplicate) {
        static final Holding NONE = new Holding(null, Set.of(), false);
    }

    /**
     * The first week R holds the player and the last of that unbroken run. {@code alreadyHeld}: that run
     * began before the trade's leg, so the trade did not move him in scored terms (F8).
     */
    private record Window(int firstWeek, int lastWeek, boolean alreadyHeld) {
        boolean hasNonFinalWeek(Set<Integer> finalWeeks) {
            for (int wk = firstWeek; wk <= lastWeek; wk++) if (!finalWeeks.contains(wk)) return true;
            return false;
        }
    }

    private record Counted(List<Integer> weeks, double started, double bench, List<Integer> duplicates) {}

    private static final class Weeks {
        private final TreeMap<Integer, List<WeekRow>> byWeek = new TreeMap<>();

        Weeks(List<WeekRow> rows) {
            for (WeekRow r : rows) byWeek.computeIfAbsent(r.week(), k -> new ArrayList<>()).add(r);
        }

        Set<Integer> allWeeks() {
            return byWeek.keySet();
        }

        Iterable<Integer> weeksFrom(int from) {
            return byWeek.tailMap(from, true).keySet();
        }

        Holding holding(int week, String player) {
            List<WeekRow> rows = byWeek.get(week);
            if (rows == null) return Holding.NONE;
            for (WeekRow r : rows) {
                if (r.starters().contains(player)) return new Holding(r.rosterId(), Set.of(r.rosterId()), false);
            }
            Set<Integer> listing = new LinkedHashSet<>();
            for (WeekRow r : rows) if (r.points().containsKey(player)) listing.add(r.rosterId());
            return new Holding(null, listing, listing.size() > 1);
        }

        WeekRow row(int week, int rosterId) {
            List<WeekRow> rows = byWeek.get(week);
            if (rows == null) return null;
            for (WeekRow r : rows) if (r.rosterId() == rosterId) return r;
            return null;
        }
    }

    /**
     * R3. {@code givers == null} means no restriction on where he sat before (a drafted player).
     * Null when the receiver never holds him, or he sat on a roster outside {@code givers} first.
     */
    private static Window window(Weeks weeks, int receiver, String player, int leg, Set<Integer> givers) {
        Integer first = null;
        for (int wk : weeks.weeksFrom(leg)) {
            Holding h = weeks.holding(wk, player);
            if (h.rosters().contains(receiver)) { first = wk; break; }
            if (givers != null && !h.rosters().isEmpty() && !givers.containsAll(h.rosters())) return null;
        }
        if (first == null) return null;
        int last = first;
        while (weeks.holding(last + 1, player).rosters().contains(receiver)) last++;
        int start = first;
        while (weeks.holding(start - 1, player).rosters().contains(receiver)) start--;
        return new Window(first, last, start < leg);
    }

    /**
     * Final weeks of the window. Plan-review F4 (narrowed): only the FIRST window week is dropped, and
     * only when the receiver did not start the asset in it, because that one week may only reflect a
     * roster change made after its games (NFL 2464's 5012 sits unstarted at 0.0 on the receiver's
     * week-7 row). Every later week counts, started or bench: a player who sits on the bench for weeks
     * is still owned. A side that held but never started a player still reads EVEN at 0.0 (F6, NBA 8044).
     */
    private static Counted countedWeeks(Inputs in, Weeks weeks, int receiver, String player, Window w,
                                        boolean dropFirstWeekIfNotStarted) {
        Integer by = weeks.holding(w.firstWeek(), player).startedBy();
        boolean startedFirst = by != null && by == receiver;
        int from = dropFirstWeekIfNotStarted && !startedFirst ? w.firstWeek() + 1 : w.firstWeek();

        List<Integer> counted = new ArrayList<>(), dups = new ArrayList<>();
        double started = 0, bench = 0;
        for (int wk = from; wk <= w.lastWeek(); wk++) {
            if (!in.finalWeeks().contains(wk)) continue;
            WeekRow row = weeks.row(wk, receiver);
            Double pts = row == null ? null : row.points().get(player);
            double p = pts == null ? 0 : pts;
            Holding h = weeks.holding(wk, player);
            counted.add(wk);
            if (h.startedBy() != null && h.startedBy() == receiver) started += p;
            else bench += p;
            if (h.duplicate()) dups.add(wk);
        }
        return new Counted(counted, started, bench, dups);
    }

    static double round1(double d) {
        return Math.round(d * 10.0) / 10.0;
    }

    // ------------------------------------------------------------------ US2: stretches and reconciliation

    public enum How { DRAFT, TRADE, WAIVER, FREE_AGENT, COMMISSIONER, UNEXPLAINED }

    /**
     * One roster's unbroken run with one player (R2), over EVERY stored week. Points come from FINAL weeks
     * only and cover the whole stretch, including a leading listed-but-unstarted week, which is flagged
     * rather than hidden. {@code duplicateWeeks}: two rosters listed him and neither started him.
     * {@code leadingUnstarted}: the receiver did not start him in the stretch's first week.
     */
    public record Stretch(String playerKey, int rosterId, int firstWeek, int lastWeek, How how, String viaTxId,
                          double startedPoints, double benchPoints, List<Integer> duplicateWeeks,
                          boolean leadingUnstarted) {}

    /**
     * R5 as amended by plan-review F1. Reported as numbers, never hidden.
     * {@code unexplainedStretches}: a stretch start with no draft pick or inbound move behind it.
     * {@code sameWeekInAndOut}: a move whose player's next move has a leg no later than its own (expected).
     * {@code neverOnAScoredRoster}: an add never reflected in a stretch, whose next move is a later leg
     * (listed in examples).
     */
    public record Reconciliation(int playerWeeksChecked, int unexplainedStretches, int sameWeekInAndOut,
                                 int neverOnAScoredRoster, List<String> examples) {}

    public record Analysis(Map<String, List<Stretch>> stretchesByPlayer, Reconciliation reconciliation) {}

    private static final int MAX_EXAMPLES = 10;

    /**
     * Whole-season stretches for every player seen in the weekly rows, and the reconciliation over them.
     * {@code playerName} and {@code teamName} only word the examples.
     */
    public static Analysis analyze(Inputs in, java.util.function.Function<String, String> playerName,
                                   java.util.function.Function<Integer, String> teamName) {
        Weeks weeks = new Weeks(in.weeks());
        int maxStored = 0;
        Set<String> keys = new TreeSet<>();
        for (WeekRow r : in.weeks()) {
            maxStored = Math.max(maxStored, r.week());
            keys.addAll(r.points().keySet());
            keys.addAll(r.starters());
        }
        Map<String, TreeMap<Integer, Holding>> holdings = new TreeMap<>();
        int playerWeeks = 0;
        for (String key : keys) {
            TreeMap<Integer, Holding> byWeek = new TreeMap<>();
            for (int wk : weeks.allWeeks()) {
                Holding h = weeks.holding(wk, key);
                if (!h.rosters().isEmpty()) { byWeek.put(wk, h); playerWeeks++; }
            }
            holdings.put(key, byWeek);
        }

        Map<String, List<Move>> movesByPlayer = new HashMap<>();
        for (Move m : in.moves()) {
            Set<String> touched = new LinkedHashSet<>(m.adds().keySet());
            touched.addAll(m.drops().keySet());
            for (String key : touched) movesByPlayer.computeIfAbsent(key, x -> new ArrayList<>()).add(m);
        }
        Map<String, DraftedPick> draftedBy = new HashMap<>();
        for (DraftedPick d : in.draftedPicks()) if (d.playerKey() != null) draftedBy.put(d.playerKey(), d);

        Map<String, List<Stretch>> out = new TreeMap<>();
        int unexplained = 0;
        for (Map.Entry<String, TreeMap<Integer, Holding>> e : holdings.entrySet()) {
            String key = e.getKey();
            List<int[]> runs = new ArrayList<>();   // {roster, first, last}
            Set<Integer> rosters = new TreeSet<>();
            e.getValue().values().forEach(h -> rosters.addAll(h.rosters()));
            for (int r : rosters) {
                int first = -1, last = -1;
                for (Map.Entry<Integer, Holding> w : e.getValue().entrySet()) {
                    if (!w.getValue().rosters().contains(r)) continue;
                    int wk = w.getKey();
                    if (first >= 0 && wk == last + 1) { last = wk; continue; }
                    if (first >= 0) runs.add(new int[] {r, first, last});
                    first = wk;
                    last = wk;
                }
                if (first >= 0) runs.add(new int[] {r, first, last});
            }
            runs.sort(Comparator.<int[]>comparingInt(a -> a[1]).thenComparingInt(a -> a[0]));

            List<Stretch> list = new ArrayList<>();
            for (int i = 0; i < runs.size(); i++) {
                int r = runs.get(i)[0], first = runs.get(i)[1], last = runs.get(i)[2];
                int prevFirst = i == 0 ? Integer.MIN_VALUE : runs.get(i - 1)[1];
                How how = How.UNEXPLAINED;
                String via = null;
                DraftedPick d = draftedBy.get(key);
                Long manager = in.managerByRoster().get(r);
                if (i == 0 && d != null && manager != null && manager.equals(d.managerId())) {
                    how = How.DRAFT;
                } else {
                    for (Move m : movesByPlayer.getOrDefault(key, List.of())) {
                        Integer to = m.adds().get(key);
                        if (to != null && to == r && m.leg() >= prevFirst && m.leg() <= first) {
                            how = howOf(m.type());
                            via = m.txId();   // the last matching move in order wins
                        }
                    }
                }
                if (how == How.UNEXPLAINED) unexplained++;

                double started = 0, bench = 0;
                List<Integer> dups = new ArrayList<>();
                for (int wk = first; wk <= last; wk++) {
                    if (!in.finalWeeks().contains(wk)) continue;
                    WeekRow row = weeks.row(wk, r);
                    Double pts = row == null ? null : row.points().get(key);
                    double p = pts == null ? 0 : pts;
                    Holding h = weeks.holding(wk, key);
                    if (h.startedBy() != null && h.startedBy() == r) started += p; else bench += p;
                    if (h.duplicate()) dups.add(wk);
                }
                Holding firstHolding = weeks.holding(first, key);
                boolean leadingUnstarted = !(firstHolding.startedBy() != null && firstHolding.startedBy() == r);
                list.add(new Stretch(key, r, first, last, how, via, round1(started), round1(bench),
                        List.copyOf(dups), leadingUnstarted));
            }
            out.put(key, list);
        }

        int sameWeek = 0, never = 0;
        List<String> examples = new ArrayList<>();
        for (Move m : in.moves()) {
            if (m.leg() > maxStored) continue;   // nothing has been scored that could reflect it yet
            for (Map.Entry<String, Integer> add : m.adds().entrySet()) {
                String key = add.getKey();
                int r = add.getValue();
                List<Move> mine = movesByPlayer.getOrDefault(key, List.of());
                int idx = mine.indexOf(m);
                Move next = idx >= 0 && idx + 1 < mine.size() ? mine.get(idx + 1) : null;
                boolean reflected = false;
                for (Stretch st : out.getOrDefault(key, List.of())) {
                    if (st.rosterId() == r && st.lastWeek() >= m.leg()
                            && (next == null || st.firstWeek() <= Math.max(next.leg(), m.leg()))) {
                        reflected = true;
                        break;
                    }
                }
                if (reflected) continue;
                if (next != null && next.leg() <= m.leg()) { sameWeek++; continue; }
                never++;
                if (examples.size() < MAX_EXAMPLES) {
                    examples.add(playerName.apply(key) + " was added to " + teamName.apply(r) + " by a "
                            + m.type() + " in week " + m.leg() + " and never appears on a scored roster; the next move was "
                            + (next == null ? "none" : "a " + next.type() + " in week " + next.leg()) + ".");
                }
            }
        }
        return new Analysis(out, new Reconciliation(playerWeeks, unexplained, sameWeek, never, List.copyOf(examples)));
    }

    private static How howOf(String type) {
        return switch (type) {
            case "TRADE" -> How.TRADE;
            case "WAIVER" -> How.WAIVER;
            case "FREE_AGENT" -> How.FREE_AGENT;
            case "COMMISSIONER" -> How.COMMISSIONER;
            default -> How.UNEXPLAINED;
        };
    }
}
