package com.ballknowers.draftsim.api;

/**
 * A route that only makes sense for a Sleeper league or draft was called with an ESPN one
 * (spec 035, plan F4). Answered as 422 {@code PLATFORM_NOT_SUPPORTED} by {@link ErrorHandler}.
 *
 * <p>The default message is fixed text. The two-argument form carries a LeagueAvailability sentence, which
 * may name the league's untranslated ESPN scoring items (labels, not secrets), and never anything from the request.
 */
public class PlatformNotSupported extends RuntimeException {

    public static final String CODE = "PLATFORM_NOT_SUPPORTED";
    public static final String MESSAGE =
            "This is an ESPN league. Drafts, the draft room, simulations and Sleeper-based setup only work for Sleeper leagues.";

    private final String code;

    public PlatformNotSupported() {
        super(MESSAGE);
        this.code = CODE;
    }

    /**
     * A page of an ESPN league that {@code LeagueAvailability} says cannot be shown, with its own code
     * ({@code SCORING_UNTRANSLATED}, {@code MULTI_WEEK_PERIODS}, ...). Both arguments come from that
     * class's fixed texts, never from ESPN or the request.
     */
    public PlatformNotSupported(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
