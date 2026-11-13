package com.ballknowers.draftsim.historical;

/**
 * The Time Machine badge catalog (spec 064), in display order.
 *
 * <p><b>Every number here is hand-set and arbitrary</b>: the XP values, the thresholds in the rule text and the
 * thresholds {@link AchievementRules} applies. None is derived from data, and only three were ever looked at against
 * play: BEAT_HINDSIGHT, CLEAN_SHEET and BIG_BUST were retuned on 2026-11-13 from counts over 81 mostly auto-drafted
 * games (see the comment in {@link AchievementRules}), which is a small sample and not a fit. The rest are in the same
 * spirit as {@code config/weights.yml}. They are shown to the fan as numbers so a change is visible. XP is frozen
 * onto the badge row at unlock, so retuning here never rewrites an earlier unlock.
 *
 * <p>{@link Source#STORED} badges can be proven from a game's saved result, so games finished before spec 064
 * can grant them. {@link Source#NEW_GAMES} badges need per-pick season values or box-score weeks, captured only
 * for games finished after it shipped.
 *
 * <p>R below is the number of teams, one round of picks. "Rank" is the season-value rank among every drafted
 * player (1 = best), the same one the bust callout uses.
 */
public enum Badge {
    FIRST_GAME("First trip back", "Finish a game.", 50, Source.STORED),
    WINS_1("First win", "Win a weekly matchup, regular season or playoffs.", 50, Source.STORED),
    WINS_10("10 wins", "Win 10 weekly matchups across all your games.", 100, Source.STORED),
    WINS_25("25 wins", "Win 25 weekly matchups across all your games.", 200, Source.STORED),
    WINS_50("50 wins", "Win 50 weekly matchups across all your games.", 300, Source.STORED),
    WINS_100("100 wins", "Win 100 weekly matchups across all your games.", 500, Source.STORED),
    PLAYOFFS("Made the bracket", "Reach the playoffs. A season with no playoffs cannot earn it.", 100,
            Source.STORED),
    PLAYOFF_WIN("Survive and advance", "Win a playoff game. A season with no playoffs cannot earn it.", 100,
            Source.STORED),
    CHAMPION("Champion", "Win the title. A season with no playoffs cannot earn it.", 200, Source.STORED),
    TITLES_3("Dynasty", "Win three titles.", 500, Source.STORED),
    BOTH_SPORTS("Two-sport champion", "Win a title in basketball and a title in football.", 300, Source.STORED),
    TOP_SEED("Best record", "Finish the regular season in first place.", 100, Source.STORED),
    PERFECT_SEASON("Perfect regular season",
            "Finish a regular season of at least 10 weeks with no losses and no ties.", 300, Source.STORED),
    BEAT_HINDSIGHT("Near-perfect draft",
            "Score at least 85% of the greedy hindsight draft's points in basketball, or 75% in football.", 200,
            Source.STORED),
    CLEAN_SHEET("Steady hands",
            "No more than two of your picks finished a full round or more below the slot where you took them.", 100,
            Source.NEW_GAMES),
    BIG_BUST("Swing and a miss",
            "A pick in round 1 or 2 finished in the bottom third of all drafted players by season value. "
                    + "Games finished before this was added only check your single biggest bust.", 50, Source.STORED),
    WOODEN_SPOON("Wooden spoon", "Finish last.", 50, Source.STORED),
    REACH_HIT("Called it",
            "Take a player in the first half of the draft at least a full round of picks ahead of his "
                    + "board rank, and he beat his pick number by a full round, or in round 1 finished in the "
                    + "top half of it.", 100, Source.NEW_GAMES),
    REACH_MISS("Galaxy brain",
            "Take a player in the first half of the draft at least a full round of picks ahead of his "
                    + "board rank, and he finishes at least two full rounds below your pick number.", 50, Source.NEW_GAMES),
    LATE_GEM("Late-round gem",
            "A pick in the second half of the draft finishes in the top full round of players by season value.", 100, Source.NEW_GAMES),
    GONE_EARLY("Gone by week 3",
            "A pick in rounds 1 to 3 whose last box score in our data came in week 3 or earlier "
                    + "(seasons of 10 or more weeks). The data can be missing rows, so this says what our data "
                    + "shows, not why.", 50, Source.NEW_GAMES);

    public enum Source { STORED, NEW_GAMES }

    private final String displayName;
    private final String rule;
    private final int xp;
    private final Source source;

    Badge(String displayName, String rule, int xp, Source source) {
        this.displayName = displayName;
        this.rule = rule;
        this.xp = xp;
        this.source = source;
    }

    public String code() {
        return name();
    }

    public String displayName() {
        return displayName;
    }

    public String rule() {
        return rule;
    }

    public int xp() {
        return xp;
    }

    public Source source() {
        return source;
    }
}
