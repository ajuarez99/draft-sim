package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TransactionAnalysisService#careerWaiverTendency} against real
 * Postgres, specs/006-deeper-history-both-sports T067 (US6). A repository-
 * walking, cross-league-season method like this one is exactly what
 * {@code HeadToHeadServiceTest}/{@code ManagerCareerServiceTest} argue a
 * Mockito test cannot reach honestly, so this seeds its own small, controlled
 * fixture rather than mocking every repository call.
 *
 * <p>Three league-seasons, one manager:
 * <ul>
 *   <li>{@code leagueFaabA} -- {@code waiver_type} 2 (FAAB), budget 200. One
 *       won bid of 20 (10% of budget) and one lost bid of 40 (20%).
 *   <li>{@code leagueFaabB} -- {@code waiver_type} 2 (FAAB), budget 1000 --
 *       DELIBERATELY a different order of magnitude than leagueFaabA's 200,
 *       the same 50x spread research R8 measured live (100 -> 10000). One won
 *       bid of 500 (50% of budget).
 *   <li>{@code leaguePriority} -- {@code waiver_type} 0 (priority, no
 *       bidding), the shape NFL 2025 actually has: WAIVER rows with no
 *       {@code faab_bid} at all, plus a FREE_AGENT row. Included to prove it
 *       is excluded, not just absent by construction.
 * </ul>
 */
@SpringBootTest
class TransactionAnalysisServiceCareerFaabTest {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping career FAAB test");
    }

    @Autowired private TransactionAnalysisService transactions;
    @Autowired private LeagueRepository leagueRepository;
    @Autowired private JdbcTemplate jdbc;

    private long leagueFaabA;     // 2024, FAAB, budget 200.
    private long leagueFaabB;     // 2025, FAAB, budget 1000.
    private long leaguePriority;  // 2026, waiver priority, budget 100 (unused -- no bidding at all).
    private long manager;

    private static final List<String> LEAGUE_SLEEPER_IDS =
            List.of("it-faab-a", "it-faab-b", "it-faab-priority");

    @BeforeEach
    void setUp() {
        cleanup();

        manager = jdbc.queryForObject(
                "insert into manager (sleeper_user_id, display_name) values (?, ?) returning id",
                Long.class, "it-faab-manager", "IT FAAB Manager");

        leagueFaabA = insertLeague("it-faab-a", 2024, 2, 200);
        leagueFaabB = insertLeague("it-faab-b", 2025, 2, 1000);
        leaguePriority = insertLeague("it-faab-priority", 2026, 0, 100);

        // leagueFaabA: one won bid (20/200 = 10%), one lost bid (40/200 = 20%).
        insertTransaction(leagueFaabA, 2024, "it-faab-a-won", "WAIVER", "complete", manager, 20);
        insertTransaction(leagueFaabA, 2024, "it-faab-a-lost", "WAIVER", "failed", manager, 40);

        // leagueFaabB: one won bid at a completely different budget scale (500/1000 = 50%).
        insertTransaction(leagueFaabB, 2025, "it-faab-b-won", "WAIVER", "complete", manager, 500);

        // leaguePriority: the NFL-2025 shape -- WAIVER claims with NO bid at
        // all, plus an ordinary free-agent add. Neither carries a faab_bid.
        insertTransaction(leaguePriority, 2026, "it-priority-w1", "WAIVER", "complete", manager, null);
        insertTransaction(leaguePriority, 2026, "it-priority-w2", "WAIVER", "complete", manager, null);
        insertTransaction(leaguePriority, 2026, "it-priority-w3", "WAIVER", "failed", manager, null);
        insertTransaction(leaguePriority, 2026, "it-priority-fa1", "FREE_AGENT", "complete", manager, null);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("delete from league where sleeper_id in (?, ?, ?)", LEAGUE_SLEEPER_IDS.toArray());
        jdbc.update("delete from manager where sleeper_user_id = ?", "it-faab-manager");
    }

    private long insertLeague(String sleeperId, int season, int waiverType, int waiverBudget) {
        return jdbc.queryForObject("""
                insert into league (sport, season, sleeper_id, name, total_rosters, status, roster_positions,
                                    settings_json)
                values ('nfl', ?, ?, ?, 4, 'complete', '{QB}',
                        jsonb_build_object('waiver_type', ?, 'waiver_budget', ?))
                returning id
                """, Long.class, season, sleeperId, "IT League " + sleeperId, waiverType, waiverBudget);
    }

    private void insertTransaction(long leagueId, int season, String sleeperTxId, String type, String status,
                                   Long managerId, Integer faabBid) {
        jdbc.update("""
                insert into league_transaction (league_id, season, week, sleeper_transaction_id, type, status,
                                                roster_id, manager_id, adds, drops, faab_bid)
                values (?, ?, 1, ?, ?, ?, 1, ?, '{}'::jsonb, '{}'::jsonb, ?)
                """, leagueId, season, sleeperTxId, type, status, managerId, faabBid);
    }

    private List<LeagueRepository.LeagueRow> allThreeSeasons() {
        return List.of(
                leagueRepository.byId(leagueFaabA).orElseThrow(),
                leagueRepository.byId(leagueFaabB).orElseThrow(),
                leagueRepository.byId(leaguePriority).orElseThrow());
    }

    // ------------------------------------------------------------- T067(a)

    /**
     * data-model.md / research R8, quoted in the task: a season whose
     * {@code waiver_type} is not FAAB is excluded from FAAB aggregation with
     * the reason "used waiver priority, not FAAB" -- named, not silently
     * dropped, and not folded into the FAAB figures as a season with zero
     * bids.
     */
    @Test
    void aWaiverPrioritySeasonIsExcludedFromFaabAggregationWithItsReason() {
        TransactionAnalysisService.WaiverTendency w =
                transactions.careerWaiverTendency(manager, allThreeSeasons(), 3);

        assertEquals(1, w.faabExcludedSeasons().size(), "exactly one of the three seasons used priority");
        TransactionAnalysisService.ExcludedSeason excluded = w.faabExcludedSeasons().get(0);
        assertEquals(2026, excluded.season());
        assertEquals("used waiver priority, not FAAB", excluded.reason());

        // The excluded season's own WAIVER/FREE_AGENT rows still count as
        // MOVES (T070's status-blind rule) -- being excluded from FAAB does
        // not mean being excluded from "how many moves did this manager
        // make". 2 (leagueFaabA) + 1 (leagueFaabB) + 4 (leaguePriority) = 7,
        // rounded to 2 decimals the same way pointsPerSeason is.
        assertEquals(2.33, w.movesPerSeason(), 1e-9);
    }

    // ------------------------------------------------------------- T067(b)

    /**
     * research R8 / data-model.md FaabTendency, verbatim: figures are
     * fractions of EACH season's own budget, aggregated AFTER normalisation.
     * leagueFaabA's 200-budget and leagueFaabB's 1000-budget are a 5x spread
     * within this one fixture (the real database's spread, 100 -> 10000, is
     * 50x) -- a bug that sums raw dollars first and divides once at the end
     * would let leagueFaabB's 500 swamp leagueFaabA's 20 and 40, and would
     * not reproduce 30%/50% below under any consistent single divisor.
     */
    @Test
    void bidsFromDifferentBudgetSeasonsAggregateAsPercentagesOfEachSeasonsOwnBudget() {
        TransactionAnalysisService.WaiverTendency w =
                transactions.careerWaiverTendency(manager, allThreeSeasons(), 3);

        assertNotNull(w.faab(), "two FAAB seasons produced bids -- faab must not be null");
        TransactionAnalysisService.FaabTendency faab = w.faab();

        // Per-bid percentages: 20/200=10%, 40/200=20%, 500/1000=50%.
        // Mean of the three percentages, not of the three dollar amounts.
        assertEquals((0.10 + 0.20 + 0.50) / 3, faab.typicalBidPct(), 1e-4,
                "typicalBidPct must be the mean of each bid's OWN percentage, not raw dollars averaged then divided");
        assertEquals(0.50, faab.largestBidPct(), 1e-9, "the largest bid by PERCENTAGE is leagueFaabB's 50%");

        // Only the two WON bids (10% + 50%) are spent; the lost 20% never
        // left the budget. Divided by the SAME seasonsCounted (3) passed in,
        // per T073's "one source for the number and its label" rule.
        assertEquals((0.10 + 0.50) / 3, faab.spentPerSeasonPct(), 1e-4);

        // 3 bid attempts total (2 in leagueFaabA, 1 in leagueFaabB), 2 of them won.
        assertEquals(3.0 / 3, faab.claimsPerSeason(), 1e-9);
        assertEquals(2.0 / 3, faab.bidSuccessRate(), 1e-4);
    }

    // ------------------------------------------------------------- sanity

    /** A manager with no league-seasons at all gets an honest empty answer, not a divide-by-zero. */
    @Test
    void noLeagueSeasonsProducesAnEmptyTendencyRatherThanAnException() {
        TransactionAnalysisService.WaiverTendency w = transactions.careerWaiverTendency(manager, List.of(), 0);
        assertEquals(0, w.movesPerSeason());
        assertNull(w.faab());
        assertTrue(w.faabExcludedSeasons().isEmpty());
    }
}
