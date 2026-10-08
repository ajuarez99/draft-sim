package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.RosterOwners.RosterOwner;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.RosterSeasonRepository.Rostered;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository.WeekBreakdown;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who owns a player at a point in time: the pure resolver for specs/022-player-stat-analysis
 * data-model "Ownership" (as amended after review F2, F5, F6, N6). No I/O: {@link Facts} carries
 * everything it reads, and the caller loads it.
 *
 * <table>
 *   <caption>Point in time to source</caption>
 *   <tr><td>current season, page view</td><td>V28 rosters, draft-gated: {@code CURRENT}</td></tr>
 *   <tr><td>completed season, page view</td><td>{@code players_points} of week {@code playoff_week_start - 1}: {@code WEEK}</td></tr>
 *   <tr><td>a night in a scored week</td><td>that week's {@code players_points}: {@code WEEK}</td></tr>
 *   <tr><td>a night in the current, unscored week</td><td>V28 rosters, draft-gated: {@code CURRENT}</td></tr>
 *   <tr><td>a night after the league's last week</td><td>{@code UNAVAILABLE}</td></tr>
 * </table>
 *
 * <p>A week in which any roster's {@code players_points} is {@code {}} is wholly {@code UNAVAILABLE}
 * (N6): otherwise that roster's players would read as free agents. A past season's ownership never uses
 * current rosters (I8); the only thing that may is the separately labelled {@code currentOwnership},
 * built with {@link #currentOf}.
 *
 * <p>Whether a season is "current" is the league's own status: anything other than {@code complete}
 * ({@code LeagueRow.isComplete}), the rule Trends gates on. The draft gate is also Trends': status
 * {@code pre_draft} or {@code drafting} is {@code NOT_DRAFTED}, and a null status with no one rostered
 * is too (B7).
 *
 * <p>The {@code asOf} of an {@code UNAVAILABLE} or {@code NOT_DRAFTED} answer is the week asked about
 * when there was one ({@code WEEK}), else null.
 */
public final class PlayerOwnership {

    private PlayerOwnership() {}

    public static final String ROSTERED = "ROSTERED";
    public static final String FREE_AGENT = "FREE_AGENT";
    public static final String NOT_DRAFTED = "NOT_DRAFTED";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String CURRENT = "CURRENT";
    public static final String WEEK = "WEEK";

    /**
     * {@code kind} is {@code CURRENT} (with {@code fetchedAt}) or {@code WEEK} (with {@code week}); the
     * field that does not apply is null.
     */
    public record AsOf(String kind, OffsetDateTime fetchedAt, Integer week) {
        static AsOf current(OffsetDateTime fetchedAt) {
            return new AsOf(CURRENT, fetchedAt, null);
        }

        static AsOf week(int week) {
            return new AsOf(WEEK, null, week);
        }
    }

    /**
     * {@code rosterId}, {@code ownerName} and {@code avatarId} are set only for {@code ROSTERED}
     * ({@code avatarId} may be null even then). {@code asOf} may be null (see the class javadoc).
     */
    public record Ownership(String state, Integer rosterId, String ownerName, String avatarId, boolean isMe,
                            AsOf asOf) {
        static Ownership none(String state, AsOf asOf) {
            return new Ownership(state, null, null, null, false, asOf);
        }
    }

    /**
     * Everything the resolver reads, for one league-season.
     *
     * @param leagueStatus    {@code league.status}, nullable (unknown reads as not complete)
     * @param playoffWeekStart {@code playoff_week_start} from {@code settings_json}, nullable
     * @param currentLeg      the league's current week ({@code settings_json.leg}), nullable
     * @param lastWeek        the league's last week, nullable (unknown: no night is "after" it)
     * @param current         V28 rosters; null when never fetched
     * @param weekRosters     scored week to roster id to that roster's player ids, from
     *                        {@code roster_week_points}; empty when not loaded
     * @param owners          roster id to owner, from {@link RosterOwners#ownerNames}
     */
    public record Facts(String leagueStatus, Integer playoffWeekStart, Integer currentLeg, Integer lastWeek,
                        Rostered current, Map<Integer, Map<Integer, Set<String>>> weekRosters,
                        Map<Integer, RosterOwner> owners) {}

    /** Week to roster id to the keys of that roster's {@code players_points}. */
    public static Map<Integer, Map<Integer, Set<String>>> weekRosters(List<WeekBreakdown> breakdowns) {
        Map<Integer, Map<Integer, Set<String>>> out = new HashMap<>();
        for (WeekBreakdown w : breakdowns) {
            Set<String> ids = new HashSet<>();
            if (w.playersPointsJson() != null && !w.playersPointsJson().isBlank()) {
                ids.addAll(JsonUtil.readMap(w.playersPointsJson()).keySet());
            }
            out.computeIfAbsent(w.week(), k -> new HashMap<>()).put(w.rosterId(), ids);
        }
        return out;
    }

    /** The player page: current rosters for a season in progress, the end of the regular season once it is over. */
    public static Ownership forSeasonView(String sleeperPlayerId, Facts f) {
        if (!isComplete(f)) return currentOf(sleeperPlayerId, f);
        Integer start = f.playoffWeekStart();
        if (start == null || start < 2) return Ownership.none(UNAVAILABLE, null);
        return ofWeek(sleeperPlayerId, f, start - 1);
    }

    /**
     * Every player rostered at the point {@link #forSeasonView} reads, or empty when that answer is
     * {@code UNAVAILABLE} or {@code NOT_DRAFTED} for the league as a whole (so "rostered" has no meaning).
     * The same gates as {@code forSeasonView}/{@code currentOf}/{@code ofWeek}; a test asserts that every id
     * returned here reads {@code ROSTERED} there.
     */
    public static java.util.Optional<Set<String>> rosteredAtSeasonView(Facts f) {
        if (!isComplete(f)) {
            String st = f.leagueStatus();
            if ("pre_draft".equals(st) || "drafting".equals(st)) return java.util.Optional.empty();
            Rostered cur = f.current();
            if (cur == null) return java.util.Optional.empty();
            if (st == null && cur.byPlayer().isEmpty()) return java.util.Optional.empty();
            return java.util.Optional.of(cur.byPlayer().keySet());
        }
        Integer start = f.playoffWeekStart();
        if (start == null || start < 2) return java.util.Optional.empty();
        Map<Integer, Set<String>> rosters = f.weekRosters().get(start - 1);
        if (rosters == null || rosters.isEmpty()) return java.util.Optional.empty();
        Set<String> all = new HashSet<>();
        for (Set<String> ids : rosters.values()) {
            if (ids.isEmpty()) return java.util.Optional.empty();
            all.addAll(ids);
        }
        return java.util.Optional.of(all);
    }

    /** A night in fantasy week {@code week}: see the class table. */
    public static Ownership forNight(String sleeperPlayerId, Facts f, int week) {
        if (f.weekRosters().containsKey(week)) return ofWeek(sleeperPlayerId, f, week);
        if (f.lastWeek() != null && week > f.lastWeek()) return Ownership.none(UNAVAILABLE, null);
        if (isComplete(f)) return Ownership.none(UNAVAILABLE, null);
        if (f.currentLeg() != null && week == f.currentLeg()) return currentOf(sleeperPlayerId, f);
        return Ownership.none(UNAVAILABLE, null);
    }

    /**
     * The V28 rosters now, draft-gated. Used for a season in progress and, on its own, for the
     * separately labelled {@code currentOwnership} of a requested season the page fell back from (F6).
     */
    public static Ownership currentOf(String sleeperPlayerId, Facts f) {
        String st = f.leagueStatus();
        if ("pre_draft".equals(st) || "drafting".equals(st)) return Ownership.none(NOT_DRAFTED, null);
        Rostered cur = f.current();
        if (cur == null) return Ownership.none(UNAVAILABLE, null);
        if (st == null && cur.byPlayer().isEmpty()) return Ownership.none(NOT_DRAFTED, null);
        AsOf asOf = AsOf.current(cur.fetchedAt());
        Integer rid = cur.byPlayer().get(sleeperPlayerId);
        if (rid == null) return Ownership.none(FREE_AGENT, asOf);
        return rostered(rid, f.owners(), asOf);
    }

    private static Ownership ofWeek(String sleeperPlayerId, Facts f, int week) {
        AsOf asOf = AsOf.week(week);
        Map<Integer, Set<String>> rosters = f.weekRosters().get(week);
        if (rosters == null || rosters.isEmpty()) return Ownership.none(UNAVAILABLE, asOf);
        for (Set<String> ids : rosters.values()) {
            if (ids.isEmpty()) return Ownership.none(UNAVAILABLE, asOf);        // N6: a {} roster poisons the week
        }
        for (Map.Entry<Integer, Set<String>> e : rosters.entrySet()) {
            if (e.getValue().contains(sleeperPlayerId)) return rostered(e.getKey(), f.owners(), asOf);
        }
        return Ownership.none(FREE_AGENT, asOf);
    }

    private static Ownership rostered(int rosterId, Map<Integer, RosterOwner> owners, AsOf asOf) {
        RosterOwner o = owners.get(rosterId);
        String name = o == null ? "Roster " + rosterId : o.name();
        return new Ownership(ROSTERED, rosterId, name, o == null ? null : o.avatarId(), o != null && o.isMe(), asOf);
    }

    private static boolean isComplete(Facts f) {
        return com.ballknowers.draftsim.store.LeagueRepository.LeagueRow.isComplete(f.leagueStatus());
    }
}
