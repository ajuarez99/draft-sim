package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LineupCheck.BenchOption;
import com.ballknowers.draftsim.engine.LineupCheck.FpAvg;
import com.ballknowers.draftsim.engine.LineupCheck.LineupFlag;
import com.ballknowers.draftsim.engine.LineupCheck.Roster;
import com.ballknowers.draftsim.sport.SportRules;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * specs/042 T013. The rule is pure, so every case is a handful of hand-built players. The ordering test is
 * the one this repo's lessons (class 1) ask for: the direction of a ranking is pinned by asserting who comes
 * first, not that a list came back.
 */
class LineupCheckTest {

    private static Player p(String id, String name, Position pos, String status, String injury) {
        return new Player(id.hashCode(), Sport.NBA, id, name, List.of(pos), "ATL", status, injury, null, null);
    }

    private static Player p(String id, String name, Position pos) {
        return p(id, name, pos, "ACT", null);
    }

    /** Eligibility as the real rules spell it for the slots these tests use; unknown slot names answer false. */
    private static SportRules rules(boolean basketball) {
        SportRules rules = mock(SportRules.class);
        when(rules.playsMultipleGamesPerScoringPeriod()).thenReturn(basketball);
        when(rules.sport()).thenReturn(basketball ? Sport.NBA : Sport.NFL);
        when(rules.isEligible(any(), anyString())).thenAnswer(inv -> {
            Player player = inv.getArgument(0);
            String slot = inv.getArgument(1);
            return switch (slot) {
                case "BN" -> true;
                case "UTIL" -> !player.positions().isEmpty();
                case "G" -> player.positions().contains(Position.PG) || player.positions().contains(Position.SG);
                case "PG", "SG", "C", "QB", "RB" -> player.positions().stream().anyMatch(x -> x.name().equals(slot));
                default -> false;
            };
        });
        when(rules.isSuspended(any())).thenAnswer(inv -> "SUS".equalsIgnoreCase(((Player) inv.getArgument(0)).status()));
        return rules;
    }

    private static Map<String, Player> byId(Player... players) {
        Map<String, Player> m = new LinkedHashMap<>();
        for (Player pl : players) m.put(pl.sleeperId(), pl);
        return m;
    }

    private static LineupCheck.Schedule games(Predicate<Player> has) {
        return new LineupCheck.Schedule() {
            @Override
            public boolean hasGameInWeek(Player player) {
                return has.test(player);
            }

            @Override
            public int gamesLeftInWeek(Player player) {
                return has.test(player) ? 2 : 0;
            }
        };
    }

    private static final LineupCheck.Schedule EVERYONE_PLAYS = games(pl -> true);

    @Test
    void aHigherFpPerGameBenchPlayerSortsFirstAndAMissingAverageSortsLast() {
        Player out = p("s1", "Starter", Position.PG, "ACT", "Out");
        Player low = p("b1", "Aaron Low", Position.PG);
        Player high = p("b2", "Zed High", Position.PG);
        Player none = p("b3", "Ann None", Position.PG);
        Map<String, FpAvg> fp = new HashMap<>();
        fp.put("b1", new FpAvg(18.0, 2025, 60));
        fp.put("b2", new FpAvg(31.4, 2025, 71));
        fp.put("b3", new FpAvg(25.0, 2025, 40));

        List<LineupFlag> flags = LineupCheck.flags(
                new Roster(List.of("s1"), List.of("s1", "b1", "b2", "b3"), List.of(), List.of()),
                List.of("PG"), byId(out, low, high, none), rules(true), EVERYONE_PLAYS, fp);

        assertEquals(1, flags.size());
        LineupFlag flag = flags.get(0);
        assertEquals(LineupCheck.OUT, flag.reason());
        assertEquals(LineupCheck.FP_PER_GAME, flag.optionsOrder());
        // Zed (31.4) is before Ann (25.0) and Aaron (18.0) although Aaron is first alphabetically.
        assertEquals(List.of("Zed High", "Ann None", "Aaron Low"),
                flag.options().stream().map(o -> o.player().name()).toList());
        BenchOption first = flag.options().get(0);
        assertEquals(31.4, first.fpPerGame());
        assertEquals(2025, first.fpSeason());
        assertEquals(71, first.fpGames());
        assertEquals(2, first.gamesLeft());
        assertNull(first.injuryStatus());
    }

    @Test
    void oneOptionWithoutAnAverageMeansNameOrderNotAnFpOrderThatPutsHimLast() {
        Player out = p("s1", "Starter", Position.PG, "ACT", "Out");
        Player star = p("b1", "Zed Star", Position.PG);      // his team hasn't played: no average yet
        Player reserve = p("b2", "Aaron Reserve", Position.PG);
        Map<String, FpAvg> fp = new HashMap<>();
        fp.put("b2", new FpAvg(14.0, 2026, 1));

        List<LineupFlag> flags = LineupCheck.flags(
                new Roster(List.of("s1"), List.of("s1", "b1", "b2"), List.of(), List.of()),
                List.of("PG"), byId(out, star, reserve), rules(true), EVERYONE_PLAYS, fp);

        assertEquals(LineupCheck.NAME, flags.get(0).optionsOrder());
        assertEquals(List.of("Aaron Reserve", "Zed Star"), flags.get(0).options().stream().map(o -> o.player().name()).toList());
    }

    @Test
    void averagesFromDifferentSeasonsAreNotComparedEither() {
        Player out = p("s1", "Starter", Position.PG, "ACT", "Out");
        Player a = p("b1", "Aaron", Position.PG);
        Player z = p("b2", "Zed", Position.PG);
        Map<String, FpAvg> fp = new HashMap<>();
        fp.put("b1", new FpAvg(14.0, 2026, 1));
        fp.put("b2", new FpAvg(31.0, 2025, 70));

        List<LineupFlag> flags = LineupCheck.flags(
                new Roster(List.of("s1"), List.of("s1", "b1", "b2"), List.of(), List.of()),
                List.of("PG"), byId(out, a, z), rules(true), EVERYONE_PLAYS, fp);

        assertEquals(LineupCheck.NAME, flags.get(0).optionsOrder());
    }

    @Test
    void anNbaSuspensionWithNoInjuryTagIsOut() {
        Player sus = p("s1", "Suspended", Position.PG, "SUS", null);
        List<LineupFlag> flags = LineupCheck.flags(new Roster(List.of("s1"), List.of("s1"), List.of(), List.of()),
                List.of("PG"), byId(sus), rules(true), EVERYONE_PLAYS, Map.of());
        assertEquals(1, flags.size());
        assertEquals(LineupCheck.OUT, flags.get(0).reason());
        assertNull(flags.get(0).injuryStatus());
        assertEquals("SUS", flags.get(0).playerStatus());
    }

    @Test
    void anyOtherInjuryTagIsListedWithTheRawValue() {
        Player dtd = p("s1", "Day To Day", Position.PG, "ACT", "DTD");
        Player na = p("s2", "Unexplained", Position.SG, "ACT", "NA");
        List<LineupFlag> flags = LineupCheck.flags(
                new Roster(List.of("s1", "s2"), List.of("s1", "s2"), List.of(), List.of()),
                List.of("PG", "SG"), byId(dtd, na), rules(true), EVERYONE_PLAYS, Map.of());
        assertEquals(List.of(LineupCheck.LISTED, LineupCheck.LISTED), flags.stream().map(LineupFlag::reason).toList());
        assertEquals(List.of("DTD", "NA"), flags.stream().map(LineupFlag::injuryStatus).toList());
    }

    @Test
    void oneReasonPerStarterInTheOrderOutListedNoGame() {
        Player outAndIdle = p("s1", "Out And Idle", Position.PG, "ACT", "Out");
        Player listedAndIdle = p("s2", "Listed And Idle", Position.SG, "ACT", "GTD");
        Player justIdle = p("s3", "Just Idle", Position.C);
        LineupCheck.Schedule nobody = games(pl -> false);
        List<LineupFlag> flags = LineupCheck.flags(
                new Roster(List.of("s1", "s2", "s3"), List.of("s1", "s2", "s3"), List.of(), List.of()),
                List.of("PG", "SG", "C"), byId(outAndIdle, listedAndIdle, justIdle), rules(true), nobody, Map.of());
        assertEquals(List.of(LineupCheck.OUT, LineupCheck.LISTED, LineupCheck.NO_GAME_IN_WEEK),
                flags.stream().map(LineupFlag::reason).toList());
    }

    @Test
    void noScheduleMeansNoGameFlagAtAll() {
        Player healthy = p("s1", "Healthy", Position.PG);
        List<LineupFlag> flags = LineupCheck.flags(new Roster(List.of("s1"), List.of("s1"), List.of(), List.of()),
                List.of("PG"), byId(healthy), rules(true), null, Map.of());
        assertTrue(flags.isEmpty());
    }

    @Test
    void aSuperFlexSlotIsUnsupportedNotEmpty() {
        Player out = p("s1", "Starter", Position.PG, "ACT", "Out");
        Player bench = p("b1", "Bench", Position.PG);
        List<LineupFlag> flags = LineupCheck.flags(new Roster(List.of("s1"), List.of("s1", "b1"), List.of(), List.of()),
                List.of("SUPER_FLEX"), byId(out, bench), rules(true), EVERYONE_PLAYS, Map.of());
        assertEquals(1, flags.size());
        assertEquals(LineupCheck.SLOT_UNSUPPORTED, flags.get(0).optionsOrder());
        assertTrue(flags.get(0).options().isEmpty());
    }

    @Test
    void taxiAndReserveAreNotOptions() {
        Player out = p("s1", "Starter", Position.PG, "ACT", "Out");
        Player taxi = p("t1", "On Taxi", Position.PG);
        Player ir = p("r1", "On Reserve", Position.PG);
        Player bench = p("b1", "On Bench", Position.PG);
        List<LineupFlag> flags = LineupCheck.flags(
                new Roster(List.of("s1"), List.of("s1", "t1", "r1", "b1"), List.of("r1"), List.of("t1")),
                List.of("PG"), byId(out, taxi, ir, bench), rules(true), EVERYONE_PLAYS, Map.of());
        assertEquals(List.of("On Bench"), flags.get(0).options().stream().map(o -> o.player().name()).toList());
    }

    @Test
    void aBenchPlayerWhoIsOutOrHasNoGameIsNotAnOptionButAListedOneStaysWithHisTag() {
        Player out = p("s1", "Starter", Position.PG, "ACT", "Out");
        Player benchOut = p("b1", "Bench Out", Position.PG, "ACT", "Out");
        Player benchListed = p("b2", "Bench Listed", Position.PG, "ACT", "GTD");
        Player benchIdle = p("b3", "Bench Idle", Position.PG);
        Player benchOk = p("b4", "Bench Ok", Position.PG);
        Player benchSus = p("b5", "Bench Sus", Position.PG, "SUS", null);
        LineupCheck.Schedule idleTeam = games(pl -> !pl.sleeperId().equals("b3"));
        List<LineupFlag> flags = LineupCheck.flags(
                new Roster(List.of("s1"), List.of("s1", "b1", "b2", "b3", "b4", "b5"), List.of(), List.of()),
                List.of("PG"), byId(out, benchOut, benchListed, benchIdle, benchOk, benchSus), rules(true), idleTeam, Map.of());
        List<BenchOption> options = flags.get(0).options();
        assertEquals(List.of("Bench Listed", "Bench Ok"), options.stream().map(o -> o.player().name()).toList());
        assertEquals("GTD", options.get(0).injuryStatus());
        assertNull(options.get(1).injuryStatus());
    }

    @Test
    void aListedStarterWhoseOnlyBenchPlayerIsDtdStillSeesHim() {
        Player out = p("s1", "Starter", Position.PG, "ACT", "Out");
        Player dtd = p("b1", "Day To Day", Position.PG, "ACT", "DTD");
        List<LineupFlag> flags = LineupCheck.flags(
                new Roster(List.of("s1"), List.of("s1", "b1"), List.of(), List.of()),
                List.of("PG"), byId(out, dtd), rules(true), EVERYONE_PLAYS, Map.of());
        assertEquals(1, flags.get(0).options().size());
        assertEquals("DTD", flags.get(0).options().get(0).injuryStatus());
    }

    @Test
    void onlyPlayersEligibleForTheSlotAreOptions() {
        Player out = p("s1", "Starter", Position.C, "ACT", "Out");
        Player guard = p("b1", "Guard", Position.PG);
        Player center = p("b2", "Center", Position.C);
        List<LineupFlag> flags = LineupCheck.flags(new Roster(List.of("s1"), List.of("s1", "b1", "b2"), List.of(), List.of()),
                List.of("C"), byId(out, guard, center), rules(true), EVERYONE_PLAYS, Map.of());
        assertEquals(List.of("Center"), flags.get(0).options().stream().map(o -> o.player().name()).toList());
    }

    @Test
    void anEmptySlotIsFlaggedWithItsOptionsAndNoPlayer() {
        Player bench = p("b1", "Bench", Position.PG);
        List<LineupFlag> flags = LineupCheck.flags(new Roster(List.of("0"), List.of("b1"), List.of(), List.of()),
                List.of("PG"), byId(bench), rules(true), EVERYONE_PLAYS, Map.of());
        assertEquals(1, flags.size());
        assertEquals(LineupCheck.EMPTY_SLOT, flags.get(0).reason());
        assertNull(flags.get(0).player());
        assertEquals("PG", flags.get(0).slot());
        assertEquals(1, flags.get(0).options().size());
    }

    @Test
    void noFlagsIsAnEmptyList() {
        Player a = p("s1", "A", Position.PG);
        Player b = p("s2", "B", Position.SG);
        List<LineupFlag> flags = LineupCheck.flags(new Roster(List.of("s1", "s2"), List.of("s1", "s2"), List.of(), List.of()),
                List.of("PG", "SG"), byId(a, b), rules(true), EVERYONE_PLAYS, Map.of());
        assertTrue(flags.isEmpty());
    }

    @Test
    void footballOptionsAreAlphabeticalAndSaySoEvenWhenAveragesExist() {
        Player out = new Player(1, Sport.NFL, "s1", "Starter", List.of(Position.QB), "DAL", "Active", "Out", null, null);
        Player z = new Player(2, Sport.NFL, "b1", "Zed", List.of(Position.QB), "DAL", "Active", null, null, null);
        Player a = new Player(3, Sport.NFL, "b2", "Abe", List.of(Position.QB), "DAL", "Active", null, null, null);
        List<LineupFlag> flags = LineupCheck.flags(new Roster(List.of("s1"), List.of("s1", "b1", "b2"), List.of(), List.of()),
                List.of("QB"), byId(out, z, a), rules(false), EVERYONE_PLAYS, Map.of("b1", new FpAvg(40, 2026, 5)));
        assertEquals(LineupCheck.NAME, flags.get(0).optionsOrder());
        assertEquals(List.of("Abe", "Zed"), flags.get(0).options().stream().map(o -> o.player().name()).toList());
    }

    @Test
    void anNbaCheckWithNoAveragesAtAllIsOrderedByNameAndSaysSo() {
        Player out = p("s1", "Starter", Position.PG, "ACT", "Out");
        Player b = p("b1", "B", Position.PG);
        Player a = p("b2", "A", Position.PG);
        List<LineupFlag> flags = LineupCheck.flags(new Roster(List.of("s1"), List.of("s1", "b1", "b2"), List.of(), List.of()),
                List.of("PG"), byId(out, b, a), rules(true), EVERYONE_PLAYS, Map.of());
        assertEquals(LineupCheck.NAME, flags.get(0).optionsOrder());
    }

    @Test
    void aStarterWithNoPlayerRowIsFlaggedUnknownSoTheCheckCannotSayAllActive() {
        List<LineupFlag> flags = LineupCheck.flags(new Roster(List.of("ghost"), List.of("ghost"), List.of(), List.of()),
                List.of("PG"), Map.of(), rules(true), EVERYONE_PLAYS, Map.of());
        assertEquals(1, flags.size());
        assertEquals(LineupCheck.UNKNOWN_PLAYER, flags.get(0).reason());
        assertEquals("ghost", flags.get(0).player().sleeperPlayerId());
        assertFalse(flags.get(0).player().known());
        assertTrue(flags.get(0).options().isEmpty());
    }
}
