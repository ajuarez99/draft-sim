package com.ballknowers.draftsim.api.dto;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * A standings row on the wire, specs/021-codebase-cleanup. One base shape, two
 * emitted shapes, because the same roster-season is sent two ways: a league's history
 * row adds its rank cell and caller-relative fields, and a manager's career row adds
 * {@code counted}. Neither shape has the other's extras, so they are separate records
 * (plan-review finding 2), sharing the base through {@link JsonUnwrapped}.
 *
 * <p>Mirrored by {@code StandingRow} / {@code CareerSeason} in web/src/api/leagueHistory.ts and web/src/api/managerHistory.ts.
 */
public final class StandingsResponses {

    private StandingsResponses() {}

    /**
     * The fields every standings row carries. {@code season} through {@code complete}
     * are null on a league's own history (the page already knows them), and set on a
     * manager's career rows, which span leagues, seasons and sports.
     */
    public record StandingBase(long leagueId, int rosterId, Long managerId, String manager, String avatarId,
                               Integer wins, Integer losses, Integer ties, Double pointsFor, Double pointsAgainst,
                               boolean champion, Integer season, String sleeperLeagueId, Sport sport,
                               String leagueName, Boolean complete) {

        /**
         * @param seasonComplete resolved by the caller, never read off {@code r.complete()}
         *                       for a league's own history, where that field is null (see
         *                       LeagueHistoryController#history). It gates {@code champion},
         *                       so a final placement of 1 in an unfinished season is never a
         *                       trophy.
         */
        public static StandingBase of(RosterSeasonRepository.StandingRow r, boolean seasonComplete) {
            return new StandingBase(r.leagueId(), r.rosterId(), r.managerId(), r.managerName(), r.avatarId(),
                    r.wins(), r.losses(), r.ties(), r.pointsFor(), r.pointsAgainst(),
                    r.finalPlacement() != null && r.finalPlacement() == 1 && seasonComplete,
                    r.season(), r.sleeperLeagueId(), r.sport(), r.leagueName(), r.complete());
        }
    }

    /**
     * A row in a league's history. {@code rankStatus} is on every row, and
     * {@code finalRank}/{@code finalRankWeek} are always present, null unless RANKED.
     * {@code teamName} is null for an unowned roster.
     */
    public record HistoryStandingRow(@JsonUnwrapped StandingBase base, String rankStatus, Integer finalRank,
                                     Integer finalRankWeek, String teamName, boolean isMe) {}

    /** A row in a manager's career: the base plus whether its weeks counted (T035). */
    public record CareerSeasonRow(@JsonUnwrapped StandingBase base, boolean counted) {}
}
