package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The trap list in claude/adp-sources.md #7, pinned one by one: nicknames,
 * punctuation, suffix drift, defenses, stale teams, and the ambiguous case a
 * matcher must refuse rather than guess.
 */
class PlayerMatcherTest {

    private static Player p(long id, String name, Position pos, String team) {
        return new Player(id, Sport.NFL, "s" + id, name, List.of(pos), team, "Active", null, null, null);
    }

    @Test
    void punctuationAndDiacriticsAreIgnored() {
        var matcher = PlayerMatcher.build(Sport.NFL, List.of(p(1, "Amon-Ra St. Brown", Position.WR, "DET")));
        assertEquals(Optional.of(1L), matcher.match("Amon Ra St Brown", "WR", "DET"));
        assertEquals(Optional.of(1L), matcher.match("AMON-RA ST. BROWN", "WR", "DET"));
    }

    @Test
    void suffixesDoNotHaveToMatch() {
        var matcher = PlayerMatcher.build(Sport.NFL, List.of(p(1, "Deebo Samuel", Position.WR, "WAS")));
        assertEquals(Optional.of(1L), matcher.match("Deebo Samuel Sr.", "WR", "WAS"));
    }

    @Test
    void staleTeamStillMatchesOnNameAndPosition() {
        // Traded since the source's snapshot -- team must be a tiebreaker, never a required key.
        var matcher = PlayerMatcher.build(Sport.NFL, List.of(p(1, "Davante Adams", Position.WR, "LAR")));
        assertEquals(Optional.of(1L), matcher.match("Davante Adams", "WR", "NYJ"));
    }

    @Test
    void defenseMatchesOnTeamAbbreviationNotName() {
        var matcher = PlayerMatcher.build(Sport.NFL, List.of(p(1, "Seattle", Position.DEF, "SEA")));
        assertEquals(Optional.of(1L), matcher.match("Seattle Defense", "DEF", "SEA"));
    }

    @Test
    void ambiguousNameAloneIsRefusedNotGuessed() {
        var matcher = PlayerMatcher.build(Sport.NFL, List.of(
                p(1, "Mike Williams", Position.WR, "NYJ"),
                p(2, "Mike Williams", Position.RB, "LAC")));
        // Neither the exact (name, position) pair the source sent...
        assertEquals(Optional.of(1L), matcher.match("Mike Williams", "WR", "NYJ"));
        // ...nor a source that only gives a name with no position should guess between them.
        assertEquals(Optional.empty(), matcher.match("Mike Williams", null, null));
    }

    @Test
    void unknownPlayerIsUnmatched() {
        var matcher = PlayerMatcher.build(Sport.NFL, List.of(p(1, "Jahmyr Gibbs", Position.RB, "DET")));
        assertEquals(Optional.empty(), matcher.match("Some Rookie Nobody Rostered", "RB", "XYZ"));
    }

    @Test
    void aliasTableOverridesWhenPresent() {
        var matcher = PlayerMatcher.build(Sport.NFL,
                List.of(p(1, "Nathaniel Hackett Jr", Position.WR, "MIA")),
                Map.of(PlayerMatcher.normalize("Tank Dell"), 1L));
        assertEquals(Optional.of(1L), matcher.match("Tank Dell", "WR", "MIA"));
    }

    /**
     * multi-sport-and-rebrand.md Phase 5: DEF-by-team-abbreviation is a
     * football-only concept (basketball has no team-unit position), so an nba
     * player is never entered into byTeamForDefense even if a caller somehow
     * built a matcher over a mixed sport list. Not reachable through today's
     * only caller (FfcAdpService, football-only), but the guard is in build()
     * itself rather than assumed from context.
     */
    @Test
    void nbaPlayersAreNeverIndexedByTeamForDefense() {
        Player nbaPlayer = new Player(9L, Sport.NBA, "s9", "Some Center",
                List.of(Position.C), "DEN", "Active", null, null, null);
        // sport=NFL here (matching the raw FFC source's sport in the only real
        // caller) is what makes this test meaningful: rawPosition "DEF" resolves
        // to Position.DEF under NFL parsing, so the assertion below only holds
        // because build() guards byTeamForDefense on each player's own p.sport(),
        // not on the matcher's sport.
        var matcher = PlayerMatcher.build(Sport.NFL, List.of(nbaPlayer));
        // Even asking with rawPosition "DEF" must not resolve through the
        // team-abbreviation path for an nba-tagged player's team.
        assertEquals(Optional.empty(), matcher.match("Denver Defense", "DEF", "DEN"));
    }
}
