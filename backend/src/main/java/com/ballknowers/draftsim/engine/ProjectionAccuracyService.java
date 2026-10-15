package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ProjectionAccuracyProperties;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ProjectionSnapshotRepository;
import com.ballknowers.draftsim.store.ProjectionSnapshotRepository.Sample;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * How far off the frozen pre-game projections were, under the asking league's own scoring
 * (specs/036-projection-foundation US2, plan "US2: accuracy" as amended).
 *
 * <p>Each snapshot is paired with the same player's realized game by {@code game_id} and classified
 * by {@code sport_schedule.status} for that game (amendments S4/S5):
 * <ul>
 *   <li>{@code complete} and a {@code player_game} row: <b>scored</b>;</li>
 *   <li>{@code complete} and no row: <b>did not play</b> (counted, never graded);</li>
 *   <li>{@code postponed} / {@code canceled}: <b>voided</b> (a rescheduled game gets a new id and its own
 *       snapshot, so the old id never reappears);</li>
 *   <li>anything else, or no schedule row: <b>pending</b>.</li>
 * </ul>
 *
 * <p><b>Both sides of every sample are scored with the same restricted scoring map</b> (amendment B3):
 * the league's map minus every category no snapshot line of that sport-season carries, through
 * {@link ProjectedCategories}. A realized line holds bonus categories a projection cannot, and scoring
 * only the realized side with them would build "projections run low" into the lean. The excluded
 * categories ride in the payload.
 *
 * <p>Lean is mean(projected - actual); positive means projections ran high. Bands are judged on the
 * PROJECTED value, never the actual (banding on the actual builds regression to the mean into the figure).
 *
 * <p>The summary ({@link #summaryOf}) is derived from the full result and never computed separately, so
 * the one-line figure and the panel cannot disagree (memory: count and label, one source).
 */
@Service
public class ProjectionAccuracyService {

    public static final String UNIT_GAME = "GAME";
    public static final String UNIT_PLAYER_WEEK = "PLAYER_WEEK";

    public static final String STATUS_MEASURED = "MEASURED";
    public static final String STATUS_TOO_EARLY = "TOO_EARLY";
    public static final String STATUS_NO_SNAPSHOTS = "NO_SNAPSHOTS";

    public static final String SERIES_GAME = "GAME";
    public static final String SERIES_SEASON_AVERAGE = "SEASON_AVERAGE";

    static final String CAVEAT_MORNING_OF =
            "Morning-of projection: late scratches and inactives are not reflected.";
    static final String CAVEAT_NBA_ONE_GAME =
            "Sleeper credits one game per starter per week; this grades individual games.";
    static final String CAVEAT_NO_SCORING =
            "This league's scoring settings aren't stored, so nothing could be graded.";

    /**
     * @param withoutSeasonAverage NBA: snapshots left out of both series because they carry no season-average
     *                             line (the two series compare one population); 0 for football
     * @param voided               snapshots whose game was postponed or canceled, excluded from everything else
     * @param excludedCategories   scored categories no projection line carries, graded on neither side
     * @param source               the projection source ("rotowire"), null when none is named
     */
    public record ProjectionAccuracy(String sport, int season, String unit, String status, int minSamples,
                                     int minCell, List<Series> series, LocalDate snapshotsFrom,
                                     LocalDate scoredThrough, int withoutSeasonAverage, int voided,
                                     List<String> excludedCategories, List<String> caveats, String source) {}

    /** @param kind {@link #SERIES_GAME} or {@link #SERIES_SEASON_AVERAGE} */
    public record Series(String kind, String label, Cell overall, List<Cell> byPosition, List<Cell> byBand,
                         int didNotPlay, int pending) {}

    /**
     * @param mae  mean absolute miss, league points; null below {@code minCell} samples or when not MEASURED
     * @param lean mean(projected - actual); positive means projections ran high; same nullability as mae
     */
    public record Cell(String key, int n, Double mae, Double lean) {}

    /**
     * The one-line view of {@link ProjectionAccuracy}: the NBA SEASON_AVERAGE series (what the rankings show)
     * or football's GAME series, overall cell only.
     *
     * @param kind  which series this is
     * @param label that series' human label, so the line can name what it measured
     */
    public record AccuracySummary(String status, int n, int minSamples, Double mae, Double lean, String unit,
                                  String kind, String label) {}

    private final ProjectionSnapshotRepository snapshots;
    private final LeagueRepository leagues;
    private final GameScoringService gameScoring;
    private final ProjectionAccuracyProperties props;

    public ProjectionAccuracyService(ProjectionSnapshotRepository snapshots, LeagueRepository leagues,
                                     GameScoringService gameScoring, ProjectionAccuracyProperties props) {
        this.snapshots = snapshots;
        this.leagues = leagues;
        this.gameScoring = gameScoring;
        this.props = props;
    }

    /** A result and the inputs' fingerprint it was computed from. */
    private record Cached(String stamp, ProjectionAccuracy result) {}

    /**
     * Computed on read, but cached by what it read (plan N3): the full read measured ~1.4 s at a season's
     * 29k samples against a ~30 ms fingerprint, and the summary is computed on every /analysis and League
     * home read. The fingerprint covers the snapshots, the realized games, the schedule and this league's
     * scoring map; a changed input is a different key, so a cached answer can't outlive its data.
     */
    private final Map<Long, Cached> cache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Cached> eldest) {
                    return size() > 64;
                }
            });

    public ProjectionAccuracy forLeague(LeagueRepository.LeagueRow league) {
        String sport = league.sport().code();
        Map<String, Double> scoring = leagues.scoringOf(league.id());
        String stamp = snapshots.stamp(sport, league.season()) + "#" + scoring.hashCode();
        Cached hit = cache.get(league.id());
        if (hit != null && hit.stamp().equals(stamp)) return hit.result();
        ProjectionAccuracy fresh = compute(sport, league.season(), scoring, snapshots.forAccuracy(sport, league.season()));
        cache.put(league.id(), new Cached(stamp, fresh));
        return fresh;
    }

    /** Derived from {@link #forLeague}; never a second computation. */
    public AccuracySummary summary(LeagueRepository.LeagueRow league) {
        return summaryOf(forLeague(league));
    }

    // ---- pure core (package-private for tests) ----

    private enum Kind { SCORED, DID_NOT_PLAY, PENDING, VOIDED }

    static Kind classify(Sample s) {
        String status = s.scheduleStatus();
        if ("postponed".equals(status) || "canceled".equals(status)) return Kind.VOIDED;
        if ("complete".equals(status)) return s.actual() != null ? Kind.SCORED : Kind.DID_NOT_PLAY;
        return Kind.PENDING;
    }

    ProjectionAccuracy compute(String sport, int season, Map<String, Double> scoring, List<Sample> samples) {
        boolean nba = Sport.fromCode(sport) == Sport.NBA;
        String unit = nba ? UNIT_GAME : UNIT_PLAYER_WEEK;
        int minSamples = props.minSamples(sport);
        int minCell = props.projectionAccuracy().minCell();

        if (samples.isEmpty()) {
            return new ProjectionAccuracy(sport, season, unit, STATUS_NO_SNAPSHOTS, minSamples, minCell, List.of(),
                    null, null, 0, 0, List.of(), List.of(), null);
        }
        if (scoring == null || scoring.isEmpty()) {
            return new ProjectionAccuracy(sport, season, unit, STATUS_NO_SNAPSHOTS, minSamples, minCell, List.of(),
                    null, null, 0, 0, List.of(), List.of(CAVEAT_NO_SCORING), null);
        }

        // B3: one restricted map for both sides of every sample, from every snapshot line of the sport-season.
        Set<String> projectedKeys = new HashSet<>();
        for (Sample s : samples) projectedKeys.addAll(s.stats().keySet());
        Map<String, Double> graded = ProjectedCategories.restrict(scoring, projectedKeys);
        List<String> excluded = ProjectedCategories.unprojected(scoring, projectedKeys);

        int voided = 0;
        int withoutAvg = 0;
        LocalDate from = null;
        LocalDate through = null;
        String source = null;
        List<Sample> live = new ArrayList<>();
        for (Sample s : samples) {
            if (s.gameDate() != null && (from == null || s.gameDate().isBefore(from))) from = s.gameDate();
            if (source == null) source = s.source();
            if (classify(s) == Kind.VOIDED) {
                voided++;
                continue;
            }
            if (nba && s.seasonAvgStats() == null) {
                withoutAvg++;
                continue;
            }
            live.add(s);
        }

        List<Series> series = new ArrayList<>();
        series.add(series(sport, SERIES_GAME, nba ? "Game projection" : "Weekly projection", live, graded,
                Sample::stats, minCell));
        if (nba) {
            series.add(series(sport, SERIES_SEASON_AVERAGE, "Season average (what the rankings use)", live, graded,
                    Sample::seasonAvgStats, minCell));
        }
        for (Sample s : live) {
            if (classify(s) == Kind.SCORED && s.gameDate() != null
                    && (through == null || s.gameDate().isAfter(through))) {
                through = s.gameDate();
            }
        }

        // The status is judged on the summary series (S1), then every figure in the payload follows it.
        String summaryKind = nba ? SERIES_SEASON_AVERAGE : SERIES_GAME;
        int judgedN = series.stream().filter(x -> x.kind().equals(summaryKind)).findFirst()
                .map(x -> x.overall().n()).orElse(0);
        String status = judgedN < minSamples ? STATUS_TOO_EARLY : STATUS_MEASURED;
        if (STATUS_TOO_EARLY.equals(status)) series = series.stream().map(ProjectionAccuracyService::withoutFigures).toList();

        List<String> caveats = new ArrayList<>();
        caveats.add(CAVEAT_MORNING_OF);
        if (nba) caveats.add(CAVEAT_NBA_ONE_GAME);
        if (!excluded.isEmpty()) {
            caveats.add("Not graded (no projection carries them): " + String.join(", ", excluded) + ".");
        }
        return new ProjectionAccuracy(sport, season, unit, status, minSamples, minCell, List.copyOf(series), from,
                through, withoutAvg, voided, excluded, List.copyOf(caveats), source);
    }

    static AccuracySummary summaryOf(ProjectionAccuracy a) {
        String kind = Sport.fromCode(a.sport()) == Sport.NBA ? SERIES_SEASON_AVERAGE : SERIES_GAME;
        Series s = a.series().stream().filter(x -> x.kind().equals(kind)).findFirst().orElse(null);
        if (s == null) {
            return new AccuracySummary(a.status(), 0, a.minSamples(), null, null, a.unit(), kind, null);
        }
        return new AccuracySummary(a.status(), s.overall().n(), a.minSamples(), s.overall().mae(),
                s.overall().lean(), a.unit(), s.kind(), s.label());
    }

    // ---- one series ----

    private static final class Acc {
        int n;
        double abs;
        double signed;

        void add(double err) {
            n++;
            abs += Math.abs(err);
            signed += err;
        }

        Cell cell(String key, int minCell) {
            if (n < minCell || n == 0) return new Cell(key, n, null, null);
            return new Cell(key, n, round2(abs / n), round2(signed / n));
        }
    }

    private Series series(String sport, String kind, String label, List<Sample> population,
                          Map<String, Double> graded, Function<Sample, Map<String, Object>> projectedLine,
                          int minCell) {
        List<Integer> edges = props.bands(sport);
        Acc overall = new Acc();
        Map<String, Acc> byPos = new LinkedHashMap<>();
        Acc[] byBand = new Acc[edges.size() + 1];
        for (int i = 0; i < byBand.length; i++) byBand[i] = new Acc();
        int dnp = 0;
        int pending = 0;

        for (Sample s : population) {
            switch (classify(s)) {
                case DID_NOT_PLAY -> dnp++;
                case PENDING -> pending++;
                case VOIDED -> { /* filtered before the series is built */ }
                case SCORED -> {
                    double proj = gameScoring.score(graded, projectedLine.apply(s));
                    double actual = gameScoring.score(graded, s.actual());
                    double err = proj - actual;
                    overall.add(err);
                    byPos.computeIfAbsent(s.position() == null ? "Other" : s.position(), k -> new Acc()).add(err);
                    int band = 0;
                    while (band < edges.size() && proj >= edges.get(band)) band++;  // judged on the PROJECTED value
                    byBand[band].add(err);
                }
            }
        }

        List<Cell> positionCells = new ArrayList<>();
        for (String key : positionOrder(sport, byPos.keySet())) positionCells.add(byPos.get(key).cell(key, minCell));
        List<Cell> bandCells = new ArrayList<>();
        for (int i = 0; i < byBand.length; i++) bandCells.add(byBand[i].cell(bandLabel(edges, i), minCell));
        return new Series(kind, label, overall.cell("ALL", minCell), positionCells, bandCells, dnp, pending);
    }

    private static Series withoutFigures(Series s) {
        return new Series(s.kind(), s.label(), blank(s.overall()), s.byPosition().stream().map(ProjectionAccuracyService::blank).toList(),
                s.byBand().stream().map(ProjectionAccuracyService::blank).toList(), s.didNotPlay(), s.pending());
    }

    private static Cell blank(Cell c) {
        return new Cell(c.key(), c.n(), null, null);
    }

    /** "0–20", "20–35", ..., "50+": edges are the lower bounds of the 2nd..last band. */
    static String bandLabel(List<Integer> edges, int i) {
        if (i == 0) return "0–" + edges.get(0);
        if (i == edges.size()) return edges.get(i - 1) + "+";
        return edges.get(i - 1) + "–" + edges.get(i);
    }

    private static List<String> positionOrder(String sport, Set<String> present) {
        List<String> out = new ArrayList<>();
        for (Position p : Position.forSport(Sport.fromCode(sport))) {
            if (present.contains(p.name())) out.add(p.name());
        }
        present.stream().filter(k -> !out.contains(k)).sorted().forEach(out::add);
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
