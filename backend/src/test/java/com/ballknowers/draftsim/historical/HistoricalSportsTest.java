package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 056 T009/T012: the sport lookup has no default, and the new card fields never come back null. */
class HistoricalSportsTest {

    /** A bare HistoricalSport: only what the lookup reads. */
    private static HistoricalSport stub(String code) {
        return (HistoricalSport) java.lang.reflect.Proxy.newProxyInstance(
                HistoricalSport.class.getClassLoader(), new Class<?>[]{HistoricalSport.class},
                (proxy, m, args) -> m.getName().equals("code") ? code : null);
    }

    @Test
    void anUnknownOrMissingSportIsAnIllegalArgumentNotADefault() {
        HistoricalSports sports = new HistoricalSports(List.of(stub("NBA")));
        assertEquals("NBA", sports.get("NBA").code());
        assertThrows(IllegalArgumentException.class, () -> sports.get("NFL"), "not registered");
        assertThrows(IllegalArgumentException.class, () -> sports.get("nba"), "case matters");
        assertThrows(IllegalArgumentException.class, () -> sports.get(null), "no default sport");
        assertThrows(IllegalArgumentException.class, () -> sports.get(""));
    }

    @Test
    void twoBeansForOneSportAreRefused() {
        assertThrows(IllegalStateException.class, () -> new HistoricalSports(List.of(stub("NBA"), stub("NBA"))));
    }

    @Test
    void priorTotalsIsNeverNullAndIsInStatOrder() {
        var none = new HistoricalPlayerCard.Prior("1994-95", 79, Map.of("pts", 1.0), 1.0, null, null);
        assertNotNull(none.totals());
        assertTrue(none.totals().isEmpty(), "a pre-V46 snapshot has no totals key: it reads as {}");

        var some = new HistoricalPlayerCard.Prior("2006", 16, Map.of(), 1.0, "2006",
                Map.of("st_td", 2.0, "fum_lost", 3.0, "pass_2pt", 1.0));
        assertEquals(List.of("fum_lost", "pass_2pt", "st_td"), List.copyOf(some.totals().keySet()),
                "football keys follow StatOrder, not hash order");
    }

    @Test
    void theEightArgumentCardIsABasketballOrVeteranCardWithNoRookiePick() {
        var c = new HistoricalPlayerCard(1, "X", List.of("PG"), null, null, 1, 1, null);
        assertNull(c.rookiePick());
        assertEquals(Sport.NBA, HistoricalBoardBuilder.toEntry(c).player().sport());
        assertEquals(Sport.NFL, HistoricalBoardBuilder.toEntry(
                new HistoricalPlayerCard(2, "Y", List.of("QB"), null, null, 2, 1, null, 5), Sport.NFL).player().sport());
        assertNotNull(LeagueShape.NBA_ROSTER);
    }
}
