package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueRepository;

import java.util.List;
import java.util.OptionalInt;

/**
 * "Which season does this sport still need projections for?" -- one rule, two callers.
 *
 * <p>Spec 030 C4 gave the daily refresh's {@code SEASON_PROJECTIONS} step this rule; spec 036's
 * {@code PROJECTION_SNAPSHOT} step needs the identical gate, and two copies would drift (the
 * "two implementations of one rule" class in this repo's memory). "Current" is the newest season
 * any league of the sport is in; it needs projections unless every league in that season is {@code
 * COMPLETE}.
 */
public final class ProjectionSeason {

    private ProjectionSeason() {}

    /** The newest season of the sport with a league that is not complete, or empty. */
    public static OptionalInt current(LeagueRepository leagues, SeasonStateService seasonState, Sport sport) {
        List<LeagueRepository.LeagueRow> ofSport = leagues.all().stream()
                .filter(l -> l.sport() == sport).toList();
        int season = ofSport.stream().mapToInt(LeagueRepository.LeagueRow::season).max().orElse(-1);
        boolean active = ofSport.stream().filter(l -> l.season() == season)
                .anyMatch(l -> seasonState.stateOf(l) != SeasonStateService.SeasonState.COMPLETE);
        return active ? OptionalInt.of(season) : OptionalInt.empty();
    }
}
