package com.ballknowers.draftsim.historical;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * Buckets a historical season's game dates into fantasy weeks (spec 053 research R8, plan F16).
 *
 * <p>Week 1 is the Monday-Sunday calendar week containing opening night. Only calendar weeks that actually
 * contain a counted game are numbered, so an empty week (an All-Star break with no games) does not consume a
 * number and the schedule never has a blank week. Dates after {@code lastCountedDate} (for 2019-20,
 * 2020-03-11, the last night before the league stopped) have no week.
 *
 * <p>Pure: no Spring, no database.
 */
public final class HistoricalWeeks {

    private final Map<Integer, Integer> weekByCalendarIndex;
    private final LocalDate anchorMonday;
    private final LocalDate openingNight;
    private final LocalDate lastCountedDate;

    private HistoricalWeeks(Map<Integer, Integer> weekByCalendarIndex, LocalDate anchorMonday,
                            LocalDate openingNight, LocalDate lastCountedDate) {
        this.weekByCalendarIndex = weekByCalendarIndex;
        this.anchorMonday = anchorMonday;
        this.openingNight = openingNight;
        this.lastCountedDate = lastCountedDate;
    }

    /**
     * @param gameDates       every game date seen in the season (duplicates fine; order irrelevant)
     * @param openingNight    the first game date; fixes which calendar week is week 1
     * @param lastCountedDate the last date that counts, inclusive
     */
    public static HistoricalWeeks of(Collection<LocalDate> gameDates, LocalDate openingNight,
                                     LocalDate lastCountedDate) {
        LocalDate anchor = openingNight.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        TreeSet<Integer> populated = new TreeSet<>();
        for (LocalDate d : gameDates) {
            if (d.isBefore(openingNight) || d.isAfter(lastCountedDate)) continue;
            populated.add(calendarIndex(anchor, d));
        }
        Map<Integer, Integer> numbered = new HashMap<>();
        int next = 1;
        for (int idx : populated) numbered.put(idx, next++);
        return new HistoricalWeeks(numbered, anchor, openingNight, lastCountedDate);
    }

    /** The fantasy week a game date falls in, or empty when it is outside the counted range. */
    public OptionalInt weekOf(LocalDate date) {
        if (date.isBefore(openingNight) || date.isAfter(lastCountedDate)) return OptionalInt.empty();
        Integer w = weekByCalendarIndex.get(calendarIndex(anchorMonday, date));
        return w == null ? OptionalInt.empty() : OptionalInt.of(w);
    }

    /** How many weeks have at least one counted game. */
    public int weekCount() {
        return weekByCalendarIndex.size();
    }

    private static int calendarIndex(LocalDate anchor, LocalDate d) {
        return (int) (ChronoUnit.DAYS.between(anchor, d) / 7);
    }
}
