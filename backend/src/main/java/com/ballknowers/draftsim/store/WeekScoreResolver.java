package com.ballknowers.draftsim.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The one rule for "what did this team score this week" (spec 057): a commissioner-entered score first
 * ({@code week_score_override}, V47), then Sleeper's official score recovered from season-total snapshots
 * ({@code roster_fpts_snapshot}, spec 055, behind its plausibility gate), then the public /matchups feed
 * ({@code roster_week_points}). Applied inside the store reads that hand out team-week totals
 * ({@link LeagueMatchupRepository#pairedWithScores}, {@link RosterWeekPointsRepository#allScores},
 * {@code through}, {@code extremes}), so every consumer of those reads gets the same answer.
 *
 * <p>Owns its own {@link JdbcClient} and depends on neither repository it serves, so there is no cycle.
 * Per-player breakdowns are never resolved (FR-012).
 *
 * <p><b>A week never mixes sources.</b> A non-FEED week that lacks a roster the feed has resolves the whole
 * week as FEED, with a warning.
 */
@Component
public class WeekScoreResolver {

    private static final Logger log = LoggerFactory.getLogger(WeekScoreResolver.class);

    public enum Source { COMMISSIONER, OFFICIAL, FEED }

    /**
     * How far an official week's league-wide total may sit from the feed's before the official week is
     * distrusted and the feed is used instead. Hand-set and arbitrary: measured drift on NBA 2026 was about 8%
     * (claude/nba-2026-score-drift.md), while a snapshot taken a leg late doubles one week and zeroes the next
     * (about 100% off), which is the failure this exists to catch (claude/official-week-scores-code-review.md H1).
     */
    static final BigDecimal MAX_OFFICIAL_DRIFT = new BigDecimal("0.25");

    /** One week's answer. {@code points} is empty for FEED (the caller's own feed value is used). */
    public record Resolved(Source source, Map<Integer, BigDecimal> points) {}

    /** The answer for a set of league-seasons: week -> source and points, per league. */
    public static final class Resolution {
        /** Nothing overridden or official: every read returns its feed rows unchanged. */
        public static final Resolution EMPTY = new Resolution(Map.of());

        private final Map<Long, Map<Integer, Resolved>> byLeague;

        Resolution(Map<Long, Map<Integer, Resolved>> byLeague) {
            this.byLeague = byLeague;
        }

        /** For tests of consumers that need a resolution without a database. */
        public static Resolution of(Map<Long, Map<Integer, Resolved>> byLeague) {
            return new Resolution(byLeague);
        }

        /** True when nothing is overridden or official anywhere: reads return their rows unchanged. */
        public boolean isEmpty() {
            return byLeague.values().stream().allMatch(m ->
                    m.values().stream().allMatch(r -> r.source() == Source.FEED));
        }

        /** The resolved score, or {@code feed} when the week is FEED (or has no value for this roster). */
        public BigDecimal points(long leagueId, int week, int rosterId, BigDecimal feed) {
            Map<Integer, Resolved> weeks = byLeague.get(leagueId);
            if (weeks == null) return feed;
            Resolved r = weeks.get(week);
            if (r == null || r.source() == Source.FEED) return feed;
            BigDecimal v = r.points().get(rosterId);
            return v != null ? v : feed;
        }

        public Source source(long leagueId, int week) {
            Map<Integer, Resolved> weeks = byLeague.get(leagueId);
            Resolved r = weeks == null ? null : weeks.get(week);
            return r == null ? Source.FEED : r.source();
        }

        /** Week -> source for every week this league has a stored score or a non-feed answer for. */
        public Map<Integer, Source> sources(long leagueId) {
            Map<Integer, Source> out = new TreeMap<>();
            Map<Integer, Resolved> weeks = byLeague.get(leagueId);
            if (weeks != null) weeks.forEach((w, r) -> out.put(w, r.source()));
            return out;
        }
    }

    private final JdbcClient db;

    public WeekScoreResolver(JdbcClient db) {
        this.db = db;
    }

    /** Batch resolve. Leagues with no override and no snapshot cost one probe query and nothing else. */
    public Resolution resolve(Collection<Long> leagueIds) {
        if (leagueIds == null || leagueIds.isEmpty()) return Resolution.EMPTY;
        List<Long> ids = List.copyOf(new TreeSet<>(leagueIds));
        String in = RosterWeekPointsRepository.inClause(ids);
        List<Object> twice = new ArrayList<>(ids);
        twice.addAll(ids);
        List<Long> needed = db.sql("""
                select league_id from week_score_override where league_id in (%s)
                union
                select league_id from roster_fpts_snapshot where league_id in (%s)
                """.formatted(in, in))
                .params(twice)
                .query(Long.class).list();
        if (needed.isEmpty()) return Resolution.EMPTY;
        String inNeeded = RosterWeekPointsRepository.inClause(needed);

        Map<Long, Map<Integer, Map<Integer, BigDecimal>>> overrides =
                weekRosterValues("select league_id, week, roster_id, points from week_score_override", inNeeded, needed);
        Map<Long, Map<Integer, Map<Integer, BigDecimal>>> snapshots =
                weekRosterValues("select league_id, through_week, roster_id, fpts from roster_fpts_snapshot", inNeeded, needed);
        Map<Long, Map<Integer, Map<Integer, BigDecimal>>> feed =
                weekRosterValues("select league_id, week, roster_id, starters_points from roster_week_points", inNeeded, needed);
        Map<Long, Set<Integer>> rosters = new HashMap<>();
        db.sql("select league_id, roster_id from roster_season where league_id in (" + inNeeded + ")")
                .params(List.copyOf(needed))
                .query((rs, i) -> {
                    rosters.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getInt(2));
                    return 0;
                }).list();

        Map<Long, Map<Integer, Resolved>> out = new HashMap<>();
        for (long id : needed) {
            out.put(id, resolveLeague(id,
                    overrides.getOrDefault(id, Map.of()), snapshots.getOrDefault(id, Map.of()),
                    rosters.getOrDefault(id, Set.of()), feed.getOrDefault(id, Map.of())));
        }
        return new Resolution(out);
    }

    private Map<Long, Map<Integer, Map<Integer, BigDecimal>>> weekRosterValues(String select, String inClause,
                                                                               List<Long> leagueIds) {
        Map<Long, Map<Integer, Map<Integer, BigDecimal>>> out = new HashMap<>();
        db.sql(select + " where league_id in (" + inClause + ")")
                .params(List.copyOf(leagueIds))
                .query((rs, i) -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new TreeMap<>())
                            .computeIfAbsent(rs.getInt(2), k -> new HashMap<>())
                            .put(rs.getInt(3), rs.getBigDecimal(4));
                    return 0;
                }).list();
        return out;
    }

    /** One league's week -> source, FEED weeks included (the weeks it has any stored score for). */
    public Map<Integer, Source> sources(long leagueId) {
        return resolve(List.of(leagueId)).sources(leagueId);
    }

    /**
     * The rule itself, pure. Per week: overrides, else a plausible official week, else the feed. Weeks are
     * the union of everything that has any answer, so a caller can read a source for a FEED week too.
     */
    static Map<Integer, Resolved> resolveLeague(long leagueId,
                                                Map<Integer, Map<Integer, BigDecimal>> overrides,
                                                Map<Integer, Map<Integer, BigDecimal>> snapshots,
                                                Set<Integer> rosterIds,
                                                Map<Integer, Map<Integer, BigDecimal>> feedByWeek) {
        Map<Integer, Map<Integer, BigDecimal>> official =
                RosterFptsSnapshotRepository.officialWeekScores(snapshots, rosterIds);
        Set<Integer> weeks = new TreeSet<>(feedByWeek.keySet());
        weeks.addAll(overrides.keySet());
        weeks.addAll(official.keySet());

        Map<Integer, Resolved> out = new TreeMap<>();
        for (int w : weeks) {
            Map<Integer, BigDecimal> feed = feedByWeek.getOrDefault(w, Map.of());
            Map<Integer, BigDecimal> override = overrides.get(w);
            if (override != null) {
                if (override.keySet().containsAll(feed.keySet())) {
                    out.put(w, new Resolved(Source.COMMISSIONER, override));
                } else {
                    log.warn("league {} week {}: commissioner scores lack a roster the feed has; using the feed for the whole week",
                            leagueId, w);
                    out.put(w, new Resolved(Source.FEED, Map.of()));
                }
                continue;
            }
            Map<Integer, BigDecimal> plausible = plausibleOfficial(official.get(w), feed);
            out.put(w, plausible != null ? new Resolved(Source.OFFICIAL, plausible)
                    : new Resolved(Source.FEED, Map.of()));
        }
        return out;
    }

    /**
     * The official week, or null when it should not be trusted over the feed: a roster the feed has but the
     * official week doesn't (never mix the two in one week), a negative week, or a league-wide total further
     * than {@link #MAX_OFFICIAL_DRIFT} from the feed's (a snapshot keyed to the wrong leg).
     */
    static Map<Integer, BigDecimal> plausibleOfficial(Map<Integer, BigDecimal> officialWeek,
                                                      Map<Integer, BigDecimal> feedByRoster) {
        if (officialWeek == null) return null;
        BigDecimal officialTotal = BigDecimal.ZERO;
        BigDecimal feedTotal = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> r : feedByRoster.entrySet()) {
            BigDecimal o = officialWeek.get(r.getKey());
            if (o == null || o.signum() < 0) return null;
            officialTotal = officialTotal.add(o);
            feedTotal = feedTotal.add(r.getValue());
        }
        if (feedTotal.signum() <= 0) return null;
        BigDecimal drift = officialTotal.subtract(feedTotal).abs().divide(feedTotal, 4, RoundingMode.HALF_UP);
        return drift.compareTo(MAX_OFFICIAL_DRIFT) > 0 ? null : officialWeek;
    }
}
