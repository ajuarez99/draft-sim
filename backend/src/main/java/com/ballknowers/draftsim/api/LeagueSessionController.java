package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.LeagueSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spec 073 follow-up, second pass. {@code POST /api/admin/session} sits under the admin gate (a wrong or missing
 * {@code X-Admin-Token} never reaches it) and trades the admin token for a short-lived league session. It works
 * whether or not the lock is enabled, so the web lock screen behaves the same against a dev backend.
 *
 * <p>{@code GET /api/league-session} is a locked route that answers 204; it exists so the web can renew (the
 * refreshed token rides on the response header) and confirm a stored session is still good.
 */
@RestController
@RequestMapping("/api")
public class LeagueSessionController {

    /** Mirrored in web/src/api.ts as {@code LeagueSessionIssued}. */
    public record SessionIssued(String token, int expiresInSeconds) {}

    private final LeagueSession sessions;

    public LeagueSessionController(LeagueSession sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/admin/session")
    public ResponseEntity<?> issue() {
        String token = sessions.issue();
        if (token == null) return ResponseEntity.status(403).build();   // unreachable behind the gate; fail closed anyway
        return ResponseEntity.ok(new SessionIssued(token, sessions.idleSeconds()));
    }

    /** Mirrored in web/src/api.ts as {@code StreamTicketIssued}. */
    public record StreamTicketIssued(String ticket, int expiresInSeconds) {}

    /**
     * A single-purpose, 60-second ticket for one draft's live stream (lock fix pass, CR-02). Locked like every other
     * route, so it needs a session (or the admin token); the ticket, not the session, is what goes in the stream URL.
     */
    @PostMapping("/league-session/stream-ticket")
    public ResponseEntity<?> streamTicket(@RequestParam(name = "draftId", required = false) String draftId) {
        String ticket = sessions.issueTicket(draftId);
        if (ticket == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(new StreamTicketIssued(ticket, LeagueSession.TICKET_SECONDS));
    }

    @GetMapping("/league-session")
    public ResponseEntity<Void> renew() {
        return ResponseEntity.noContent().build();
    }
}
