package com.ballknowers.draftsim.api.dto;

import com.ballknowers.draftsim.engine.LeagueRecordService;

import java.math.BigDecimal;
import java.util.List;

/**
 * The league record book on the wire (specs/002-league-history-record-book,
 * specs/021-codebase-cleanup). Every list is always present, even when empty: a
 * missing key would make "no records" look the same as "an endpoint older than the
 * record book". Points are {@link BigDecimal}, so the wire carries the stored number
 * (205.04) and formatting is the client's job.
 *
 * <p>These mirror {@link LeagueRecordService}'s records field for field today. They
 * are separate types anyway, so the wire contract is declared here and an engine
 * change can't quietly reshape an API response. Mirrored by {@code LeagueRecords} and
 * friends in web/src/api.ts.
 */
public final class RecordBookResponses {

    private RecordBookResponses() {}

    public record RecordBookResponse(int limit, List<WeeklyScoreRow> highestWeeks, List<WeeklyScoreRow> lowestWeeks,
                                     List<MarginRow> closestMatchups, List<MarginRow> biggestBlowouts,
                                     String marginsUnavailableReason, List<PointsLeaderRow> pointsLeaders,
                                     List<StreakRow> winStreaks, List<StreakRow> lossStreaks) {

        public static RecordBookResponse of(LeagueRecordService.RecordBook b) {
            return new RecordBookResponse(b.limit(),
                    b.highestWeeks().stream().map(WeeklyScoreRow::of).toList(),
                    b.lowestWeeks().stream().map(WeeklyScoreRow::of).toList(),
                    b.closestMatchups().stream().map(MarginRow::of).toList(),
                    b.biggestBlowouts().stream().map(MarginRow::of).toList(),
                    b.marginsUnavailableReason(),
                    b.pointsLeaders().stream().map(PointsLeaderRow::of).toList(),
                    b.winStreaks().stream().map(StreakRow::of).toList(),
                    b.lossStreaks().stream().map(StreakRow::of).toList());
        }
    }

    public record WeeklyScoreRow(int season, int week, int rosterId, Long managerId, String manager,
                                 String avatarId, BigDecimal points) {
        static WeeklyScoreRow of(LeagueRecordService.WeeklyScoreRecord r) {
            return new WeeklyScoreRow(r.season(), r.week(), r.rosterId(), r.managerId(), r.manager(), r.avatarId(), r.points());
        }
    }

    public record MarginRow(int season, int week, BigDecimal margin, MarginSide winner, MarginSide loser) {
        static MarginRow of(LeagueRecordService.MarginRecord r) {
            return new MarginRow(r.season(), r.week(), r.margin(), MarginSide.of(r.winner()), MarginSide.of(r.loser()));
        }
    }

    public record MarginSide(int rosterId, Long managerId, String manager, String avatarId, BigDecimal points) {
        static MarginSide of(LeagueRecordService.Side s) {
            return new MarginSide(s.rosterId(), s.managerId(), s.manager(), s.avatarId(), s.points());
        }
    }

    public record PointsLeaderRow(int rosterId, Long managerId, String manager, String avatarId, BigDecimal points,
                                  List<Integer> spanSeasons) {
        static PointsLeaderRow of(LeagueRecordService.PointsLeaderRecord r) {
            return new PointsLeaderRow(r.rosterId(), r.managerId(), r.manager(), r.avatarId(), r.points(), r.spanSeasons());
        }
    }

    /** {@code withinSeasonOnly} rides on each entry so the page can state the rule beside the figure (research R7). */
    public record StreakRow(int rosterId, Long managerId, String manager, String avatarId, int length,
                            List<Integer> spanSeasons, int startWeek, int endWeek, boolean withinSeasonOnly) {
        static StreakRow of(LeagueRecordService.StreakRecord r) {
            return new StreakRow(r.rosterId(), r.managerId(), r.manager(), r.avatarId(), r.length(), r.spanSeasons(),
                    r.startWeek(), r.endWeek(), r.withinSeasonOnly());
        }
    }
}
