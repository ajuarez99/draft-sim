package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.NflHistoricalProperties;
import com.ballknowers.draftsim.historical.NflverseSource.DraftPickRow;
import com.ballknowers.draftsim.historical.NflverseSource.ParsedSeason;
import com.ballknowers.draftsim.historical.NflverseSource.SeasonAgg;
import com.ballknowers.draftsim.historical.NflverseSource.WeekRow;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 056 T015: the nflverse parser over a small hand-written CSV in the real file's column vocabulary (a subset of
 * its 114 columns plus an extra the parser must ignore). No network, no database.
 */
class NflverseSourceTest {

    private static final String HEADER = "player_id,player_name,player_display_name,position,season,week,season_type,"
            + "game_id,team,opponent_team,passing_yards,passing_tds,passing_interceptions,passing_2pt_conversions,"
            + "sack_fumbles_lost,rushing_yards,rushing_tds,rushing_fumbles_lost,rushing_2pt_conversions,receptions,"
            + "receiving_yards,receiving_tds,receiving_fumbles_lost,receiving_2pt_conversions,special_teams_tds,"
            + "fantasy_points_ppr,headshot_url\n";

    // id,name,display,pos,season,week,type,game,team,opp, py,ptd,pint,p2pt,sackfl, ry,rtd,rfl,r2pt, rec,recy,rectd,recfl,rec2pt, st, ppr, extra
    private static final String ROWS = String.join("\n",
            "00-0000001,A.Quarter,Al Quarterback,QB,2007,1,REG,2007_01_NYG_DAL,NYG,DAL,300,2,1,0,1,5,0,0,0,0,0,0,0,0,0,25.2,http://x",
            "00-0000001,A.Quarter,Al Quarterback,QB,2007,2,REG,2007_02_DAL_PHI,DAL,PHI,250,1,0,1,0,0,0,0,0,0,0,0,0,0,0,14.0,",
            "00-0000001,A.Quarter,Al Quarterback,QB,2007,3,POST,2007_21_DAL_PHI,DAL,PHI,999,9,9,9,9,9,9,9,9,9,9,9,9,9,9,99,",
            "00-0000002,B.Fullback,Bo Fullback,FB,2007,1,REG,2007_01_NYG_DAL,NYG,DAL,NA,NA,NA,NA,NA,40,1,1,0,2,10,0,1,0,0,18.0,",
            "00-0000003,C.Half,Cy Halfback,HB,2007,1,REG,2007_01_NYG_DAL,NYG,DAL,0,0,0,0,0,80,1,0,0,3,20,0,0,0,2,31.0,",
            "00-0000004,D.Kicker,Dan Kicker,K,2007,1,REG,2007_01_NYG_DAL,NYG,DAL,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,9.0,",
            // a team-total row: id 0, no position, all zero -> counted as raw blank, NOT dropped
            "0,,,,2007,1,REG,2007_01_NYG_DAL,NYG,DAL,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,",
            // blank id with a skill position -> dropped
            ",W.Mystery,,WR,2007,2,REG,2007_02_DAL_PHI,DAL,PHI,0,0,0,0,0,0,0,0,0,4,50,0,0,0,0,9.0,",
            // id 0, no position, but a scored stat -> dropped
            "0,,,,2007,2,REG,2007_02_DAL_PHI,DAL,PHI,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,6.0,",
            // a quoted name with a comma; display name blank falls back to player_name
            "00-0000005,\"Smith, Jr.\",,TE,2007,2,REG,2007_02_DAL_PHI,PHI,DAL,0,0,0,0,0,0,0,0,0,5,60,1,0,0,0,17.0,");

    @Test
    void aLiteralNaPlayerIdIsNoPlayerJustLikeBlankAndZero() {
        // review N4: nflverse writes NA for missing values; an id of NA must not become a player called "NA".
        String row = "NA,N.A.,Not Available,WR,2007,1,REG,2007_01_NYG_DAL,NYG,DAL,0,0,0,0,0,0,0,0,0,4,50,0,0,0,0,9.0,";
        ParsedSeason p = source.parse(2007, new StringReader(HEADER + row + "\n"));
        assertEquals(0, p.weeks().size());
        assertEquals(1, p.rawBlankIdRows());
        assertEquals(1, p.droppedRows(), "a skill position with no id is a loss");
    }

    private final NflverseSource source = new NflverseSource(props());

    private static NflHistoricalProperties props() {
        Map<Integer, Integer> games = new TreeMap<>();
        for (int y = 2000; y <= 2025; y++) games.put(y, 256);
        return new NflHistoricalProperties("https://example.test/w_{season}.csv.gz", "https://example.test/d.csv", 12, 15,
                List.of("QB", "RB", "WR", "TE", "FLEX", "BN"), 0, Map.of("pass_yd", 0.04), 17, 6, 3, 12, 2, games,
                2000, 2025, 14);
    }

    private ParsedSeason parse() {
        return source.parse(2007, new StringReader(HEADER + ROWS + "\n"));
    }

    private static WeekRow row(ParsedSeason p, String id, int week) {
        return p.weeks().stream().filter(w -> w.gsisId().equals(id) && w.week() == week).findFirst().orElseThrow();
    }

    private static double stat(WeekRow w, String key) {
        return w.stats()[NflverseSource.KEYS.indexOf(key)];
    }

    @Test
    void keepsRegRowsOfSkillPositionsOnly() {
        ParsedSeason p = parse();
        // QB x2 (the POST row is ignored), the fullback, the halfback, the tight end = 5 week rows
        assertEquals(5, p.weeks().size());
        assertTrue(p.weeks().stream().noneMatch(w -> w.gsisId().equals("00-0000004")), "the kicker is not a skill position");
        assertEquals(1, p.nonSkillRows());
        assertEquals(2, p.maxWeek(), "week 3 is POST, so the max REG week is 2");
    }

    @Test
    void mapsFbAndHbToRbAndKeepsTheOtherPositions() {
        ParsedSeason p = parse();
        assertEquals("RB", row(p, "00-0000002", 1).position());
        assertEquals("RB", row(p, "00-0000003", 1).position());
        assertEquals("QB", row(p, "00-0000001", 1).position());
        assertEquals("TE", row(p, "00-0000005", 2).position());
    }

    @Test
    void mapsTheSourceColumnsToTheScoringKeysAndSumsTheThreeFumblesLost() {
        ParsedSeason p = parse();
        WeekRow qb = row(p, "00-0000001", 1);
        assertEquals(300, stat(qb, "pass_yd"));
        assertEquals(2, stat(qb, "pass_td"));
        assertEquals(1, stat(qb, "pass_int"));
        assertEquals(5, stat(qb, "rush_yd"));
        assertEquals(1, stat(qb, "fum_lost"), "sack_fumbles_lost 1");
        assertEquals(25.2, qb.srcPpr());
        WeekRow fb = row(p, "00-0000002", 1);
        assertEquals(40, stat(fb, "rush_yd"));
        assertEquals(1, stat(fb, "rush_td"));
        assertEquals(2, stat(fb, "rec"));
        assertEquals(10, stat(fb, "rec_yd"));
        assertEquals(2, stat(fb, "fum_lost"), "rushing 1 + receiving 1");
        assertEquals(0, stat(fb, "pass_yd"), "NA is 0");
        WeekRow hb = row(p, "00-0000003", 1);
        assertEquals(2, stat(hb, "st_td"));
        WeekRow qb2 = row(p, "00-0000001", 2);
        assertEquals(1, stat(qb2, "pass_2pt"));
        assertEquals(NflverseSource.KEYS.size(), qb.stats().length);
        assertEquals("pass_yd", NflverseSource.KEYS.getFirst(), "KEYS follows StatOrder");
    }

    @Test
    void blankIdRowsAreCountedButOnlyThoseWithASkillPositionOrAScoredStatAreDropped() {
        ParsedSeason p = parse();
        assertEquals(3, p.rawBlankIdRows(), "the team row, the mystery receiver, the id-0 row with a stat");
        assertEquals(2, p.droppedRows(), "the all-zero team row is not a loss");
        assertTrue(p.weeks().stream().noneMatch(w -> w.name().equals("W.Mystery")));
    }

    @Test
    void countsDistinctRegGamesOverEveryRowAndAggregatesTheSeason() {
        ParsedSeason p = parse();
        assertEquals(2, p.regGames(), "2007_01_NYG_DAL and 2007_02_DAL_PHI; the POST game is not counted");
        SeasonAgg qb = p.seasonLines().stream().filter(a -> a.gsisId().equals("00-0000001")).findFirst().orElseThrow();
        assertEquals(2, qb.games(), "REG weeks with a row; the POST row is not a game");
        assertEquals("DAL", qb.team(), "the last REG row's team");
        assertEquals("QB", qb.position());
        assertEquals(550, qb.totals()[NflverseSource.KEYS.indexOf("pass_yd")]);
        assertEquals(1, qb.totals()[NflverseSource.KEYS.indexOf("pass_2pt")]);
        assertEquals(4, p.seasonLines().size(), "QB, FB, HB, TE");
    }

    @Test
    void aQuotedNameKeepsItsCommaAndABlankDisplayNameFallsBackToPlayerName() {
        ParsedSeason p = parse();
        assertEquals("Smith, Jr.", row(p, "00-0000005", 2).name());
        assertEquals("Al Quarterback", row(p, "00-0000001", 1).name());
    }

    @Test
    void aSecondRegRowForOnePlayerInOneWeekFailsTheSeasonLoudly() {
        String dup = "00-0000001,A.Quarter,Al Quarterback,QB,2007,1,REG,2007_01_NYG_DAL,NYG,DAL,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1.0,";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> source.parse(2007, new StringReader(HEADER + ROWS + "\n" + dup + "\n")));
        assertTrue(e.getMessage().contains("00-0000001") && e.getMessage().contains("week 1"), e.getMessage());
    }

    @Test
    void aMissingColumnFailsLoudlyNamingIt() {
        String header = HEADER.replace("special_teams_tds,", "");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> source.parse(2007, new StringReader(header)));
        assertTrue(e.getMessage().contains("special_teams_tds"), "names the missing column: " + e.getMessage());
    }

    @Test
    void aRowFromAnotherSeasonFailsLoudly() {
        String other = "00-0000009,X,X Y,QB,2008,1,REG,2008_01_A_B,A,B,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1.0,";
        assertThrows(IllegalStateException.class,
                () -> source.parse(2007, new StringReader(HEADER + other + "\n")));
    }

    @Test
    void anUnreadableNumberFailsLoudlyInsteadOfScoringZero() {
        String bad = "00-0000009,X,X Y,QB,2007,1,REG,2007_01_A_B,A,B,abc,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1.0,";
        assertThrows(IllegalStateException.class, () -> source.parse(2007, new StringReader(HEADER + bad + "\n")));
    }

    @Test
    void anEmptyFileOfJustAHeaderIsAnEmptySeasonNotAnError() {
        ParsedSeason p = source.parse(2007, new StringReader(HEADER));
        assertEquals(0, p.weeks().size());
        assertEquals(0, p.regGames());
        assertEquals(Collections.emptyList(), p.seasonLines());
    }

    // ---- draft picks -------------------------------------------------------------------------------------

    private static final String DRAFT = "season,round,pick,team,gsis_id,pfr_player_id,position,college\n"
            + "2005,1,3,SF,00-0000001,AAA,QB,Utah\n"
            + "2005,2,40,SF,00-0000002,BBB,FB,X\n"
            + "2005,1,5,SF,00-0000003,CCC,OT,X\n"           // not a skill position
            + "2005,3,70,SF,,DDD,WR,X\n"                      // no gsis id
            + "2006,7,200,SF,00-0000001,AAA,QB,Utah\n";      // the same player again: his first draft wins

    @Test
    void draftPicksKeepSkillPositionsWithAnIdAndTheFirstDraftOfAPlayer() {
        List<DraftPickRow> picks = source.parseDraftPicks(new StringReader(DRAFT));
        assertEquals(2, picks.size());
        assertEquals(new DraftPickRow("00-0000001", 2005, 1, 3, "QB"), picks.get(0));
        assertEquals(new DraftPickRow("00-0000002", 2005, 2, 40, "RB"), picks.get(1), "FB -> RB");
    }

    private static String relocRow(int season, String team, String opp, int week) {
        return "00-0000009,R.Locate,Ray Locate,RB," + season + "," + week + ",REG," + season + "_0" + week
                + "_AAA_BBB," + team + "," + opp + ",0,0,0,0,0,50,0,0,0,0,0,0,0,0,0,8.0,\n";
    }

    private WeekRow oneWeek(int season, String team, String opp) {
        return source.parse(season, new StringReader(HEADER + relocRow(season, team, opp, 1))).weeks().get(0);
    }

    @Test
    void relocatedFranchisesMapBackToTheCodeOfTheSeason() {
        assertEquals("SD", oneWeek(2004, "LAC", "KC").team());
        assertEquals("SD", oneWeek(2016, "LAC", "KC").team());
        assertEquals("LAC", oneWeek(2017, "LAC", "KC").team());
        assertEquals("STL", oneWeek(2015, "LA", "KC").team());
        assertEquals("LA", oneWeek(2016, "LA", "KC").team());
        assertEquals("OAK", oneWeek(2019, "LV", "KC").team());
        assertEquals("LV", oneWeek(2020, "LV", "KC").team());
    }

    @Test
    void theOpponentIsMappedAndOtherCodesAreUntouched() {
        WeekRow w = oneWeek(2004, "KC", "LAC");
        assertEquals("KC", w.team());
        assertEquals("SD", w.opponent());
        WeekRow x = oneWeek(2010, "NYG", "LV");
        assertEquals("NYG", x.team());
        assertEquals("OAK", x.opponent());
        assertEquals("STL", oneWeek(2010, "SF", "LA").opponent());
    }

    @Test
    void seasonLinesInheritTheMappedTeam() {
        String rows = relocRow(2004, "LAC", "KC", 1) + relocRow(2004, "LAC", "DEN", 2);
        ParsedSeason p = source.parse(2004, new StringReader(HEADER + rows));
        assertEquals("SD", p.seasonLines().get(0).team());
    }

    @Test
    void aDraftFileMissingAColumnFailsLoudly() {
        assertThrows(IllegalStateException.class,
                () -> source.parseDraftPicks(new StringReader("season,round,pick,position\n2005,1,3,QB\n")));
    }
}
