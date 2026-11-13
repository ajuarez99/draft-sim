package com.ballknowers.draftsim.historical;

/**
 * Time Machine levels (spec 064). <b>Hand-set and arbitrary</b>: the curve and the names are a guess at a pace
 * that feels like progress, not something measured. Levels are cosmetic.
 *
 * <p>Level n starts at {@code 100 * n * (n - 1) / 2} total XP (level 2 at 100, 3 at 300, 5 at 1000, 10 at 4500),
 * with no cap. Level is always derived from XP on read and never stored.
 */
public final class Levels {

    private Levels() {}

    /** Total XP at which level {@code n} (n >= 1) begins. */
    public static int startXp(int level) {
        if (level < 1) throw new IllegalArgumentException("level must be >= 1: " + level);
        return 50 * level * (level - 1);
    }

    /** The level a total XP puts you at; negative XP counts as 0. */
    public static int levelFor(long totalXp) {
        long xp = Math.max(0, totalXp);
        int level = 1;
        while (startXp(level + 1) <= xp) level++;
        return level;
    }

    public static String name(int level) {
        if (level <= 2) return "Rookie";
        if (level <= 4) return "Starter";
        if (level <= 7) return "Veteran";
        if (level <= 11) return "All-Star";
        if (level <= 16) return "MVP";
        return "Hall of Famer";
    }
}
