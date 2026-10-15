package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerProjectionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Does scoring a stored football stat line ourselves reproduce Sleeper's own {@code pts_ppr}?
 * (specs/036-projection-foundation US3, contracts/parity.md as amended by S6.) Read-only. The answer
 * decides whether {@code draftsim.projections.nfl-exact-scoring} may flip; this class never flips it.
 *
 * <p><b>The tolerance is fixed before any result is looked at</b> (S6): Sleeper rounds each stat in the
 * line to two decimals, so a row can legitimately differ from its own points total by up to half a
 * hundredth per scored stat times that stat's weight. Per row it is
 * {@code sum(|weight_k| * 0.005) over the scored keys the line carries, + 0.005} (the last term is the
 * rounding of the total itself). A flat 0.01 would fail on rounding alone (measured on NFL 2026 week 6:
 * 241 of 402 rows differ by more than 0.01).
 */
@Service
public class ProjectionParityService {

    /** Half a unit in the second decimal: the most one rounded stat or total can be off. */
    static final double HALF_CENT = 0.005;
    static final int MAX_ROWS = 50;

    /**
     * Sleeper-style default full-PPR scoring, used ONLY when no stored NFL league has {@code rec = 1.0}.
     * UNVERIFIED: these are the offensive defaults as recalled from Sleeper's league-creation screen, not
     * read from a document or an API response, so a result scored with them says so in {@code scoringSource}.
     */
    static final Map<String, Double> ASSUMED_DEFAULT_PPR = Map.ofEntries(
            Map.entry("pass_yd", 0.04), Map.entry("pass_td", 4.0), Map.entry("pass_int", -1.0),
            Map.entry("pass_2pt", 2.0), Map.entry("rush_yd", 0.1), Map.entry("rush_td", 6.0),
            Map.entry("rush_2pt", 2.0), Map.entry("rec", 1.0), Map.entry("rec_yd", 0.1),
            Map.entry("rec_td", 6.0), Map.entry("rec_2pt", 2.0), Map.entry("fum_lost", -1.0));

    /** @param diff scored minus Sleeper's pts_ppr */
    public record Mismatch(String sleeperPlayerId, double ptsPpr, double scored, double diff, double tolerance,
                           Map<String, Object> stats) {}

    /**
     * @param withinTolerance rows whose |diff| is inside their own rounding tolerance
     * @param beyondTolerance rows outside it; {@code firstBeyondTolerance} lists the largest {@value #MAX_ROWS}
     * @param maxAbsDiff      the largest |diff| over every row with a line
     */
    public record Result(String sport, int season, int week, String scoringSource, int rowsWithStats,
                         int rowsWithoutStats, int withinTolerance, int beyondTolerance, double maxAbsDiff,
                         List<Mismatch> firstBeyondTolerance) {}

    private final PlayerProjectionRepository projections;
    private final LeagueRepository leagues;
    private final GameScoringService gameScoring;

    public ProjectionParityService(PlayerProjectionRepository projections, LeagueRepository leagues,
                                   GameScoringService gameScoring) {
        this.projections = projections;
        this.leagues = leagues;
        this.gameScoring = gameScoring;
    }

    /** The per-row rounding tolerance: sum of |weight| * 0.005 over the scored keys the line carries, + 0.005. */
    public static double tolerance(Map<String, ? extends Number> scoring, Map<String, ?> stats) {
        double t = HALF_CENT;
        for (Map.Entry<String, ? extends Number> e : scoring.entrySet()) {
            if (e.getValue() != null && stats.get(e.getKey()) instanceof Number) {
                t += Math.abs(e.getValue().doubleValue()) * HALF_CENT;
            }
        }
        return t;
    }

    /**
     * @param sleeperLeagueId optional: score under that stored league instead of the automatic pick
     * @throws IllegalArgumentException for a non-football sport or an unknown league
     */
    public Result check(String sport, int season, int week, String sleeperLeagueId) {
        if (Sport.fromCode(sport) != Sport.NFL) {
            throw new IllegalArgumentException("projection parity is a football check; sport=" + sport);
        }
        Source source = pickSource(sleeperLeagueId);
        List<Mismatch> beyond = new ArrayList<>();
        int with = 0;
        int without = 0;
        int within = 0;
        int beyondN = 0;
        double maxAbs = 0;
        for (PlayerProjectionRepository.WeekLine l : projections.linesInWindow(sport, season, week, week)) {
            if (l.stats() == null || l.ptsPpr() == null) {
                without++;
                continue;
            }
            with++;
            double scored = gameScoring.score(source.scoring(), l.stats());
            double diff = round2(scored - l.ptsPpr());
            double tol = tolerance(source.scoring(), l.stats());
            maxAbs = Math.max(maxAbs, Math.abs(diff));
            if (Math.abs(diff) <= tol) {
                within++;
            } else {
                beyondN++;
                beyond.add(new Mismatch(l.sleeperPlayerId(), l.ptsPpr(), scored, diff, round4(tol), l.stats()));
            }
        }
        beyond.sort(Comparator.comparingDouble((Mismatch m) -> Math.abs(m.diff())).reversed());
        List<Mismatch> first = beyond.size() > MAX_ROWS ? List.copyOf(beyond.subList(0, MAX_ROWS)) : List.copyOf(beyond);
        return new Result(sport, season, week, source.label(), with, without, within, beyondN, maxAbs, first);
    }

    private record Source(String label, Map<String, Double> scoring) {}

    private Source pickSource(String sleeperLeagueId) {
        if (sleeperLeagueId != null) {
            LeagueRepository.LeagueRow l = leagues.bySleeperId(sleeperLeagueId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown league " + sleeperLeagueId));
            return new Source(label(l, "requested league"), leagues.scoringOf(l.id()));
        }
        // Lowest id wins, so the pick is stable and re-runnable.
        Optional<LeagueRepository.LeagueRow> found = leagues.all().stream()
                .filter(l -> l.sport() == Sport.NFL)
                .sorted(Comparator.comparingLong(LeagueRepository.LeagueRow::id))
                .filter(l -> Double.valueOf(1.0).equals(leagues.scoringOf(l.id()).get("rec")))
                .findFirst();
        if (found.isPresent()) {
            return new Source(label(found.get(), "first stored NFL league with rec=1.0"),
                    leagues.scoringOf(found.get().id()));
        }
        return new Source("assumed Sleeper default PPR (UNVERIFIED constants, no stored rec=1.0 league)",
                new LinkedHashMap<>(ASSUMED_DEFAULT_PPR));
    }

    private static String label(LeagueRepository.LeagueRow l, String why) {
        return "league " + l.sleeperId() + " (\"" + l.name() + "\" " + l.season() + ", " + why + ")";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
