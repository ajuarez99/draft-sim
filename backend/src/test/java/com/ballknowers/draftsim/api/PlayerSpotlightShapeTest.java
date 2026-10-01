package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.PlayerSpotlightService;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.Performance;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.Period;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.Section;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.TrendingEntry;
import com.ballknowers.draftsim.engine.PlayerSpotlightService.TrendingSection;
import com.ballknowers.draftsim.engine.SpotlightOwnership.Ownership;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The response shape from contracts/player-spotlight-api.md, asserted against the map the
 * controller really builds, with no database (specs/014-home-player-spotlight, T010). Mirrors
 * {@code WeeklyReportShapeTest}, for the same reason: a hand-built map's absent-vs-null rules are
 * decided in one method and nothing but a test of that method will notice them drift.
 */
class PlayerSpotlightShapeTest {

    private static final Ownership ROSTERED = new Ownership(true, "Dunk Tank", false, "av123");

    private static Period night() {
        return new Period(PlayerSpotlightService.Kind.NIGHT, LocalDate.parse("2026-10-21"), 11, null, null);
    }

    private static Period week() {
        return new Period(PlayerSpotlightService.Kind.WEEK, null, null, 3, true);
    }

    private static PlayerSpotlightService.Result result(boolean multiple, Period period,
                                                        Section top, TrendingSection trending) {
        return new PlayerSpotlightService.Result(true, null, 2026, multiple ? Sport.NBA : Sport.NFL,
                multiple, period, null, null, null, top, trending, new Section(List.of(), null));
    }

    private static TrendingSection trending(TrendingEntry... entries) {
        return new TrendingSection(List.of(entries), 24, Instant.parse("2026-10-21T14:05:11Z"),
                false, 0, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object section) {
        return (List<Map<String, Object>>) map(section).get("entries");
    }

    @Test
    void notApplicableCarriesOnlyAppliesReasonSeasonAndSport() {
        Map<String, Object> body = PlayerSpotlightController.body(
                PlayerSpotlightService.Result.notApplicable("PAST_SEASON", 2025, Sport.NBA));

        assertEquals(List.of("applies", "reason", "season", "sport"), List.copyOf(body.keySet()));
        assertEquals(false, body.get("applies"));
        assertEquals("PAST_SEASON", body.get("reason"));
        assertEquals("nba", body.get("sport"));
    }

    @Test
    void footballOmitsTopOfNightAndKeepsTheOtherSections() {
        Map<String, Object> body = PlayerSpotlightController.body(result(false, week(), null, trending()));

        assertFalse(body.containsKey("topOfNight"), "ABSENT for football, not null and not []");
        assertTrue(body.containsKey("trending"));
        assertTrue(body.containsKey("rookieWatch"));
        assertEquals(false, body.get("playersPlayMultiplePerPeriod"));
        assertEquals(List.of("kind", "week", "weekFinal"), List.copyOf(map(body.get("period")).keySet()));
    }

    @Test
    void basketballIncludesTopOfNightWithANightPeriod() {
        Performance p = new Performance("1", "AJ Dybantsa", "SF", "WAS", null, null, 41.5, ROSTERED);
        Map<String, Object> body = PlayerSpotlightController.body(
                result(true, night(), new Section(List.of(p), null), trending()));

        assertTrue(body.containsKey("topOfNight"));
        assertEquals(true, body.get("playersPlayMultiplePerPeriod"));
        Map<String, Object> period = map(body.get("period"));
        assertEquals(List.of("kind", "date", "gamesCount"), List.copyOf(period.keySet()));
        assertEquals("2026-10-21", period.get("date"));

        // unknown opponent/isAway are emitted as null, not dropped and not guessed
        Map<String, Object> row = rows(body.get("topOfNight")).getFirst();
        assertTrue(row.containsKey("opponent") && row.get("opponent") == null);
        assertTrue(row.containsKey("isAway") && row.get("isAway") == null);
        assertEquals(41.5, row.get("points"));
    }

    @Test
    void aNullPeriodSerializesAsNullBesideItsReason() {
        PlayerSpotlightService.Result r = new PlayerSpotlightService.Result(true, null, 2026, Sport.NBA,
                true, null, "NO_GAMES_YET", null, null, new Section(List.of(), null), trending(),
                new Section(List.of(), null));
        Map<String, Object> body = PlayerSpotlightController.body(r);
        assertTrue(body.containsKey("period") && body.get("period") == null);
        assertEquals("NO_GAMES_YET", body.get("periodUnavailable"));
    }

    @Test
    void onlyAPlayedTrendingEntryCarriesPoints() {
        TrendingEntry played = new TrendingEntry(1, "10", "A", "PG", "POR", 600, ROSTERED,
                "PLAYED", 28.0, "SAC", true);
        TrendingEntry dnp = new TrendingEntry(2, "11", "B", "PG", "POR", 500, Ownership.UNROSTERED,
                "DID_NOT_PLAY", null, null, null);
        TrendingEntry bye = new TrendingEntry(3, "12", "C", "PG", "POR", 400, Ownership.UNROSTERED,
                "NO_GAME", null, null, null);
        TrendingEntry none = new TrendingEntry(4, "13", "D", "PG", "POR", 300, Ownership.UNROSTERED,
                "NO_PERIOD", null, null, null);

        Map<String, Object> body = PlayerSpotlightController.body(
                result(true, night(), new Section(List.of(), null), trending(played, dnp, bye, none)));
        List<Map<String, Object>> rows = rows(body.get("trending"));

        assertEquals(28.0, rows.get(0).get("points"));
        assertEquals("SAC", rows.get(0).get("opponent"));
        for (int i = 1; i < 4; i++) {
            assertFalse(rows.get(i).containsKey("points"), "a non-game is never a zero: " + rows.get(i));
            assertFalse(rows.get(i).containsKey("opponent"));
            assertFalse(rows.get(i).containsKey("isAway"));
        }
    }

    @Test
    void aNullTeamNameSerializesWithoutThrowing() {
        Performance p = new Performance("1", "X", "SF", "WAS", "CHA", false, 10.0,
                new Ownership(true, null, true, null));
        Map<String, Object> body = assertDoesNotThrow(() -> PlayerSpotlightController.body(
                result(true, night(), new Section(List.of(p), null), trending())));
        Map<String, Object> own = map(rows(body.get("topOfNight")).getFirst().get("ownership"));
        assertEquals(true, own.get("rostered"));
        assertTrue(own.containsKey("teamName") && own.get("teamName") == null);
        assertEquals(true, own.get("isMe"));
        assertTrue(own.containsKey("avatarId") && own.get("avatarId") == null,
                "a rostered owner with no avatar serializes avatarId as null, not absent");
    }

    @Test
    void aRosteredOwnershipCarriesTheAvatarIdAndAnUnrosteredOneHasNoSuchKey() {
        Performance rostered = new Performance("1", "X", "SF", "WAS", "CHA", false, 10.0, ROSTERED);
        Performance free = new Performance("2", "Y", "SF", "WAS", "CHA", false, 9.0, Ownership.UNROSTERED);
        Map<String, Object> body = PlayerSpotlightController.body(
                result(true, night(), new Section(List.of(rostered, free), null), trending()));
        List<Map<String, Object>> rows = rows(body.get("topOfNight"));
        assertEquals("av123", map(rows.get(0).get("ownership")).get("avatarId"));
        assertFalse(map(rows.get(1).get("ownership")).containsKey("avatarId"));
    }

    @Test
    void anUnrosteredOwnershipIsJustTheFlag() {
        Performance p = new Performance("1", "X", "SF", "WAS", "CHA", false, 10.0, Ownership.UNROSTERED);
        Map<String, Object> body = PlayerSpotlightController.body(
                result(true, night(), new Section(List.of(p), null), trending()));
        Map<String, Object> own = map(rows(body.get("topOfNight")).getFirst().get("ownership"));
        assertEquals(Map.of("rostered", false), own);
    }
}
