package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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

    /**
     * @param seasonStart the drafted season
     * @param seed        orders the unranked tail
     * @param scoring     the drafted season's era-filtered scoring map
     * @param priors      lines by player id for seasons S-1, S-2, ... (index 0 = S-1, newest first; its size is
     *                    the lookback window, config {@code historical.rank-lookback-seasons})
     * @param drafted     season S lines by player id. Used ONLY as a last resort for a player's POSITIONS
     */
    public static List<HistoricalPlayerCard> build(int seasonStart, long seed, Map<String, Double> scoring,
                                                   List<Candidate> candidates, List<Map<Long, SeasonLine>> priors,
                                                   Map<Long, SeasonLine> drafted,
                                                   RankConfig cfg) {
        List<String> counted = new ArrayList<>();
        for (String k : COUNTING) if (scoring.containsKey(k)) counted.add(k);

        record Row(Candidate c, SeasonLine shown, String bestOfTwo, double score,
                   List<String> positions, String team, Integer age) {}

        List<Row> ranked = new ArrayList<>();
        List<Row> unranked = new ArrayList<>();
        for (Candidate c : candidates) {
            List<SeasonLine> window = new ArrayList<>(priors.size());
            for (Map<Long, SeasonLine> m : priors) window.add(m.get(c.id()));
            SeasonLine l1 = window.isEmpty() ? null : window.get(0);
            SeasonLine d = drafted.get(c.id());

            // Positions: the prior season's, newest first; the drafted season's only when there is none
            // (a rookie), a small documented leak (F5). Nobody with no line at all can be placed: off the board.
            List<String> positions = firstNonEmpty(window, d);
            if (positions == null) continue;

            SeasonLine best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            // The NEWEST qualifying line decides (Allan 2026-11-02): window is newest first, so stop at the first.
            for (SeasonLine l : window) {
                if (l == null || l.games() < cfg.minGames()) continue;
                bestScore = fantasyPerGame(l, scoring, counted) * cfg.standardGames();
                best = l;
                break;
            }

            // What the card shows is the line the ranking used; with none, the S-1 line if there is one.
            SeasonLine shown = best != null ? best : l1;
            // team/age come from the line the card shows, so the card header and its line always agree (review F4).
            String team = shown == null ? null : shown.team();
            Integer age = shown == null ? null : shown.age();
            Row row = new Row(c, shown, best == null ? null : label(best.seasonStart()), bestScore,
                    positions, team, age);
            (best != null ? ranked : unranked).add(row);
        }

        ranked.sort(Comparator.comparingDouble(Row::score).reversed().thenComparingLong(r -> r.c().id()));
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
                if (Position.valueOf(p).sport() != Sport.NBA) {
                    throw new IllegalStateException("player " + r.c().id() + " has a non-NBA position " + p);
                }
            }
            int positional = perPosition.merge(r.positions().getFirst(), 1, Integer::sum);
            out.add(new HistoricalPlayerCard(r.c().id(), r.c().name(), r.positions(), r.team(), r.age(), rank++,
                    positional, prior(r.shown(), r.bestOfTwo(), scoring, counted)));
        }
        return out;
    }

    /** "1995-96" for start year 1995; the century rolls ("1999-00"). */
    public static String label(int seasonStart) {
        return seasonStart + "-" + String.format("%02d", (seasonStart + 1) % 100);
    }

    /** Counting-stat fantasy points per game under {@code scoring}'s counted counting keys. Null totals are 0. */
    static double fantasyPerGame(SeasonLine l, Map<String, Double> scoring, List<String> counted) {
        if (l.games() <= 0) return 0.0;
        double total = 0;
        for (String k : counted) total += scoring.get(k) * total(l, k);
        return total / l.games();
    }

    private static HistoricalPlayerCard.Prior prior(SeasonLine l, String bestOfTwo, Map<String, Double> scoring,
                                                    List<String> counted) {
        if (l == null) return null;
        Map<String, Double> perGame = new LinkedHashMap<>();
        double fp = 0;
        for (String k : counted) {
            double t = total(l, k);
            perGame.put(k, l.games() <= 0 ? 0.0 : Math.round(t / l.games() * 10.0) / 10.0);
            fp += scoring.get(k) * t;
        }
        return new HistoricalPlayerCard.Prior(label(l.seasonStart()), l.games(), perGame,
                Math.round(fp * 10.0) / 10.0, bestOfTwo);
    }

    private static double total(SeasonLine l, String key) {
        Double v = switch (key) {
            case "pts" -> l.pts();
            case "reb" -> l.reb();
            case "ast" -> l.ast();
            case "stl" -> l.stl();
            case "blk" -> l.blk();
            case "to" -> l.to();
            case "tpm" -> l.tpm();
            default -> throw new IllegalArgumentException("not a counting stat: " + key);
        };
        return v == null ? 0.0 : v;   // FR-007: a counted stat unrecorded in a line is 0
    }

    private static List<String> firstNonEmpty(List<SeasonLine> window, SeasonLine drafted) {
        List<SeasonLine> lines = new ArrayList<>(window);
        lines.add(drafted);
        for (SeasonLine l : lines) {
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

    /** A card back into the engine's own type; the snapshot is the only source (plan F9). */
    public static BoardEntry toEntry(HistoricalPlayerCard c) {
        List<Position> positions = c.positions().stream().map(Position::valueOf).toList();
        if (positions.isEmpty()) {
            throw new IllegalStateException("board player " + c.id() + " has no positions");
        }
        Player p = new Player(c.id(), Sport.NBA, "h" + c.id(), c.name(), positions, c.team(), null, null,
                c.age(), null);
        return new BoardEntry(p, c.rank(), c.positionalRank());
    }
}
