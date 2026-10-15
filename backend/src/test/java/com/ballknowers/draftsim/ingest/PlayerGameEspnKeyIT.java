package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.espn.EspnIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 035 F10: an {@code espn:} key in stored weekly points is not a Sleeper player id. */
class PlayerGameEspnKeyIT extends EspnIT {

    @Autowired PlayerGameIngestService playerGames;

    @Test
    void rosteredPlayersSkipsEspnPrefixedKeys() {
        jdbc.update("insert into league (sport, season, sleeper_id, name, total_rosters, platform, platform_league_id) "
                + "values ('nba', 2099, 'espn-fba-9990001-2099', 'x', 2, 'ESPN', '9990001')");
        long league = jdbc.queryForObject("select id from league where sleeper_id = 'espn-fba-9990001-2099'", Long.class);
        jdbc.update("insert into roster_week_points (league_id, season, week, roster_id, starters_points, players_points) "
                + "values (?, 2099, 1, 1, 10, '{\"it035-1\": 5, \"espn:9990006\": 5}'::jsonb)", league);
        Set<String> ids = playerGames.rosteredPlayers(Sport.NBA, 2099);
        assertTrue(ids.contains("it035-1"));
        assertFalse(ids.contains("espn:9990006"), "an ESPN player the matcher refused has no Sleeper stat line");
    }
}
