package com.ballknowers.draftsim.historical;

/**
 * A signed-out player asked for something only a signed-in one gets (spec 074): a random season, a second chosen
 * season, the profile or an award. Answered 403 with {@code code: GUEST_LIMIT} by {@code ErrorHandler}, so the page
 * can offer sign-in instead of an error.
 */
public class GuestLimitException extends RuntimeException {

    public static final String CODE = "GUEST_LIMIT";

    public GuestLimitException(String message) {
        super(message);
    }
}
