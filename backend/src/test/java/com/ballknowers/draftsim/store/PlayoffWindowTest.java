package com.ballknowers.draftsim.store;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/017-nba-schedule-grid T006: the last playoff week and the codes for refusing to name one
 * (research R5). Round type 0 is the only one measured against a league's own last_scored_leg
 * with a single-week-per-round bracket; anything else is refused rather than guessed.
 */
class PlayoffWindowTest {

    private static LeagueRepository.PlayoffFormat fmt(int start, int teams, Integer roundType) {
        return new LeagueRepository.PlayoffFormat(teams, start, 0, false, false, roundType);
    }

    private static void assertLast(int expected, int start, int teams, Integer type) {
        LeagueRepository.PlayoffFormat f = fmt(start, teams, type);
        assertEquals(OptionalInt.of(expected), f.lastPlayoffWeek(), start + "/" + teams + "/" + type);
        assertEquals(Optional.empty(), f.playoffWindowRefusal());
    }

    private static void assertRefused(String code, int start, int teams, Integer type) {
        LeagueRepository.PlayoffFormat f = fmt(start, teams, type);
        assertEquals(OptionalInt.empty(), f.lastPlayoffWeek(), start + "/" + teams + "/" + type);
        assertEquals(Optional.of(code), f.playoffWindowRefusal());
    }

    @Test
    void roundTypeZeroGivesStartPlusCeilLog2TeamsMinusOne() {
        assertLast(22, 20, 6, 0);
        assertLast(21, 19, 6, 0);   // NBA 2025, measured
        assertLast(24, 22, 6, 0);   // NBA 2024, measured
        assertLast(17, 15, 6, 0);   // (Foot) Ball Knowers 2025, measured
        assertLast(16, 15, 4, 0);
        assertLast(15, 15, 2, 0);
    }

    @Test
    void anAbsentRoundTypeIsUnknownNotZero() {
        assertRefused("ROUND_TYPE_UNKNOWN", 15, 6, null);
    }

    @Test
    void otherRoundTypesAreRefusedAsUnsupported() {
        assertRefused("ROUND_TYPE_UNSUPPORTED", 15, 6, 1);   // West Coast Fantasy Football's real setting
        assertRefused("ROUND_TYPE_UNSUPPORTED", 15, 6, 2);
    }

    @Test
    void noPlayoffStartIsRefused() {
        assertRefused("NO_START", 0, 6, 0);
    }

    @Test
    void tooFewPlayoffTeamsIsRefused() {
        assertRefused("TOO_FEW_TEAMS", 15, 1, 0);
    }
}
