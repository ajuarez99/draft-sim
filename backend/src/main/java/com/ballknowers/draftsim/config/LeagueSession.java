package com.ballknowers.draftsim.config;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

/**
 * Stateless, signed league-session tokens and live-stream tickets (spec 073 follow-up; lock fix pass 2026-11-16).
 *
 * <p><b>Session</b> = {@code <expiry>.<origin>.<base64url(HMAC-SHA256(sessionKey, "league-session-v2:" + expiry +
 * ":" + origin))>}, both numbers epoch seconds. {@code expiry} slides on renewal ({@code now + idle}); {@code origin}
 * is the time of the original unlock and is carried through every renewal, so a session dies
 * {@code max-session-seconds} after the unlock whatever happens in between (CR-02). Version 1 tokens (two parts) fail
 * validation, which just means whoever held one unlocks again.
 *
 * <p><b>Stream ticket</b> = {@code t.<expiry>.<base64url(HMAC-SHA256(ticketKey, "league-ticket-v1:" + draftId + ":" +
 * expiry))>}. It exists because the browser's EventSource cannot send a header, so the live stream needs a credential
 * in the URL, and a URL credential must not be the session (it lands in request logs). A ticket is bound to one draft
 * id, lives {@link #TICKET_SECONDS} seconds, is never renewed, and carries a different key, label and shape from a
 * session, so neither can be presented as the other.
 *
 * <p>Both keys derive from the configured admin token ({@code HMAC(adminToken, label)}); there is no second
 * secret and nothing is stored. Consequences, all deliberate:
 * <ul>
 *   <li><b>Fails closed.</b> With no admin token configured nothing can be issued and nothing validates.</li>
 *   <li><b>Rotating {@code ADMIN_TOKEN} invalidates every session and ticket at once.</b> That is the only
 *       revocation there is; a stateless token cannot be revoked before it expires.</li>
 *   <li>A captured token lets someone test admin-token guesses offline, so the admin token must be long and random
 *       (WebConfig warns below 32 characters; DEPLOY.md says how to generate one).</li>
 *   <li>Signatures are compared in constant time ({@link MessageDigest#isEqual}); numbers are accepted only in
 *       canonical form (digits, no leading zero, no sign, no whitespace).</li>
 *   <li>A token is never logged by this class, and callers must not log one.</li>
 * </ul>
 */
@Component
public class LeagueSession {

    /**
     * Stream-ticket lifetime. ARBITRARY: hand-set. Long enough for a slow connection to open the stream right after
     * the ticket call, short enough that a ticket read out of a log is useless by the time anyone could use it.
     */
    public static final int TICKET_SECONDS = 60;
    public static final String TICKET_PREFIX = "t.";

    private static final String HMAC = "HmacSHA256";
    private static final String SESSION_KEY_LABEL = "league-session-key-v2";
    private static final String SESSION_MESSAGE_LABEL = "league-session-v2:";
    private static final String TICKET_KEY_LABEL = "league-ticket-key-v1";
    private static final String TICKET_MESSAGE_LABEL = "league-ticket-v1:";
    // An epoch-seconds value is 10 digits today; refusing long digit runs keeps parseLong from being a probe surface.
    private static final int MAX_NUMBER_DIGITS = 12;

    private final AdminProperties admin;
    private final LeagueLockProperties lock;
    private final Clock clock;

    public LeagueSession(AdminProperties admin, LeagueLockProperties lock, Clock clock) {
        this.admin = admin;
        this.lock = lock;
        this.clock = clock;
    }

    public int idleSeconds() {
        return lock.idleSeconds();
    }

    /** A brand-new session (origin = now) expiring {@link #idleSeconds()} from now, or {@code null} with no admin token. */
    public String issue() {
        if (!admin.enabled()) return null;
        long now = clock.instant().getEpochSecond();
        return build(now + lock.idleSeconds(), now);
    }

    /**
     * The renewed token for a VALID session: same origin, expiry slid forward but never past the absolute cap.
     * {@code null} when the presented token is not valid.
     */
    public String renew(String token) {
        long[] parts = parseValid(token);
        if (parts == null) return null;
        long now = clock.instant().getEpochSecond();
        return build(now + lock.idleSeconds(), parts[1]);
    }

    private String build(long wantedExpiry, long origin) {
        long expiry = Math.min(wantedExpiry, origin + lock.maxSessionSeconds());
        return expiry + "." + origin + "." + signSession(expiry, origin);
    }

    /** True only for a well-formed, correctly signed, unexpired token still inside its absolute lifetime. */
    public boolean valid(String token) {
        return parseValid(token) != null;
    }

    /** {expiry, origin} of a valid session token, else null. */
    private long[] parseValid(String token) {
        if (!admin.enabled() || token == null) return null;
        int first = token.indexOf('.');
        int second = first < 0 ? -1 : token.indexOf('.', first + 1);
        if (first <= 0 || second < 0 || second == token.length() - 1 || token.indexOf('.', second + 1) >= 0) return null;
        long expiry = canonicalNumber(token.substring(0, first));
        long origin = canonicalNumber(token.substring(first + 1, second));
        if (expiry < 0 || origin < 0) return null;
        boolean signatureOk = constantTimeEquals(signSession(expiry, origin), token.substring(second + 1));
        long now = clock.instant().getEpochSecond();
        if (!signatureOk || now >= expiry || now - origin >= lock.maxSessionSeconds()) return null;
        return new long[] {expiry, origin};
    }

    /** A ticket for one draft's live stream. {@code null} with no admin token or no draft id. Never renewable. */
    public String issueTicket(String draftId) {
        if (!admin.enabled() || draftId == null || draftId.isBlank()) return null;
        long expiry = clock.instant().getEpochSecond() + TICKET_SECONDS;
        return TICKET_PREFIX + expiry + "." + signTicket(draftId, expiry);
    }

    /** True only for a correctly signed, unexpired ticket bound to exactly this draft id. */
    public boolean validTicket(String ticket, String draftId) {
        if (!admin.enabled() || ticket == null || draftId == null || draftId.isBlank()) return false;
        if (!ticket.startsWith(TICKET_PREFIX)) return false;
        String rest = ticket.substring(TICKET_PREFIX.length());
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot != rest.lastIndexOf('.') || dot == rest.length() - 1) return false;
        long expiry = canonicalNumber(rest.substring(0, dot));
        if (expiry < 0) return false;
        boolean signatureOk = constantTimeEquals(signTicket(draftId, expiry), rest.substring(dot + 1));
        return signatureOk && clock.instant().getEpochSecond() < expiry;
    }

    /** The number, or -1 when the text is not a canonical non-negative integer (no sign, space, leading zero). */
    private static long canonicalNumber(String text) {
        if (text.isEmpty() || text.length() > MAX_NUMBER_DIGITS) return -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        if (text.length() > 1 && text.charAt(0) == '0') return -1;
        return Long.parseLong(text);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String signSession(long expiry, long origin) {
        return sign(SESSION_KEY_LABEL, SESSION_MESSAGE_LABEL + expiry + ":" + origin);
    }

    private String signTicket(String draftId, long expiry) {
        return sign(TICKET_KEY_LABEL, TICKET_MESSAGE_LABEL + draftId + ":" + expiry);
    }

    private String sign(String keyLabel, String message) {
        byte[] key = hmac(admin.token().getBytes(StandardCharsets.UTF_8), keyLabel);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, message));
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandatory in every JRE; reaching here is a broken runtime, not a bad request.
            throw new IllegalStateException(e);
        }
    }
}
