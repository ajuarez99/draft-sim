package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * The AI weekly recap's storage ({@code league_recap} and {@code league_recap_attempt},
 * V29; specs/020-ai-weekly-recap).
 *
 * <p>A row holds two independent things: the last READY body ({@code ready_*}, headline,
 * sections...) and the last attempt ({@code attempt_*}). {@link #writeFailure} touches only the
 * second, so a failed regeneration never destroys a good body (review F11).
 */
@Repository
public class LeagueRecapRepository {

    /** Mirrors every {@code league_recap} column; jsonb columns come back as JSON text. */
    public record Row(
            long id, long leagueId, int week,
            String readyKey, String readyNumbersHash, String readyInputJson,
            String headline, String sectionsJson, String model, String promptVersion,
            Integer inputTokens, Integer outputTokens,
            int revision, String revisionReason, Instant generatedAt,
            String attemptKey, String attemptStatus, String failureReason, String failureDetailJson,
            String stopReason, Instant retryAfter, Instant attemptedAt) {}

    private final JdbcClient db;

    public LeagueRecapRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<Row> find(long leagueId, int week) {
        return db.sql("""
                select id, league_id, week, ready_key, ready_numbers_hash, ready_input_json,
                       headline, sections::text as sections, model, prompt_version,
                       input_tokens, output_tokens, revision, revision_reason, generated_at,
                       attempt_key, attempt_status, failure_reason, failure_detail::text as failure_detail,
                       stop_reason, retry_after, attempted_at
                from league_recap where league_id = ? and week = ?
                """)
                .params(leagueId, week)
                .query(LeagueRecapRepository::map)
                .optional();
    }

    /**
     * Stores a new READY body: sets the ready_* and attempt_* columns, bumps {@code revision}
     * (a first write makes it 1) and records why it was regenerated ({@code revisionReason} is
     * null for a first generation).
     */
    public void writeReady(long leagueId, int week, String readyKey, String numbersHash, String inputJson,
                           String headline, String sectionsJson, String model, String promptVersion,
                           int inputTokens, int outputTokens, String revisionReason,
                           String stopReason, Instant now) {
        Timestamp ts = Timestamp.from(now);
        db.sql("""
                insert into league_recap (league_id, week, ready_key, ready_numbers_hash, ready_input_json,
                    headline, sections, model, prompt_version, input_tokens, output_tokens,
                    revision, revision_reason, generated_at,
                    attempt_key, attempt_status, failure_reason, failure_detail, stop_reason,
                    retry_after, attempted_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, 1, ?, ?, ?, 'READY', null, null, ?, null, ?)
                on conflict (league_id, week) do update set
                    ready_key = excluded.ready_key,
                    ready_numbers_hash = excluded.ready_numbers_hash,
                    ready_input_json = excluded.ready_input_json,
                    headline = excluded.headline,
                    sections = excluded.sections,
                    model = excluded.model,
                    prompt_version = excluded.prompt_version,
                    input_tokens = excluded.input_tokens,
                    output_tokens = excluded.output_tokens,
                    revision = league_recap.revision + 1,
                    revision_reason = excluded.revision_reason,
                    generated_at = excluded.generated_at,
                    attempt_key = excluded.attempt_key,
                    attempt_status = 'READY',
                    failure_reason = null,
                    failure_detail = null,
                    stop_reason = excluded.stop_reason,
                    retry_after = null,
                    attempted_at = excluded.attempted_at
                """)
                .params(leagueId, week, readyKey, numbersHash, inputJson,
                        headline, sectionsJson, model, promptVersion, inputTokens, outputTokens,
                        revisionReason, ts,
                        readyKey, stopReason, ts)
                .update();
    }

    /** Records a failed attempt. Never touches the ready_* columns, revision or headline/sections. */
    public void writeFailure(long leagueId, int week, String attemptKey, String failureReason,
                             String failureDetailJson, String stopReason, Instant retryAfter, Instant now) {
        db.sql("""
                insert into league_recap (league_id, week, attempt_key, attempt_status, failure_reason,
                    failure_detail, stop_reason, retry_after, attempted_at)
                values (?, ?, ?, 'FAILED', ?, ?::jsonb, ?, ?, ?)
                on conflict (league_id, week) do update set
                    attempt_key = excluded.attempt_key,
                    attempt_status = 'FAILED',
                    failure_reason = excluded.failure_reason,
                    failure_detail = excluded.failure_detail,
                    stop_reason = excluded.stop_reason,
                    retry_after = excluded.retry_after,
                    attempted_at = excluded.attempted_at
                """)
                .params(leagueId, week, attemptKey, failureReason, failureDetailJson, stopReason,
                        retryAfter == null ? null : Timestamp.from(retryAfter), Timestamp.from(now))
                .update();
    }

    /** Forgets the last attempt (the operator's "try again"); the READY body, if any, stays. */
    public void clearAttempt(long leagueId, int week) {
        db.sql("""
                update league_recap set attempt_key = null, attempt_status = null, failure_reason = null,
                    failure_detail = null, stop_reason = null, retry_after = null, attempted_at = null
                where league_id = ? and week = ?
                """)
                .params(leagueId, week)
                .update();
    }

    /**
     * The operator's "pull this recap" (R12): clears the READY body AND the last attempt so the next
     * GET generates fresh. {@code revision} is kept, so the regeneration counts as the next revision.
     */
    public void reroll(long leagueId, int week) {
        db.sql("""
                update league_recap set ready_key = null, ready_numbers_hash = null, ready_input_json = null,
                    headline = null, sections = null, model = null, prompt_version = null,
                    input_tokens = null, output_tokens = null, revision_reason = null, generated_at = null,
                    attempt_key = null, attempt_status = null, failure_reason = null,
                    failure_detail = null, stop_reason = null, retry_after = null, attempted_at = null
                where league_id = ? and week = ?
                """)
                .params(leagueId, week)
                .update();
    }

    public int callsSince(long leagueId, Instant from) {
        return db.sql("select count(*) from league_recap_attempt where league_id = ? and at >= ?")
                .params(leagueId, Timestamp.from(from))
                .query(Integer.class)
                .single();
    }

    public int callsSinceGlobal(Instant from) {
        return db.sql("select count(*) from league_recap_attempt where at >= ?")
                .params(Timestamp.from(from))
                .query(Integer.class)
                .single();
    }

    public void logCall(long leagueId, Instant at) {
        db.sql("insert into league_recap_attempt (league_id, at) values (?, ?)")
                .params(leagueId, Timestamp.from(at))
                .update();
    }

    private static Row map(ResultSet rs, int i) throws SQLException {
        return new Row(
                rs.getLong("id"), rs.getLong("league_id"), rs.getInt("week"),
                rs.getString("ready_key"), rs.getString("ready_numbers_hash"), rs.getString("ready_input_json"),
                rs.getString("headline"), rs.getString("sections"), rs.getString("model"),
                rs.getString("prompt_version"),
                nullableInt(rs, "input_tokens"), nullableInt(rs, "output_tokens"),
                rs.getInt("revision"), rs.getString("revision_reason"), instant(rs, "generated_at"),
                rs.getString("attempt_key"), rs.getString("attempt_status"), rs.getString("failure_reason"),
                rs.getString("failure_detail"), rs.getString("stop_reason"),
                instant(rs, "retry_after"), instant(rs, "attempted_at"));
    }

    private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
