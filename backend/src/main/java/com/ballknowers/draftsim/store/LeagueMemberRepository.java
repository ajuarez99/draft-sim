package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * claude/power-rankings-ballots.md's fix for a membership hole
 * {@link LeagueMembership} could not close on its own -- see V9's migration
 * comment for the full argument. Rewritten wholesale on every league ingest
 * (the two {@code upsertManagers} loops in {@code LeagueIngestService} and
 * {@code LeagueHistoryIngestService}), because Sleeper is the source of truth
 * for who is in a league and a handed-over commissionership should show up on
 * the next re-ingest without anyone doing anything special. Never written
 * from anywhere in the ballot path -- {@code ranking_ballot} is the opposite
 * kind of row, a person's stated opinion that ingest must never touch.
 */
@Repository
public class LeagueMemberRepository {

    private final JdbcClient db;

    public LeagueMemberRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * @param teamName metadata.team_name with the ingest-time fallback to
     *                 display_name already applied ({@code "TBD"} treated as
     *                 absent) -- the further fallback to "roster N" happens
     *                 at read time, once a caller knows which roster this
     *                 manager currently owns.
     */
    public void upsert(long leagueId, long managerId, boolean isCommissioner, String teamName) {
        db.sql("""
                insert into league_member (league_id, manager_id, is_commissioner, team_name)
                values (?, ?, ?, ?)
                on conflict (league_id, manager_id) do update set
                    is_commissioner = excluded.is_commissioner,
                    team_name = excluded.team_name
                """)
                .params(leagueId, managerId, isCommissioner, teamName)
                .update();
    }

    public record MemberRow(long managerId, String managerName, boolean isCommissioner, String teamName) {}

    public List<MemberRow> forLeague(long leagueId) {
        return db.sql("""
                select lm.manager_id, m.display_name, lm.is_commissioner, lm.team_name
                from league_member lm
                join manager m on m.id = lm.manager_id
                where lm.league_id = ?
                order by m.display_name
                """)
                .param(leagueId)
                .query((rs, i) -> new MemberRow(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)))
                .list();
    }

    public boolean isMember(long leagueId, long managerId) {
        return Boolean.TRUE.equals(db.sql(
                "select exists (select 1 from league_member where league_id = ? and manager_id = ?)")
                .params(leagueId, managerId)
                .query(Boolean.class)
                .single());
    }

    public boolean isCommissioner(long leagueId, long managerId) {
        return Boolean.TRUE.equals(db.sql(
                "select exists (select 1 from league_member where league_id = ? and manager_id = ? and is_commissioner)")
                .params(leagueId, managerId)
                .query(Boolean.class)
                .single());
    }

    /**
     * Whether this league has ANY member flagged a commissioner -- the signal
     * behind {@code commissionerKnown} on the wire. False on every league
     * until it has been ingested at least once after V9, which is exactly the
     * "no commissioner detected -- re-run league ingest" state the page is
     * meant to say out loud rather than silently locking everyone out.
     */
    public boolean anyCommissioner(long leagueId) {
        return Boolean.TRUE.equals(db.sql(
                "select exists (select 1 from league_member where league_id = ? and is_commissioner)")
                .param(leagueId)
                .query(Boolean.class)
                .single());
    }
}
