package com.ballknowers.draftsim.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * SQL for usage analytics (spec 076): the {@code usage_daily} upsert and reads, and the Redraft funnel read
 * straight from {@code historical_game}. Days are America/New_York calendar days throughout.
 */
@Repository
public class UsageRepository {

    private final JdbcTemplate jdbc;

    public UsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds 1 to one counter row, creating it if it is new. */
    public void increment(LocalDate day, String site, String kind, String route, String name, String props,
                          String leagueId, String sport) {
        add(day, site, kind, route, name, props, leagueId, sport, 1);
    }

    /** Adds {@code n} to the {@code rejected} counter for a site and day. */
    public void addRejected(LocalDate day, String site, long n) {
        add(day, site, "rejected", "", "", "", "", "", n);
    }

    private void add(LocalDate day, String site, String kind, String route, String name, String props,
                     String leagueId, String sport, long n) {
        jdbc.update("""
                insert into usage_daily (day, site, kind, route, name, props, league_id, sport, count)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (day, site, kind, route, name, props, league_id)
                do update set count = usage_daily.count + excluded.count
                """, day, site, kind, route, name, props, leagueId, sport, n);
    }

    /** The sport a league row is stored under (as the table spells it), or empty when no such league exists. */
    public Optional<String> leagueSport(String sleeperLeagueId) {
        List<String> rows = jdbc.query("select sport from league where sleeper_id = ?",
                (rs, i) -> rs.getString(1), sleeperLeagueId);
        return rows.stream().findFirst();
    }

    public record SiteSince(String site, LocalDate since) {}

    /**
     * Per site, the earliest day with a view, league-view or event row. A site with no such row has no entry.
     * Rejected rows do not count as "counting".
     */
    public List<SiteSince> countingSince() {
        return jdbc.query("""
                select site, min(day) from usage_daily where kind in ('view', 'league-view', 'event')
                group by site order by site
                """, (rs, i) -> new SiteSince(rs.getString(1), rs.getObject(2, LocalDate.class)));
    }

    /** ET date of the oldest Redraft game, or empty when there are none. */
    public Optional<LocalDate> gamesSince() {
        List<LocalDate> rows = jdbc.query("""
                select min((created_at at time zone 'America/New_York')::date) from historical_game
                """, (rs, i) -> rs.getObject(1, LocalDate.class));
        return rows.stream().filter(d -> d != null).findFirst();
    }

    public record DayViews(LocalDate day, String site, long views) {}

    public record RouteViews(String site, String route, long views) {}

    public record LeagueViews(String leagueId, String leagueName, String sport, long views) {}

    public record EventCount(String site, String name, String props, long count) {}

    /** Raw funnel row; {@code sport} is as the table spells it. */
    public record FunnelRow(LocalDate day, String sport, long started, long finished, long abandoned, long drafting,
                            long guestStarted, long guestFinished, long unknownStarted,
                            long dailyStarted, long dailyFinished, long ownerStarted, long ownerFinished,
                            long ownerAbandoned, long ownerDrafting, long ownerDailyStarted, long ownerDailyFinished) {}

    public List<DayViews> viewsByDay(LocalDate from, LocalDate to) {
        return jdbc.query("""
                select day, site, sum(count) from usage_daily
                where kind = 'view' and day between ? and ?
                group by day, site order by day, site
                """, (rs, i) -> new DayViews(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getLong(3)), from, to);
    }

    public List<RouteViews> viewsByRoute(LocalDate from, LocalDate to) {
        return jdbc.query("""
                select site, route, sum(count) as n from usage_daily
                where kind = 'view' and day between ? and ?
                group by site, route order by n desc, site, route
                """, (rs, i) -> new RouteViews(rs.getString(1), rs.getString(2), rs.getLong(3)), from, to);
    }

    /** The league name is joined at read time and is null when the league row is gone. */
    public List<LeagueViews> leagueViews(LocalDate from, LocalDate to) {
        return jdbc.query("""
                select u.league_id, l.name, u.sport, sum(u.count) as n
                from usage_daily u left join league l on l.sleeper_id = u.league_id
                where u.kind = 'league-view' and u.day between ? and ?
                group by u.league_id, l.name, u.sport order by n desc, u.league_id
                """, (rs, i) -> new LeagueViews(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                from, to);
    }

    public List<EventCount> events(LocalDate from, LocalDate to) {
        return jdbc.query("""
                select site, name, props, sum(count) as n from usage_daily
                where kind = 'event' and day between ? and ?
                group by site, name, props order by n desc, site, name, props
                """, (rs, i) -> new EventCount(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                from, to);
    }

    public long rejected(LocalDate from, LocalDate to) {
        Long n = jdbc.queryForObject("""
                select coalesce(sum(count), 0) from usage_daily
                where kind = 'rejected' and day between ? and ?
                """, Long.class, from, to);
        return n == null ? 0 : n;
    }

    /**
     * The Redraft funnel per start day (ET) and sport, straight from the saved games. {@code ownerId} null or
     * blank means no owner is configured: the owner counts are then 0.
     *
     * <p>Owner counts are the owner's SIGNED-IN games only ({@code started_as_guest is false}). That keeps four
     * buckets disjoint: guest ({@code is true}), unknown ({@code is null}), owner (false and the owner's id) and
     * other signed-in member (false and anyone else). Claiming a guest game rewrites {@code sleeper_user_id} but
     * not {@code started_as_guest}, so a claimed game stays a guest game and is never also an owner game. A null
     * flag on an owner-owned row stays "unknown", not owner.
     */
    public List<FunnelRow> funnel(LocalDate from, LocalDate to, String ownerId) {
        String owner = (ownerId == null || ownerId.isBlank()) ? null : ownerId;
        return jdbc.query("""
                select day, sport,
                       count(*) as started,
                       count(*) filter (where status = 'COMPLETE') as finished,
                       count(*) filter (where status = 'ABANDONED') as abandoned,
                       count(*) filter (where status = 'DRAFTING') as drafting,
                       count(*) filter (where started_as_guest is true) as guest_started,
                       count(*) filter (where started_as_guest is true and status = 'COMPLETE') as guest_finished,
                       count(*) filter (where started_as_guest is null) as unknown_started,
                       count(*) filter (where daily_date is not null) as daily_started,
                       count(*) filter (where daily_date is not null and status = 'COMPLETE') as daily_finished,
                       count(*) filter (where is_owner) as owner_started,
                       count(*) filter (where is_owner and status = 'COMPLETE') as owner_finished,
                       count(*) filter (where is_owner and status = 'ABANDONED') as owner_abandoned,
                       count(*) filter (where is_owner and status = 'DRAFTING') as owner_drafting,
                       count(*) filter (where is_owner and daily_date is not null) as owner_daily_started,
                       count(*) filter (where is_owner and daily_date is not null and status = 'COMPLETE') as owner_daily_finished
                from (select (created_at at time zone 'America/New_York')::date as day, sport, status, daily_date,
                             started_as_guest,
                             coalesce(sleeper_user_id = cast(? as text), false) and started_as_guest is false as is_owner
                      from historical_game) g
                where day between ? and ?
                group by 1, 2 order by 1, 2
                """, (rs, i) -> new FunnelRow(rs.getObject(1, LocalDate.class), rs.getString(2),
                        rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                        rs.getLong(9), rs.getLong(10), rs.getLong(11), rs.getLong(12), rs.getLong(13),
                        rs.getLong(14), rs.getLong(15), rs.getLong(16), rs.getLong(17)),
                owner, from, to);
    }
}
