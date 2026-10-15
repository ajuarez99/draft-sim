package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.ballknowers.draftsim.ingest.PlayerMatcher.Outcome.Kind.AMBIGUOUS;
import static com.ballknowers.draftsim.ingest.PlayerMatcher.Outcome.Kind.ESPN_ONLY_POSITION;
import static com.ballknowers.draftsim.ingest.PlayerMatcher.Outcome.Kind.MATCHED;
import static com.ballknowers.draftsim.ingest.PlayerMatcher.Outcome.Kind.UNMATCHED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlayerMatcherStrictTest {

    private static Player nfl(long id, String name, Position pos, String team) {
        return new Player(id, Sport.NFL, "s" + id, name, List.of(pos), team, "Active", null, null, null);
    }

    private final List<Player> table = List.of(
            nfl(1, "David Johnson", Position.RB, "ARI"),
            nfl(2, "David Johnson", Position.RB, "PIT"),          // the same name and position: a different man
            nfl(3, "Lamar Jackson", Position.QB, "BAL"),
            nfl(4, "Tyler Conklin", Position.TE, "NYJ"),
            nfl(5, "Ryan Izzo", Position.TE, "NYJ"),
            nfl(6, "Seattle Defense", Position.DEF, "SEA"));

    @Test
    void twoPlayersWithOneNameAndPositionAreAmbiguousNotTheFirst() {
        PlayerMatcher m = PlayerMatcher.buildStrict(Sport.NFL, table, Map.of());
        PlayerMatcher.Outcome o = m.matchStrict("David Johnson", "RB", "ARI", null);
        assertEquals(AMBIGUOUS, o.kind());
        assertNull(o.playerId(), "never carries a guessed id");
    }

    @Test
    void aHintNamingExactlyOneCandidateBreaksTheTie() {
        PlayerMatcher m = PlayerMatcher.buildStrict(Sport.NFL, table, Map.of(), Map.of(1L, 111L, 2L, 222L));
        PlayerMatcher.Outcome o = m.matchStrict("David Johnson", "RB", null, 222L);
        assertEquals(MATCHED, o.kind());
        assertEquals(2L, o.playerId());
    }

    @Test
    void aHintThatNamesNoCandidateOrBothDoesNotBreakTheTie() {
        PlayerMatcher m = PlayerMatcher.buildStrict(Sport.NFL, table, Map.of(), Map.of(1L, 111L, 2L, 111L));
        assertEquals(AMBIGUOUS, m.matchStrict("David Johnson", "RB", null, 111L).kind(), "both carry it");
        assertEquals(AMBIGUOUS, m.matchStrict("David Johnson", "RB", null, 999L).kind(), "neither carries it");
    }

    @Test
    void aHintNamingAPlayerWithADifferentNameIsIgnored() {
        // The Conklin to Izzo case: Sleeper's espn_id crosswalk pointed one man at another. The hint is
        // only ever consulted among same-name candidates, so it cannot pull Izzo in for Conklin.
        PlayerMatcher m = PlayerMatcher.buildStrict(Sport.NFL, table, Map.of(), Map.of(5L, 4444L));
        PlayerMatcher.Outcome o = m.matchStrict("Tyler Conklin", "TE", "NYJ", 4444L);
        assertEquals(MATCHED, o.kind());
        assertEquals(4L, o.playerId(), "matched by name, not by the crosswalk");
    }

    @Test
    void anEspnPositionThisSportDoesNotModelIsNeverMatchedByNameAlone() {
        PlayerMatcher m = PlayerMatcher.buildStrict(Sport.NFL, table, Map.of());
        // ESPN's cornerback Lamar Jackson must not become the QB.
        assertEquals(ESPN_ONLY_POSITION, m.matchStrict("Lamar Jackson", "CB", "BAL", null).kind());
        assertEquals(ESPN_ONLY_POSITION, m.matchStrict("Lamar Jackson", "OTHER_14", null, null).kind());
        // With no position at all the name alone may still match.
        assertEquals(MATCHED, m.matchStrict("Lamar Jackson", null, null, null).kind());
    }

    @Test
    void aDefenseIsMatchedByItsTeamNeverItsName() {
        PlayerMatcher m = PlayerMatcher.buildStrict(Sport.NFL, table, Map.of());
        PlayerMatcher.Outcome o = m.matchStrict("Seahawks D/ST", "DEF", "SEA", null);
        assertEquals(MATCHED, o.kind());
        assertEquals(6L, o.playerId());
        assertEquals(UNMATCHED, m.matchStrict("Seattle Defense", "DEF", "DEN", null).kind());
    }

    @Test
    void aGivenModelledPositionNeverFallsBackToTheNameAlone() {
        // Spec 035 code review S5: ESPN's kicker "Lamar Jackson" (a modelled position, K) is not the app's QB.
        List<Player> withKicker = new java.util.ArrayList<>(table);
        PlayerMatcher m = PlayerMatcher.buildStrict(Sport.NFL, withKicker, Map.of());
        assertEquals(UNMATCHED, m.matchStrict("Lamar Jackson", "K", null, null).kind(),
                "one app player has the name, but at QB: a position mismatch is a miss, not a match");
        assertEquals(UNMATCHED, m.matchStrict("Lamar Jackson", "RB", null, null).kind());
        assertEquals(UNMATCHED, m.matchStrict("Nobody Atall", "RB", null, null).kind());
        // The same name at the right position, and a name with no position at all, still match.
        assertEquals(MATCHED, m.matchStrict("Lamar Jackson", "QB", null, null).kind());
        assertEquals(MATCHED, m.matchStrict("Lamar Jackson", null, null, null).kind());
    }

    @Test
    void theExistingBuildStillReturnsTheFirstIdForADuplicate() {
        // Pins today's behaviour (FR-013): the old matcher is unchanged, the first of two wins.
        PlayerMatcher old = PlayerMatcher.build(Sport.NFL, table);
        assertEquals(1L, old.match("David Johnson", "RB", "ARI").orElseThrow());
    }
}
