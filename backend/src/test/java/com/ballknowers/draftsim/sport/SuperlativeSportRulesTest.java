package com.ballknowers.draftsim.sport;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/008-season-superlatives T008: {@code playedIn}, {@code isSuspended} and
 * {@code closeGameMargin}, fixtures copied from research.md R9 and R11's
 * measured shapes.
 */
class SuperlativeSportRulesTest {

    private static final ScoringProperties.SportScoring SCORING = new ScoringProperties.SportScoring(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85,
            Map.of("K", 3, "DEF", 4), 1.0, 30);

    private final FootballRules football = new FootballRules(new ScoringProperties(SCORING, SCORING));
    private final BasketballRules basketball = new BasketballRules(new ScoringProperties(SCORING, SCORING));

    private static Player player(Sport sport, String status, String injuryStatus) {
        return new Player(1, sport, "s1", "Test Player", List.of(Position.RB), "FA",
                status, injuryStatus, null, null);
    }

    // --- playedIn -----------------------------------------------------

    @Test
    void footballGmsActiveWithoutGpIsNotPlayed() {
        // research R9: Christian McCaffrey 2024 weeks 2-8, on IR.
        assertFalse(football.playedIn(Map.of("gms_active", 1.0)));
    }

    @Test
    void footballGpAboveZeroIsPlayed() {
        assertTrue(football.playedIn(Map.of("gp", 1.0, "pts_ppr", 16.7)));
    }

    @Test
    void basketballEmptyStatsIsNotPlayed() {
        // research R9: Joel Embiid's missed games.
        assertFalse(basketball.playedIn(Map.of()));
    }

    @Test
    void basketballBoxScoreWithoutGpIsPlayed() {
        assertTrue(basketball.playedIn(Map.of("pts", 30.0, "reb", 10.0)));
    }

    // --- isSuspended ----------------------------------------------------

    @Test
    void footballSusInjuryStatusIsSuspended() {
        Player p = player(Sport.NFL, "Active", "Sus");
        assertTrue(football.isSuspended(p));
    }

    @Test
    void footballStatusSuspendedAloneIsNotSuspended() {
        // Doesn't occur in Sleeper's data (research R11); football only reads injury_status.
        Player p = player(Sport.NFL, "Suspended", null);
        assertFalse(football.isSuspended(p));
    }

    @Test
    void basketballStatusSusIsSuspended() {
        Player p = player(Sport.NBA, "SUS", null);
        assertTrue(basketball.isSuspended(p));
    }

    @Test
    void basketballStatusSuspendedAloneIsNotSuspended() {
        Player p = player(Sport.NBA, "Suspended", null);
        assertFalse(basketball.isSuspended(p));
    }

    // --- closeGameMargin --------------------------------------------------

    @Test
    void footballMarginIsTen() {
        assertEquals(10.0, football.closeGameMargin(), 1e-9);
    }

    @Test
    void basketballMarginIsFifteen() {
        assertEquals(15.0, basketball.closeGameMargin(), 1e-9);
    }
}
