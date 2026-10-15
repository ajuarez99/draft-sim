package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EspnLeagueRefTest {

    @Test
    void theRealLeaguesTeamLinkMapsEspnSeasonToTheAppSeason() {
        // The user's real link: ESPN labels the 2026-27 NBA season 2027; the app calls it 2026.
        EspnLeagueRef r = EspnLeagueRef.parse(
                "https://fantasy.espn.com/basketball/team?leagueId=1314454666&teamId=8&seasonId=2027", 1999);
        assertEquals(Sport.NBA, r.sport());
        assertEquals("fba", r.game());
        assertEquals("1314454666", r.leagueId());
        assertEquals(2026, r.season());
        assertEquals(2027, r.espnSeason());
        assertEquals(8, r.teamId());
        assertEquals("espn-fba-1314454666-2026", r.key());
    }

    @Test
    void footballSeasonsAreTheSameNumberOnBothSides() {
        EspnLeagueRef r = EspnLeagueRef.parse("https://fantasy.espn.com/football/league?leagueId=42&seasonId=2025", 1999);
        assertEquals(Sport.NFL, r.sport());
        assertEquals(2025, r.season());
        assertEquals(2025, r.espnSeason());
        assertEquals("espn-ffl-42-2025", r.key());
        assertNull(r.teamId());
    }

    @Test
    void everyAcceptedPathForm() {
        for (String link : new String[] {
                "https://fantasy.espn.com/football/league?leagueId=7&seasonId=2024",
                "fantasy.espn.com/football/league?leagueId=7&seasonId=2024",
                "https://fantasy.espn.com/football/league/standings?leagueId=7&seasonId=2024",
                "https://fantasy.espn.com/football/team?leagueId=7&teamId=3&seasonId=2024",
                "http://fantasy.espn.com/football/league?seasonId=2024&leagueId=7&view=x",
                "  https://fantasy.espn.com/football/league?leagueId=7&seasonId=2024  "}) {
            EspnLeagueRef r = EspnLeagueRef.parse(link, 1999);
            assertEquals("7", r.leagueId(), link);
            assertEquals(2024, r.season(), link);
        }
    }

    @Test
    void aMissingSeasonIdUsesTheAppSeasonTheCallerPassed() {
        EspnLeagueRef r = EspnLeagueRef.parse("https://fantasy.espn.com/basketball/league?leagueId=9", 2031);
        assertEquals(2031, r.season());
        assertEquals(2032, r.espnSeason());
    }

    @Test
    void junkIsRejectedWithAFixedMessageThatDoesNotEchoTheInput() {
        for (String junk : new String[] {null, "", "   ", "not a url", "https://example.com/football/league?leagueId=7",
                "https://fantasy.espn.com/hockey/league?leagueId=7", "https://fantasy.espn.com/football/league",
                "https://fantasy.espn.com/football/league?leagueId=abc", "https://fantasy.espn.com/football/league?leagueId=7&seasonId=20x",
                "https://fantasy.espn.com/football/league?leagueId=7&teamId=zz", "x".repeat(700)}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EspnLeagueRef.parse(junk, 2025), "input=" + junk);
            assertEquals("MALFORMED_REF", e.getMessage());
        }
    }

    @Test
    void ofRejectsABadId() {
        assertThrows(IllegalArgumentException.class, () -> EspnLeagueRef.of("12ab", Sport.NFL, 2025));
        assertEquals("espn-fba-5-2026", EspnLeagueRef.of("5", Sport.NBA, 2026).key());
    }

    @Test
    void seasonMappingIsExplicitPerSportAndInvertible() {
        for (int app = 2020; app <= 2030; app++) {
            assertEquals(app, EspnSeasons.toApp(Sport.NBA, EspnSeasons.toEspn(Sport.NBA, app)));
            assertEquals(app, EspnSeasons.toApp(Sport.NFL, EspnSeasons.toEspn(Sport.NFL, app)));
        }
        assertEquals(2026, EspnSeasons.toApp(Sport.NBA, 2027));
        assertEquals(2027, EspnSeasons.toEspn(Sport.NBA, 2026));
        assertEquals(2026, EspnSeasons.toApp(Sport.NFL, 2026));
    }

    @Test
    void currentAppSeasonFollowsEachSportsCalendar() {
        assertEquals(2026, EspnSeasons.currentAppSeason(Sport.NBA, LocalDate.of(2026, 10, 14)));
        assertEquals(2025, EspnSeasons.currentAppSeason(Sport.NBA, LocalDate.of(2026, 3, 1)));
        assertEquals(2026, EspnSeasons.currentAppSeason(Sport.NFL, LocalDate.of(2026, 10, 14)));
        assertEquals(2025, EspnSeasons.currentAppSeason(Sport.NFL, LocalDate.of(2026, 1, 20)));
    }
}
