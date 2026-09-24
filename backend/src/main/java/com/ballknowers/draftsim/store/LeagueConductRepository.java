package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * The commissioner's own list of conduct the automatic suspension read can't
 * see (specs/008-season-superlatives, data-model.md, research R12). Scoped to
 * one league ROW -- one league-season, not the league chain -- so FR-017's
 * "never visible to another league" is structural and a new season starts
 * with an empty list (spec amendment 9).
 */
@Repository
public class LeagueConductRepository {

    private final JdbcClient db;

    public LeagueConductRepository(JdbcClient db) {
        this.db = db;
    }

    /** @param addedByManagerId null when added by the configured app owner, who has no manager row in the league. */
    public record Entry(long id, long leagueId, String playerId, String reason, int appliesFromWeek,
                        Long addedByManagerId, Instant createdAt) {}

    public List<Entry> forLeague(long leagueId) {
        return db.sql("""
                select id, league_id, sleeper_player_id, reason, applies_from_week, added_by_manager_id, created_at
                from league_conduct_entry
                where league_id = ?
                order by created_at
                """)
                .param(leagueId)
                .query(LeagueConductRepository::toEntry)
                .list();
    }

    /**
     * Upsert on (league_id, sleeper_player_id) -- editing an entry replaces
     * its reason and week, but NOT who added it.
     *
     * <p><b>Fixed 2026-09-23 (coordinator follow-up):</b> the update branch
     * used to also set {@code added_by_manager_id = excluded.added_by_manager_id},
     * so every edit silently reassigned credit/blame to whoever happened to
     * make the edit. {@code added_by_manager_id} is simply left out of the
     * {@code do update set} clause now, which in Postgres means the column is
     * untouched on conflict -- the original adder from the INSERT branch
     * survives every subsequent edit.
     */
    public Entry upsert(long leagueId, String playerId, String reason, int appliesFromWeek, Long addedByManagerId) {
        return db.sql("""
                insert into league_conduct_entry (league_id, sleeper_player_id, reason, applies_from_week, added_by_manager_id)
                values (?, ?, ?, ?, ?)
                on conflict (league_id, sleeper_player_id) do update set
                    reason = excluded.reason,
                    applies_from_week = excluded.applies_from_week
                returning id, league_id, sleeper_player_id, reason, applies_from_week, added_by_manager_id, created_at
                """)
                .params(leagueId, playerId, reason, appliesFromWeek, addedByManagerId)
                .query(LeagueConductRepository::toEntry)
                .single();
    }

    /**
     * True when a row in THIS league was deleted. An id belonging to another
     * league matches nothing (the where clause pins {@code league_id}), so an
     * entry id can't be used to reach across leagues (contract's DELETE 404
     * rule).
     */
    public boolean delete(long leagueId, long entryId) {
        int rows = db.sql("delete from league_conduct_entry where id = ? and league_id = ?")
                .params(entryId, leagueId)
                .update();
        return rows > 0;
    }

    private static Entry toEntry(ResultSet rs, int n) throws SQLException {
        long addedBy = rs.getLong("added_by_manager_id");
        Long addedByManagerId = rs.wasNull() ? null : addedBy;
        return new Entry(rs.getLong("id"), rs.getLong("league_id"), rs.getString("sleeper_player_id"),
                rs.getString("reason"), rs.getInt("applies_from_week"), addedByManagerId,
                rs.getTimestamp("created_at").toInstant());
    }
}
