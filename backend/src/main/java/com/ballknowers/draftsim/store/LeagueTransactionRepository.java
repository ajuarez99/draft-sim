package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Roster moves: waiver claims, free-agent adds and drops, and trades
 * (specs/004-ffwrapped-feature-parity, US6).
 *
 * <p>Sport-neutral by construction. Sleeper's transaction payload has the same
 * shape for basketball, and nothing stored here encodes a position.
 */
@Repository
public class LeagueTransactionRepository {

    private final JdbcClient db;

    public LeagueTransactionRepository(JdbcClient db) {
        this.db = db;
    }

    public record Row(long leagueId, int season, int week, String sleeperTransactionId,
                      String type, String status, Integer rosterId, Long managerId,
                      String addsJson, String dropsJson, Integer faabBid, Instant createdAt) {}

    /**
     * Idempotent on {@code (league_id, sleeper_transaction_id)}: re-ingesting a
     * week updates its moves rather than duplicating them. A transaction can
     * legitimately change -- a pending waiver becomes complete or failed -- so
     * this updates rather than doing nothing on conflict.
     */
    public void upsert(Row r) {
        db.sql("""
                insert into league_transaction (league_id, season, week, sleeper_transaction_id, type,
                                                status, roster_id, manager_id, adds, drops, faab_bid,
                                                created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                on conflict (league_id, sleeper_transaction_id) do update set
                    season = excluded.season,
                    week = excluded.week,
                    type = excluded.type,
                    status = excluded.status,
                    roster_id = excluded.roster_id,
                    manager_id = excluded.manager_id,
                    adds = excluded.adds,
                    drops = excluded.drops,
                    faab_bid = excluded.faab_bid,
                    created_at = excluded.created_at
                """)
                .params(r.leagueId(), r.season(), r.week(), r.sleeperTransactionId(), r.type(),
                        r.status(), r.rosterId(), r.managerId(), r.addsJson(), r.dropsJson(),
                        r.faabBid(), r.createdAt() == null ? null : java.sql.Timestamp.from(r.createdAt()))
                .update();
    }

    /**
     * Weeks already ingested for this league-season.
     *
     * <p>Keyed on THIS table, deliberately, not on roster_week_points' gate.
     * Reusing that one would be research R6's bug in a new place: a week with
     * scores but no transactions would look settled and never be fetched.
     */
    public Set<Integer> storedWeeks(long leagueId, int season) {
        return db.sql("select distinct week from league_transaction where league_id = ? and season = ?")
                .params(leagueId, season)
                .query(Integer.class)
                .list()
                .stream().collect(Collectors.toSet());
    }

    public List<Row> forSeason(long leagueId, int season) {
        return db.sql("""
                select league_id, season, week, sleeper_transaction_id, type, status, roster_id,
                       manager_id, adds::text, drops::text, faab_bid, created_at
                from league_transaction
                where league_id = ? and season = ?
                order by week, created_at nulls last, id
                """)
                .params(leagueId, season)
                .query((rs, i) -> new Row(
                        rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                        rs.getString(6),
                        (Integer) rs.getObject(7), (Long) rs.getObject(8),
                        rs.getString(9), rs.getString(10),
                        (Integer) rs.getObject(11),
                        rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant()))
                .list();
    }
}
