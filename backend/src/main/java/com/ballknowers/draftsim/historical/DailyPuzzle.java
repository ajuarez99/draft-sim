package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Deals one sport's game of the day (spec 071): which season, and the seed every player of that day shares.
 *
 * <p>Both come from the sport code and the date alone, so two servers asked at the same moment deal the same
 * puzzle. The result is then stored ({@code historical_daily}) and never re-derived, because the offered list this
 * draws from can change with an import.
 */
public final class DailyPuzzle {

    /** How many of a sport's previous daily seasons a new day avoids. Hand-set: about a month without a repeat. */
    public static final int NO_REPEAT_DAYS = 30;

    private DailyPuzzle() {}

    public record Deal(int seasonStart, long seed) {}

    /**
     * @param offered  the sport's offered seasons, non-empty
     * @param skew     the sport's own lean toward recent seasons, the same one free play uses
     * @param recent   seasons used by the days just before this one; skipped unless that would leave nothing
     */
    public static Deal deal(String sportCode, LocalDate date, List<SeasonRow> offered, SeasonSkew skew,
                            Collection<Integer> recent) {
        if (offered.isEmpty()) throw new IllegalArgumentException("no seasons to deal from");
        SplittableRandom rng = new SplittableRandom(mix(sportCode, date));
        long seed = rng.nextLong();

        Set<Integer> avoid = new HashSet<>(recent);
        List<SeasonRow> fresh = offered.stream().filter(s -> !avoid.contains(s.seasonStart())).toList();
        SeasonRow season = skew.pick(fresh.isEmpty() ? offered : fresh, rng.nextDouble());
        return new Deal(season.seasonStart(), seed);
    }

    /** A stable 64-bit mix of the sport and the day: String.hashCode is specified, so this never varies by JVM. */
    private static long mix(String sportCode, LocalDate date) {
        return 0x9E3779B97F4A7C15L * (date.toEpochDay() + 1) ^ ((long) sportCode.hashCode() << 32);
    }
}
