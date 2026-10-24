package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.store.LeagueRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.util.OptionalInt;

/**
 * Spec 046 C3: "which season does the ADP compare page mean?" -- ONE rule, every caller.
 *
 * <p>It is the newest season of any league of the sport (complete or not: dynasty drafts happen in the
 * offseason, when {@link ProjectionSeason#current} has nothing to do). The daily ADP_CAPTURE step, the
 * season-projection ingest and {@code POST /api/ingest/adp/sleeper} all ask this; a Sleeper capture for
 * any other season is refused, because it would be stored as TODAY's capture and overwrite this season's.
 *
 * <p>Spec 046 live verification (2026-10-23): seasons later than next calendar year are ignored. One stray
 * league is otherwise enough to move the whole sport -- measured on the scratch DB, where an IT fixture
 * league at season 2099 ("IT 014 NBA") made every real 2026 capture refuse with "not the page's season
 * (2099)". A real league is never more than one season ahead of the calendar (Sleeper renews into next
 * year's season in the offseason, which this still allows).
 */
public final class AdpSeason {

    private AdpSeason() {}

    public static OptionalInt current(LeagueRepository leagues, Sport sport) {
        return current(leagues, sport, Clock.systemDefaultZone());
    }

    static OptionalInt current(LeagueRepository leagues, Sport sport, Clock clock) {
        int latestPlausible = LocalDate.now(clock).getYear() + 1;
        return leagues.all().stream().filter(l -> l.sport() == sport)
                .mapToInt(LeagueRepository.LeagueRow::season)
                .filter(s -> s <= latestPlausible)
                .max();
    }
}
