package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.DraftGradesService.DraftGrades;
import com.ballknowers.draftsim.engine.DraftGradesService.PickGrade;
import com.ballknowers.draftsim.engine.DraftGradesService.ProductionBasis;
import com.ballknowers.draftsim.engine.DraftGradesService.TeamGrade;
import com.ballknowers.draftsim.store.DraftRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 018 T020: the real read path over the real database, checked against contract C1's
 * invariants and with values read back, not just counted. SKIPS (with a message) when the local
 * Postgres is unreachable or the draft is not in it, so the caller must read the skip count.
 * Prints the steals and busts it measured as "REPORT" lines.
 */
@SpringBootTest
class DraftGradesReadIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String NBA_2025 = "1229352720230514688";
    private static final String NFL_2025 = "1254190894563729408";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no local Postgres reachable at " + JDBC_URL + " -- skipping DraftGradesReadIT");
    }

    @Autowired private DraftGradesService service;
    @Autowired private DraftRepository drafts;

    private DraftGrades read(String sleeperDraftId) {
        Optional<DraftRepository.DraftRow> draft = drafts.bySleeperId(sleeperDraftId);
        Assumptions.assumeTrue(draft.isPresent(), "draft " + sleeperDraftId + " is not in the local database");
        DraftGrades g = service.read(draft.get());
        int stored = drafts.picks(draft.get().id()).size();
        assertEquals(stored, g.picks().size() + g.excludedPicks(), "invariant 1");
        return g;
    }

    private static void checkInvariants(DraftGrades g) {
        assertTrue(g.available(), "reason: " + g.reason());
        // 2: available <=> weeksCounted > 0 for a complete draft with config.
        assertTrue(g.weeksCounted() > 0);
        assertNull(g.reason());
        // 3
        assertEquals(g.weeksCounted() < SeasonWindow.EARLY_THRESHOLD_WEEKS, g.gradesEarly());
        assertEquals(SeasonWindow.EARLY_THRESHOLD_WEEKS, g.earlyThresholdWeeks());
        // 4, and value read back as production minus baseline
        for (PickGrade p : g.picks()) {
            assertTrue(p.production() >= 0, "production " + p.production() + " for pick " + p.pickNo());
            assertTrue(p.weeksPlayed() <= g.weeksCounted());
            if (p.slotBaseline() != null) {
                assertEquals(p.production() - p.slotBaseline(), p.valueOverSlot(), 0.011, "pick " + p.pickNo());
            } else {
                assertNull(p.valueOverSlot());
            }
        }
        // 5
        double sum = g.teams().stream().map(TeamGrade::draftValue).filter(v -> v != null)
                .mapToDouble(Double::doubleValue).sum();
        assertEquals(0.0, sum, 0.01 * g.teams().size());
        // 6
        Set<Integer> pickNos = g.picks().stream().map(PickGrade::pickNo).collect(Collectors.toSet());
        assertTrue(g.steals().size() <= 5 && g.busts().size() <= 5);
        assertTrue(pickNos.containsAll(g.steals()) && pickNos.containsAll(g.busts()));
        assertTrue(Collections.disjoint(g.steals(), g.busts()));
        Map<Integer, PickGrade> byNo = g.picks().stream().collect(Collectors.toMap(PickGrade::pickNo, p -> p));
        double minSteal = g.steals().stream().mapToDouble(n -> byNo.get(n).valueOverSlot()).min().orElse(0);
        double maxBust = g.busts().stream().mapToDouble(n -> byNo.get(n).valueOverSlot()).max().orElse(0);
        assertTrue(maxBust <= minSteal);
        // 9
        assertTrue(Collections.disjoint(g.countedWeeks(), g.weeksMissingGameData()));
        assertEquals(g.countedWeeks().size(), g.weeksCounted());
        for (TeamGrade t : g.teams()) assertEquals(t.draftValue() == null, t.rank() == null);
        // 4 (counted for you) and 8
        int mapped = 0;
        for (PickGrade p : g.picks()) {
            if (p.countedForYou() == null) {
                assertNull(p.creditedForYou());
                assertNull(p.weeksStartedForYou());
                assertNull(p.weeksUnknownForYou());
                continue;
            }
            mapped++;
            assertNotNull(p.creditedForYou());
            assertTrue(p.countedForYou() <= p.production() + 0.01,
                    "pick " + p.pickNo() + " counted " + p.countedForYou() + " > production " + p.production());
            assertTrue(p.weeksStartedForYou() + p.weeksUnknownForYou() <= g.weeksCounted(), "pick " + p.pickNo());
            assertTrue(p.weeksStartedForYou() >= 0 && p.weeksUnknownForYou() >= 0);
        }
        assertEquals(g.picks().size() - mapped, g.unmappedPicks(), "unmappedPicks");
    }

    private static double mean(DraftGrades g, java.util.function.IntPredicate pickNo) {
        return g.picks().stream().filter(p -> pickNo.test(p.pickNo()) && p.valueOverSlot() != null)
                .mapToDouble(PickGrade::valueOverSlot).average().orElse(Double.NaN);
    }

    private static void reportEdges(String label, DraftGrades g) {
        int last = g.picks().stream().mapToInt(PickGrade::pickNo).max().orElse(0);
        System.out.println("REPORT " + label + " mean value picks1-6=" + mean(g, n -> n <= 6)
                + " picks1-12=" + mean(g, n -> n <= 12) + " picks61-108=" + mean(g, n -> n >= 61 && n <= 108)
                + " last12=" + mean(g, n -> n > last - 12) + " (last pick " + last + ")");
        System.out.println("REPORT " + label + " unmappedPicks=" + g.unmappedPicks() + " fitted/unfitted="
                + g.picks().stream().filter(p -> p.valueOverSlot() != null).count() + "/"
                + g.picks().stream().filter(p -> p.valueOverSlot() == null).count());
    }

    private static void report(String label, DraftGrades g) {
        Map<Integer, PickGrade> byNo = g.picks().stream().collect(Collectors.toMap(PickGrade::pickNo, p -> p));
        System.out.println("REPORT " + label + " weeksCounted=" + g.weeksCounted() + " picks=" + g.picks().size()
                + " excluded=" + g.excludedPicks() + " unpositioned=" + g.unpositionedPicks()
                + " basis=" + g.productionBasis() + " avgTeamRaw=" + g.averageTeamRawValue()
                + " missing=" + g.weeksMissingGameData());
        for (int n : g.steals()) System.out.println("REPORT " + label + " STEAL " + line(byNo.get(n)));
        for (int n : g.busts()) System.out.println("REPORT " + label + " BUST " + line(byNo.get(n)));
        for (TeamGrade t : g.teams()) {
            System.out.println("REPORT " + label + " TEAM slot=" + t.slot() + " " + t.manager() + " value="
                    + t.draftValue() + " rank=" + t.rank() + " grade=" + t.grade());
        }
    }

    private static String line(PickGrade p) {
        return "pick " + p.pickNo() + " " + p.playerName() + " " + p.position() + " value=" + p.valueOverSlot()
                + " production=" + p.production() + " baseline=" + p.slotBaseline() + " weeksPlayed=" + p.weeksPlayed();
    }

    @Test
    void nba2025() {
        DraftGrades g = read(NBA_2025);
        checkInvariants(g);
        assertEquals(21, g.weeksCounted());
        assertEquals(ProductionBasis.WEEKLY_AVERAGE_GAME, g.productionBasis());
        assertEquals(168, g.picks().size() + g.excludedPicks());
        // B1: basketball fits one curve over the whole draft, so nobody is unpositioned or below the minimum.
        assertEquals(0, g.unpositionedPicks());
        for (PickGrade p : g.picks()) assertNotNull(p.slotBaseline(), "pick " + p.pickNo() + " has a baseline");
        report("NBA2025", g);
        List<PickGrade> noGames = g.picks().stream().filter(p -> p.weeksPlayed() == 0).toList();
        System.out.println("REPORT NBA2025 weeksPlayed0 count=" + noGames.size());
        noGames.forEach(p -> System.out.println("REPORT NBA2025 WEEKS0 " + line(p)));
        reportEdges("NBA2025", g);
        reportForYou(g);
    }

    private void reportForYou(DraftGrades g) {
        long below = g.picks().stream().filter(p -> p.countedForYou() != null
                && p.countedForYou() < 0.5 * p.production()).count();
        double maxOver = g.picks().stream().filter(p -> p.countedForYou() != null)
                .mapToDouble(p -> p.countedForYou() - p.production()).max().orElse(Double.NaN);
        int unknown = g.picks().stream().filter(p -> p.weeksUnknownForYou() != null)
                .mapToInt(PickGrade::weeksUnknownForYou).sum();
        double maxCreditRatio = g.picks().stream().filter(p -> p.countedForYou() != null && p.countedForYou() > 0)
                .mapToDouble(p -> p.creditedForYou() / p.countedForYou()).max().orElse(Double.NaN);
        System.out.println("REPORT NBA2025 forYou: countedForYou<0.5*production=" + below + " of "
                + g.picks().stream().filter(p -> p.countedForYou() != null).count()
                + "; max(countedForYou-production)=" + maxOver + "; total weeksUnknownForYou=" + unknown
                + "; unmappedPicks=" + g.unmappedPicks() + "; max credited/counted=" + maxCreditRatio);
        // Playoff-week example: pick 1 (Jokic) was in his roster's stored lineup in week 19, a week that roster
        // had no matchup. Compare the service's count with the lineup weeks straight from SQL.
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim");
             java.sql.PreparedStatement st = c.prepareStatement("""
                select rw.week, (lm.matchup_id is not null) as had_game
                from draft d
                join draft_pick pk on pk.draft_id = d.id and pk.pick_no = ?
                join player p on p.id = pk.player_id
                join roster_season rs on rs.league_id = d.league_id and rs.manager_id = pk.manager_id
                join roster_week_points rw on rw.league_id = d.league_id and rw.season = d.season and rw.roster_id = rs.roster_id
                left join league_matchup lm on lm.league_id = d.league_id and lm.season = d.season
                     and lm.week = rw.week and lm.roster_id = rs.roster_id
                where d.sleeper_draft_id = ? and rw.starters::text like '%"' || p.sleeper_id || '"%'
                order by rw.week""")) {
            for (int pickNo : new int[] {1, 3}) {
                st.setInt(1, pickNo);
                st.setString(2, NBA_2025);
                List<Integer> inLineup = new java.util.ArrayList<>();
                List<Integer> noGame = new java.util.ArrayList<>();
                try (java.sql.ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        inLineup.add(rs.getInt(1));
                        if (!rs.getBoolean(2)) noGame.add(rs.getInt(1));
                    }
                }
                PickGrade p = g.picks().stream().filter(x -> x.pickNo() == pickNo).findFirst().orElseThrow();
                System.out.println("REPORT NBA2025 playoff example pick " + pickNo + " " + p.playerName()
                        + ": in stored lineup in weeks " + inLineup.size() + " (SQL, counted weeks only if <= 21), "
                        + "of which no matchup in weeks " + noGame + "; service weeksStartedForYou="
                        + p.weeksStartedForYou() + " weeksUnknownForYou=" + p.weeksUnknownForYou()
                        + " countedForYou=" + p.countedForYou() + " production=" + p.production());
                long inCounted = inLineup.stream().filter(g.countedWeeks()::contains).count();
                long noGameCounted = noGame.stream().filter(g.countedWeeks()::contains).count();
                assertEquals(inCounted - noGameCounted, (long) p.weeksStartedForYou(),
                        "started weeks = counted lineup weeks in which the roster had a game");
            }
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void nfl2025() {
        DraftGrades g = read(NFL_2025);
        checkInvariants(g);
        assertEquals(ProductionBasis.WEEKLY_GAME, g.productionBasis());
        report("NFL2025", g);
        reportEdges("NFL2025", g);
        Map<Integer, PickGrade> byNo = g.picks().stream().collect(Collectors.toMap(PickGrade::pickNo, p -> p));
        long qbs = g.steals().stream().filter(n -> "QB".equals(byNo.get(n).position())).count();
        assertTrue(qbs < 3, "QBs among the top 5 steals: " + qbs);
        Map<String, Long> mix = g.picks().stream().filter(p -> p.valueOverSlot() != null)
                .sorted(Comparator.comparingDouble((PickGrade p) -> p.valueOverSlot()).reversed()
                        .thenComparingInt(PickGrade::pickNo))
                .limit(20).collect(Collectors.groupingBy(p -> String.valueOf(p.position()), LinkedHashMap::new,
                        Collectors.counting()));
        System.out.println("REPORT NFL2025 top20 steals position mix=" + mix);
    }
}
