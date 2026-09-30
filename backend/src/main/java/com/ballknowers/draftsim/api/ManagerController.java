package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.ManualTendencies;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.LeagueMembership;
import com.ballknowers.draftsim.store.ManagerNoteRepository;
import com.ballknowers.draftsim.store.ManagerProfileRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Reading and setting a private note about each manager.
 *
 * Backs the standalone /managers page (web/src/pages/ManagerTendencies.tsx) as
 * well as the per-seat popover inside a draft board.
 *
 * claude/audit-2026-09-28/02-tendency-writes.md: this used to accept stated
 * reachBias/unpredictability from anyone, which blended into effectiveReachBias
 * and so changed every user's simulations. Now only {@code note} is writable, it
 * is private to its author (V25), and every route needs an identity that shares
 * a league with the manager -- no identity or no shared league is a 404 (an
 * empty list for the list route), the same answer for both so a probe learns
 * nothing. The operator's {@code X-Admin-Token} passes the membership check but
 * still cannot write without an author.
 */
@RestController
@RequestMapping("/api/managers")
public class ManagerController {

    /** Matches the conduct list's cap. */
    public static final int MAX_NOTE_LENGTH = 140;

    private final ProfileService profiles;
    private final ManagerProfileRepository repo;
    private final ManagerNoteRepository notes;
    private final LeagueMembership membership;

    public ManagerController(ProfileService profiles, ManagerProfileRepository repo,
                             ManagerNoteRepository notes, LeagueMembership membership) {
        this.profiles = profiles;
        this.repo = repo;
        this.notes = notes;
        this.membership = membership;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "nfl") String sport,
                                          @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Sport s = Sport.fromCode(sport);
        if (LeagueMembership.isAnonymous(sleeperUserId) && !membership.isAdminRequest()) return List.of();
        ProfileService.Fit fit = profiles.fit(s);
        Set<Long> visible = membership.visibleManagerIds(sleeperUserId, fit.profiles().keySet());
        // Only ever this caller's own notes; an operator with no identity has none.
        Map<Long, String> mine = LeagueMembership.isAnonymous(sleeperUserId)
                ? Map.of() : notes.notesBy(sleeperUserId, s);
        List<Map<String, Object>> out = new ArrayList<>();
        fit.profiles().values().stream()
                .filter(p -> visible.contains(p.managerId()))
                .forEach(p -> out.add(describe(p, repo.manualFor(p.managerId(), s),
                        fit, mine.get(p.managerId()))));
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("manager"))));
        return out;
    }

    /**
     * Sets the caller's private note about this manager:
     *
     *   { "note": "drafts his own Bengals" }
     *
     * A null or blank note deletes it. {@code reachBias} and {@code unpredictability}
     * are refused with a 400 when present and non-null -- they are model internals the
     * engine fits from history, not something a person types in. Over
     * {@value #MAX_NOTE_LENGTH} characters (after trimming) is a 400, never a silent cut.
     */
    @PutMapping("/{managerId}/tendencies")
    public ResponseEntity<Map<String, Object>> set(@PathVariable long managerId,
                                                   @RequestParam(defaultValue = "nfl") String sport,
                                                   @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId,
                                                   @RequestBody(required = false) NoteBody body) {
        Sport s = Sport.fromCode(sport);
        // Existence first: a stranger must get the same 404 whatever the body says.
        if (!membership.canSeeManager(sleeperUserId, managerId)) return ResponseEntity.notFound().build();
        if (body != null && (body.reachBias() != null || body.unpredictability() != null)) {
            return ResponseEntity.badRequest().body(error(
                    "reachBias and unpredictability cannot be set: the engine fits them from draft history. "
                            + "Only note is writable."));
        }
        if (LeagueMembership.isAnonymous(sleeperUserId)) {
            return ResponseEntity.badRequest().body(error(
                    "X-Sleeper-User is required to write a note -- a note is private to its author"));
        }
        String note = body == null || body.note() == null ? null : body.note().strip();
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            return ResponseEntity.badRequest().body(error(
                    "note is " + note.length() + " characters; the limit is " + MAX_NOTE_LENGTH));
        }
        if (note == null || note.isEmpty()) {
            notes.delete(sleeperUserId, managerId, s);
            note = null;
        } else {
            notes.save(sleeperUserId, managerId, s, note);
        }
        return ResponseEntity.ok(respond(managerId, s, note));
    }

    /** Deletes only the caller's own note about this manager. */
    @DeleteMapping("/{managerId}/tendencies")
    public ResponseEntity<Map<String, Object>> clear(@PathVariable long managerId,
                                                     @RequestParam(defaultValue = "nfl") String sport,
                                                     @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Sport s = Sport.fromCode(sport);
        if (!membership.canSeeManager(sleeperUserId, managerId)) return ResponseEntity.notFound().build();
        if (LeagueMembership.isAnonymous(sleeperUserId)) {
            return ResponseEntity.badRequest().body(error(
                    "X-Sleeper-User is required to clear a note -- a note is private to its author"));
        }
        notes.delete(sleeperUserId, managerId, s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("managerId", managerId);
        out.put("cleared", true);
        return ResponseEntity.ok(out);
    }

    /**
     * {@code reachBias} and {@code unpredictability} are here only so a request carrying
     * them can be told no, rather than having them silently ignored.
     */
    public record NoteBody(Object reachBias, Object unpredictability, String note) {}

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private Map<String, Object> respond(long managerId, Sport s, String note) {
        ProfileService.Fit fit = profiles.fit(s);
        if (fit.profiles().get(managerId) instanceof ManagerProfile p) {
            return describe(p, repo.manualFor(managerId, s), fit, note);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("managerId", managerId);
        m.put("note", note);
        return m;
    }

    private static Map<String, Object> describe(ManagerProfile p, ManualTendencies manual, ProfileService.Fit fit,
                                                String callerNote) {
        Double empiricalReachBias = fit.empiricalReachBias().get(p.managerId());
        Double relativeReachBias = fit.relativeReachBias().get(p.managerId());
        Double relativeReachStdErr = fit.relativeReachStdErr().get(p.managerId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("managerId", p.managerId());
        m.put("manager", p.displayName());
        m.put("avatarId", p.avatarId());
        m.put("provenance", p.provenance().name());
        // The value the engine will actually use, after blending.
        m.put("effectiveReachBias", Math.round(p.reachBias() * 100) / 100.0);
        // The unshrunk average of this manager's own scoreable picks -- "what
        // they normally pick," independent of any stated belief. Null with no
        // scoreable picks.
        m.put("empiricalReachBias", empiricalReachBias == null ? null : Math.round(empiricalReachBias * 100) / 100.0);
        // Reach measured against the OTHER managers in the same draft(s), not the market
        // board (audit 11: the board runs several picks off for every room). Positive =
        // earlier than the room. The standard error rides with it so the client can say
        // "drafts like the room" inside one SE. Both null with no scoreable picks; the
        // SE is also null with fewer than 2. Display only -- the engine never reads these.
        m.put("relativeReachBias", relativeReachBias == null ? null : Math.round(relativeReachBias * 100) / 100.0);
        m.put("relativeReachStdErr", relativeReachStdErr == null ? null : Math.round(relativeReachStdErr * 100) / 100.0);
        m.put("unpredictability", p.unpredictability());
        m.put("positionalTilt", p.positionalTilt());
        // The caller's own private note, never anything shared.
        m.put("note", callerNote);
        m.put("draftsObserved", p.draftsObserved());
        m.put("picksScored", p.picksScored());
        // reachBias/unpredictability are whatever manual_json holds (nothing can write
        // them now, and V25 cleared them); the note is the caller's alone.
        Map<String, Object> stated = new LinkedHashMap<>();
        stated.put("reachBias", manual.reachBias());
        stated.put("unpredictability", manual.unpredictability());
        stated.put("note", callerNote);
        m.put("stated", stated);
        return m;
    }
}
