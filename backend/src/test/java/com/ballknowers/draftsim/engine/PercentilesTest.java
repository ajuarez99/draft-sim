package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.PlayerStatsProperties;
import com.ballknowers.draftsim.engine.AdvancedStats.Window;
import com.ballknowers.draftsim.engine.AdvancedStats.WindowKind;
import com.ballknowers.draftsim.engine.NbaGameLines.Line;
import com.ballknowers.draftsim.engine.PlayerOwnership.Facts;
import com.ballknowers.draftsim.engine.PlayerPercentiles.Member;
import com.ballknowers.draftsim.engine.PlayerPercentiles.Population;
import com.ballknowers.draftsim.engine.PlayerStatsService.Pct;
import com.ballknowers.draftsim.engine.PlayerStatsService.Qualification;
import com.ballknowers.draftsim.engine.PlayerTrendsService.PlayerInfo;
import com.ballknowers.draftsim.store.PlayerGameRepository.TeamGame;
import com.ballknowers.draftsim.store.RosterSeasonRepository.Rostered;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 022 T036 (F13, I7): the percentile formula, its inversion, its reasons and its groups. */
class PercentilesTest {

    private static final LocalDate D0 = LocalDate.parse("2025-11-01");
    private static final PlayerStatsProperties PROPS = new PlayerStatsProperties(0.5, 15, 100, 14, 5, 15, 10, 10, 5);
    private static final Qualification OK = new Qualification(true, null);

    // ------------------------------------------------------------------ builders

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        return out;
    }

    /** One game, no team row: ts = 5 x pts (10 FGA, no free throws), tovPct = 100 x to / (10 + to). */
    private static Line game(int day, double pts, double to) {
        return new Line("g" + day, D0.plusDays(day), 1, "AAA", "OPP", true, 30, m("pts", pts, "fga", 10, "to", to),
                null, null);
    }

    private static Map<WindowKind, Window> windowsOf(List<Line> lines) {
        Map<WindowKind, Window> w = new EnumMap<>(WindowKind.class);
        for (WindowKind k : WindowKind.values()) w.put(k, AdvancedStats.window(k.select(lines), 100));
        return w;
    }

    private static Map<WindowKind, Qualification> allQualified() {
        Map<WindowKind, Qualification> q = new EnumMap<>(WindowKind.class);
        for (WindowKind k : WindowKind.values()) q.put(k, OK);
        return q;
    }

    private static Member member(String id, String position, double ts, double tov) {
        return new Member(id, position, Map.of("ts", ts, "tovPct", tov));
    }

    /** The same members in every window. */
    private static Population pop(Member... members) {
        Map<WindowKind, List<Member>> by = new EnumMap<>(WindowKind.class);
        for (WindowKind k : WindowKind.values()) by.put(k, List.of(members));
        return new Population(by);
    }

    private static Pct pos(Map<WindowKind, Map<String, List<Pct>>> p, WindowKind k, String rate) {
        return p.get(k).get(rate).get(0);
    }

    private static Pct ros(Map<WindowKind, Map<String, List<Pct>>> p, WindowKind k, String rate) {
        return p.get(k).get(rate).get(1);
    }

    /** Five guards with ts 50..70, one forward with 99, and the target (ts 60 from pts 12) as a guard member. */
    private static Population league() {
        return pop(member("g50", "G", 50, 10), member("g55", "G", 55, 12), member("g60", "G", 60, 14),
                member("g65", "G", 65, 16), member("g70", "G", 70, 18), member("f99", "F", 99, 1),
                member("me", "G", 60, 14.2));
    }

    // ------------------------------------------------------------------ formula

    @Test
    void theFormulaCountsBelowPlusHalfTheTiesOverTheOthers() {
        List<Line> mine = List.of(game(1, 12, 0));                       // ts 60.0
        Map<WindowKind, Map<String, List<Pct>>> p = PlayerPercentiles.of(league(), "me", "G", windowsOf(mine),
                allQualified(), Set.of("g50", "g60", "g70"));
        // other guards: 50, 55 below, 60 tied, 65, 70 above; "me" is excluded from his own group: n 5
        Pct g = pos(p, WindowKind.SEASON, "ts");
        assertEquals(100.0 * (2 + 0.5) / 5, g.value(), 1e-9);
        assertEquals(5, g.n());
        assertEquals("NBA_POSITION", g.group());
        assertNull(g.reason());
        // rostered guards (and not the forward): 50 below, 60 tied, 70 above -> 100 x (1 + 0.5) / 3
        Pct r = ros(p, WindowKind.SEASON, "ts");
        assertEquals(50.0, r.value(), 1e-9);
        assertEquals(3, r.n());
        assertEquals("LEAGUE_ROSTERED", r.group());
    }

    @Test
    void thePercentileIsBoundedAndTheBestAndWorstAreTheEnds() {
        Set<String> everyone = Set.of("g50", "g55", "g60", "g65", "g70", "f99");
        Map<WindowKind, Map<String, List<Pct>>> best = PlayerPercentiles.of(league(), "me", "G",
                windowsOf(List.of(game(1, 20, 0))), allQualified(), everyone);           // ts 100
        assertEquals(100.0, pos(best, WindowKind.SEASON, "ts").value(), 1e-9);
        assertEquals(100.0, ros(best, WindowKind.SEASON, "ts").value(), 1e-9);
        Map<WindowKind, Map<String, List<Pct>>> worst = PlayerPercentiles.of(league(), "me", "G",
                windowsOf(List.of(game(1, 1, 0))), allQualified(), everyone);            // ts 5
        assertEquals(0.0, pos(worst, WindowKind.SEASON, "ts").value(), 1e-9);
    }

    @Test
    void valuesAreComparedAtTwoDecimalsSoShownEqualFiguresTie() {
        // his ts is 60.004: shown as 60.00, so it ties the 60.00 guard
        Population p = pop(member("a", "G", 60.00, 1), member("b", "G", 59.00, 1), member("c", "G", 61.00, 1));
        List<Line> mine = List.of(new Line("g1", D0, 1, "AAA", "OPP", true, 30,
                m("pts", 6000.4, "fga", 5000, "fta", 0), null, null));
        Map<WindowKind, Map<String, List<Pct>>> out = PlayerPercentiles.of(p, "me", "G", windowsOf(mine),
                allQualified(), Set.of());
        assertEquals(100.0 * (1 + 0.5) / 3, pos(out, WindowKind.SEASON, "ts").value(), 1e-9);
    }

    // ------------------------------------------------------------------ inversion (I7)

    @Test
    void aHigherTurnoverRateGetsALowerPercentile() {
        Population p = pop(member("a", "G", 50, 10), member("b", "G", 50, 12), member("c", "G", 50, 14),
                member("d", "G", 50, 16), member("e", "G", 50, 18));
        Set<String> rostered = Set.of("a", "b", "c", "d", "e");
        Pct careful = pos(PlayerPercentiles.of(p, "me", "G", windowsOf(List.of(game(1, 5, 0.5))), allQualified(),
                rostered), WindowKind.SEASON, "tovPct");     // 4.76%: lower than all five
        Pct middling = pos(PlayerPercentiles.of(p, "me", "G", windowsOf(List.of(game(1, 5, 1.7))), allQualified(),
                rostered), WindowKind.SEASON, "tovPct");     // 14.5%: between 14 and 16
        Pct sloppy = pos(PlayerPercentiles.of(p, "me", "G", windowsOf(List.of(game(1, 5, 5))), allQualified(),
                rostered), WindowKind.SEASON, "tovPct");     // 33.3%: higher than all five
        assertEquals(100.0, careful.value(), 1e-9);
        assertEquals(100.0 * 2 / 5, middling.value(), 1e-9);   // only 16 and 18 are worse
        assertEquals(0.0, sloppy.value(), 1e-9);
        assertTrue(careful.value() > middling.value() && middling.value() > sloppy.value());
    }

    @Test
    void aHigherShootingFigureStillGetsAHigherPercentile() {
        Population p = pop(member("a", "G", 50, 1), member("b", "G", 55, 1), member("c", "G", 60, 1));
        double low = pos(PlayerPercentiles.of(p, "me", "G", windowsOf(List.of(game(1, 9, 0))), allQualified(), Set.of()),
                WindowKind.SEASON, "ts").value();                 // 45
        double high = pos(PlayerPercentiles.of(p, "me", "G", windowsOf(List.of(game(1, 12, 0))), allQualified(), Set.of()),
                WindowKind.SEASON, "ts").value();                 // 60 ties the top
        assertTrue(high > low);
    }

    // ------------------------------------------------------------------ reasons

    @Test
    void aGroupOfFewerThanTwoOthersIsTooSmall() {
        Population p = pop(member("g1", "G", 50, 1), member("f1", "F", 50, 1), member("f2", "F", 55, 1),
                member("me", "G", 60, 1));
        Map<WindowKind, Map<String, List<Pct>>> out = PlayerPercentiles.of(p, "me", "G",
                windowsOf(List.of(game(1, 12, 0))), allQualified(), Set.of("g1", "f1", "f2"));
        Pct only = pos(out, WindowKind.SEASON, "ts");               // one other guard
        assertNull(only.value());
        assertEquals("GROUP_TOO_SMALL", only.reason());
        assertEquals(1, only.n());
        // rostered: three others of any position -> a value
        assertNotNull(ros(out, WindowKind.SEASON, "ts").value());
        // two others is enough
        Pct two = pos(PlayerPercentiles.of(pop(member("g1", "G", 50, 1), member("g2", "G", 55, 1)), "me", "G",
                windowsOf(List.of(game(1, 12, 0))), allQualified(), Set.of()), WindowKind.SEASON, "ts");
        assertEquals(100.0, two.value(), 1e-9);
        assertEquals(2, two.n());
        // a player with no position has no position group
        Pct none = pos(PlayerPercentiles.of(p, "me", null, windowsOf(List.of(game(1, 12, 0))), allQualified(), Set.of()),
                WindowKind.SEASON, "ts");
        assertEquals("GROUP_TOO_SMALL", none.reason());
        assertEquals(0, none.n());
    }

    @Test
    void anUnqualifiedOrStalePlayerHasReasonsNotValues() {
        Map<WindowKind, Qualification> q = allQualified();
        q.put(WindowKind.SEASON, new Qualification(false, "NOT_QUALIFIED"));
        q.put(WindowKind.LAST_10, new Qualification(false, "NOT_QUALIFIED_STALE"));
        Map<WindowKind, Map<String, List<Pct>>> out = PlayerPercentiles.of(league(), "me", "G",
                windowsOf(List.of(game(1, 12, 0))), q, Set.of("g50", "g60", "g70"));
        for (String rate : AdvancedStats.ADVANCED_KEYS) {
            for (Pct pct : out.get(WindowKind.SEASON).get(rate)) {
                assertNull(pct.value());
                assertEquals("NOT_QUALIFIED", pct.reason());
            }
            for (Pct pct : out.get(WindowKind.LAST_10).get(rate)) assertEquals("NOT_QUALIFIED_STALE", pct.reason());
        }
        // per window: LAST_5 stayed qualified and still has a value
        assertNotNull(pos(out, WindowKind.LAST_5, "ts").value());
        assertEquals("NOT_QUALIFIED", pos(out, WindowKind.SEASON, "ts").reason());
        assertEquals(5, pos(out, WindowKind.SEASON, "ts").n());     // n is still the group's size
    }

    @Test
    void aFreeAgentIsRankedAgainstTheRosteredGroupAndGetsAValue() {
        // "me" is qualified but not rostered, and not in the population's rostered set
        Population p = pop(member("a", "F", 50, 1), member("b", "F", 55, 1), member("c", "F", 65, 1));
        Pct r = ros(PlayerPercentiles.of(p, "me", "G", windowsOf(List.of(game(1, 12, 0))), allQualified(),
                Set.of("a", "b", "c")), WindowKind.SEASON, "ts");   // 60: above 50, 55; below 65
        assertEquals(100.0 * 2 / 3, r.value(), 1e-9);               // n is the group size, 3, not 2
        assertEquals(3, r.n());
        assertNull(r.reason());
    }

    @Test
    void withoutOwnershipTheRosteredGroupIsUnavailableAndThePositionGroupIsNot() {
        Map<WindowKind, Map<String, List<Pct>>> out = PlayerPercentiles.of(league(), "me", "G",
                windowsOf(List.of(game(1, 12, 0))), allQualified(), null);
        for (String rate : List.of("ts", "efg", "ftr", "tpar", "tovPct")) {   // the rates he has a value for
            Pct r = out.get(WindowKind.SEASON).get(rate).get(1);
            assertNull(r.value());
            assertEquals("OWNERSHIP_UNAVAILABLE", r.reason());
            assertEquals("LEAGUE_ROSTERED", r.group());
        }
        assertNotNull(pos(out, WindowKind.SEASON, "ts").value());
        // an empty rostered set (nobody rostered, ownership known) is a small group, not unavailable
        Pct empty = ros(PlayerPercentiles.of(league(), "me", "G", windowsOf(List.of(game(1, 12, 0))), allQualified(),
                Set.of()), WindowKind.SEASON, "ts");
        assertEquals("GROUP_TOO_SMALL", empty.reason());
    }

    @Test
    void whenHisOwnRateHasNoValueThePercentileCarriesThatReason() {
        // game() has no team row: usage-based rates are NO_TEAM_ROW for him
        Map<WindowKind, Map<String, List<Pct>>> out = PlayerPercentiles.of(league(), "me", "G",
                windowsOf(List.of(game(1, 12, 0))), allQualified(), Set.of("g50", "g60", "g70"));
        assertEquals("NO_TEAM_ROW", pos(out, WindowKind.SEASON, "usg").reason());
        assertEquals("NO_TEAM_ROW", ros(out, WindowKind.SEASON, "usg").reason());
    }

    @Test
    void everyPercentileHasTwoGroupsInOrderAndEveryWindowEveryRateIsPresent() {
        Map<WindowKind, Map<String, List<Pct>>> out = PlayerPercentiles.of(league(), "me", "G",
                windowsOf(List.of(game(1, 12, 0))), allQualified(), Set.of("g50"));
        assertEquals(Set.of(WindowKind.values()), out.keySet());
        for (WindowKind k : WindowKind.values()) {
            assertEquals(AdvancedStats.ADVANCED_KEYS, List.copyOf(out.get(k).keySet()));
            for (List<Pct> two : out.get(k).values()) {
                assertEquals(2, two.size());
                assertEquals("NBA_POSITION", two.get(0).group());
                assertEquals("LEAGUE_ROSTERED", two.get(1).group());
                for (Pct pct : two) assertTrue(pct.n() >= 0);
            }
        }
    }

    @Test
    void aPctHasExactlyOneOfValueAndReason() {
        assertThrows(IllegalArgumentException.class, () -> new Pct(null, "NBA_POSITION", 3, null));
        assertThrows(IllegalArgumentException.class, () -> new Pct(50.0, "NBA_POSITION", 3, "GROUP_TOO_SMALL"));
    }

    // ------------------------------------------------------------------ per-window qualification and population

    @Test
    void eachWindowUsesItsOwnPopulationAndItsOwnQualification() {
        Map<WindowKind, List<Member>> by = new EnumMap<>(WindowKind.class);
        by.put(WindowKind.SEASON, List.of(member("a", "G", 40, 1), member("b", "G", 45, 1), member("c", "G", 50, 1)));
        by.put(WindowKind.LAST_10, List.of(member("a", "G", 40, 1), member("b", "G", 70, 1), member("c", "G", 80, 1)));
        by.put(WindowKind.LAST_5, List.of(member("a", "G", 90, 1), member("b", "G", 91, 1)));   // c not qualified here
        Map<WindowKind, Map<String, List<Pct>>> out = PlayerPercentiles.of(new Population(by), "me", "G",
                windowsOf(List.of(game(1, 12, 0))), allQualified(), Set.of("a", "b", "c"));      // ts 60
        assertEquals(100.0, pos(out, WindowKind.SEASON, "ts").value(), 1e-9);
        assertEquals(100.0 / 3, pos(out, WindowKind.LAST_10, "ts").value(), 1e-9);
        assertEquals(0.0, pos(out, WindowKind.LAST_5, "ts").value(), 1e-9);
        assertEquals(3, pos(out, WindowKind.SEASON, "ts").n());
        assertEquals(2, pos(out, WindowKind.LAST_5, "ts").n());
    }

    @Test
    void thePopulationHoldsOnlyPlayersQualifiedInThatWindow() {
        // AAA played 12 games, so a SEASON player needs ceil(0.5 x 12) = 6; LAST_N needs N games, 15 mpg, recent
        Map<String, List<Line>> by = new HashMap<>();
        by.put("full", gamesOf(1, 12, 20));
        by.put("short", gamesOf(8, 3, 20));                         // 3 games: no SEASON, no LAST_5
        by.put("five", gamesOf(8, 5, 20));                          // 5 games: no SEASON (needs 6), LAST_5 yes, LAST_10 no
        by.put("seven", gamesOf(1, 7, 20));                         // 7 games, last on day 7: 5 days before day 12, so not stale
        List<TeamGame> tg = new ArrayList<>();
        for (int d = 1; d <= 12; d++) tg.add(new TeamGame("AAA", "g" + d, D0.plusDays(d), "OPP", Map.of()));
        NbaGameLines lines = new NbaGameLines(by, Map.of("AAA", tg), Set.of("AAA", "OPP"));
        Map<String, PlayerInfo> infos = Map.of("full", new PlayerInfo("Full", List.of("G", "F"), "AAA"));
        Population p = PlayerPercentiles.population(lines, infos, PROPS);
        assertEquals(Set.of("full", "seven"), ids(p, WindowKind.SEASON));
        assertEquals(Set.of("full"), ids(p, WindowKind.LAST_10));
        assertEquals(Set.of("full", "five", "seven"), ids(p, WindowKind.LAST_5));
        // first listed position only; a player with no row has none
        Member full = p.byWindow().get(WindowKind.SEASON).stream().filter(x -> x.id().equals("full")).findFirst().orElseThrow();
        assertEquals("G", full.position());
        assertTrue(full.values().containsKey("ts"));
        assertNull(p.byWindow().get(WindowKind.SEASON).stream().filter(x -> x.id().equals("seven")).findFirst()
                .orElseThrow().position());
    }

    @Test
    void aLastNWindowWhoseLastGameIsOldIsLeftOutOfTheLastNPopulation() {
        Map<String, List<Line>> by = new HashMap<>();
        by.put("fresh", gamesOf(21, 5, 20));                        // days 21..25
        by.put("old", gamesOf(1, 5, 20));                           // last game day 5: 20 days before day 25 > 14
        List<TeamGame> tg = new ArrayList<>();
        for (int d = 1; d <= 25; d++) tg.add(new TeamGame("AAA", "g" + d, D0.plusDays(d), "OPP", Map.of()));
        NbaGameLines lines = new NbaGameLines(by, Map.of("AAA", tg), Set.of("AAA", "OPP"));
        Population p = PlayerPercentiles.population(lines, Map.of(), PROPS);
        assertEquals(Set.of("fresh"), ids(p, WindowKind.LAST_5));
    }

    private static Set<String> ids(Population p, WindowKind k) {
        Set<String> out = new java.util.HashSet<>();
        for (Member x : p.byWindow().get(k)) out.add(x.id());
        return out;
    }

    private static List<Line> gamesOf(int firstDay, int count, double minutes) {
        List<Line> l = new ArrayList<>();
        for (int d = firstDay; d < firstDay + count; d++) {
            l.add(new Line("g" + d, D0.plusDays(d), 1, "AAA", "OPP", true, minutes, m("pts", 10, "fga", 10), null, null));
        }
        return l;
    }

    // ------------------------------------------------------------------ ownership set agrees with Ownership

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @Test
    void theRosteredSetIsExactlyWhoReadsRosteredAtTheSeasonView() {
        Rostered cur = new Rostered(Map.of("p1", 1, "p2", 2), T);
        Facts inSeason = new Facts("in_season", null, 5, null, cur, Map.of(), Map.of());
        assertEquals(Optional.of(Set.of("p1", "p2")), PlayerOwnership.rosteredAtSeasonView(inSeason));
        assertRosteredMatches(inSeason, Set.of("p1", "p2"), "free");

        // not drafted, never fetched, and an undrafted null status with no one rostered: no meaningful set
        assertEquals(Optional.empty(), PlayerOwnership.rosteredAtSeasonView(new Facts("pre_draft", null, null, null, cur, Map.of(), Map.of())));
        assertEquals(Optional.empty(), PlayerOwnership.rosteredAtSeasonView(new Facts("drafting", null, null, null, cur, Map.of(), Map.of())));
        assertEquals(Optional.empty(), PlayerOwnership.rosteredAtSeasonView(new Facts("in_season", null, null, null, null, Map.of(), Map.of())));
        assertEquals(Optional.empty(), PlayerOwnership.rosteredAtSeasonView(
                new Facts(null, null, null, null, new Rostered(Map.of(), T), Map.of(), Map.of())));

        // a completed season: the week before the playoffs, union of the rosters
        Map<Integer, Map<Integer, Set<String>>> weeks = Map.of(14, Map.of(1, Set.of("p1"), 2, Set.of("p2", "p3")));
        Facts complete = new Facts("complete", 15, null, 17, null, weeks, Map.of());
        assertEquals(Optional.of(Set.of("p1", "p2", "p3")), PlayerOwnership.rosteredAtSeasonView(complete));
        assertRosteredMatches(complete, Set.of("p1", "p2", "p3"), "free");
        // an empty roster poisons the week (N6); a missing week and a missing playoff start are unavailable
        Map<Integer, Map<Integer, Set<String>>> poisoned = Map.of(14, Map.of(1, Set.of("p1"), 2, Set.of()));
        assertEquals(Optional.empty(), PlayerOwnership.rosteredAtSeasonView(new Facts("complete", 15, null, 17, null, poisoned, Map.of())));
        assertEquals(Optional.empty(), PlayerOwnership.rosteredAtSeasonView(new Facts("complete", 15, null, 17, null, Map.of(), Map.of())));
        assertEquals(Optional.empty(), PlayerOwnership.rosteredAtSeasonView(new Facts("complete", null, null, 17, null, weeks, Map.of())));
    }

    private static void assertRosteredMatches(Facts f, Set<String> rostered, String freeAgent) {
        for (String id : rostered) assertEquals(PlayerOwnership.ROSTERED, PlayerOwnership.forSeasonView(id, f).state());
        assertEquals(PlayerOwnership.FREE_AGENT, PlayerOwnership.forSeasonView(freeAgent, f).state());
    }
}
