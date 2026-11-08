package com.ballknowers.draftsim.historical;

import java.time.LocalDate;

/**
 * One player's game in one week, whatever the sport (spec 056 T005). The replay reads stats only through this
 * interface, so a basketball box score and a football week are interchangeable to it.
 */
public interface WeekStatLine {
    long playerId();

    int week();

    /** Null for football: the nflverse stats files carry no game dates (research R4). */
    LocalDate gameDate();

    /** One stat of this game, or null when unrecorded (the caller scores that as 0, FR-007). */
    Double stat(String key);
}
