package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Platform;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * The one seam for "who is on each roster right now" and "what week is it" while a page is served
 * (spec 035 research R5, plan T022). Sleeper leagues delegate to {@link SleeperClient} exactly as
 * before (FR-013). ESPN leagues read the stored {@code roster_season} rows and {@code settings_json.leg}.
 *
 * <p>Plain object, not a bean: each consuming service builds one from dependencies it already has, so
 * no constructor signature changes. A null {@code leagues} (some unit tests) means "Sleeper".
 */
public class LeagueRosterSource {

    private final SleeperClient sleeper;
    private final LeagueRepository leagues;
    private final RosterSeasonRepository rosterSeasons;

    public LeagueRosterSource(SleeperClient sleeper, LeagueRepository leagues, RosterSeasonRepository rosterSeasons) {
        this.sleeper = sleeper;
        this.leagues = leagues;
        this.rosterSeasons = rosterSeasons;
    }

    private boolean isEspn(String sleeperLeagueId) {
        if (leagues == null) return false;
        var p = leagues.platformOf(sleeperLeagueId);
        return p != null && p.isPresent() && p.get() != Platform.SLEEPER;
    }

    public List<Map<String, Object>> rosters(LeagueRepository.LeagueRow league) {
        return league.isSleeper() ? sleeper.rosters(league.sleeperId()) : espnRosters(league.id());
    }

    /** By key, for callers that only hold the league key. */
    public List<Map<String, Object>> rosters(String sleeperLeagueId) {
        if (!isEspn(sleeperLeagueId)) return sleeper.rosters(sleeperLeagueId);
        return espnRosters(leagues.bySleeperId(sleeperLeagueId).orElseThrow().id());
    }

    /** Sleeper-shaped: {@code roster_id, owner_id, players, reserve}. Maps are mutable (owner_id can be null). */
    private List<Map<String, Object>> espnRosters(long leagueId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RosterSeasonRepository.EspnRoster r : rosterSeasons.espnRosters(leagueId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roster_id", r.rosterId());
            m.put("owner_id", r.ownerSleeperUserId());
            m.put("players", r.players() == null ? new ArrayList<String>() : new ArrayList<>(r.players()));
            m.put("reserve", r.reserve() == null ? new ArrayList<String>() : new ArrayList<>(r.reserve()));
            out.add(m);
        }
        return out;
    }

    /** ESPN only: the stored current matchup period; empty when never written (never defaulted to 1). */
    public OptionalInt espnCurrentWeek(LeagueRepository.LeagueRow league) {
        return leagues.currentLeg(league.id());
    }
}
