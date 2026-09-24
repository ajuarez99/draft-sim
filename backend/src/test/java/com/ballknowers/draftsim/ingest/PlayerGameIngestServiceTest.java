package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerAbsenceRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The pure half of the per-game ingest: turning one upstream entry into a row,
 * which is where every "we invented a fact" bug would live
 * (specs/005-daily-weekly-top-players, US1). Extended for
 * specs/008-season-superlatives T043: the walk now also writes
 * {@code player_absence} rows (research R9), so several tests below drive the
 * full walk with mocked repositories rather than only {@link
 * PlayerGameIngestService#toRow}.
 */
class PlayerGameIngestServiceTest {

    private static Map<String, Object> entry(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> fullEntry() {
        return entry(
                "game_id", "1261029460694540288",
                "date", "2025-11-17",
                "week", 5,
                "team", "PHI",
                "opponent", "CHI",
                "is_away_team", false,
                "stats", Map.of("pts", 36.0, "reb", 18.0, "ast", 13.0));
    }

    // ------------------------------------------------------------- toRow

    @Test
    void mapsAGameToARow() {
        PlayerGameRepository.Row r = PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", fullEntry());

        assertNotNull(r);
        assertEquals("1658", r.sleeperPlayerId());
        assertEquals("1261029460694540288", r.gameId());
        assertEquals("2025-11-17", r.gameDate().toString());
        assertEquals("CHI", r.opponent());
        assertEquals(Boolean.FALSE, r.isAway());
        assertEquals(5, r.week());
    }

    /**
     * Research R6, and the spec's postponed-game edge case in one assertion.
     *
     * <p>The week comes from the entry's own field. If it were derived from the
     * date, a game moved into another week would land in the week it was
     * scheduled rather than the week it was played -- and this service would
     * need to know about calendars, which it should not.
     */
    @Test
    void theWeekComesFromTheEntryNotTheDate() {
        Map<String, Object> postponed = fullEntry();
        postponed.put("week", 9);
        postponed.put("date", "2025-11-17");

        PlayerGameRepository.Row r = PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", postponed);

        assertEquals(9, r.week(), "the entry said week 9; the date must not override it");
        assertEquals("2025-11-17", r.gameDate().toString());
    }

    /**
     * A game with no id cannot be deduplicated, one with no date cannot be a
     * "night", and one with no week cannot be placed. Dropping it is honest;
     * inventing any of the three is not.
     */
    @Test
    void anEntryMissingAnIdentifyingFieldIsDroppedRatherThanInvented() {
        for (String missing : new String[] {"game_id", "date", "week"}) {
            Map<String, Object> e = fullEntry();
            e.remove(missing);
            assertNull(PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e),
                    "an entry without " + missing + " must not become a row");
        }
    }

    /** A week that is not a number is missing, not zero. */
    @Test
    void aNonNumericWeekIsTreatedAsMissing() {
        Map<String, Object> e = fullEntry();
        e.put("week", "five");
        assertNull(PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e));
    }

    /** An unparseable date is dropped rather than defaulted to today. */
    @Test
    void anUnparseableDateIsDropped() {
        Map<String, Object> e = fullEntry();
        e.put("date", "Nov 17, 2025");
        assertNull(PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e));
    }

    /**
     * research R9, amended from spec 005: an entry with a {@code stats} key
     * that is not even a map is unusable (dropped), but an EMPTY map is a
     * well-formed entry now -- exactly how basketball marks a missed game.
     * Whether it counts as a game played is {@link SportRules#playedIn}'s call
     * during the walk, not {@code toRow}'s.
     */
    @Test
    void anEntryWithAnEmptyStatsMapStillProducesARowForTheWalkToClassify() {
        Map<String, Object> e = fullEntry();
        e.put("stats", Map.of());

        PlayerGameRepository.Row r = PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e);

        assertNotNull(r, "an empty stats map is a missed game, not a malformed entry");
    }

    @Test
    void anEntryWithNoStatsKeyAtAllIsDropped() {
        Map<String, Object> e = fullEntry();
        e.remove("stats");
        assertNull(PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e));
    }

    /**
     * FR-006: a missing opponent still stores the game. The date is the fact
     * the section is built on; the opponent is the nicety, and a row without it
     * is better than no row or a guessed one.
     */
    @Test
    void aMissingOpponentStillStoresTheGame() {
        Map<String, Object> e = fullEntry();
        e.remove("opponent");
        e.remove("is_away_team");

        PlayerGameRepository.Row r = PlayerGameIngestService.toRow(Sport.NBA, 2025, "1658", e);

        assertNotNull(r);
        assertNull(r.opponent());
        assertNull(r.isAway(), "unknown must stay unknown rather than defaulting to home");
    }

    // ---- the walk itself, with the upstream client mocked (T017/T019/T043/T044) ----

    private static SportRulesRegistry registryFor(Sport sport, SportRules rules) {
        SportRulesRegistry registry = mock(SportRulesRegistry.class);
        when(registry.get(sport)).thenReturn(rules);
        return registry;
    }

    /** A real basketball played-signal: non-null, non-empty stats (research R9). */
    private static SportRules basketballRules() {
        SportRules rules = mock(SportRules.class);
        when(rules.playedIn(any())).thenAnswer(inv -> {
            Map<?, ?> stats = inv.getArgument(0);
            return stats != null && !stats.isEmpty();
        });
        return rules;
    }

    /** A real football played-signal: {@code gp > 0} (research R9). */
    private static SportRules footballRules() {
        SportRules rules = mock(SportRules.class);
        when(rules.playedIn(any())).thenAnswer(inv -> {
            Map<?, ?> stats = inv.getArgument(0);
            Object gp = stats == null ? null : stats.get("gp");
            return gp instanceof Number n && n.doubleValue() > 0;
        });
        return rules;
    }

    /**
     * T019/T044. One player's failure must not cost the other 330, and must not
     * pass silently either -- a partial backfill that reports success is how
     * feature 004 ended up with five leagues holding nothing while the suite
     * stayed green.
     */
    @Test
    void oneFailingPlayerIsCountedAndTheRestAreStillStored() {
        var stats = mock(SleeperPlayerStatsClient.class);
        var leagues = mock(LeagueRepository.class);
        var weekPoints = mock(RosterWeekPointsRepository.class);
        var games = mock(PlayerGameRepository.class);
        var absences = mock(PlayerAbsenceRepository.class);
        var registry = registryFor(Sport.NBA, basketballRules());

        when(leagues.bySleeperId("L1")).thenReturn(Optional.of(
                new LeagueRepository.LeagueRow(7L, Sport.NBA, "L1", "Ball Knowers", 2025, 12, List.of(), 0.0, null, null)));
        when(weekPoints.breakdownsFor(7L, 2025)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(5, 3, 269.0, "{\"good\":58.5,\"bad\":10.0}", null)));

        when(stats.seasonByWeek("nba", "good", 2025)).thenReturn(Map.of("5", List.of(fullEntry())));
        when(stats.seasonByWeek("nba", "bad", 2025)).thenThrow(new RuntimeException("upstream said no"));

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, registry);
        PlayerGameIngestService.Result r = service.ingest("L1", 2025);

        assertEquals(2, r.playersWalked());
        assertEquals(1, r.gamesStored(), "the healthy player's game still landed");
        assertEquals(1, r.playersFailed(), "the failure is reported, not swallowed");
        verify(games, times(1)).upsert(any());
    }

    /**
     * T017's half that does not need a database: re-running the walk issues the
     * same upserts rather than accumulating. The natural key turning those into
     * one row is asserted against real Postgres in
     * {@code PlayerGameRepositoryIT}.
     */
    @Test
    void reRunningTheWalkIssuesTheSameUpsertsRatherThanMore() {
        var stats = mock(SleeperPlayerStatsClient.class);
        var leagues = mock(LeagueRepository.class);
        var weekPoints = mock(RosterWeekPointsRepository.class);
        var games = mock(PlayerGameRepository.class);
        var absences = mock(PlayerAbsenceRepository.class);
        var registry = registryFor(Sport.NBA, basketballRules());

        when(leagues.bySleeperId("L1")).thenReturn(Optional.of(
                new LeagueRepository.LeagueRow(7L, Sport.NBA, "L1", "Ball Knowers", 2025, 12, List.of(), 0.0, null, null)));
        when(weekPoints.breakdownsFor(7L, 2025)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(5, 3, 269.0, "{\"1658\":58.5}", null)));
        when(stats.seasonByWeek("nba", "1658", 2025)).thenReturn(Map.of("5", List.of(fullEntry())));

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, registry);
        PlayerGameIngestService.Result first = service.ingest("L1", 2025);
        PlayerGameIngestService.Result second = service.ingest("L1", 2025);

        assertEquals(first, second, "a second walk must report the same work, not more");
        assertEquals(1, first.gamesStored());
    }

    /** A league this app has never ingested is a no-op, not a crash. */
    @Test
    void anUnknownLeagueWalksNothing() {
        var stats = mock(SleeperPlayerStatsClient.class);
        var leagues = mock(LeagueRepository.class);
        var weekPoints = mock(RosterWeekPointsRepository.class);
        var games = mock(PlayerGameRepository.class);
        var absences = mock(PlayerAbsenceRepository.class);
        var registry = mock(SportRulesRegistry.class);
        when(leagues.bySleeperId("nope")).thenReturn(Optional.empty());

        var r = new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, registry).ingest("nope", 2025);

        assertEquals(new PlayerGameIngestService.Result(0, 0, 0, 0, 0), r);
        verifyNoInteractions(stats, games, absences);
    }

    /**
     * Measured live 2026-09-23 (T045), correcting research R9's description:
     * a football week's raw value is a bare JSON object, not a single-element
     * array like basketball's. The very first live run against real Sleeper
     * data hit a {@code ClassCastException} here -- every NFL week failed to
     * classify until {@code asEntryList} learned to accept a bare {@code Map}
     * as a one-entry week. This test pins that shape directly, unlike the
     * other NFL tests below which (still validly) use the list-wrapped form.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aFootballWeekValueThatIsABareObjectNotWrappedInAListIsHandledCorrectly() {
        var stats = mock(SleeperPlayerStatsClient.class);
        var leagues = mock(LeagueRepository.class);
        var weekPoints = mock(RosterWeekPointsRepository.class);
        var games = mock(PlayerGameRepository.class);
        var absences = mock(PlayerAbsenceRepository.class);
        var registry = registryFor(Sport.NFL, footballRules());

        when(leagues.bySleeperId("L1")).thenReturn(Optional.of(
                new LeagueRepository.LeagueRow(7L, Sport.NFL, "L1", "Ball Knowers", 2025, 12, List.of(), 0.0, null, null)));
        when(weekPoints.breakdownsFor(7L, 2025)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(1, 8, 0.0, "{\"4034\":0.0}", null)));

        Map<String, Object> played = entry(
                "game_id", "g9", "date", "2025-09-07", "week", 1, "team", "SF", "opponent", "SEA",
                "stats", Map.of("gp", 1.0, "pts_ppr", 23.2));
        // The real shape: the week key maps DIRECTLY to the entry object, not a list containing it.
        Map<String, Object> bareShaped = new HashMap<>();
        bareShaped.put("1", played);
        when(stats.seasonByWeek("nfl", "4034", 2025)).thenReturn((Map) bareShaped);

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, registry);
        PlayerGameIngestService.Result r = service.ingest("L1", 2025);

        assertEquals(1, r.gamesStored(), "a bare-object week value must still be read as one played game");
        assertEquals(0, r.weeksUnclassified());
        verify(games, times(1)).upsert(any());
    }

    // ------------------------------------------------------- T043 absences

    /**
     * An NBA entry with {@code stats: {}} produces no {@code player_game} row
     * and one {@code player_absence} row (ENTRY_WITHOUT_PLAY, with game_id).
     */
    @Test
    void nbaEmptyStatsProducesNoGameAndOneEntryWithoutPlayAbsence() {
        var stats = mock(SleeperPlayerStatsClient.class);
        var leagues = mock(LeagueRepository.class);
        var weekPoints = mock(RosterWeekPointsRepository.class);
        var games = mock(PlayerGameRepository.class);
        var absences = mock(PlayerAbsenceRepository.class);
        var registry = registryFor(Sport.NBA, basketballRules());

        when(leagues.bySleeperId("L1")).thenReturn(Optional.of(
                new LeagueRepository.LeagueRow(7L, Sport.NBA, "L1", "Ball Knowers", 2025, 12, List.of(), 0.0, null, null)));
        when(weekPoints.breakdownsFor(7L, 2025)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(7, 8, 0.0, "{\"1511\":0.0}", null)));

        Map<String, Object> missedGame = entry(
                "game_id", "g1", "date", "2025-11-01", "week", 7, "team", "PHI", "stats", Map.of());
        when(stats.seasonByWeek("nba", "1511", 2025)).thenReturn(Map.of("7", List.of(missedGame)));

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, registry);
        PlayerGameIngestService.Result r = service.ingest("L1", 2025);

        assertEquals(0, r.gamesStored());
        assertEquals(1, r.absencesStored());
        ArgumentCaptor<PlayerAbsenceRepository.Row> cap = ArgumentCaptor.forClass(PlayerAbsenceRepository.Row.class);
        verify(absences, times(1)).upsert(cap.capture());
        assertEquals("ENTRY_WITHOUT_PLAY", cap.getValue().basis());
        assertEquals("g1", cap.getValue().gameId());
        assertEquals(7, cap.getValue().week());
        verify(games, never()).upsert(any());
    }

    /**
     * An NFL week with {@code gms_active: 1.0} and no {@code gp} produces no
     * {@code player_game} row and an ENTRY_WITHOUT_PLAY absence -- the latent
     * bug regression research R9 describes (McCaffrey's 2024 weeks 2-8).
     */
    @Test
    void nflDnpWeekWithGmsActiveButNoGpProducesAnAbsenceNotAGame() {
        var stats = mock(SleeperPlayerStatsClient.class);
        var leagues = mock(LeagueRepository.class);
        var weekPoints = mock(RosterWeekPointsRepository.class);
        var games = mock(PlayerGameRepository.class);
        var absences = mock(PlayerAbsenceRepository.class);
        var registry = registryFor(Sport.NFL, footballRules());

        when(leagues.bySleeperId("L1")).thenReturn(Optional.of(
                new LeagueRepository.LeagueRow(7L, Sport.NFL, "L1", "Ball Knowers", 2024, 12, List.of(), 0.0, null, null)));
        when(weekPoints.breakdownsFor(7L, 2024)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(3, 8, 0.0, "{\"4034\":0.0}", null)));

        Map<String, Object> dnp = entry(
                "game_id", "g2", "date", "2024-09-22", "week", 3, "team", "SF", "opponent", "NYG",
                "stats", Map.of("gms_active", 1.0));
        when(stats.seasonByWeek("nfl", "4034", 2024)).thenReturn(Map.of("3", List.of(dnp)));

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, registry);
        PlayerGameIngestService.Result r = service.ingest("L1", 2024);

        assertEquals(0, r.gamesStored(), "gms_active alone must not be read as played (research R9)");
        assertEquals(1, r.absencesStored());
        ArgumentCaptor<PlayerAbsenceRepository.Row> cap = ArgumentCaptor.forClass(PlayerAbsenceRepository.Row.class);
        verify(absences).upsert(cap.capture());
        assertEquals("ENTRY_WITHOUT_PLAY", cap.getValue().basis());
    }

    /**
     * An NFL {@code None} week for a player whose team appears with an entry
     * in that week among the walked players produces a TEAM_PLAYED_NO_ENTRY
     * absence (Mahomes week 18).
     */
    @Test
    void nflNoneWeekWhoseTeamPlayedElsewhereProducesTeamPlayedNoEntryAbsence() {
        var stats = mock(SleeperPlayerStatsClient.class);
        var leagues = mock(LeagueRepository.class);
        var weekPoints = mock(RosterWeekPointsRepository.class);
        var games = mock(PlayerGameRepository.class);
        var absences = mock(PlayerAbsenceRepository.class);
        var registry = registryFor(Sport.NFL, footballRules());

        when(leagues.bySleeperId("L1")).thenReturn(Optional.of(
                new LeagueRepository.LeagueRow(7L, Sport.NFL, "L1", "Ball Knowers", 2024, 12, List.of(), 0.0, null, null)));
        when(weekPoints.breakdownsFor(7L, 2024)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(18, 8, 0.0, "{\"4046\":0.0,\"9999\":25.0}", null)));

        // 4046 (Mahomes) has no entry at all in week 18 -- a `None`.
        Map<String, Object> rested = entry(
                "game_id", "g3", "date", "2025-01-04", "week", 17, "team", "KC", "stats", Map.of("gp", 1.0, "pts_ppr", 12.0));
        Map<String, Object> weekNone = null;
        Map<String, List<Map<String, Object>>> mahomesByWeek = new HashMap<>();
        mahomesByWeek.put("17", List.of(rested));
        mahomesByWeek.put("18", null); // Sleeper's `None`
        when(stats.seasonByWeek("nfl", "4046", 2024)).thenReturn(mahomesByWeek);

        // A teammate whose own week-18 entry proves KC played that week.
        Map<String, Object> teammatePlayed = entry(
                "game_id", "g4", "date", "2025-01-04", "week", 18, "team", "KC", "stats", Map.of("gp", 1.0, "pts_ppr", 8.0));
        when(stats.seasonByWeek("nfl", "9999", 2024)).thenReturn(Map.of("18", List.of(teammatePlayed)));

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, registry);
        PlayerGameIngestService.Result r = service.ingest("L1", 2024);

        // 1 played game (week 17 rested-entry, real game) + 1 played game (teammate week 18) = 2 games stored,
        // and 1 absence for Mahomes' week 18 None.
        assertEquals(2, r.gamesStored());
        assertEquals(1, r.absencesStored());
        assertEquals(0, r.weeksUnclassified());
        ArgumentCaptor<PlayerAbsenceRepository.Row> cap = ArgumentCaptor.forClass(PlayerAbsenceRepository.Row.class);
        verify(absences).upsert(cap.capture());
        assertEquals("TEAM_PLAYED_NO_ENTRY", cap.getValue().basis());
        assertEquals(18, cap.getValue().week());
        assertEquals("4046", cap.getValue().playerId());
        assertNull(cap.getValue().gameId(), "a football None week has no game id");
    }

    /**
     * An NFL {@code None} week for a team with no walked entry that week is a
     * bye: no row. It's counted as unclassified only when the player's own
     * team can't be determined at all.
     */
    @Test
    void nflByeWeekWithNoEvidenceOfATeamGameWritesNothing() {
        var stats = mock(SleeperPlayerStatsClient.class);
        var leagues = mock(LeagueRepository.class);
        var weekPoints = mock(RosterWeekPointsRepository.class);
        var games = mock(PlayerGameRepository.class);
        var absences = mock(PlayerAbsenceRepository.class);
        var registry = registryFor(Sport.NFL, footballRules());

        when(leagues.bySleeperId("L1")).thenReturn(Optional.of(
                new LeagueRepository.LeagueRow(7L, Sport.NFL, "L1", "Ball Knowers", 2024, 12, List.of(), 0.0, null, null)));
        when(weekPoints.breakdownsFor(7L, 2024)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(9, 8, 0.0, "{\"4034\":0.0}", null)));

        Map<String, Object> playedWeek10 = entry(
                "game_id", "g5", "date", "2024-11-03", "week", 10, "team", "SF", "stats", Map.of("gp", 1.0, "pts_ppr", 20.0));
        Map<String, List<Map<String, Object>>> byWeek = new HashMap<>();
        byWeek.put("9", null); // bye -- no walked entry anywhere names SF in week 9
        byWeek.put("10", List.of(playedWeek10));
        when(stats.seasonByWeek("nfl", "4034", 2024)).thenReturn(byWeek);

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, registry);
        PlayerGameIngestService.Result r = service.ingest("L1", 2024);

        assertEquals(1, r.gamesStored(), "only the played week-10 game");
        assertEquals(0, r.absencesStored(), "a bye is not an absence");
        assertEquals(0, r.weeksUnclassified(), "the player's team (SF) is known from his week-10 entry");
        // No absence UPSERT for either week; the week-10 played entry does
        // still touch `absences` to clear any stale week-level classification
        // (item 2, coordinator follow-up 2026-09-23) -- a real interaction,
        // just never a write of a new row.
        verify(absences, never()).upsert(any());
    }

    /**
     * A {@code None} week whose player has no other entry anywhere carrying a
     * team is unclassified, not guessed as a bye or an absence.
     *
     * <p>Coordinator follow-up 2026-09-23 (V23): now PERSISTED as its own
     * {@code UNCLASSIFIED} {@code player_absence} row, not just counted --
     * that's what lets the superlatives coverage note be backed by a real
     * number instead of a standing, unbacked caveat.
     */
    @Test
    void aNoneWeekWithNoKnownTeamAnywhereIsUnclassifiedAndPersisted() {
        var stats = mock(SleeperPlayerStatsClient.class);
        var leagues = mock(LeagueRepository.class);
        var weekPoints = mock(RosterWeekPointsRepository.class);
        var games = mock(PlayerGameRepository.class);
        var absences = mock(PlayerAbsenceRepository.class);
        var registry = registryFor(Sport.NFL, footballRules());

        when(leagues.bySleeperId("L1")).thenReturn(Optional.of(
                new LeagueRepository.LeagueRow(7L, Sport.NFL, "L1", "Ball Knowers", 2024, 12, List.of(), 0.0, null, null)));
        when(weekPoints.breakdownsFor(7L, 2024)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(1, 8, 0.0, "{\"unknown\":0.0}", null)));

        Map<String, List<Map<String, Object>>> byWeek = new HashMap<>();
        byWeek.put("1", null); // the only week, and it's None -- no team is ever seen for this player
        when(stats.seasonByWeek("nfl", "unknown", 2024)).thenReturn(byWeek);

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, registry);
        PlayerGameIngestService.Result r = service.ingest("L1", 2024);

        assertEquals(0, r.gamesStored());
        assertEquals(1, r.absencesStored(), "the UNCLASSIFIED row is now stored, not just counted");
        assertEquals(1, r.weeksUnclassified());
        ArgumentCaptor<PlayerAbsenceRepository.Row> cap = ArgumentCaptor.forClass(PlayerAbsenceRepository.Row.class);
        verify(absences, times(1)).upsert(cap.capture());
        assertEquals("UNCLASSIFIED", cap.getValue().basis());
        assertEquals(1, cap.getValue().week());
        assertEquals("unknown", cap.getValue().playerId());
        assertNull(cap.getValue().gameId());
        assertNull(cap.getValue().gameDate());
        assertNull(cap.getValue().team());
        verifyNoInteractions(games);
    }

    // ----------------------------------------------------- item 2 (coordinator follow-up 2026-09-23)

    /**
     * Mid-season, Sleeper lists an upcoming game as {@code stats: {}} (stored
     * as ENTRY_WITHOUT_PLAY by a prior run) and later fills in the box score
     * once it's played. The stale absence row for that exact game must be
     * deleted, or both rows exist and the award charges a game he played.
     */
    @Test
    void aGameThatWasStoredAsAnAbsenceAndIsNowPlayedDeletesTheStaleAbsenceRow() {
        var stats = mock(SleeperPlayerStatsClient.class);
        var leagues = mock(LeagueRepository.class);
        var weekPoints = mock(RosterWeekPointsRepository.class);
        var games = mock(PlayerGameRepository.class);
        var absences = mock(PlayerAbsenceRepository.class);
        var registry = registryFor(Sport.NBA, basketballRules());

        when(leagues.bySleeperId("L1")).thenReturn(Optional.of(
                new LeagueRepository.LeagueRow(7L, Sport.NBA, "L1", "Ball Knowers", 2025, 12, List.of(), 0.0, null, null)));
        when(weekPoints.breakdownsFor(7L, 2025)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(7, 8, 0.0, "{\"1511\":0.0}", null)));

        Map<String, Object> nowPlayed = entry(
                "game_id", "g1", "date", "2025-11-01", "week", 7, "team", "PHI",
                "stats", Map.of("pts", 30.0));
        when(stats.seasonByWeek("nba", "1511", 2025)).thenReturn(Map.of("7", List.of(nowPlayed)));

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, registry);
        service.ingest("L1", 2025);

        verify(games, times(1)).upsert(any());
        verify(absences, times(1)).deleteByGame(Sport.NBA, 2025, "1511", "g1");
        verify(absences, never()).upsert(any());
    }

    /**
     * The mirror case: a game previously stored as PLAYED is now reported as
     * DNP (a stat correction). The stale {@code player_game} row for that
     * exact game must be deleted.
     */
    @Test
    void aGameThatWasStoredAsPlayedAndIsNowDnpDeletesTheStalePlayerGameRow() {
        var stats = mock(SleeperPlayerStatsClient.class);
        var leagues = mock(LeagueRepository.class);
        var weekPoints = mock(RosterWeekPointsRepository.class);
        var games = mock(PlayerGameRepository.class);
        var absences = mock(PlayerAbsenceRepository.class);
        var registry = registryFor(Sport.NFL, footballRules());

        when(leagues.bySleeperId("L1")).thenReturn(Optional.of(
                new LeagueRepository.LeagueRow(7L, Sport.NFL, "L1", "Ball Knowers", 2025, 12, List.of(), 0.0, null, null)));
        when(weekPoints.breakdownsFor(7L, 2025)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(3, 8, 0.0, "{\"4018\":0.0}", null)));

        Map<String, Object> nowDnp = entry(
                "game_id", "g2", "date", "2025-09-21", "week", 3, "team", "HOU", "opponent", "MIN",
                "stats", Map.of("gms_active", 1.0)); // no gp -- DNP
        when(stats.seasonByWeek("nfl", "4018", 2025)).thenReturn(Map.of("3", List.of(nowDnp)));

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, registry);
        service.ingest("L1", 2025);

        verify(absences, times(1)).upsert(any());
        verify(games, times(1)).deleteByGame(Sport.NFL, 2025, "4018", "g2");
        verify(games, never()).upsert(any());
    }

    /**
     * A week previously classified TEAM_PLAYED_NO_ENTRY or UNCLASSIFIED (no
     * {@code game_id}) must be cleared once a later run sees an actual
     * per-entry classification (played or DNP) for that same week -- the
     * neighbour-team inference is strictly worse evidence than a real entry.
     */
    @Test
    void aWeekPreviouslyClassifiedByNeighbourTeamIsClearedWhenAnActualEntryAppears() {
        var stats = mock(SleeperPlayerStatsClient.class);
        var leagues = mock(LeagueRepository.class);
        var weekPoints = mock(RosterWeekPointsRepository.class);
        var games = mock(PlayerGameRepository.class);
        var absences = mock(PlayerAbsenceRepository.class);
        var registry = registryFor(Sport.NFL, footballRules());

        when(leagues.bySleeperId("L1")).thenReturn(Optional.of(
                new LeagueRepository.LeagueRow(7L, Sport.NFL, "L1", "Ball Knowers", 2025, 12, List.of(), 0.0, null, null)));
        when(weekPoints.breakdownsFor(7L, 2025)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(9, 8, 0.0, "{\"4034\":0.0}", null)));

        // Now has a real entry (played) for week 9 -- no None week this time.
        Map<String, Object> nowPlayed = entry(
                "game_id", "g3", "date", "2025-11-02", "week", 9, "team", "SF",
                "stats", Map.of("gp", 1.0, "pts_ppr", 12.0));
        when(stats.seasonByWeek("nfl", "4034", 2025)).thenReturn(Map.of("9", List.of(nowPlayed)));

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, registry);
        service.ingest("L1", 2025);

        verify(absences, times(1)).deleteWeeklyBasis(Sport.NFL, 2025, "4034", 9);
    }

    /**
     * Never write an absence for an entry whose date is today or in the
     * future -- a not-yet-played, merely scheduled game is not a missed one.
     * "Today" is compared in UTC ({@link java.time.ZoneOffset#UTC}), chosen
     * for determinism across whatever timezone this service happens to run
     * in, rather than the host machine's local zone.
     */
    @Test
    void anEntryDatedInTheFutureIsNeverWrittenAsAnAbsence() {
        var stats = mock(SleeperPlayerStatsClient.class);
        var leagues = mock(LeagueRepository.class);
        var weekPoints = mock(RosterWeekPointsRepository.class);
        var games = mock(PlayerGameRepository.class);
        var absences = mock(PlayerAbsenceRepository.class);
        var registry = registryFor(Sport.NBA, basketballRules());

        when(leagues.bySleeperId("L1")).thenReturn(Optional.of(
                new LeagueRepository.LeagueRow(7L, Sport.NBA, "L1", "Ball Knowers", 2099, 12, List.of(), 0.0, null, null)));
        when(weekPoints.breakdownsFor(7L, 2099)).thenReturn(List.of(
                new RosterWeekPointsRepository.WeekBreakdown(1, 8, 0.0, "{\"1511\":0.0}", null)));

        Map<String, Object> scheduledNotYetPlayed = entry(
                "game_id", "future1", "date", "2099-01-01", "week", 1, "team", "PHI", "stats", Map.of());
        when(stats.seasonByWeek("nba", "1511", 2099)).thenReturn(Map.of("1", List.of(scheduledNotYetPlayed)));

        var service = new PlayerGameIngestService(stats, leagues, weekPoints, games, absences, registry);
        PlayerGameIngestService.Result r = service.ingest("L1", 2099);

        assertEquals(0, r.absencesStored(), "a future-dated scheduled game is not a missed game");
        verify(absences, never()).upsert(any());
    }
}
