package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.ingest.SleeperClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Base64;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared set-up for the ESPN integration tests (spec 035). Real Postgres through the Spring datasource
 * (so {@code DB_URL} decides which one, never a hard-coded URL), a stubbed {@link EspnClient} serving the
 * hand-built synthetic fixtures, and a {@link SleeperClient} mock that no test may talk to.
 *
 * <p>Every subclass shares this class's annotations and mock beans, so they share one Spring context.
 * The ESPN league id (9990001) and the sleeper-style ids below are test-only and are deleted before
 * and after each test.
 */
@SpringBootTest(properties = "credentials.key=" + EspnIT.KEY)
@AutoConfigureMockMvc
public abstract class EspnIT {

    /** 32 bytes of 0x09, base64. A test key, not a secret. */
    static final String KEY = "CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=";

    protected static final String LEAGUE_ID = "9990001";
    protected static final String LEAGUE_KEY = "espn-fba-" + LEAGUE_ID + "-2026";
    protected static final String PREVIOUS_KEY = "espn-fba-" + LEAGUE_ID + "-2025";
    protected static final String DRAFT_KEY = LEAGUE_KEY + "-draft";
    protected static final String LINK = "https://fantasy.espn.com/basketball/team?leagueId=" + LEAGUE_ID + "&teamId=1&seasonId=2027";

    protected static final String ALLAN = "it035-allan";
    protected static final String STRANGER = "it035-stranger";

    protected static final String SENTINEL_S2 = "SENTINEL_S2_0123456789abcdef";
    protected static final String SENTINEL_SWID = "{SENTINEL-SWID-0000}";

    @MockitoBean protected EspnClient client;
    @MockitoBean protected SleeperClient sleeper;

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    protected static final ObjectMapper JSON = new ObjectMapper();

    /** When true the stubbed league answers 401 unless a credential is passed. */
    protected volatile boolean privateLeague = false;

    @BeforeEach
    void espnSetUp() {
        cleanup();
        // NBA players: unique names so no real player collides. "Zed Ambig" is two people on purpose.
        player("it035-1", "Zed Itaa One", "PG");
        player("it035-2", "Zed Itaa Two", "C");
        player("it035-3", "Zed Itaa Three", "SG");
        player("it035-4", "Zed Itbb One", "SG");
        player("it035-5a", "Zed Ambig", "SF");
        player("it035-5b", "Zed Ambig", "SF");
        player("it035-7", "Zed Itcc One", "PG");   // ESPN calls him an unmodelled position: never matched

        when(client.league(eq("fba"), eq(LEAGUE_ID), eq(2027), anySet(), any())).thenAnswer(inv -> {
            if (privateLeague && inv.getArgument(4) == null) throw new EspnAccessDenied();
            return EspnFixtures.nbaLeague();
        });
        when(client.league(eq("fba"), eq(LEAGUE_ID), eq(2026), anySet(), any())).thenAnswer(inv -> {
            if (privateLeague && inv.getArgument(4) == null) throw new EspnAccessDenied();
            return EspnFixtures.nbaLeaguePreviousSeason();
        });
        when(client.players(anyString(), anyInt())).thenThrow(new EspnNotFound());
        // Roster moves: the synthetic payload for scoring period 7 (the last day of NBA matchup period 1), none elsewhere.
        when(client.transactions(anyString(), anyString(), anyInt(), anyInt(), any())).thenAnswer(inv ->
                (int) inv.getArgument(3) == 7 ? EspnFixtures.nbaTransactions() : EspnFixtures.noTransactions());
    }

    @AfterEach
    void espnTearDown() {
        cleanup();
    }

    protected void player(String sleeperId, String name, String position) {
        jdbc.update("insert into player (sport, sleeper_id, name, positions, team, status) "
                + "values ('nba', ?, ?, array[?]::text[], 'ATL', 'Active')", sleeperId, name, position);
    }

    protected void cleanup() {
        jdbc.update("delete from league where platform = 'ESPN' and platform_league_id = ?", LEAGUE_ID);
        jdbc.update("delete from league_credential where platform_league_id = ?", LEAGUE_ID);
        jdbc.update("delete from manager where sleeper_user_id like 'espn:{IT035-%' or sleeper_user_id like 'it035-%'");
        jdbc.update("delete from platform_player_link where external_id like '99900%'");
        jdbc.update("delete from player where sleeper_id like 'it035-%'");
    }

    protected static String body(Integer claim, boolean withCookies) {
        try {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("link", LINK);
            m.put("claimRosterId", claim);
            if (withCookies) m.put("credential", Map.of("espnS2", SENTINEL_S2, "swid", SENTINEL_SWID));
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected ResultActions add(String user, Integer claim, boolean withCookies) throws Exception {
        return mvc.perform(post("/api/setup/espn/league").header("X-Sleeper-User", user)
                .contentType(MediaType.APPLICATION_JSON).content(body(claim, withCookies)));
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    static String unused() {
        return Base64.getEncoder().encodeToString(new byte[0]);
    }
}
