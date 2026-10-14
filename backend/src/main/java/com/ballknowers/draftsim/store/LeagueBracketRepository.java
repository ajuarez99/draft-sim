package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * Sleeper's winners bracket, one row per match per league-season (specs/034-rivalry-schedule-grid
 * T002, {@code league_bracket_match} in {@code V33__league_bracket_match.sql}).
 *
 * <p><b>An empty write never deletes</b> (plan.md F5). Memory
 * {@code project_sleeper_nba_2025_matchup_drift} records Sleeper serving degraded data for old
 * seasons; a transient {@code []} must not wipe a correct bracket and still report success. A
 * non-empty write replaces the season's rows wholesale, in one transaction, so a re-ingest never
 * leaves a mix of two fetches.
 */
@Repository
public class LeagueBracketRepository {

    private final JdbcClient db;

    public LeagueBracketRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * @param t1RosterId / {@code t2RosterId} null while the slot still points at an unplayed match
     * @param placement  Sleeper's {@code p}: 1 final, 3 third place, 5 fifth place, null elimination
     */
    public record Match(long leagueId, int matchId, int round, Integer t1RosterId, Integer t2RosterId,
                        Integer winnerRosterId, Integer loserRosterId, Integer placement) {}

    /** No-op when {@code rows} is empty (F5); otherwise delete + insert in one transaction. */
    @Transactional
    public void replaceForLeague(long leagueId, List<Match> rows) {
        if (rows == null || rows.isEmpty()) return;
        db.sql("delete from league_bracket_match where league_id = ?").param(leagueId).update();
        for (Match m : rows) {
            db.sql("""
                    insert into league_bracket_match
                        (league_id, match_id, round, t1_roster_id, t2_roster_id,
                         winner_roster_id, loser_roster_id, placement)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(leagueId, m.matchId(), m.round(), m.t1RosterId(), m.t2RosterId(),
                            m.winnerRosterId(), m.loserRosterId(), m.placement())
                    .update();
        }
    }

    public List<Match> forLeagues(Collection<Long> leagueIds) {
        if (leagueIds == null || leagueIds.isEmpty()) return List.of();
        return db.sql("select " + COLUMNS + " from league_bracket_match where league_id in ("
                        + RosterWeekPointsRepository.inClause(leagueIds) + ") order by league_id, round, match_id")
                .params(List.copyOf(leagueIds))
                .query((rs, i) -> new Match(rs.getLong(1), rs.getInt(2), rs.getInt(3),
                        (Integer) rs.getObject(4), (Integer) rs.getObject(5), (Integer) rs.getObject(6),
                        (Integer) rs.getObject(7), (Integer) rs.getObject(8)))
                .list();
    }

    private static final String COLUMNS =
            "league_id, match_id, round, t1_roster_id, t2_roster_id, winner_roster_id, loser_roster_id, placement";

    public int count(long leagueId) {
        Integer n = db.sql("select count(*) from league_bracket_match where league_id = ?")
                .param(leagueId).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    /** Has rows and every row has a winner: the bracket is fully played and frozen (F5). */
    public boolean isComplete(long leagueId) {
        return Boolean.TRUE.equals(db.sql("""
                select count(*) > 0 and bool_and(winner_roster_id is not null)
                from league_bracket_match where league_id = ?
                """).param(leagueId).query(Boolean.class).single());
    }
}
