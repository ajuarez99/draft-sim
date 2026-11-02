package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.TestAdmin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 053 T013 against Postgres: the admin gate, the async job and its report, run through the real download
 * (a {@code file:} URL), the real SHA-256 check, the real COPY into staging and the real season-file parse.
 *
 * <p>The fixture zip is tiny and built so that EVERY season is refused (a season with no season-file rows, and
 * 1975 with a fantasy-relevant box name the season file doesn't have), so the run commits nothing and cannot
 * touch real imported data in this database. Only the job rows it created are removed afterwards.
 *
 * <p>SKIPS when Postgres isn't reachable at {@code DB_URL}, so read the skip count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoricalImportControllerIT {

    private static final String BOX = "NBA Player Box Score Stats(1950 - 2022).csv";
    private static final String SEASONS = "NBA Player Stats(1950 - 2022).csv";

    private static final String BOX_CSV = String.join("\n",
            ",Season,Game_ID,PLAYER_NAME,Team,GAME_DATE,MATCHUP,WL,MIN,FGM,FGA,FG_PCT,FG3M,FG3A,FG3_PCT,FTM,FTA,FT_PCT,OREB,DREB,REB,AST,STL,BLK,TOV,PF,PTS,PLUS_MINUS,VIDEO_AVAILABLE",
            "1,1975,0027500001,Al Attles,GSW,\"OCT 22, 1975\",GSW vs. BOS,W,30,5,10,0.5,,,,2,2,1.0,,,4,6,,,,3,12,,0",
            "2,1975,0027500001,Zed Nobody,BOS,\"OCT 22, 1975\",BOS @ GSW,L,25,4,9,0.4,,,,1,2,0.5,,,7,1,,,,2,9,,0",
            "3,1946,0024600001,Old Timer,HUS,\"NOV 19, 1946\",HUS vs. CLR,L,10,1,2,0.5,,,,0,0,,,,,,,,,1,2,,0",
            "") + "\n";

    private static final String SEASON_CSV = String.join("\n",
            ",Unnamed: 0,Season,Player,Pos,Age,Tm,G,GS,MP,FG,FGA,FG%,3P,3PA,3P%,2P,2PA,2P%,eFG%,FT,FTA,FT%,ORB,DRB,TRB,AST,STL,BLK,TOV,PF,PTS",
            "0,0,1976,Al Attles,PG,39.0,GSW,50.0,,,,,,,,,,,,,,,,,,,,,,,,120.0",
            "") + "\n";

    private static byte[] zipBytes;
    private static String sha;
    private static Path zipFile;

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping historical import IT");
    }

    @DynamicPropertySource
    static void fixtureZip(DynamicPropertyRegistry registry) throws Exception {
        zipBytes = buildZip();
        zipFile = Files.createTempFile("historical-fixture-", ".zip");
        Files.write(zipFile, zipBytes);
        sha = sha256(zipBytes);
        registry.add("historical.kaggle-zip-url", () -> zipFile.toUri().toString());
        registry.add("historical.kaggle-zip-sha256", () -> sha);
    }

    private static byte[] buildZip() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String[] e : new String[][]{{BOX, BOX_CSV}, {SEASONS, SEASON_CSV}}) {
                ZipEntry entry = new ZipEntry(e[0]);
                entry.setTime(0L); // fixed, so the bytes (and the SHA) are reproducible
                zip.putNextEntry(entry);
                zip.write(e[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private final List<Long> jobs = new ArrayList<>();

    @AfterEach
    void cleanUp() throws IOException {
        for (Long id : jobs) jdbc.update("delete from historical_import_job where id = ?", id);
        Files.write(zipFile, zipBytes); // a test may have tampered with it
    }

    private long startJob(String part) throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/historical/import")
                        .header("X-Admin-Token", TestAdmin.TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"part\":\"" + part + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").isNumber())
                .andReturn();
        long id = json.readTree(r.getResponse().getContentAsString()).get("jobId").asLong();
        jobs.add(id);
        return id;
    }

    private JsonNode awaitJob(long id) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            MvcResult r = mvc.perform(get("/api/admin/historical/import/" + id)
                            .header("X-Admin-Token", TestAdmin.TOKEN))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode job = json.readTree(r.getResponse().getContentAsString());
            if (!"RUNNING".equals(job.get("status").asText())) return job;
            Thread.sleep(100);
        }
        fail("the import job did not finish in 60 s");
        return null;
    }

    @Test
    void theImportRoutesRefuseWithoutTheAdminToken() throws Exception {
        mvc.perform(post("/api/admin/historical/import").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"part\":\"KAGGLE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/historical/import").header("X-Admin-Token", "not-the-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"part\":\"KAGGLE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/historical/import/1")).andExpect(status().isForbidden());
    }

    @Test
    void aBadPartIs400AndAnUnknownJobIs404() throws Exception {
        mvc.perform(post("/api/admin/historical/import").header("X-Admin-Token", TestAdmin.TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"part\":\"NOPE\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/historical/import").header("X-Admin-Token", TestAdmin.TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/historical/import/999999999").header("X-Admin-Token", TestAdmin.TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void anImportRunsAsyncReportsRefusedSeasonsAndCommitsNothingFromARefusedRun() throws Exception {
        int seasonsBefore = jdbc.queryForObject("select count(*) from historical_season", Integer.class);
        int gamesBefore = jdbc.queryForObject("select count(*) from historical_player_game", Integer.class);

        long id = startJob("KAGGLE");
        JsonNode job = awaitJob(id);

        assertEquals("DONE", job.get("status").asText(), job.toString());
        assertEquals("KAGGLE", job.get("part").asText());
        assertNotNull(job.get("finishedAt").asText());
        JsonNode report = job.get("report");
        assertTrue(report.get("error").isNull(), "no job-level error: " + report.get("error"));
        assertEquals(sha, report.get("notes").get("zipSha256").asText(), "the SHA that was checked is reported");
        assertEquals(2, report.get("notes").get("stagedBoxRows").asInt(),
                "COPY staged the two 1975 rows and skipped 1946, which is outside 1969..2016");
        assertEquals(1, report.get("notes").get("seasonFileRows").asInt());

        // every season was refused, so nothing in the report's committed list
        assertEquals(0, report.get("seasons").size());
        assertEquals(48, report.get("failedSeasons").size(), "1969..2016, each one refused");
        JsonNode f1975 = null;
        for (JsonNode f : report.get("failedSeasons")) if (f.get("seasonStart").asInt() == 1975) f1975 = f;
        assertNotNull(f1975);
        assertTrue(f1975.get("reasons").toString().contains("unmatched fantasy-relevant: Zed Nobody"),
                f1975.toString());
        JsonNode unmatched = report.get("unmatchedFantasyRelevant");
        assertEquals(1, unmatched.size());
        assertEquals("Zed Nobody", unmatched.get(0).get("name").asText());
        assertEquals("NO_SEASON_ROW", unmatched.get(0).get("problem").asText());
        assertEquals(0, report.get("uncoveredCollisions").size());

        // refused seasons wrote nothing; staging was truncated at the end
        assertEquals(seasonsBefore, jdbc.queryForObject("select count(*) from historical_season", Integer.class));
        assertEquals(gamesBefore, jdbc.queryForObject("select count(*) from historical_player_game", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from historical_kaggle_staging", Integer.class));
    }

    @Test
    void aZipWhoseShaDoesNotMatchTheConfiguredOneFailsTheJobBeforeReadingIt() throws Exception {
        byte[] tampered = new byte[zipBytes.length + 1];
        System.arraycopy(zipBytes, 0, tampered, 0, zipBytes.length);
        tampered[zipBytes.length] = 7;
        Files.write(zipFile, tampered);

        long id = startJob("KAGGLE");
        JsonNode job = awaitJob(id);

        assertEquals("FAILED", job.get("status").asText(), job.toString());
        String error = job.get("report").get("error").asText();
        assertTrue(error.contains("SHA-256 mismatch"), error);
        assertEquals(0, job.get("report").get("seasons").size());
        assertEquals(0, jdbc.queryForObject("select count(*) from historical_kaggle_staging", Integer.class));
    }
}
