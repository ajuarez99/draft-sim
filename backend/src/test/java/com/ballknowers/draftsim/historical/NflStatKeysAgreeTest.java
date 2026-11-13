package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.NflHistoricalProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 056 review N3/S3: the football stat order lives in a few places; this pins them to one list, and pins the
 * settings line to the config it describes. (The web's STAT_ORDER is pinned in historicalCopy.test.ts.)
 */
class NflStatKeysAgreeTest {

    static final List<String> DOCUMENTED = List.of("pass_yd", "pass_td", "pass_int", "rush_yd", "rush_td", "rec",
            "rec_yd", "rec_td", "fum_lost", "pass_2pt", "rush_2pt", "rec_2pt", "st_td");

    @Test
    void statOrderTailCountingAndParserKeysAreTheSameThirteen() {
        assertEquals(13, NflHistoricalSport.COUNTING.size());
        assertEquals(DOCUMENTED, NflHistoricalSport.COUNTING);
        List<String> order = StatOrder.ORDER;
        assertEquals(NflHistoricalSport.COUNTING, order.subList(order.size() - 13, order.size()),
                "StatOrder's football tail");
        assertEquals(NflHistoricalSport.COUNTING, NflverseSource.KEYS, "the parser's KEYS");
    }

    @Test
    void settingsLineIsBuiltFromTheConfigAndFollowsIt() {
        Map<Integer, Integer> games = new TreeMap<>();
        for (int y = 2000; y <= 2025; y++) games.put(y, 256);
        List<String> roster = List.of("QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "BN", "BN", "BN", "BN", "BN", "BN", "BN", "BN");
        NflHistoricalProperties p = new NflHistoricalProperties("https://x/{season}.csv.gz", "https://x/d.csv", 12, 15,
                roster, 0, Map.of("pass_yd", 0.04), 17, 6, 3, 12, 2, games, 2000, 2025, 14);
        assertEquals("Hand-set 12-team, 15-round roster: QB, 2 RB, 2 WR, TE, FLEX + 8 bench · full PPR",
                new NflHistoricalSport(p, null, null).settingsSource());
        NflHistoricalProperties q = new NflHistoricalProperties("https://x/{season}.csv.gz", "https://x/d.csv", 10, 3,
                List.of("QB", "WR", "BN"), 0, Map.of("pass_yd", 0.04), 17, 6, 3, 12, 2, games, 2000, 2025, 14);
        assertEquals("Hand-set 10-team, 3-round roster: QB, WR + 1 bench · full PPR",
                new NflHistoricalSport(q, null, null).settingsSource());
    }
}
