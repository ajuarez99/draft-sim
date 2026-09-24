package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.SeasonSuperlativesService.DetailRow;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.PickupDetail;
import com.ballknowers.draftsim.engine.WaiverPickupAttribution.PlayerContribution;
import com.ballknowers.draftsim.engine.WaiverPickupAttribution.RosterTotal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WAIVER_WIRE_WARRIOR's PICKUP detail rows (specs/008-season-superlatives
 * US3, coordinator follow-up after the live check), run against in-memory
 * fixtures -- no Postgres. PICKUP is the one detail type that didn't carry
 * {@code rosterId} at first; every other type already does, and a tie
 * between two holders needs each row tagged with its OWN holder's roster,
 * not just whichever roster's map entry happened to be read.
 */
class SeasonSuperlativesWaiverTest {

    private static PlayerContribution contribution(String playerId, double points) {
        return new PlayerContribution(playerId, 1, "WAIVER", List.of(2), points);
    }

    @Test
    void tiedHoldersEachGetTheirOwnRosterIdOnTheirPickupRows() {
        RosterTotal rosterA = new RosterTotal(4, 20.0, List.of(contribution("100", 20.0)));
        RosterTotal rosterB = new RosterTotal(7, 20.0, List.of(contribution("200", 20.0)));
        Map<Integer, RosterTotal> byRoster = Map.of(4, rosterA, 7, rosterB);

        List<DetailRow> detail = SeasonSuperlativesService.pickupDetail(
                List.of(4, 7), byRoster, Map.of());

        assertEquals(2, detail.size(), "one PICKUP row per tied holder");
        PickupDetail rowForA = (PickupDetail) detail.stream()
                .filter(d -> ((PickupDetail) d).playerId().equals("100")).findFirst().orElseThrow();
        PickupDetail rowForB = (PickupDetail) detail.stream()
                .filter(d -> ((PickupDetail) d).playerId().equals("200")).findFirst().orElseThrow();

        assertEquals(4, rowForA.rosterId(), "player 100's row belongs to roster 4, its own holder");
        assertEquals(7, rowForB.rosterId(), "player 200's row belongs to roster 7, its own holder");
    }

    @Test
    void topThreePerHolderKeepsThatHolderOwnRosterIdOnEveryRow() {
        RosterTotal roster = new RosterTotal(9, 60.0, List.of(
                contribution("100", 30.0), contribution("200", 20.0),
                contribution("300", 10.0), contribution("400", 0.0)));
        Map<Integer, RosterTotal> byRoster = Map.of(9, roster);

        List<DetailRow> detail = SeasonSuperlativesService.pickupDetail(List.of(9), byRoster, Map.of());

        assertEquals(3, detail.size(), "top 3 only");
        for (DetailRow d : detail) {
            assertEquals(9, ((PickupDetail) d).rosterId());
        }
    }
}
