package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.domain.Platform;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerLinkRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec 035 T036 over the synthetic fixture. Runs against the Spring datasource (DB_URL). */
class EspnLeagueIngestIT extends EspnIT {

    private int rows(String table) {
        return count("select count(*) from " + table + " t join league l on l.id = t.league_id "
                + "where l.platform_league_id = ?", LEAGUE_ID);
    }

    private int leagues() {
        return count("select count(*) from league where platform = 'ESPN' and platform_league_id = ?", LEAGUE_ID);
    }

    @Test
    void addWritesEspnRowsThroughTheSharedTables() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(true))
                .andExpect(jsonPath("$.leagueKey").value(LEAGUE_KEY))
                .andExpect(jsonPath("$.seasons.length()").value(2));

        assertEquals(2, leagues());
        assertEquals(2, count("select count(*) from league where platform_league_id = ? and platform = 'ESPN' and sport = 'nba'", LEAGUE_ID));
        assertEquals(PREVIOUS_KEY, jdbc.queryForObject("select previous_league_id from league where sleeper_id = ?", String.class, LEAGUE_KEY));
        assertEquals(4, count("select count(*) from manager where platform = 'ESPN' and sleeper_user_id like 'espn:{IT035-%'"));
        assertEquals(2, count("select count(*) from draft where platform = 'ESPN' and league_id in (select id from league where platform_league_id = ?)", LEAGUE_ID));
        assertEquals(8, rows("roster_season"));
        assertEquals(6, count("select count(*) from draft_pick where draft_id in (select id from draft where sleeper_draft_id in (?, ?))", DRAFT_KEY, PREVIOUS_KEY + "-draft"));
        assertEquals(1, count("select count(*) from manager_link"
                + " where linked_manager_id = (select id from manager where sleeper_user_id = 'espn:{IT035-A}')"));

        // points_possible is null (ESPN has no equivalent), and the champion exists only on the finished season.
        assertEquals(0, count("select count(*) from roster_season rs join league l on l.id = rs.league_id "
                + "where l.platform_league_id = ? and rs.points_possible is not null", LEAGUE_ID));
        assertEquals(0, count("select count(*) from roster_season rs join league l on l.id = rs.league_id "
                + "where l.sleeper_id = ? and rs.final_placement is not null", LEAGUE_KEY));
        assertEquals(1, count("select count(*) from roster_season rs join league l on l.id = rs.league_id "
                + "where l.sleeper_id = ? and rs.final_placement = 1", PREVIOUS_KEY));
        assertEquals(0, jdbc.queryForObject("select (settings_json->>'playoff_seed_type')::int from league where sleeper_id = ?", Integer.class, LEAGUE_KEY));

        // Undecided periods have pairings but no points; the bye has a null matchup_id.
        assertEquals(4, count("select count(*) from roster_week_points w join league l on l.id = w.league_id where l.sleeper_id = ?", LEAGUE_KEY));
        assertEquals(11, count("select count(*) from league_matchup m join league l on l.id = m.league_id where l.sleeper_id = ?", LEAGUE_KEY));
        assertEquals(1, count("select count(*) from league_matchup m join league l on l.id = m.league_id "
                + "where l.sleeper_id = ? and m.matchup_id is null", LEAGUE_KEY));

        // Players: matched ones carry the Sleeper id; the refused ones carry espn:<id> and say why.
        Map<String, Object> roster = jdbc.queryForMap("select players, reserve from roster_season rs join league l on l.id = rs.league_id "
                + "where l.sleeper_id = ? and rs.roster_id = 1", LEAGUE_KEY);
        assertEquals(List.of("it035-1", "it035-2"), List.of((Object[]) ((java.sql.Array) roster.get("players")).getArray()));
        assertEquals(List.of("it035-3"), List.of((Object[]) ((java.sql.Array) roster.get("reserve")).getArray()), "IR is the reserve list");
        String bravo = jdbc.queryForObject("select players::text from roster_season rs join league l on l.id = rs.league_id "
                + "where l.sleeper_id = ? and rs.roster_id = 2", String.class, LEAGUE_KEY);
        assertTrue(bravo.contains("it035-4") && bravo.contains("espn:9990005") && bravo.contains("espn:9990006"), bravo);
        assertEquals("AMBIGUOUS", jdbc.queryForObject("select outcome from platform_player_link where external_id = '9990005'", String.class));
        assertEquals("UNMATCHED", jdbc.queryForObject("select outcome from platform_player_link where external_id = '9990006'", String.class));
        assertEquals("ESPN_ONLY_POSITION", jdbc.queryForObject("select outcome from platform_player_link where external_id = '9990007'", String.class));
        assertNull(jdbc.queryForObject("select player_id from platform_player_link where external_id = '9990005'", Long.class), "an ambiguous player is never given an id");
        verifyNoInteractions(sleeper);
    }

    @Test
    void addingTwiceChangesNothing() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        int[] before = snapshot();
        add(ALLAN, 1, false).andExpect(status().isOk());
        assertEquals(java.util.Arrays.toString(before), java.util.Arrays.toString(snapshot()));
    }

    private int[] snapshot() {
        return new int[] {leagues(), rows("roster_season"), rows("roster_week_points"), rows("league_matchup"), rows("league_member"),
                rows("league_transaction"), rows("league_week_fetch"),
                count("select count(*) from draft_pick where draft_id in (select id from draft where sleeper_draft_id in (?, ?))", DRAFT_KEY, PREVIOUS_KEY + "-draft"),
                count("select count(*) from manager where sleeper_user_id like 'espn:{IT035-%'"),
                count("select count(*) from manager_link"), count("select count(*) from platform_player_link where external_id like '99900%'")};
    }

    @Test
    void aTranslatorFailureOnTheSecondSeasonWritesNothing() throws Exception {
        org.mockito.Mockito.when(client.league(org.mockito.ArgumentMatchers.eq("fba"), org.mockito.ArgumentMatchers.eq(LEAGUE_ID),
                org.mockito.ArgumentMatchers.eq(2026), org.mockito.ArgumentMatchers.anySet(), any())).thenAnswer(inv -> {
            Map<String, Object> p = EspnFixtures.nbaLeaguePreviousSeason();
            EspnFixtures.list(p.get("teams")).get(1).remove("name");
            return p;
        });
        add(ALLAN, 1, false).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("ESPN_SHAPE_CHANGED"));
        assertEquals(0, leagues());
        assertEquals(0, count("select count(*) from manager where sleeper_user_id like 'espn:{IT035-%'"));
        assertEquals(0, count("select count(*) from manager_link"));
        assertEquals(0, count("select count(*) from platform_player_link where external_id like '99900%'"));
    }

    @Test
    void aSeasonEspnWillNotServeIsRecordedAsMissingAndTheRestIsKept() throws Exception {
        org.mockito.Mockito.when(client.league(org.mockito.ArgumentMatchers.eq("fba"), org.mockito.ArgumentMatchers.eq(LEAGUE_ID),
                org.mockito.ArgumentMatchers.eq(2026), org.mockito.ArgumentMatchers.anySet(), any())).thenThrow(new EspnNotFound());
        add(ALLAN, 1, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.missingSeasons[0].season").value(2025))
                .andExpect(jsonPath("$.missingSeasons[0].reason").value("ESPN_NOT_FOUND"));
        assertEquals(1, leagues());
    }

    @Test
    void withoutAClaimTheTeamListComesBackAndNothingIsWritten() throws Exception {
        add(ALLAN, null, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(false))
                .andExpect(jsonPath("$.teams.length()").value(4))
                .andExpect(jsonPath("$.teams[0].name").value("Team Alpha"))
                .andExpect(jsonPath("$.players.ambiguous").value(1));
        assertEquals(0, leagues());
        assertEquals(0, count("select count(*) from manager where sleeper_user_id like 'espn:{IT035-%' or sleeper_user_id like 'it035-%'"));
        assertEquals(0, count("select count(*) from platform_player_link where external_id like '99900%'"));
    }

    @Test
    void aStrangerCannotBorrowTheStoredCookies() throws Exception {
        privateLeague = true;
        add(ALLAN, 1, true).andExpect(status().isOk()).andExpect(jsonPath("$.credentialStored").value(true));
        assertEquals(1, count("select count(*) from league_credential where platform_league_id = ?", LEAGUE_ID));
        byte[] sealed = jdbc.queryForObject("select ciphertext from league_credential where platform_league_id = ?", byte[].class, LEAGUE_ID);
        assertFalse(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1).contains("SENTINEL"));

        clearInvocations(client);
        String stranger = add(STRANGER, 1, false).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ESPN_PRIVATE_NO_ACCESS"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(stranger.contains("Synthetic Hoops") || stranger.contains("Alpha") || stranger.contains("teams"), stranger);
        verify(client, never()).league(anyString(), anyString(), anyInt(), any(), isNotNull());   // the stored cookies were never sent

        // The control: the person who can already see the league does get the stored cookies used.
        clearInvocations(client);
        add(ALLAN, 1, false).andExpect(status().isOk());
        verify(client, org.mockito.Mockito.atLeastOnce()).league(anyString(), anyString(), anyInt(), any(), isNotNull());
    }

    @Test
    void aSecondPersonClaimingTheSameTeamIsRefused() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        add(STRANGER, 1, false).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ALREADY_CLAIMED"));
        assertEquals(1, count("select count(*) from manager_link"));
        // A different team is fine.
        add(STRANGER, 2, false).andExpect(status().isOk());
        assertEquals(2, count("select count(*) from manager_link"));
    }

    @Test
    void cookiesWithoutAServerKeyAreRefusedBeforeAnyEspnCall() throws Exception {
        // The shared context has a key, so exercise the service rule directly with an unkeyed cipher.
        com.ballknowers.draftsim.security.CredentialCipher none = new com.ballknowers.draftsim.security.CredentialCipher("");
        EspnLeagueIngestService svc = new EspnLeagueIngestService(client, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, none, java.time.Clock.systemUTC(), null);
        EspnAddFailure f = org.junit.jupiter.api.Assertions.assertThrows(EspnAddFailure.class,
                () -> svc.add(EspnLeagueRef.of(LEAGUE_ID, com.ballknowers.draftsim.domain.Sport.NBA, 2026),
                        new EspnCredential("a", "b"), ALLAN, 1));
        assertEquals("CREDENTIALS_NOT_CONFIGURED", f.code());
        verifyNoInteractions(client);
    }

    @Test
    void aBadCredentialShapeNeverReachesTheService() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/setup/espn/league")
                        .header("X-Sleeper-User", ALLAN).contentType("application/json")
                        .content("{\"link\":\"" + LINK + "\",\"credential\":{\"espnS2\":\"bad;value\",\"swid\":\"x\"}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    // ---- roster moves (T039) --------------------------------------------------------------------------

    private int moves(String leagueKey) {
        return count("select count(*) from league_transaction t join league l on l.id = t.league_id where l.sleeper_id = ?", leagueKey);
    }

    @Test
    void addStoresTheRosterMovesInTheStoredTypeSetKeyedByPlayer() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        assertEquals(4, moves(LEAGUE_KEY));
        assertEquals(4, moves(PREVIOUS_KEY), "the finished season asks about every matchup period");
        assertEquals(List.of("espn:5001 WAIVER complete", "espn:5002 FREE_AGENT complete", "espn:5003 TRADE complete",
                        "espn:5004 WAIVER failed"),
                jdbc.queryForList("select t.sleeper_transaction_id || ' ' || t.type || ' ' || t.status from league_transaction t "
                        + "join league l on l.id = t.league_id where l.sleeper_id = ? order by 1", String.class, LEAGUE_KEY));

        Map<String, Object> waiver = jdbc.queryForMap("select t.faab_bid, t.roster_id, t.adds::text a, t.drops::text d, "
                + "(select sleeper_user_id from manager where id = t.manager_id) owner, t.week from league_transaction t "
                + "join league l on l.id = t.league_id where l.sleeper_id = ? and t.sleeper_transaction_id = 'espn:5001'", LEAGUE_KEY);
        assertEquals(12, waiver.get("faab_bid"));
        assertEquals(2, waiver.get("roster_id"));
        assertEquals("espn:{IT035-B}", waiver.get("owner"));
        assertEquals(1, waiver.get("week"), "an NBA move belongs to its matchup period");
        assertTrue(waiver.get("a").toString().contains("it035-2"), "a matched player is stored by his Sleeper id: " + waiver.get("a"));
        assertTrue(waiver.get("d").toString().contains("espn:9990006"), "a refused player keeps the espn: key: " + waiver.get("d"));

        assertEquals(1, count("select count(*) from league_transaction t join league l on l.id = t.league_id "
                + "where l.sleeper_id = ? and t.type = 'TRADE' and t.roster_id is null and t.manager_id is null", LEAGUE_KEY));
        assertEquals(0, count("select count(*) from league_transaction t join league l on l.id = t.league_id "
                + "where l.sleeper_id = ? and t.type <> 'WAIVER' and t.faab_bid is not null", LEAGUE_KEY), "only a waiver claim carries a bid");
        verifyNoInteractions(sleeper);
    }

    @Test
    void aPeriodStoredAsFinalIsNeverAskedAboutAgain() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        assertEquals(List.of("1 true", "2 false"), jdbc.queryForList("select f.week || ' ' || f.final from league_week_fetch f "
                + "join league l on l.id = f.league_id where l.sleeper_id = ? and f.kind = 'TRANSACTIONS' order by f.week", String.class, LEAGUE_KEY),
                "period 1 is over, the current period is not");
        int before = moves(LEAGUE_KEY);
        add(ALLAN, 1, false).andExpect(status().isOk());
        assertEquals(before, moves(LEAGUE_KEY), "a second add is idempotent");
        // 2027 = the current season: period 1's last day (7) was asked once, the open period's (14) twice.
        verify(client, times(1)).transactions(eq("fba"), eq(LEAGUE_ID), eq(2027), eq(7), any());
        verify(client, times(2)).transactions(eq("fba"), eq(LEAGUE_ID), eq(2027), eq(14), any());
        // 2026 = finished: every period is final after the first read.
        verify(client, times(1)).transactions(eq("fba"), eq(LEAGUE_ID), eq(2026), eq(7), any());
    }

    @Test
    void aTransactionsShapeFailureStoresTheLeagueWithoutMovesAndSaysSoByCodeOnly() throws Exception {
        org.mockito.Mockito.doReturn(new java.util.LinkedHashMap<String, Object>()).when(client)
                .transactions(anyString(), anyString(), anyInt(), anyInt(), any());
        add(ALLAN, 1, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(true))
                .andExpect(jsonPath("$.unavailable.transactions").value("ESPN_SHAPE_CHANGED"));
        assertEquals(2, leagues(), "the league is stored");
        assertEquals(0, moves(LEAGUE_KEY));
        assertEquals(0, count("select count(*) from league_week_fetch f join league l on l.id = f.league_id "
                + "where l.platform_league_id = ? and f.kind = 'TRANSACTIONS'", LEAGUE_ID), "nothing is marked as read");
        assertEquals("ESPN_SHAPE_CHANGED", jdbc.queryForObject("select r.last_failure from league_refresh r join league l on l.id = r.league_id "
                + "where l.sleeper_id = ?", String.class, LEAGUE_KEY));
        assertEquals(0, count("select count(*) from league_refresh r join league l on l.id = r.league_id "
                + "where l.sleeper_id = ? and r.loaded_complete", PREVIOUS_KEY), "a season missing its moves is not frozen");
    }

    @Test
    void aFinishedSeasonIsFrozenOnceReadButTheCurrentOneNeedsASecondRead() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());
        assertEquals(1, count("select count(*) from league_refresh r join league l on l.id = r.league_id "
                + "where l.sleeper_id = ? and r.loaded_complete", PREVIOUS_KEY));
        assertEquals(0, count("select count(*) from league_refresh r join league l on l.id = r.league_id "
                + "where l.sleeper_id = ? and r.loaded_complete", LEAGUE_KEY));
    }

    // ---- fix pass (code-review.md) -------------------------------------------------------------------

    @Autowired EspnLeagueIngestService service;
    @Autowired ManagerRepository managerRepo;
    @Autowired ManagerLinkRepository linkRepo;
    @Autowired LeagueRepository leagueRepo;

    @AfterEach
    void dropProvenanceLeague() {
        jdbc.update("delete from league where sleeper_id = 'it035-provenance'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void s1_theLeagueLockIsAReentrantLockHeldDuringTheFetchNeverAMonitor() throws Exception {
        // On Java 21 a virtual thread blocked inside `synchronized` pins its carrier. The lock must be a
        // ReentrantLock, held by the add while the ESPN call is in flight, and no monitor may be held.
        java.lang.reflect.Field f = EspnLeagueIngestService.class.getDeclaredField("locks");
        f.setAccessible(true);
        Map<String, Object> locks = (Map<String, Object>) f.get(service);
        boolean[] seen = new boolean[3];   // [0] a lock existed, [1] held as a ReentrantLock, [2] held as a monitor
        org.mockito.Mockito.when(client.league(eq("fba"), eq(LEAGUE_ID), eq(2027), org.mockito.ArgumentMatchers.anySet(), any()))
                .thenAnswer(inv -> {
                    for (Object l : locks.values()) {
                        seen[0] = true;
                        seen[1] |= l instanceof java.util.concurrent.locks.ReentrantLock rl && rl.isHeldByCurrentThread();
                        seen[2] |= Thread.holdsLock(l);
                    }
                    return EspnFixtures.nbaLeague();
                });
        add(ALLAN, null, false).andExpect(status().isOk());
        assertTrue(seen[0], "the add took a lock before calling ESPN");
        assertTrue(seen[1], "and it was a ReentrantLock held by the calling thread");
        assertFalse(seen[2], "no monitor is held across the network call");
    }

    @Test
    void s3_aPhaseTwoFailureOnABrandNewLeagueLeavesNothingBehind() throws Exception {
        // Member A of the fixture is already linked to someone else (they play in another stored league).
        // A second person adds THIS league (never stored) and claims A's team: writeSeason has written every
        // row by the time links.link throws AlreadyClaimed, so only the transaction keeps them out of the store.
        long other = managerRepo.upsert("it035-other", "Other", null);
        long a = managerRepo.upsert("espn:{IT035-A}", "Alpha", null, Platform.ESPN);
        long provenance = leagueRepo.upsert(Sport.NBA, 2099, "it035-provenance", null, "Provenance", 4, "{}", "{}",
                List.of("PG"), "in_season");
        linkRepo.link(other, a, provenance);

        add(ALLAN, 1, true).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ALREADY_CLAIMED"));

        assertEquals(0, leagues(), "no league row");
        for (String table : List.of("roster_season", "league_member", "league_matchup", "roster_week_points",
                "league_transaction", "league_week_fetch", "league_refresh")) {
            assertEquals(0, rows(table), table);
        }
        assertEquals(0, count("select count(*) from draft where league_id in (select id from league where platform_league_id = ?)", LEAGUE_ID));
        assertEquals(0, count("select count(*) from manager where sleeper_user_id like 'espn:{IT035-%' and sleeper_user_id <> 'espn:{IT035-A}'"),
                "no other ESPN manager was created");
        assertEquals(0, count("select count(*) from manager where sleeper_user_id = ?", ALLAN), "the claimant's manager row was rolled back");
        assertEquals(0, count("select count(*) from platform_player_link where external_id like '99900%'"));
        assertEquals(0, count("select count(*) from league_credential where platform_league_id = ?", LEAGUE_ID),
                "the cookies sent with the request were not stored");
        assertEquals(1, count("select count(*) from manager_link"), "only the pre-existing link");
    }

    @Test
    void s7_aCoOwnerCanClaimATeamWhosePrimaryOwnerIsAlreadyClaimed() throws Exception {
        // Fixture team 3 is owned by C with D as co-owner.
        add(ALLAN, 3, false).andExpect(status().isOk());
        assertEquals("espn:{IT035-C}", jdbc.queryForObject("select m.sleeper_user_id from manager_link l join manager m on m.id = l.linked_manager_id "
                + "join manager u on u.id = l.manager_id where u.sleeper_user_id = ?", String.class, ALLAN), "the primary owner is linked first");

        add(STRANGER, 3, false).andExpect(status().isOk());
        assertEquals("espn:{IT035-D}", jdbc.queryForObject("select m.sleeper_user_id from manager_link l join manager m on m.id = l.linked_manager_id "
                + "join manager u on u.id = l.manager_id where u.sleeper_user_id = ?", String.class, STRANGER), "the co-owner claims through D");

        // Every owner of the team is now held: a third person is refused.
        add("it035-third", 3, false).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("ALREADY_CLAIMED"));
        assertEquals(2, count("select count(*) from manager_link"));

        // The team list says the team is taken only once every owner is.
        add("it035-third", null, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.teams[2].claimedByOther").value(true));
    }

    @Test
    void s7_aTeamWithAFreeCoOwnerIsStillClaimableInThePreview() throws Exception {
        add(ALLAN, 3, false).andExpect(status().isOk());   // C held; D free
        add("it035-third", null, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.teams[2].claimedByOther").value(false));
    }

    @Test
    void s9_theAddResultNamesTheRefusedPlayers() throws Exception {
        add(ALLAN, null, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.unmatched.length()").value(3))
                .andExpect(jsonPath("$.unmatched[*].name").value(org.hamcrest.Matchers.hasItem("Zed Ambig")))
                .andExpect(jsonPath("$.unmatched[*].outcome").value(org.hamcrest.Matchers.containsInAnyOrder("AMBIGUOUS", "UNMATCHED", "ESPN_ONLY_POSITION")));
    }

    @Test
    void s4_anEspnShapedHeaderIsNobodyEvenWhenThatManagerExists() throws Exception {
        add(ALLAN, 1, false).andExpect(status().isOk());   // creates manager espn:{IT035-A}, who has a roster here
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/leagues/" + LEAGUE_KEY + "/availability").header("X-Sleeper-User", "espn:{IT035-A}"))
                .andExpect(status().isNotFound());
        // Adding a league as that "identity" is refused outright.
        add("espn:{IT035-A}", 1, false).andExpect(status().isUnauthorized());
    }
}
