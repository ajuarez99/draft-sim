package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.TestAdmin;
import com.ballknowers.draftsim.historical.ImportReport;
import com.ballknowers.draftsim.historical.KaggleHistoricalSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 053 T013: a second import started while one is running is a 409, and the guard is released when the first
 * one ends (even by dying). The Kaggle source is a mock whose {@code prepare} blocks on a latch, so the overlap
 * is deterministic rather than a race. SKIPS when Postgres isn't reachable at {@code DB_URL}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoricalImportConcurrencyIT {

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping import concurrency IT");
    }

    @MockitoBean private KaggleHistoricalSource kaggle;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private final List<Long> jobs = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : jobs) jdbc.update("delete from historical_import_job where id = ?", id);
    }

    private long start() throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/historical/import").header("X-Admin-Token", TestAdmin.TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"part\":\"KAGGLE\"}"))
                .andExpect(status().isAccepted()).andReturn();
        long id = json.readTree(r.getResponse().getContentAsString()).get("jobId").asLong();
        jobs.add(id);
        return id;
    }

    private JsonNode awaitDone(long id) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            MvcResult r = mvc.perform(get("/api/admin/historical/import/" + id)
                    .header("X-Admin-Token", TestAdmin.TOKEN)).andExpect(status().isOk()).andReturn();
            JsonNode job = json.readTree(r.getResponse().getContentAsString());
            if (!"RUNNING".equals(job.get("status").asText())) return job;
            Thread.sleep(50);
        }
        fail("job " + id + " did not finish");
        return null;
    }

    @Test
    void aSecondStartWhileOneIsRunningIs409AndTheGuardReleasesWhenItEnds() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(kaggle.prepare(any(ImportReport.class))).thenAnswer(inv -> {
            started.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            throw new IllegalStateException("released by the test");
        });

        long first = start();
        assertTrue(started.await(30, TimeUnit.SECONDS), "the first job never reached prepare");

        mvc.perform(post("/api/admin/historical/import").header("X-Admin-Token", TestAdmin.TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"part\":\"KAGGLE\"}"))
                .andExpect(status().isConflict());

        release.countDown();
        JsonNode done = awaitDone(first);
        assertEquals("FAILED", done.get("status").asText());
        assertTrue(done.get("report").get("error").asText().contains("released by the test"));

        // the guard is gone: a new job is accepted again
        long second = start();
        assertNotEquals(first, second);
        awaitDone(second);
    }
}
