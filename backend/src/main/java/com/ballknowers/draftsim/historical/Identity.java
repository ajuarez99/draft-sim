package com.ballknowers.draftsim.historical;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Who is calling the Redraft routes (spec 074): one resolver, used by both historical controllers.
 *
 * <p>A Sleeper id ({@code X-Sleeper-User}) wins. Otherwise a well-formed UUID in {@code X-Guest-Id} (made and kept
 * by the browser) becomes the owner key {@code guest:<uuid>}. Otherwise 401, as before. A Sleeper header that itself
 * starts with {@code guest:} is a 400, so the two namespaces can never collide in
 * {@code historical_game.sleeper_user_id}. The owner key is what every game row, ownership check and unique index
 * sees; nothing else in the code base knows the difference.
 */
public final class Identity {

    public static final String GUEST_PREFIX = "guest:";

    private static final Pattern UUID_FORM = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";

    private Identity() {}

    /**
     * Eight hex characters of a SHA-256 of an owner key, for log lines. A guest key is a bearer credential (CR-11): it
     * is never logged, but two log lines about the same guest should still be matchable.
     */
    public static String shortHash(String ownerKey) {
        if (ownerKey == null) return "none";
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(ownerKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "hash-unavailable";
        }
    }

    /** The owner key for a request: the Sleeper id, else {@code guest:<uuid>}; 400 for a {@code guest:} Sleeper id, 401 for neither. */
    public static String resolve(String sleeperUser, String guestId) {
        if (sleeperUser != null && !sleeperUser.isBlank()) return checkedSleeper(sleeperUser);
        String guest = guestKey(guestId);
        if (guest == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "X-Sleeper-User or a valid X-Guest-Id is required for the historical draft game");
        }
        return guest;
    }

    /** The Sleeper id from its header: 401 when absent or blank, 400 when it starts with {@code guest:}. */
    public static String sleeperId(String sleeperUser) {
        if (sleeperUser == null || sleeperUser.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Sleeper-User is required");
        }
        return checkedSleeper(sleeperUser);
    }

    /** {@code guest:<lowercase uuid>} for a well-formed UUID, else null (absent, blank or malformed). */
    public static String guestKey(String guestId) {
        if (guestId == null) return null;
        String g = guestId.trim();
        if (!UUID_FORM.matcher(g).matches()) return null;
        // The nil UUID is a placeholder every buggy client would share, so it is not an identity (CR-11).
        if (NIL_UUID.equals(g.toLowerCase(Locale.ROOT))) return null;
        return GUEST_PREFIX + g.toLowerCase(Locale.ROOT);
    }

    public static boolean isGuest(String ownerKey) {
        return ownerKey != null && ownerKey.startsWith(GUEST_PREFIX);
    }

    private static String checkedSleeper(String sleeperUser) {
        if (sleeperUser.regionMatches(true, 0, GUEST_PREFIX, 0, GUEST_PREFIX.length())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Sleeper-User may not start with guest:");
        }
        return sleeperUser;
    }
}
