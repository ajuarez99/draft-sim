package com.ballknowers.draftsim.recap;

import com.ballknowers.draftsim.domain.Sport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5's amended table, row for row (specs/020-ai-weekly-recap/research.md). NFL 2026 week 3 and NBA
 * 2025 week 10 are the real captured reports, indices as in the plan's example excerpts.
 */
class GroundingCheckTest {

    private final RecapInputBuilder builder = new RecapInputBuilder();
    private final String nfl = builder.canonicalJson(builder.build(RecapFixtures.nfl()));
    private final String nba = builder.canonicalJson(builder.build(RecapFixtures.nba()));

    private static RecapOutput.Section sec(String body, String... cites) {
        return new RecapOutput.Section("T", body, List.of(cites));
    }

    private static RecapOutput out(String headline, RecapOutput.Section... sections) {
        return new RecapOutput(headline, List.of(sections));
    }

    private GroundingResult nflCheck(RecapOutput o) { return GroundingCheck.check(o, nfl, Sport.NFL); }

    private GroundingResult nflCheck(String body, String... cites) {
        return nflCheck(out("Week recap", sec(body, cites)));
    }

    // ---- the plan's excerpts ------------------------------------------------------------------

    @Test
    void haikuExcerptPasses() {
        GroundingResult r = nflCheck(out("Master Bates hits 188.48 and stays perfect",
                sec("Master Bates scored 188.48, the top total of week 3, and beat Khatt Stafford by 67.58 to move to "
                        + "3-0. Jahmyr Gibbs led every player with 41.4 against NYJ.",
                        "/matchups/3", "/weekHigh", "/topPerformers/0"),
                sec("Khatt Stafford outscored 6 of 11 other teams and still lost. They're 1-2.",
                        "/awards/1", "/matchups/3")));
        assertTrue(r.ok(), r.toString());
    }

    @Test
    void opusExcerptPasses() {
        GroundingResult r = nflCheck(out("Got away with it, and paid for it",
                sec("Dart has hit anotha Bower beat Likely Have Downs 147.54 to 114.9 while playing only 86% of their "
                        + "best lineup and leaving 23.10 on the bench. Likely Have Downs had no answer beyond Jaxon "
                        + "Smith-Njigba, whose 35.36 was 31% of their starters' points. They're 0-3.",
                        "/matchups/0", "/awards/0", "/awards/2"),
                sec("Torta Pounder with Cheese lost to jpelwell by 14.42, and the Broncos sitting on their bench "
                        + "outscored the Packers they started by 16.00.",
                        "/matchups/5", "/awards/3")));
        assertTrue(r.ok(), r.toString());
    }

    // ---- false facts --------------------------------------------------------------------------

    @Test
    void falseThirdPlaceFails() {
        // He was 6th. The 3 in "3-0" is a record (atomic), and week 3 must not rescue an ordinal.
        GroundingResult r = nflCheck("Khatt Stafford finished 3rd in weekly scoring and lost to a 3-0 team.",
                "/awards/1", "/matchups/3");
        assertFalse(r.ok());
        assertTrue(r.unmatched().contains("3rd"), r.toString());
    }

    @Test
    void wrongDateFails() {
        GroundingResult r = GroundingCheck.check(
                out("Week 10 recap", sec("Nikola Jokić scored 71 on December 12.", "/bestNights/0")), nba, Sport.NBA);
        assertFalse(r.ok());
        assertTrue(r.unmatched().contains("December 12"), r.toString());
        // The right date is accepted, in a month-name spelling, against the ISO leaf.
        assertTrue(GroundingCheck.check(
                out("Week 10 recap", sec("Nikola Jokić scored 71 on December 25.", "/bestNights/0")), nba, Sport.NBA)
                .ok());
    }

    @Test
    void finishedSecondFails() {
        GroundingResult r = nflCheck("Khatt Stafford finished second in weekly scoring.", "/awards/1");
        assertFalse(r.ok());
        assertTrue(r.unmatched().contains("second"), r.toString());
    }

    // ---- "the one bright spot" ----------------------------------------------------------------

    @Test
    void oneBrightSpotNeedsAOneInTheCitedItems() {
        String body = "Jahmyr Gibbs was the one bright spot, scoring 41.4.";
        GroundingResult alone = nflCheck(body, "/topPerformers/0");
        assertFalse(alone.ok());
        assertTrue(alone.unmatched().contains("one"), alone.toString());

        // Master Bates' scoreRank is 1, so citing matchup 3 supplies it.
        assertTrue(nflCheck(body, "/topPerformers/0", "/matchups/3").ok());
        // Matchup 0 does not: its ranks are 3 and 7, and its records are atomic tokens.
        assertFalse(nflCheck(body, "/topPerformers/0", "/matchups/0").ok());
    }

    // ---- cites --------------------------------------------------------------------------------

    @Test
    void badCites() {
        assertFalse(nflCheck("Master Bates won.", "/matchups").badCites().isEmpty());
        assertFalse(nflCheck("Master Bates won.", "").badCites().isEmpty());
        assertFalse(nflCheck("Master Bates won.", "/matchups/99").badCites().isEmpty(), "does not resolve");
        assertFalse(nflCheck("Master Bates won.", "/nope/0").badCites().isEmpty());
        assertFalse(nflCheck("Master Bates won.", "matchups/3").badCites().isEmpty());
        GroundingResult five = nflCheck("Master Bates won.",
                "/matchups/0", "/matchups/1", "/matchups/2", "/matchups/3", "/matchups/4");
        assertFalse(five.ok());
        assertFalse(five.badCites().isEmpty(), "5 cites is over the limit of 4");
        assertTrue(nflCheck("Master Bates won.", "/matchups/3", "/weekHigh", "/weekLow").badCites().isEmpty());
        assertTrue(nflCheck("Master Bates won.", "/awardsOmitted/0").badCites().stream()
                .anyMatch(c -> c.contains("/awardsOmitted/0")), "empty list index does not resolve");
    }

    // ---- names --------------------------------------------------------------------------------

    @Test
    void namesAndNumbersAreTiedToTheCitedItems() {
        GroundingResult r = nflCheck("Master Bates beat Puka-Boo by 43.32.", "/matchups/3");
        assertFalse(r.ok());
        assertTrue(r.unmatched().contains("43.32"), r.toString());
        assertEquals(List.of("Puka-Boo"), r.unboundNames());
    }

    @Test
    void aNameInsideACitedAwardDetailIsBound() {
        // "Jaxon Smith-Njigba" is in /awards/2's detail text, not in a name field there.
        assertTrue(nflCheck("Jaxon Smith-Njigba supplied 35.36.", "/awards/2").ok());
        assertFalse(nflCheck("Jaxon Smith-Njigba supplied 35.36.", "/awards/1").unboundNames().isEmpty());
    }

    // ---- NBA basis ----------------------------------------------------------------------------

    @Test
    void nbaCountedIsABasisViolation() {
        RecapOutput o = out("Week 10 recap", sec("Nikola Jokić played 4 games and all of it counted.", "/bestWeek/0"));
        GroundingResult r = GroundingCheck.check(o, nba, Sport.NBA);
        assertFalse(r.ok());
        assertEquals(1, r.basisViolations().size(), r.toString());
        // The same words are not a basis problem in football.
        assertTrue(nflCheck("Master Bates' 188.48 all counted.", "/weekHigh").basisViolations().isEmpty());
        assertFalse(GroundingCheck.check(
                out("x", sec("Jokić credited 71.", "/bestNights/0")), nba, Sport.NBA).basisViolations().isEmpty());
        assertFalse(GroundingCheck.check(
                out("x", sec("Jokić scored for 71.", "/bestNights/0")), nba, Sport.NBA).basisViolations().isEmpty());
    }

    // ---- tokenizing ---------------------------------------------------------------------------

    @Test
    void alphanumericNamesContributeNoNumbers() {
        // "FentMachines5:SoFkingOver" is a real NBA team name; the 5 must not become a token.
        GroundingResult r = GroundingCheck.check(
                out("Recap", sec("FentMachines5:SoFkingOver won with 87% of an optimal lineup, ahead of the 49ers.",
                        "/awards/0")), nba, Sport.NBA);
        assertTrue(r.ok(), r.toString());
        assertFalse(r.unmatched().contains("5"));
        assertFalse(r.unmatched().contains("49"));
    }

    @Test
    void decimalsPercentsAndRecords() {
        assertTrue(nflCheck("Likely Have Downs scored 114.90.", "/matchups/0").ok(), "114.9 = 114.90");
        assertTrue(nflCheck("Likely Have Downs scored 114.9.", "/matchups/0").ok());
        assertTrue(nflCheck("Jaxon Smith-Njigba supplied 31 of it.", "/awards/2").ok(), "31% = 31");
        assertTrue(nflCheck("Jaxon Smith-Njigba supplied 31%.", "/awards/2").ok());
        // A record is one token: 3-0 is not in /awards/1, and neither 3 nor 0 stands in for it.
        GroundingResult r = nflCheck("Khatt Stafford is 3-0.", "/awards/1");
        assertEquals(List.of("3-0"), r.unmatched());
        assertTrue(nflCheck("Master Bates is 3-0.", "/matchups/3").ok());
    }

    @Test
    void weekAndSeasonAreInEveryPool() {
        assertTrue(nflCheck("Khatt Stafford lost in week 3.", "/awards/1").ok());
        assertTrue(nflCheck("Khatt Stafford lost in the 2026 season.", "/awards/1").ok());
        assertFalse(nflCheck("Khatt Stafford lost in week 4.", "/awards/1").ok());
    }

    @Test
    void numberWordsMapToValues() {
        assertTrue(nflCheck("Khatt Stafford beat six of eleven teams.", "/awards/1").ok());
        assertFalse(nflCheck("Khatt Stafford beat seven of eleven teams.", "/awards/1").ok());
        assertTrue(nflCheck("Khatt Stafford took 6th.", "/awards/1").ok());
        assertTrue(nflCheck("Khatt Stafford was sixth.", "/awards/1").ok());
    }

    @Test
    void theHeadlineIsCheckedAgainstTheUnionOfCitedItems() {
        // 188.48 is under /weekHigh, which a section cites: the headline may use it (R1).
        assertTrue(nflCheck(out("Master Bates hits 188.48", sec("Master Bates had the week's high.", "/weekHigh"),
                sec("Dart has hit anotha Bower got away with it.", "/awards/0"))).ok());
        // It was formerly "the whole input": 188.48 uncited by any section now fails.
        GroundingResult uncited = nflCheck(out("Master Bates hits 188.48",
                sec("Dart has hit anotha Bower got away with it.", "/awards/0")));
        assertFalse(uncited.ok());
        assertTrue(uncited.unmatched().contains("188.48"), uncited.toString());
        assertEquals(List.of("Master Bates"), uncited.unboundNames());
        GroundingResult bad = nflCheck(out("Master Bates hits 199.99", sec("Dart has hit anotha Bower won.", "/awards/0")));
        assertFalse(bad.ok());
        assertTrue(bad.unmatched().contains("199.99"));
    }

    @Test
    void aHeadlineNameNoSectionCitesIsUnbound() {
        GroundingResult r = nflCheck(out("Puka-Boo's revenge", sec("jpelwell scored 126.84.", "/matchups/5")));
        assertFalse(r.ok());
        assertEquals(List.of("Puka-Boo"), r.unboundNames());
    }

    @Test
    void anNbaHeadlineIsHeldToTheBasisRule() {
        GroundingResult r = GroundingCheck.check(out("Every point counted for Jokić",
                sec("Nikola Jokić played 4 games.", "/bestWeek/0")), nba, Sport.NBA);
        assertFalse(r.basisViolations().isEmpty(), r.toString());
    }

    // ---- R1: titles ---------------------------------------------------------------------------

    @Test
    void anInventedNumberInATitleFails() {
        GroundingResult r = nflCheck(out("Week recap", new RecapOutput.Section("jpelwell's 7th straight win, Mahomes 40",
                "jpelwell scored 126.84.", List.of("/matchups/5"))));
        assertFalse(r.ok());
        assertTrue(r.unmatched().contains("7th"), r.toString());
        assertTrue(r.unmatched().contains("40"), r.toString());
    }

    @Test
    void aNameInATitleIsBoundToTheSectionsCites() {
        GroundingResult r = nflCheck(out("Week recap", new RecapOutput.Section("Puka-Boo's revenge",
                "jpelwell scored 126.84.", List.of("/matchups/5"))));
        assertEquals(List.of("Puka-Boo"), r.unboundNames());
    }

    // ---- R2: case ------------------------------------------------------------------------------

    @Test
    void aCapitalizedHandleIsStillBoundToItsCites() {
        // 111.84 is Puka-Boo's score (matchup 4); jpelwell is matchup 5.
        GroundingResult r = nflCheck("Jpelwell put up 111.84.", "/matchups/4");
        assertFalse(r.ok());
        assertEquals(List.of("jpelwell"), r.unboundNames());
        GroundingResult shouted = nflCheck("PUKA-BOO scored 126.84.", "/matchups/5");
        assertFalse(shouted.ok());
        assertEquals(List.of("Puka-Boo"), shouted.unboundNames());
        assertTrue(nflCheck("Jpelwell put up 126.84.", "/matchups/5").ok());
    }

    // ---- R3: dashes ----------------------------------------------------------------------------

    @Test
    void anEnDashRecordIsOneRecordToken() {
        GroundingResult r = nflCheck("jpelwell moved to 7–2.", "/matchups/5");
        assertFalse(r.ok());
        assertTrue(r.unmatched().contains("7-2"), r.toString());
        assertTrue(nflCheck("jpelwell is 3–0.", "/matchups/5").ok(), "the true record, en-dashed");
        assertTrue(nflCheck("jpelwell is 3−0.", "/matchups/5").ok(), "minus sign");
    }

    // ---- R11: tokenizer escapes -----------------------------------------------------------------

    @Test
    void unitSuffixesAreStillChecked() {
        assertTrue(nflCheck("jpelwell scored 126.84pts.", "/matchups/5").ok());
        assertTrue(nflCheck("jpelwell scored 126.84 pts.", "/matchups/5").ok());
        GroundingResult glued = nflCheck("jpelwell scored 157pts.", "/matchups/5");
        assertTrue(glued.unmatched().contains("157pts"), glued.toString());
        GroundingResult x = nflCheck("jpelwell scored 2.7x what Torta Pounder with Cheese did.", "/matchups/5");
        assertTrue(x.unmatched().contains("2.7x"), x.toString());
    }

    @Test
    void aNegativeMustMatchANegative() {
        String input = "{\"awards\":[{\"detail\":\"Lost by -22.2.\",\"kind\":\"X\",\"teamName\":\"A\"}],\"season\":2026,\"week\":9}";
        assertTrue(GroundingCheck.check(out("Recap", sec("A lost by -22.2.", "/awards/0")), input, Sport.NFL).ok());
        GroundingResult positive = GroundingCheck.check(out("Recap", sec("A lost by 22.2.", "/awards/0")), input, Sport.NFL);
        assertFalse(positive.ok());
        assertTrue(positive.unmatched().contains("22.2"), positive.toString());
        assertFalse(nflCheck("jpelwell lost by -126.84.", "/matchups/5").ok());
    }

    @Test
    void fullwidthDigitsAreNormalized() {
        assertTrue(nflCheck("jpelwell scored １２６.84.", "/matchups/5").ok());
        GroundingResult r = nflCheck("jpelwell scored １５７.", "/matchups/5");
        assertTrue(r.unmatched().contains("157"), r.toString());
    }

    @Test
    void aNameIsALabelNotAClaim() {
        // A team called "Three Amigos" must not demand a 3 in the cited items.
        String input = "{\"awards\":[{\"detail\":\"Won.\",\"kind\":\"X\",\"teamName\":\"Three Amigos\"}],\"season\":2026,\"week\":9}";
        assertTrue(GroundingCheck.check(out("Recap", sec("Three Amigos won.", "/awards/0")), input, Sport.NFL).ok());
    }
}
