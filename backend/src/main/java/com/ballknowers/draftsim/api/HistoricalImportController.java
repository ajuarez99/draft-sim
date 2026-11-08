package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.historical.HistoricalDataRepository.ImportJobRow;
import com.ballknowers.draftsim.historical.HistoricalImportService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The historical-data import (spec 053 T013, contracts/api.md amendment box): {@code POST} starts an async job and
 * answers 202 with its id, {@code GET} reads the job and its report.
 *
 * <p>Admin gating is {@code WebConfig}'s {@code /api/admin/**} interceptor (403 without the token), not a check
 * here: a second one would be a second definition of "admin" that could drift from the first. A second
 * concurrent start is a 409 (an {@link IllegalStateException}, see {@code ErrorHandler}).
 */
@RestController
@RequestMapping("/api/admin/historical/import")
public class HistoricalImportController {

    private final HistoricalImportService imports;
    private final ObjectMapper json;

    public HistoricalImportController(HistoricalImportService imports, ObjectMapper json) {
        this.imports = imports;
        this.json = json;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@RequestBody(required = false) Map<String, String> body) {
        String part = body == null ? null : body.get("part");
        // Optional, NFLVERSE only (spec 056): re-import a range of seasons instead of all 27 files.
        Integer from = seasonOf(body, "fromSeason"), to = seasonOf(body, "toSeason");
        long jobId = imports.start(part, from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", jobId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
    }

    private static Integer seasonOf(Map<String, String> body, String key) {
        String v = body == null ? null : body.get(key);
        if (v == null || v.isBlank()) return null;
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a year, got '" + v + "'");
        }
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<Map<String, Object>> job(@PathVariable long jobId) {
        return imports.job(jobId).map(this::view)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    private Map<String, Object> view(ImportJobRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", row.id());
        out.put("part", row.part());
        out.put("status", row.status());
        out.put("startedAt", row.startedAt() == null ? null : row.startedAt().toString());
        out.put("finishedAt", row.finishedAt() == null ? null : row.finishedAt().toString());
        out.put("report", parse(row.reportJson()));
        return out;
    }

    private JsonNode parse(String text) {
        if (text == null) return null;
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
