package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * specs/002-league-history-record-book: the record book behind League History --
 * the highest and lowest weeks anyone has posted, and the closest and widest
 * games, across every season this DB has ingested for a league.
 *
 * <p>Two things about the scoping are easy to get wrong and are therefore
 * spelled out here.
 *
 * <p><b>A league id is a SEASON.</b> Sleeper mints a new league id every year and
 * links it back through {@code previous_league_id}, so {@code league.id} in this
 * schema identifies a league-season, not a franchise. Every method here takes a
 * <em>collection</em> of league ids -- the chain, as
 * {@link com.ballknowers.draftsim.store.LeagueRepository#chainBySleeperId}
 * assembles it. Passing one id answers "this season", which is the question the
 * page already had an answer for.
 *
 * <p><b>This reads stored rows only.</b> No Sleeper call, on this path or any
 * path it delegates to. Scores arrive via
 * {@link com.ballknowers.draftsim.ingest.LeagueHistoryIngestService}, and the
 * page is a reader.
 *
 * <p>Aggregate volume is small enough that this is deliberately plain SQL rather
 * than anything cached: the largest per-league-season population measured on
 * 2026-09-16 was 288 roster-weeks (NBA 2024).
 */
@Service
public class LeagueRecordService {

    /**
     * Entries per record list. Applies to all four lists, so the high list and
     * the low list are always the same length (FR-003) -- a reader comparing
     * them should not have to notice that one of them stopped early.
     */
    public static final int DEFAULT_LIMIT = 10;

    private final RosterWeekPointsRepository weekPoints;
    private final LeagueMatchupRepository fixtures;

    public LeagueRecordService(RosterWeekPointsRepository weekPoints, LeagueMatchupRepository fixtures) {
        this.weekPoints = weekPoints;
        this.fixtures = fixtures;
    }

    /** One roster's scored total in one (season, week), attributed to a manager. */
    public record WeeklyScoreRecord(int season, int week, int rosterId, Long managerId, String manager,
                                    String avatarId, BigDecimal points) {}

    /** One side of a played matchup. */
    public record Side(int rosterId, Long managerId, String manager, String avatarId, BigDecimal points) {}

    /** Two rosters paired by {@code matchup_id} within one (season, week). */
    public record MarginRecord(int season, int week, BigDecimal margin, Side winner, Side loser) {}

    /**
     * Everything the League History page's record book needs, in one object.
     *
     * <p>{@code marginsUnavailableReason} is non-null exactly when the two
     * margin lists are empty, and null when they are populated. An empty panel
     * that does not say why reads as a broken feature, which is the same
     * argument the power-rankings compute endpoint already makes about a zero
     * that does not explain itself.
     */
    public record RecordBook(int limit, List<WeeklyScoreRecord> highestWeeks, List<WeeklyScoreRecord> lowestWeeks,
                             List<MarginRecord> closestMatchups, List<MarginRecord> biggestBlowouts,
                             String marginsUnavailableReason) {}

    public RecordBook forChain(Collection<Long> leagueIds) {
        return forChain(leagueIds, DEFAULT_LIMIT);
    }

    public RecordBook forChain(Collection<Long> leagueIds, int limit) {
        List<WeeklyScoreRecord> highest = highestWeeks(leagueIds, limit);
        List<WeeklyScoreRecord> lowest = lowestWeeks(leagueIds, limit);
        List<MarginRecord> closest = closestMatchups(leagueIds, limit);
        List<MarginRecord> blowouts = biggestBlowouts(leagueIds, limit);
        return new RecordBook(limit, highest, lowest, closest, blowouts,
                closest.isEmpty() ? marginsUnavailableReason(leagueIds) : null);
    }

    public List<WeeklyScoreRecord> highestWeeks(Collection<Long> leagueIds, int limit) {
        return weekPoints.extremes(leagueIds, true, limit).stream().map(LeagueRecordService::score).toList();
    }

    public List<WeeklyScoreRecord> lowestWeeks(Collection<Long> leagueIds, int limit) {
        return weekPoints.extremes(leagueIds, false, limit).stream().map(LeagueRecordService::score).toList();
    }

    public List<MarginRecord> closestMatchups(Collection<Long> leagueIds, int limit) {
        return margins(leagueIds, Comparator.comparing(MarginRecord::margin), limit);
    }

    public List<MarginRecord> biggestBlowouts(Collection<Long> leagueIds, int limit) {
        return margins(leagueIds, Comparator.comparing(MarginRecord::margin).reversed(), limit);
    }

    /**
     * Ordered in Java rather than in SQL, deliberately: the margin is a derived
     * quantity (|a - b|) and the winner/loser split depends on it, so sorting
     * here keeps one definition of "margin" instead of one in SQL and one in
     * the mapper that would be free to disagree.
     *
     * <p>Volume makes this safe -- the largest per-league-season population
     * measured was 288 roster-weeks, so a chain is a few hundred games.
     */
    private List<MarginRecord> margins(Collection<Long> leagueIds, Comparator<MarginRecord> order, int limit) {
        if (limit <= 0) return List.of();
        return fixtures.pairedWithScores(leagueIds).stream()
                .map(LeagueRecordService::margin)
                .sorted(order
                        // Deterministic beyond the margin itself, for the same
                        // reason the score query has a tiebreak: at the boundary
                        // of the limit, a tie would otherwise change which game
                        // is shown between reloads.
                        .thenComparing(MarginRecord::season, Comparator.reverseOrder())
                        .thenComparing(MarginRecord::week)
                        .thenComparing(m -> m.winner().rosterId()))
                .limit(limit)
                .toList();
    }

    private static MarginRecord margin(LeagueMatchupRepository.PairedGame g) {
        Side a = new Side(g.aRosterId(), g.aManagerId(), g.aManager(), g.aAvatarId(), g.aPoints());
        Side b = new Side(g.bRosterId(), g.bManagerId(), g.bManager(), g.bAvatarId(), g.bPoints());
        // By points, not by roster_season.wins -- a season's win column is the
        // whole year, and this is one game.
        boolean aWon = g.aPoints().compareTo(g.bPoints()) >= 0;
        return new MarginRecord(g.season(), g.week(), g.aPoints().subtract(g.bPoints()).abs(),
                aWon ? a : b, aWon ? b : a);
    }

    private static WeeklyScoreRecord score(RosterWeekPointsRepository.ScoreRow r) {
        return new WeeklyScoreRecord(r.season(), r.week(), r.rosterId(), r.managerId(), r.manager(),
                r.avatarId(), r.points());
    }

    /**
     * Why there are no margins to show. Separated from the lists themselves
     * because "no games played yet" and "this league's past seasons were
     * ingested before pairings were stored" are different sentences, and the
     * second one is the common case on any league ingested before 2026-09-14.
     */
    private String marginsUnavailableReason(Collection<Long> leagueIds) {
        return "Head-to-head pairings haven't been loaded for this league's seasons yet.";
    }
}
