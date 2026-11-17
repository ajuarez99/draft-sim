package com.ballknowers.draftsim.historical;

import java.util.List;

/**
 * The offered seasons of one sport, as the static site's build and the app read them
 * ({@code GET /api/history/public/seasons?sport=}). Wrapped, not a bare array, so a web build can refuse a backend
 * whose shape it does not know: {@code schema} is {@link #SCHEMA}, and a web build that expects another number fails
 * rather than publishing pages rendered from the wrong shape (PR-04). Bump it when {@link SeasonPage} changes shape.
 * Mirrored in {@code web/src/api.ts}.
 */
public record SeasonList(int schema, String sport, List<SeasonRef> seasons) {

    public static final int SCHEMA = 1;

    /** One season: {@code slug} equals {@code label} and is the address segment of its page. */
    public record SeasonRef(String sport, int seasonStart, String label, String slug) {}
}
