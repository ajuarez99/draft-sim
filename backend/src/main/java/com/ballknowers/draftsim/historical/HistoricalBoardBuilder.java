package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntFunction;

/**
 * The draft board for one historical season (spec 053 plan F5, F6, F9, F12), built from PRIOR seasons only.
 *
 * <p>Ranking is the labelled fallback, never "ADP" (research R4): the most recent qualifying line of the last {@code lookback}
 * seasons (S-1, S-2, S-3 by default) among those with at least {@code minGames}, scored as counting-stat fantasy points PER GAME times
 * {@code standardGames} (both hand-set, F6). Counting stats under the drafted season's COUNTED keys only, and
 * no dd/td/bonuses (F12), the same for both sources. A player with no qualifying prior line (a rookie, or one
 * who sat out) ranks after everyone who has one, in a seeded random order, and the card says so.
 *
 * <p><b>The returned list is sorted by rank ascending, rank 1 = best.</b> The engine's {@code PickDecider}
 * takes its candidates in list order and reads {@code adp} as a pick number, so this ordering is load-bearing
 * (plan F9); a sign inversion here would silently make every bot draft the worst player first.
 *
 * <p>Pure: no Spring, no database. Ties break by {@code historical_player.id} ascending.
 */
public final class HistoricalBoardBuilder {

    /** The stats a card may show and the ranking may count; derived keys (dd, td, bonuses) are never among them. */
    public static final List<String> COUNTING = List.of("pts", "reb", "ast", "stl", "blk", "to", "tpm");

    private HistoricalBoardBuilder() {}

    /** A player who played at least one game in the drafted season. */
    public record Candidate(long id, String name) {}

    /** Hand-set ranking constants (config {@code historical.rank-*}). */
    public record RankConfig(int standardGames, int minGames) {}

    /** How the ranked (veteran) part of the board is ordered. */
    public enum RankMode {
        /** The newest qualifying prior line, counting-stat points per game times the standard games (053). */
        PRIOR_PER_GAME,
        /**
         * Football (spec 056 F6, F14): the same per-game points scaled to the standard season, minus the score of
         * the replacement-level veteran at the player's position; early NFL draftees take a veteran's value.
         */
        POINTS_OVER_REPLACEMENT
    }

    /** A rookie's real NFL draft slot in the drafted season: the round and the overall pick (spec 056 F14). */
    public record RookiePick(int round, int pick) {}

    /**
     * The football-only ranking inputs (spec 056 F6, F14). Every number is hand-set and arbitrary, labelled so in
     * {@code NflHistoricalProperties}.
     *
     * @param replacementDepth per position, how many veterans of that position start across the league (teams x the
     *                         position's DEDICATED roster slots; the FLEX is ignored on purpose, F6). The Nth
     *                         qualifying veteran's score is the replacement level there.
     * @param rookieOffset     an early rookie is valued as the veteran at positional rank offset + his order
     * @param rookieMaxRound   only rookies drafted in NFL rounds 1..this are placed by their pick
     * @param totalKeys        the keys the card shows as season totals instead of per game (F16)
     */
    public record NflRank(Map<String, Integer> replacementDepth, int rookieOffset, int rookieMaxRound,
                          java.util.Set<String> totalKeys) {}

    /**
     * What differs per sport (spec 056 T010). {@code sport} is the domain sport every position must belong to,
     * {@code counting} the keys a card may show and the ranking may count, {@code label} names a season
     * ("1995-96" or "2007"), {@code mode} the ranking, {@code rookiePicks} (player id to his draft slot in the
     * drafted season) feeds the football rookie placement and the card, and {@code nfl} carries the football-only
     * inputs. Both are empty/null for basketball.
     */
    public record Options(Sport sport, List<String> counting, IntFunction<String> label, RankMode mode,
                          Map<Long, RookiePick> rookiePicks, NflRank nfl) {}

    /** The basketball options: today's behaviour exactly. */
    static Options nba() {
        return new Options(Sport.NBA, COUNTING, HistoricalBoardBuilder::label, RankMode.PRIOR_PER_GAME, Map.of(),
                null);
    }

    /**
     * @param seasonStart the drafted season
     * @param seed        orders the unranked tail
     * @param scoring     the drafted season's era-filtered scoring map
     * @param priors      lines by player id for seasons S-1, S-2, ... (index 0 = S-1, newest first; its size is
     *                    the lookback window, config {@code historical.rank-lookback-seasons})
     * @param drafted     season S lines by player id. Used ONLY as a last resort for a player's POSITIONS
     */
    public static List<HistoricalPlayerCard> build(int seasonStart, long seed, Map<String, Double> scoring,
                                                   List<Candidate> candidates,
                                                   List<? extends Map<Long, ? extends StatLine>> priors,
                                                   Map<Long, ? extends StatLine> drafted,
                                                   RankConfig cfg, Options opts) {
        List<String> counted = new ArrayList<>();
        for (String k : opts.counting()) if (scoring.containsKey(k)) counted.add(k);

        boolean par = opts.mode() == RankMode.POINTS_OVER_REPLACEMENT;
        if (par && opts.nfl() == null) {
            throw new IllegalArgumentException("POINTS_OVER_REPLACEMENT needs the football inputs (Options.nfl)");
        }

        List<Row> ranked = new ArrayList<>();
        List<Row> rookies = new ArrayList<>();
        List<Row> unranked = new ArrayList<>();
        for (Candidate c : candidates) {
            List<StatLine> window = new ArrayList<>(priors.size());
            for (Map<Long, ? extends StatLine> m : priors) window.add(m.get(c.id()));
            StatLine l1 = window.isEmpty() ? null : window.get(0);
            StatLine d = drafted.get(c.id());

            // Positions: the prior season's, newest first; the drafted season's only when there is none
            // (a rookie), a small documented leak (F5). Nobody with no line at all can be placed: off the board.
            List<String> positions = firstNonEmpty(window, d);
            if (positions == null) continue;

            StatLine best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            // The NEWEST qualifying line decides (Allan 2026-11-02): window is newest first, so stop at the first.
            for (StatLine l : window) {
                if (l == null || l.games() < cfg.minGames()) continue;
                bestScore = fantasyPerGame(l, scoring, counted) * cfg.standardGames();
                best = l;
                break;
            }

            // What the card shows is the line the ranking used; with none, the S-1 line if there is one.
            StatLine shown = best != null ? best : l1;
            // team/age come from the line the card shows, so the card header and its line always agree (review F4).
            String team = shown == null ? null : shown.team();
            Integer age = shown == null ? null : shown.age();
            RookiePick rp = opts.rookiePicks().get(c.id());
            boolean early = par && best == null && shown == null && rp != null
                    && rp.round() <= opts.nfl().rookieMaxRound();
            Row row = new Row(c, shown, best == null ? null : opts.label().apply(best.seasonStart()), bestScore,
                    positions, team, age, early, rp == null ? 0 : rp.pick());
            (best != null ? ranked : early ? rookies : unranked).add(row);
        }

        if (par) overReplacement(ranked, rookies, opts.nfl());
        ranked.addAll(rookies);
        // Veterans before a rookie on an equal score, then id (veteran) or draft pick (rookie): deterministic.
        ranked.sort(Comparator.comparingDouble(Row::score).reversed()
                .thenComparing(Row::rookie)
                .thenComparingLong(r -> r.rookie() ? r.pick() : r.c().id()));
        unranked.sort(Comparator.comparingLong(r -> r.c().id()));
        Collections.shuffle(unranked, new Random(seed));

        List<Row> all = new ArrayList<>(ranked);
        all.addAll(unranked);

        List<HistoricalPlayerCard> out = new ArrayList<>(all.size());
        Map<String, Integer> perPosition = new LinkedHashMap<>();
        int rank = 1;
        for (Row r : all) {
            // Position.valueOf throws on an unknown string, which is the intent: fail loud, never guess (F5).
            for (String p : r.positions()) {
                if (Position.valueOf(p).sport() != opts.sport()) {
                    throw new IllegalStateException("player " + r.c().id() + " has a non-" + opts.sport()
                            + " position " + p);
                }
            }
            int positional = perPosition.merge(r.positions().getFirst(), 1, Integer::sum);
            out.add(new HistoricalPlayerCard(r.c().id(), r.c().name(), r.positions(), r.team(), r.age(), rank++,
                    positional, prior(r.shown(), r.bestOfTwo(), scoring, counted, opts.label(),
                    opts.nfl() == null ? java.util.Set.of() : opts.nfl().totalKeys()),
                    rookiePickOf(r.c().id(), r.shown(), opts)));
        }
        return out;
    }

    /**
     * One candidate on its way to a board position. {@code rookie} marks a football early draftee placed by his NFL
     * pick ({@code pick}, the overall number); everyone else is placed by {@code score}.
     */
    private record Row(Candidate c, StatLine shown, String bestOfTwo, double score, List<String> positions,
                       String team, Integer age, boolean rookie, int pick) {
        Row withScore(double s) {
            return new Row(c, shown, bestOfTwo, s, positions, team, age, rookie, pick);
        }
    }

    /**
     * Turns raw season points into points over replacement (spec 056 F6) and values the early rookies (F14), in place.
     *
     * <p>Per position the qualifying veterans are sorted by raw score (ties by id). The replacement level is the raw
     * score of the Nth, N = {@code replacementDepth[position]} (teams x that position's dedicated starter slots), or
     * the last veteran's when fewer exist, or 0 when none do. A veteran's score is his raw minus that level. The
     * k-th early rookie of a position (by overall pick) is valued as the veteran at positional rank
     * {@code rookieOffset + k}, or the last veteran when fewer exist, or 0 (replacement level) when none do.
     */
    private static void overReplacement(List<Row> veterans, List<Row> rookies, NflRank nfl) {
        Map<String, List<Row>> byPosition = new LinkedHashMap<>();
        for (Row r : veterans) byPosition.computeIfAbsent(r.positions().getFirst(), k -> new ArrayList<>()).add(r);
        Map<String, List<Double>> rawByPosition = new LinkedHashMap<>();
        Map<String, Double> replacement = new LinkedHashMap<>();
        for (Map.Entry<String, List<Row>> e : byPosition.entrySet()) {
            Integer depth = nfl.replacementDepth().get(e.getKey());
            if (depth == null || depth < 1) {
                throw new IllegalStateException("no replacement depth for position " + e.getKey()
                        + " (a position the league has no dedicated slot for cannot be ranked over replacement)");
            }
            List<Double> raws = new ArrayList<>();
            e.getValue().stream().sorted(Comparator.comparingDouble(Row::score).reversed()
                    .thenComparingLong(r -> r.c().id())).forEach(r -> raws.add(r.score()));
            rawByPosition.put(e.getKey(), raws);
            replacement.put(e.getKey(), raws.get(Math.min(depth, raws.size()) - 1));
        }

        // Rookies first: they read the veterans' RAW scores, so this must run before the veterans are rewritten.
        Map<String, Integer> order = new LinkedHashMap<>();
        rookies.sort(Comparator.comparingInt(Row::pick));
        for (int i = 0; i < rookies.size(); i++) {
            Row r = rookies.get(i);
            String pos = r.positions().getFirst();
            int k = order.merge(pos, 1, Integer::sum);
            List<Double> raws = rawByPosition.get(pos);
            double value = 0.0;   // no veteran at the position: replacement level, which is 0 over replacement
            if (raws != null) {
                int target = Math.min(nfl.rookieOffset() + k, raws.size());
                value = raws.get(target - 1) - replacement.get(pos);
            }
            rookies.set(i, r.withScore(value));
        }
        for (int i = 0; i < veterans.size(); i++) {
            Row r = veterans.get(i);
            veterans.set(i, r.withScore(r.score() - replacement.get(r.positions().getFirst())));
        }
    }

    /** A rookie's draft slot is shown only on a card with no prior line at all; null for veterans and basketball. */
    private static Integer rookiePickOf(long id, StatLine shown, Options opts) {
        RookiePick rp = shown == null ? opts.rookiePicks().get(id) : null;
        return rp == null ? null : rp.pick();
    }

    /**
     * The basketball board with today's options. Package-private: it exists so the 053 tests keep calling the
     * builder as they always did, and generic code must go through the full {@link #build} with explicit options.
     */
    static List<HistoricalPlayerCard> build(int seasonStart, long seed, Map<String, Double> scoring,
                                            List<Candidate> candidates,
                                            List<? extends Map<Long, ? extends StatLine>> priors,
                                            Map<Long, ? extends StatLine> drafted, RankConfig cfg) {
        return build(seasonStart, seed, scoring, candidates, priors, drafted, cfg, nba());
    }

    /** "1995-96" for start year 1995; the century rolls ("1999-00"). */
    public static String label(int seasonStart) {
        return seasonStart + "-" + String.format("%02d", (seasonStart + 1) % 100);
    }

    /** Counting-stat fantasy points per game under {@code scoring}'s counted counting keys. Null totals are 0. */
    static double fantasyPerGame(StatLine l, Map<String, Double> scoring, List<String> counted) {
        if (l.games() <= 0) return 0.0;
        double total = 0;
        for (String k : counted) total += scoring.get(k) * total(l, k);
        return total / l.games();
    }

    private static HistoricalPlayerCard.Prior prior(StatLine l, String bestOfTwo, Map<String, Double> scoring,
                                                    List<String> counted, IntFunction<String> label,
                                                    java.util.Set<String> totalKeys) {
        if (l == null) return null;
        Map<String, Double> perGame = new LinkedHashMap<>();
        Map<String, Double> totals = new LinkedHashMap<>();   // football: rare keys as season totals (F16)
        double fp = 0;
        for (String k : counted) {
            double t = total(l, k);
            if (totalKeys.contains(k)) {
                if (t != 0) totals.put(k, t);
            } else {
                perGame.put(k, l.games() <= 0 ? 0.0 : Math.round(t / l.games() * 10.0) / 10.0);
            }
            fp += scoring.get(k) * t;
        }
        return new HistoricalPlayerCard.Prior(label.apply(l.seasonStart()), l.games(), perGame,
                Math.round(fp * 10.0) / 10.0, bestOfTwo, totals);
    }

    private static double total(StatLine l, String key) {
        Double v = l.stat(key);   // the per-sport switch lives on the line (spec 056 T005)
        return v == null ? 0.0 : v;   // FR-007: a counted stat unrecorded in a line is 0
    }

    private static List<String> firstNonEmpty(List<StatLine> window, StatLine drafted) {
        List<StatLine> lines = new ArrayList<>(window);
        lines.add(drafted);
        for (StatLine l : lines) {
            if (l == null) continue;
            // The first line that exists decides. An existing line with no positions is a data fault, not
            // "unknown": fail loud rather than let Player.primary() say WR (F5).
            if (l.positions().isEmpty()) {
                throw new IllegalStateException("player " + l.playerId() + " season " + l.seasonStart()
                        + " has an empty position set (Player.primary() would silently say WR)");
            }
            return l.positions();
        }
        return null;
    }

    /**
     * A card back into the engine's own type; the snapshot is the only source (plan F9). Package-private NBA
     * convenience for the 053 tests; generic code passes the game's sport.
     */
    static BoardEntry toEntry(HistoricalPlayerCard c) {
        return toEntry(c, Sport.NBA);
    }

    /** A card back into the engine's own type; the snapshot is the only source (plan F9). */
    public static BoardEntry toEntry(HistoricalPlayerCard c, Sport sport) {
        List<Position> positions = c.positions().stream().map(Position::valueOf).toList();
        if (positions.isEmpty()) {
            throw new IllegalStateException("board player " + c.id() + " has no positions");
        }
        Player p = new Player(c.id(), sport, "h" + c.id(), c.name(), positions, c.team(), null, null,
                c.age(), null);
        return new BoardEntry(p, c.rank(), c.positionalRank());
    }
}
