package com.ballknowers.draftsim.api.dto;

import com.ballknowers.draftsim.engine.ExpectedWinsService;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService;

import java.util.List;

/**
 * Season superlatives and the commissioner's conduct list on the wire
 * (specs/008-season-superlatives contracts/superlatives-api.md, specs/021-codebase-cleanup).
 * Mirrored by {@code Superlatives}, {@code SuperlativeDetail} and {@code ConductList} in
 * web/src/api/superlatives.ts.
 *
 * <p>Several fields are legitimately null, which is why these were never {@code Map.of}:
 * reason, coverage, emptyReason, value, unit, regularSeasonEnd, managerId and avatarId.
 * A record has no such trap.
 */
public final class SuperlativeResponses {

    private SuperlativeResponses() {}

    public record SuperlativesResponse(boolean available, String reason, int season, Integer requestedSeason,
                                       String sport, Integer throughWeek, int weeksScored, Integer regularSeasonEnd,
                                       boolean early, int earlyThresholdWeeks, Double closeGameMargin,
                                       List<Integer> suspensionWeeksObserved, boolean commissionerListAvailable,
                                       String leagueSleeperId, List<SuperlativeRow> superlatives) {

        public static SuperlativesResponse of(SeasonSuperlativesService.Result r) {
            return new SuperlativesResponse(r.available(), r.reason(), r.season(), r.requestedSeason(),
                    r.sport().code(), r.throughWeek(), r.weeksScored(), r.regularSeasonEnd(), r.early(),
                    r.earlyThresholdWeeks(), r.closeGameMargin(), r.suspensionWeeksObserved(),
                    r.commissionerListAvailable(), r.leagueSleeperId(),
                    r.superlatives().stream().map(SuperlativeRow::of).toList());
        }
    }

    /**
     * One award. {@code playerHolders} and {@code playerStandings} are JABARI_SMITH_JR
     * only, and {@code []} on every other kind. {@code coverage} is null when the kind
     * doesn't report it.
     */
    public record SuperlativeRow(String kind, boolean available, String reason, boolean early, Double value,
                                 String unit, List<HolderRow> holders, String emptyReason, List<DetailOut> detail,
                                 CoverageRow coverage, List<PlayerHolderRow> playerHolders,
                                 List<StandingRow> standings, List<PlayerStandingRow> playerStandings) {

        static SuperlativeRow of(SeasonSuperlativesService.Superlative s) {
            SeasonSuperlativesService.Coverage c = s.coverage();
            return new SuperlativeRow(s.kind().name(), s.available(), s.reason(), s.early(), s.value(), s.unit(),
                    s.holders().stream().map(HolderRow::of).toList(),
                    s.emptyReason(),
                    s.detail().stream().map(DetailOut::of).toList(),
                    c == null ? null : new CoverageRow(c.weeksCovered(), c.weeksExcluded(), c.reasons()),
                    s.playerHolders().stream().map(ph -> new PlayerHolderRow(ph.playerId(), ph.playerName(),
                            ph.position(), ph.team(), ph.adds(), ph.distinctTeams())).toList(),
                    s.standings().stream().map(st -> new StandingRow(st.rank(), HolderRow.of(st.team()), st.value(),
                            st.note(), st.hasValue(), st.missingReason())).toList(),
                    s.playerStandings().stream().map(ps -> new PlayerStandingRow(ps.rank(), ps.playerId(),
                            ps.playerName(), ps.position(), ps.team(), ps.adds(), ps.distinctTeams())).toList());
        }
    }

    public record HolderRow(int rosterId, Long managerId, String teamName, String username, String avatarId) {
        static HolderRow of(SeasonSuperlativesService.Holder h) {
            return new HolderRow(h.rosterId(), h.managerId(), h.teamName(), h.username(), h.avatarId());
        }
    }

    public record CoverageRow(int weeksCovered, int weeksExcluded, List<String> reasons) {}

    /** {@code rank}, {@code value}, {@code note} and {@code missingReason} are null on some rows. */
    public record StandingRow(Integer rank, HolderRow team, Double value, String note, boolean hasValue,
                              String missingReason) {}

    /** {@code position} and {@code team} are nullable: a free agent has no team. */
    public record PlayerStandingRow(int rank, String playerId, String playerName, String position, String team,
                                    int adds, int distinctTeams) {}

    public record PlayerHolderRow(String playerId, String playerName, String position, String team, int adds,
                                  int distinctTeams) {}

    /**
     * One detail row. There is one shape per {@code type} discriminator
     * (contracts/superlatives-api.md's detail table), and every record carries
     * {@code type} as an explicit first component, the same hand-written key the map
     * code emitted. That's deliberate rather than {@code @JsonTypeInfo} (plan-review
     * finding 2). The switch is exhaustive over the engine's sealed type, so a new
     * engine subtype fails to compile here instead of quietly serializing as {}.
     */
    public sealed interface DetailOut
            permits WeekScoreRow, GameRow, LuckRow, BenchTotalRow, PickupRow, AbsenceRow, ConductRow, AddRow {

        static DetailOut of(SeasonSuperlativesService.DetailRow d) {
            return switch (d) {
                case SeasonSuperlativesService.WeekScoreDetail w ->
                        new WeekScoreRow("WEEK_SCORE", w.week(), w.rosterId(), w.points());
                case SeasonSuperlativesService.GameDetail g ->
                        new GameRow("GAME", g.week(), g.rosterId(), g.opponentRosterId(), g.opponentTeamName(),
                                g.points(), g.opponentPoints(), g.margin());
                case SeasonSuperlativesService.LuckDetail l ->
                        new LuckRow("LUCK", l.rosterId(), l.actualWins(), l.expectedWins(), l.winsAboveExpected(),
                                l.swingWeeks(), l.fromWeek(), l.throughWeek(), l.reading());
                case SeasonSuperlativesService.BenchTotalDetail b ->
                        new BenchTotalRow("BENCH_TOTAL", b.rosterId(), b.pointsLeft(), b.weeksCounted(), b.fromWeek(),
                                b.throughWeek(), b.biggestWeek() == null ? null
                                        : new BiggestWeekRow(b.biggestWeek().week(), b.biggestWeek().pointsLeft()));
                case SeasonSuperlativesService.PickupDetail p ->
                        new PickupRow("PICKUP", p.playerId(), p.playerName(), p.position(), p.rosterId(), p.addedWeek(),
                                p.addType(), p.startedWeeks(), p.points());
                case SeasonSuperlativesService.AbsenceDetail a ->
                        new AbsenceRow("ABSENCE", a.playerId(), a.playerName(), a.position(), a.rosterId(),
                                a.gamesMissed(), a.weeksAffected(), a.pointsPerGame(), a.estimatedPointsLost(),
                                a.estimated());
                case SeasonSuperlativesService.ConductDetail c ->
                        new ConductRow("CONDUCT", c.playerId(), c.playerName(), c.rosterId(), c.source(), c.weeks(),
                                c.reason());
                case SeasonSuperlativesService.AddDetail a ->
                        new AddRow("ADD", a.playerId(), a.week(), a.rosterId(), a.teamName(), a.avatarId(), a.addType(),
                                a.faabBid());
            };
        }
    }

    public record WeekScoreRow(String type, int week, int rosterId, double points) implements DetailOut {}

    public record GameRow(String type, int week, int rosterId, int opponentRosterId, String opponentTeamName,
                          double points, double opponentPoints, double margin) implements DetailOut {}

    public record LuckRow(String type, int rosterId, double actualWins, double expectedWins, double winsAboveExpected,
                          List<ExpectedWinsService.SwingWeek> swingWeeks, int fromWeek, int throughWeek,
                          String reading) implements DetailOut {}

    /** {@code biggestWeek} is null when no week left points on the bench. */
    public record BenchTotalRow(String type, int rosterId, double pointsLeft, int weeksCounted, int fromWeek,
                                int throughWeek, BiggestWeekRow biggestWeek) implements DetailOut {}

    public record BiggestWeekRow(int week, double pointsLeft) {}

    public record PickupRow(String type, String playerId, String playerName, String position, int rosterId,
                            int addedWeek, String addType, List<Integer> startedWeeks, double points)
            implements DetailOut {}

    public record AbsenceRow(String type, String playerId, String playerName, String position, int rosterId,
                             int gamesMissed, int weeksAffected, double pointsPerGame, double estimatedPointsLost,
                             boolean estimated) implements DetailOut {}

    public record ConductRow(String type, String playerId, String playerName, int rosterId, String source,
                             List<Integer> weeks, String reason) implements DetailOut {}

    /** {@code faabBid} is null for a free-agent add. */
    public record AddRow(String type, String playerId, int week, int rosterId, String teamName, String avatarId,
                         String addType, Integer faabBid) implements DetailOut {}

    /** The commissioner's conduct list for one league-season (spec amendment 9). */
    public record ConductListResponse(boolean canEdit, boolean commissionerKnown, List<ConductEntryRow> entries) {}

    /** {@code playerName} is "Unknown player" for an id the player table lacks; {@code addedBy} is nullable. */
    public record ConductEntryRow(long id, String playerId, String playerName, String reason, int appliesFromWeek,
                                  String addedBy, String createdAt) {}
}
