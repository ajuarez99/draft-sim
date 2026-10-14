package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 033 against the local database's real leagues, through the real read path (real SQL, real JDBC
 * binds). Skipped by {@link Assumptions} when Postgres or the league is absent: READ THE SKIP COUNT.
 *
 * <p>State observed when written (2026-10-14): the V32 backfill has NOT run, so every stored trade has
 * {@code draft_picks} null. These tests assert that state ({@code tradeAssetsLoaded == false}) and do not
 * assume the backfilled one; a trade stored before the backfill and made only of FAAB or picks has no
 * sides at all, which is shown as not comparable, never as a score.
 */
@SpringBootTest
class TradeVerdictServiceIT {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5433/draftsim";

    @Autowired private TransactionAnalysisService service;
    @Autowired private JdbcTemplate jdbc;

    private static boolean reachable() {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "draftsim", "draftsim")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private TransactionAnalysisService.Result load(long leagueId, String sleeperId, String label) {
        Assumptions.assumeTrue(reachable(), "no local Postgres at " + JDBC_URL + " -- skipping " + label);
        Assumptions.assumeTrue(jdbc.queryForObject("select count(*) from league where id = ? and sleeper_id = ?",
                Integer.class, leagueId, sleeperId) == 1, label + " is not in the local database");
        Optional<TransactionAnalysisService.Result> found = service.forLeague(sleeperId);
        assertTrue(found.isPresent());
        assertTrue(found.get().available(), label);
        return found.get();
    }

    @Test
    void nba2025ListsEveryStoredTradeAndNoneReadsZeroZero() {
        TransactionAnalysisService.Result r = load(211, "1229352720222134272", "NBA 2025");
        int stored = jdbc.queryForObject(
                "select count(*) from league_transaction where league_id = 211 and type = 'TRADE' and status = 'complete'",
                Integer.class);
        int missing = jdbc.queryForObject(
                "select count(*) from league_transaction where league_id = 211 and type = 'TRADE' and draft_picks is null",
                Integer.class);
        assertEquals(stored, r.trades().size(), "one entry per stored trade, FAAB-only and picks-only included");
        assertEquals(missing == 0, r.tradeAssetsLoaded(), "stored trades with null draft_picks: " + missing);
        assertTrue(r.seasonFinal());
        assertTrue(r.throughWeek() > 0);
        for (TradeVerdictService.TradeResult t : r.trades()) {
            TradeVerdictService.VerdictResult v = t.verdict();
            if (v.state().equals("WINNER")) assertTrue(v.margin() >= 0.05, t.txId());
            if (v.state().equals("EVEN")) assertTrue(Math.abs(v.margin()) < 0.05, t.txId());
            // "0-0" would be two sides with no weeks counted that still claim a state of play.
            if (v.weeksCounted() == 0) assertTrue(List.of("NO_WEEK_YET", "UNDONE", "RESTORED", "NONE_COUNTED", "NOT_COMPARABLE").contains(v.state()), t.txId());
        }
        for (int i = 1; i < r.trades().size(); i++) {
            var a = r.trades().get(i - 1).acceptedAt();
            var b = r.trades().get(i).acceptedAt();
            assertFalse(a.isBefore(b), "newest accepted first, never by stored id");
        }
    }

    /** SC-004: started totals equal a hand-written SQL sum over roster_week_points, to 0.1. */
    @Test
    void nba2025StartedTotalsMatchAHandSqlSum() {
        checkTotals(load(211, "1229352720222134272", "NBA 2025"), 211, "1292031786481614848");
    }

    @Test
    void nfl2025StartedTotalsMatchAHandSqlSum() {
        checkTotals(load(5, "1254190892974084096", "NFL 2025"), 5, "1283514796255232000");
    }

    private void checkTotals(TransactionAnalysisService.Result r, long leagueId, String txId) {
        TradeVerdictService.TradeResult t = r.trades().stream().filter(x -> x.txId().equals(txId)).findFirst().orElseThrow();
        int counted = 0;
        for (TradeVerdictService.SideResult side : t.sides()) {
            double sql = 0;
            for (TradeVerdictService.AssetResult a : side.assets()) {
                if (!(a instanceof TradeVerdictService.PlayerResult p) || p.firstWeek() == null) continue;
                counted++;
                // The asset's own counted span, summed by SQL: points where this roster STARTED him.
                Double v = jdbc.queryForObject("""
                        select coalesce(sum((players_points ->> ?)::numeric), 0)::float8
                        from roster_week_points
                        where league_id = ? and roster_id = ? and week between ? and ?
                          and players_points ? ? and starters @> to_jsonb(?::text)
                        """.replace("players_points ? ?", "jsonb_exists(players_points, ?)"),
                        Double.class, p.playerId(), leagueId, side.rosterId(), p.firstWeek(), p.lastWeek(),
                        p.playerId(), p.playerId());
                sql += v;
            }
            assertEquals(sql, side.startedTotal(), 0.1, "roster " + side.rosterId() + " of trade " + txId);
        }
        assertTrue(counted > 0, "the chosen trade counts at least one asset");
        assertNotEquals("NO_WEEK_YET", t.verdict().state());
    }

    /**
     * Spec 033 R5 / plan-review F1: the stored moves explain every roster change in the weekly rows, and the
     * only adds never seen on a scored roster are pickups dropped the next leg. 0/0/0 and 5/2/1 come from the
     * plan-review script (a different implementation); a disagreement is investigated, never edited away.
     */
    /**
     * DISAGREEMENT with the plan-review script (5), investigated and resolved in the Java's favour: NFL 2025 is 6.
     * The extra row is Kameron Johnson (11994): WAIVER 2424 (week 6) to roster 3, dropped by FREE_AGENT 2474
     * (week 7), never on ANY roster's scored row. The script walked only players present in the weekly rows, so
     * a player who never appeared anywhere was never checked -- a scope bug in the script, not in this code.
     * (Cooper Kupp's 2464 move was in the script's 5 as well; it is not the difference.) See plan-review.md.
     */
    @Test void reconciliationNfl2025() { check(5, "1254190892974084096", "NFL 2025", 6); }
    @Test void reconciliationNba2025() { check(211, "1229352720222134272", "NBA 2025", 2); }
    @Test void reconciliationNba2024() { check(212, "1141438340626231296", "NBA 2024", 1); }

    private void check(long leagueId, String sleeperId, String label, int expectedNever) {
        OwnershipTimeline.Reconciliation rec = load(leagueId, sleeperId, label).reconciliation();
        assertNotNull(rec, label);
        System.out.println("RECONCILIATION " + label + " " + rec);
        assertEquals(0, rec.unexplainedStretches(), label + " unexplained stretches");
        assertEquals(expectedNever, rec.neverOnAScoredRoster(), label + " never on a scored roster: " + rec.examples());
        assertTrue(rec.examples().size() <= 10);
        assertTrue(rec.playerWeeksChecked() > 0);
    }

    /**
     * F8 misfire scan over all four league-seasons: the only assets that may read ALREADY_HELD are NBA 1720's
     * two (Giannis and Reaves, restored by 1720 after 1753 was undone before a scored week). Anything else is
     * listed in the failure, not "fixed".
     */
    @Test
    void onlyTrade1720HasAlreadyHeldAssets() {
        java.util.List<String> found = new java.util.ArrayList<>();
        Object[][] leagues = {{5L, "1254190892974084096"}, {210L, "1339351318115946496"},
                {211L, "1229352720222134272"}, {212L, "1141438340626231296"}};
        for (Object[] l : leagues) {
            TransactionAnalysisService.Result r = load((Long) l[0], (String) l[1], "league " + l[0]);
            for (TradeVerdictService.TradeResult t : r.trades()) {
                for (TradeVerdictService.SideResult side : t.sides()) {
                    for (TradeVerdictService.AssetResult a : side.assets()) {
                        TradeVerdictService.PlayerResult p = a instanceof TradeVerdictService.PlayerResult pr ? pr
                                : a instanceof TradeVerdictService.PickResult k ? k.player() : null;
                        if (p != null && p.status().equals("ALREADY_HELD")) {
                            found.add("league " + l[0] + " tx " + t.txId() + " " + p.playerName() + " (" + p.playerId()
                                    + ") receiver roster " + side.rosterId() + " stretches " + p.stretches());
                        }
                    }
                }
            }
        }
        System.out.println("ALREADY_HELD SCAN " + found);
        assertEquals(2, found.size(), found.toString());
        for (String f : found) assertTrue(f.contains("tx 1325944237992185856"), f);
    }
}
