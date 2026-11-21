package com.ballknowers.draftsim.historical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 079: the words of a share preview. Vectors for the headline, ordinal and day label are the web's own
 * ({@code historicalCopy.outcome.test.ts}, {@code historicalDaily.test.ts}), in the shared voice.
 */
class SharePreviewTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- the one place that calls the positional constructor: a new field is a one-line fix here ----------

    private static PublicGame game(String sport, String label, int teams, int userSlot, String dailyDate,
                                   boolean chosen, String resultJson, String reducedUntil) {
        try {
            JsonNode result = resultJson == null ? null : JSON.readTree(resultJson);
            var season = new HistoricalGameState.SeasonView(label, List.of(), Map.of(), Map.of(), "x", "x", "x",
                    List.of(), null);
            var state = new HistoricalGameState(7L, sport, "COMPLETE", season, teams, 14, List.of(), userSlot,
                    List.of(), 169, null, false, List.of(), List.of(), result, 0, dailyDate, chosen);
            return new PublicGame(state, reducedUntil);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String FORMAT = "{\"version\":2,\"seasonWeeks\":25}";

    private static String result(String outcome, int finish, String wins, String ties, String marks) {
        return "{\"userFinish\":" + finish + ",\"userOutcome\":\"" + outcome + "\",\"format\":" + FORMAT
                + ",\"standings\":[{\"slot\":3,\"wins\":" + wins + ",\"losses\":1,\"ties\":" + ties
                + ",\"pointsFor\":100.0,\"place\":1},{\"slot\":5,\"wins\":2,\"losses\":12,\"ties\":0,\"pointsFor\":1.0,\"place\":9}]"
                + ",\"hindsight\":{\"label\":\"x\",\"userPointsFor\":87.0,\"pointsFor\":100.0,\"userBeatIt\":false,\"picks\":[]}"
                + (marks == null ? "" : ",\"pickFinishes\":" + marks) + "}";
    }

    private static String pf(int no, int drafted, int finished) {
        return "{\"pickNo\":" + no + ",\"draftedPositionRank\":" + drafted + ",\"finishPositionRank\":" + finished + "}";
    }

    @Test
    void championDailyIsTrophyTitledWithADayAndAScore() {
        PublicGame g = game("NBA", "2003-04", 12, 3, "2026-11-21", false,
                result("CHAMPION", 1, "13", "0", "[" + pf(3, 5, 2) + "," + pf(22, 4, 4) + "," + pf(27, 2, 9) + "]"), null);
        SharePreview p = SharePreview.from(g, null);
        assertEquals("Redraft daily · Nov 21 · 🏆 Champion", p.title());
        assertEquals("13-1 · Draft score 87% · 2003-04 NBA · Play today's Redraft.", p.description());
        assertTrue(p.card().champion());
        assertEquals("Champion", p.card().headline());
        assertEquals("13-1", p.card().record());
        assertEquals(87, p.card().draftScore());
        assertEquals("2003-04 NBA", p.card().seasonLine());
        assertEquals("Nov 21", p.card().dayLine());
        assertEquals(List.of("ahead", "same", "behind"), p.card().marks());
        assertEquals("Champion, 13-1, draft score 87%, 2003-04 NBA. 1 pick ahead of their draft spot, 1 even, 1 behind.",
                p.imageAlt());
    }

    @Test
    void aDailyNumberGoesAfterTheWordDaily() {
        PublicGame g = game("NBA", "2003-04", 12, 3, "2026-11-21", false, result("CHAMPION", 1, "13", "0", null), null);
        assertEquals("Redraft daily #8 · Nov 21 · 🏆 Champion", SharePreview.from(g, 8).title());
    }

    @Test
    void aLostFinalInFreePlayHasNoTrophyAndTheSharedVoice() {
        PublicGame g = game("NFL", "2011", 10, 3, null, false, result("LOST_FINAL", 2, "10", "0", null), null);
        SharePreview p = SharePreview.from(g, null);
        assertEquals("Ball Knowers Redraft · 2011 NFL · This team lost the final", p.title());
        assertEquals("10-1 · Draft score 87% · 2011 NFL · Draft a past season.", p.description());
        assertFalse(p.card().champion());
        assertNull(p.card().dayLine());
    }

    @Test
    void everyOutcomeUsesTheWebWording() {
        String[][] cases = {
                {"LOST_SEMIFINAL", "3", "This team lost in the semifinal"},
                {"LOST_ROUND_ONE", "5", "This team lost in round one"},
                {"MISSED_PLAYOFFS", "7", "This team missed the playoffs (7th of 10)"},
                {"NO_PLAYOFFS", "3", "This team finished 3rd of 10"},
                {"SOMETHING_NEW", "12", "This team finished 12th of 10"},
        };
        for (String[] c : cases) {
            PublicGame g = game("NBA", "2003-04", 10, 3, null, false, result(c[0], Integer.parseInt(c[1]), "5", "0", null), null);
            assertEquals(c[2], SharePreview.from(g, null).card().headline(), c[0]);
        }
    }

    @Test
    void ordinalsAndDayLabelsMatchTheWeb() {
        assertEquals("1st", SharePreview.ordinal(1));
        assertEquals("2nd", SharePreview.ordinal(2));
        assertEquals("3rd", SharePreview.ordinal(3));
        assertEquals("11th", SharePreview.ordinal(11));
        assertEquals("12th", SharePreview.ordinal(12));
        assertEquals("13th", SharePreview.ordinal(13));
        assertEquals("21st", SharePreview.ordinal(21));
        assertEquals("112th", SharePreview.ordinal(112));
        assertEquals("Nov 14", SharePreview.dayLabel("2026-11-14"));
        assertEquals("Jan 1", SharePreview.dayLabel("2026-01-01"));
        assertEquals("garbage", SharePreview.dayLabel("garbage"));
    }

    @Test
    void anOldFormatResultNeverGetsATrophyOrAnOutcome() {
        String old = "{\"userFinish\":1,\"userOutcome\":\"CHAMPION\",\"standings\":[{\"slot\":3,\"wins\":9,\"losses\":5,\"ties\":0,\"pointsFor\":9.0,\"place\":1}],"
                + "\"hindsight\":{\"userPointsFor\":50.0,\"pointsFor\":100.0}}";
        SharePreview p = SharePreview.from(game("NBA", "1999-00", 12, 3, null, false, old, null), null);
        assertFalse(p.card().champion());
        assertEquals("This team finished 1st of 12", p.card().headline());
        assertEquals("Ball Knowers Redraft · 1999-00 NBA · This team finished 1st of 12", p.title());
        assertEquals(50, p.card().draftScore());
    }

    @Test
    void tiesAreInTheRecord() {
        PublicGame g = game("NBA", "2003-04", 12, 3, null, false, result("NO_PLAYOFFS", 4, "8", "2", null), null);
        assertEquals("8-1-2", SharePreview.from(g, null).card().record());
        assertTrue(SharePreview.from(g, null).description().startsWith("8-1-2 · "));
    }

    @Test
    void noScoreMeansNoScoreLineAndNoSquaresMeansNoCounts() {
        String noScore = "{\"userFinish\":4,\"userOutcome\":\"NO_PLAYOFFS\",\"format\":" + FORMAT
                + ",\"standings\":[{\"slot\":3,\"wins\":8,\"losses\":6,\"ties\":0,\"pointsFor\":1.0,\"place\":4}],"
                + "\"hindsight\":{\"userPointsFor\":50.0,\"pointsFor\":0.0}}";
        SharePreview p = SharePreview.from(game("NBA", "2003-04", 12, 3, null, false, noScore, null), null);
        assertNull(p.card().draftScore());
        assertTrue(p.card().marks().isEmpty());
        assertEquals("8-6 · 2003-04 NBA · Draft a past season.", p.description());
        assertFalse(p.imageAlt().contains("draft score"));
        assertFalse(p.imageAlt().contains("picks"));
    }

    @Test
    void aResultWithNothingInItStillMakesAPreview() {
        SharePreview p = SharePreview.from(game("NBA", "2003-04", 12, 3, null, false, null, null), null);
        assertNull(p.card().record());
        assertNull(p.card().draftScore());
        assertFalse(p.card().champion());
    }

    @Test
    void aChosenSeasonSaysSoWithTheWebsOwnLine() {
        PublicGame g = game("NBA", "2003-04", 12, 3, null, true, result("NO_PLAYOFFS", 4, "8", "0", null), null);
        SharePreview p = SharePreview.from(g, null);
        assertEquals("8-1 · Draft score 87% · 2003-04 NBA · Chosen season, not the game of the day · Draft a past season.",
                p.description());
        assertTrue(p.card().chosenSeason());
    }

    @Test
    void squaresAreInPickOrderWhateverOrderTheyWereStoredIn() {
        PublicGame g = game("NBA", "2003-04", 12, 3, null, false,
                result("NO_PLAYOFFS", 4, "8", "0", "[" + pf(30, 9, 1) + "," + pf(3, 1, 9) + "," + pf(14, 2, 2) + "]"), null);
        assertEquals(List.of("behind", "same", "ahead"), SharePreview.from(g, null).card().marks());
    }

    // ---- the privacy rule: a card is built only from what the reduced view keeps ----------------------------

    private static final String FULL = "{\"userFinish\":1,\"userOutcome\":\"CHAMPION\",\"format\":" + FORMAT
            + ",\"standings\":[{\"slot\":3,\"wins\":13,\"losses\":1,\"ties\":0,\"pointsFor\":9100.5,\"place\":1},"
            + "{\"slot\":5,\"wins\":2,\"losses\":12,\"ties\":0,\"pointsFor\":1.0,\"place\":9}],"
            + "\"weeks\":[{\"week\":1,\"matchups\":[]}],\"lineups\":{\"3\":[{\"week\":1,\"starters\":[{\"playerId\":9}]}]},"
            + "\"hindsight\":{\"label\":\"Best roster\",\"pointsFor\":10000.0,\"userPointsFor\":8700.9,\"userBeatIt\":false,"
            + "\"picks\":[{\"pickNo\":1,\"player\":{\"name\":\"Zorblax Quillfeather\"},\"seasonValue\":500.0}]},"
            + "\"biggestBust\":{\"player\":{\"name\":\"Ignatius Brambleworth\"}},"
            + "\"pickFinishes\":[{\"pickNo\":3,\"player\":{\"name\":\"Zorblax Quillfeather\"},\"position\":\"PG\",\"draftedPositionRank\":5,"
            + "\"finishPositionRank\":2,\"positionPlayers\":30,\"seasonValue\":500.0,\"weeksPlayed\":20},"
            + "{\"pickNo\":22,\"player\":{\"name\":\"Ignatius Brambleworth\"},\"position\":\"C\",\"draftedPositionRank\":4,"
            + "\"finishPositionRank\":4,\"positionPlayers\":30,\"seasonValue\":300.0,\"weeksPlayed\":20},"
            + "{\"pickNo\":27,\"player\":{\"name\":\"Wilhelmina Thistlewick\"},\"position\":\"SF\",\"draftedPositionRank\":2,"
            + "\"finishPositionRank\":9,\"positionPlayers\":30,\"seasonValue\":100.0,\"weeksPlayed\":20}]}";

    /** What HistoricalGameService.reduced(...) produces from FULL (hand-built here; the MVC IT checks the real one). */
    private static final String REDUCED = "{\"userFinish\":1,\"userOutcome\":\"CHAMPION\",\"format\":" + FORMAT
            + ",\"standings\":[{\"slot\":3,\"wins\":13,\"losses\":1,\"ties\":0,\"pointsFor\":9100.5,\"place\":1}],"
            + "\"hindsight\":{\"label\":\"Best roster\",\"userPointsFor\":8700.9,\"userBeatIt\":false,\"draftScore\":87,\"picks\":[]},"
            + "\"pickFinishes\":[{\"pickNo\":3,\"mark\":\"ahead\"},{\"pickNo\":22,\"mark\":\"same\"},{\"pickNo\":27,\"mark\":\"behind\"}],"
            + "\"weeks\":[],\"lineups\":{},\"finalPlaces\":[]}";

    @Test
    void thePreviewOfAReducedGameEqualsThePreviewOfTheFullOne() {
        SharePreview full = SharePreview.from(game("NBA", "2003-04", 12, 3, "2026-11-21", false, FULL, null), null);
        SharePreview reduced = SharePreview.from(game("NBA", "2003-04", 12, 3, "2026-11-21", false, REDUCED, "2026-11-22T05:00:00Z"), null);
        assertEquals(full, reduced);
        assertEquals(87, full.card().draftScore());
        assertEquals(List.of("ahead", "same", "behind"), full.card().marks());
    }

    @Test
    void noPlayerNameOrHindsightTotalReachesAnyTextOfTheCard() {
        SharePreview full = SharePreview.from(game("NBA", "2003-04", 12, 3, "2026-11-21", false, FULL, null), null);
        String all = full.title() + "|" + full.description() + "|" + full.imageAlt() + "|" + full.card();
        for (String leak : new String[]{"Zorblax", "Quillfeather", "Ignatius", "Brambleworth", "Wilhelmina", "Thistlewick",
                "10000", "8700", "9100", "Best roster", "weeks"}) {
            assertFalse(all.contains(leak), "leaked " + leak + " in " + all);
        }
    }
}
