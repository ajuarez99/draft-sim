package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A league member's own ranking of every roster for one week --
 * claude/power-rankings-ballots.md's mode 2. Never touched by ingest: unlike
 * {@code manager} or {@link LeagueMemberRepository}, which are Sleeper's own
 * facts and get rewritten on every re-ingest, a ballot is a person's stated
 * opinion. Nothing in the ingest path may ever write here.
 */
@Repository
public class RankingBallotRepository {

    private final JdbcClient db;

    public RankingBallotRepository(JdbcClient db) {
        this.db = db;
    }

    public record BallotEntry(int rosterId, int rank) {}

    public record Ballot(long id, long managerId, java.time.Instant submittedAt, List<BallotEntry> entries) {}

    /**
     * Replaces this manager's whole ballot for this league/week in one
     * transaction. {@code @Transactional} where the pattern it otherwise
     * mirrors, {@link PowerRankingRepository#save}, is not --
     * claude/plan-review-power-rankings-ballots.md finding 5: that method's
     * delete-then-reinsert has no transaction boundary anywhere on it, so a
     * failure partway through a 12-entry write is a real, silently partial
     * ballot on disk, not a hypothetical. Measured against local PG 17.11:
     * same-transaction delete-then-reinsert of a full, reordered ballot is
     * accepted cleanly, so there is no cost to closing this gap.
     *
     * <p>{@code rosterIdsInOrder} is trusted to already be validated as
     * exactly this league's roster-id set -- the schema cannot enforce that
     * (no FK from {@code ranking_ballot_entry.roster_id} to anything, by
     * design, same as {@code power_ranking_entry}), so the caller (the
     * ballot controller) must have checked it first.
     */
    @Transactional
    public void upsert(long leagueId, int week, long managerId, List<Integer> rosterIdsInOrder) {
        Long ballotId = db.sql("""
                insert into ranking_ballot (league_id, week, manager_id)
                values (?, ?, ?)
                on conflict (league_id, week, manager_id) do update set submitted_at = now()
                returning id
                """)
                .params(leagueId, week, managerId)
                .query(Long.class)
                .single();

        db.sql("delete from ranking_ballot_entry where ballot_id = ?").param(ballotId).update();
        for (int i = 0; i < rosterIdsInOrder.size(); i++) {
            db.sql("insert into ranking_ballot_entry (ballot_id, roster_id, rank) values (?, ?, ?)")
                    .params(ballotId, rosterIdsInOrder.get(i), i + 1)
                    .update();
        }
    }

    public Optional<Ballot> find(long leagueId, int week, long managerId) {
        return oneBallot(rows("select b.id, b.manager_id, b.submitted_at, e.roster_id, e.rank "
                        + "from ranking_ballot b join ranking_ballot_entry e on e.ballot_id = b.id "
                        + "where b.league_id = ? and b.week = ? and b.manager_id = ? order by e.rank",
                leagueId, week, managerId));
    }

    /** Every ballot submitted for this league/week -- the full input to the room's aggregate. */
    public List<Ballot> forWeek(long leagueId, int week) {
        return groupByBallot(rows("select b.id, b.manager_id, b.submitted_at, e.roster_id, e.rank "
                        + "from ranking_ballot b join ranking_ballot_entry e on e.ballot_id = b.id "
                        + "where b.league_id = ? and b.week = ? order by b.id, e.rank",
                leagueId, week));
    }

    /** Every week this league has at least one ballot for -- "zero ballots, the week does not exist for mode 2". */
    public List<Integer> weeksWithBallots(long leagueId) {
        return db.sql("select distinct week from ranking_ballot where league_id = ? order by week")
                .param(leagueId)
                .query(Integer.class)
                .list();
    }

    private record Row(long ballotId, long managerId, java.sql.Timestamp submittedAt, int rosterId, int rank) {}

    private List<Row> rows(String sql, Object... params) {
        return db.sql(sql).params(params)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3), rs.getInt(4), rs.getInt(5)))
                .list();
    }

    private Optional<Ballot> oneBallot(List<Row> rows) {
        List<Ballot> all = groupByBallot(rows);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    private List<Ballot> groupByBallot(List<Row> rows) {
        Map<Long, List<Row>> byBallot = new LinkedHashMap<>();
        for (Row r : rows) byBallot.computeIfAbsent(r.ballotId(), k -> new ArrayList<>()).add(r);

        List<Ballot> out = new ArrayList<>();
        for (var e : byBallot.entrySet()) {
            List<Row> group = e.getValue();
            List<BallotEntry> entries = new ArrayList<>(group.size());
            for (Row r : group) entries.add(new BallotEntry(r.rosterId(), r.rank()));
            out.add(new Ballot(e.getKey(), group.get(0).managerId(), group.get(0).submittedAt().toInstant(), entries));
        }
        return out;
    }
}
