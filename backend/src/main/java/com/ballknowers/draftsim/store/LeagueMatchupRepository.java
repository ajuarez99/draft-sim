package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The league's fixture list -- who plays whom, including weeks nobody has
 * played yet (claude/playoff-odds.md). Sibling of
 * {@link RosterWeekPointsRepository}, which caches results only and drops the
 * pairing entirely; without this table the app knows what every team scored
 * and not who they played, which is exactly the hole playoff odds fell into.
 */
@Repository
public class LeagueMatchupRepository {

    private final JdbcClient db;

    public LeagueMatchupRepository(JdbcClient db) {
        this.db = db;
    }

    /** {@code matchupId} null = no game that week (bye, odd roster count, or unpublished). */
    public record Row(long leagueId, int season, int week, int rosterId, Integer matchupId) {}

    public void upsert(Row r) {
        db.sql("""
                insert into league_matchup (league_id, season, week, roster_id, matchup_id)
                values (?, ?, ?, ?, ?)
                on conflict (league_id, season, week, roster_id) do update set
                    matchup_id = excluded.matchup_id
                """)
                .params(r.leagueId(), r.season(), r.week(), r.rosterId(), r.matchupId())
                .update();
    }

    /**
     * Weeks with at least one PAIRED roster stored. A week Sleeper answered
     * with every matchup_id null (it does this for weeks it has not scheduled
     * yet) does not count as cached -- otherwise the first ingest of an
     * unscheduled week would poison the cache and the schedule would never
     * arrive.
     */
    public Set<Integer> scheduledWeeks(long leagueId, int season) {
        return db.sql("""
                select distinct week from league_matchup
                where league_id = ? and season = ? and matchup_id is not null
                """)
                .params(leagueId, season)
                .query(Integer.class)
                .list()
                .stream().collect(Collectors.toSet());
    }

    public record Fixture(int week, int rosterId, Integer matchupId) {}

    /** Every stored pairing for weeks {@code fromWeek..toWeek} inclusive, week then roster order. */
    public List<Fixture> between(long leagueId, int season, int fromWeek, int toWeek) {
        return db.sql("""
                select week, roster_id, matchup_id
                from league_matchup
                where league_id = ? and season = ? and week between ? and ? and matchup_id is not null
                order by week, roster_id
                """)
                .params(leagueId, season, fromWeek, toWeek)
                .query((rs, i) -> new Fixture(rs.getInt(1), rs.getInt(2), (Integer) rs.getObject(3)))
                .list();
    }
}
