package com.ballknowers.draftsim.profile;

/**
 * What the user says about a seat, as opposed to what history says.
 *
 * Every field is optional — a seat may have a note and nothing else. Nulls mean
 * "no opinion", which is different from zero.
 *
 * @param reachBias       picks early (positive) or late (negative) this manager
 *                        tends to take players, relative to the board. Same units
 *                        and sign as the fitted value.
 * @param unpredictability multiplier on the run's temperature for this seat only.
 *                        1.0 is "drafts like the room", 2.0 is "does something
 *                        insane every year", 0.5 is a metronome. A multiplier
 *                        rather than an absolute so the global chaos slider keeps
 *                        its meaning — at temperature 0 the board is still modal
 *                        no matter what any seat claims.
 * @param note            not used by the engine. A reminder on the seat card.
 */
public record ManualTendencies(
        Double reachBias,
        Double unpredictability,
        String note
) {
    public static final ManualTendencies EMPTY = new ManualTendencies(null, null, null);

    /** Compact constructor keeps stored values inside ranges the engine can survive. */
    public ManualTendencies {
        if (reachBias != null) reachBias = clamp(reachBias, -40, 40);
        if (unpredictability != null) unpredictability = clamp(unpredictability, 0.1, 5.0);
        if (note != null) {
            note = note.strip();
            if (note.isEmpty()) note = null;
            else if (note.length() > 280) note = note.substring(0, 280);
        }
    }

    public boolean isEmpty() {
        return reachBias == null && unpredictability == null && note == null;
    }

    /** Whether anything here changes how the seat drafts, as opposed to just annotating it. */
    public boolean affectsBehaviour() {
        return reachBias != null || unpredictability != null;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
