package com.ballknowers.draftsim.api;

import com.ballknowers.draftsim.usage.UsageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public usage collection (spec 076, contracts/usage-api.md). Every request that reaches this handler answers
 * 204 with no body: counted, refused and limited sends look the same to the sender, and nothing here can throw
 * into the page. It reads NO identity header (no X-Sleeper-User, X-Guest-Id, X-League-Session), and logs no
 * body, address or user agent.
 *
 * <p>The body is read straight off the stream, at most 1 KB plus one byte, whatever its content type, so a
 * {@code text/plain} beacon works and a huge body is never buffered.
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService usage;

    public UsageController(UsageService usage) {
        this.usage = usage;
    }

    @PostMapping
    public ResponseEntity<Void> collect(HttpServletRequest request) {
        try {
            byte[] raw = request.getInputStream().readNBytes(UsageService.MAX_BODY_BYTES + 1);
            byte[] body = raw.length > UsageService.MAX_BODY_BYTES ? null : raw;
            usage.collect(body, request.getHeader("User-Agent"), address(request));
        } catch (Throwable ignored) {
            // The sender must never learn anything, and a failure here must not reach the page.
        }
        return ResponseEntity.noContent().build();
    }

    /** X-Real-IP (what Railway's proxy documents), else the socket address. Used only as a limiter key. */
    static String address(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        // 45 = the longest textual IPv6 address; anything longer is junk and must not become a map key
        if (real != null && !real.isBlank() && real.trim().length() <= MAX_ADDRESS_CHARS) return real.trim();
        return request.getRemoteAddr();
    }

    private static final int MAX_ADDRESS_CHARS = 45;
}
