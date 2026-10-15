package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ProjectionAccuracyProperties;
import com.ballknowers.draftsim.engine.ProjectionAccuracyService.AccuracySummary;
import com.ballknowers.draftsim.engine.ProjectionAccuracyService.Cell;
import com.ballknowers.draftsim.engine.ProjectionAccuracyService.ProjectionAccuracy;
import com.ballknowers.draftsim.engine.ProjectionAccuracyService.Series;
import com.ballknowers.draftsim.store.ProjectionSnapshotRepository.Sample;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/036 T014: the accuracy maths over in-memory samples. The ordering tests (lean sign, the bonus
 * category) are the ones AGENTS.md bug class 1 asks for: a structural test cannot catch a sign inversion.
 */
class ProjectionAccuracyServiceTest {

    private static final Map<String, Double> NBA_SCORING = Map.of("pts", 1.0, "ast", 1.0);

    private static ProjectionAccuracyService service(int minSamples, int minCell) {
        var props = new ProjectionAccuracyProperties(
                new ProjectionAccuracyProperties.Accuracy(Map.of("nba", minSamples, "nfl", minSamples), minCell,
                        Map.of("nba", List.of(20, 35, 50), "nfl", List.of(5, 10, 17))),
                new ProjectionAccuracyProperties.Snapshot(Map.of("nba", "12:00", "nfl", "09:00"), 7));
        return new ProjectionAccuracyService(null, null, new GameScoringService(), props);
    }

    private static Map<String, Object> line(double pts, double ast) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pts", pts);
        m.put("ast", ast);
        return m;
    }

    private static Sample sample(String player, String position, Map<String, Object> proj, Map<String, Object> avg,
                                 Map<String, Object> actual, String status) {
        return new Sample(player, "g-" + player, LocalDate.of(2026, 10, 21), position, proj, avg, actual, status,
                "rotowire");
    }

    private static Sample scored(String player, double projPts, double actualPts) {
        return sample(player, "PG", line(projPts, 0), line(projPts, 0), line(actualPts, 0), "complete");
    }

    private static Series gameSeries(ProjectionAccuracy a) {
        return a.series().stream().filter(s -> s.kind().equals("GAME")).findFirst().orElseThrow();
    }

    private static Series avgSeries(ProjectionAccuracy a) {
        return a.series().stream().filter(s -> s.kind().equals("SEASON_AVERAGE")).findFirst().orElseThrow();
    }

    @Test
    void projectionsTwoPointsAboveActualGiveLeanPlusTwoAndMaeTwo() {
        var a = service(1, 1).compute("nba", 2026, NBA_SCORING,
                List.of(scored("a", 22, 20), scored("b", 32, 30), scored("c", 12, 10)));
        Cell o = gameSeries(a).overall();
        assertEquals(3, o.n());
        assertEquals(2.0, o.mae(), 1e-9);
        assertEquals(2.0, o.lean(), 1e-9, "positive lean means projections ran HIGH");
    }

    @Test
    void projectionsTwoPointsBelowActualGiveLeanMinusTwo() {
        var a = service(1, 1).compute("nba", 2026, NBA_SCORING,
                List.of(scored("a", 18, 20), scored("b", 28, 30), scored("c", 8, 10)));
        Cell o = gameSeries(a).overall();
        assertEquals(2.0, o.mae(), 1e-9);
        assertEquals(-2.0, o.lean(), 1e-9);
    }

    @Test
    void aBonusCategoryNoProjectionCarriesDoesNotBuildRunsLowIntoTheLean() {
        // The league scores a 40-point-game bonus (2.0). No projection line carries the key, but one realized
        // line holds it. The realized line is otherwise IDENTICAL to the projection, so the lean must be 0.
        // WITHOUT the restriction (scoring only the realized side with the full map) this sample would score
        // projected 25 against actual 27: lean -2.0, "projections run low", built in by the scoring map alone.
        Map<String, Double> scoring = Map.of("pts", 1.0, "ast", 1.0, "bonus_pt_40p", 2.0);
        Map<String, Object> actual = line(20, 5);
        actual.put("bonus_pt_40p", 1.0);
        var a = service(1, 1).compute("nba", 2026, scoring,
                List.of(sample("a", "PG", line(20, 5), line(20, 5), actual, "complete")));

        assertEquals(0.0, gameSeries(a).overall().lean(), 1e-9);
        assertEquals(0.0, avgSeries(a).overall().lean(), 1e-9);
        assertEquals(List.of("bonus_pt_40p"), a.excludedCategories());
        assertTrue(a.caveats().stream().anyMatch(c -> c.startsWith("Not graded") && c.contains("bonus_pt_40p")),
                "the excluded category is named in the caveats");
    }

    @Test
    void aPartiallyProjectedDefBucketFamilyIsNotGradedSoAShutoutDoesNotMoveTheLean() {
        // The projected DEF line carries one bucket (14_20 at 1.0); the realized line is a shutout
        // (pts_allow_0). Everything else (sack) is equal. Because the family is only partly projected it is
        // excluded whole, so the lean is 0. BEFORE the fix, 14_20 alone was graded: projected 3 + 1 = 4 vs
        // actual 3 + 0 = 3, lean +1.0 ("runs high") from the bucket choice alone, while the shutout's +10
        // was never graded.
        Map<String, Double> scoring = new LinkedHashMap<>();
        scoring.put("sack", 1.0);
        scoring.put("pts_allow_0", 10.0);
        scoring.put("pts_allow_14_20", 1.0);
        scoring.put("pts_allow_28_34", -1.0);
        Map<String, Object> proj = new LinkedHashMap<>();
        proj.put("sack", 3.0);
        proj.put("pts_allow_14_20", 1.0);
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("sack", 3.0);
        actual.put("pts_allow_0", 1.0);
        var a = service(1, 1).compute("nfl", 2026, scoring,
                List.of(new Sample("d", "g", LocalDate.of(2026, 10, 18), "DEF", proj, null, actual, "complete", "rotowire")));

        assertEquals(0.0, gameSeries(a).overall().lean(), 1e-9);
        assertEquals(List.of("pts_allow_0", "pts_allow_14_20", "pts_allow_28_34"), a.excludedCategories());
    }

    @Test
    void aBandIsJudgedOnTheProjectedValueNotTheActual() {
        var a = service(1, 1).compute("nba", 2026, NBA_SCORING, List.of(scored("a", 18, 40)));
        Map<String, Integer> byBand = new LinkedHashMap<>();
        for (Cell c : gameSeries(a).byBand()) byBand.put(c.key(), c.n());
        assertEquals(List.of("0–20", "20–35", "35–50", "50+"), List.copyOf(byBand.keySet()));
        assertEquals(1, byBand.get("0–20"), "projected 18 lands in 0-20 although the actual was 40");
        assertEquals(0, byBand.get("35–50"));
    }

    @Test
    void aBandEdgeBelongsToTheUpperBand() {
        var a = service(1, 1).compute("nba", 2026, NBA_SCORING, List.of(scored("a", 20, 20)));
        assertEquals(1, gameSeries(a).byBand().get(1).n());
        assertEquals("20–35", gameSeries(a).byBand().get(1).key());
    }

    @Test
    void aCellBelowMinCellKeepsItsCountAndShowsNoFigure() {
        var a = service(1, 2).compute("nba", 2026, NBA_SCORING,
                List.of(scored("a", 22, 20), scored("b", 22, 20), sample("c", "C", line(22, 0), line(22, 0), line(20, 0), "complete")));
        Cell pg = gameSeries(a).byPosition().stream().filter(c -> c.key().equals("PG")).findFirst().orElseThrow();
        Cell center = gameSeries(a).byPosition().stream().filter(c -> c.key().equals("C")).findFirst().orElseThrow();
        assertEquals(2, pg.n());
        assertEquals(2.0, pg.mae(), 1e-9);
        assertEquals(1, center.n());
        assertNull(center.mae());
        assertNull(center.lean());
    }

    @Test
    void positionsComeInTheSportsOrder() {
        var a = service(1, 1).compute("nba", 2026, NBA_SCORING, List.of(
                sample("a", "C", line(22, 0), line(22, 0), line(20, 0), "complete"),
                sample("b", "PG", line(22, 0), line(22, 0), line(20, 0), "complete")));
        assertEquals(List.of("PG", "C"), gameSeries(a).byPosition().stream().map(Cell::key).toList());
    }

    @Test
    void belowMinSamplesTheStatusIsTooEarlyAndNoFigureAppearsAnywhere() {
        var a = service(5, 1).compute("nba", 2026, NBA_SCORING, List.of(scored("a", 22, 20), scored("b", 22, 20)));
        assertEquals("TOO_EARLY", a.status());
        for (Series s : a.series()) {
            assertEquals(2, s.overall().n(), "counts survive");
            assertNull(s.overall().mae());
            assertNull(s.overall().lean());
            for (Cell c : s.byPosition()) { assertNull(c.mae()); assertNull(c.lean()); }
            for (Cell c : s.byBand()) { assertNull(c.mae()); assertNull(c.lean()); }
        }
        AccuracySummary sum = ProjectionAccuracyService.summaryOf(a);
        assertEquals("TOO_EARLY", sum.status());
        assertEquals(2, sum.n());
        assertEquals(5, sum.minSamples());
        assertNull(sum.mae());
        assertNull(sum.lean());
    }

    @Test
    void noSnapshotsIsItsOwnStatusWithNoSeries() {
        var a = service(1, 1).compute("nba", 2026, NBA_SCORING, List.of());
        assertEquals("NO_SNAPSHOTS", a.status());
        assertTrue(a.series().isEmpty());
        assertNull(a.snapshotsFrom());
        assertNull(a.scoredThrough());
    }

    @Test
    void theFourClassificationsFollowTheScheduleStatus() {
        Map<String, Object> p = line(20, 0);
        var a = service(1, 1).compute("nba", 2026, NBA_SCORING, List.of(
                sample("scored", "PG", p, p, line(18, 0), "complete"),
                sample("dnp", "PG", p, p, null, "complete"),
                sample("pre", "PG", p, p, null, "pre_game"),
                sample("live", "PG", p, p, line(10, 0), "in_game"),     // a partial box score is NOT scored (S4)
                sample("noRow", "PG", p, p, null, null),                // not in the schedule: pending
                sample("post", "PG", p, p, null, "postponed"),
                sample("canc", "PG", p, p, null, "canceled")));

        Series g = gameSeries(a);
        assertEquals(1, g.overall().n());
        assertEquals(1, g.didNotPlay());
        assertEquals(3, g.pending());
        assertEquals(2, a.voided());
    }

    @Test
    void nbaSeriesCompareOnePopulationAndTheSummaryIsTheSeasonAverageSeries() {
        Map<String, Object> proj = line(22, 0);
        Map<String, Object> avg = line(30, 0);   // the season-average line is a different, worse projection
        var a = service(1, 1).compute("nba", 2026, NBA_SCORING, List.of(
                sample("withAvg", "PG", proj, avg, line(20, 0), "complete"),
                sample("noAvg", "PG", proj, null, line(20, 0), "complete")));

        assertEquals(1, a.withoutSeasonAverage());
        assertEquals(1, gameSeries(a).overall().n(), "the player with no season-average line is in neither series");
        assertEquals(1, avgSeries(a).overall().n());
        assertEquals(2.0, gameSeries(a).overall().lean(), 1e-9);
        assertEquals(10.0, avgSeries(a).overall().lean(), 1e-9);

        AccuracySummary sum = ProjectionAccuracyService.summaryOf(a);
        assertEquals("SEASON_AVERAGE", sum.kind(), "the NBA summary is the series the rankings actually use");
        assertEquals(10.0, sum.lean(), 1e-9);
        assertEquals("GAME", sum.unit());
    }

    @Test
    void footballHasOneSeriesAndTheSummaryIsTheGameSeriesInPlayerWeeks() {
        Map<String, Object> proj = Map.of("pts_ppr", 15.0, "rec", 5.0);
        Map<String, Object> actual = Map.of("rec", 3.0);
        var a = service(1, 1).compute("nfl", 2026, Map.of("rec", 1.0), List.of(
                new Sample("p", "g", LocalDate.of(2026, 10, 18), "WR", proj, null, actual, "complete", "rotowire")));

        assertEquals(1, a.series().size());
        assertEquals("PLAYER_WEEK", a.unit());
        assertEquals(0, a.withoutSeasonAverage(), "football carries no season-average line, and that is not a gap");
        AccuracySummary sum = ProjectionAccuracyService.summaryOf(a);
        assertEquals("GAME", sum.kind());
        assertEquals(2.0, sum.lean(), 1e-9);
        assertEquals(2.0, sum.mae(), 1e-9);
    }

    @Test
    void theSummaryIsCopiedFromTheFullResultSoTheTwoCannotDisagree() {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 4; i++) samples.add(scored("p" + i, 22 + i, 20));
        var a = service(1, 1).compute("nba", 2026, NBA_SCORING, samples);
        AccuracySummary sum = ProjectionAccuracyService.summaryOf(a);
        Cell o = avgSeries(a).overall();
        assertEquals(o.n(), sum.n());
        assertEquals(o.mae(), sum.mae());
        assertEquals(o.lean(), sum.lean());
        assertEquals(a.status(), sum.status());
    }

    @Test
    void scoredThroughIsTheLatestRealizedGameAndPendingGamesDoNotMoveIt() {
        Map<String, Object> p = line(20, 0);
        var done = new Sample("a", "g1", LocalDate.of(2026, 10, 21), "PG", p, p, line(18, 0), "complete", "rotowire");
        var later = new Sample("b", "g2", LocalDate.of(2026, 10, 25), "PG", p, p, null, "pre_game", "rotowire");
        var a = service(1, 1).compute("nba", 2026, NBA_SCORING, List.of(done, later));
        assertEquals(LocalDate.of(2026, 10, 21), a.scoredThrough());
        assertEquals(LocalDate.of(2026, 10, 21), a.snapshotsFrom());
    }
}
