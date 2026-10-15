package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.config.ProjectionAccuracyProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.ProjectionSeason;
import com.ballknowers.draftsim.engine.SeasonStateService;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerSeasonProjectionRepository;
import com.ballknowers.draftsim.store.ProjectionSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * Captures each game's pre-game projection and leaves it frozen (specs/036-projection-foundation
 * US1, plan "US1: capture" as amended by B2).
 *
 * <p>Why this exists: Sleeper serves past weeks' projections, but every past row measured carries a
 * {@code last_modified} after its own game (research R1), so none of it can be proven pre-game.
 * The only honest accuracy number grades lines this app fetched itself before the game.
 *
 * <p>"Before the game" has no start time to lean on (the feed has a date, not a time), so a row is
 * written only if BOTH hold (research R3): the schedule fetched in the same run says the game is
 * {@code pre_game}, and {@code now} is before a hand-set per-sport cutoff on the game's date in
 * America/New_York. Either assumption failing loses a sample; it never records a post-tip line as
 * pre-game. The cutoffs live in config/weights.yml and are labelled arbitrary.
 *
 * <p>Rows that fail the guard are dropped HERE, before the SQL: the table's {@code check
 * (fetched_at < cutoff_at)} runs before {@code on conflict}, so one late row in a batch would abort
 * the whole batch (amendment B2). The table's trigger is the second wall behind this one.
 *
 * <p>{@code now} is a parameter so tests drive the cutoff without a clock mock.
 */
@Service
public class ProjectionSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionSnapshotService.class);

    static final ZoneId EASTERN = ZoneId.of("America/New_York");
    static final String PRE_GAME = "pre_game";

    /**
     * @param season            null when no current-season league needs projections (nothing was fetched)
     * @param rowsFrozenSkipped rows the table refused to change because their cutoff had passed
     * @param rowsNotPreGame    rows that failed the double guard and were never sent to the table
     */
    public record Result(String sport, Integer season, List<Integer> weeks, int rowsWritten,
                         int rowsFrozenSkipped, int rowsNotPreGame) {}

    private record Game(String status, LocalDate date, Integer week) {}

    private final SleeperPlayerStatsClient statsClient;
    private final SleeperProjectionClient projectionClient;
    private final PlayerSeasonProjectionRepository seasonProjections;
    private final ProjectionSnapshotRepository snapshots;
    private final LeagueRepository leagues;
    private final SeasonStateService seasonState;
    private final ProjectionAccuracyProperties props;

    public ProjectionSnapshotService(SleeperPlayerStatsClient statsClient, SleeperProjectionClient projectionClient,
                                     PlayerSeasonProjectionRepository seasonProjections,
                                     ProjectionSnapshotRepository snapshots, LeagueRepository leagues,
                                     SeasonStateService seasonState, ProjectionAccuracyProperties props) {
        this.statsClient = statsClient;
        this.projectionClient = projectionClient;
        this.seasonProjections = seasonProjections;
        this.snapshots = snapshots;
        this.leagues = leagues;
        this.seasonState = seasonState;
        this.props = props;
    }

    public Result capture(Sport sport, Instant now) {
        String code = sport.code();
        OptionalInt current = ProjectionSeason.current(leagues, seasonState, sport);
        if (current.isEmpty()) return new Result(code, null, List.of(), 0, 0, 0);
        int season = current.getAsInt();

        Map<String, Game> schedule = scheduleById(statsClient.schedule(code, season));
        LocalDate today = now.atZone(EASTERN).toLocalDate();
        LocalDate last = today.plusDays(props.projectionSnapshot().lookaheadDays());
        TreeSet<Integer> weeks = new TreeSet<>();
        for (Game g : schedule.values()) {
            if (PRE_GAME.equals(g.status()) && g.week() != null && g.date() != null
                    && !g.date().isBefore(today) && !g.date().isAfter(last)) {
                weeks.add(g.week());
            }
        }
        if (weeks.isEmpty()) return new Result(code, season, List.of(), 0, 0, 0);

        // NBA only (research R6): the season-average line the rankings show, copied beside each game line.
        Map<String, Map<String, Double>> seasonAvg = sport == Sport.NBA
                ? seasonProjections.statsByPlayer(code, season) : Map.of();
        String requiredKey = sport == Sport.NBA ? "pts" : "pts_ppr";

        List<ProjectionSnapshotRepository.Row> toWrite = new ArrayList<>();
        int notPreGame = 0;
        for (int week : weeks) {
            List<Map<String, Object>> raw = projectionClient.week(code, season, week);
            if (raw == null) continue;
            for (Map<String, Object> r : raw) {
                Object playerId = r.get("player_id");
                Object gameId = r.get("game_id");
                if (playerId == null || gameId == null || !(r.get("stats") instanceof Map<?, ?> stats)
                        || !(stats.get(requiredKey) instanceof Number)) {
                    continue; // an ADP stub, not a projection (the same filter the existing ingests use)
                }
                LocalDate date = parseDate(r.get("date"));
                Game game = schedule.get(String.valueOf(gameId));
                Instant cutoff = date == null ? null : cutoffAt(sport, date);
                boolean preGame = date != null && game != null && PRE_GAME.equals(game.status()) && now.isBefore(cutoff);
                if (!preGame) {
                    notPreGame++;
                    continue;
                }
                Map<String, Object> avg = null;
                Map<String, Double> a = seasonAvg.get(String.valueOf(playerId));
                if (a != null) avg = new LinkedHashMap<>(a);
                Integer rowWeek = r.get("week") instanceof Number n ? n.intValue() : null;
                toWrite.add(new ProjectionSnapshotRepository.Row(code, season, rowWeek != null ? rowWeek : week,
                        String.valueOf(playerId), String.valueOf(gameId), date,
                        asString(r.get("opponent")), asString(r.get("team")), stripped(stats), avg,
                        asString(r.get("company")),
                        r.get("last_modified") instanceof Number m ? Instant.ofEpochMilli(m.longValue()) : null,
                        now, cutoff));
            }
        }

        ProjectionSnapshotRepository.UpsertResult res = snapshots.upsertPreGame(toWrite);
        log.info("projection snapshot: {} {} weeks={} written={} frozenSkipped={} notPreGame={}",
                code, season, weeks, res.written(), res.frozenSkipped(), notPreGame);
        return new Result(code, season, List.copyOf(weeks), res.written(), res.frozenSkipped(), notPreGame);
    }

    /** The game date at the sport's hand-set Eastern cutoff time, as an instant (DST-safe). */
    Instant cutoffAt(Sport sport, LocalDate gameDate) {
        return LocalDateTime.of(gameDate, props.cutoffEt(sport.code())).atZone(EASTERN).toInstant();
    }

    /** Projection-only noise out: the average-draft-position keys are not part of a stat line. */
    static Map<String, Object> stripped(Map<?, ?> stats) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : stats.entrySet()) {
            String k = String.valueOf(e.getKey());
            if (k.startsWith("adp_") || k.startsWith("pos_adp_")) continue;
            out.put(k, e.getValue());
        }
        return out;
    }

    /** Only game_id, status, date and week are read, so the NFL string vs NBA object home/away shape is moot. */
    private static Map<String, Game> scheduleById(List<Map<String, Object>> raw) {
        Map<String, Game> out = new LinkedHashMap<>();
        for (Map<String, Object> g : raw) {
            Object id = g.get("game_id");
            if (id == null) continue;
            out.put(String.valueOf(id), new Game(asString(g.get("status")), parseDate(g.get("date")),
                    g.get("week") instanceof Number n ? n.intValue() : null));
        }
        return out;
    }

    private static LocalDate parseDate(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        if (s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
