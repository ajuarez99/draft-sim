package com.ballknowers.draftsim.store;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/002-league-history-record-book SC-002: the extremes query against real
 * Postgres, checked against the numbers frozen in that feature's baseline.md.
 *
 * <p>The ordering and the tiebreak live in SQL, so a Mockito test of the
 * service cannot reach them -- this is where they are actually verified.
 *
 * <p>Follows the {@link LeagueHistoryContaminationIT} convention of skipping
 * when no local Postgres is reachable. Note that a skip here is invisible in a
 * "BUILD SUCCESSFUL" line; check the skip count, not the exit code.
 */
@SpringBootTest
class LeagueRecordIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";
    private static final String USER = "draftsim";
    private static final String PASSWORD = "draftsim";

    @Autowired private RosterWeekPointsRepository weekPoints;
    @Autowired private LeagueRepository leagues;

    @BeforeAll
    static void requiresLocalPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "no local Postgres reachable at " + JDBC_URL + " -- skipping league record test");
    }

    /** The (Foot) Ball Knowers chain, or skip -- this asserts against that league's real numbers. */
    private Set<Long> footballChain() {
        List<LeagueRepository.LeagueRow> chain = leagues.chainBySleeperId("1346366555759341568");
        Assumptions.assumeFalse(chain.isEmpty(), "(Foot) Ball Knowers not ingested in this database");
        return chain.stream().map(LeagueRepository.LeagueRow::id).collect(java.util.stream.Collectors.toSet());
    }

    /**
     * baseline.md Q2: the chain's maximum is 205.04, posted in 2025 week 8.
     *
     * <p>Independently corroborated -- the reference product shows the same
     * 205.04 for that week, which is how we know starters_points is the column
     * a reader expects rather than merely a column that exists.
     */
    @Test
    void highestWeekInTheChainIs205AtSeason2025Week8() {
        List<RosterWeekPointsRepository.ScoreRow> top = weekPoints.extremes(footballChain(), true, 5);

        Assumptions.assumeFalse(top.isEmpty(), "no roster_week_points stored for this chain");
        assertEquals(new BigDecimal("205.04"), top.get(0).points());
        assertEquals(2025, top.get(0).season());
        assertEquals(8, top.get(0).week());
    }

    /** Descending for highest, ascending for lowest -- not the same list twice. */
    @Test
    void lowestIsAscendingAndDiffersFromHighest() {
        List<RosterWeekPointsRepository.ScoreRow> low = weekPoints.extremes(footballChain(), false, 5);
        Assumptions.assumeFalse(low.isEmpty(), "no roster_week_points stored for this chain");

        assertEquals(new BigDecimal("40.68"), low.get(0).points(), "baseline.md Q2 bottom row");
        for (int i = 1; i < low.size(); i++) {
            assertTrue(low.get(i).points().compareTo(low.get(i - 1).points()) >= 0,
                    "lowest list must ascend");
        }
    }

    /**
     * FR-001 -- records span the chain. A result confined to the head league
     * would mean the aggregation is scoped to the current season, which is the
     * question the page could already answer.
     */
    @Test
    void recordsSpanMoreThanTheHeadSeason() {
        Set<Long> chain = footballChain();
        Assumptions.assumeTrue(chain.size() > 1, "this chain has only one ingested season");

        List<RosterWeekPointsRepository.ScoreRow> top = weekPoints.extremes(chain, true, 10);
        Assumptions.assumeFalse(top.isEmpty(), "no roster_week_points stored for this chain");

        assertTrue(top.stream().anyMatch(r -> r.season() == 2025),
                "the all-time high is in 2025, a season that is not the head of the chain");
    }

    /**
     * R2 -- deterministic ordering. Two calls must agree, or the page reshuffles
     * on every reload and a tie at the boundary silently changes who is in the
     * list.
     */
    @Test
    void orderingIsDeterministicAcrossRepeatCalls() {
        Set<Long> chain = footballChain();
        List<RosterWeekPointsRepository.ScoreRow> a = weekPoints.extremes(chain, true, 10);
        List<RosterWeekPointsRepository.ScoreRow> b = weekPoints.extremes(chain, true, 10);
        assertEquals(a, b);
    }

    /**
     * The limit is honoured. 204 rows are stored for 2025 alone, so an
     * unbounded query would put the whole season on the page.
     */
    @Test
    void limitIsHonoured() {
        assertTrue(weekPoints.extremes(footballChain(), true, 3).size() <= 3);
    }

    /**
     * research.md § Open risk: nothing enforces that a stored week holds every
     * roster, so the query must not assume a full slate. An empty league id set
     * is the degenerate form of the same thing and must return empty rather
     * than throw or return everything.
     */
    @Test
    void emptyLeagueSetReturnsNothingRatherThanEverything() {
        assertTrue(weekPoints.extremes(Set.of(), true, 10).isEmpty());
    }
}
