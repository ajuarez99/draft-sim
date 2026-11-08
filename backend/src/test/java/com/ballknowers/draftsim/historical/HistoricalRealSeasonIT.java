package com.ballknowers.draftsim.historical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 053 T029 / SC-001 against REAL imported seasons: creates a game, auto-finishes it, and checks the frozen
 * result is complete (12 standings rows, as many weeks as the season has). Records the wall time of create and
 * of the finishing call, and a sample of the standings and the three comparisons, into
 * {@code build/historical-real-season.txt}.
 *
 * <p>Reads the real reference data and writes only games under user {@code it-real-*}, which it removes.
 * SKIPS when Postgres is unreachable or the seasons were never imported, so read the skip count.
 */
@SpringBootTest
class HistoricalRealSeasonIT {

    private static final String USER = "it-real-user";

    @BeforeAll
    static void requiresPostgres() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url, "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url + " -- skipping real-season IT");
    }

    @Autowired private HistoricalGameService service;
    @Autowired private HistoricalDataRepository data;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from historical_game where sleeper_user_id like 'it-real-%'");
    }

    private final List<String> report = new ArrayList<>();

    @Test
    void aKaggleSeasonAndASleeperSeasonBothPlayThroughToAFrozenResult() throws Exception {
        for (int season : new int[]{1971, 1995, 2023}) {
            Assumptions.assumeTrue(data.offeredSeasons("NBA", season, season).size() == 1,
                    "season " + season + " is not imported here -- skipping");
            playThrough(season);
        }
        Path out = Path.of("build", "historical-real-season.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report);
    }

    private void playThrough(int season) throws Exception {
        long t0 = System.nanoTime();
        HistoricalGameState created = service.create(USER, "NBA", 6, season);
        long createMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals("DRAFTING", created.status());
        assertEquals(6, created.currentPickNo());

        long t1 = System.nanoTime();
        HistoricalGameState done = service.auto(created.id(), HistoricalGameService.Scope.FINISH, USER).orElseThrow();
        long finishMs = (System.nanoTime() - t1) / 1_000_000;

        assertEquals("COMPLETE", done.status());
        JsonNode result = done.result();
        assertEquals(12, result.get("standings").size(), "twelve teams");
        int weekCount = data.findSeason("NBA", season).orElseThrow().weekCount();
        assertEquals(weekCount, result.get("weeks").size(), "one replayed week per week of the real season");
        assertEquals(168, done.picks().size());
        assertEquals(weekCount, result.get("lineups").get("6").size());
        int wins = 0, losses = 0;
        for (JsonNode s : result.get("standings")) {
            wins += s.get("wins").asInt();
            losses += s.get("losses").asInt();
        }
        assertEquals(wins, losses, "every decided game has one winner and one loser");

        JsonNode h = result.get("hindsight");
        assertEquals(14, h.get("picks").size());
        assertEquals(h.get("pointsFor").asDouble() < h.get("userPointsFor").asDouble(), h.get("userBeatIt").asBoolean());

        // the user's own points: the first week's lineup sums to what the standings machinery used
        double week1 = 0;
        for (JsonNode st : result.get("lineups").get("6").get(0).get("starters")) week1 += st.get("points").asDouble();
        JsonNode m0 = result.get("weeks").get(0).get("matchups");
        double fromMatchups = Double.NaN;
        for (JsonNode m : m0) {
            if (m.get("homeSlot").asInt() == 6) fromMatchups = m.get("homePoints").asDouble();
            if (m.get("awaySlot").asInt() == 6) fromMatchups = m.get("awayPoints").asDouble();
        }
        assertEquals(fromMatchups, Math.round(week1 * 100) / 100.0, 0.011, "week-1 starters add up to the matchup score");

        report.add("== season " + season + " (" + data.findSeason("NBA", season).orElseThrow().source() + ") ==");
        report.add("create " + createMs + " ms, final auto-FINISH (13 user picks + bots + replay) " + finishMs + " ms");
        report.add("weeks " + weekCount + ", userFinish " + result.get("userFinish").asInt());
        for (JsonNode s : result.get("standings")) {
            report.add(String.format("  place %2d  slot %2d  %d-%d-%d  PF %.2f%s", s.get("place").asInt(),
                    s.get("slot").asInt(), s.get("wins").asInt(), s.get("losses").asInt(), s.get("ties").asInt(),
                    s.get("pointsFor").asDouble(), s.get("slot").asInt() == 6 ? "   <- user" : ""));
        }
        report.add(String.format("  hindsight %.2f vs user %.2f (userBeatIt=%s) %s", h.get("pointsFor").asDouble(),
                h.get("userPointsFor").asDouble(), h.get("userBeatIt").asBoolean(), h.get("label").asText()));
        JsonNode bust = result.get("biggestBust"), steal = result.get("bestStealPassedOn");
        report.add("  bust: " + (bust.isNull() ? "none" : "pick " + bust.get("pickNo").asInt() + " "
                + bust.get("player").get("name").asText() + " finished " + bust.get("seasonValueRank").asInt()
                + "th of 168 drafted (value " + bust.get("seasonValue").asDouble() + ")"));
        report.add("  steal: " + (steal.isNull() ? "none" : "at pick " + steal.get("pickNo").asInt() + " took "
                + steal.get("taken").get("name").asText() + " (" + steal.get("takenValue").asDouble()
                + "), passed on " + steal.get("passedOn").get("name").asText() + " ("
                + steal.get("passedOnValue").asDouble() + ")"));
        System.out.println(String.join("\n", report.subList(report.size() - 17 > 0 ? report.size() - 17 : 0,
                report.size())));
    }

    @Autowired private com.ballknowers.draftsim.config.HistoricalProperties props;

    /** Spec 053 F6 as amended at build: Jordan drafting 1995-96 (17 G in 1994-95, retired 1993-94) must rank. */
    @Test
    void jordanIsInTheTopTenOfThe1995Board() throws Exception {
        int s = 1995;
        Assumptions.assumeTrue(data.offeredSeasons("NBA", s, s).size() == 1, "season 1995 is not imported here -- skipping");
        var season = data.offeredSeasons("NBA", s, s).get(0);
        java.util.Map<String, Double> scoring = new java.util.TreeMap<>();
        for (String k : season.countedStats()) {
            if (props.scoring().containsKey(k)) scoring.put(k, props.scoring().get(k));
        }
        List<java.util.Map<Long, HistoricalDataRepository.SeasonLine>> priors = new ArrayList<>();
        for (int i = 1; i <= props.rankLookbackSeasons(); i++) {
            java.util.Map<Long, HistoricalDataRepository.SeasonLine> m = new java.util.HashMap<>();
            for (var l : data.seasonLines(s - i)) m.put(l.line().playerId(), l.line());
            priors.add(m);
        }
        java.util.Map<Long, HistoricalDataRepository.SeasonLine> drafted = new java.util.HashMap<>();
        for (var l : data.seasonLines(s)) drafted.put(l.line().playerId(), l.line());
        var cards = HistoricalBoardBuilder.build(s, 1L, scoring, data.candidates(s), priors, drafted,
                new HistoricalBoardBuilder.RankConfig(props.rankStandardGames(), props.rankMinGames()));

        List<String> top = new ArrayList<>();
        for (var c : cards.subList(0, 10)) {
            top.add(String.format("%2d  %s  (%s, %d G)", c.rank(), c.name(), c.prior().bestOfTwo(),
                    c.prior().games()));
        }
        var jordan = cards.stream().filter(c -> c.name().equals("Michael Jordan")).findFirst().orElseThrow();
        String msg = "1995 board top 10:" + System.lineSeparator() + String.join(System.lineSeparator(), top)
                + System.lineSeparator() + "Jordan rank " + jordan.rank();
        System.out.println(msg);
        Path out = Path.of("build", "historical-1995-board.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, msg);
        assertTrue(jordan.rank() <= 10, msg);
    }
}
