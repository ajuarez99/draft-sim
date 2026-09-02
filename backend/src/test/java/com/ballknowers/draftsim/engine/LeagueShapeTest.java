package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.domain.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LeagueShapeTest {

    @ParameterizedTest
    @ValueSource(ints = {8, 10, 12, 14})
    void theStandardShapeMatchesTheLeaguesThisToolIsActuallyPointedAt(int teams) {
        LeagueSettings s = LeagueShape.standard(teams).toSettings();

        assertEquals(teams, s.teams());
        assertEquals(15, s.rounds());
        assertEquals(1, s.slotCount("QB"));
        assertEquals(2, s.slotCount("RB"));
        assertEquals(2, s.slotCount("WR"));
        assertEquals(1, s.slotCount("TE"));
        assertEquals(2, s.flexSlots());
        assertEquals(5, s.benchSlots());
        assertEquals(10, s.totalStarters());
        assertEquals(1, s.dedicatedStarters().get(Position.K));
        assertEquals(1, s.dedicatedStarters().get(Position.DEF));
    }

    /**
     * The cap is 14, not 16, and the reason is board depth rather than taste —
     * see the doc on {@link LeagueShape#SUPPORTED_TEAM_COUNTS}. Odd sizes are
     * out too: Sleeper snake leagues in this project are all even, and every
     * size shipped is one the UI renders in a single dropdown.
     */
    @Test
    void unsupportedTeamCountsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> LeagueShape.standard(16));
        assertThrows(IllegalArgumentException.class, () -> LeagueShape.standard(11));
        assertThrows(IllegalArgumentException.class, () -> LeagueShape.standard(0));
    }

    @Test
    void aShapeIsImmutableEvenIfItsCallerKeepsTheList() {
        List<String> mutable = new java.util.ArrayList<>(LeagueShape.STANDARD_ROSTER);
        LeagueShape shape = new LeagueShape(12, 15, mutable, 1.0);
        mutable.clear();
        assertEquals(15, shape.rosterPositions().size());
        assertThrows(UnsupportedOperationException.class, () -> shape.rosterPositions().add("QB"));
    }

    @Test
    void totalPicksIsTeamsTimesRounds() {
        assertEquals(210, LeagueShape.standard(14).totalPicks());
        assertEquals(120, LeagueShape.standard(8).totalPicks());
    }
}
