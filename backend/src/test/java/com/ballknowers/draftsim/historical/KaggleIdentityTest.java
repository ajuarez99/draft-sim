package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.KaggleIdentity.Curation;
import com.ballknowers.draftsim.historical.KaggleIdentity.Derived;
import com.ballknowers.draftsim.historical.KaggleIdentity.Index;
import com.ballknowers.draftsim.historical.KaggleIdentity.Issue;
import com.ballknowers.draftsim.historical.KaggleIdentity.PersonSeason;
import com.ballknowers.draftsim.historical.KaggleIdentity.SeasonFileRow;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 053 T011 on small fixtures: the identity rules that make the Kaggle import fail loudly instead of
 * silently attaching the wrong person's stats (plan-review F2, F5, F17, R5) and the derived-stat flags (R2).
 */
class KaggleIdentityTest {

    // ---- fixture builders ----------------------------------------------------------------------------

    private static final String HEADER = ",Unnamed: 0,Season,Player,Pos,Age,Tm,G,GS,MP,FG,FGA,FG%,3P,3PA,3P%,2P,2PA,2P%,eFG%,FT,FTA,FT%,ORB,DRB,TRB,AST,STL,BLK,TOV,PF,PTS";

    /** One season-file line: end year, name, pos, age, team, games, points (the rest empty). 32 cells, as the header. */
    private static String line(int end, String name, String pos, int age, String team, int g, int pts) {
        String[] cells = new String[32];
        java.util.Arrays.fill(cells, "");
        cells[0] = "0";
        cells[1] = "0";
        cells[2] = String.valueOf(end);
        cells[3] = "\"" + name + "\"";
        cells[4] = pos;
        cells[5] = age + ".0";
        cells[6] = team;
        cells[7] = g + ".0";
        cells[31] = pts + ".0";
        return String.join(",", cells);
    }

    private static List<SeasonFileRow> rows(String... lines) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (String l : lines) sb.append(l).append('\n');
        return KaggleIdentity.readSeasonFile(new StringReader(sb.toString()), 1900, 2100);
    }

    private static Curation curation(List<String[]> aliases, List<String[]> same, List<String[]> teams) {
        return Curation.fromRows(aliases, same, teams);
    }

    private static Index index(Curation c, String... lines) {
        return new Index(rows(lines), c);
    }

    private static long count(Index idx, String kind) {
        return idx.issues().stream().filter(i -> i.kind().equals(kind)).count();
    }

    // ---- names, positions, derived flags -------------------------------------------------------------

    @Test
    void normalizeStripsAccentsPunctuationCaseAndSpace() {
        assertEquals("nikola jokic", KaggleIdentity.normalize("Nikola  Jokić"));
        assertEquals("ac green", KaggleIdentity.normalize("A.C. Green"));
        assertEquals("ted hound dog mcclain", KaggleIdentity.normalize("Ted 'Hound Dog' McClain"));
        assertEquals("", KaggleIdentity.normalize(null));
    }

    @Test
    void positionsAreTheUnionWithTheFirstListedFirstAndGAndFExpand() {
        assertEquals(List.of("PG"), KaggleIdentity.positions("PG"));
        assertEquals(List.of("SF", "SG"), KaggleIdentity.positions("SF-SG"));
        assertEquals(List.of("PG", "SG"), KaggleIdentity.positions("G"));
        assertEquals(List.of("SF", "PF"), KaggleIdentity.positions("F"));
        assertEquals(List.of("PG", "SG", "SF", "PF"), KaggleIdentity.positions("G-F"));
        assertEquals(List.of("C", "SF", "PF"), KaggleIdentity.positions("C-F"));
        assertEquals(List.of("PF", "C"), KaggleIdentity.positions("PF-C"));
    }

    @Test
    void anEmptyOrUnknownPositionIsEmptyNotGuessed() {
        assertTrue(KaggleIdentity.positions(null).isEmpty());
        assertTrue(KaggleIdentity.positions("").isEmpty());
        assertTrue(KaggleIdentity.positions("  ").isEmpty());
        assertTrue(KaggleIdentity.positions("PG-XX").isEmpty(), "one unknown token empties the whole set");
    }

    @Test
    void doubleAndTripleDoublesAndBonusesAreDerivedFromTheRecordedStats() {
        Derived d = KaggleIdentity.derive(22, 11, 3, 1, 0);
        assertEquals(1, d.dd());
        assertEquals(0, d.td());
        assertEquals(0, d.bonusPt40());

        Derived t = KaggleIdentity.derive(25, 12, 10, 0, 0);
        assertEquals(1, t.dd());
        assertEquals(1, t.td());

        Derived none = KaggleIdentity.derive(9, 9, 9, 9, 9);
        assertEquals(0, none.dd());
        assertEquals(0, none.td());

        Derived big = KaggleIdentity.derive(52, 21, 16, 2, 3);
        assertEquals(1, big.bonusPt40(), "a 50-point game earns both point bonuses");
        assertEquals(1, big.bonusPt50());
        assertEquals(1, big.bonusReb20());
        assertEquals(1, big.bonusAst15());

        Derived forty = KaggleIdentity.derive(40, 0, 0, 0, 0);
        assertEquals(1, forty.bonusPt40());
        assertEquals(0, forty.bonusPt50());
        assertEquals(0, KaggleIdentity.derive(19, 19, 14, 0, 0).bonusReb20());
        assertEquals(0, KaggleIdentity.derive(0, 0, 14, 0, 0).bonusAst15());
    }

    @Test
    void aBonusIsNullWhenItsInputIsNullButDdTdTreatMissingStatsAsZero() {
        // 1983-84 box score: points, rebounds, assists recorded; steals and blocks not (FR-007: missing scores 0).
        Derived early = KaggleIdentity.derive(30, 12, 4, null, null);
        assertEquals(1, early.dd(), "pts and reb already make the double-double");
        assertEquals(0, early.td());
        assertEquals(1, KaggleIdentity.derive(12, 11, 10, null, null).td(), "three known tens are a triple-double");
        assertEquals(0, KaggleIdentity.derive(12, 4, 3, null, null).dd());
        assertNull(KaggleIdentity.derive(null, null, null, null, null).dd(), "nothing recorded at all stays null");
        assertEquals(0, early.bonusPt40(), "the point bonus only needs points");
        assertEquals(0, early.bonusReb20());
        assertEquals(0, early.bonusAst15());

        Derived noPts = KaggleIdentity.derive(null, 25, 3, 1, 1);
        assertNull(noPts.bonusPt40());
        assertNull(noPts.bonusPt50());
        assertEquals(1, noPts.bonusReb20());

        assertEquals(1, KaggleIdentity.derive(10, 10, null, 0, 0).dd(), "a missing assist count is 0, not unknown");
        assertNull(KaggleIdentity.derive(10, null, 0, 0, 0).bonusReb20());
        assertNull(KaggleIdentity.derive(10, 0, null, 0, 0).bonusAst15());
    }

    // ---- the season file and the curation files -------------------------------------------------------

    @Test
    void theSeasonFileKeepsTheEndYearAndReadsEmptyCellsAsNull() {
        List<SeasonFileRow> r = rows(line(1971, "Al Attles", "PG", 34, "GSW", 82, 500));
        assertEquals(1, r.size());
        SeasonFileRow a = r.get(0);
        assertEquals(1971, a.endYear());
        assertEquals(1970, a.seasonStart(), "Season is the END year (research R3)");
        assertEquals("al attles", a.norm());
        assertEquals(34, a.age());
        assertEquals(82, a.games());
        assertEquals(500.0, a.pts());
        assertNull(a.reb(), "an empty cell is null, never 0");
    }

    // ---- keying, collisions, age continuity ----------------------------------------------------------

    @Test
    void aTradedPlayerWithATotRowIsOnePersonWithTheTotLine() {
        Index idx = index(Curation.empty(),
                line(1990, "Sam Jones", "SG", 25, "TOT", 70, 900),
                line(1990, "Sam Jones", "SG", 25, "BOS", 40, 500),
                line(1990, "Sam Jones", "SG", 25, "LAL", 30, 400));
        assertTrue(idx.issues().isEmpty(), idx.issues().toString());
        List<PersonSeason> ps = idx.personSeasons(1990);
        assertEquals(1, ps.size());
        assertEquals(70, ps.get(0).chosen().games(), "the card line is the TOT row");
        assertEquals("LAL", ps.get(0).lastTeam(), "the last team, not TOT");
        assertEquals("sam jones", ps.get(0).personKey());
    }

    @Test
    void severalTeamRowsAndNoTotRowIsAnUncoveredCollision() {
        Index idx = index(Curation.empty(),
                line(1983, "George Johnson", "C", 34, "ATL", 60, 300),
                line(1983, "George Johnson", "PF", 26, "IND", 80, 700));
        assertEquals(1, count(idx, "COLLISION"));
        assertEquals(1983 - 1, idx.issues().get(0).seasonStart());
    }

    @Test
    void anAgeThatJumpsBetweenSeasonsIsFlaggedEvenWithOneTeamAndATotRow() {
        // plan-review F2: Charles Jones, age 40 in 1998 and age 23 in 1999, one team each.
        Index idx = index(Curation.empty(),
                line(1998, "Charles Jones", "PF", 40, "HOU", 24, 22),
                line(1999, "Charles Jones", "SG", 23, "CHI", 29, 30));
        assertEquals(0, count(idx, "COLLISION"));
        assertEquals(1, count(idx, "AGE_JUMP"));
        Issue jump = idx.issues().get(0);
        assertEquals(1998, jump.seasonStart(), "attached to the later season (end 1999 = start 1998)");

        // and an ordinary career is not flagged: age +1 a year, with a gap year allowed k-1..k+1
        Index fine = index(Curation.empty(),
                line(1990, "Sam Jones", "SG", 25, "BOS", 70, 900),
                line(1991, "Sam Jones", "SG", 26, "BOS", 70, 900),
                line(1993, "Sam Jones", "SG", 28, "BOS", 70, 900));
        assertTrue(fine.issues().isEmpty());
    }

    @Test
    void curatingAPersonKeyClearsTheCollisionAndTheAgeJump() {
        Curation c = curation(List.of(), List.of(
                new String[]{"1998", "HOU", "Charles Jones", "charles-jones-1"},
                new String[]{"1999", "CHI", "Charles Jones", "charles-jones-2"}), List.of());
        Index idx = index(c,
                line(1998, "Charles Jones", "PF", 40, "HOU", 24, 22),
                line(1999, "Charles Jones", "SG", 23, "CHI", 29, 30));
        assertTrue(idx.issues().isEmpty(), idx.issues().toString());
        assertEquals("charles-jones-1", idx.personSeasons(1998).get(0).personKey());
        assertEquals("charles-jones-2", idx.personSeasons(1999).get(0).personKey());
    }

    @Test
    void everySeasonRowOfACuratedNameMustBeKeyed() {
        Curation c = curation(List.of(), List.<String[]>of(
                new String[]{"1982", "ATL", "Eddie Johnson", "eddie-johnson-1"}), List.of());
        Index idx = index(c,
                line(1982, "Eddie Johnson", "SF", 26, "ATL", 80, 1000),
                line(1988, "Eddie Johnson", "SG", 28, "PHO", 80, 1000)); // not keyed
        assertEquals(1, count(idx, "UNKEYED"), idx.issues().toString());
    }

    @Test
    void twoPeopleOfOneNameOnTheSameTeamAreToldApartByTeamAndAge() {
        Curation c = curation(List.of(), List.of(
                new String[]{"1989", "WSB:31", "Charles Jones", "charles-jones-1"},
                new String[]{"1989", "WSB:27", "Charles Jones", "charles-jones-2"}), List.of());
        Index idx = index(c,
                line(1989, "Charles Jones", "C", 31, "WSB", 53, 187),
                line(1989, "Charles Jones", "PF", 27, "WSB", 43, 49));
        assertTrue(idx.issues().isEmpty(), idx.issues().toString());
        assertEquals(2, idx.personSeasons(1989).size());

        // the box score carries no age, so these two cannot be told apart there: not forced onto either
        Index.Match m = idx.match(1989, "Charles Jones", "WSB");
        assertNull(m.personKey());
        assertEquals(KaggleIdentity.AMBIGUOUS_SAME_TEAM, m.problem());
    }

    // ---- matching box rows ---------------------------------------------------------------------------

    @Test
    void anAliasResolvesABoxNameTheSeasonFileSpellsDifferently() {
        Curation c = curation(List.<String[]>of(new String[]{"1971", "Alvin Attles", "Al Attles"}), List.of(), List.of());
        Index idx = index(c, line(1971, "Al Attles", "PG", 34, "GSW", 82, 500));
        assertNull(idx.match(1972, "Alvin Attles", "GSW").personKey(), "the alias is for season_end 1971 only");
        Index.Match m = idx.match(1971, "Alvin Attles", "GSW");
        assertEquals("al attles", m.personKey());
        assertNull(m.problem());
        assertFalse(m.teamMismatch());
    }

    @Test
    void aNameWithNoSeasonRowAndNoAliasIsUnmatched() {
        Index idx = index(Curation.empty(), line(1971, "Al Attles", "PG", 34, "GSW", 82, 500));
        Index.Match m = idx.match(1971, "Nobody Here", "GSW");
        assertNull(m.personKey());
        assertEquals("NO_SEASON_ROW", m.problem());
    }

    @Test
    void theTeamCrosswalkMapsBoxCodesAndEitherSpellingCountsButADifferentTeamIsNotMerged() {
        Curation c = curation(List.of(), List.of(), List.<String[]>of(new String[]{"PHX", "PHO"}, new String[]{"WAS", "WSB"}));
        Index idx = index(c,
                line(1990, "Tom Chambers", "PF", 30, "PHO", 80, 1500),
                line(2000, "Rod Strickland", "PG", 33, "WAS", 80, 1000));
        assertFalse(idx.match(1990, "Tom Chambers", "PHX").teamMismatch(), "PHX maps to PHO");
        assertFalse(idx.match(2000, "Rod Strickland", "WAS").teamMismatch(), "WAS verbatim also matches");
        // a name whose season rows never list the box team is another player (or a trade the file lacks):
        // not merged onto him, because that double-counts games (Greg Smith 1973-74, plan R5 follow-up)
        Index.Match other = idx.match(1990, "Tom Chambers", "BOS");
        assertNull(other.personKey());
        assertEquals(KaggleIdentity.TEAM_MISMATCH, other.problem());
        assertEquals("PHO", other.seasonTeams());
    }

    @Test
    void curatedSameNameBoxRowsAreNarrowedByTeamWhenTheTeamsDiffer() {
        Curation c = curation(List.of(), List.of(
                new String[]{"1983", "ATL", "George Johnson", "george-johnson-1"},
                new String[]{"1983", "IND", "George Johnson", "george-johnson-3"}), List.of());
        Index idx = index(c,
                line(1983, "George Johnson", "C", 34, "ATL", 60, 300),
                line(1983, "George Johnson", "PF", 26, "IND", 80, 700));
        assertEquals("george-johnson-1", idx.match(1983, "George Johnson", "ATL").personKey());
        assertEquals("george-johnson-3", idx.match(1983, "George Johnson", "IND").personKey());
        assertEquals(KaggleIdentity.AMBIGUOUS_SAME_TEAM, idx.match(1983, "George Johnson", "SEA").problem());
    }

    @Test
    void theCheckedInCurationFilesParseAndCoverEveryRowOfEachCuratedName() {
        Curation c = Curation.load();
        assertFalse(c.aliases().isEmpty());
        assertFalse(c.sameName().isEmpty());
        assertEquals("BRK", c.mapTeam("BKN"));
        assertEquals("WSB", c.mapTeam("WAS"));
        assertEquals("LAL", c.mapTeam("LAL"), "an unlisted code maps to itself");

        // the seeds from the task are present (season_end is the END year)
        assertEquals("Al Attles", c.aliases().get("1971|alvin attles"));
        assertEquals("Jo Jo White", c.aliases().get("1971|jojo white"));

        // the plan-review F2 adjacent-season cases are keyed apart
        assertNotEquals(c.sameName().get("1997|SAS|charles smith"), c.sameName().get("1998|TOT|charles smith"));
        assertNotEquals(c.sameName().get("1998|HOU|charles jones"), c.sameName().get("1999|CHI|charles jones"));
        assertNotEquals(c.sameName().get("1995|LAC|michael smith"), c.sameName().get("1995|SAC|michael smith"));
        assertNotEquals(c.sameName().get("2013|MIN|chris johnson"), c.sameName().get("2014|BOS|chris johnson"));

        // every key points at a person key of the form name-N
        List<String> bad = new ArrayList<>();
        for (String k : c.sameName().values()) if (!k.matches("[a-z]+(-[a-z]+)*-\\d+")) bad.add(k);
        assertTrue(bad.isEmpty(), bad.toString());
    }
}
