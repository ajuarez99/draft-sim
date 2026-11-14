package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;

import java.util.List;

/**
 * How "Draft a random year" leans toward recent seasons (Allan 2026-11-14: basketball offers 1970 onward, so a flat
 * draw lands before 2001 more than half the time, and that got old).
 *
 * <p>The newest {@code recentSeasons} offered seasons each count {@code recentWeight} times in the draw; every older
 * season counts once. "Newest" is measured back from the latest season in the offered list, not from today's date,
 * so the window moves when a season is added and never depends on the clock. Old seasons stay possible on purpose.
 *
 * <p>Both numbers are hand-set and arbitrary, a taste call and not a measurement. {@link #NONE} is the flat draw
 * every sport had before this existed.
 *
 * @param recentSeasons how many of the newest offered seasons get the extra weight
 * @param recentWeight  how many times likelier one of those is than one older season; 1 is a flat draw
 */
public record SeasonSkew(int recentSeasons, double recentWeight) {

    public static final SeasonSkew NONE = new SeasonSkew(0, 1.0);

    public SeasonSkew {
        if (recentSeasons < 0) {
            throw new IllegalArgumentException("recentSeasons must be >= 0, got " + recentSeasons);
        }
        if (!(recentWeight > 0) || Double.isInfinite(recentWeight)) {
            throw new IllegalArgumentException("recentWeight must be a positive number, got " + recentWeight);
        }
    }

    /** This season's share of the draw, before normalising. */
    public double weight(int seasonStart, int latestOffered) {
        return seasonStart > latestOffered - recentSeasons ? recentWeight : 1.0;
    }

    /**
     * One season from the offered list.
     *
     * @param offered non-empty, in any order
     * @param u       a uniform draw in [0, 1)
     */
    public SeasonRow pick(List<SeasonRow> offered, double u) {
        if (offered.isEmpty()) throw new IllegalArgumentException("no seasons to pick from");
        int latest = offered.stream().mapToInt(SeasonRow::seasonStart).max().getAsInt();
        double total = 0;
        for (SeasonRow s : offered) total += weight(s.seasonStart(), latest);
        double target = u * total, run = 0;
        for (SeasonRow s : offered) {
            run += weight(s.seasonStart(), latest);
            if (target < run) return s;
        }
        return offered.get(offered.size() - 1);   // u so close to 1 that rounding walked past the end
    }
}
