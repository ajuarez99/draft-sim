package com.ballknowers.draftsim.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Optional;

/**
 * Power rankings, member ballots and their commissioner writes on the wire
 * (claude/league-suite.md, claude/power-rankings-ballots.md, specs/021-codebase-cleanup).
 * Mirrored by {@code PowerRankings}, {@code BallotState} and the write helpers in
 * web/src/api.ts.
 */
public final class PowerRankingResponses {

    private PowerRankingResponses() {}

    /** {@code playoffOdds} is always present, and null when the league has no odds at all. */
    public record PowerRankingsResponse(String sleeperLeagueId, SportStateRow sportState, List<PowerEntry> entries,
                                        PlayoffOddsSummaryRow playoffOdds) {}

    public record SportStateRow(int week, String season, String seasonStartDate, boolean started) {}

    public record PlayoffOddsSummaryRow(int week, int iterations, String model, int weeksOfScoring) {}

    /**
     * One power-ranking entry, computed or MEMBER alike: one shape for every kind.
     * The MEMBER-only fields ({@code bestRank} through {@code selfRankBias}) are
     * always present, null on other kinds. {@code makesPlayoffsPct} is always present,
     * null until a stored odds snapshot fills it in, and never 0 standing in for
     * "no answer". The two {@code with…} methods build the copies the controller
     * fills in afterwards. They're not getters, so Jackson doesn't serialize them.
     */
    public record PowerEntry(int season, int week, String kind, int rosterId, Long managerId, String manager,
                             String avatarId, int rank, Double score, String note,
                             Integer bestRank, Integer worstRank, Double stdev, Integer ballotCount,
                             Integer selfRankBias, Double makesPlayoffsPct, String teamName) {

        public PowerEntry withMakesPlayoffsPct(Double pct) {
            return new PowerEntry(season, week, kind, rosterId, managerId, manager, avatarId, rank, score, note,
                    bestRank, worstRank, stdev, ballotCount, selfRankBias, pct, teamName);
        }

        public PowerEntry withTeamName(String name) {
            return new PowerEntry(season, week, kind, rosterId, managerId, manager, avatarId, rank, score, note,
                    bestRank, worstRank, stdev, ballotCount, selfRankBias, makesPlayoffsPct, name);
        }
    }

    /** {@code mine} is null until this caller has a ballot for the week. */
    public record BallotResponse(int season, int week, boolean canSubmit, boolean canCommission,
                                 boolean commissionerKnown, int memberCount, int ballotCount,
                                 List<BallotMemberRow> members, MyBallot mine) {}

    public record BallotMemberRow(int rosterId, Long managerId, String manager, String avatarId, String teamName,
                                  boolean isMe) {}

    public record MyBallot(List<Integer> rosterIds, String submittedAt) {}

    public record BallotSaved(boolean saved, int season, int week) {}

    public record CommissionerSaved(int saved) {}

    public record BackfillResponse(List<Backfilled> backfilled, List<Skipped> skipped) {}

    public record Backfilled(int season, Integer week, int entries) {}

    public record Skipped(int season, String reason) {}

    /**
     * {@code POST /power/compute}. The counts are always present. The rest are absent
     * when there is nothing to say, rather than present and null:
     * {@code playoffOddsThroughWeek} and {@code playoffOddsSkipped} are mutually
     * exclusive, and {@code week0Skipped} appears only for a league that hasn't drafted.
     *
     * <p>{@code realizedSkipped} has <b>three</b> wire states, and that is why it is an
     * {@link Optional}:
     * <ul>
     *   <li>absent (Java {@code null}), when a final week's ranking was written;</li>
     *   <li>a reason ({@code Optional.of}), when nothing was written and the reason is known;</li>
     *   <li>present-with-null ({@code Optional.empty()}), when a final week wrote nothing
     *       and {@code realizedGap} itself returned null.</li>
     * </ul>
     * Under {@code NON_NULL} an empty Optional is still written, as JSON null. A plain
     * {@code String} with {@code NON_NULL} would collapse the third state into the
     * first. LeagueHistoryControllerCharacterizationTest pins all three.
     */
    public record ComputeResponse(int week0, int realized, int playoffOdds,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) Integer playoffOddsThroughWeek,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String playoffOddsSkipped,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) Optional<String> realizedSkipped,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String week0Skipped) {}
}
