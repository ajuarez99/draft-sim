package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.engine.SimulationRequest;
import com.ballknowers.draftsim.engine.SimulationResult;
import com.ballknowers.draftsim.engine.SimulationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/sims")
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);
    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final SimulationService sims;

    public SimulationController(SimulationService sims) {
        this.sims = sims;
    }

    /** Blocking. Fine for a few hundred iterations; use the stream for more. */
    @PostMapping
    public SimulationResult run(@RequestBody SimulationRequest request) {
        return sims.simulate(request, null);
    }

    /**
     * Streams progress while the run is in flight, then one final "result" event.
     * The board fills in as iterations land rather than blocking on all of them.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody SimulationRequest request) {
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
                    } catch (IOException e) {
                        // client went away; the run will finish and be discarded
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
                } catch (IOException ignored) {
                    // nothing left to tell
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
