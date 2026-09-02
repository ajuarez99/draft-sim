package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.config.OwnerProperties;
import com.ballknowers.draftsim.ingest.BoardService;
import com.ballknowers.draftsim.ingest.LiveDraftPoller;
import com.ballknowers.draftsim.profile.ProfileService;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * POST /api/drafts/{id}/track is the draft-night diagnostic endpoint, and it used
 * to build its response with Map.of(..., "status", r.status()). Map.of throws
 * NullPointerException on a null value, draft.status is a nullable column, and NPE
 * isn't handled by ErrorHandler -- so a null status came back as a bare 500 from
 * the one endpoint that has to work at 8:15 PM.
 *
 * Same class of bug as claude/lessons.md #12, and the same fix: a LinkedHashMap,
 * which carries the null through as JSON null.
 */
@ExtendWith(MockitoExtension.class)
class LeagueControllerTrackTest {

    @Mock private LeagueRepository leagues;
    @Mock private DraftRepository drafts;
    @Mock private ProfileService profiles;
    @Mock private BoardService boards;
    @Mock private LiveDraftPoller poller;
    @Mock private ManagerRepository managers;
    @Mock private PlayerRepository players;
    @Mock private OwnerProperties owner;

    private LeagueController controller() {
        return new LeagueController(leagues, drafts, profiles, boards, poller, managers, players, owner);
    }

    private static DraftRepository.DraftRow row(String status) {
        return new DraftRepository.DraftRow(1L, 10L, "d1", 2026, 15, 14, status, Map.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void trackWithANullStatusReturns200AndSerializesStatusAsJsonNull() throws Exception {
        DraftRepository.DraftRow draft = row(null);
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(draft));
        when(poller.track(draft)).thenReturn(new LiveDraftPoller.TrackResult(true, null, 0, true, true));

        ResponseEntity<?> response = controller().track("d1");

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("status"), "status key must be present even when null");
        assertNull(body.get("status"));

        // Map.of would have thrown at construction, so getting here is already the
        // regression -- but force real serialization too, the way the endpoint does.
        String json = new ObjectMapper().writeValueAsString(body);
        assertTrue(json.contains("\"status\":null"), json);
    }

    /**
     * seatsMapped is the draft-night diagnostic the endpoint exists to carry: zero
     * means every seat is an unattributed league-average bot.
     */
    @Test
    @SuppressWarnings("unchecked")
    void trackReportsTheSeatCountAlongsideTheLeagueSize() {
        DraftRepository.DraftRow draft = row("drafting");
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(draft));
        when(poller.track(draft)).thenReturn(new LiveDraftPoller.TrackResult(false, "drafting", 14, true, true));

        Map<String, Object> body = (Map<String, Object>) controller().track("d1").getBody();

        assertNotNull(body);
        assertEquals("drafting", body.get("status"));
        assertEquals(14, body.get("seatsMapped"));
        assertEquals(14, body.get("teams"));
        assertEquals(true, body.get("tracking"));
        assertEquals(true, body.get("alreadyTracking"),
                "started=false with a poller running means something was already tracking");
        assertEquals(true, body.get("observed"));
    }

    /**
     * track() deliberately spawns nothing for a complete draft, so started=false --
     * which the response used to read as "somebody else is already tracking it". It
     * reported "tracking": true, "alreadyTracking": true for a draft nothing was
     * polling, on the one endpoint whose entire job is telling you the truth about
     * what is running.
     */
    @Test
    @SuppressWarnings("unchecked")
    void trackOnACompleteDraftReportsNeitherTrackingNorAlreadyTracking() {
        DraftRepository.DraftRow draft = row("complete");
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(draft));
        when(poller.track(draft))
                .thenReturn(new LiveDraftPoller.TrackResult(false, "complete", 14, true, false));

        Map<String, Object> body = (Map<String, Object>) controller().track("d1").getBody();

        assertNotNull(body);
        assertEquals(false, body.get("tracking"), "nothing polls a finished draft");
        assertEquals(false, body.get("alreadyTracking"));
        assertEquals("complete", body.get("status"));
    }

    /**
     * A Sleeper hiccup makes track() fall back to the stale DB status -- exactly the
     * failure the synchronous tick was added to prevent. The label is the only thing
     * that keeps that from being silent.
     */
    @Test
    @SuppressWarnings("unchecked")
    void trackMarksAStatusThatCameFromTheDbRatherThanSleeper() {
        DraftRepository.DraftRow draft = row("pre_draft");
        when(drafts.bySleeperId("d1")).thenReturn(Optional.of(draft));
        when(poller.track(draft))
                .thenReturn(new LiveDraftPoller.TrackResult(true, "pre_draft", 0, false, true));

        Map<String, Object> body = (Map<String, Object>) controller().track("d1").getBody();

        assertNotNull(body);
        assertEquals(false, body.get("observed"));
        assertEquals(true, body.get("tracking"));
    }

    @Test
    void trackOnAnUnknownDraftIs404() {
        when(drafts.bySleeperId("nope")).thenReturn(Optional.empty());
        assertEquals(404, controller().track("nope").getStatusCode().value());
    }
}
