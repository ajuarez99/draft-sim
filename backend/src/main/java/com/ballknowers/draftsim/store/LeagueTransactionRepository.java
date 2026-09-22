package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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

    /**
     * One manager's move counts by type, per league-season, within a SET of
     * league ids -- a career spans several
     * (specs/006-deeper-history-both-sports T069). Grouped by
     * {@code league_id} rather than collapsed into one number here: the
     * caller ({@code TransactionAnalysisService.careerWaiverTendency})
     * applies each season's own {@code waiver_type}/{@code waiver_budget}
     * rule before summing anything across seasons (research R8), and that
     * rule belongs to the service, not this repository.
     *
     * <p>Reads the {@code manager_id} column this table already carries
     * rather than re-deriving roster ownership: {@code TransactionIngestService}
     * stamps it at ingest time from that same season's {@code roster_season},
     * and it is fully populated for WAIVER and FREE_AGENT (560/560, 2536/2536
     * measured) and correctly null for every TRADE, which names several
     * rosters and cannot be attributed to one manager (research R8) -- so
     * {@code where manager_id = ?} already excludes every trade for free.
     */
    public record ManagerTypeCount(long leagueId, String type, int count) {}

    public List<ManagerTypeCount> movesByManager(long managerId, Collection<Long> leagueIds) {
        if (leagueIds == null || leagueIds.isEmpty()) return List.of();
        String sql = """
                select league_id, type, count(*)
                from league_transaction
                where manager_id = ? and league_id in (%s)
                group by league_id, type
                """.formatted(RosterWeekPointsRepository.inClause(leagueIds));
        List<Object> params = new ArrayList<>();
        params.add(managerId);
        params.addAll(leagueIds);
        return db.sql(sql)
                .params(params)
                .query((rs, i) -> new ManagerTypeCount(rs.getLong(1), rs.getString(2), rs.getInt(3)))
                .list();
    }

    /**
     * Every FAAB bid one manager placed, one row per {@code WAIVER} claim
     * that actually carried a {@code faab_bid} -- not pre-aggregated, because
     * the normalisation rule (a fraction of THIS season's own
     * {@code waiver_budget}) must run per-claim, before anything sums across
     * seasons of different budgets (research R8, data-model.md FaabTendency).
     * A waiver-PRIORITY season ({@code waiver_type} 0) produces no rows here
     * at all: its {@code WAIVER} claims carry no bid (321 NFL-2025 rows
     * measured, zero bids), which is the correct format for that season, not
     * a gap to backfill.
     */
    public record FaabBidRow(long leagueId, int bid, boolean successful) {}

    public List<FaabBidRow> faabBidsByManager(long managerId, Collection<Long> leagueIds) {
        if (leagueIds == null || leagueIds.isEmpty()) return List.of();
        String sql = """
                select league_id, faab_bid, status
                from league_transaction
                where manager_id = ? and league_id in (%s)
                  and type = 'WAIVER' and faab_bid is not null
                """.formatted(RosterWeekPointsRepository.inClause(leagueIds));
        List<Object> params = new ArrayList<>();
        params.add(managerId);
        params.addAll(leagueIds);
        return db.sql(sql)
                .params(params)
                // "complete" is the only successful status this table stores
                // (the other observed value is "failed") -- a claim that never
                // resolved to either is treated as unsuccessful rather than
                // assumed won, the same caution status==null gets everywhere
                // else in this codebase.
                .query((rs, i) -> new FaabBidRow(rs.getLong(1), rs.getInt(2), "complete".equals(rs.getString(3))))
                .list();
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
