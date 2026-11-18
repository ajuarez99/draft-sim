package com.ballknowers.draftsim.usage;

import com.ballknowers.draftsim.historical.HistoricalDataRepository;
import com.ballknowers.draftsim.historical.HistoricalGameRepository;
import com.ballknowers.draftsim.historical.HistoricalSyntheticSeason;
import com.ballknowers.draftsim.historical.NflSyntheticSeason;
import org.junit.jupiter.api.AfterAll;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 076 T017: the Redraft funnel read, against real Postgres. Games are seeded with {@code created_at} in March
 * 1990 (days no real game can have) under {@code it-usage-fn-} owners on the made-up 1895 season, and deleted
 * before and after. The shared database holds real games too, but none in 1990, so the range 1990-03-10..13 holds
 * only these.
 *
 * <p>SKIPS when Postgres is not reachable, so read the skip count.
 */
@SpringBootTest
class UsageFunnelIT {

    private static final LocalDate FROM = LocalDate.of(1990, 3, 10), TO = LocalDate.of(1990, 3, 13);
    private static final String OWNER = "it-usage-fn-owner";

    private static String url() {
        return System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5433/draftsim");
    }

    @BeforeAll
    static void requiresPostgres() {
        boolean reachable;
        try (Connection c = DriverManager.getConnection(url(), "draftsim", "draftsim")) {
            reachable = true;
        } catch (SQLException e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Postgres reachable at " + url() + " -- skipping funnel IT");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HistoricalDataRepository data;
    @Autowired private HistoricalGameRepository games;
    @Autowired private UsageRepository usage;
    @Autowired private UsageService service;

    @BeforeEach
    void fresh() {
        cleanup(jdbc);
        HistoricalSyntheticSeason.clean(jdbc);
        HistoricalSyntheticSeason.seed(data);
        data.upsertSeason(NflSyntheticSeason.seasonRow(NflSyntheticSeason.S, 4, 64, null, 0)); // the row the FK needs
    }

    @AfterAll
    static void removeEverything() {
        try {
            JdbcTemplate j = new JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(url(), "draftsim", "draftsim"));
            cleanup(j);
            HistoricalSyntheticSeason.clean(j);
            j.update("delete from historical_season where sport = 'NFL' and season_start = ?", NflSyntheticSeason.S);
        } catch (RuntimeException e) {
            // Postgres was unreachable, so nothing was written either.
        }
    }

    private static void cleanup(JdbcTemplate j) {
        j.update("delete from historical_game where sleeper_user_id like '%it-usage-fn-%'");
    }

    /** One game through the real insert, then forced into the status and guest flag the case needs. */
    private void game(String owner, String createdUtc, String status, LocalDate daily, Boolean startedAsGuest) {
        long id = games.create(owner, "NBA", HistoricalSyntheticSeason.S, 2, 1, 0, List.of("G"), "{}", 1, 1L, "[]",
                OffsetDateTime.parse(createdUtc), daily, false);
        jdbc.update("update historical_game set status = ?, started_as_guest = ? where id = ?", status, startedAsGuest, id);
    }

    /** The same, for a football game: only the sport differs. */
    private void nflGame(String owner, String createdUtc, String status, Boolean startedAsGuest) {
        long id = games.create(owner, "NFL", NflSyntheticSeason.S, 2, 1, 0, List.of("G"), "{}", 1, 1L, "[]",
                OffsetDateTime.parse(createdUtc), null, false);
        jdbc.update("update historical_game set status = ?, started_as_guest = ? where id = ?", status, startedAsGuest, id);
    }

    private void seedWorld() {
        LocalDate d12 = LocalDate.of(1990, 3, 12);
        // 04:30 UTC on the 12th is 23:30 on the 11th in New York: it belongs to the 11th
        game("it-usage-fn-m1", "1990-03-12T04:30:00Z", "COMPLETE", null, false);
        game("guest:it-usage-fn-g1", "1990-03-12T16:00:00Z", "DRAFTING", null, true);
        game("guest:it-usage-fn-g2", "1990-03-12T17:00:00Z", "COMPLETE", null, true);
        game("it-usage-fn-m2", "1990-03-12T18:00:00Z", "ABANDONED", d12, false);
        game("it-usage-fn-m3", "1990-03-12T19:00:00Z", "COMPLETE", d12, null);          // not recorded
        game(OWNER, "1990-03-12T20:00:00Z", "COMPLETE", null, false);
        game(OWNER, "1990-03-12T21:00:00Z", "DRAFTING", null, false);
        // the owner played the game of the day signed in, and abandoned another
        game(OWNER, "1990-03-12T22:00:00Z", "COMPLETE", d12, false);
        game(OWNER, "1990-03-12T22:30:00Z", "ABANDONED", null, false);
        // a game the owner played as a guest and then claimed: owned by the owner, still a guest game
        game(OWNER, "1990-03-12T23:00:00Z", "COMPLETE", null, true);
        // a football game: the sport grouping and the lowercase conversion are exercised against the database
        nflGame("it-usage-fn-m4", "1990-03-12T18:30:00Z", "COMPLETE", false);
    }

    @Test
    void everyFunnelFieldAndTheEasternDayBucket() {
        seedWorld();
        List<UsageRepository.FunnelRow> rows = usage.funnel(FROM, TO, OWNER);
        assertEquals(3, rows.size(), "the 11th (NBA), then the 12th NBA and NFL");

        UsageRepository.FunnelRow eleventh = rows.get(0);
        assertEquals(LocalDate.of(1990, 3, 11), eleventh.day(), "04:30Z is still the 11th in New York");
        assertEquals("NBA", eleventh.sport());
        assertEquals(1, eleventh.started());
        assertEquals(1, eleventh.finished());
        assertEquals(0, eleventh.abandoned() + eleventh.drafting() + eleventh.guestStarted() + eleventh.dailyStarted()
                + eleventh.ownerStarted());

        UsageRepository.FunnelRow twelfth = rows.get(1);
        assertEquals(LocalDate.of(1990, 3, 12), twelfth.day());
        assertEquals("NBA", twelfth.sport());
        assertEquals(9, twelfth.started());
        assertEquals(5, twelfth.finished(), "g2, m3, the owner's first, the owner's daily and the claimed one");
        assertEquals(2, twelfth.abandoned());
        assertEquals(2, twelfth.drafting());
        assertEquals(twelfth.started(), twelfth.finished() + twelfth.abandoned() + twelfth.drafting(), "statuses partition the starts");
        assertEquals(3, twelfth.guestStarted(), "g1, g2 and the owner's claimed guest game");
        assertEquals(2, twelfth.guestFinished());
        assertEquals(1, twelfth.unknownStarted(), "a null started_as_guest is its own count, not signed-in");
        assertEquals(3, twelfth.dailyStarted());
        assertEquals(2, twelfth.dailyFinished());
        // CR-01: the owner counts are signed-in games only, so the claimed guest game is NOT among them
        assertEquals(4, twelfth.ownerStarted());
        assertEquals(2, twelfth.ownerFinished());
        assertEquals(1, twelfth.ownerAbandoned());
        assertEquals(1, twelfth.ownerDrafting());
        assertEquals(1, twelfth.ownerDailyStarted());
        assertEquals(1, twelfth.ownerDailyFinished());
        // guest, unknown, owner and the one other signed-in member (m2) partition the starts: none counted twice
        assertEquals(twelfth.started(), twelfth.guestStarted() + twelfth.unknownStarted() + twelfth.ownerStarted() + 1);
        assertEquals(twelfth.ownerStarted(),
                twelfth.ownerFinished() + twelfth.ownerAbandoned() + twelfth.ownerDrafting());

        UsageRepository.FunnelRow football = rows.get(2);
        assertEquals("NFL", football.sport());
        assertEquals(1, football.started());
        assertEquals(1, football.finished());
        assertEquals(0, football.ownerStarted());
    }

    @Test
    void anOwnerClaimedGuestGameIsAGuestGameAndNotAnOwnerGame() {
        game(OWNER, "1990-03-12T20:00:00Z", "COMPLETE", null, true);
        UsageRepository.FunnelRow r = usage.funnel(FROM, TO, OWNER).get(0);
        assertEquals(1, r.started());
        assertEquals(1, r.guestStarted());
        assertEquals(1, r.guestFinished());
        assertEquals(0, r.ownerStarted(), "guestStarted and ownerStarted must not both count one game");
        assertEquals(0, r.ownerFinished());
        // and an owner row written before the column existed is unknown, not owner
        game(OWNER, "1990-03-12T21:00:00Z", "COMPLETE", null, null);
        UsageRepository.FunnelRow both = usage.funnel(FROM, TO, OWNER).get(0);
        assertEquals(1, both.unknownStarted());
        assertEquals(0, both.ownerStarted());
    }

    @Test
    void ownerCountsAreZeroWhenNoOwnerIsConfigured() {
        seedWorld();
        for (String none : new String[]{null, "", "   "}) {
            for (var r : usage.funnel(FROM, TO, none)) {
                assertEquals(0, r.ownerStarted(), "owner id: '" + none + "'");
                assertEquals(0, r.ownerFinished());
                assertEquals(0, r.ownerAbandoned() + r.ownerDrafting() + r.ownerDailyStarted() + r.ownerDailyFinished());
            }
        }
        assertEquals(9, usage.funnel(FROM, TO, null).get(1).started(), "the rest of the row is unchanged");
    }

    @Test
    void theRangeFiltersByEasternStartDay() {
        seedWorld();
        List<UsageRepository.FunnelRow> onlyTwelfth = usage.funnel(LocalDate.of(1990, 3, 12), LocalDate.of(1990, 3, 12), OWNER);
        assertEquals(2, onlyTwelfth.size(), "NBA and NFL");
        assertEquals(9, onlyTwelfth.get(0).started());
        assertTrue(usage.funnel(LocalDate.of(1990, 3, 13), LocalDate.of(1990, 3, 20), OWNER).isEmpty());
    }

    @Test
    void theReportCarriesTheFunnelWithLowercaseSportAndTheGamesSince() {
        seedWorld();
        UsageReport r = service.report(FROM, TO);
        assertEquals(3, r.funnel().size());
        assertEquals("nba", r.funnel().get(1).sport());
        assertEquals("nfl", r.funnel().get(2).sport());
        assertEquals("1990-03-12", r.funnel().get(1).day());
        assertEquals(9, r.funnel().get(1).started());
        assertEquals("1990-03-11", r.gamesSince(), "the oldest game is the 04:30Z one, on its Eastern day");
        assertEquals(0, r.funnel().get(1).ownerStarted(), "no owner is configured in the test profile");
    }
}
