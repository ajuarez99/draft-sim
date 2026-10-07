package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.ExpectedWinsService;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.Kind;
import com.ballknowers.draftsim.store.LeagueConductRepository;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * specs/021-codebase-cleanup T030, the oracle for converting SuperlativesController's
 * hand-built maps into response records. It works the same way as
 * {@link LeagueHistoryControllerCharacterizationTest}: standalone MockMvc with every
 * collaborator mocked, so there's no context, no Postgres and no database ids, and it
 * was written green against the map code first.
 *
 * <p>Coverage aimed at the conversion's traps (plan-review findings 2, 3, 7):
 * <ul>
 *   <li>all eight {@code detail} subtypes, each with its hand-written {@code type};</li>
 *   <li>{@code biggestWeek} and {@code faabBid} both null and set;</li>
 *   <li>{@code coverage} null and set;</li>
 *   <li>a standings row with a value and one without;</li>
 *   <li>a free agent's null team;</li>
 *   <li>every top-level nullable on the result both null and set.</li>
 * </ul>
 */
class SuperlativesControllerCharacterizationTest {

    private static final String LEAGUE = "L-2026";
    private static final String ME = "u-commish";

    private SeasonSuperlativesService superlatives;
    private LeagueMembership membership;
    private LeagueConductRepository conduct;
    private LeagueMemberRepository leagueMembers;
    private PlayerRepository players;
    private ManagerRepository managers;
    private MockMvc mvc;

    private static final LeagueRepository.LeagueRow ROW = new LeagueRepository.LeagueRow(
            100L, Sport.NBA, LEAGUE, "Ball Knowers", 2026, 12, List.of(), 1.0, null, "in_season");

    @BeforeEach
    void setUp() {
        superlatives = mock(SeasonSuperlativesService.class);
        membership = mock(LeagueMembership.class);
        conduct = mock(LeagueConductRepository.class);
        leagueMembers = mock(LeagueMemberRepository.class);
        players = mock(PlayerRepository.class);
        managers = mock(ManagerRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new SuperlativesController(superlatives, membership, conduct, leagueMembers, players, managers)).build();
        when(membership.visibleLeague(eq(LEAGUE), any())).thenReturn(Optional.of(ROW));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r, String who) {
        return who == null ? r : r.header("X-Sleeper-User", who);
    }

    private void check(MockHttpServletRequestBuilder req, int status, String golden) throws Exception {
        MvcResult res = mvc.perform(req).andReturn();
        assertEquals(status, res.getResponse().getStatus(), golden + " status");
        String body = res.getResponse().getContentAsString();
        if (body.isEmpty()) return;
        GoldenJson.assertJsonMatchesGolden(body, "superlatives/" + golden);
    }

    private static final SeasonSuperlativesService.Holder SHARKS =
            new SeasonSuperlativesService.Holder(1, 11L, "Sharks", "popsharky", "av11");
    private static final SeasonSuperlativesService.Holder ORPHAN =
            new SeasonSuperlativesService.Holder(4, null, null, null, null);

    private static SeasonSuperlativesService.Result fullResult() {
        List<SeasonSuperlativesService.DetailRow> everyDetail = List.of(
                new SeasonSuperlativesService.WeekScoreDetail(3, 1, 205.04),
                new SeasonSuperlativesService.GameDetail(5, 1, 2, "Comets", 150.1, 150.0, 0.1),
                new SeasonSuperlativesService.LuckDetail(1, 7.0, 5.5, 1.5,
                        List.of(new ExpectedWinsService.SwingWeek(4, true, 98.2, 9, "Comets")), 1, 9, "lucky"),
                new SeasonSuperlativesService.BenchTotalDetail(1, 210.5, 9, 1, 9,
                        new SeasonSuperlativesService.BiggestBenchWeek(6, 44.25)),
                new SeasonSuperlativesService.BenchTotalDetail(2, 0.0, 0, 1, 9, null),
                new SeasonSuperlativesService.PickupDetail("p1", "Waiver Hero", "SF", 1, 4, "waiver", List.of(5, 6), 88.5),
                new SeasonSuperlativesService.AbsenceDetail("p2", "Joel Embiid", "C", 2, 6, 3, 52.0, 312.0, true),
                new SeasonSuperlativesService.ConductDetail("p3", "Bad Actor", 3, "COMMISSIONER", List.of(2, 3), "fined"),
                new SeasonSuperlativesService.AddDetail("p4", 7, 1, "Sharks", "av11", "free_agent", null),
                new SeasonSuperlativesService.AddDetail("p4", 8, 2, "Comets", null, "waiver", 17));
        SeasonSuperlativesService.Superlative big = new SeasonSuperlativesService.Superlative(
                Kind.HIGHEST_WEEK, true, null, false, 205.04, "points",
                List.of(SHARKS), null, everyDetail,
                new SeasonSuperlativesService.Coverage(9, 1, List.of("week 4 unscored")),
                List.of(), List.of(
                        new SeasonSuperlativesService.Standing(1, SHARKS, 205.04, "week 3", true, null),
                        new SeasonSuperlativesService.Standing(null, ORPHAN, null, null, false, "no games played")),
                List.of());
        SeasonSuperlativesService.Superlative jabari = new SeasonSuperlativesService.Superlative(
                Kind.JABARI_SMITH_JR, true, null, true, 3.0, "adds",
                List.of(), null, List.of(), null,
                List.of(new SeasonSuperlativesService.PlayerHolder("p4", "Journeyman", null, null, 3, 2)),
                List.of(),
                List.of(new SeasonSuperlativesService.PlayerStanding(1, "p4", "Journeyman", "PF", "BOS", 3, 2),
                        new SeasonSuperlativesService.PlayerStanding(2, "p5", "Free Agent", null, null, 2, 1)));
        SeasonSuperlativesService.Superlative empty = new SeasonSuperlativesService.Superlative(
                Kind.UNETHICAL, false, "no conduct list yet", false, null, null,
                List.of(), "nobody has been listed", List.of(), null);
        return new SeasonSuperlativesService.Result(true, null, 2026, 2025, Sport.NBA, 9, 9, 18, false, 4, 5.0,
                List.of(3, 4), true, List.of(big, jabari, empty), LEAGUE);
    }

    @Test
    void superlativesWithEveryShape() throws Exception {
        when(superlatives.forLeague(LEAGUE)).thenReturn(Optional.of(fullResult()));
        check(as(get("/api/leagues/" + LEAGUE + "/superlatives"), ME), 200, "superlatives-full");
    }

    @Test
    void superlativesUnavailableWithEveryNullable() throws Exception {
        when(superlatives.forLeague(LEAGUE)).thenReturn(Optional.of(new SeasonSuperlativesService.Result(
                false, "the season has not started", 2026, null, Sport.NFL, null, 0, null, true, 4, null,
                List.of(), false, List.of(), LEAGUE)));
        check(as(get("/api/leagues/" + LEAGUE + "/superlatives"), ME), 200, "superlatives-unavailable");
    }

    @Test
    void superlativesNotFound() throws Exception {
        check(as(get("/api/leagues/" + LEAGUE + "/superlatives"), ME), 404, "superlatives-404-no-result");
        check(as(get("/api/leagues/nope/superlatives"), ME), 404, "superlatives-404-hidden");
    }

    private static Player player(String id, String name) {
        return new Player(1L, Sport.NBA, id, name, List.of(Position.C), "PHI", "Active", null, 30, 9);
    }

    @Test
    void conductListShowsNamesAndUnknowns() throws Exception {
        when(membership.canCommission(eq(100L), any())).thenReturn(true);
        when(leagueMembers.anyCommissioner(100L)).thenReturn(true);
        when(conduct.forLeague(100L)).thenReturn(List.of(
                new LeagueConductRepository.Entry(5L, 100L, "p2", "fined", 3, 11L, Instant.parse("2026-10-01T12:00:00Z")),
                new LeagueConductRepository.Entry(6L, 100L, "gone", "left", 1, null, Instant.parse("2026-10-02T12:00:00Z"))));
        when(players.byIds(eq(Sport.NBA), anyCollection())).thenReturn(Map.of("p2", player("p2", "Joel Embiid")));
        when(managers.displayName(11L)).thenReturn(Optional.of("popsharky"));
        check(as(get("/api/leagues/" + LEAGUE + "/conduct-list"), ME), 200, "conduct-list");
        check(as(get("/api/leagues/nope/conduct-list"), ME), 404, "conduct-list-404");
    }

    @Test
    void saveConductEntryEveryOutcome() throws Exception {
        String url = "/api/leagues/" + LEAGUE + "/conduct-list";
        String ok = "{\"playerId\":\"p2\",\"reason\":\"  fined  \",\"appliesFromWeek\":3}";
        check(as(post(url), ME).contentType("application/json").content(ok), 403, "save-403-unknown");
        when(leagueMembers.anyCommissioner(100L)).thenReturn(true);
        check(as(post(url), ME).contentType("application/json").content(ok), 403, "save-403-known");
        when(membership.canCommission(eq(100L), any())).thenReturn(true);
        check(as(post(url), ME).contentType("application/json").content("{\"reason\":\"x\",\"appliesFromWeek\":1}"), 400, "save-400-player");
        check(as(post(url), ME).contentType("application/json").content("{\"playerId\":\"p2\",\"reason\":\"   \",\"appliesFromWeek\":1}"), 400, "save-400-reason");
        check(as(post(url), ME).contentType("application/json").content("{\"playerId\":\"p2\",\"reason\":\"x\",\"appliesFromWeek\":0}"), 400, "save-400-week");
        check(as(post(url), ME).contentType("application/json").content(ok), 400, "save-400-unknown-player");
        when(players.bySleeperId(Sport.NBA, "p2")).thenReturn(Optional.of(player("p2", "Joel Embiid")));
        when(managers.idsBySleeperUserId()).thenReturn(Map.of(ME, 11L));
        when(conduct.upsert(anyLong(), anyString(), anyString(), anyInt(), any())).thenReturn(
                new LeagueConductRepository.Entry(5L, 100L, "p2", "fined", 3, 11L, Instant.parse("2026-10-01T12:00:00Z")));
        when(managers.displayName(11L)).thenReturn(Optional.of("popsharky"));
        check(as(post(url), ME).contentType("application/json").content(ok), 200, "save-ok");
    }

    @Test
    void deleteConductEntry() throws Exception {
        when(membership.canCommission(eq(100L), any())).thenReturn(true);
        when(conduct.delete(100L, 5L)).thenReturn(true);
        check(as(delete("/api/leagues/" + LEAGUE + "/conduct-list/5"), ME), 204, "delete-204");
        check(as(delete("/api/leagues/" + LEAGUE + "/conduct-list/6"), ME), 404, "delete-404");
    }
}
