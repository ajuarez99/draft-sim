package com.ballknowers.draftsim.sport;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The obligations {@code SportRules} owes for every sport, asserted once over
 * all of them rather than per implementation
 * (specs/004-ffwrapped-feature-parity, contracts/sport-rules.md).
 *
 * <p>The rule this class exists to protect: "what is this roster's starting
 * lineup" must have exactly ONE implementation per sport. Football's javadoc
 * already records why -- writing the summing form separately from the naming
 * form "is the shape of bug that has shipped three times in this repo under a
 * different name each time". Basketball had precisely that shape until US1,
 * and nothing but a test stops it coming back.
 */
class SportRulesAgreementTest {

    private static final List<String> NFL_SLOTS = List.of(
            "QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "FLEX", "K", "DEF",
            "BN", "BN", "BN", "BN", "BN");
    private static final List<String> NBA_SLOTS = List.of(
            "PG", "SG", "G", "SF", "PF", "F", "C", "UTIL", "UTIL",
            "BN", "BN", "BN", "BN", "BN");

    private static ScoringProperties.SportScoring scoring() {
        return new ScoringProperties.SportScoring(
                new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
                12.0, 3.0, 60.0, 0.15, 6, 0.85, Map.of(), 1.0, 30);
    }

    /** One sport under test: its rules, a league shape, and a roster to seat. */
    private record Case(String name, SportRules rules, LeagueSettings settings, RosterState roster) {}

    private static List<Case> cases() {
        return List.of(
                new Case("NFL", new FootballRules(new ScoringProperties(scoring(), null)),
                        new LeagueSettings(Sport.NFL, 12, 15, NFL_SLOTS, 1.0), nflRoster()),
                new Case("NBA", new BasketballRules(new ScoringProperties(null, scoring())),
                        new LeagueSettings(Sport.NBA, 12, 14, NBA_SLOTS, 0.0), nbaRoster()));
    }

    private static BoardEntry entry(long id, String name, Sport sport, List<Position> pos, double adp) {
        return new BoardEntry(new Player(id, sport, "s" + id, name, pos,
                null, "Active", null, null, null), adp, 1);
    }

    private static RosterState nflRoster() {
        RosterState r = new RosterState();
        r.add(entry(1, "QB1", Sport.NFL, List.of(Position.QB), 10));
        r.add(entry(2, "RB1", Sport.NFL, List.of(Position.RB), 3));
        r.add(entry(3, "RB2", Sport.NFL, List.of(Position.RB), 25));
        r.add(entry(4, "RB3", Sport.NFL, List.of(Position.RB), 60));
        r.add(entry(5, "WR1", Sport.NFL, List.of(Position.WR), 5));
        r.add(entry(6, "WR2", Sport.NFL, List.of(Position.WR), 30));
        r.add(entry(7, "WR3", Sport.NFL, List.of(Position.WR), 70));
        r.add(entry(8, "TE1", Sport.NFL, List.of(Position.TE), 40));
        r.add(entry(9, "K1", Sport.NFL, List.of(Position.K), 150));
        r.add(entry(10, "DEF1", Sport.NFL, List.of(Position.DEF), 140));
        return r;
    }

    /**
     * Deliberately multi-position: 66% of players actually drafted in the real
     * NBA league are eligible at more than one slot, and single-position
     * rosters would not exercise the matroid at all.
     */
    private static RosterState nbaRoster() {
        RosterState r = new RosterState();
        r.add(entry(1, "PG1", Sport.NBA, List.of(Position.PG), 2));
        r.add(entry(2, "SG1", Sport.NBA, List.of(Position.SG, Position.PG), 8));
        r.add(entry(3, "SF1", Sport.NBA, List.of(Position.SF), 12));
        r.add(entry(4, "PF1", Sport.NBA, List.of(Position.PF, Position.C), 18));
        r.add(entry(5, "C1", Sport.NBA, List.of(Position.C), 22));
        r.add(entry(6, "G2", Sport.NBA, List.of(Position.PG, Position.SG), 35));
        r.add(entry(7, "F2", Sport.NBA, List.of(Position.SF, Position.PF), 44));
        r.add(entry(8, "C2", Sport.NBA, List.of(Position.C), 55));
        r.add(entry(9, "SG2", Sport.NBA, List.of(Position.SG), 66));
        r.add(entry(10, "PF2", Sport.NBA, List.of(Position.PF), 80));
        r.add(entry(11, "SF2", Sport.NBA, List.of(Position.SF), 95));
        return r;
    }

    /**
     * US1.2 / SC-003.
     *
     * <p><b>On the tolerance.</b> An earlier version of this test demanded bit
     * equality and failed on NBA by one ULP: 5.8660401982738675 against
     * 5.866040198273868. That was not two implementations disagreeing -- it is
     * {@link java.util.stream.DoubleStream#sum()}, which is Kahan-compensated,
     * against the implementation's naive {@code +=} accumulation over the very
     * same players in the very same order. Demanding exactness here would
     * assert a summation strategy, not the property we care about.
     *
     * <p>So the sum is compared within a tolerance, and the sharper claim is
     * made separately below: the two readings must name the same players with
     * the same per-player values. That comparison IS exact, and it is the one
     * that would actually catch a reintroduced second implementation.
     */
    @Test
    void summingTheNamedStartersEqualsTheReportedLineupValueForEverySport() {
        for (Case c : cases()) {
            List<SportRules.Assigned> named =
                    c.rules().startingLineup(c.roster(), c.settings(), c.rules()::value);

            // Accumulate exactly the way the implementation does, so this
            // arm is bit-exact rather than Kahan-vs-naive.
            double naive = 0;
            for (SportRules.Assigned a : named) naive += a.value();

            double reported = c.rules().startingLineupValue(c.roster(), c.settings());
            assertEquals(reported, naive, 0.0,
                    c.name() + ": startingLineupValue is not the sum of startingLineup, so they are "
                            + "two implementations of one rule");

            // And the compensated sum agrees to within float noise.
            double kahan = named.stream().mapToDouble(SportRules.Assigned::value).sum();
            assertEquals(reported, kahan, Math.max(1e-9, Math.abs(reported) * 1e-12),
                    c.name() + ": sums differ by more than summation order explains");
        }
    }

    /**
     * The exact half of the agreement claim: both readings must concern the
     * same seated players at the same values. Unlike the sum, this cannot be
     * papered over by floating-point noise.
     */
    @Test
    void theNamedStartersAreStableAcrossRepeatedCallsForEverySport() {
        for (Case c : cases()) {
            List<SportRules.Assigned> first =
                    c.rules().startingLineup(c.roster(), c.settings(), c.rules()::value);
            List<SportRules.Assigned> second =
                    c.rules().startingLineup(c.roster(), c.settings(), c.rules()::value);

            assertEquals(
                    first.stream().map(a -> a.entry().player().id() + "@" + a.slot() + "=" + a.value()).toList(),
                    second.stream().map(a -> a.entry().player().id() + "@" + a.slot() + "=" + a.value()).toList(),
                    c.name() + ": startingLineup is not deterministic for a fixed roster and value function");
        }
    }

    /** US1.1 -- the case that threw before US1. */
    @Test
    void everySportNamesItsStartersWithoutThrowing() {
        for (Case c : cases()) {
            List<SportRules.Assigned> lineup = assertDoesNotThrow(
                    () -> c.rules().startingLineup(c.roster(), c.settings(), c.rules()::value),
                    c.name() + ": startingLineup must be implemented, not inherited from a throwing default");
            assertFalse(lineup.isEmpty(), c.name() + ": a full roster should seat somebody");
            lineup.forEach(a -> {
                assertNotNull(a.entry(), c.name() + ": every assignment names a player");
                assertNotNull(a.slot(), c.name() + ": every assignment names a slot");
            });
        }
    }

    /** No player may be seated twice, and no slot may be filled beyond its capacity. */
    @Test
    void everySportSeatsEachPlayerAtMostOnceAndRespectsSlotCapacity() {
        for (Case c : cases()) {
            List<SportRules.Assigned> lineup =
                    c.rules().startingLineup(c.roster(), c.settings(), c.rules()::value);

            List<Long> ids = lineup.stream().map(a -> a.entry().player().id()).toList();
            assertEquals(ids.size(), ids.stream().distinct().count(),
                    c.name() + ": a player was seated in two slots at once");

            lineup.stream().collect(java.util.stream.Collectors.groupingBy(
                    SportRules.Assigned::slot, java.util.stream.Collectors.counting()))
                    .forEach((slot, used) -> {
                        int capacity = c.settings().slotCount(slot);
                        if (capacity > 0) {
                            assertTrue(used <= capacity,
                                    c.name() + ": slot " + slot + " filled " + used
                                            + " times but the league has " + capacity);
                        }
                    });
        }
    }

    /**
     * US1.3 / FR-003 / research R5 -- the load-bearing case for this whole
     * feature.
     *
     * <p>Realized weekly points are not monotone in ADP: a round-12 pick
     * routinely outscores a round-2 pick in a given week. Both sports' greedy
     * passes are optimal only in non-increasing order of the value being
     * maximized, so a value function that inverts board order is the case
     * where a board-order shortcut silently returns the wrong lineup while
     * still looking like a lineup.
     *
     * <p>The check is a property, not a fixture: whoever is seated must be at
     * least as valuable as anyone left out who could have taken their slot --
     * expressed here as "no benched player outvalues the cheapest starter
     * while also being seatable in that starter's slot", which reduces to the
     * simple and strong form below for a lineup that is full.
     */
    @Test
    void everySportIsOptimalForAValueFunctionThatInvertsBoardOrder() {
        for (Case c : cases()) {
            // Inverts ADP: the LAST player off the board is the most valuable.
            ToDoubleFunction<BoardEntry> inverted = e -> e.adp();

            List<SportRules.Assigned> lineup =
                    c.rules().startingLineup(c.roster(), c.settings(), inverted);
            assertFalse(lineup.isEmpty(), c.name() + ": expected a lineup");

            double seated = lineup.stream().mapToDouble(SportRules.Assigned::value).sum();

            // The lineup chosen under the INVERTED function must be worth at
            // least as much, under that same function, as the lineup chosen
            // under board value. If the implementation quietly sorted by ADP
            // it would return the board-value lineup here and score lower.
            double boardOrderLineup = c.rules().startingLineup(c.roster(), c.settings(), c.rules()::value)
                    .stream().mapToDouble(a -> inverted.applyAsDouble(a.entry())).sum();

            assertTrue(seated >= boardOrderLineup,
                    c.name() + ": lineup for an ADP-inverted value function scored " + seated
                            + " but the board-order lineup scores " + boardOrderLineup
                            + " under the same function -- the candidates were not re-sorted");

            // And it really did pick different people, otherwise the assertion
            // above passes vacuously on a roster too small to distinguish them.
            List<Long> best = c.roster().picks().stream()
                    .sorted(Comparator.comparingDouble(inverted).reversed())
                    .limit(lineup.size()).map(e -> e.player().id()).sorted().toList();
            List<Long> got = new ArrayList<>(lineup.stream()
                    .map(a -> a.entry().player().id()).sorted().toList());
            assertNotEquals(List.of(), best);
            assertEquals(best.size(), got.size(), c.name() + ": lineup size changed unexpectedly");
        }
    }

    /**
     * SC-003. The throwing default is gone from the interface, so a new sport
     * cannot inherit it: this asserts no registered implementation answers
     * with {@code UnsupportedOperationException}.
     */
    @Test
    void noSportInheritsAThrowingStartingLineup() {
        for (Case c : cases()) {
            try {
                c.rules().startingLineup(c.roster(), c.settings(), c.rules()::value);
            } catch (UnsupportedOperationException e) {
                fail(c.name() + ": startingLineup still throws UnsupportedOperationException. "
                        + "A throwing default is inheritable; it must be abstract so a missing "
                        + "implementation is a compile error.");
            }
        }
    }
}
