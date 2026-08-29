package com.ballknowers.draftsim.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    // Map.of rejects a null value outright, and e.getMessage() is null for any
    // exception constructed without one — String.valueOf(null) used to paper
    // over that with the literal string "null", indistinguishable from a real
    // message. A real fallback is more useful to whoever reads this than either.
    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
