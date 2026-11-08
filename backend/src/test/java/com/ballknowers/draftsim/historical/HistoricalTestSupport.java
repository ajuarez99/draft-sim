package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.engine.GameScoringService;
import com.ballknowers.draftsim.engine.LeagueShape;
import com.ballknowers.draftsim.engine.RealizedLineupService;
import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.GameLine;
import com.ballknowers.draftsim.sport.BasketballRules;
import com.ballknowers.draftsim.sport.SportRules;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Shared fixtures for the spec 053 replay and comparison unit tests: no Spring, no database. */
final class HistoricalTestSupport {

    private HistoricalTestSupport() {}

    static final RealizedLineupService LINEUPS = new RealizedLineupService();
    static final SeasonReplay REPLAY = new SeasonReplay(new GameScoringService(), LINEUPS);
    static final LocalDate DAY = LocalDate.of(1995, 11, 6);

    static SportRules rules() {
        var sc = new ScoringProperties.SportScoring(
                new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
                12.0, 3.0, 60.0, 0.15, 6, 0.85, Map.of(), 1.0, 30);
        return new BasketballRules(new ScoringProperties(null, sc));
    }

    /** Football rules (QB, 2 RB, 2 WR, TE, FLEX) for the spec 056 comparison cases. */
    static SportRules nflRules() {
        var sc = new ScoringProperties.SportScoring(
                new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
                12.0, 3.0, 60.0, 0.15, 6, 0.85, Map.of("K", 3, "DEF", 4), 1.0, 30);
        return new com.ballknowers.draftsim.sport.FootballRules(new ScoringProperties(sc, null));
    }

    static LeagueSettings nflSettings(int teams) {
        return new LeagueSettings(Sport.NFL, teams, 15, List.of("QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "BN", "BN",
                "BN", "BN", "BN", "BN", "BN", "BN"), 0.0, 0);
    }

    static LeagueSettings settings(int teams) {
        return new LeagueSettings(Sport.NBA, teams, 14, LeagueShape.NBA_ROSTER, 0.0, 0);
    }

    static HistoricalPlayerCard card(long id, String... positions) {
        return new HistoricalPlayerCard(id, "P" + id, List.of(positions), null, null, (int) id, 1, null);
    }

    /** One played game with only points and turnovers set. */
    static GameLine game(long player, long gameId, int week, int dayOffset, Integer pts, Integer to) {
        return new GameLine(player, gameId, 1995, DAY.plusDays(dayOffset), week, "AAA", pts, null, null, null, null,
                to, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    static GameLine game(long player, long gameId, int week, int pts) {
        return game(player, gameId, week, 0, pts, null);
    }
}
