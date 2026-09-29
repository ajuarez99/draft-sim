package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pure half of the per-game ingest: turning one upstream entry into a row,
 * which is where every "we invented a fact" bug would live
 * (specs/005-daily-weekly-top-players, US1). The walk itself is covered by
 * {@link PlayerGameWeekIngestTest} since specs/009 rebuilt it on per-week data;
 * {@code is_away} is now an argument (from the schedule), not read off the entry.
 */
class PlayerGameIngestServiceTest {

    private static Map<String, Object> entry(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> fullEntry() {
        return entry(
                "game_id", "1261029460694540288",
                "date", "2025-11-17",
                "week", 5,
                "team", "PHI",
                "opponent", "CHI",
                "stats", Map.of("pts", 36.0, "reb", 18.0, "ast", 13.0));
    }

    // ------------------------------------------------------------- toRow

    @Test
    void mapsAGameToARow() {
        PlayerGameRepository.Row r = PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", fullEntry(), Boolean.FALSE);

        assertNotNull(r);
        assertEquals("1658", r.sleeperPlayerId());
        assertEquals("1261029460694540288", r.gameId());
        assertEquals("2025-11-17", r.gameDate().toString());
        assertEquals("CHI", r.opponent());
        assertEquals(Boolean.FALSE, r.isAway());
        assertEquals(5, r.week());
    }

    /**
     * Research R6, and the spec's postponed-game edge case in one assertion.
     *
     * <p>The week comes from the entry's own field. If it were derived from the
     * date, a game moved into another week would land in the week it was
     * scheduled rather than the week it was played -- and this service would
     * need to know about calendars, which it should not.
     */
    @Test
    void theWeekComesFromTheEntryNotTheDate() {
        Map<String, Object> postponed = fullEntry();
        postponed.put("week", 9);
        postponed.put("date", "2025-11-17");

        PlayerGameRepository.Row r = PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", postponed, null);

        assertEquals(9, r.week(), "the entry said week 9; the date must not override it");
        assertEquals("2025-11-17", r.gameDate().toString());
    }

    /**
     * A game with no id cannot be deduplicated, one with no date cannot be a
     * "night", and one with no week cannot be placed. Dropping it is honest;
     * inventing any of the three is not.
     */
    @Test
    void anEntryMissingAnIdentifyingFieldIsDroppedRatherThanInvented() {
        for (String missing : new String[] {"game_id", "date", "week"}) {
            Map<String, Object> e = fullEntry();
            e.remove(missing);
            assertNull(PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e, null),
                    "an entry without " + missing + " must not become a row");
        }
    }

    /** A week that is not a number is missing, not zero. */
    @Test
    void aNonNumericWeekIsTreatedAsMissing() {
        Map<String, Object> e = fullEntry();
        e.put("week", "five");
        assertNull(PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e, null));
    }

    /** An unparseable date is dropped rather than defaulted to today. */
    @Test
    void anUnparseableDateIsDropped() {
        Map<String, Object> e = fullEntry();
        e.put("date", "Nov 17, 2025");
        assertNull(PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e, null));
    }

    /**
     * research R9, amended from spec 005: an entry with a {@code stats} key
     * that is not even a map is unusable (dropped), but an EMPTY map is a
     * well-formed entry now -- exactly how basketball marks a missed game.
     * Whether it counts as a game played is {@code SportRules.playedIn}'s call
     * during the walk, not {@code toRow}'s.
     */
    @Test
    void anEntryWithAnEmptyStatsMapStillProducesARowForTheWalkToClassify() {
        Map<String, Object> e = fullEntry();
        e.put("stats", Map.of());

        PlayerGameRepository.Row r = PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e, null);

        assertNotNull(r, "an empty stats map is a missed game, not a malformed entry");
    }

    @Test
    void anEntryWithNoStatsKeyAtAllIsDropped() {
        Map<String, Object> e = fullEntry();
        e.remove("stats");
        assertNull(PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e, null));
    }

    /**
     * FR-006: a missing opponent still stores the game. The date is the fact
     * the section is built on; the opponent is the nicety, and a row without it
     * is better than no row or a guessed one.
     */
    @Test
    void aMissingOpponentStillStoresTheGame() {
        Map<String, Object> e = fullEntry();
        e.remove("opponent");

        PlayerGameRepository.Row r = PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e, null);

        assertNotNull(r);
        assertNull(r.opponent());
        assertNull(r.isAway(), "unknown must stay unknown rather than defaulting to home");
    }
}
