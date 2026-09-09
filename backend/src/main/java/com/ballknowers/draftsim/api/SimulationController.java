package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.SimulationRequest;
import com.ballknowers.draftsim.engine.SimulationResult;
import com.ballknowers.draftsim.engine.SimulationService;
import com.ballknowers.draftsim.store.LeagueMembership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/sims")
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);
    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final SimulationService sims;
    private final LeagueMembership membership;

    public SimulationController(SimulationService sims, LeagueMembership membership) {
        this.sims = sims;
        this.membership = membership;
    }

    /**
     * The scoping every {@code /api/drafts/{id}/...} route has and this one did
     * not.
     *
     * A simulation is addressed by a draft id and answers with that draft's
     * board, its seat map and every seat's fitted manager profile -- the same
     * data GET /api/drafts/{id}/seats and /board return, reached by a different
     * verb. Scoping those two while leaving this open meant the boundary was
     * decoration: anyone could read a stranger's league by POSTing its draft id
     * here instead of GETting it there.
     *
     * Same message for "no such draft" and "not yours", and the same message
     * {@link SimulationService} already throws for a genuinely missing draft, so
     * this endpoint does not become a way to test whether a draft id exists.
     */
    private void requireVisible(SimulationRequest request, String sleeperUserId) {
        if (membership.visibleDraft(sleeperUserId, request.draftSleeperId()).isEmpty()) {
            throw new IllegalArgumentException("draft " + request.draftSleeperId() + " not ingested");
        }
    }

    /** Blocking. Fine for a few hundred iterations; use the stream for more. */
    @PostMapping
    public SimulationResult run(@RequestBody SimulationRequest request,
                                @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        requireVisible(request, sleeperUserId);
        return sims.simulate(request, null);
    }

    /**
     * Streams progress while the run is in flight, then one final "result" event.
     * The board fills in as iterations land rather than blocking on all of them.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody SimulationRequest request,
                             @RequestHeader(value = "X-Sleeper-User", required = false) String sleeperUserId) {
        // Before the emitter, deliberately: this is a POST, so unlike the live
        // stream it carries a real header and can fail as an ordinary 400 rather
        // than as an `error` event on a stream that opened successfully.
        requireVisible(request, sleeperUserId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        Thread.ofVirtual().name("sim-stream").start(() -> {
            try {
                emitter.send(SseEmitter.event().name("started")
                        .data(Map.of("iterations", request.iterations())));

                SimulationResult result = sims.simulate(request, done -> {
                    try {
                        emitter.send(SseEmitter.event().name("progress").data(Map.of(
                                "completed", done,
                                "total", request.iterations(),
                                "fraction", done / (double) request.iterations())));
                    } catch (Exception e) {
                        // client went away; the run will finish and be discarded.
                        // Exception, not IOException: send() on an already-completed
                        // emitter throws IllegalStateException, which a narrower
                        // catch lets escape -- out of the progress callback, up
                        // through SimulationService, killing the run.
                    }
                });

                emitter.send(SseEmitter.event().name("result").data(result));
                emitter.complete();
            } catch (Exception e) {
                log.warn("simulation stream failed", e);
                try {
                    // Map.of rejects a null value, and a message-less exception has one —
                    // fall back to the class name rather than the literal string "null".
                    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", message)));
                } catch (Exception ignored) {
                    // Nothing left to tell. Exception rather than IOException for the
                    // same reason as above: this send happens on the failure path,
                    // where the emitter is most likely to be already completed, and
                    // an escaping IllegalStateException here would skip
                    // completeWithError below and leak the emitter until timeout.
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
