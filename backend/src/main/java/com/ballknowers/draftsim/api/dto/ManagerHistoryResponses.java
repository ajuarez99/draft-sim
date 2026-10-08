package com.ballknowers.draftsim.api.dto;

import com.ballknowers.draftsim.api.dto.StandingsResponses.CareerSeasonRow;
import com.ballknowers.draftsim.api.dto.StandingsResponses.StandingBase;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.ManagerCareerService;
import com.ballknowers.draftsim.engine.TransactionAnalysisService;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/managers/{id}/history} on the wire (specs/006-deeper-history-both-sports,
 * specs/021-codebase-cleanup). One {@code draftHistory} entry and one {@code careers}
 * entry per sport, never a total across sports. Mirrored by {@code ManagerHistory} in
 * web/src/api.ts.
 */
public final class ManagerHistoryResponses {

    private ManagerHistoryResponses() {}

    public record ManagerHistoryResponse(long managerId, String manager, String avatarId,
                                         List<DraftHistoryEntry> draftHistory, List<CareerResponse> careers) {}

    /**
     * One sport's fitted draft numbers. {@code positionalTilt} stays a map: its keys
     * are data, and the page renders them in the map's own order, unsorted
     * (ManagerHistory.tsx), so it is passed through as the same instance the profile
     * holds. The relative-reach pair is null with too few scoreable picks.
     */
    public record DraftHistoryEntry(Sport sport, double reachBias, Double relativeReachBias, Double relativeReachStdErr,
                                    Map<Position, Double> positionalTilt, int draftsObserved, int picksScored,
                                    String provenance) {}

    public record CareerResponse(Sport sport, int seasonsCounted, List<CareerSeasonRow> seasons,
                                 int wins, int losses, int ties, Double winRate,
                                 double pointsFor, double pointsAgainst, Double pointsPerSeason,
                                 Double averageEfficiency, int weeksCounted, int weeksExcluded,
                                 Double winsAboveExpected, int titles,
                                 List<UnavailableRow> unavailable, List<RankRow> ranks, WaiverTendencyRow waivers) {

        public static CareerResponse of(ManagerCareerService.CareerProfile c) {
            return new CareerResponse(c.sport(), c.seasonsCounted(),
                    c.seasons().stream()
                            // A career row's complete() is populated by forManager()'s join (T014).
                            .map(se -> new CareerSeasonRow(
                                    StandingBase.of(se.row(), Boolean.TRUE.equals(se.row().complete())), se.counted()))
                            .toList(),
                    c.wins(), c.losses(), c.ties(), c.winRate(), c.pointsFor(), c.pointsAgainst(),
                    c.pointsPerSeason(), c.averageEfficiency(), c.weeksCounted(), c.weeksExcluded(),
                    c.winsAboveExpected(), c.titles(),
                    c.unavailable().stream().map(u -> new UnavailableRow(u.figure(), u.reason())).toList(),
                    c.ranks().stream().map(r -> new RankRow(r.figure(), r.position(), r.population(),
                            r.leagueName(), r.sleeperLeagueId())).toList(),
                    WaiverTendencyRow.of(c.waivers()));
        }
    }

    public record UnavailableRow(String figure, String reason) {}

    public record RankRow(String figure, int position, int population, String leagueName, String sleeperLeagueId) {}

    /** {@code faab} is null for a manager with no FAAB season; the exclusions say why. */
    public record WaiverTendencyRow(double movesPerSeason, int seasonsCounted, FaabRow faab,
                                    List<ExcludedSeasonRow> faabExcludedSeasons) {
        static WaiverTendencyRow of(TransactionAnalysisService.WaiverTendency w) {
            TransactionAnalysisService.FaabTendency f = w.faab();
            return new WaiverTendencyRow(w.movesPerSeason(), w.seasonsCounted(),
                    f == null ? null : new FaabRow(f.typicalBidPct(), f.largestBidPct(), f.spentPerSeasonPct(),
                            f.claimsPerSeason(), f.bidSuccessRate()),
                    w.faabExcludedSeasons().stream()
                            .map(e -> new ExcludedSeasonRow(e.season(), e.leagueName(), e.reason())).toList());
        }
    }

    public record FaabRow(double typicalBidPct, double largestBidPct, double spentPerSeasonPct,
                          double claimsPerSeason, double bidSuccessRate) {}

    public record ExcludedSeasonRow(int season, String leagueName, String reason) {}
}
