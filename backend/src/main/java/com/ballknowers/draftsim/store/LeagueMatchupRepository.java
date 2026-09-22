package com.ballknowers.draftsim.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
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

    /**
     * A played game: two rosters that shared a matchup_id in one (season, week),
     * with both scored totals. specs/002-league-history-record-book.
     */
    public record PairedGame(int season, int week,
                             int aRosterId, Long aManagerId, String aManager, String aAvatarId, BigDecimal aPoints,
                             int bRosterId, Long bManagerId, String bManager, String bAvatarId, BigDecimal bPoints) {}

    /**
     * Every PLAYED pairing across a set of league ids (the chain).
     *
     * <p>Three constraints here are load-bearing, and each of them is a wrong
     * answer rather than an error if dropped (data-model R5-R8):
     *
     * <ul>
     *   <li>{@code a.roster_id < b.roster_id} -- without it the self-join returns
     *       every game twice, mirrored, and both copies land in the record book.
     *   <li>{@code matchup_id is not null} on both sides -- a null means no game
     *       that week (bye, odd roster count, unpublished), not a game against
     *       nobody.
     *   <li>an INNER join to roster_week_points on both sides -- this table also
     *       holds FUTURE fixtures, which have a pairing and no score. Treating a
     *       missing score as zero would invent a 130-point blowout for a game
     *       that has not kicked off.
     * </ul>
     */
    public List<PairedGame> pairedWithScores(Collection<Long> leagueIds) {
        if (leagueIds == null || leagueIds.isEmpty()) return List.of();
        String ids = RosterWeekPointsRepository.inClause(leagueIds);
        String sql = """
                select a.season, a.week,
                       a.roster_id, rsa.manager_id, ma.display_name, ma.avatar_id, wa.starters_points,
                       b.roster_id, rsb.manager_id, mb.display_name, mb.avatar_id, wb.starters_points
                from league_matchup a
                join league_matchup b
                  on b.league_id = a.league_id and b.season = a.season and b.week = a.week
                 and b.matchup_id = a.matchup_id and b.roster_id > a.roster_id
                join roster_week_points wa on wa.league_id = a.league_id and wa.week = a.week and wa.roster_id = a.roster_id
                join roster_week_points wb on wb.league_id = b.league_id and wb.week = b.week and wb.roster_id = b.roster_id
                left join roster_season rsa on rsa.league_id = a.league_id and rsa.roster_id = a.roster_id
                left join roster_season rsb on rsb.league_id = b.league_id and rsb.roster_id = b.roster_id
                left join manager ma on ma.id = rsa.manager_id
                left join manager mb on mb.id = rsb.manager_id
                where a.league_id in (%s) and a.matchup_id is not null and b.matchup_id is not null
                order by a.season desc, a.week asc, a.roster_id asc
                """.formatted(ids);
        return db.sql(sql)
                .params(List.copyOf(leagueIds))
                .query((rs, i) -> new PairedGame(rs.getInt(1), rs.getInt(2),
                        rs.getInt(3), (Long) rs.getObject(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7),
                        rs.getInt(8), (Long) rs.getObject(9), rs.getString(10), rs.getString(11), rs.getBigDecimal(12)))
                .list();
    }

    /**
     * Both sides of every SCHEDULED pairing within one league-season, by
     * manager -- no join to {@code roster_week_points}, unlike {@link
     * #pairedWithScores}. specs/006-deeper-history-both-sports T048.
     *
     * <p>Exists for exactly one reason: telling apart the two ways a shared
     * season can produce zero head-to-head meetings between two particular
     * managers (contracts/head-to-head-api.md, US4.4). "The fixtures are
     * scheduled but nobody has played yet" (the 2026 chains, 168/196 rows with
     * no score) and "the schedule simply never paired these two people this
     * season" (true even in a fully-scored season -- a 12-team league does not
     * play a full round robin in 17 weeks) are different facts, and only one
     * of them is fixed by waiting. {@link HeadToHeadService} uses this to pick
     * the right sentence rather than leaving every empty pairing looking the
     * same.
     *
     * <p>Same three constraints as {@code pairedWithScores}'s own javadoc,
     * minus the score join: {@code roster_id <} to avoid a mirrored double
     * row, {@code matchup_id is not null} on both sides because a null means
     * no game that week, not a game against nobody.
     */
    public record ScheduledPair(int season, int week, int aRosterId, Long aManagerId,
                                int bRosterId, Long bManagerId) {}

    public List<ScheduledPair> scheduledPairs(long leagueId) {
        return db.sql("""
                select a.season, a.week, a.roster_id, rsa.manager_id, b.roster_id, rsb.manager_id
                from league_matchup a
                join league_matchup b
                  on b.league_id = a.league_id and b.season = a.season and b.week = a.week
                 and b.matchup_id = a.matchup_id and b.roster_id > a.roster_id
                left join roster_season rsa on rsa.league_id = a.league_id and rsa.roster_id = a.roster_id
                left join roster_season rsb on rsb.league_id = b.league_id and rsb.roster_id = b.roster_id
                where a.league_id = ? and a.matchup_id is not null and b.matchup_id is not null
                order by a.week, a.roster_id
                """)
                .param(leagueId)
                .query((rs, i) -> new ScheduledPair(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        (Long) rs.getObject(4), rs.getInt(5), (Long) rs.getObject(6)))
                .list();
    }
}
