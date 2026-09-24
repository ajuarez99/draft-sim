package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import com.ballknowers.draftsim.store.WeekBound;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
     * One manager's (or one unowned roster-season's, per R1) total scored
     * points summed across the whole chain. specs/006-deeper-history-both-sports
     * T060, data-model.md's {@code LeagueRecord} entity, kind {@code POINTS_LEADER}.
     *
     * <p>{@code rosterId}/{@code manager}/{@code avatarId} are the MOST RECENT
     * season's own values (research R1's grouping-by-manager argument applies
     * here too: a manager can hold a different roster id in a different
     * season, so there is no single "the" roster id -- the latest one is
     * simply the one worth showing).
     */
    public record PointsLeaderRecord(int rosterId, Long managerId, String manager, String avatarId,
                                     BigDecimal points, List<Integer> spanSeasons) {}

    /**
     * A run of consecutive weeks one roster won (or lost) every game it
     * played, within one season by default (research R7 -- with one or two
     * played seasons per chain, a cross-season streak would be a silent and
     * misleading merge). {@code withinSeasonOnly} rides on every entry so the
     * client states the rule rather than leaving it for the reader to assume
     * (US5.2).
     */
    public record StreakRecord(int rosterId, Long managerId, String manager, String avatarId,
                               int length, List<Integer> spanSeasons, int startWeek, int endWeek,
                               boolean withinSeasonOnly) {}

    /**
     * Everything the League History page's record book needs, in one object.
     *
     * <p>{@code marginsUnavailableReason} is non-null exactly when the two
     * margin lists are empty, and null when they are populated. An empty panel
     * that does not say why reads as a broken feature, which is the same
     * argument the power-rankings compute endpoint already makes about a zero
     * that does not explain itself.
     *
     * <p>{@code pointsLeaders}/{@code winStreaks}/{@code lossStreaks} carry no
     * unavailable-reason field of their own: {@code pointsLeaders} is empty
     * under exactly the same condition as {@code highestWeeks}/{@code
     * lowestWeeks} (no stored weekly scores), and the two streak lists are
     * empty under exactly the same condition as the margin lists (no paired
     * games) -- the client already has a sentence for each of those two
     * causes and reuses it rather than this object saying the same thing
     * twice.
     */
    public record RecordBook(int limit, List<WeeklyScoreRecord> highestWeeks, List<WeeklyScoreRecord> lowestWeeks,
                             List<MarginRecord> closestMatchups, List<MarginRecord> biggestBlowouts,
                             String marginsUnavailableReason, List<PointsLeaderRecord> pointsLeaders,
                             List<StreakRecord> winStreaks, List<StreakRecord> lossStreaks) {}

    public RecordBook forChain(Collection<Long> leagueIds, WeekBound bound) {
        return forChain(leagueIds, DEFAULT_LIMIT, bound);
    }

    public RecordBook forChain(Collection<Long> leagueIds, int limit, WeekBound bound) {
        List<WeeklyScoreRecord> highest = highestWeeks(leagueIds, limit, bound);
        List<WeeklyScoreRecord> lowest = lowestWeeks(leagueIds, limit, bound);
        List<MarginRecord> closest = closestMatchups(leagueIds, limit, bound);
        List<MarginRecord> blowouts = biggestBlowouts(leagueIds, limit, bound);
        List<PointsLeaderRecord> leaders = pointsLeaders(leagueIds, limit, bound);
        List<StreakRecord> wins = winStreaks(leagueIds, limit, bound);
        List<StreakRecord> losses = lossStreaks(leagueIds, limit, bound);
        return new RecordBook(limit, highest, lowest, closest, blowouts,
                closest.isEmpty() ? marginsUnavailableReason(leagueIds, bound) : null,
                leaders, wins, losses);
    }

    public List<WeeklyScoreRecord> highestWeeks(Collection<Long> leagueIds, int limit, WeekBound bound) {
        return weekPoints.extremes(leagueIds, true, limit, bound).stream().map(LeagueRecordService::score).toList();
    }

    public List<WeeklyScoreRecord> lowestWeeks(Collection<Long> leagueIds, int limit, WeekBound bound) {
        return weekPoints.extremes(leagueIds, false, limit, bound).stream().map(LeagueRecordService::score).toList();
    }

    public List<MarginRecord> closestMatchups(Collection<Long> leagueIds, int limit, WeekBound bound) {
        return margins(leagueIds, Comparator.comparing(MarginRecord::margin), limit, bound);
    }

    public List<MarginRecord> biggestBlowouts(Collection<Long> leagueIds, int limit, WeekBound bound) {
        return margins(leagueIds, Comparator.comparing(MarginRecord::margin).reversed(), limit, bound);
    }

    /**
     * All-time points scored, summed across the whole chain and grouped by
     * manager -- or, for an unowned roster-season, by that one season's own
     * roster id, exactly as R1 already treats an unowned week in the score
     * lists above. Grouping by roster id ALONE would be wrong here in a way
     * the score lists never have to worry about: {@code roster_id} is scoped
     * to one league-season, so roster 1 in one season and roster 1 in the
     * next are not necessarily the same person, and summing across seasons by
     * that key would silently merge two different managers' totals.
     */
    public List<PointsLeaderRecord> pointsLeaders(Collection<Long> leagueIds, int limit, WeekBound bound) {
        if (limit <= 0) return List.of();
        Map<String, List<RosterWeekPointsRepository.ScoreRow>> grouped = weekPoints.allScores(leagueIds, bound).stream()
                .collect(Collectors.groupingBy(r -> r.managerId() != null
                        ? "m:" + r.managerId()
                        : "u:" + r.season() + ":" + r.rosterId()));

        List<PointsLeaderRecord> out = new ArrayList<>();
        for (List<RosterWeekPointsRepository.ScoreRow> rows : grouped.values()) {
            BigDecimal total = rows.stream()
                    .map(RosterWeekPointsRepository.ScoreRow::points)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<Integer> spanSeasons = rows.stream()
                    .map(RosterWeekPointsRepository.ScoreRow::season)
                    .distinct().sorted().toList();
            // The most recent (season, week) row stands in for display -- see
            // the class javadoc on why there is no single "the" roster id.
            RosterWeekPointsRepository.ScoreRow latest = rows.stream()
                    .max(Comparator.comparingInt(RosterWeekPointsRepository.ScoreRow::season)
                            .thenComparingInt(RosterWeekPointsRepository.ScoreRow::week))
                    .orElseThrow();
            out.add(new PointsLeaderRecord(latest.rosterId(), latest.managerId(), latest.manager(),
                    latest.avatarId(), total, spanSeasons));
        }

        return out.stream()
                .sorted(Comparator.comparing(PointsLeaderRecord::points).reversed()
                        // Deterministic beyond the total itself, same reason the
                        // margin lists carry a tiebreak (data-model R2-equivalent).
                        .thenComparingInt(PointsLeaderRecord::rosterId))
                .limit(limit)
                .toList();
    }

    public List<StreakRecord> winStreaks(Collection<Long> leagueIds, int limit, WeekBound bound) {
        return streaks(leagueIds, Outcome.WIN, limit, bound);
    }

    public List<StreakRecord> lossStreaks(Collection<Long> leagueIds, int limit, WeekBound bound) {
        return streaks(leagueIds, Outcome.LOSS, limit, bound);
    }

    /** One side's result in one played game, by {@code starters_points}. Never derived from roster_season.wins -- same argument {@link #margin} already makes: a season's win column is the whole year, and this is one game. */
    private enum Outcome { WIN, LOSS, TIE }

    private record GameOutcome(int season, int week, int rosterId, Long managerId, String manager,
                               String avatarId, Outcome outcome) {}

    private static List<GameOutcome> outcomes(LeagueMatchupRepository.PairedGame g) {
        int cmp = g.aPoints().compareTo(g.bPoints());
        Outcome aOutcome = cmp > 0 ? Outcome.WIN : cmp < 0 ? Outcome.LOSS : Outcome.TIE;
        Outcome bOutcome = cmp > 0 ? Outcome.LOSS : cmp < 0 ? Outcome.WIN : Outcome.TIE;
        return List.of(
                new GameOutcome(g.season(), g.week(), g.aRosterId(), g.aManagerId(), g.aManager(), g.aAvatarId(), aOutcome),
                new GameOutcome(g.season(), g.week(), g.bRosterId(), g.bManagerId(), g.bManager(), g.bAvatarId(), bOutcome));
    }

    /**
     * Longest runs of {@code want} (WIN or LOSS), one roster and one season at
     * a time (research R7: streaks never cross a season boundary today).
     *
     * <p>A run is built by walking each roster-season's games in week order
     * and extending it only while BOTH hold: the outcome still matches
     * {@code want}, and the next game's week is exactly one more than the
     * last -- not merely the next game this roster happens to have played. A
     * tie breaks a run without starting a loss (the same rule
     * {@code HeadToHeadServiceTest.aTieIsCountedAsATieNeverFoldedIntoLosses}
     * already holds head-to-head records to); a bye or an unscored week
     * breaks it too, rather than being silently skipped over -- which is
     * exactly what keeps every returned streak's weeks contiguous.
     */
    private List<StreakRecord> streaks(Collection<Long> leagueIds, Outcome want, int limit, WeekBound bound) {
        if (limit <= 0) return List.of();
        List<GameOutcome> all = fixtures.pairedWithScores(leagueIds, bound).stream()
                .flatMap(g -> outcomes(g).stream())
                .toList();
        Map<String, List<GameOutcome>> bySeasonRoster = all.stream()
                .collect(Collectors.groupingBy(o -> o.season() + ":" + o.rosterId()));

        List<StreakRecord> out = new ArrayList<>();
        for (List<GameOutcome> games : bySeasonRoster.values()) {
            List<GameOutcome> sorted = games.stream()
                    .sorted(Comparator.comparingInt(GameOutcome::week))
                    .toList();
            int i = 0;
            while (i < sorted.size()) {
                if (sorted.get(i).outcome() != want) {
                    i++;
                    continue;
                }
                int start = i;
                int expectedWeek = sorted.get(i).week();
                int j = i;
                while (j < sorted.size() && sorted.get(j).outcome() == want && sorted.get(j).week() == expectedWeek) {
                    j++;
                    expectedWeek++;
                }
                GameOutcome first = sorted.get(start);
                GameOutcome last = sorted.get(j - 1);
                out.add(new StreakRecord(first.rosterId(), first.managerId(), first.manager(), first.avatarId(),
                        j - start, List.of(first.season()), first.week(), last.week(), true));
                i = j;
            }
        }

        return out.stream()
                .sorted(Comparator.comparingInt(StreakRecord::length).reversed()
                        // Same tiebreak shape as the margin lists: deterministic
                        // beyond the length itself, or the boundary of the limit
                        // reshuffles on reload.
                        .thenComparing((StreakRecord r) -> r.spanSeasons().get(0), Comparator.reverseOrder())
                        .thenComparingInt(StreakRecord::startWeek)
                        .thenComparingInt(StreakRecord::rosterId))
                .limit(limit)
                .toList();
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
    private List<MarginRecord> margins(Collection<Long> leagueIds, Comparator<MarginRecord> order, int limit, WeekBound bound) {
        if (limit <= 0) return List.of();
        return fixtures.pairedWithScores(leagueIds, bound).stream()
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
    private String marginsUnavailableReason(Collection<Long> leagueIds, WeekBound bound) {
        return "Head-to-head pairings haven't been loaded for this league's seasons yet.";
    }
}
