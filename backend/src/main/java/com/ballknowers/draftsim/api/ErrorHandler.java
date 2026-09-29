package com.ballknowers.draftsim.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.ballknowers.draftsim.engine.SimulationCancelledException;

import java.util.Map;

@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", message(e)));
    }

    /** Almost always "you have not run ingest yet". Say so rather than a 500. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", message(e)));
    }

    /** Every simulation permit is taken. Fast refusal, not a queue: see {@link SimulationPermits}. */
    @ExceptionHandler(SimulationBusyException.class)
    public ResponseEntity<Map<String, String>> busy(SimulationBusyException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(SimulationBusyException.RETRY_AFTER_SECONDS))
                // Explicit, because /api/sims/stream is produces=text/event-stream: a
                // client whose Accept says only that would otherwise turn this 429
                // into a 500 while trying to negotiate a JSON body.
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("error", message(e)));
    }

    /** A newer run from the same signed-in user took this one's place. */
    @ExceptionHandler(SimulationCancelledException.class)
    public ResponseEntity<Map<String, String>> superseded(SimulationCancelledException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("error", message(e)));
    }

    // Map.of rejects a null value outright, and e.getMessage() is null for any
    // exception constructed without one — String.valueOf(null) used to paper
    // over that with the literal string "null", indistinguishable from a real
    // message. A real fallback is more useful to whoever reads this than either.
    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
