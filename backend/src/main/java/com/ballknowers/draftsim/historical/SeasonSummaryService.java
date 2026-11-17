package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.engine.RealizedLineupService;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import com.ballknowers.draftsim.historical.SeasonPage.DreamPlayer;
import com.ballknowers.draftsim.historical.SeasonPage.Finisher;
import com.ballknowers.draftsim.historical.SeasonPage.Mover;
import com.ballknowers.draftsim.historical.SeasonReplay.PlayerWeeks;
import com.ballknowers.draftsim.historical.SeasonReplay.TeamScorer;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.LongToDoubleFunction;
import java.util.function.LongToIntFunction;
import java.util.stream.IntStream;

/**
 * The public season summaries (spec 074): one {@link SeasonPage} per offered season, from the board and the player-week
 * scoring a game uses, with no game and no identity. Cached in memory (only the small records, never boards or stat
 * lines: spec 041's memory budget) and cleared when a historical import ends.
 *
 * <p><b>Cache.</b> Single flight per season and no JDBC inside a map operation (PR-19): the first caller for a season
 * stores an unfinished future and computes OUTSIDE the map; everyone else waits on that future. At most
 * {@link #MAX_CONCURRENT} seasons compute at once, so a crawler asking for every season cannot take every connection
 * or carrier thread from live drafts. A failure is not cached, and neither is an unknown season.
 *
 * <p><b>Ranks.</b> Defined here, not borrowed from a game (PR-15; no game reports an overall finish rank): a player's
 * finish rank is 1 + the number of board players with strictly more season points, so level points share a rank. His
 * going-in rank is his board rank when he is among the ranked part of the board, else none. See {@link #rank}.
 */
@Service
public class SeasonSummaryService {

    private static final Logger log = LoggerFactory.getLogger(SeasonSummaryService.class);
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Double>> DOUBLE_MAP = new TypeReference<>() {};

    // Hand-set and arbitrary, like the other display constants: a taste call about how much fits on a page, not a
    // measurement of anything.
    /** Overall finishers listed. */
    static final int TOP_OVERALL = 12;
    /** Finishers listed per position. */
    static final int TOP_PER_POSITION = 5;
    /** Busts and steals listed, each. */
    static final int MOVERS = 5;
    /** "Early" means the first this-many rounds of the draft: teams x this many players (hand-set). */
    static final int EARLY_ROUNDS = 5;
    /** Seasons computed at once (hand-set): bounds the JDBC and CPU a burst of cold requests can take. */
    static final int MAX_CONCURRENT = 2;
    /** The seed the page's board is built with. Only the unranked tail depends on it, and the page ignores that order. */
    static final long BOARD_SEED = 0L;

    private static final double EPS = 1e-9;

    private record Key(String sport, int seasonStart) {}

    private final HistoricalSports sports;
    private final SeasonReplay replay;
    private final RealizedLineupService lineups;
    private final SportRulesRegistry rulesRegistry;
    private final ObjectMapper json;

    private final Map<Key, CompletableFuture<SeasonPage>> pages = new ConcurrentHashMap<>();
    private final Map<String, List<SeasonRow>> offered = new ConcurrentHashMap<>();
    private final Semaphore permits = new Semaphore(MAX_CONCURRENT);

    public SeasonSummaryService(HistoricalSports sports, SeasonReplay replay, RealizedLineupService lineups,
                                SportRulesRegistry rulesRegistry, ObjectMapper json) {
        this.sports = sports;
        this.replay = replay;
        this.lineups = lineups;
        this.rulesRegistry = rulesRegistry;
        this.json = json;
    }

    // ---- public API -------------------------------------------------------------------------------------

    /** The offered seasons of one sport, ascending, as the public list. */
    public SeasonList list(HistoricalSport sport) {
        List<SeasonList.SeasonRef> refs = new ArrayList<>();
        for (SeasonRow s : offeredOf(sport)) {
            String label = sport.label(s.seasonStart());
            refs.add(new SeasonList.SeasonRef(sport.code(), s.seasonStart(), label, label));
        }
        return new SeasonList(SeasonList.SCHEMA, sport.code(), refs);
    }

    /** One season's page by its slug; empty for a slug that is not an offered season. */
    public Optional<SeasonPage> page(HistoricalSport sport, String slug) {
        if (slug == null) return Optional.empty();
        List<SeasonRow> all = offeredOf(sport);
        for (int i = 0; i < all.size(); i++) {
            SeasonRow row = all.get(i);
            if (!sport.label(row.seasonStart()).equals(slug)) continue;
            String prev = i > 0 ? sport.label(all.get(i - 1).seasonStart()) : null;
            String next = i + 1 < all.size() ? sport.label(all.get(i + 1).seasonStart()) : null;
            return Optional.of(cached(sport, row, prev, next));
        }
        return Optional.empty();
    }

    /** Forgets every cached summary and season list. Called when a historical import ends. */
    public void clear() {
        pages.clear();
        offered.clear();
    }

    // ---- cache ------------------------------------------------------------------------------------------

    private List<SeasonRow> offeredOf(HistoricalSport sport) {
        List<SeasonRow> cachedList = offered.get(sport.code());
        if (cachedList != null) return cachedList;
        List<SeasonRow> loaded = List.copyOf(sport.offeredSeasons());   // JDBC outside any map operation
        if (loaded.isEmpty()) return loaded;                              // an empty answer is not cached
        List<SeasonRow> raced = offered.putIfAbsent(sport.code(), loaded);
        return raced != null ? raced : loaded;
    }

    private SeasonPage cached(HistoricalSport sport, SeasonRow row, String prev, String next) {
        Key key = new Key(sport.code(), row.seasonStart());
        CompletableFuture<SeasonPage> existing = pages.get(key);
        if (existing == null) {
            CompletableFuture<SeasonPage> mine = new CompletableFuture<>();
            existing = pages.putIfAbsent(key, mine);
            if (existing == null) {
                // This caller won the race: compute outside the map, under the concurrency bound.
                try {
                    permits.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pages.remove(key, mine);
                    mine.completeExceptionally(e);
                    throw new IllegalStateException("interrupted waiting to compute " + sport.code() + " season "
                            + row.seasonStart());
                }
                try {
                    long t0 = System.nanoTime();
                    SeasonPage page = compute(sport, row, prev, next);
                    mine.complete(page);
                    log.info("season summary {} {} computed in {} ms", sport.code(), page.label(),
                            (System.nanoTime() - t0) / 1_000_000);
                    return page;
                } catch (RuntimeException | Error e) {
                    pages.remove(key, mine);   // never cache a failure
                    mine.completeExceptionally(e);
                    throw e;
                } finally {
                    permits.release();
                }
            }
        }
        try {
            return existing.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    // ---- compute ----------------------------------------------------------------------------------------

    private SeasonPage compute(HistoricalSport sport, SeasonRow season, String prevSlug, String nextSlug) {
        int s = season.seasonStart();
        Map<String, Double> scoring = sport.scoringFor(season);
        HistoricalBoardBuilder.Board board = sport.buildBoardRanked(season, BOARD_SEED, scoring);
        List<HistoricalPlayerCard> cards = board.cards();
        List<Long> ids = cards.stream().map(HistoricalPlayerCard::id).toList();

        PlayerWeeks pw = replay.scorePlayerWeeks(scoring, sport.playedWeeks(s, ids));
        Map<Long, Double> value = new HashMap<>();
        for (long id : ids) value.put(id, pw.seasonValue(id));

        Ranking ranking = rank(cards, board.rankedCount(), id -> value.get(id), pw::weeksPlayed, sport.teams(),
                positionOrder(sport));

        // Dream roster: the greedy search the game's hindsight uses, with nothing taken and every pick open, so it is
        // "the best roster a greedy search found", not a proven optimum (PR-14). The total is the roster's best-ball
        // team total from TeamScorer.season, not the sum of its players' points.
        LeagueSettings settings = new LeagueSettings(sport.sport(), sport.teams(), sport.rounds(),
                sport.rosterPositions(), 0.0, sport.reversalRound());
        Map<Long, Player> players = new HashMap<>();
        Map<Long, HistoricalPlayerCard> byId = new HashMap<>();
        for (HistoricalPlayerCard c : cards) {
            players.put(c.id(), HistoricalBoardBuilder.toEntry(c, sport.sport()).player());
            byId.put(c.id(), c);
        }
        TeamScorer team = new TeamScorer(lineups, settings, rulesRegistry.get(sport.sport()), players, pw);
        List<Integer> open = IntStream.rangeClosed(1, sport.rounds()).boxed().toList();
        java.util.Set<Long> chosen = new Comparisons(lineups).greedy(open, Map.of(), ids, team, season.weekCount(),
                value);
        double dreamTotal = team.season(chosen, season.weekCount());
        List<DreamPlayer> dream = new ArrayList<>();
        for (long id : chosen) {
            HistoricalPlayerCard c = byId.get(id);
            dream.add(new DreamPlayer(id, c.name(), c.positions(), value.get(id)));
        }
        dream.sort(Comparator.comparingDouble(DreamPlayer::points).reversed()
                .thenComparingLong(DreamPlayer::playerId));

        Map<String, String> excluded = StatOrder.sorted(readMap(season.excludedStatsJson(), STRING_MAP));
        Map<String, Double> coverage = StatOrder.sorted(readMap(season.statCoverageJson(), DOUBLE_MAP));
        String label = sport.label(s);
        return new SeasonPage(sport.code(), s, label, label, prevSlug, nextSlug, sport.teams(), sport.rounds(),
                sport.rosterPositions(), sport.settingsSource(), sport.pointsNote(), sport.rankingLabel(),
                sport.dataNotes(season), excluded, coverage, sport.attribution(), cards.size(), board.rankedCount(),
                ranking.topOverall(), ranking.topByPosition(), ranking.busts(), ranking.steals(),
                ranking.unrankedLeftOut(), dream, dreamTotal);
    }

    /** The sport's positions in declaration order: the order the page lists "best at each position". */
    private static List<String> positionOrder(HistoricalSport sport) {
        return Position.forSport(sport.sport()).stream().map(Enum::name).toList();
    }

    private <T> Map<String, T> readMap(String text, TypeReference<Map<String, T>> type) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored season metadata is not valid JSON: " + e.getOriginalMessage());
        }
    }

    // ---- the ranking: pure ------------------------------------------------------------------------------

    /** The lists a season page prints. */
    record Ranking(List<Finisher> topOverall, Map<String, List<Finisher>> topByPosition, List<Mover> busts,
                   List<Mover> steals, int unrankedLeftOut) {}

    /**
     * Who finished where, and who beat or missed his going-in rank. Pure: no Spring, no database.
     *
     * <p><b>Direction (bug class 1).</b> A LOWER rank is better, for both ranks. A bust has a finish rank WORSE
     * (numerically larger) than his going-in rank and a steal has one BETTER; a player who finished exactly where he
     * was ranked is neither. A bust is drawn from the players ranked going in among the first
     * {@code teams x EARLY_ROUNDS} (the ones taken early), a steal from the players who finished among that many.
     * An unranked player has no going-in rank, so he can be neither: if he finished among the top
     * {@code teams x EARLY_ROUNDS} he is only counted in {@code unrankedLeftOut}.
     *
     * <p>Ties never flap: every ordering ends on the player id, and equal points share a rank.
     *
     * @param cards        the board, best going-in first (card.rank is the going-in rank for the ranked part)
     * @param rankedCount  how many of the first cards are ranked rather than in the seeded tail
     * @param positionOrder the order of the position keys of {@code topByPosition}; a position with no player is left out
     */
    static Ranking rank(List<HistoricalPlayerCard> cards, int rankedCount, LongToDoubleFunction points,
                        LongToIntFunction weeksPlayed, int teams, List<String> positionOrder) {
        int n = cards.size();
        Map<Long, Integer> goingIn = new HashMap<>();
        for (int i = 0; i < rankedCount && i < n; i++) goingIn.put(cards.get(i).id(), cards.get(i).rank());

        int[] finish = finishRanks(cards, points);
        Map<Long, Integer> finishById = new HashMap<>();
        for (int i = 0; i < n; i++) finishById.put(cards.get(i).id(), finish[i]);

        List<HistoricalPlayerCard> best = new ArrayList<>(cards);
        best.sort(byPointsThenId(points));

        List<Finisher> topOverall = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_OVERALL, n); i++) {
            HistoricalPlayerCard c = best.get(i);
            topOverall.add(finisher(c, finishById.get(c.id()), points, weeksPlayed, goingIn));
        }

        Map<String, List<HistoricalPlayerCard>> byPosition = new HashMap<>();
        for (HistoricalPlayerCard c : best) byPosition.computeIfAbsent(primary(c), k -> new ArrayList<>()).add(c);
        Map<String, List<Finisher>> topByPosition = new LinkedHashMap<>();
        for (String pos : positionOrder) {
            List<HistoricalPlayerCard> pool = byPosition.get(pos);
            if (pool == null || pool.isEmpty()) continue;
            List<Finisher> rows = new ArrayList<>();
            for (int i = 0; i < Math.min(TOP_PER_POSITION, pool.size()); i++) {
                HistoricalPlayerCard c = pool.get(i);
                rows.add(finisher(c, positionRank(pool, c, points), points, weeksPlayed, goingIn));
            }
            topByPosition.put(pos, rows);
        }

        int early = teams * EARLY_ROUNDS;
        List<Mover> busts = new ArrayList<>(), steals = new ArrayList<>();
        int unrankedLeftOut = 0;
        for (HistoricalPlayerCard c : cards) {
            Integer in = goingIn.get(c.id());
            int out = finishById.get(c.id());
            if (in == null) {
                if (out <= early) unrankedLeftOut++;
                continue;
            }
            Mover m = new Mover(c.id(), c.name(), c.positions(), c.team(), in, out, points.applyAsDouble(c.id()),
                    weeksPlayed.applyAsInt(c.id()));
            if (in <= early && out > in) busts.add(m);
            if (out <= early && in > out) steals.add(m);
        }
        busts.sort(Comparator.comparingInt((Mover m) -> m.finishRank() - m.goingInRank()).reversed()
                .thenComparingLong(Mover::playerId));
        steals.sort(Comparator.comparingInt((Mover m) -> m.goingInRank() - m.finishRank()).reversed()
                .thenComparingLong(Mover::playerId));
        return new Ranking(topOverall, topByPosition, firstN(busts, MOVERS), firstN(steals, MOVERS), unrankedLeftOut);
    }

    /** 1 + how many scored strictly more (level within a hair share a rank), in the order of {@code cards}. */
    private static int[] finishRanks(List<HistoricalPlayerCard> cards, LongToDoubleFunction points) {
        int n = cards.size();
        double[] sorted = new double[n];
        for (int i = 0; i < n; i++) sorted[i] = points.applyAsDouble(cards.get(i).id());
        Arrays.sort(sorted);   // ascending
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            double mine = points.applyAsDouble(cards.get(i).id());
            out[i] = 1 + (n - firstIndexAbove(sorted, mine + EPS));
        }
        return out;
    }

    /** The first index whose value is strictly greater than {@code threshold}... in an ascending array; n when none. */
    private static int firstIndexAbove(double[] ascending, double threshold) {
        int lo = 0, hi = ascending.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ascending[mid] > threshold) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /** 1 + how many in {@code pool} scored strictly more than {@code c}: the game's {@code finishPositionRank} rule. */
    private static int positionRank(List<HistoricalPlayerCard> pool, HistoricalPlayerCard c,
                                    LongToDoubleFunction points) {
        double mine = points.applyAsDouble(c.id());
        int ahead = 0;
        for (HistoricalPlayerCard o : pool) if (points.applyAsDouble(o.id()) > mine + EPS) ahead++;
        return ahead + 1;
    }

    private static Finisher finisher(HistoricalPlayerCard c, int rank, LongToDoubleFunction points,
                                     LongToIntFunction weeksPlayed, Map<Long, Integer> goingIn) {
        return new Finisher(c.id(), c.name(), c.positions(), c.team(), rank, points.applyAsDouble(c.id()),
                weeksPlayed.applyAsInt(c.id()), goingIn.get(c.id()));
    }

    private static Comparator<HistoricalPlayerCard> byPointsThenId(LongToDoubleFunction points) {
        return Comparator.comparingDouble((HistoricalPlayerCard c) -> points.applyAsDouble(c.id())).reversed()
                .thenComparingLong(HistoricalPlayerCard::id);
    }

    private static <T> List<T> firstN(List<T> l, int n) {
        return new ArrayList<>(l.subList(0, Math.min(n, l.size())));
    }

    private static String primary(HistoricalPlayerCard c) {
        return c.positions() == null || c.positions().isEmpty() ? "" : c.positions().get(0);
    }
}
