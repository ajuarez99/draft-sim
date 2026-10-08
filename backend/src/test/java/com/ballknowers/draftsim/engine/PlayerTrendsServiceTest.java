package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.PlayerTrendsProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.PlayerTrendsService.Grid;
import com.ballknowers.draftsim.engine.PlayerTrendsService.Input;
import com.ballknowers.draftsim.engine.PlayerTrendsService.PlayerInfo;
import com.ballknowers.draftsim.engine.PlayerTrendsService.PlayerTrends;
import com.ballknowers.draftsim.engine.PlayerTrendsService.SeasonData;
import com.ballknowers.draftsim.engine.PlayerTrendsService.TrendRow;
import com.ballknowers.draftsim.engine.RosterOwners.RosterOwner;
import com.ballknowers.draftsim.store.PlayerAbsenceRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonGame;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;
import com.ballknowers.draftsim.store.RosterSeasonRepository.Rostered;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository.WeekBreakdown;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/019-minutes-streaming T009: the pure core, one test per case of tasks.md. Pure: no database.
 */
class PlayerTrendsServiceTest {

    private static final PlayerTrendsProperties PROPS = new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 20, 0.9);
    private static final LocalDate BASE = LocalDate.of(2025, 10, 22);
    private static final OffsetDateTime FETCHED = OffsetDateTime.of(2026, 10, 11, 2, 0, 0, 0, ZoneOffset.UTC);

    // ------------------------------------------------------------------ builders

    /** A season of synthetic games. Every game gets a team row for both sides (default box-score totals). */
    static final class S {
        final int season;
        final LocalDate base;
        final List<SeasonGame> games = new ArrayList<>();
        final List<TeamGame> teams = new ArrayList<>();
        final List<PlayerAbsenceRepository.Row> absences = new ArrayList<>();
        final Set<String> teamRowGames = new HashSet<>();

        S(int season, LocalDate base) {
            this.season = season;
            this.base = base;
        }

        static Map<String, Object> teamStats() {
            Map<String, Object> m = new HashMap<>();
            m.put("sp", 14400);
            m.put("fga", 90);
            m.put("fta", 20);
            m.put("to", 13);
            return m;
        }

        /** Team rows for both sides of a game (once). */
        S game(String gid, int day, String team, String opp) {
            return game(gid, day, team, opp, teamStats(), teamStats());
        }

        S game(String gid, int day, String team, String opp, Map<String, Object> tStats, Map<String, Object> oStats) {
            if (teamRowGames.add(gid)) {
                LocalDate d = base.plusDays(day);
                teams.add(new TeamGame(team, gid, d, opp, tStats));
                teams.add(new TeamGame(opp, gid, d, team, oStats));
            }
            return this;
        }

        static String gid(String team, int day) {
            return team + "-" + day;
        }

        /** One player-game, with team rows created for it. */
        S line(String pid, String team, int day, double min, double pts) {
            Map<String, Object> st = new HashMap<>();
            st.put("sp", (int) Math.round(min * 60));
            st.put("pts", pts);
            return raw(pid, team, "OPP", day, st, true);
        }

        S raw(String pid, String team, String opp, int day, Map<String, Object> stats, boolean withTeamRows) {
            String gid = gid(team, day);
            if (withTeamRows) game(gid, day, team, opp);
            games.add(new SeasonGame(pid, gid, base.plusDays(day), opp, stats, 1, true));
            return this;
        }

        /** {@code count} games at a flat minutes and points from {@code startDay}. */
        S run(String pid, String team, int startDay, int count, double min, double pts) {
            for (int i = 0; i < count; i++) line(pid, team, startDay + i, min, pts);
            return this;
        }

        SeasonData data() {
            return new SeasonData(season, games, teams, absences);
        }
    }

    static final class B {
        String status = "in_season";
        Integer week = 1;
        PlayerTrendsProperties props = PROPS;
        S current = new S(2026, BASE.plusYears(1));
        S previous = new S(2025, BASE);
        Map<String, PlayerInfo> players = new HashMap<>();
        Grid grid = null;
        Optional<Rostered> rostered = Optional.of(new Rostered(new HashMap<>(), FETCHED));
        Map<Integer, RosterOwner> owners = new HashMap<>();
        Double oneGameShare = null;

        B info(String pid, String team) {
            players.put(pid, new PlayerInfo("Player " + pid, List.of("PG"), team));
            return this;
        }

        B rostered(String pid, int rosterId) {
            Map<String, Integer> m = new HashMap<>(rostered.map(Rostered::byPlayer).orElse(Map.of()));
            m.put(pid, rosterId);
            rostered = Optional.of(new Rostered(m, FETCHED));
            return this;
        }

        PlayerTrends run() {
            return PlayerTrendsService.compute(new Input("nba", 2026, status, week, props, Map.of("pts", 1.0),
                    current.data(), previous.data(), players, grid, rostered, owners, oneGameShare, 2025));
        }
    }

    private static List<String> ids(List<TrendRow> rows) {
        return rows.stream().map(TrendRow::sleeperPlayerId).toList();
    }

    private static TrendRow row(List<TrendRow> rows, String id) {
        return rows.stream().filter(r -> r.sleeperPlayerId().equals(id)).findFirst().orElseThrow();
    }

    /** A B whose previous season is the data under test (the new season has no games). */
    private static B prev() {
        return new B();
    }

    // ------------------------------------------------------------------ minutes role

    @Test
    void aRecentJumpInMinutesIsARiser() {
        B b = prev().info("p1", "AAA");
        // 10 games at 18, then 3 at 32. data-model: seasonMin is the mean of ALL games (21.23), so the
        // delta is 32 - 21.23 = +10.77 (tasks.md's "+14" is recent minus the OLD level, not the spec'd delta).
        b.previous.run("p1", "AAA", 0, 10, 18, 10).run("p1", "AAA", 10, 3, 32, 20);
        PlayerTrends t = b.run();

        assertTrue(t.available());
        TrendRow r = row(t.risers(), "p1");
        assertEquals("RISER", r.role());
        assertEquals(32.0, r.recentMin(), 1e-9);
        assertEquals(21.23, r.seasonMin(), 0.01);
        assertEquals(10.77, r.minDelta(), 0.01);
        assertEquals(13, r.games());
        assertEquals(1, t.risersTotal());
    }

    @Test
    void oneShortGameDoesNotMakeAFallerBecauseRecentIsAMedian() {
        B b = prev().info("p1", "AAA");
        b.previous.run("p1", "AAA", 0, 7, 30, 10).line("p1", "AAA", 7, 30, 10).line("p1", "AAA", 8, 30, 10)
                .line("p1", "AAA", 9, 8, 10);
        PlayerTrends t = b.run();
        assertTrue(t.fallers().isEmpty());
        assertTrue(t.risers().isEmpty());
        // the last three are 30, 30, 8: the median is 30 (a mean would be 22.7), and 30 vs the season's 27.8 is STEADY
        TrendRow r = row(t.streaming(), "p1");
        assertEquals(30.0, r.recentMin(), 1e-9);
        assertEquals("STEADY", r.role());
    }

    // ------------------------------------------------------------------ TEAM_* and All-Star

    @Test
    void teamTotalRowsAndAllStarGamesNeverCount() {
        B b = prev().info("p1", "AAA");
        b.previous.run("p1", "AAA", 0, 6, 25, 10);
        // a TEAM_ id that leaked into the player rows, and an All-Star game (opponent is not a team code)
        Map<String, Object> st = new HashMap<>();
        st.put("sp", 14400);
        st.put("pts", 120);
        b.previous.games.add(new SeasonGame("TEAM_AAA", "AAA-0", BASE, "OPP", st, 1, true));
        b.previous.raw("p1", "STP", "STR", 7, Map.of("sp", 3000, "pts", 50), false);
        b.previous.game("ALLSTAR-1", 7, "", "");   // the bare TEAM_ row of the All-Star game (empty suffix)
        PlayerTrends t = b.run();

        // the All-Star game is not one of p1's games (6, not 7); no TEAM_ id anywhere; nobody teamless
        assertEquals(0, t.excludedNoTeam());
        List<TrendRow> all = new ArrayList<>();
        all.addAll(t.risers());
        all.addAll(t.fallers());
        all.addAll(t.streaming());
        for (TrendRow r : all) assertFalse(r.sleeperPlayerId().startsWith("TEAM_"));
        TrendRow p1 = row(t.streaming(), "p1");
        assertEquals(6, p1.games());
        assertEquals(10.0, p1.seasonPts(), 1e-9, "the 50-point All-Star game did not enter the mean");
    }

    // ------------------------------------------------------------------ usage

    @Test
    void usageIsPooledOverTheWindowNotAveragedPerGame() {
        PlayerTrendsProperties p = new PlayerTrendsProperties(6, 3, 5, 3, 14, 10, 20, 0.9);
        B b = prev().info("p1", "AAA");
        b.props = p;
        // game 1: regulation team row. game 2: overtime team row (265 min). game 3: no team row at all.
        Map<String, Object> reg = S.teamStats();
        Map<String, Object> ot = new HashMap<>(S.teamStats());
        ot.put("sp", 15900);
        ot.put("fga", 100);
        ot.put("fta", 30);
        ot.put("to", 10);
        b.previous.game("AAA-0", 0, "AAA", "OPP", reg, S.teamStats());
        b.previous.game("AAA-1", 1, "AAA", "OPP", ot, S.teamStats());
        b.previous.raw("p1", "AAA", "OPP", 0, stats(1800, 10, 5, 2), false);
        b.previous.raw("p1", "AAA", "OPP", 1, stats(2400, 20, 10, 4), false);
        b.previous.raw("p1", "AAA", "OPP", 2, stats(1200, 30, 10, 3), false);   // AAA-2: no team row
        PlayerTrends t = b.run();

        double num = (10 + 0.44 * 5 + 2) * (240.0 / 5) + (20 + 0.44 * 10 + 4) * (265.0 / 5);
        double den = 30 * (90 + 0.44 * 20 + 13) + 40 * (100 + 0.44 * 30 + 10);
        double expected = 100 * num / den;
        TrendRow r = row(t.streaming(), "p1");
        assertEquals(expected, r.recentUsg(), 0.01);
        assertEquals(expected, r.seasonUsg(), 0.01, "all three games are in both windows; the team-row-less one is skipped");

        // per-game averaging would give a different number, which is why it is pooled
        double g1 = 100 * ((10 + 0.44 * 5 + 2) * 48.0) / (30 * (90 + 0.44 * 20 + 13));
        double g2 = 100 * ((20 + 0.44 * 10 + 4) * 53.0) / (40 * (100 + 0.44 * 30 + 10));
        assertNotEquals(expected, (g1 + g2) / 2, 0.001);
    }

    private static Map<String, Object> stats(int sp, int fga, int fta, int to) {
        Map<String, Object> m = new HashMap<>();
        m.put("sp", sp);
        m.put("fga", fga);
        m.put("fta", fta);
        m.put("to", to);
        m.put("pts", 20);
        return m;
    }

    @Test
    void aMissingStatKeyCountsAsZero() {
        PlayerTrendsProperties p = new PlayerTrendsProperties(6, 3, 5, 3, 14, 10, 20, 0.9);
        B b = prev().info("p1", "AAA");
        b.props = p;
        for (int d = 0; d < 3; d++) {
            Map<String, Object> m = new HashMap<>();
            m.put("sp", 1800);
            m.put("fta", 5);          // no fga, no to
            m.put("pts", 10);
            b.previous.raw("p1", "AAA", "OPP", d, m, true);
        }
        PlayerTrends t = b.run();
        double num = 3 * (0.44 * 5) * (240.0 / 5);
        double den = 3 * 30 * (90 + 0.44 * 20 + 13);
        assertEquals(100 * num / den, row(t.streaming(), "p1").recentUsg(), 0.01);
    }

    // ------------------------------------------------------------------ eligibility

    @Test
    void aStalePlayerIsExcludedAndCounted() {
        B b = prev().info("fresh", "AAA").info("old", "BBB");
        b.previous.run("fresh", "AAA", 0, 6, 25, 10).run("fresh", "AAA", 60, 1, 25, 10);
        b.previous.run("old", "BBB", 0, 6, 25, 10);    // last game 60 days before the season's last game
        PlayerTrends t = b.run();
        assertEquals(1, t.excludedStale());
        assertFalse(ids(t.streaming()).contains("old"));
        assertTrue(ids(t.streaming()).contains("fresh"));
    }

    @Test
    void aPlayerWithNoTeamIsExcludedAndCounted() {
        B b = prev().info("p1", "AAA").info("free", null);
        b.previous.run("p1", "AAA", 0, 6, 25, 10).run("free", "AAA", 0, 6, 25, 10);
        PlayerTrends t = b.run();
        assertEquals(1, t.excludedNoTeam());
        assertEquals(0, t.excludedStale());
        assertEquals(List.of("p1"), ids(t.streaming()));
    }

    // ------------------------------------------------------------------ which season each list reads

    private static B newSeasonWith(int[] gamesPerTeam) {
        B b = prev().info("p1", "AAA");
        // last season: a full run of one player, with a team row set so that season exists
        b.previous.run("p1", "AAA", 0, 10, 25, 10);
        String[] codes = {"AAA", "BBB", "CCC", "DDD"};
        for (int i = 0; i < codes.length; i++) {
            for (int d = 0; d < gamesPerTeam[i]; d++) b.current.game(codes[i] + "-" + d, d, codes[i], "ZZ" + i);
        }
        b.current.raw("p1", "AAA", "ZZ0", 0, Map.of("sp", 1800, "pts", 20), true);
        return b;
    }

    @Test
    void earlyInANewSeasonBothListsReadThePreviousOne() {
        PlayerTrends t = newSeasonWith(new int[]{2, 1, 1, 2}).run();
        assertTrue(t.rolesFallback());
        assertTrue(t.streamingFallback());
        assertEquals(2025, t.rolesSeason());
        assertEquals(2025, t.streamingSeason());
    }

    @Test
    void streamingSwitchesBeforeRoles() {
        // two of the four teams (half) have 3 games, none has 5
        PlayerTrends t = newSeasonWith(new int[]{3, 4, 1, 1}).run();
        assertFalse(t.streamingFallback());
        assertEquals(2026, t.streamingSeason());
        assertTrue(t.rolesFallback());
        assertEquals(2025, t.rolesSeason());
    }

    @Test
    void rolesSwitchOnceHalfTheTeamsHaveFiveGames() {
        PlayerTrends t = newSeasonWith(new int[]{5, 6, 1, 1}).run();
        assertFalse(t.rolesFallback());
        assertEquals(2026, t.rolesSeason());
    }

    // ------------------------------------------------------------------ streaming

    @Test
    void streamingRanksBySeasonMeanNotRecentForm() {
        B b = prev().info("hot", "AAA").info("steady", "BBB");
        // hot: 20 over the season but 40 over the last 5. steady: 30 all season, 25 over the last 5.
        b.previous.run("hot", "AAA", 0, 10, 28, 10).run("hot", "AAA", 10, 5, 28, 40);
        b.previous.run("steady", "BBB", 0, 10, 28, 33).run("steady", "BBB", 10, 5, 28, 25);
        PlayerTrends t = b.run();
        TrendRow hot = row(t.streaming(), "hot");
        TrendRow steady = row(t.streaming(), "steady");
        assertTrue(hot.formPts() > steady.formPts(), "the form is higher...");
        assertTrue(hot.seasonPts() < steady.seasonPts(), "...but the season mean is lower");
        assertEquals(List.of("steady", "hot"), ids(t.streaming()), "...and the season mean is the sort");
    }

    @Test
    void streamingExcludesRosteredPlayersAndEveryRowIsFalse() {
        B b = prev().info("free", "AAA").info("taken", "BBB");
        b.previous.run("free", "AAA", 0, 6, 25, 10).run("taken", "BBB", 0, 6, 25, 12);
        b.rostered("taken", 3);
        b.owners.put(3, new RosterOwner("Dunk Tank", null, false));
        PlayerTrends t = b.run();
        assertNull(t.streamingReason());
        assertEquals(List.of("free"), ids(t.streaming()));
        for (TrendRow r : t.streaming()) {
            assertEquals(Boolean.FALSE, r.rostered());
            assertNull(r.rosteredBy());
        }
    }

    @Test
    void rolesRowsCarryTheOwnerWhenOwnershipIsKnown() {
        B b = prev().info("p1", "AAA");
        b.previous.run("p1", "AAA", 0, 10, 18, 10).run("p1", "AAA", 10, 3, 32, 20);
        b.rostered("p1", 3);
        b.owners.put(3, new RosterOwner("Dunk Tank", null, false));
        TrendRow r = row(b.run().risers(), "p1");
        assertEquals(Boolean.TRUE, r.rostered());
        assertEquals("Dunk Tank", r.rosteredBy());
    }

    @Test
    void completeSeasonIsSeasonCompleteAndNothingIsSplit() {
        B b = streamingFixture();
        b.status = "complete";
        assertReasonWithNoOwnership(b.run(), "SEASON_COMPLETE");
    }

    @Test
    void preDraftIsNotDraftedEvenWithANonEmptyRosteredMap() {
        B b = streamingFixture();
        b.status = "pre_draft";
        b.rostered("someone", 1);
        assertReasonWithNoOwnership(b.run(), "NOT_DRAFTED");
        b.status = "drafting";
        assertReasonWithNoOwnership(b.run(), "NOT_DRAFTED");
    }

    @Test
    void inSeasonWithNoRostersFetchedIsRostersNotLoaded() {
        B b = streamingFixture();
        b.rostered = Optional.empty();
        assertReasonWithNoOwnership(b.run(), "ROSTERS_NOT_LOADED");
    }

    @Test
    void inSeasonWithFetchedButEmptyRostersIsAvailable() {
        B b = streamingFixture();
        b.rostered = Optional.of(new Rostered(new HashMap<>(), FETCHED));
        PlayerTrends t = b.run();
        assertNull(t.streamingReason());
        assertFalse(t.streaming().isEmpty());
        assertEquals(FETCHED, t.rostersFetchedAt());
    }

    private static B streamingFixture() {
        B b = prev().info("p1", "AAA").info("p2", "BBB");
        b.previous.run("p1", "AAA", 0, 10, 18, 10).run("p1", "AAA", 10, 3, 32, 20);
        b.previous.run("p2", "BBB", 0, 10, 30, 10);
        return b;
    }

    private static void assertReasonWithNoOwnership(PlayerTrends t, String reason) {
        assertEquals(reason, t.streamingReason());
        assertTrue(t.streaming().isEmpty());
        assertFalse(t.risers().isEmpty(), "the role lists still show");
        for (TrendRow r : t.risers()) {
            assertNull(r.rostered(), "ownership is unknown, so rostered is null and not false (F4)");
            assertNull(r.rosteredBy());
        }
        for (TrendRow r : t.fallers()) assertNull(r.rostered());
    }

    // ------------------------------------------------------------------ list sizes, totals, ties

    @Test
    void listsAreCappedTotalsAreNotAndTiesGoToHigherRecentMinutesThenId() {
        PlayerTrendsProperties p = new PlayerTrendsProperties(6, 5, 5, 3, 14, 2, 20, 0.9);
        B b = prev();
        b.props = p;
        // a: +7.69 at 30 recent. b and c: also +7.69 (same shape, shifted) at 40 recent. All three tie on delta.
        b.info("a", "AAA").info("b", "BBB").info("c", "CCC");
        b.previous.run("a", "AAA", 0, 10, 20, 10).run("a", "AAA", 10, 3, 30, 10);
        b.previous.run("b", "BBB", 0, 10, 30, 10).run("b", "BBB", 10, 3, 40, 10);
        b.previous.run("c", "CCC", 0, 10, 30, 10).run("c", "CCC", 10, 3, 40, 10);
        PlayerTrends t = b.run();
        assertEquals(3, t.risersTotal());
        assertEquals(2, t.risers().size());
        assertEquals(List.of("b", "c"), ids(t.risers()), "higher recentMin first, then id");
        assertEquals(0, t.fallersTotal());
    }

    @Test
    void fallersSortMostNegativeFirstAndAreOnlyFallers() {
        B b = prev().info("f", "AAA").info("g", "BBB");
        b.previous.run("f", "AAA", 0, 10, 34, 10).run("f", "AAA", 10, 3, 20, 10);
        b.previous.run("g", "BBB", 0, 10, 34, 10).run("g", "BBB", 10, 3, 10, 10);
        PlayerTrends t = b.run();
        assertEquals(List.of("g", "f"), ids(t.fallers()));
        assertTrue(t.risers().isEmpty());
        for (TrendRow r : t.fallers()) assertEquals("FALLER", r.role());
    }

    @Test
    void streamingIsCappedAtItsOwnSize() {
        PlayerTrendsProperties p = new PlayerTrendsProperties(6, 5, 5, 3, 14, 10, 2, 0.9);
        B b = prev();
        b.props = p;
        for (int i = 0; i < 4; i++) {
            b.info("p" + i, "AAA");
            b.previous.run("p" + i, "AAA", 0, 6, 25, 10 + i);
        }
        assertEquals(List.of("p3", "p2"), ids(b.run().streaming()));
    }

    // ------------------------------------------------------------------ missed team games

    @Test
    void missedTeamGamesCountsTheMostRecentConsecutiveAbsences() {
        B b = prev().info("p1", "AAA");
        b.previous.run("p1", "AAA", 0, 6, 25, 10);
        for (int d = 6; d < 9; d++) b.previous.game(S.gid("AAA", d), d, "AAA", "OPP");
        // absent from the last two team games (days 7 and 8), and from day 4 (older, not consecutive)
        b.previous.absences.add(absence("p1", S.gid("AAA", 7), 7));
        b.previous.absences.add(absence("p1", S.gid("AAA", 8), 8));
        PlayerTrends t = b.run();
        assertEquals(2, row(t.streaming(), "p1").missedTeamGames());
    }

    @Test
    void aPlayerWhoPlayedTheLastTeamGameHasMissedZero() {
        B b = prev().info("p1", "AAA");
        b.previous.run("p1", "AAA", 0, 6, 25, 10);
        b.previous.absences.add(absence("p1", S.gid("AAA", 3), 3));   // an older miss, then he played again
        assertEquals(0, row(b.run().streaming(), "p1").missedTeamGames());
    }

    /** The real basketball shape (B1): a per-game miss is ENTRY_WITHOUT_PLAY with a game id. */
    private static PlayerAbsenceRepository.Row absence(String pid, String gid, int day) {
        return new PlayerAbsenceRepository.Row(Sport.NBA, 2025, 1, pid, gid, BASE.plusDays(day), "AAA",
                "ENTRY_WITHOUT_PLAY");
    }

    @Test
    void aWeekLevelAbsenceRowWithNoGameIdIsIgnored() {
        B b = prev().info("p1", "AAA");
        b.previous.run("p1", "AAA", 0, 6, 25, 10);
        b.previous.game(S.gid("AAA", 6), 6, "AAA", "OPP");
        b.previous.absences.add(new PlayerAbsenceRepository.Row(Sport.NBA, 2025, 1, "p1", null, null, "AAA",
                "TEAM_PLAYED_NO_ENTRY"));
        assertEquals(0, row(b.run().streaming(), "p1").missedTeamGames());
    }

    @Test
    void aGameLevelTeamPlayedNoEntryRowAlsoCounts() {
        B b = prev().info("p1", "AAA");
        b.previous.run("p1", "AAA", 0, 6, 25, 10);
        b.previous.game(S.gid("AAA", 6), 6, "AAA", "OPP");
        b.previous.absences.add(new PlayerAbsenceRepository.Row(Sport.NBA, 2025, 1, "p1", S.gid("AAA", 6),
                BASE.plusDays(6), "AAA", "TEAM_PLAYED_NO_ENTRY"));
        assertEquals(1, row(b.run().streaming(), "p1").missedTeamGames());
    }

    // ------------------------------------------------------------------ code-review fixes

    @Test
    void b2WhenOwnershipIsKnownEachGroupGetsItsOwnTopN() {
        PlayerTrendsProperties p = new PlayerTrendsProperties(6, 5, 5, 3, 14, 2, 20, 0.9);
        B b = prev();
        b.props = p;
        // four free-agent risers with a big jump, two rostered risers with a smaller one
        for (int i = 0; i < 4; i++) {
            String id = "fa" + i;
            b.info(id, "AAA");
            b.previous.run(id, "AAA", 0, 10, 20, 10).run(id, "AAA", 10, 3, 40 - i, 10);
        }
        for (int i = 0; i < 2; i++) {
            String id = "ro" + i;
            b.info(id, "BBB").rostered(id, 1);
            b.previous.run(id, "BBB", 0, 10, 20, 10).run(id, "BBB", 10, 3, 31 - i, 10);
        }
        PlayerTrends t = b.run();
        assertEquals(6, t.risersTotal());
        assertEquals(4, t.risersFreeAgentTotal());
        assertEquals(2, t.risersRosteredTotal());
        assertEquals(4, t.risers().size(), "top 2 free agents + top 2 rostered");
        assertEquals(List.of("fa0", "fa1", "ro0", "ro1"), ids(t.risers()));
        assertEquals(0, t.fallersFreeAgentTotal() + t.fallersRosteredTotal());
    }

    @Test
    void b2WhenOwnershipIsUnknownTheCapIsGlobalAndGroupTotalsAreZero() {
        PlayerTrendsProperties p = new PlayerTrendsProperties(6, 5, 5, 3, 14, 2, 20, 0.9);
        B b = prev();
        b.props = p;
        b.status = "complete";
        for (int i = 0; i < 3; i++) {
            b.info("p" + i, "AAA");
            b.previous.run("p" + i, "AAA", 0, 10, 20, 10).run("p" + i, "AAA", 10, 3, 34 - i, 10);
        }
        PlayerTrends t = b.run();
        assertEquals(2, t.risers().size());
        assertEquals(3, t.risersTotal());
        assertEquals(0, t.risersFreeAgentTotal() + t.risersRosteredTotal());
    }

    @Test
    void b3ExclusionCountsOnlyPlayersWhoWouldOtherwiseBeListed() {
        B b = prev().info("riser", "AAA").info("steadyStale", "BBB").info("riserStale", "CCC")
                .info("steadyNoTeam", null).info("riserNoTeam", null);
        b.rostered("steadyStale", 1);   // rostered + steady: could never be listed
        b.rostered("steadyNoTeam", 1);
        b.previous.run("riser", "AAA", 0, 10, 18, 10).run("riser", "AAA", 10, 3, 32, 10);
        b.previous.run("steadyStale", "BBB", 0, 6, 25, 10);
        b.previous.run("riserStale", "CCC", 0, 10, 18, 10).run("riserStale", "CCC", 10, 3, 32, 10);
        b.previous.run("steadyNoTeam", "AAA", 0, 6, 25, 10);
        b.previous.run("riserNoTeam", "AAA", 0, 10, 18, 10).run("riserNoTeam", "AAA", 10, 3, 32, 10);
        b.previous.run("riser", "AAA", 80, 1, 25, 10);   // pushes the season's last game 80 days out
        PlayerTrends t = b.run();
        assertEquals(1, t.excludedStale(), "only riserStale: a rostered steady player could never be listed");
        assertEquals(1, t.excludedNoTeam(), "only riserNoTeam");
    }

    @Test
    void b3TheStaleReferenceDateIsTheDataSeasonsLastGame() {
        B b = prev().info("p1", "AAA");
        b.previous.run("p1", "AAA", 0, 6, 25, 10);
        assertEquals(BASE.plusDays(5), b.run().staleReferenceDate());
    }

    @Test
    void b4ACompletedSeasonShowsTheTeamHeWasOnAndKeepsTeamlessPlayers() {
        B b = prev().info("moved", "NEW").info("retired", null);
        b.status = "complete";
        b.current.run("moved", "AAA", 0, 10, 18, 10).run("moved", "AAA", 10, 3, 32, 10);
        b.current.run("retired", "BBB", 0, 10, 18, 10).run("retired", "BBB", 10, 3, 32, 10);
        PlayerTrends t = b.run();
        assertEquals("AAA", row(t.risers(), "moved").team(), "the team of that season, not Sleeper's current one");
        assertEquals("BBB", row(t.risers(), "retired").team(), "a teamless player stays in a completed season");
        assertEquals(0, t.excludedNoTeam());
    }

    @Test
    void b4ALiveSeasonKeepsTheCurrentTeamAndTheNoTeamExclusion() {
        B b = prev().info("moved", "NEW").info("free", null);
        b.previous.run("moved", "AAA", 0, 10, 18, 10).run("moved", "AAA", 10, 3, 32, 10);
        b.previous.run("free", "BBB", 0, 10, 18, 10).run("free", "BBB", 10, 3, 32, 10);
        PlayerTrends t = b.run();
        assertEquals("NEW", row(t.risers(), "moved").team());
        assertFalse(ids(t.risers()).contains("free"));
        assertEquals(1, t.excludedNoTeam());
    }

    @Test
    void b7ANullStatusWithFetchedButEmptyRostersIsNotDrafted() {
        B b = streamingFixture();
        b.status = null;
        b.rostered = Optional.of(new Rostered(new HashMap<>(), FETCHED));
        assertReasonWithNoOwnership(b.run(), "NOT_DRAFTED");
    }

    // ------------------------------------------------------------------ games this/next week

    @Test
    void gamesComeFromTheGridColumnForThatWeekNumberNotThePosition() {
        B b = prev().info("p1", "AAA");
        b.previous.run("p1", "AAA", 0, 6, 25, 10);
        // week numbers 3, 4, 5: position 1 is week 4
        b.grid = new Grid(List.of(3, 4, 5), Map.of("AAA", new int[]{2, 4, 3}));
        b.week = 4;
        TrendRow r = row(b.run().streaming(), "p1");
        assertEquals(4, r.gamesThisWeek());
        assertEquals(3, r.gamesNextWeek());
    }

    @Test
    void aWeekOutsideTheGridOrATeamNotInItIsNull() {
        B b = prev().info("p1", "AAA").info("p2", "BBB");
        b.previous.run("p1", "AAA", 0, 6, 25, 10).run("p2", "BBB", 0, 6, 25, 10);
        b.grid = new Grid(List.of(3, 4), Map.of("AAA", new int[]{2, 4}));
        b.week = 4;     // next week 5 is not in the grid
        PlayerTrends t = b.run();
        assertEquals(4, row(t.streaming(), "p1").gamesThisWeek());
        assertNull(row(t.streaming(), "p1").gamesNextWeek());
        assertNull(row(t.streaming(), "p2").gamesThisWeek(), "BBB has no column in the grid");
    }

    @Test
    void aCompleteSeasonHasNoGamesThisOrNextWeek() {
        B b = prev().info("p1", "AAA");
        b.previous.run("p1", "AAA", 0, 6, 25, 10).run("p1", "AAA", 6, 3, 40, 10);
        b.grid = new Grid(List.of(3, 4, 5), Map.of("AAA", new int[]{2, 4, 3}));
        b.week = 4;
        b.status = "complete";
        PlayerTrends t = b.run();
        TrendRow r = row(t.risers(), "p1");
        assertNull(r.gamesThisWeek());
        assertNull(r.gamesNextWeek());
    }

    @Test
    void noGridMeansNullGames() {
        B b = prev().info("p1", "AAA");
        b.previous.run("p1", "AAA", 0, 6, 25, 10);
        assertNull(row(b.run().streaming(), "p1").gamesThisWeek());
    }

    // ------------------------------------------------------------------ one-game credit

    @Test
    void oneGameCreditAppearsAtTheShareThresholdAndNotBelow() {
        B b = streamingFixture();
        b.oneGameShare = 0.994;
        PlayerTrendsService.OneGameCredit c = b.run().oneGameCredit();
        assertEquals("ONE_GAME_CREDITED", c.code());
        assertEquals(0.99, c.share(), 1e-9);
        assertEquals(2025, c.seasonMeasured());

        b.oneGameShare = 0.9;
        assertNotNull(b.run().oneGameCredit(), "at the threshold counts");
        b.oneGameShare = 0.89;
        assertNull(b.run().oneGameCredit());
        b.oneGameShare = null;
        assertNull(b.run().oneGameCredit());
    }

    @Test
    void oneGameShareIsTheShareOfMultiGameStarterWeeksCreditedAsExactlyOneGame() {
        Map<String, Double> scoring = Map.of("pts", 1.0);
        List<SeasonGame> games = new ArrayList<>();
        // p1 week 1: games of 20 and 30. p2 week 1: 10 and 15. p3 week 1: a single game of 40.
        games.add(g("p1", "g1", 1, 20));
        games.add(g("p1", "g2", 1, 30));
        games.add(g("p2", "g3", 1, 10));
        games.add(g("p2", "g4", 1, 15));
        games.add(g("p3", "g5", 1, 40));
        // roster 1 credits p1 with 30 (the best single game) and p3 with 40; roster 2 credits p2 with the SUM, 25
        List<WeekBreakdown> weeks = List.of(
                new WeekBreakdown(1, 1, 70, "{\"p1\": 30.0, \"p3\": 40.0, \"bench\": 5.0}", "[\"p1\",\"p3\",\"0\"]"),
                new WeekBreakdown(1, 2, 25, "{\"p2\": 25.0}", "[\"p2\"]"));
        Double share = PlayerTrendsService.oneGameShare(weeks, games, scoring);
        assertEquals(0.5, share, 1e-9, "p1 credited one game, p2 the sum; p3 is single-game and not counted");

        assertNull(PlayerTrendsService.oneGameShare(List.of(), games, scoring));
        assertNull(PlayerTrendsService.oneGameShare(
                List.of(new WeekBreakdown(1, 1, 40, "{\"p3\": 40.0}", "[\"p3\"]")), games, scoring));
    }

    private static SeasonGame g(String pid, String gid, int week, double pts) {
        return new SeasonGame(pid, gid, BASE.plusDays(week), "OPP", Map.of("sp", 1800, "pts", pts), week, true);
    }
}
