package com.ballknowers.draftsim.engine;

/**
 * The one champion rule (specs/047-hall-of-fame T003, research R4). A roster is its season's champion when
 * Sleeper's stored final placement is 1 <b>and</b> the season is complete. The completeness half matters: a
 * {@code final_placement = 1} written before the V21 status gate can sit next to an unfinished season, and
 * must never resurface as a trophy.
 *
 * <p>History's standings rows, the manager career {@code titles} count and the trophy case all call this, so
 * the three can't disagree. (Careers keep their own separate "season counted" gate; that is a counting rule,
 * not a second champion rule -- plan A10.)
 */
public final class ChampionRule {

    private ChampionRule() {}

    public static boolean isChampion(Integer finalPlacement, boolean seasonComplete) {
        return finalPlacement != null && finalPlacement == 1 && seasonComplete;
    }
}
