package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.domain.Sport;

import java.util.Map;

/**
 * ESPN {@code proTeamId} to the abbreviation the app's {@code player.team} column uses
 * (research R4). ESPN spells several differently (NFL {@code WSH}; NBA {@code Bkn, NY, GS, SA, Utah,
 * Wsh, Phx, Cha, NO}).
 *
 * <p>Measured 2026-10-14 from {@code ?view=proTeamSchedules_wl} (2026 season): NFL ids are 0 (FA),
 * 1..30, 33, 34 (there is no 31 or 32); NBA ids are 0 (FA) and 1..30. Both abbreviation sets were
 * also compared with the distinct non-null {@code player.team} values in the scratch DB. Team is used
 * only for D/ST matching and display, never as a required key for a player.
 */
public final class EspnProTeams {

    private EspnProTeams() {}

    private static final Map<Integer, String> NFL = Map.ofEntries(
            Map.entry(1, "ATL"), Map.entry(2, "BUF"), Map.entry(3, "CHI"), Map.entry(4, "CIN"),
            Map.entry(5, "CLE"), Map.entry(6, "DAL"), Map.entry(7, "DEN"), Map.entry(8, "DET"),
            Map.entry(9, "GB"), Map.entry(10, "TEN"), Map.entry(11, "IND"), Map.entry(12, "KC"),
            Map.entry(13, "LV"), Map.entry(14, "LAR"), Map.entry(15, "MIA"), Map.entry(16, "MIN"),
            Map.entry(17, "NE"), Map.entry(18, "NO"), Map.entry(19, "NYG"), Map.entry(20, "NYJ"),
            Map.entry(21, "PHI"), Map.entry(22, "ARI"), Map.entry(23, "PIT"), Map.entry(24, "LAC"),
            Map.entry(25, "SF"), Map.entry(26, "SEA"), Map.entry(27, "TB"),
            Map.entry(28, "WAS"),   // ESPN spells it WSH
            Map.entry(29, "CAR"), Map.entry(30, "JAX"), Map.entry(33, "BAL"), Map.entry(34, "HOU"));

    private static final Map<Integer, String> NBA = Map.ofEntries(
            Map.entry(1, "ATL"), Map.entry(2, "BOS"),
            Map.entry(3, "NOP"),    // ESPN: NO
            Map.entry(4, "CHI"), Map.entry(5, "CLE"), Map.entry(6, "DAL"), Map.entry(7, "DEN"),
            Map.entry(8, "DET"),
            Map.entry(9, "GSW"),    // ESPN: GS
            Map.entry(10, "HOU"), Map.entry(11, "IND"), Map.entry(12, "LAC"), Map.entry(13, "LAL"),
            Map.entry(14, "MIA"), Map.entry(15, "MIL"), Map.entry(16, "MIN"),
            Map.entry(17, "BKN"),   // ESPN: Bkn
            Map.entry(18, "NYK"),   // ESPN: NY
            Map.entry(19, "ORL"), Map.entry(20, "PHI"),
            Map.entry(21, "PHX"),   // ESPN: Phx
            Map.entry(22, "POR"), Map.entry(23, "SAC"),
            Map.entry(24, "SAS"),   // ESPN: SA
            Map.entry(25, "OKC"),
            Map.entry(26, "UTA"),   // ESPN: Utah
            Map.entry(27, "WAS"),   // ESPN: Wsh
            Map.entry(28, "TOR"), Map.entry(29, "MEM"),
            Map.entry(30, "CHA"));  // ESPN: Cha

    /** The app's abbreviation for an ESPN team id; null for 0 (free agent), an unknown id, or null. */
    public static String abbreviation(Sport sport, Integer proTeamId) {
        if (proTeamId == null || proTeamId == 0) return null;
        return (sport == Sport.NFL ? NFL : NBA).get(proTeamId);
    }

    /** Every id mapped for a sport, for tests. */
    static Map<Integer, String> table(Sport sport) {
        return sport == Sport.NFL ? NFL : NBA;
    }
}
