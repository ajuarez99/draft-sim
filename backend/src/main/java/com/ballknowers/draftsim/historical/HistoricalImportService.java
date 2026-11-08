package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.NflHistoricalProperties;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.ImportJobRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * {@code ALL} stays basketball-only (the contract): an NBA import must never download 27 football files by
     * surprise (spec 056). {@code NFLVERSE} is its own part.
     */
    public static final Set<String> PARTS = Set.of("KAGGLE", "SLEEPER", "NFLVERSE", "ALL");


    private final HistoricalDataRepository data;
    private final KaggleHistoricalSource kaggle;
    private final SleeperHistoricalSource sleeper;
    private final NflverseSource nflverse;
    private final NflHistoricalRepository nflData;
    private final NflHistoricalProperties nflProps;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public HistoricalImportService(HistoricalDataRepository data, KaggleHistoricalSource kaggle,
                                   SleeperHistoricalSource sleeper, NflverseSource nflverse,
                                   NflHistoricalRepository nflData, NflHistoricalProperties nflProps,
                                   TransactionTemplate tx, ObjectMapper json) {
        this.data = data;
        this.kaggle = kaggle;
        this.sleeper = sleeper;
        this.nflverse = nflverse;
        this.nflData = nflData;
        this.nflProps = nflProps;
        this.tx = tx;
        this.json = json;
    }

    /** Starts the job and returns its id at once. */
    public long start(String part) {
        return start(part, null, null);
    }

    /**
     * @param fromSeason NFLVERSE only: the first season to (re)import, default 1999
     * @param toSeason   NFLVERSE only: the last, default the last offered season. Lets an admin re-import one season
     *                   without downloading 27 files. Ignored (and refused if given) for the other parts.
     */
    public long start(String part, Integer fromSeason, Integer toSeason) {
        if (part == null || !PARTS.contains(part)) {
            throw new IllegalArgumentException("part must be one of KAGGLE, SLEEPER, NFLVERSE, ALL");
        }
        if ((fromSeason != null || toSeason != null) && !part.equals("NFLVERSE")) {
            throw new IllegalArgumentException("fromSeason/toSeason apply to the NFLVERSE part only");
        }
        int from = fromSeason == null ? nflFirstSeason() : fromSeason;
        int to = toSeason == null ? nflProps.lastOffered() : toSeason;
        if (part.equals("NFLVERSE") && (from < nflFirstSeason() || to > nflProps.lastOffered() || from > to)) {
            throw new IllegalArgumentException("NFLVERSE seasons must satisfy " + nflFirstSeason() + " <= fromSeason <= toSeason <= "
                    + nflProps.lastOffered() + ", got " + from + ".." + to);
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
        Thread.ofVirtual().name("historical-import-" + jobId).start(() -> run(jobId, part, from, to));
        return jobId;
    }

    public Optional<ImportJobRow> job(long id) {
        return data.findJob(id);
    }

    void run(long jobId, String part) {
        run(jobId, part, nflFirstSeason(), nflProps.lastOffered());
    }

    void run(long jobId, String part, int nflFrom, int nflTo) {
        ImportReport report = new ImportReport(part);
        long t0 = System.currentTimeMillis();
        String status = "DONE";
        try {
            if (part.equals("KAGGLE") || part.equals("ALL")) runKaggle(jobId, report);
            if (part.equals("SLEEPER") || part.equals("ALL")) {
                sleeper.importAll(report);
                save(jobId, report);
            }
            if (part.equals("NFLVERSE")) runNflverse(jobId, report, nflFrom, nflTo);
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

    /**
     * Football (spec 056 T018): one season per transaction, then the draft picks (which need the players the
     * seasons just created). A season that fails (a missing column, a duplicate player-week, an unreadable number)
     * is refused whole and listed in {@code failedSeasons}; the others still commit.
     */
    private void runNflverse(long jobId, ImportReport report, int from, int to) {
        for (int s = from; s <= to; s++) {
            long t = System.currentTimeMillis();
            try {
                NflverseSource.ParsedSeason p = nflverse.fetchSeason(s);
                if (p.weeks().isEmpty()) {
                    report.failed(s, "NFLVERSE", List.of("no regular-season skill-position rows in the file"));
                    log.info("historical import: nflverse season {} REFUSED (empty)", s);
                    save(jobId, report);
                    continue;
                }
                Integer expected = nflProps.expectedGames().get(s);   // null for 1999: prior-only, not offered
                HistoricalDataRepository.SeasonRow row = new HistoricalDataRepository.SeasonRow(
                        NflHistoricalSport.CODE, s, String.valueOf(s), "NFLVERSE", null, null, p.maxWeek(),
                        p.regGames(), NflHistoricalSport.COUNTING, "{}", "{}", "PRIOR_SEASON_POINTS", now(), expected,
                        p.droppedRows(), p.fileSha256());
                tx.executeWithoutResult(status -> nflData.replaceSeason(p, row));

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("seasonStart", s);
                m.put("source", "NFLVERSE");
                m.put("games", p.regGames());
                m.put("expectedGames", expected);
                m.put("maxWeek", p.maxWeek());
                m.put("playerWeeks", p.weeks().size());
                m.put("playerSeasons", p.seasonLines().size());
                m.put("droppedRows", p.droppedRows());
                m.put("rawBlankIdRows", p.rawBlankIdRows());
                m.put("nonSkillRows", p.nonSkillRows());
                m.put("fileSha256", p.fileSha256());
                m.put("fileBytes", p.fileBytes());
                m.put("seconds", (System.currentTimeMillis() - t) / 1000.0);
                report.season(m);
                log.info("historical import: nflverse season {} committed ({} games of {}, {} player-weeks, {} ms)",
                        s, p.regGames(), expected, p.weeks().size(), System.currentTimeMillis() - t);
            } catch (Exception e) {
                report.failed(s, "NFLVERSE", List.of(e.getClass().getSimpleName() + ": " + e.getMessage()));
                log.warn("historical import: nflverse season {} REFUSED: {}", s, e.toString());
            }
            save(jobId, report);
        }
        try {
            List<NflverseSource.DraftPickRow> picks = nflverse.fetchDraftPicks();
            int stored = tx.execute(status -> nflData.replaceDraftPicks(picks));
            report.note("draftPicksRead", picks.size());
            report.note("draftPicksStored", stored);
            log.info("historical import: nflverse draft picks {} read, {} stored", picks.size(), stored);
        } catch (Exception e) {
            report.failed(0, "NFLVERSE draft_picks", List.of(e.getClass().getSimpleName() + ": " + e.getMessage()));
            log.warn("historical import: nflverse draft picks REFUSED: {}", e.toString());
        }
        save(jobId, report);
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

    /**
     * One season before the first offered one, imported only so that season has prior-season cards; it is never
     * offered (research R1). Derived from the config, not a second literal (review S3).
     */
    int nflFirstSeason() {
        return nflProps.firstOffered() - 1;
    }
}
