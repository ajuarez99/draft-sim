package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.WaiverPickupAttribution.CompletedAdd;

/**
 * The one definition of which completed adds count as a "pickup" (specs/051-trending-tony-award,
 * plan-review F1; originally research R16 decision 1 of spec 008). Shared by {@link MostAddedPlayers}
 * and {@link TrendingPickups}, because two implementations of one rule is this repo's recurring bug:
 * {@code WaiverPickupAttribution.completedAdds} returns adds of every type, TRADE and COMMISSIONER
 * included, so the filter has to live somewhere both awards read.
 */
public final class PickupRules {

    private PickupRules() {}

    /** A WAIVER or FREE_AGENT add in a real scoring week ({@code week >= 1}). The upper week bound is the caller's. */
    public static boolean isCountedPickup(CompletedAdd a) {
        return ("WAIVER".equals(a.type()) || "FREE_AGENT".equals(a.type())) && a.week() >= 1;
    }
}
