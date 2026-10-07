package com.ballknowers.draftsim.api.dto;

import com.ballknowers.draftsim.engine.WeeklyReportService;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * {@code GET /leagues/{id}/weekly-report/{week}} on the wire (specs/004, 005, 013,
 * 021). Mirrored by {@code WeeklyReport} in web/src/api.ts.
 *
 * <p>There are two emitted shapes, so there are two records (plan-review finding 2):
 * <ul>
 *   <li>An <b>unavailable</b> week states its {@code reason} and sends the four core
 *       lists empty.</li>
 *   <li>An <b>available</b> week has no {@code reason} key. Its sport-specific
 *       sections are either the single {@code topPerformers} list (one game per
 *       period) or the {@code bestNights}/{@code bestWeek} pair with its
 *       {@code basis}. The section that doesn't apply is <i>absent</i>, not empty: an
 *       empty array says "we looked and found none", absence says "this doesn't apply
 *       here". Those components are {@code NON_NULL} because the service sets them
 *       null exactly when they don't apply. There is no present-null state here, which
 *       is checked against WeeklyReportShapeTest's goldens.</li>
 * </ul>
 * Both shapes state {@code playersPlayMultiplePerPeriod}, so the client renders from a
 * fact the server asserts rather than inferring the sport's rules from which arrays it
 * finds. {@code latestScoredWeek} is on both too.
 */
public final class WeeklyReportResponses {

    private WeeklyReportResponses() {}

    public sealed interface WeeklyReportBody permits UnavailableReport, AvailableReport {

        static WeeklyReportBody of(WeeklyReportService.Result r) {
            if (!r.available()) {
                return new UnavailableReport(false, r.season(), r.requestedSeason(), r.week(), r.latestScoredWeek(),
                        r.latestFinalWeek(), r.weekFinal(), r.sport().code(), r.playersPlayMultiplePerPeriod(),
                        r.reason(), List.of(), List.of(), List.of(), List.of());
            }
            return new AvailableReport(true, r.season(), r.requestedSeason(), r.week(), r.latestScoredWeek(),
                    r.latestFinalWeek(), r.weekFinal(), r.sport().code(), r.playersPlayMultiplePerPeriod(),
                    r.matchups().stream().map(m -> new MatchupRow(SideRow.of(m.home()), SideRow.of(m.away()))).toList(),
                    r.topPerformers() == null ? null : r.topPerformers().stream().map(p -> new PerformerRow(
                            p.playerId(), p.playerName(), p.position(), p.teamName(), p.points(), p.team(),
                            p.opponent(), p.isAway(), p.avatarId())).toList(),
                    r.bestNights() == null ? null : r.bestNights().stream().map(n -> new NightRow(
                            n.playerId(), n.playerName(), n.position(), n.teamName(), n.points(), n.date().toString(),
                            n.opponent(), n.isAway())).toList(),
                    r.bestWeek() == null ? null : r.bestWeek().stream().map(w -> new PlayerWeekRow(
                            w.playerId(), w.playerName(), w.position(), w.teamName(), w.totalPoints(),
                            w.gamesPlayed())).toList(),
                    r.basis(),
                    r.sectionsUnavailable() == null ? null : r.sectionsUnavailable().stream()
                            .map(u -> new SectionGapRow(u.section(), u.reason())).toList(),
                    r.awards().stream().map(a -> new AwardRow(a.kind(), a.teamName(), a.detail())).toList(),
                    // The honesty mechanism: an award that could not be computed says so
                    // rather than not appearing, which would read as "nobody qualified" (US5.4).
                    r.awardsOmitted().stream().map(o -> new OmittedAwardRow(o.kind(), o.reason())).toList());
        }
    }

    public record UnavailableReport(boolean available, int season, Integer requestedSeason, int week,
                                    int latestScoredWeek, int latestFinalWeek, boolean weekFinal, String sport,
                                    boolean playersPlayMultiplePerPeriod, String reason, List<MatchupRow> matchups,
                                    List<PerformerRow> topPerformers, List<AwardRow> awards,
                                    List<OmittedAwardRow> awardsOmitted) implements WeeklyReportBody {}

    public record AvailableReport(boolean available, int season, Integer requestedSeason, int week,
                                  int latestScoredWeek, int latestFinalWeek, boolean weekFinal, String sport,
                                  boolean playersPlayMultiplePerPeriod, List<MatchupRow> matchups,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) List<PerformerRow> topPerformers,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) List<NightRow> bestNights,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) List<PlayerWeekRow> bestWeek,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String basis,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) List<SectionGapRow> sectionsUnavailable,
                                  List<AwardRow> awards, List<OmittedAwardRow> awardsOmitted)
            implements WeeklyReportBody {}

    public record MatchupRow(SideRow home, SideRow away) {}

    /** {@code isMe} is present and false for a non-member, never absent (spec 013 T043). */
    public record SideRow(int rosterId, String teamName, String username, String avatarId, String record,
                          double points, boolean isMe) {
        static SideRow of(WeeklyReportService.Side s) {
            return new SideRow(s.rosterId(), s.teamName(), s.username(), s.avatarId(), s.record(), s.points(), s.isMe());
        }
    }

    /** {@code team}, {@code opponent}, {@code isAway} and {@code avatarId} are nullable: unknown is unknown. */
    public record PerformerRow(String playerId, String playerName, String position, String teamName, double points,
                               String team, String opponent, Boolean isAway, String avatarId) {}

    public record NightRow(String playerId, String playerName, String position, String teamName, double points,
                           String date, String opponent, Boolean isAway) {}

    /** A week total never travels without the games it covers (FR-002). */
    public record PlayerWeekRow(String playerId, String playerName, String position, String teamName,
                                double totalPoints, int gamesPlayed) {}

    public record SectionGapRow(String section, String reason) {}

    public record AwardRow(String kind, String teamName, String detail) {}

    public record OmittedAwardRow(String kind, String reason) {}
}
