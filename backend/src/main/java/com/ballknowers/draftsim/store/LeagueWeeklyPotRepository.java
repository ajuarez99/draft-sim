package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * The commissioner's weekly-pot switch (specs/048-weekly-high-score-ledger, V41). One row per league ROW
 * (a league-season), so a new season starts off. Absent row = off. Turning it off keeps the stake.
 */
@Repository
public class LeagueWeeklyPotRepository {

    /** The default stake when nothing was ever saved: $1. Mirrors the column default. */
    public static final int DEFAULT_STAKE_CENTS = 100;

    public record Setting(boolean enabled, int stakeCents) {}

    private final JdbcClient db;

    public LeagueWeeklyPotRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<Setting> find(long leagueId) {
        return db.sql("select enabled, stake_cents from league_weekly_pot where league_id = ?")
                .param(leagueId)
                .query((rs, n) -> new Setting(rs.getBoolean(1), rs.getInt(2)))
                .optional();
    }

    /** {@code updatedBy} is null for the app owner, who has no manager row; a null binds the same way LeagueConductRepository's addedByManagerId does. */
    public void upsert(long leagueId, boolean enabled, int stakeCents, Long updatedBy) {
        db.sql("""
                insert into league_weekly_pot (league_id, enabled, stake_cents, updated_by)
                values (?, ?, ?, ?)
                on conflict (league_id) do update set
                    enabled = excluded.enabled,
                    stake_cents = excluded.stake_cents,
                    updated_by = excluded.updated_by,
                    updated_at = now()
                """)
                .params(leagueId, enabled, stakeCents, updatedBy)
                .update();
    }
}
