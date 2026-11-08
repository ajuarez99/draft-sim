package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.TestAdmin;
import com.ballknowers.draftsim.config.NflHistoricalProperties;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.ImportJobRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 056 T017/T018 end to end against Postgres, through the real download (a {@code file:} URL), the real gzip
 * reader, the real parser, the real transactions and the real SQL: the NFLVERSE import part over four fixture seasons
 * (1892..1895, numbers no real import produces) of which two are good and two are broken on purpose.
 *
 * <p>The job runs synchronously through the package-private {@code run}, with explicit seasons, so it can never touch
 * a real season's rows. The draft-picks file is a fixture too and only covers 1893, so only that year is replaced.
 *
 * <p>SKIPS when Postgres is not reachable at {@code DB_URL}, so read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NflImportIT {

    private static final String PREFIX = "it-nflimp-";
    private static final String HEADER = "player_id,player_name,player_display_name,position,season,week,season_type,"
            + "game_id,team,opponent_team,passing_yards,passing_tds,passing_interceptions,passing_2pt_conversions,"
            + "sack_fumbles_lost,rushing_yards,rushing_tds,rushing_fumbles_lost,rushing_2pt_conversions,receptions,"
            + "receiving_yards,receiving_tds,receiving_fumbles_lost,receiving_2pt_conversions,special_teams_tds,"
            + "fantasy_points_ppr\n";

    private static Path dir;

    @DynamicPropertySource
    static void fixtures(DynamicPropertyRegistry registry) throws IOException {
        dir = Files.createTempDirectory("nflimport-fixture-");
        for (int s : new int[]{1892, 1893}) write("w_" + s + ".csv.gz", good(s), true);
        write("w_1894.csv.gz", good(1894).replace("special_teams_tds,", ""), true);   // a missing column
        // two REG rows for one player in one week
        write("w_1895.csv.gz", good(1895) + PREFIX + "1,QB One,QB One,QB," + 1895 + ",1,REG," + 1895
                + "_01_AAA_BBB,AAA,BBB,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1.0\n", true);
        write("draft_picks.csv", "season,round,pick,team,gsis_id,pfr_player_id,position,college\n"
                + "1893,1,2," + "AAA," + PREFIX + "1,x,QB,Utah\n"
                + "1893,2,40,AAA," + PREFIX + "2,y,FB,Utah\n"
                + "1893,3,70,AAA,nobody,z,WR,Utah\n", false);
        registry.add("historical-nfl.stats-url-template", () -> dir.toUri() + "w_{season}.csv.gz");
        registry.add("historical-nfl.draft-picks-url", () -> dir.resolve("draft_picks.csv").toUri().toString());
    }

    /** Three skill players and a team row, two REG weeks, plus a POST row that must not count. */
    private static String good(int s) {
        String g1 = s + "_01_AAA_BBB", g2 = s + "_02_BBB_CCC";
        return HEADER
                + PREFIX + "1,QB One,QB One,QB," + s + ",1,REG," + g1 + ",AAA,BBB,300,2,1,0,0,5,0,0,0,0,0,0,0,0,0,25.0\n"
                + PREFIX + "1,QB One,QB One,QB," + s + ",2,REG," + g2 + ",AAA,CCC,200,1,0,0,0,0,0,0,0,0,0,0,0,0,0,9.0\n"
                + PREFIX + "2,Rb Two,Rb Two,FB," + s + ",1,REG," + g1 + ",BBB,AAA,0,0,0,0,0,80,1,0,0,2,20,0,0,0,0,18.0\n"
                + PREFIX + "3,Wr Three,Wr Three,WR," + s + ",2,REG," + g2 + ",CCC,BBB,0,0,0,0,0,0,0,0,0,6,90,1,0,0,0,26.0\n"
                + "0,,,," + s + ",1,REG," + g1 + ",AAA,BBB,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0\n"
                + PREFIX + "1,QB One,QB One,QB," + s + ",3,POST," + s + "_21_AAA_BBB,AAA,BBB,999,9,9,0,0,0,0,0,0,0,0,0,0,0,0,9\n";
    }

    private static void write(String name, String text, boolean gzip) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (gzip) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(bytes);
            }
            bytes = out.toByteArray();
        }
        Files.write(dir.resolve(name), bytes);
    }

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping NFL import IT");
    }

    @Autowired private HistoricalImportService imports;
    @Autowired private HistoricalDataRepository data;
    @Autowired private NflHistoricalRepository nfl;
    @Autowired private NflHistoricalProperties props;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private MockMvc mvc;

    @BeforeEach
    void clean() {
        cleanUp(jdbc);
    }

    @AfterAll
    static void removeTheFixtureSeasons() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        try {
            cleanUp(new JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url, "draftsim", "draftsim")));
        } catch (RuntimeException e) {
            // Postgres was unreachable, so nothing was seeded either.
        }
    }

    private static void cleanUp(JdbcTemplate jdbc) {
        jdbc.update("delete from historical_nfl_draft_pick where player_id in"
                + " (select id from historical_player where source_key like ?)", PREFIX + "%");
        jdbc.update("delete from historical_nfl_player_week where season between 1892 and 1895 and player_id in"
                + " (select id from historical_player where source_key like ?)", PREFIX + "%");
        jdbc.update("delete from historical_nfl_player_season where season between 1892 and 1895 and player_id in"
                + " (select id from historical_player where source_key like ?)", PREFIX + "%");
        jdbc.update("delete from historical_player where source_key like ?", PREFIX + "%");
        jdbc.update("delete from historical_season where sport = 'NFL' and season_start between 1892 and 1895");
    }

    private JsonNode runJob() throws Exception {
        long jobId = data.createJob("NFLVERSE", OffsetDateTime.now());
        imports.run(jobId, "NFLVERSE", 1892, 1895);
        ImportJobRow row = data.findJob(jobId).orElseThrow();
        try {
            assertEquals("DONE", row.status(), row.reportJson());
            return json.readTree(row.reportJson());
        } finally {
            jdbc.update("delete from historical_import_job where id = ?", jobId);
        }
    }

    private static String sha(String name) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve(name)))) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void goodSeasonsCommitBrokenOnesAreRefusedLoudlyAndCommitNothing() throws Exception {
        JsonNode report = runJob();

        assertEquals(2, report.get("seasons").size());
        JsonNode s93 = null;
        for (JsonNode s : report.get("seasons")) if (s.get("seasonStart").asInt() == 1893) s93 = s;
        assertNotNull(s93);
        assertEquals("NFLVERSE", s93.get("source").asText());
        assertEquals(2, s93.get("games").asInt(), "two distinct REG game ids; the POST game is not counted");
        assertTrue(s93.get("expectedGames").isNull(), "no schedule fact for a made-up year");
        assertEquals(2, s93.get("maxWeek").asInt());
        assertEquals(4, s93.get("playerWeeks").asInt(), "QB x2, FB, WR; the team row and the POST row are not stored");
        assertEquals(3, s93.get("playerSeasons").asInt());
        assertEquals(0, s93.get("droppedRows").asInt(), "the all-zero team row is not a loss");
        assertEquals(1, s93.get("rawBlankIdRows").asInt());
        assertEquals(sha("w_1893.csv.gz"), s93.get("fileSha256").asText());
        assertEquals(Files.size(dir.resolve("w_1893.csv.gz")), s93.get("fileBytes").asLong());

        // the broken seasons are refused and say why
        assertEquals(2, report.get("failedSeasons").size());
        String failed = report.get("failedSeasons").toString();
        assertTrue(failed.contains("1894") && failed.contains("special_teams_tds"), failed);
        assertTrue(failed.contains("1895") && failed.contains("two REG rows"), failed);

        // committed: rows and the season row for the good ones, nothing at all for the bad ones
        assertEquals(4, nfl.rowCounts(1893)[0]);
        assertEquals(3, nfl.rowCounts(1893)[1]);
        assertTrue(data.findSeason("NFL", 1893).isPresent());
        assertTrue(data.findSeason("NFL", 1892).isPresent());
        SeasonRowCheck.assertRow(data.findSeason("NFL", 1893).orElseThrow(), sha("w_1893.csv.gz"));
        assertEquals(0, nfl.rowCounts(1894)[0] + nfl.rowCounts(1894)[1]);
        assertEquals(0, nfl.rowCounts(1895)[0] + nfl.rowCounts(1895)[1], "a refused season rolled back whole");
        assertTrue(data.findSeason("NFL", 1894).isEmpty());
        assertTrue(data.findSeason("NFL", 1895).isEmpty());

        // the draft picks: FB maps to RB, an unknown id is skipped, only the covered year is replaced
        assertEquals(3, report.get("notes").get("draftPicksRead").asInt(), "QB, FB and a WR whose id is not in the database");
        assertEquals(2, report.get("notes").get("draftPicksStored").asInt());
        var picks = nfl.draftPicks(1893);
        assertEquals(2, picks.size());
        assertEquals("RB", jdbc.queryForObject("select position from historical_nfl_draft_pick where pick = 40"
                + " and season = 1893", String.class));
    }

    @Test
    void aSecondRunReplacesTheSeasonsInsteadOfDoublingThem() throws Exception {
        runJob();
        long playerBefore = jdbc.queryForObject("select id from historical_player where source_key = ?", Long.class,
                PREFIX + "1");
        runJob();
        assertEquals(4, nfl.rowCounts(1893)[0]);
        assertEquals(3, nfl.rowCounts(1893)[1]);
        assertEquals(playerBefore, (long) jdbc.queryForObject("select id from historical_player where source_key = ?",
                Long.class, PREFIX + "1").longValue(), "players keep their ids across a re-import (053 F9)");
        assertEquals(2, nfl.draftPicks(1893).size());
    }

    @Test
    void theAdminRouteValidatesTheSeasonRangeAndKeepsAllBasketballOnly() throws Exception {
        for (String body : new String[]{
                "{\"part\":\"NFLVERSE\",\"fromSeason\":\"1998\"}",                       // before the prior-only season
                "{\"part\":\"NFLVERSE\",\"toSeason\":\"" + (props.lastOffered() + 1) + "\"}",
                "{\"part\":\"NFLVERSE\",\"fromSeason\":\"2012\",\"toSeason\":\"2011\"}",
                "{\"part\":\"NFLVERSE\",\"fromSeason\":\"abc\"}",
                "{\"part\":\"ALL\",\"fromSeason\":\"2012\"}",                            // ranges are NFLVERSE only
                "{\"part\":\"KAGGLE\",\"toSeason\":\"2012\"}"}) {
            mvc.perform(post("/api/admin/historical/import").header("X-Admin-Token", TestAdmin.TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class, () -> imports.start("NOPE"));
        assertTrue(bad.getMessage().contains("NFLVERSE"), "the 400 message names the new part: " + bad.getMessage());
        assertTrue(HistoricalImportService.PARTS.contains("NFLVERSE"));
        assertTrue(HistoricalImportService.PARTS.contains("ALL"));
    }

    /** The season row fields the import is responsible for. */
    private static final class SeasonRowCheck {
        static void assertRow(HistoricalDataRepository.SeasonRow r, String sha) {
            assertEquals("NFL", r.sport());
            assertEquals("1893", r.label());
            assertEquals("NFLVERSE", r.source());
            assertEquals(2, r.weekCount());
            assertEquals(2, r.gameCount());
            assertNull(r.expectedGameCount());
            assertEquals(0, r.droppedRows());
            assertEquals(sha, r.sourceSha256());
            assertEquals(NflHistoricalSport.COUNTING, r.countedStats());
            assertEquals("PRIOR_SEASON_POINTS", r.rankingSource());
            assertNull(r.openingNight());
        }
    }
}
