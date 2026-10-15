package com.ballknowers.draftsim.domain;

/**
 * Where a league, manager, draft or transaction came from (spec 035, FR-002). Stored in an
 * explicit {@code platform} column and never inferred by parsing a key's prefix.
 *
 * <p>The read side of the app speaks Sleeper's dialect; an ESPN row is translated into it at
 * ingest and carries {@link #ESPN} so the few places that must treat it differently (the draft
 * model, anything that would call Sleeper) can tell.
 */
public enum Platform {
    SLEEPER, ESPN;

    /** Null reads as SLEEPER: the column defaults to it, so a missing value is a pre-V35 row. */
    public static Platform fromCode(String code) {
        if (code == null) return SLEEPER;
        return valueOf(code.toUpperCase(java.util.Locale.ROOT));
    }
}
