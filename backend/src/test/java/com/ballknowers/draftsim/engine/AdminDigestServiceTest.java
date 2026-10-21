package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.AdminDigestService.AdminDigest;
import com.ballknowers.draftsim.engine.AdminDigestService.DigestEntry;
import com.ballknowers.draftsim.engine.AdminDigestService.KeyEntry;
import com.ballknowers.draftsim.engine.AdminDigestService.SportDigest;
import com.ballknowers.draftsim.engine.AdminDigestService.TrendingItem;
import com.ballknowers.draftsim.ingest.SportSchedule;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.SportTrendingRepository;
import com.ballknowers.draftsim.store.SportWeekStatsRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The morning digest's assembler (specs/043), pure: hand-built rows, schedule and trending snapshot
 * behind a fake {@link AdminDigestService.Data}, a pinned clock, no database.
 */
class AdminDigestServiceTest {

    // 10:00 Eastern on 10-21 (DST): yesterday is 10-20, and 10-20's cutoff (10-21 10:00 UTC) has passed.
    static final Instant NOW = Instant.parse("2026-10-21T14:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Instant FETCHED = Instant.parse("2026-10-21T11:00:00Z");
    static final LocalDate NIGHT = LocalDate.parse("2026-10-20");
    static final GameScoringService SCORER = new GameScoringService();

    // ---- Fixture ------------------------------------------------------------------------------------

    static class Fake implements AdminDigestService.Data {
        final List<PlayerGameRepository.Row> rows = new ArrayList<>();
        // Keyed by sport and season: a season-blind fake hid the wrong-season fallback (S3, L11).
        final Map<String, List<SportSchedule.Game>> schedule = new HashMap<>();
        Integer current = 2026;
        final Map<String, Player> players = new HashMap<>();
        final Map<Sport, List<BoardRepository.RankedPlayer>> ranks = new HashMap<>();
        final Map<Sport, SportTrendingRepository.Snapshot> trending = new HashMap<>();
        Instant fetchedAt = FETCHED;

        Fake player(Sport sport, String id, String name, Position pos, String team) {
            players.put(id, new Player(Math.abs(id.hashCode()), sport, id, name, List.of(pos), team,
                    "Active", null, 25, 3));
            return this;
        }

        Fake row(Sport sport, int season, int week, String pid, String gameId, LocalDate date,
                 String opp, boolean away, String statsJson) {
            rows.add(new PlayerGameRepository.Row(sport, season, week, pid, gameId, date, opp, away, statsJson));
            return this;
        }

        Fake game(Sport sport, String id, int week, LocalDate date, String home, String away) {
            return game(sport, 2026, id, week, date, home, away);
        }

        Fake game(Sport sport, int season, String id, int week, LocalDate date, String home, String away) {
            schedule.computeIfAbsent(sport + "|" + season, k -> new ArrayList<>())
                    .add(new SportSchedule.Game(id, week, date, "complete", home, away));
            return this;
        }

        Fake rank(Sport sport, String... ids) {
            List<BoardRepository.RankedPlayer> l = new ArrayList<>();
            for (int i = 0; i < ids.length; i++) l.add(new BoardRepository.RankedPlayer(ids[i], i + 1));
            ranks.put(sport, l);
            return this;
        }

        Fake trendingList(Sport sport, String... ids) {
            List<SportTrendingRepository.Entry> es = new ArrayList<>();
            for (int i = 0; i < ids.length; i++) es.add(new SportTrendingRepository.Entry(ids[i], 1000 - i));
            trending.put(sport, new SportTrendingRepository.Snapshot(
                    NOW.minusSeconds(600).atOffset(ZoneOffset.UTC), 24, 2026, null, null, null, es));
            return this;
        }

        @Override public Optional<Integer> currentSeason(Sport sport) { return Optional.ofNullable(current); }

        @Override public List<Integer> seasonsOn(Sport sport, LocalDate date) {
            Set<Integer> s = new LinkedHashSet<>();
            for (PlayerGameRepository.Row r : rows) {
                if (r.sport() == sport && r.gameDate().equals(date)) s.add(r.season());
            }
            return s.stream().sorted(Comparator.reverseOrder()).toList();
        }

        @Override public List<PlayerGameRepository.DateRow> dates(Sport sport, int season) {
            Map<String, Set<String>> games = new HashMap<>();
            Map<String, Integer> weekOf = new HashMap<>();
            for (PlayerGameRepository.Row r : rows) {
                if (r.sport() != sport || r.season() != season) continue;
                String k = r.gameDate() + "|" + r.week();
                games.computeIfAbsent(k, x -> new HashSet<>()).add(r.gameId());
                weekOf.put(k, r.week());
            }
            List<PlayerGameRepository.DateRow> out = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : games.entrySet()) {
                out.add(new PlayerGameRepository.DateRow(LocalDate.parse(e.getKey().split("\\|")[0]),
                        weekOf.get(e.getKey()), e.getValue().size()));
            }
            return out;
        }

        @Override public List<SportWeekStatsRepository.Row> weekStats(Sport sport, int season) {
            Set<Integer> weeks = new HashSet<>();
            for (PlayerGameRepository.Row r : rows) {
                if (r.sport() == sport && r.season() == season) weeks.add(r.week());
            }
            List<SportWeekStatsRepository.Row> out = new ArrayList<>();
            if (fetchedAt != null) {
                for (int w : weeks) out.add(new SportWeekStatsRepository.Row(sport, season, w, fetchedAt, false));
            }
            return out;
        }

        @Override public List<SportSchedule.Game> schedule(Sport sport, int season) {
            return schedule.getOrDefault(sport + "|" + season, List.of());
        }

        @Override public List<PlayerGameRepository.Row> rowsOn(Sport sport, int season, LocalDate date) {
            return rows.stream().filter(r -> r.sport() == sport && r.season() == season
                    && r.gameDate().equals(date)).toList();
        }

        @Override public Optional<SportTrendingRepository.Snapshot> trending(Sport sport) {
            return Optional.ofNullable(trending.get(sport));
        }

        @Override public Map<String, Player> players(Sport sport, Collection<String> ids) {
            Map<String, Player> out = new HashMap<>();
            for (String id : ids) {
                Player p = players.get(id);
                if (p != null && p.sport() == sport) out.put(id, p);
            }
            return out;
        }

        @Override public List<BoardRepository.RankedPlayer> topSearchRank(Sport sport, int limit) {
            return ranks.getOrDefault(sport, List.of()).stream().limit(limit).toList();
        }

        @Override public List<PlayerGameRepository.Row> gamesOf(Sport sport, int season, Collection<String> ids) {
            return rows.stream().filter(r -> r.sport() == sport && r.season() == season
                    && ids.contains(r.sleeperPlayerId())).toList();
        }
    }

    static AdminDigest run(Fake f, LocalDate date) {
        return new AdminDigestService(f, SCORER, CLOCK).digest(date);
    }

    static SportDigest nba(AdminDigest d) { return d.sports().get(0); }

    static SportDigest nfl(AdminDigest d) { return d.sports().get(1); }

    static String stats(double pts, String extra) {
        return "{\"pts_std\": " + pts + (extra.isEmpty() ? "" : ", " + extra) + "}";
    }

    /** A small NBA night: DEN-GSW and OKC-SAS on 10-20, plus a TEAM_ total row. */
    static Fake nbaNight() {
        Fake f = new Fake();
        f.player(Sport.NBA, "jok", "Nikola Jokić", Position.C, "DEN")
                .player(Sport.NBA, "sga", "Shai G-A", Position.PG, "OKC")
                .player(Sport.NBA, "gsw1", "Gsw One", Position.SG, "GSW")
                .player(Sport.NBA, "sas1", "Sas One", Position.SF, "SAS")
                .player(Sport.NBA, "dnp1", "Dnp One", Position.PF, "DEN")
                .player(Sport.NBA, "bye1", "Bye One", Position.PF, "LAL");
        f.game(Sport.NBA, "g1", 1, NIGHT, "DEN", "GSW").game(Sport.NBA, "g2", 1, NIGHT, "OKC", "SAS");
        f.row(Sport.NBA, 2026, 1, "jok", "g1", NIGHT, "GSW", false,
                stats(62.5, "\"pts\": 34, \"reb\": 12, \"ast\": 8, \"stl\": 3, \"tov\": 0"));
        f.row(Sport.NBA, 2026, 1, "gsw1", "g1", NIGHT, "DEN", true, stats(30, "\"pts\": 20"));
        // Played and scored exactly 0: Sleeper leaves pts_std out (F1).
        f.row(Sport.NBA, 2026, 1, "sga", "g2", NIGHT, "SAS", false, "{\"sp\": 81, \"plus_minus\": 0}");
        f.row(Sport.NBA, 2026, 1, "sas1", "g2", NIGHT, "OKC", true, stats(30, ""));
        f.row(Sport.NBA, 2026, 1, "TEAM_DEN", "g1", NIGHT, "GSW", false, stats(155, ""));
        return f;
    }

    // ---- T014 ----------------------------------------------------------------------------------------

    @Test
    void aPlayedRowWithNoPointsFieldScoresZeroAndIsPlayedNotDnp() {
        Fake f = nbaNight().trendingList(Sport.NBA, "sga");
        SportDigest s = nba(run(f, null));
        assertEquals("READY", s.status());
        TrendingItem it = s.trending().entries().getFirst();
        assertEquals("PLAYED", it.outcome(), "he played; a missing pts_std is 0, not DNP");
        assertEquals(0.0, it.entry().points());
    }

    @Test
    void trendingKeepsSleepersOrderAndClassifiesNoGame() {
        Fake f = nbaNight().trendingList(Sport.NBA, "jok", "ghost", "sga", "bye1", "dnp1");
        SportDigest s = nba(run(f, null));
        List<TrendingItem> es = s.trending().entries();
        assertEquals(List.of("jok", "sga", "bye1", "dnp1"), es.stream().map(e -> e.entry().playerId()).toList());
        assertEquals(List.of(1, 3, 4, 5), es.stream().map(TrendingItem::rank).toList(), "true Sleeper ranks");
        assertEquals(1, s.trending().omittedUnknownPlayers());
        assertEquals("PLAYED", es.get(0).outcome());
        assertEquals(62.5, es.get(0).entry().points());
        assertEquals(34L, es.get(0).entry().line().get("pts"));
        assertFalse(es.get(0).entry().line().containsKey("tov"), "headline keys only");
        assertEquals("NO_GAME", es.get(2).outcome());
        assertNull(es.get(2).entry().points(), "a non-game is never a zero");
        assertEquals("DID_NOT_PLAY", es.get(3).outcome());
    }

    @Test
    void yesterdayScheduledWithNoRowsIsNotFetchedNeverNoGames() {
        Fake f = new Fake();
        f.game(Sport.NBA, "g1", 1, NIGHT, "DEN", "GSW");
        f.row(Sport.NBA, 2026, 1, "old", "g0", LocalDate.parse("2026-10-18"), "X", true, stats(10, ""));
        f.fetchedAt = Instant.parse("2026-10-20T23:48:00Z");
        SportDigest s = nba(run(f, null));
        assertEquals("NOT_FETCHED", s.status());
        assertEquals(Instant.parse("2026-10-20T23:48:00Z"), s.statsFetchedAt());
        assertTrue(s.topPerformers().isEmpty());
        assertTrue(s.keyPlayers().isEmpty());
    }

    @Test
    void yesterdayUnscheduledIsNoGamesWithTheNextGameDate() {
        Fake f = new Fake();
        f.game(Sport.NBA, "g1", 1, LocalDate.parse("2026-10-18"), "DEN", "GSW");
        f.game(Sport.NBA, "g2", 1, LocalDate.parse("2026-10-23"), "DEN", "GSW");
        f.game(Sport.NBA, "g3", 1, LocalDate.parse("2026-10-24"), "DEN", "GSW");
        f.row(Sport.NBA, 2026, 1, "old", "g1", LocalDate.parse("2026-10-18"), "X", true, stats(10, ""));
        SportDigest s = nba(run(f, null));
        assertEquals("NO_GAMES_LAST_NIGHT", s.status());
        assertEquals(LocalDate.parse("2026-10-23"), s.nextGameDate());
        assertEquals(LocalDate.parse("2026-10-18"), s.lastSlateDate());
        assertTrue(s.topPerformers().isEmpty());
        assertNull(s.date());
    }

    @Test
    void anExplicitDateHasNoHistoricalTrending() {
        Fake f = nbaNight().trendingList(Sport.NBA, "jok");
        SportDigest s = nba(run(f, NIGHT));
        assertEquals("READY", s.status());
        assertEquals("TRENDING_NOT_HISTORICAL", s.trending().status());
        assertTrue(s.trending().entries().isEmpty());
        assertEquals(2, s.gamesCount());
        assertEquals(1, s.week());
    }

    @Test
    void anExplicitDateWithNoRowsAndNoGamesSaysSo() {
        SportDigest s = nba(run(nbaNight(), LocalDate.parse("2026-10-05")));
        assertEquals("NO_GAMES_ON_DATE", s.status());
    }

    // ---- Code-review fixes (S1, S3, L1, L2) --------------------------------------------------------

    @Test
    void aGameTonightIsTheNextGameInDefaultModeButNotAfterAnExplicitDate() {
        Fake f = new Fake();
        LocalDate today = LocalDate.parse("2026-10-21"); // NOW is the morning of 10-21 ET
        f.game(Sport.NBA, "g1", 1, LocalDate.parse("2026-10-18"), "DEN", "GSW");
        f.game(Sport.NBA, "g2", 1, today, "DEN", "GSW");
        f.game(Sport.NBA, "g3", 1, LocalDate.parse("2026-10-23"), "DEN", "GSW");
        f.row(Sport.NBA, 2026, 1, "old", "g1", LocalDate.parse("2026-10-18"), "X", true, stats(10, ""));
        SportDigest s = nba(run(f, null));
        assertEquals("NO_GAMES_LAST_NIGHT", s.status());
        assertEquals(today, s.nextGameDate(), "tonight's game must not be skipped");
        // An explicit date keeps "strictly after that date".
        SportDigest onToday = nba(run(f, today));
        assertEquals(LocalDate.parse("2026-10-23"), onToday.nextGameDate());
    }

    @Test
    void aPastDateWithNoRowsUsesTheSeasonWhoseScheduleCoversIt() {
        Fake f = new Fake();
        LocalDate march = LocalDate.parse("2026-03-02");
        f.game(Sport.NBA, 2025, "o1", 20, march, "DEN", "GSW");                       // 2025 season, never fetched
        f.game(Sport.NBA, 2025, "o2", 21, LocalDate.parse("2026-03-04"), "DEN", "GSW");
        f.game(Sport.NBA, 2026, "n1", 1, LocalDate.parse("2026-10-22"), "DEN", "GSW");
        SportDigest listed = nba(run(f, march));
        assertEquals("NOT_FETCHED", listed.status(), "games listed in the 2025 schedule, nothing stored");
        assertEquals(2025, listed.season());
        // An off day inside the 2025 span: no games, and the next game is the 2025 one, not 2026's.
        SportDigest off = nba(run(f, LocalDate.parse("2026-03-03")));
        assertEquals("NO_GAMES_ON_DATE", off.status());
        assertEquals(LocalDate.parse("2026-03-04"), off.nextGameDate());
        // The off-season gap before the current opener: current season's first game is next.
        SportDigest gap = nba(run(f, LocalDate.parse("2026-07-01")));
        assertEquals("NO_GAMES_ON_DATE", gap.status());
        assertEquals(LocalDate.parse("2026-10-22"), gap.nextGameDate());
    }

    @Test
    void aPastDateNoKnownScheduleCoversGivesNoNextGameRatherThanAnotherSeasons() {
        Fake f = new Fake();
        f.game(Sport.NFL, 2026, "n1", 1, LocalDate.parse("2026-09-09"), "AAA", "BBB");
        SportDigest s = nfl(run(f, LocalDate.parse("2025-11-05"))); // no 2025 schedule stored at all
        assertEquals("NO_GAMES_ON_DATE", s.status());
        assertNull(s.nextGameDate(), "2026's opener is not the 'next' game after a 2025 date");
    }

    @Test
    void aFutureDateIsNotYetPlayedNotNotFetched() {
        Fake f = nbaNight();
        f.game(Sport.NBA, "gf", 5, LocalDate.parse("2026-12-01"), "DEN", "GSW"); // scheduled, but in the future
        SportDigest s = nba(run(f, LocalDate.parse("2026-12-01")));
        assertEquals("DATE_IN_FUTURE", s.status());
        assertTrue(s.topPerformers().isEmpty());
    }

    @Test
    void withNoSeasonTrendingStillRidesAlongInDefaultMode() {
        Fake f = nbaNight().trendingList(Sport.NBA, "jok");
        f.current = null;
        SportDigest s = nba(run(f, null));
        assertEquals("NO_SEASON", s.status());
        assertNotNull(s.trending());
        assertEquals("OK", s.trending().status());
        assertEquals("NO_PERIOD", s.trending().entries().getFirst().outcome());
    }

    @Test
    void anIncompleteNightIsNotReadyAndTrendingStillRidesAlong() {
        Fake f = nbaNight().trendingList(Sport.NBA, "jok");
        f.fetchedAt = Instant.parse("2026-10-20T23:48:00Z"); // before the 10-21 10:00 UTC cutoff
        SportDigest s = nba(run(f, null));
        assertEquals("NIGHT_INCOMPLETE", s.status());
        assertTrue(s.topPerformers().isEmpty());
        assertEquals("OK", s.trending().status());
        assertEquals("NO_PERIOD", s.trending().entries().getFirst().outcome(), "F16");
        assertNull(s.trending().entries().getFirst().entry().points());
    }

    @Test
    void neverFetchedTrendingIsReportedAsSuch() {
        SportDigest s = nba(run(nbaNight(), null));
        assertEquals("NEVER_FETCHED", s.trending().status());
    }

    @Test
    void aTeamIdNeverAppears() {
        Fake f = nbaNight().trendingList(Sport.NBA, "TEAM_DEN", "jok");
        // Even if a TEAM_ id resolved to a player, its rows are excluded before scoring.
        f.players.put("TEAM_DEN", new Player(1, Sport.NBA, "TEAM_DEN", "Denver", List.of(Position.C), "DEN",
                "Active", null, null, null));
        SportDigest s = nba(run(f, null));
        assertTrue(s.topPerformers().stream().noneMatch(e -> e.playerId().startsWith("TEAM_")));
        assertEquals("jok", s.topPerformers().getFirst().playerId(), "the 155 team total must not top the list");
    }

    // ---- T021 ----------------------------------------------------------------------------------------

    @Test
    void topPerformersAreOrderedByPointsThenPlayerId() {
        Fake f = nbaNight();
        f.player(Sport.NBA, "p_b", "B", Position.PG, "DEN").player(Sport.NBA, "p_a", "A", Position.PG, "GSW");
        // Inserted b before a, equal points: the id tie-break must put a first.
        f.row(Sport.NBA, 2026, 1, "p_b", "g1", NIGHT, "GSW", false, stats(30, ""));
        f.row(Sport.NBA, 2026, 1, "p_a", "g1", NIGHT, "DEN", true, stats(30, ""));
        List<DigestEntry> top = nba(run(f, null)).topPerformers();
        assertEquals(List.of("jok", "gsw1", "p_a", "p_b", "sas1", "sga"),
                top.stream().map(DigestEntry::playerId).toList());
        List<Double> pts = top.stream().map(DigestEntry::points).toList();
        assertEquals(pts, pts.stream().sorted(Comparator.reverseOrder()).toList());
    }

    @Test
    void topPerformersAreCappedAtTen() {
        Fake f = new Fake();
        f.game(Sport.NBA, "g1", 1, NIGHT, "DEN", "GSW");
        for (int i = 0; i < 14; i++) {
            String id = String.format("p%02d", i);
            f.player(Sport.NBA, id, id, Position.PG, "DEN");
            f.row(Sport.NBA, 2026, 1, id, "g1", NIGHT, "GSW", false, stats(i, ""));
        }
        List<DigestEntry> top = nba(run(f, null)).topPerformers();
        assertEquals(10, top.size());
        assertEquals("p13", top.getFirst().playerId());
    }

    /** jok: 5 earlier games at 50 (+ a game after the night worth 200 that must not count). */
    static Fake keyFixture() {
        Fake f = nbaNight();
        f.player(Sport.NBA, "newbie", "New Guy", Position.PG, "OKC")
                .rank(Sport.NBA, "jok", "sga", "dnp1", "bye1", "newbie");
        f.row(Sport.NBA, 2026, 1, "newbie", "g2", NIGHT, "SAS", false, stats(12, ""));
        for (int i = 1; i <= 5; i++) {
            f.row(Sport.NBA, 2026, 1, "jok", "e" + i, NIGHT.minusDays(i), "X", true, stats(50, ""));
        }
        f.row(Sport.NBA, 2026, 1, "jok", "later", NIGHT.plusDays(2), "X", true, stats(200, ""));
        // sga: 3 games this season (too few) and 10 last season at 20.
        for (int i = 1; i <= 3; i++) {
            f.row(Sport.NBA, 2026, 1, "sga", "s" + i, NIGHT.minusDays(i), "X", true, stats(99, ""));
        }
        for (int i = 1; i <= 10; i++) {
            f.row(Sport.NBA, 2025, 20, "sga", "l" + i, LocalDate.parse("2026-01-0" + Math.min(i, 9)), "X", true,
                    stats(20, ""));
        }
        return f;
    }

    @Test
    void theExpectationUsesOnlyEarlierGames() {
        KeyEntry jok = nba(run(keyFixture(), null)).keyPlayers().getFirst();
        assertEquals("jok", jok.entry().playerId());
        assertEquals("THIS_SEASON", jok.expectationBasis());
        assertEquals(50.0, jok.expectation(), 1e-9, "not the 62.5 of the night itself, not the 200 after it");
        assertEquals("ABOVE", jok.verdict());
        assertTrue(jok.played());
    }

    @Test
    void fewerThanFiveGamesThisSeasonFallsBackToLastSeason() {
        KeyEntry sga = nba(run(keyFixture(), null)).keyPlayers().get(1);
        assertEquals("sga", sga.entry().playerId());
        assertEquals("LAST_SEASON", sga.expectationBasis());
        assertEquals(20.0, sga.expectation(), 1e-9);
        assertEquals("BELOW", sga.verdict(), "scored 0 against 20");
    }

    @Test
    void noHistoryIsNoBaselineAndAnIdleTeamsPlayerIsLeftOut() {
        List<KeyEntry> keys = nba(run(keyFixture(), null)).keyPlayers();
        assertEquals(List.of("jok", "sga", "dnp1", "newbie"),
                keys.stream().map(k -> k.entry().playerId()).toList(), "bye1's team had no game");
        KeyEntry dnp = keys.get(2);
        assertFalse(dnp.played());
        assertEquals("DNP", dnp.verdict());
        assertNull(dnp.entry().points());
        assertEquals("GSW", dnp.entry().opponent(), "DEN was home to GSW");
        assertEquals(Boolean.FALSE, dnp.entry().isAway());
        KeyEntry nb = keys.get(3);
        assertEquals("NO_BASELINE", nb.verdict());
        assertEquals("NONE", nb.expectationBasis());
        assertNull(nb.expectation());
        assertEquals(List.of(1, 2, 3, 5), keys.stream().map(KeyEntry::rank).toList());
    }

    @Test
    void onAnExplicitDateOnlyRowsCountSoATradedPlayerComesFromHisRow() {
        Fake f = keyFixture();
        // ant now plays for MIA (no game) but has a row for his old team on the night; dnp1 has no row.
        f.player(Sport.NBA, "ant", "Giannis", Position.PF, "MIA");
        f.rank(Sport.NBA, "jok", "ant", "dnp1");
        f.row(Sport.NBA, 2026, 1, "ant", "g1", NIGHT, "DEN", true, stats(40, ""));
        List<KeyEntry> keys = nba(run(f, NIGHT)).keyPlayers();
        assertEquals(List.of("jok", "ant"), keys.stream().map(k -> k.entry().playerId()).toList(),
                "dnp1 has no row on a past date, and his current team is not trusted");
        assertTrue(keys.get(1).played());
        // In default mode the same list keeps dnp1 as a DNP via the schedule.
        List<KeyEntry> live = nba(run(f, null)).keyPlayers();
        assertTrue(live.stream().anyMatch(k -> k.entry().playerId().equals("dnp1") && !k.played()));
        assertTrue(live.stream().noneMatch(k -> k.entry().playerId().equals("ant") && !k.played()));
    }

    // ---- T025 / football ---------------------------------------------------------------------------------

    static final LocalDate MNF = LocalDate.parse("2026-10-19");

    static Fake nflNight() {
        Fake f = new Fake();
        f.player(Sport.NFL, "qb1", "Qb One", Position.QB, "AAA").player(Sport.NFL, "qb2", "Qb Two", Position.QB, "BBB")
                .player(Sport.NFL, "rb1", "Rb One", Position.RB, "AAA").player(Sport.NFL, "wr1", "Wr One", Position.WR, "BBB")
                .player(Sport.NFL, "k1", "Kicker", Position.K, "AAA").player(Sport.NFL, "def1", "Defense", Position.DEF, "BBB");
        f.game(Sport.NFL, "n1", 6, MNF, "AAA", "BBB");
        Object[][] games = {{"qb1", 25.0, "BBB"}, {"qb2", 30.0, "AAA"}, {"rb1", 20.0, "BBB"},
                {"wr1", 18.0, "AAA"}, {"k1", 40.0, "BBB"}, {"def1", 50.0, "AAA"}};
        for (Object[] r : games) {
            f.row(Sport.NFL, 2026, 6, (String) r[0], "n1", MNF, (String) r[2], true,
                    "{\"pts_ppr\": " + r[1] + ", \"pass_yd\": 300, \"pass_int\": 0}");
        }
        return f;
    }

    @Test
    void positionLeadersArePickedPerPositionAndSkipEmptyOnes() {
        SportDigest s = nfl(run(nflNight(), MNF));
        assertEquals("READY", s.status());
        assertEquals(6, s.week(), "the week comes from the rows themselves");
        assertEquals("Sleeper PPR", s.pointsBasis());
        assertEquals(List.of("qb2", "rb1", "wr1"), s.positionLeaders().stream().map(DigestEntry::playerId).toList(),
                "no TE rows, so no TE entry");
    }

    @Test
    void anNflPlayerWithNoPositionIsNeitherSkillNorLeader() {
        Fake f = nflNight();
        f.players.put("lb1", new Player(77, Sport.NFL, "lb1", "Unmapped", List.of(), "AAA", "Active", null, 25, 3));
        f.row(Sport.NFL, 2026, 6, "lb1", "n1", MNF, "BBB", true, "{\"pts_ppr\": 99.0}");
        SportDigest s = nfl(run(f, MNF));
        assertTrue(s.topPerformers().stream().noneMatch(e -> e.playerId().equals("lb1")));
        assertTrue(s.positionLeaders().stream().noneMatch(e -> e.playerId().equals("lb1")));
    }

    @Test
    void nflTopPerformersNeverIncludeKickersOrDefenses() {
        SportDigest s = nfl(run(nflNight(), MNF));
        assertEquals(List.of("qb2", "qb1", "rb1", "wr1"),
                s.topPerformers().stream().map(DigestEntry::playerId).toList());
        assertEquals(300L, s.topPerformers().getFirst().line().get("pass_yd"));
        assertFalse(s.topPerformers().getFirst().line().containsKey("pass_int"), "zero stats left out");
    }

    @Test
    void positionLeadersAreNullForBasketballAndEmptyWhenFootballIsNotReady() {
        AdminDigest d = run(nflNight(), MNF);
        assertNull(nba(d).positionLeaders());
        SportDigest idle = nfl(run(nflNight(), LocalDate.parse("2026-10-20")));
        assertEquals(List.of(), idle.positionLeaders());
        assertEquals("NO_GAMES_ON_DATE", idle.status());
    }

    @Test
    void sportsComeBasketballFirstAndYesterdayIsEasternYesterday() {
        AdminDigest d = run(nbaNight(), null);
        assertEquals("nba", d.sports().get(0).sport());
        assertEquals("nfl", d.sports().get(1).sport());
        assertEquals(NIGHT, d.yesterday());
        assertEquals("Sleeper default points", nba(d).pointsBasis());
    }
}
