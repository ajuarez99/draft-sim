package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalDataRepository.ImportJobRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the historical import as an async job (plan F14): a {@code historical_import_job} row, a virtual thread,
 * and a report that is rewritten after every season so a poll shows progress. One job at a time (a second start
 * is a 409 via {@link IllegalStateException}). The staging table is truncated at the start (in
 * {@code KaggleHistoricalSource.prepare}) and again at the end, success or not.
 *
 * <p>A job is DONE when it ran to the end, even if some seasons were refused: those are in
 * {@code report.failedSeasons} and committed nothing. FAILED means the job itself died (a SHA mismatch, a
 * download failure) and {@code report.error} says why.
 */
@Service
public class HistoricalImportService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalImportService.class);

    public static final Set<String> PARTS = Set.of("KAGGLE", "SLEEPER", "ALL");

    private final HistoricalDataRepository data;
    private final KaggleHistoricalSource kaggle;
    private final SleeperHistoricalSource sleeper;
    private final ObjectMapper json;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public HistoricalImportService(HistoricalDataRepository data, KaggleHistoricalSource kaggle,
                                   SleeperHistoricalSource sleeper, ObjectMapper json) {
        this.data = data;
        this.kaggle = kaggle;
        this.sleeper = sleeper;
        this.json = json;
    }

    /** Starts the job and returns its id at once. */
    public long start(String part) {
        if (part == null || !PARTS.contains(part)) {
            throw new IllegalArgumentException("part must be one of KAGGLE, SLEEPER, ALL");
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("a historical import is already running");
        }
        long jobId;
        try {
            jobId = data.createJob(part, now());
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        Thread.ofVirtual().name("historical-import-" + jobId).start(() -> run(jobId, part));
        return jobId;
    }

    public Optional<ImportJobRow> job(long id) {
        return data.findJob(id);
    }

    void run(long jobId, String part) {
        ImportReport report = new ImportReport(part);
        long t0 = System.currentTimeMillis();
        String status = "DONE";
        try {
            if (part.equals("KAGGLE") || part.equals("ALL")) runKaggle(jobId, report);
            if (part.equals("SLEEPER") || part.equals("ALL")) {
                sleeper.importAll(report);
                save(jobId, report);
            }
        } catch (Throwable t) {
            log.error("historical import job {} died", jobId, t);
            report.error(t.getClass().getSimpleName() + ": " + t.getMessage());
            status = "FAILED";
        } finally {
            try {
                data.truncateStaging();
            } catch (RuntimeException e) {
                log.warn("could not truncate staging after job {}: {}", jobId, e.toString());
            }
            report.note("wallSeconds", (System.currentTimeMillis() - t0) / 1000.0);
            try {
                data.finishJob(jobId, status, now(), toJson(report));
            } finally {
                running.set(false);
            }
        }
        log.info("historical import job {} finished {} in {} s", jobId, status, (System.currentTimeMillis() - t0) / 1000);
    }

    private void runKaggle(long jobId, ImportReport report) throws Exception {
        try (KaggleHistoricalSource.Prepared prepared = kaggle.prepare(report)) {
            save(jobId, report);
            for (int s = KaggleHistoricalSource.FIRST_SEASON; s <= KaggleHistoricalSource.LAST_SEASON; s++) {
                boolean ok = kaggle.importSeason(s, prepared, report);
                log.info("historical import: Kaggle season {} {}", s, ok ? "committed" : "REFUSED");
                save(jobId, report);
            }
        }
    }

    private void save(long jobId, ImportReport report) {
        data.updateJobReport(jobId, toJson(report));
    }

    private String toJson(ImportReport report) {
        try {
            return json.writeValueAsString(report.snapshot());
        } catch (JsonProcessingException e) {
            return "{\"error\":\"report could not be serialised\"}";
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
