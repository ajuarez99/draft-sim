package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.SportSchedule;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.LeagueRepository.PlayoffFormat;
import com.ballknowers.draftsim.store.SportScheduleRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Games per NBA team per week, from the stored Sleeper schedule (specs/017-nba-schedule-grid,
 * contracts/api.md C1).
 *
 * <p><b>The league row is the URL's.</b> This reads {@code leagues.bySleeperId(urlId)} and never
 * {@link LeagueSeasonResolver}: the resolver walks back to the newest season with scored weeks, which
 * for NBA 2026 is 2025, and would answer about the wrong season for the whole pre-scoring window
 * (review F1). The response's {@code season} is that row's season.
 *
 * <p>Counting goes through {@link SportSchedule#counts} only (FR-004). The server encodes no ranking:
 * teams come back ordered by code and the client sorts.
 */
@Service
public class ScheduleGridService {

    static final String FOOTBALL_REASON =
            "The schedule grid is for basketball leagues: an NFL team plays once a week.";

    private final LeagueRepository leagues;
    private final SportScheduleRepository schedules;

    public ScheduleGridService(LeagueRepository leagues, SportScheduleRepository schedules) {
        this.leagues = leagues;
        this.schedules = schedules;
    }

    public record Week(int week, LocalDate firstDate, LocalDate lastDate) {}

    public record Team(String team, int[] games, int seasonTotal) {}

    /** {@code reason} is non-null iff {@code endWeek} is null (research R5). */
    public record Playoff(Integer startWeek, Integer endWeek, String reason) {}

    /** {@code exhibition}: games with a non-franchise side, see {@link #EXHIBITION_SHARE}. */
    public record Excluded(int postponed, int canceled, int exhibition) {}

    /**
     * A side with fewer counted games than this share of the median team's is an exhibition team,
     * and its games aren't counted. Hand-set, not fitted: a franchise plays ~80, the All-Star teams
     * 1-2, so anything between works. Measured 2026-10-07: Sleeper's 2024 schedule keeps the 2025
     * All-Star final (CHK vs SHQ) as {@code complete}, so the status rule alone let it through.
     * 2025's (STP/STR) happened to be {@code canceled}. Relative to the median rather than a list
     * of 30 codes, so a renamed franchise can't silently vanish from the grid.
     */
    static final double EXHIBITION_SHARE = 0.25;

    public record Result(String sport, int season, boolean available, String reason, OffsetDateTime fetchedAt,
                         Integer currentWeek, Integer lastLeagueWeek, boolean seasonOver, List<Week> weeks,
                         Playoff playoff, List<Team> teams, Excluded excluded) {}

    public Optional<Result> forLeague(String sleeperLeagueId) {
        Optional<LeagueRow> found = leagues.bySleeperId(sleeperLeagueId);
        if (found.isEmpty()) return Optional.empty();
        LeagueRow league = found.get();
        OptionalInt leg = leagues.currentLeg(league.id());
        Optional<PlayoffFormat> format = leagues.playoffFormat(league.id());
        boolean basketball = league.sport() == Sport.NBA;
        List<SportSchedule.Game> games = basketball
                ? schedules.forSeason(league.sport().code(), league.season()) : List.of();
        Optional<OffsetDateTime> fetchedAt = basketball && !games.isEmpty()
                ? schedules.fetchedAt(league.sport().code(), league.season()) : Optional.empty();
        return Optional.of(compute(league, leg, format, games, fetchedAt));
    }

    /** The pure core: everything it needs is an argument. */
    static Result compute(LeagueRow league, OptionalInt leg, Optional<PlayoffFormat> format,
                          List<SportSchedule.Game> games, Optional<OffsetDateTime> fetchedAt) {
        Integer currentWeek = leg.isPresent() ? leg.getAsInt() : null;
        Playoff playoff = playoff(format);
        Integer lastLeagueWeek = lastLeagueWeek(format, games);
        // Over only when Sleeper marks it complete, or the playoff end is KNOWN and passed. The
        // start-1 / last-stored-week fallbacks bound the default columns, never "over" (review R2).
        OptionalInt knownEnd = format.map(PlayoffFormat::lastPlayoffWeek).orElse(OptionalInt.empty());
        boolean seasonOver = league.complete()
                || (currentWeek != null && knownEnd.isPresent() && currentWeek > knownEnd.getAsInt());
        String sport = league.sport().code();

        if (league.sport() != Sport.NBA) {
            return unavailable(league, FOOTBALL_REASON, currentWeek, lastLeagueWeek, seasonOver, playoff);
        }
        if (games.isEmpty()) {
            // A complete season is never refreshed again, so it must not promise a refresh (review R1).
            String reason = league.complete()
                    ? "The " + league.season() + " NBA schedule wasn't saved for this season: "
                            + "it finished before the app started storing schedules."
                    : "The " + league.season() + " NBA schedule hasn't been loaded yet. "
                            + "It loads with the league's next refresh.";
            return unavailable(league, reason, currentWeek, lastLeagueWeek, seasonOver, playoff);
        }

        TreeSet<Integer> weekNumbers = new TreeSet<>();
        for (SportSchedule.Game g : games) weekNumbers.add(g.week());
        List<Integer> order = new ArrayList<>(weekNumbers);
        Map<Integer, Integer> indexOf = new TreeMap<>();
        for (int i = 0; i < order.size(); i++) indexOf.put(order.get(i), i);

        LocalDate[] first = new LocalDate[order.size()];
        LocalDate[] last = new LocalDate[order.size()];
        Map<String, int[]> byTeam = new TreeMap<>();   // sorted by code: no server ranking
        Map<String, Integer> seasonGames = new TreeMap<>();
        for (SportSchedule.Game g : games) {
            if (!SportSchedule.counts(g.status())) continue;
            for (String t : new String[]{g.home(), g.away()}) {
                if (t != null) seasonGames.merge(t, 1, Integer::sum);
            }
        }
        double floor = EXHIBITION_SHARE * median(seasonGames.values());
        int postponed = 0;
        int canceled = 0;
        int exhibition = 0;
        for (SportSchedule.Game g : games) {
            if (!SportSchedule.counts(g.status())) {
                if ("postponed".equals(g.status())) postponed++; else canceled++;
                continue;
            }
            if (isExhibitionSide(g.home(), seasonGames, floor) || isExhibitionSide(g.away(), seasonGames, floor)) {
                exhibition++;
                continue;
            }
            int w = indexOf.get(g.week());
            if (g.date() != null) {
                if (first[w] == null || g.date().isBefore(first[w])) first[w] = g.date();
                if (last[w] == null || g.date().isAfter(last[w])) last[w] = g.date();
            }
            for (String t : new String[]{g.home(), g.away()}) {
                if (t == null) continue;
                byTeam.computeIfAbsent(t, k -> new int[order.size()])[w]++;
            }
        }
        List<Week> weeks = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) weeks.add(new Week(order.get(i), first[i], last[i]));
        List<Team> teams = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byTeam.entrySet()) {
            teams.add(new Team(e.getKey(), e.getValue(), java.util.Arrays.stream(e.getValue()).sum()));
        }
        return new Result(sport, league.season(), true, null, fetchedAt.orElse(null), currentWeek,
                lastLeagueWeek, seasonOver, weeks, playoff, teams, new Excluded(postponed, canceled, exhibition));
    }

    private static boolean isExhibitionSide(String team, Map<String, Integer> seasonGames, double floor) {
        return team != null && seasonGames.getOrDefault(team, 0) < floor;
    }

    private static double median(java.util.Collection<Integer> counts) {
        if (counts.isEmpty()) return 0;
        int[] sorted = counts.stream().mapToInt(Integer::intValue).sorted().toArray();
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }

    private static Result unavailable(LeagueRow league, String reason, Integer currentWeek,
                                      Integer lastLeagueWeek, boolean seasonOver, Playoff playoff) {
        return new Result(league.sport().code(), league.season(), false, reason, null, currentWeek,
                lastLeagueWeek, seasonOver, List.of(), playoff, List.of(), new Excluded(0, 0, 0));
    }

    /** data-model "League span": playoff end, else the week before playoffs, else the last stored week. */
    private static Integer lastLeagueWeek(Optional<PlayoffFormat> format, List<SportSchedule.Game> games) {
        if (format.isPresent()) {
            OptionalInt end = format.get().lastPlayoffWeek();
            if (end.isPresent()) return end.getAsInt();
            if (format.get().playoffWeekStart() >= 2) return format.get().playoffWeekStart() - 1;
        }
        OptionalInt max = games.stream().mapToInt(SportSchedule.Game::week).max();
        return max.isPresent() ? max.getAsInt() : null;
    }

    private static Playoff playoff(Optional<PlayoffFormat> format) {
        if (format.isEmpty()) return new Playoff(null, null, refusalSentence("NO_START"));
        PlayoffFormat f = format.get();
        Integer start = f.playoffWeekStart() >= 2 ? f.playoffWeekStart() : null;
        OptionalInt end = f.lastPlayoffWeek();
        if (end.isPresent()) return new Playoff(start, end.getAsInt(), null);
        return new Playoff(start, null, refusalSentence(f.playoffWindowRefusal().orElse("NO_START")));
    }

    /**
     * {@code store/} returns a code and the sentence lives here, where
     * {@code NoIngestHintsInMessagesTest} scans it (review N3).
     */
    static String refusalSentence(String code) {
        return switch (code) {
            case "NO_START" -> "This league has no playoff start week in its settings.";
            case "TOO_FEW_TEAMS" -> "This league's settings don't have enough playoff teams to make a bracket.";
            case "ROUND_TYPE_UNKNOWN" -> "This league's settings don't say how long each playoff round is.";
            case "ROUND_TYPE_UNSUPPORTED" ->
                    "This league's playoff rounds aren't one week each, and the grid only works out "
                            + "one-week rounds so far.";
            default -> throw new IllegalArgumentException("unknown playoff window refusal code: " + code);
        };
    }
}
