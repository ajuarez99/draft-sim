package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.PlayerStatsProperties;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.AdvancedStats.WindowKind;
import com.ballknowers.draftsim.engine.LeagueSeasonResolver.Resolved;
import com.ballknowers.draftsim.engine.LeagueSeasonResolver.Rule;
import com.ballknowers.draftsim.engine.LeagueSeasonResolver.SeasonOption;
import com.ballknowers.draftsim.engine.NbaGameLines.Line;
import com.ballknowers.draftsim.engine.PlayerStatsService.BreakdownRow;
import com.ballknowers.draftsim.engine.PlayerStatsService.Candidate;
import com.ballknowers.draftsim.engine.PlayerStatsService.Computed;
import com.ballknowers.draftsim.engine.PlayerStatsService.Input;
import com.ballknowers.draftsim.engine.PlayerStatsService.PlayerStatsPage;
import com.ballknowers.draftsim.engine.PlayerStatsService.Ranked;
import com.ballknowers.draftsim.engine.PlayerTrendsService.PlayerInfo;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository.SeasonToken;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;
import com.ballknowers.draftsim.store.PlayerAbsenceRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Spec 022 T019: the pure core and the read's gates, on synthetic lines. */
class PlayerStatsServiceTest {

    private static final LocalDate D0 = LocalDate.parse("2025-11-01");
    private static final GameScoringService SCORER = new GameScoringService();
    /** share 0.5, 15 mpg, small sample 100 min, recency 14 days, ... */
    private static final PlayerStatsProperties PROPS = new PlayerStatsProperties(0.5, 15, 100, 14, 5, 15, 10, 10, 5);

    // ------------------------------------------------------------------ builders

    private static Map<String, Object> stats(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    private static Line line(String player, int day, double minutes, Map<String, Object> stats) {
        return new Line("g" + day, D0.plusDays(day), 1, "AAA", "OPP", true, minutes, stats, null, null);
    }

    /** {@code days} games, one per day starting day 1, each worth {@code pts} points and {@code minutes} minutes. */
    private static List<Line> flat(String player, int days, double pts, double minutes) {
        List<Line> l = new ArrayList<>();
        for (int d = 1; d <= days; d++) l.add(line(player, d, minutes, stats("pts", pts)));
        return l;
    }

    private static Map<String, List<TeamGame>> teamGames(int games) {
        List<TeamGame> l = new ArrayList<>();
        for (int d = 1; d <= games; d++) l.add(new TeamGame("AAA", "g" + d, D0.plusDays(d), "OPP", Map.of()));
        return Map.of("AAA", l);
    }

    private static NbaGameLines lines(int teamGames, Map<String, List<Line>> byPlayer) {
        return new NbaGameLines(byPlayer, teamGames(teamGames), java.util.Set.of("AAA", "OPP"));
    }

    private static PlayerInfo info(String name, String position) {
        return new PlayerInfo(name, List.of(position), "AAA");
    }

    private static Map<String, Double> scoring(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return m;
    }

    private static Computed compute(NbaGameLines lines, Map<String, PlayerInfo> infos, Map<String, Double> scoring,
                                    String target) {
        return PlayerStatsService.compute(new Input(PROPS, scoring, lines, infos, target, List.of()), SCORER);
    }

    // ------------------------------------------------------------------ qualification (F9)

    @Test
    void seasonQualificationNeedsHalfTheMostTeamGamesAndTheMinutes() {
        // maxTeamGames 10 -> ceil(0.5 x 10) = 5 games
        Map<String, List<Line>> by = new HashMap<>();
        by.put("five", flat("five", 5, 10, 20));
        by.put("four", flat("four", 4, 10, 20));
        by.put("lowMin", flat("lowMin", 8, 10, 14.9));
        by.put("atMin", flat("atMin", 8, 10, 15));
        NbaGameLines l = lines(10, by);
        Map<String, Double> sc = scoring("pts", 1);
        assertTrue(compute(l, Map.of(), sc, "five").qualification().get(WindowKind.SEASON).qualified());
        assertEquals("NOT_QUALIFIED", compute(l, Map.of(), sc, "four").qualification().get(WindowKind.SEASON).reason());
        assertEquals("NOT_QUALIFIED", compute(l, Map.of(), sc, "lowMin").qualification().get(WindowKind.SEASON).reason());
        assertTrue(compute(l, Map.of(), sc, "atMin").qualification().get(WindowKind.SEASON).qualified());
        // an odd number of team games rounds the requirement up: ceil(0.5 x 9) = 5
        assertFalse(compute(lines(9, by), Map.of(), sc, "four").qualification().get(WindowKind.SEASON).qualified());
    }

    @Test
    void lastNNeedsAllNGamesTheMinutesAndAFreshLastGame() {
        Map<String, List<Line>> by = new HashMap<>();
        by.put("full", flat("full", 30, 10, 20));                      // last game day 30 = the season's latest
        by.put("short", flat("short", 4, 10, 20));                    // only 4 games
        by.put("lowMin", flat("lowMin", 30, 10, 10));
        List<Line> old = flat("old", 6, 10, 20);                      // last game day 6, 24 days before day 30
        by.put("old", old);
        by.put("edge", new ArrayList<>(flat("edge", 16, 10, 20)));    // last game day 16 = exactly 14 days before 30
        NbaGameLines l = lines(30, by);
        Map<String, Double> sc = scoring("pts", 1);
        assertTrue(compute(l, Map.of(), sc, "full").qualification().get(WindowKind.LAST_5).qualified());
        assertTrue(compute(l, Map.of(), sc, "full").qualification().get(WindowKind.LAST_10).qualified());
        assertEquals("NOT_QUALIFIED", compute(l, Map.of(), sc, "short").qualification().get(WindowKind.LAST_5).reason());
        assertEquals("NOT_QUALIFIED", compute(l, Map.of(), sc, "lowMin").qualification().get(WindowKind.LAST_5).reason());
        // F9: a player whose last game was 24 days before the season's latest is stale, not merely unqualified
        PlayerStatsService.Qualification stale = compute(l, Map.of(), sc, "old").qualification().get(WindowKind.LAST_5);
        assertFalse(stale.qualified());
        assertEquals("NOT_QUALIFIED_STALE", stale.reason());
        // 14 days is within recency-days; the boundary is inclusive
        assertTrue(compute(l, Map.of(), sc, "edge").qualification().get(WindowKind.LAST_5).qualified());
        // the games-share rule is for SEASON only: "old" still qualifies for the season window
        assertFalse(compute(l, Map.of(), sc, "old").qualification().get(WindowKind.SEASON).qualified(),
                "6 games is under ceil(0.5 x 30) = 15 for the season, for its own reason");
        assertTrue(compute(l, Map.of(), sc, "full").qualification().get(WindowKind.SEASON).qualified());
    }

    // ------------------------------------------------------------------ ranks (F13, I4, I7)

    @Test
    void rankedOnTheTwoDecimalValueSoVisiblyEqualPlayersShareARank() {
        // A averages 41.2333 and B 41.23: both show 41.23 and so share rank 2 behind C
        Map<String, List<Line>> by = new HashMap<>();
        by.put("A", List.of(line("A", 1, 30, stats("pts", 41.23)), line("A", 2, 30, stats("pts", 41.23)),
                line("A", 3, 30, stats("pts", 41.24))));
        by.put("B", flat("B", 3, 41.23, 30));
        by.put("C", flat("C", 3, 50, 30));
        by.put("D", flat("D", 3, 30, 30));
        Map<String, PlayerInfo> infos = Map.of("A", info("Aaron", "PG"), "B", info("Bo", "PG"),
                "C", info("Cy", "PG"), "D", info("Di", "PG"));
        NbaGameLines l = lines(3, by);
        Map<String, Double> sc = scoring("pts", 1);
        Computed a = compute(l, infos, sc, "A");
        Computed b = compute(l, infos, sc, "B");
        assertEquals(41.23, a.fantasy().fpPerGame().get(WindowKind.SEASON));
        assertEquals(2, a.fantasy().ranks().leagueRank());
        assertEquals(2, b.fantasy().ranks().leagueRank());
        assertEquals(1, compute(l, infos, sc, "C").fantasy().ranks().leagueRank());
        assertEquals(4, compute(l, infos, sc, "D").fantasy().ranks().leagueRank(), "competition rank: 1, 2, 2, 4");
        assertEquals(4, a.fantasy().ranks().groupSize());
    }

    @Test
    void displayOrderWithinATieIsGamesThenNameThenIdAndNeverChangesARank() {
        List<Ranked> r = PlayerStatsService.rank(List.of(
                new Candidate("z9", "Zed", 50, 30.0),
                new Candidate("a1", "Zed", 50, 30.0),          // same name: id decides
                new Candidate("m1", "Amy", 40, 30.0),          // fewer games: after the 50-game pair
                new Candidate("m2", "Ann", 40, 30.0),
                new Candidate("top", "Top", 10, 31.0),
                new Candidate("bottom", null, 82, 29.99)));
        assertEquals(List.of("top", "a1", "z9", "m1", "m2", "bottom"),
                r.stream().map(x -> x.candidate().sleeperPlayerId()).toList());
        assertEquals(List.of(1, 2, 2, 2, 2, 6), r.stream().map(Ranked::rank).toList());
        // deterministic: the same input in another order gives the same output (I4)
        List<Candidate> shuffled = new ArrayList<>(List.of(
                new Candidate("bottom", null, 82, 29.99), new Candidate("m2", "Ann", 40, 30.0),
                new Candidate("top", "Top", 10, 31.0), new Candidate("a1", "Zed", 50, 30.0),
                new Candidate("m1", "Amy", 40, 30.0), new Candidate("z9", "Zed", 50, 30.0)));
        assertEquals(r, PlayerStatsService.rank(shuffled));
    }

    @Test
    void moreFantasyPointsPerGameIsABetterLeagueRank() {
        Map<String, List<Line>> by = new HashMap<>();
        by.put("low", flat("low", 5, 10, 25));
        by.put("mid", flat("mid", 5, 20, 25));
        by.put("high", flat("high", 5, 30, 25));
        NbaGameLines l = lines(5, by);
        Map<String, Double> sc = scoring("pts", 1);
        int low = compute(l, Map.of(), sc, "low").fantasy().ranks().leagueRank();
        int mid = compute(l, Map.of(), sc, "mid").fantasy().ranks().leagueRank();
        int high = compute(l, Map.of(), sc, "high").fantasy().ranks().leagueRank();
        assertTrue(high < mid && mid < low, "a higher figure ranks better");
        assertEquals(1, high);
        // and a scoring that rewards rebounds changes who is best, in the right direction
        Map<String, List<Line>> by2 = new HashMap<>();
        by2.put("scorer", List.of(line("scorer", 1, 30, stats("pts", 30))));
        by2.put("boards", List.of(line("boards", 1, 30, stats("pts", 10, "reb", 15))));
        NbaGameLines l2 = lines(1, by2);
        assertEquals(1, compute(l2, Map.of(), scoring("pts", 1, "reb", 2), "boards").fantasy().ranks().leagueRank());
        assertEquals(1, compute(l2, Map.of(), scoring("pts", 1, "reb", 0.5), "scorer").fantasy().ranks().leagueRank());
    }

    @Test
    void positionRankPointsRankAndRankMove() {
        // fantasy: pts + 2 reb. X: 30 pts (fp 30). Y: 10 pts 15 reb (fp 40). Z: 20 pts 5 reb (fp 30... use 12 reb: 44)
        Map<String, List<Line>> by = new HashMap<>();
        by.put("X", List.of(line("X", 1, 30, stats("pts", 30))));
        by.put("Y", List.of(line("Y", 1, 30, stats("pts", 10, "reb", 15))));
        by.put("Z", List.of(line("Z", 1, 30, stats("pts", 20, "reb", 12))));
        Map<String, PlayerInfo> infos = Map.of("X", info("X", "PG"), "Y", info("Y", "C"), "Z", info("Z", "C"));
        NbaGameLines l = lines(1, by);
        Map<String, Double> sc = scoring("pts", 1, "reb", 2);
        PlayerStatsService.Ranks y = compute(l, infos, sc, "Y").fantasy().ranks();
        assertEquals(3, y.groupSize());
        assertEquals(2, y.leagueRank());            // Z 44, Y 40, X 30
        assertEquals(2, y.positionRank());          // among centres: Z then Y
        assertEquals(2, y.positionGroupSize());
        assertEquals("C", y.position());
        assertEquals(3, y.pointsRank());            // by points: X 30, Z 20, Y 10
        assertEquals(1, y.rankMove());              // pointsRank - leagueRank
        PlayerStatsService.Ranks x = compute(l, infos, sc, "X").fantasy().ranks();
        assertEquals(1, x.positionRank());
        assertEquals(1, x.positionGroupSize());
        assertEquals(1, x.pointsRank());
        assertEquals(-2, x.rankMove());
    }

    @Test
    void anUnqualifiedPlayerHasNoRankNumbersAndSaysWhy() {
        Map<String, List<Line>> by = new HashMap<>();
        by.put("starter", flat("starter", 10, 20, 30));
        by.put("cameo", flat("cameo", 1, 40, 30));
        PlayerStatsService.Ranks r = compute(lines(10, by), Map.of(), scoring("pts", 1), "cameo").fantasy().ranks();
        assertNull(r.leagueRank());
        assertNull(r.positionRank());
        assertNull(r.pointsRank());
        assertNull(r.rankMove());
        assertEquals("NOT_QUALIFIED", r.reason());
        assertEquals(1, r.groupSize(), "the group is the qualified players");
    }

    // ------------------------------------------------------------------ breakdown (I3, N8)

    @Test
    void breakdownSumsToTheTotalKeepsNegativesAndTheDoubleDoubleKeys() {
        Map<String, Double> sc = scoring("pts", 1, "reb", 1.2, "ast", 1.5, "to", -1, "dd", 1, "td", 2,
                "bonus_pt_40p", 2);
        Map<String, List<Line>> by = new HashMap<>();
        by.put("P", List.of(
                line("P", 1, 36, stats("pts", 42, "reb", 11, "ast", 10, "to", 5, "dd", 1, "bonus_pt_40p", 1)),
                line("P", 2, 30, stats("pts", 18, "reb", 4, "ast", 3, "to", 1)),
                line("P", 3, 30, stats("pts", 25, "reb", 12, "ast", 12, "to", 2, "dd", 1, "td", 1))));
        Computed c = compute(lines(3, by), Map.of(), sc, "P");
        double sumPoints = c.fantasy().breakdown().stream().mapToDouble(BreakdownRow::points).sum();
        double sumScores = c.gameLog().stream().mapToDouble(PlayerStatsService.GameLogRow::fantasyPoints).sum();
        assertEquals(sumScores, sumPoints, 0.01 * 3);
        assertEquals(sumScores, c.fantasy().seasonTotal(), 1e-9);
        List<String> keys = c.fantasy().breakdown().stream().map(BreakdownRow::key).toList();
        assertTrue(keys.containsAll(List.of("dd", "td", "bonus_pt_40p", "to")), keys.toString());
        BreakdownRow to = c.fantasy().breakdown().stream().filter(b -> b.key().equals("to")).findFirst().orElseThrow();
        assertEquals(-8.0, to.points(), 1e-9);
        assertTrue(to.share() < 0, "a negative category keeps its sign");
        // sorted by points descending
        List<Double> pts = c.fantasy().breakdown().stream().map(BreakdownRow::points).toList();
        for (int i = 1; i < pts.size(); i++) assertTrue(pts.get(i - 1) >= pts.get(i));
        assertEquals("pts", keys.getFirst());
    }

    @Test
    void shareIsNullWhenTheSeasonTotalIsNotPositive() {
        Map<String, Double> sc = scoring("pts", 1, "to", -2);
        Map<String, List<Line>> by = new HashMap<>();
        by.put("P", List.of(line("P", 1, 20, stats("pts", 4, "to", 6))));      // 4 - 12 = -8
        Computed c = compute(lines(1, by), Map.of(), sc, "P");
        assertEquals(-8.0, c.fantasy().seasonTotal(), 1e-9);
        assertFalse(c.fantasy().breakdown().isEmpty());
        for (BreakdownRow b : c.fantasy().breakdown()) assertNull(b.share());
    }

    // ------------------------------------------------------------------ leagues (I6) and empty seasons (N9)

    @Test
    void sameStatsInTwoLeaguesGiveTheSameRealFiguresAndDifferentFantasy() {
        Map<String, List<Line>> by = new HashMap<>();
        by.put("P", List.of(line("P", 1, 30, stats("pts", 20, "reb", 10, "dd", 1)),
                line("P", 2, 30, stats("pts", 10, "reb", 2))));
        NbaGameLines l = lines(2, by);
        Computed one = compute(l, Map.of(), scoring("pts", 1, "reb", 1, "dd", 1), "P");
        Computed two = compute(l, Map.of(), scoring("pts", 1, "reb", 1.5, "dd", 2), "P");
        assertEquals(one.windows().get(WindowKind.SEASON), two.windows().get(WindowKind.SEASON));
        assertEquals(one.gameLog().stream().map(PlayerStatsService.GameLogRow::gameScore).toList(),
                two.gameLog().stream().map(PlayerStatsService.GameLogRow::gameScore).toList());
        assertEquals((31.0 + 12.0) / 2, one.fantasy().fpPerGame().get(WindowKind.SEASON), 1e-9);
        assertEquals((37.0 + 13.0) / 2.0, two.fantasy().fpPerGame().get(WindowKind.SEASON), 1e-9);
    }

    @Test
    void aPlayerWithNoLinesHasEmptyWindowsAndNoRanks() {
        Map<String, List<Line>> by = new HashMap<>();
        by.put("other", flat("other", 5, 10, 25));
        Computed c = compute(lines(5, by), Map.of(), scoring("pts", 1), "ghost");
        assertEquals(0, c.windows().get(WindowKind.SEASON).games());
        assertTrue(c.gameLog().isEmpty());
        assertTrue(c.teams().isEmpty());
        assertNull(c.fantasy().fpPerGame().get(WindowKind.LAST_5));
        assertEquals("NOT_QUALIFIED", c.fantasy().ranks().reason());
        assertEquals(0.0, c.fantasy().seasonTotal());
        assertTrue(c.fantasy().breakdown().isEmpty());
    }

    @Test
    void gameLogIsNewestFirstAndTeamsAreInOrderOfFirstAppearance() {
        List<Line> l = new ArrayList<>();
        l.add(new Line("g1", D0.plusDays(1), 1, "AAA", "X", true, 30, stats("pts", 1), null, null));
        l.add(new Line("g2", D0.plusDays(2), 1, "BBB", "X", null, 30, stats("pts", 2), null, null));
        l.add(new Line("g3", D0.plusDays(3), 1, "BBB", "X", false, 30, stats("pts", 3), null, null));
        Map<String, List<Line>> by = new HashMap<>();
        by.put("P", l);
        Computed c = compute(lines(3, by), Map.of(), scoring("pts", 1), "P");
        assertEquals(List.of("g3", "g2", "g1"), c.gameLog().stream().map(PlayerStatsService.GameLogRow::gameId).toList());
        assertNull(c.gameLog().get(1).isHome(), "an unstored is_away stays unknown");
        assertEquals(List.of("AAA", "BBB"), c.teams());
        assertEquals(WindowKind.LAST_5.select(l).size(), c.windows().get(WindowKind.LAST_5).games());
    }

    // ------------------------------------------------------------------ read: gates, 404, fallback

    private static final class Wiring {
        final SeasonBoxCache box = mock(SeasonBoxCache.class);
        final LeagueSeasonResolver resolver = mock(LeagueSeasonResolver.class);
        final LeagueRepository leagues = mock(LeagueRepository.class);
        final PlayerRepository players = mock(PlayerRepository.class);
        final RosterSeasonRepository rosterSeasons = mock(RosterSeasonRepository.class);
        final LeagueMemberRepository members = mock(LeagueMemberRepository.class);
        final ManagerRepository managers = mock(ManagerRepository.class);
        final RosterWeekPointsRepository weekPoints = mock(RosterWeekPointsRepository.class);
        final SportRulesRegistry rules = mock(SportRulesRegistry.class);
        final SportRules nbaRules = mock(SportRules.class);
        final PlayerAbsenceRepository absences = mock(PlayerAbsenceRepository.class);

        Wiring(boolean basketball) {
            when(rules.get(any())).thenReturn(nbaRules);
            when(nbaRules.playsMultipleGamesPerScoringPeriod()).thenReturn(basketball);
            when(managers.idsBySleeperUserId()).thenReturn(Map.of());
            when(rosterSeasons.forLeague(anyLong())).thenReturn(List.of());
            when(members.forLeague(anyLong())).thenReturn(List.of());
            when(leagues.playoffFormat(anyLong())).thenReturn(Optional.empty());
            when(leagues.currentLeg(anyLong())).thenReturn(OptionalInt.empty());
            when(rosterSeasons.rosteredPlayers(anyLong())).thenReturn(Optional.empty());
            when(weekPoints.breakdownsFor(anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        }

        PlayerStatsService service(PlayerStatsProperties props) {
            return new PlayerStatsService(props, box, resolver, SCORER, leagues, players, rosterSeasons, members,
                    managers, weekPoints, rules, absences, org.mockito.Mockito.mock(DraftAndAdpJoin.class));
        }
    }

    private static LeagueRow row(long id, int season, String sleeperId, String status) {
        return new LeagueRow(id, Sport.NBA, sleeperId, "Ball Knowers", season, 12, List.of("PG", "BN"), 0.0, null,
                status);
    }

    private static Player player(String id, String name, Position pos) {
        return new Player(1L, Sport.NBA, id, name, List.of(pos), "AAA", null, null, null, null);
    }

    private static SeasonBoxCache.Season season(Map<String, List<Line>> by, int teamGames, boolean hasRows) {
        NbaGameLines l = lines(teamGames, by);
        List<com.ballknowers.draftsim.store.PlayerGameRepository.SeasonGame> games = hasRows
                ? List.of(new com.ballknowers.draftsim.store.PlayerGameRepository.SeasonGame("x", "g1", D0, "OPP",
                        Map.of(), 1, true))
                : List.of();
        return new SeasonBoxCache.Season(new SeasonToken(hasRows ? 1 : 0, OffsetDateTime.parse("2026-04-01T00:00:00Z")),
                games, List.of(), l);
    }

    @Test
    void gatesNotConfiguredThenNotBasketballWithoutTouchingTheData() {
        LeagueRow league = row(1, 2025, "L25", "complete");
        PlayerStatsPage cfg = new Wiring(true).service(new PlayerStatsProperties(null, null, null, null, null, null,
                null, null, null)).read(league, "p", null).orElseThrow();
        assertFalse(cfg.available());
        assertEquals("NOT_CONFIGURED", cfg.reason());

        Wiring nfl = new Wiring(false);
        PlayerStatsPage page = nfl.service(PROPS).read(league, "p", null).orElseThrow();
        assertFalse(page.available());
        assertEquals("NOT_BASKETBALL", page.reason());
        assertNull(page.fantasy());
        assertNull(page.qualification());
        assertFalse(page.player().known());
    }

    @Test
    void aSeasonWithNoStoredGamesIsNoGamesNotNoPlayerGames() {
        Wiring w = new Wiring(true);
        LeagueRow league = row(1, 2026, "L26", "pre_draft");
        when(w.resolver.resolve("L26", Rule.STORED_GAMES)).thenReturn(Optional.of(new Resolved(league, null)));
        when(w.resolver.seasons("L26")).thenReturn(List.of(new SeasonOption(2026, "L26", false)));
        when(w.box.get(Sport.NBA, 2026)).thenReturn(season(Map.of(), 0, false));
        when(w.players.byIds(any(), anyCollection())).thenReturn(Map.of());
        PlayerStatsPage page = w.service(PROPS).read(league, "p", null).orElseThrow();
        assertFalse(page.available());
        assertEquals("NO_GAMES", page.reason());
        assertNull(page.qualification());
        assertEquals(List.of(new SeasonOption(2026, "L26", false)), page.seasons());
    }

    @Test
    void theLeaderboardGatesAreTheSameAsThePlayerPages() {
        LeagueRow league = row(1, 2025, "L25", "complete");
        var cfg = new Wiring(true).service(new PlayerStatsProperties(null, null, null, null, null, null, null, null,
                null)).readLeaderboard(league, WindowKind.LAST_10, null);
        assertFalse(cfg.available());
        assertEquals("NOT_CONFIGURED", cfg.reason());
        assertEquals(WindowKind.LAST_10, cfg.window());
        assertTrue(cfg.rows().isEmpty());

        var nfl = new Wiring(false).service(PROPS).readLeaderboard(league, WindowKind.SEASON, null);
        assertEquals("NOT_BASKETBALL", nfl.reason());
        assertNull(nfl.replacement());

        Wiring w = new Wiring(true);
        LeagueRow l26 = row(1, 2026, "L26", "pre_draft");
        when(w.resolver.resolve("L26", Rule.STORED_GAMES)).thenReturn(Optional.of(new Resolved(l26, null)));
        when(w.resolver.seasons("L26")).thenReturn(List.of(new SeasonOption(2026, "L26", false)));
        when(w.box.get(Sport.NBA, 2026)).thenReturn(season(Map.of(), 0, false));
        var none = w.service(PROPS).readLeaderboard(l26, WindowKind.SEASON, null);
        assertEquals("NO_GAMES", none.reason());
        assertEquals(List.of(new SeasonOption(2026, "L26", false)), none.seasons());
    }

    @Test
    void aKnownPlayerWithoutGamesIsAvailableWithNoPlayerGames() {
        Wiring w = new Wiring(true);
        LeagueRow league = row(1, 2025, "L25", "complete");
        when(w.resolver.resolve("L25", Rule.STORED_GAMES)).thenReturn(Optional.of(new Resolved(league, null)));
        when(w.resolver.seasons("L25")).thenReturn(List.of(new SeasonOption(2025, "L25", true)));
        Map<String, List<Line>> by = new HashMap<>();
        by.put("other", flat("other", 5, 10, 25));
        when(w.box.get(Sport.NBA, 2025)).thenReturn(season(by, 5, true));
        when(w.players.byIds(any(), anyCollection())).thenReturn(Map.of("retired", player("retired", "Old Timer", Position.C)));
        when(w.leagues.scoringOf(1L)).thenReturn(scoring("pts", 1));
        PlayerStatsPage page = w.service(PROPS).read(league, "retired", null).orElseThrow();
        assertTrue(page.available());
        assertEquals("NO_PLAYER_GAMES", page.reason());
        assertTrue(page.player().known());
        assertEquals("Old Timer", page.player().name());
        assertEquals(new PlayerStatsService.QualificationRule(0.5, 3, 5, 15, 14), page.qualification());
        assertTrue(page.gameLog().isEmpty());
        assertEquals(0, page.windows().get(WindowKind.SEASON).games());
        assertNotNull(page.ownership());
    }

    @Test
    void anUnknownIdWithNoGamesIsEmptyForTheControllersNotFound() {
        Wiring w = new Wiring(true);
        LeagueRow league = row(1, 2025, "L25", "complete");
        when(w.resolver.resolve("L25", Rule.STORED_GAMES)).thenReturn(Optional.of(new Resolved(league, null)));
        when(w.resolver.seasons("L25")).thenReturn(List.of());
        Map<String, List<Line>> by = new HashMap<>();
        by.put("other", flat("other", 5, 10, 25));
        when(w.box.get(Sport.NBA, 2025)).thenReturn(season(by, 5, true));
        when(w.players.byIds(any(), anyCollection())).thenReturn(Map.of());
        assertTrue(w.service(PROPS).read(league, "nobody", null).isEmpty());
    }

    @Test
    void aPlayerWithGamesButNoRowIsKnownFalseWithNullNameAndTeam() {
        Wiring w = new Wiring(true);
        LeagueRow league = row(1, 2025, "L25", "complete");
        when(w.resolver.resolve("L25", Rule.STORED_GAMES)).thenReturn(Optional.of(new Resolved(league, null)));
        when(w.resolver.seasons("L25")).thenReturn(List.of());
        Map<String, List<Line>> by = new HashMap<>();
        by.put("ghost", flat("ghost", 5, 10, 25));
        when(w.box.get(Sport.NBA, 2025)).thenReturn(season(by, 5, true));
        when(w.players.byIds(any(), anyCollection())).thenReturn(Map.of());
        when(w.leagues.scoringOf(1L)).thenReturn(scoring("pts", 1));
        PlayerStatsPage page = w.service(PROPS).read(league, "ghost", null).orElseThrow();
        assertTrue(page.available());
        assertNull(page.reason());
        assertFalse(page.player().known());
        assertNull(page.player().name());
        assertTrue(page.player().positions().isEmpty());
        assertNull(page.player().team());
        assertEquals(5, page.windows().get(WindowKind.SEASON).games());
        assertNull(page.fantasy().ranks().position(), "no row, no position");
    }

    @Test
    void aFallbackSeasonScoresUnderTheAnsweredSeasonsLeagueAndCarriesCurrentOwnershipSeparately() {
        Wiring w = new Wiring(true);
        LeagueRow requested = row(3, 2026, "L26", "in_season");
        LeagueRow answered = row(2, 2025, "L25", "complete");
        when(w.resolver.resolve("L26", Rule.STORED_GAMES)).thenReturn(Optional.of(new Resolved(answered, 2026)));
        when(w.resolver.seasons("L26")).thenReturn(List.of(new SeasonOption(2026, "L26", false),
                new SeasonOption(2025, "L25", true)));
        Map<String, List<Line>> by = new HashMap<>();
        by.put("p", flat("p", 5, 10, 25));
        when(w.box.get(Sport.NBA, 2025)).thenReturn(season(by, 5, true));
        when(w.players.byIds(any(), anyCollection())).thenReturn(Map.of("p", player("p", "Pat", Position.PG)));
        when(w.leagues.scoringOf(2L)).thenReturn(scoring("pts", 3));        // 2025's scoring
        when(w.leagues.scoringOf(3L)).thenReturn(scoring("pts", 100));      // 2026's must not be used
        when(w.rosterSeasons.rosteredPlayers(3L)).thenReturn(Optional.of(new RosterSeasonRepository.Rostered(
                Map.of("p", 4), OffsetDateTime.parse("2026-10-08T10:00:00Z"))));
        PlayerStatsPage page = w.service(PROPS).read(requested, "p", null).orElseThrow();
        assertEquals(2025, page.season());
        assertEquals(2026, page.requestedSeason());
        assertEquals(30.0, page.fantasy().fpPerGame().get(WindowKind.SEASON), 1e-9);
        assertNotNull(page.currentOwnership());
        assertEquals("ROSTERED", page.currentOwnership().state());
        assertEquals("CURRENT", page.currentOwnership().asOf().kind());
        // the answered season's own ownership never borrows the current rosters (I8)
        assertEquals("UNAVAILABLE", page.ownership().state());
    }

    @Test
    void noFallbackMeansNoCurrentOwnership() {
        Wiring w = new Wiring(true);
        LeagueRow league = row(2, 2025, "L25", "in_season");
        when(w.resolver.resolve("L25", Rule.STORED_GAMES)).thenReturn(Optional.of(new Resolved(league, null)));
        when(w.resolver.seasons("L25")).thenReturn(List.of());
        Map<String, List<Line>> by = new HashMap<>();
        by.put("p", flat("p", 5, 10, 25));
        when(w.box.get(Sport.NBA, 2025)).thenReturn(season(by, 5, true));
        when(w.players.byIds(any(), anyCollection())).thenReturn(Map.of("p", player("p", "Pat", Position.PG)));
        when(w.leagues.scoringOf(anyLong())).thenReturn(scoring("pts", 1));
        PlayerStatsPage page = w.service(PROPS).read(league, "p", null).orElseThrow();
        assertNull(page.requestedSeason());
        assertNull(page.currentOwnership());
    }

    // ---- spec 023 code review N1, N2: the scoring-changed flag

    @Test
    void sameScoringIgnoresZeroWeightEntriesAndOrder() {
        assertEquals(Boolean.TRUE, PlayerStatsService.sameScoring(scoring("pts", 1, "ast", 1.5), scoring("ast", 1.5, "pts", 1)));
        assertEquals(Boolean.TRUE, PlayerStatsService.sameScoring(scoring("pts", 1), scoring("pts", 1, "new_stat", 0)));
        assertEquals(Boolean.TRUE, PlayerStatsService.sameScoring(scoring("pts", 1, "new_stat", 0), scoring("pts", 1)));
    }

    @Test
    void sameScoringSeesARealDifference() {
        assertEquals(Boolean.FALSE, PlayerStatsService.sameScoring(scoring("pts", 1), scoring("pts", 1, "blk", 2)));
        assertEquals(Boolean.FALSE, PlayerStatsService.sameScoring(scoring("pts", 1), scoring("pts", 2)));
    }

    @Test
    void sameScoringIsUnknownWhenEitherSideIsNotStored() {
        assertNull(PlayerStatsService.sameScoring(Map.of(), scoring("pts", 1)));
        assertNull(PlayerStatsService.sameScoring(scoring("pts", 1), Map.of()));
        assertNull(PlayerStatsService.sameScoring(Map.of(), Map.of()));
    }
}
