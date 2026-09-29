package com.ballknowers.draftsim.store;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The daily job's once-a-day record (specs/009-auto-data-refresh, FR-009,
 * {@code V24__data_refresh.sql}).
 */
@Repository
public class DailyCaptureRepository {

    private final JdbcClient db;

    public DailyCaptureRepository(JdbcClient db) {
        this.db = db;
    }

    public boolean exists(Sport sport, LocalDate date, String kind) {
        Integer n = db.sql("select count(*) from daily_capture where sport = ? and capture_date = ? and kind = ?")
                .params(sport.code(), Date.valueOf(date), kind)
                .query(Integer.class)
                .single();
        return n != null && n > 0;
    }

    /** Upsert on {@code (sport, capture_date, kind)}. {@code detail} may be null. */
    public void record(Sport sport, LocalDate date, String kind, Instant completedAt, String detail) {
        db.sql("""
                insert into daily_capture (sport, capture_date, kind, completed_at, detail)
                values (?, ?, ?, ?, ?)
                on conflict (sport, capture_date, kind) do update set
                    completed_at = excluded.completed_at,
                    detail = excluded.detail
                """)
                .params(sport.code(), Date.valueOf(date), kind, Timestamp.from(completedAt), detail)
                .update();
    }
}
