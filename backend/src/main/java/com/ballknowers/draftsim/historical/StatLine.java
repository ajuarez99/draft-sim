package com.ballknowers.draftsim.historical;

import java.util.List;

/**
 * One player-season card line, whatever the sport (spec 056 T005). The board builder reads lines only through
 * this interface, so basketball's {@code SeasonLine} and football's season line are interchangeable to it.
 */
public interface StatLine {
    long playerId();

    int seasonStart();

    String team();

    /** Null where the source has no age (football). */
    Integer age();

    int games();

    List<String> positions();

    /**
     * The season total of one counting stat, or null when the line did not record it. Callers apply the
     * "an unrecorded counted stat is 0" rule (FR-007), so the null stays visible here. Throws
     * {@link IllegalArgumentException} for a key that is not a counting stat of this sport.
     */
    Double stat(String key);
}
