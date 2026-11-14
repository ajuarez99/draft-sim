package com.ballknowers.draftsim.historical;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * A player's run of finished games of the day for one sport (spec 071).
 *
 * @param current consecutive days ending today, or ending yesterday when today's game is not finished yet (the
 *                day is not over, so the run is not broken); 0 when neither day was finished
 * @param best    the longest run of consecutive days ever
 */
public record DailyStreak(int current, int best) {

    public static final DailyStreak NONE = new DailyStreak(0, 0);

    /** @param finished the puzzle dates of the player's COMPLETE daily games; duplicates and order do not matter */
    public static DailyStreak of(Collection<LocalDate> finished, LocalDate today) {
        Set<LocalDate> days = new HashSet<>(finished);
        if (days.isEmpty()) return NONE;

        LocalDate end = days.contains(today) ? today : today.minusDays(1);
        int current = 0;
        for (LocalDate d = end; days.contains(d); d = d.minusDays(1)) current++;

        int best = 0;
        for (LocalDate d : days) {
            if (days.contains(d.minusDays(1))) continue;   // not the first day of its run
            int run = 0;
            for (LocalDate x = d; days.contains(x); x = x.plusDays(1)) run++;
            best = Math.max(best, run);
        }
        return new DailyStreak(current, best);
    }
}
