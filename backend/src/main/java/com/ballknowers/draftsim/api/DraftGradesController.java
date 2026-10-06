package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.DraftGradesService;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Draft grades (specs/018-draft-grades, contract C1): how each pick of a completed draft actually
 * played out, and how each team's draft did. Scoped like {@code /board}: a draft the caller cannot
 * see is a 404.
 */
@RestController
@RequestMapping("/api")
public class DraftGradesController {

    private final DraftGradesService grades;
    private final LeagueMembership membership;

    public DraftGradesController(DraftGradesService grades, LeagueMembership membership) {
        this.grades = grades;
        this.membership = membership;
    }

    @GetMapping("/drafts/{sleeperDraftId}/grades")
    public ResponseEntity<DraftGradesService.DraftGrades> grades(@PathVariable String sleeperDraftId,
            @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        Optional<DraftRepository.DraftRow> found = membership.visibleDraft(sleeperUserId, sleeperDraftId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(grades.read(found.get()));
    }
}
