package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.usage.UsageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The owner's usage read (spec 076). Admin gating is {@code WebConfig}'s {@code /api/admin/**} interceptor, not a
 * check here: a second one would be a second definition of "admin" that could drift from the first.
 */
@RestController
@RequestMapping("/api/admin/usage")
public class AdminUsageController {

    static final int MAX_SPAN_DAYS = 366;
    static final int DEFAULT_SPAN_DAYS = 30;

    private final UsageService usage;

    public AdminUsageController(UsageService usage) {
        this.usage = usage;
    }

    /** {@code from}/{@code to} are ET days, inclusive. Defaults: to = today, from = to - 29 days. */
    @GetMapping
    public ResponseEntity<?> get(@RequestParam(value = "from", required = false) String from,
                                 @RequestParam(value = "to", required = false) String to) {
        LocalDate toDay;
        LocalDate fromDay;
        try {
            toDay = blank(to) ? usage.today() : LocalDate.parse(to.trim());
            fromDay = blank(from) ? toDay.minusDays(DEFAULT_SPAN_DAYS - 1) : LocalDate.parse(from.trim());
        } catch (DateTimeParseException e) {
            return badRange("from and to must be YYYY-MM-DD dates");
        }
        if (fromDay.isAfter(toDay)) return badRange("from must not be after to");
        if (ChronoUnit.DAYS.between(fromDay, toDay) + 1 > MAX_SPAN_DAYS) {
            return badRange("the range may span at most " + MAX_SPAN_DAYS + " days");
        }
        return ResponseEntity.ok(usage.report(fromDay, toDay));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseEntity<Map<String, Object>> badRange(String message) {
        // Mutable map, not Map.of: house rule for response bodies.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "bad_range");
        body.put("message", message);
        return ResponseEntity.badRequest().body(body);
    }
}
