package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.profile.Provenance;
import com.ballknowers.draftsim.sport.FootballRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The factory exists so the batch simulator, the ad-hoc league-size branch and
 * the mock room cannot drift apart in how they turn "who is in which seat" into
 * a runnable context. These tests pin the part that is easy to get subtly wrong
 * in three places independently: which seats end up modelled, which end up
 * league-average, and which requests are refused outright.
 */
class DraftContextFactoryTest {

    private static final ScoringProperties.Sport CFG = new ScoringProperties.Sport(
            new ScoringProperties.Weights(1.0, 0.35, 0.5, 0.25),
            12.0, 3.0, 60.0, 0.15, 6, 0.85,
            Map.of("K", 3, "DEF", 4), 1.0, 30);

    private final DraftContextFactory factory =
            new DraftContextFactory(new FootballRules(new ScoringProperties(CFG)), new ScoringProperties(CFG));

    private static List<BoardEntry> board(int size) {
        List<BoardEntry> out = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            out.add(new BoardEntry(new Player(i, Sport.NFL, "s" + i, "P" + i,
                    List.of(Position.WR), "FA", "Active", null, null, null), i, i));
        }
        return out;
    }

    private static ManagerProfile fitted(long id) {
        return new ManagerProfile(id, "manager " + id, 4.0, Map.of(), 1.0, null, 2, 30, Provenance.FITTED);
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 10, 12, 14})
    void everySeatGetsAProfileAtEverySupportedSize(int teams) {
        LeagueShape shape = LeagueShape.standard(teams);
        DraftContext ctx = factory.build(shape, List.of(SeatSpec.user(1, null)),
                Map.of(), PositionalPriors.uniform(), board(shape.totalPicks()), Map.of());

        assertEquals(teams, ctx.profileBySlot().size());
        for (int slot = 1; slot <= teams; slot++) {
            assertNotNull(ctx.profileBySlot().get(slot), "slot " + slot);
        }
        assertEquals(teams * 15, ctx.totalPicks());
    }

    @Test
    void aManagerSeatCarriesItsFittedProfileAndAnUnlistedSeatIsLeagueAverage() {
        DraftContext ctx = factory.build(LeagueShape.standard(8),
                List.of(SeatSpec.manager(3, 77L), SeatSpec.user(1, null)),
                Map.of(77L, fitted(77L)), PositionalPriors.uniform(), board(120), Map.of());

        assertEquals(Provenance.FITTED, ctx.profileFor(3).provenance());
        assertEquals(4.0, ctx.profileFor(3).reachBias(), 1e-9);
        assertEquals(Provenance.NEUTRAL, ctx.profileFor(5).provenance());
        assertEquals(0.0, ctx.profileFor(5).reachBias(), 1e-9);
    }

    /**
     * A modelled manager we happen to have fitted nothing for is a seat we know
     * nothing about, which is the neutral profile — not a missing-key crash and
     * not a reason to reject the request.
     */
    @Test
    void aManagerWithNoFittedProfileFallsBackToNeutralRatherThanFailing() {
        DraftContext ctx = factory.build(LeagueShape.standard(8),
                List.of(SeatSpec.manager(2, 404L)),
                Map.of(), PositionalPriors.uniform(), board(120), Map.of());

        assertEquals(Provenance.NEUTRAL, ctx.profileFor(2).provenance());
        assertEquals(404L, ctx.profileFor(2).managerId());
    }

    @Test
    void aUserSeatWithASleeperIdentityIsScoredLikeAnyOtherModelledSeat() {
        DraftContext ctx = factory.build(LeagueShape.standard(8),
                List.of(SeatSpec.user(6, 77L)),
                Map.of(77L, fitted(77L)), PositionalPriors.uniform(), board(120), Map.of());

        assertEquals(Provenance.FITTED, ctx.profileFor(6).provenance());
        assertEquals(4.0, ctx.profileFor(6).reachBias(), 1e-9);
    }

    @Test
    void completedPicksArriveOnTheContextInPickOrder() {
        DraftContext ctx = factory.build(LeagueShape.standard(8), List.of(),
                Map.of(), PositionalPriors.uniform(), board(120), Map.of(3, 30L, 1, 10L));

        assertEquals(List.of(1, 3), ctx.completedPickNumbers());
        assertEquals(10L, ctx.completedAt(1));
        assertEquals(30L, ctx.completedAt(3));
        assertEquals(0L, ctx.completedAt(2));
    }

    @Test
    void aBoardShorterThanTheDraftIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> factory.build(LeagueShape.standard(14), List.of(),
                        Map.of(), PositionalPriors.uniform(), board(200), Map.of()));
        assertTrue(e.getMessage().contains("210"), e.getMessage());
    }

    @Test
    void duplicateAndOutOfRangeSeatsAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(LeagueShape.standard(8),
                        List.of(SeatSpec.manager(4, 1L), SeatSpec.manager(4, 2L)),
                        Map.of(), PositionalPriors.uniform(), board(120), Map.of()));

        assertThrows(IllegalArgumentException.class,
                () -> factory.build(LeagueShape.standard(8), List.of(SeatSpec.manager(9, 1L)),
                        Map.of(), PositionalPriors.uniform(), board(120), Map.of()));
    }

    /**
     * Two humans in one draft is not a shape the mock room can serve — it has
     * one on-the-clock input — so it is rejected here rather than at whichever
     * of the three call sites happens to notice first.
     */
    @Test
    void twoUserSeatsAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.build(LeagueShape.standard(8),
                        List.of(SeatSpec.user(1, null), SeatSpec.user(2, null)),
                        Map.of(), PositionalPriors.uniform(), board(120), Map.of()));
    }

    @Test
    void anEmptyBoardIsRefusedWithTheIngestHint() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> factory.build(LeagueShape.standard(8), List.of(),
                        Map.of(), PositionalPriors.uniform(), List.of(), Map.of()));
        assertTrue(e.getMessage().contains("ingest"), e.getMessage());
    }

    @Test
    void seatSpecRefusesIncoherentSeats() {
        assertThrows(IllegalArgumentException.class, () -> new SeatSpec(1, SeatSpec.Type.MANAGER, null));
        assertThrows(IllegalArgumentException.class, () -> new SeatSpec(1, SeatSpec.Type.BOT, 7L));
        assertThrows(IllegalArgumentException.class, () -> SeatSpec.bot(0));
    }
}
