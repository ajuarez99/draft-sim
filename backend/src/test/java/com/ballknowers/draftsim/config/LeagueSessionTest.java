package com.ballknowers.draftsim.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stateless league-session token and stream ticket (spec 073 follow-up). Pure: a fixed clock, no Spring. */
class LeagueSessionTest {

    private static final Instant T0 = Instant.parse("2026-11-16T12:00:00Z");
    private static final String ADMIN = "admin-secret";

    private static Clock at(Instant t) {
        return Clock.fixed(t, ZoneOffset.UTC);
    }

    private static LeagueSession session(String adminToken, int idle, int max, Instant now) {
        return new LeagueSession(new AdminProperties(adminToken), new LeagueLockProperties(true, idle, max), at(now));
    }

    private static LeagueSession session(String adminToken, int idle, Instant now) {
        return session(adminToken, idle, 43200, now);
    }

    private static String[] parts(String token) {
        return token.split("\\.");
    }

    @Test
    void anIssuedTokenIsValidAndCarriesNowPlusIdleAndNowAsOrigin() {
        LeagueSession s = session(ADMIN, 300, T0);
        String token = s.issue();
        assertTrue(s.valid(token));
        assertEquals(String.valueOf(T0.getEpochSecond() + 300), parts(token)[0]);
        assertEquals(String.valueOf(T0.getEpochSecond()), parts(token)[1]);
        assertEquals(300, s.idleSeconds());
    }

    @Test
    void validStrictlyBeforeTheExpirySecondAndExpiredAtIt() {
        String token = session(ADMIN, 300, T0).issue();
        assertTrue(session(ADMIN, 300, T0.plusSeconds(299)).valid(token), "one second before expiry");
        assertFalse(session(ADMIN, 300, T0.plusSeconds(300)).valid(token), "the expiry second itself");
        assertFalse(session(ADMIN, 300, T0.plusSeconds(301)).valid(token), "after expiry");
    }

    @Test
    void tamperingWithAnyPartIsRefused() {
        LeagueSession s = session(ADMIN, 300, T0);
        String[] p = parts(s.issue());
        String expiry = p[0], origin = p[1], sig = p[2];

        // Extending the expiry while keeping the old signature.
        assertFalse(s.valid((Long.parseLong(expiry) + 1000) + "." + origin + "." + sig));
        // Moving the origin forward (to dodge the absolute cap) while keeping the signature.
        assertFalse(s.valid(expiry + "." + (Long.parseLong(origin) + 1000) + "." + sig));
        // A flipped signature character.
        char first = sig.charAt(0);
        String flipped = (first == 'A' ? 'B' : 'A') + sig.substring(1);
        assertFalse(s.valid(expiry + "." + origin + "." + flipped));
        // Truncated and extended signatures.
        assertFalse(s.valid(expiry + "." + origin + "." + sig.substring(0, sig.length() - 1)));
        assertFalse(s.valid(expiry + "." + origin + "." + sig + "A"));
        // Signature of a different token grafted on.
        String other = parts(session(ADMIN, 600, T0).issue())[2];
        assertFalse(s.valid(expiry + "." + origin + "." + other));
    }

    @Test
    void aTokenFromAnotherAdminTokenIsRefusedSoRotatingTheSecretEndsEverySession() {
        String token = session("old-admin-token", 300, T0).issue();
        assertTrue(session("old-admin-token", 300, T0).valid(token));
        assertFalse(session("new-admin-token", 300, T0).valid(token));
    }

    @Test
    void garbageIsRefusedWithoutThrowing() {
        LeagueSession s = session(ADMIN, 300, T0);
        String[] garbage = {null, "", " ", ".", "..", "...", "a.b", "a.b.c", "123.", ".abc", "-1.abc", "+5.abc", "1e9.abc",
                "99999999999999999999999.1.sig", "12345.sig.extra.more", "null", "undefined", "Bearer x", "0.", " 1.2",
                "1.2", "1.2.", "1..3", "t.1.2"};
        for (String g : garbage) assertFalse(s.valid(g), "should refuse: " + g);
    }

    @Test
    void aVersionOneTokenWithTwoPartsIsRefused() {
        // The v1 shape (<expiry>.<sig>) signed with the same admin token must not validate under v2.
        LeagueSession s = session(ADMIN, 300, T0);
        String[] p = parts(s.issue());
        assertFalse(s.valid(p[0] + "." + p[2]));
    }

    @Test
    void nonCanonicalNumbersAreRefusedEvenWithAValidSignatureOverTheParsedValue() {
        // CR-09: leading zeros, signs and whitespace. A forged "0" + expiry has a signature that matches the parsed
        // number, so only the canonical-form rule stops it.
        LeagueSession s = session(ADMIN, 300, T0);
        String[] p = parts(s.issue());
        assertFalse(s.valid("0" + p[0] + "." + p[1] + "." + p[2]), "leading zero on expiry");
        assertFalse(s.valid(p[0] + ".0" + p[1] + "." + p[2]), "leading zero on origin");
        assertFalse(s.valid("+" + p[0] + "." + p[1] + "." + p[2]), "plus sign");
        assertFalse(s.valid(" " + p[0] + "." + p[1] + "." + p[2]), "leading space");
        assertFalse(s.valid(p[0] + " ." + p[1] + "." + p[2]), "trailing space");
        assertFalse(s.valid(p[0] + "." + p[1] + "." + p[2] + " "), "space after signature");
        assertTrue(s.valid(p[0] + "." + p[1] + "." + p[2]));
    }

    @Test
    void withNoAdminTokenNothingIsIssuedAndNothingValidates() {
        for (String blank : new String[] {null, "", "   "}) {
            LeagueSession none = session(blank, 300, T0);
            assertNull(none.issue());
            assertNull(none.issueTicket("d1"));
            assertNull(none.renew(session(ADMIN, 300, T0).issue()));
            assertFalse(none.valid(session(ADMIN, 300, T0).issue()));
            assertFalse(none.validTicket(session(ADMIN, 300, T0).issueTicket("d1"), "d1"));
            assertFalse(none.valid(null));
            assertFalse(none.valid(""));
        }
    }

    @Test
    void renewingMovesTheExpiryForwardAndKeepsTheOrigin() {
        String first = session(ADMIN, 300, T0).issue();
        String renewed = session(ADMIN, 300, T0.plusSeconds(200)).renew(first);
        assertNotNull(renewed);
        assertNotEquals(first, renewed);
        assertEquals(parts(first)[1], parts(renewed)[1], "origin is preserved by a renewal");
        assertEquals(String.valueOf(T0.getEpochSecond() + 200 + 300), parts(renewed)[0]);
        // Valid at T0+400 only because it was renewed at T0+200.
        LeagueSession later = session(ADMIN, 300, T0.plusSeconds(400));
        assertFalse(later.valid(first));
        assertTrue(later.valid(renewed));
    }

    @Test
    void anInvalidTokenIsNotRenewed() {
        String first = session(ADMIN, 300, T0).issue();
        assertNull(session(ADMIN, 300, T0.plusSeconds(301)).renew(first), "expired");
        assertNull(session(ADMIN, 300, T0).renew("garbage"));
        assertNull(session(ADMIN, 300, T0).renew(null));
    }

    @Test
    void theAbsoluteLifetimeCapsARenewedSessionNoMatterHowActive() {
        int max = 1000;
        String token = session(ADMIN, 300, max, T0).issue();
        long originSecond = T0.getEpochSecond();
        // Renewed every 200 s, as an always-active user would, right up to the cap.
        Instant t = T0;
        while (t.isBefore(T0.plusSeconds(max - 200))) {
            t = t.plusSeconds(200);
            token = session(ADMIN, 300, max, t).renew(token);
            assertNotNull(token, "still inside the cap at +" + (t.getEpochSecond() - originSecond));
            assertTrue(Long.parseLong(parts(token)[0]) <= originSecond + max, "the expiry never passes origin + cap");
            assertEquals(String.valueOf(originSecond), parts(token)[1], "origin survives every renewal");
        }
        // A renewal at the last moment is clamped to the cap, and refused from the cap second on.
        Instant last = T0.plusSeconds(max - 1);
        String lastToken = session(ADMIN, 300, max, last).renew(token);
        assertNotNull(lastToken);
        assertEquals(String.valueOf(originSecond + max), parts(lastToken)[0]);
        assertFalse(session(ADMIN, 300, max, T0.plusSeconds(max)).valid(lastToken));
        assertNull(session(ADMIN, 300, max, T0.plusSeconds(max)).renew(lastToken));
    }

    @Test
    void aTokenWhoseExpiryIsInTheFutureIsStillRefusedPastTheCap() {
        // Built by a server with a generous cap and checked by one with a tight cap: the signature is good and the
        // expiry has not passed, so only now - origin >= cap refuses it.
        String token = session(ADMIN, 300, 100_000, T0).issue();
        String renewed = session(ADMIN, 300, 100_000, T0.plusSeconds(1000)).renew(token);   // wait: token is expired by then
        assertNull(renewed);
        String chain = token;
        for (int i = 1; i <= 5; i++) {
            chain = session(ADMIN, 300, 100_000, T0.plusSeconds(200L * i)).renew(chain);
        }
        Instant now = T0.plusSeconds(1000);   // 1000 s after the origin; the chain was renewed at +1000 - so valid
        assertTrue(session(ADMIN, 300, 100_000, now).valid(chain));
        assertFalse(session(ADMIN, 300, 900, now).valid(chain), "cap of 900 s: refused although the expiry is +1300");
        assertTrue(session(ADMIN, 300, 1100, now).valid(chain));
    }

    @Test
    void theCapAppliesEvenWhenTheExpiryIsStillInTheFuture() {
        // Idle 600 > the remaining cap: the expiry is clamped to origin + cap, then refused at it.
        int max = 400;
        String token = session(ADMIN, 600, max, T0).issue();
        assertEquals(String.valueOf(T0.getEpochSecond() + max), parts(token)[0]);
        assertTrue(session(ADMIN, 600, max, T0.plusSeconds(399)).valid(token));
        assertFalse(session(ADMIN, 600, max, T0.plusSeconds(400)).valid(token));
    }

    @Test
    void theIdleWindowAndCapMustBePositive() {
        assertThrows(IllegalStateException.class, () -> new LeagueLockProperties(true, 0, null));
        assertThrows(IllegalStateException.class, () -> new LeagueLockProperties(true, -5, null));
        assertThrows(IllegalStateException.class, () -> new LeagueLockProperties(true, 300, 0));
        assertEquals(300, new LeagueLockProperties(false, null, null).idleSeconds());
        assertEquals(43200, new LeagueLockProperties(false, null, null).maxSessionSeconds());
    }

    // ---------------------------------------------------------------- stream tickets

    @Test
    void aTicketIsBoundToItsDraftAndExpiresInSixtySeconds() {
        LeagueSession s = session(ADMIN, 300, T0);
        String ticket = s.issueTicket("draft-1");
        assertTrue(ticket.startsWith("t."));
        assertEquals(60, LeagueSession.TICKET_SECONDS);
        assertTrue(s.validTicket(ticket, "draft-1"));
        assertFalse(s.validTicket(ticket, "draft-2"), "another draft");
        assertFalse(s.validTicket(ticket, "draft-1 "), "not even a padded id");
        assertFalse(s.validTicket(ticket, ""));
        assertFalse(s.validTicket(ticket, null));
        assertTrue(session(ADMIN, 300, T0.plusSeconds(59)).validTicket(ticket, "draft-1"));
        assertFalse(session(ADMIN, 300, T0.plusSeconds(60)).validTicket(ticket, "draft-1"), "expired at 60 s");
    }

    @Test
    void aTicketAndASessionAreNotInterchangeable() {
        LeagueSession s = session(ADMIN, 300, T0);
        String ticket = s.issueTicket("draft-1");
        String sessionToken = s.issue();
        assertFalse(s.valid(ticket), "a ticket is not a session");
        assertFalse(s.validTicket(sessionToken, "draft-1"), "a session is not a ticket");
        assertFalse(s.validTicket("t." + sessionToken, "draft-1"));
        assertNull(s.renew(ticket), "a ticket cannot be renewed into a session");
        assertNull(s.issueTicket(null));
        assertNull(s.issueTicket("  "));
    }

    @Test
    void aTicketFromAnotherAdminTokenOrTamperedIsRefused() {
        String ticket = session("old-admin-token", 300, T0).issueTicket("d");
        assertFalse(session("new-admin-token", 300, T0).validTicket(ticket, "d"));
        LeagueSession s = session(ADMIN, 300, T0);
        String good = s.issueTicket("d");
        String[] p = good.substring(2).split("\\.");
        assertFalse(s.validTicket("t." + (Long.parseLong(p[0]) + 100) + "." + p[1], "d"), "extended expiry");
        assertFalse(s.validTicket("t.0" + p[0] + "." + p[1], "d"), "leading zero");
        assertFalse(s.validTicket("t.+" + p[0] + "." + p[1], "d"), "sign");
        assertFalse(s.validTicket("t.", "d"));
        assertFalse(s.validTicket("t.1.2.3", "d"));
        assertFalse(s.validTicket("1.2", "d"));
    }
}
