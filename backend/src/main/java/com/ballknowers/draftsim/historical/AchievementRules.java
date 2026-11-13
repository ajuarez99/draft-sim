package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.domain.DraftSlot;
import com.ballknowers.draftsim.historical.HistoricalResult.Bust;
import com.ballknowers.draftsim.historical.HistoricalResult.PlayoffMatchup;
import com.ballknowers.draftsim.historical.HistoricalResult.Round;
import com.ballknowers.draftsim.historical.HistoricalResult.Standing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure achievement rules (spec 064): a finished game's facts in, XP lines, badges and moments out. No Spring, no
 * database, no clock, so every rule is unit-tested in both directions.
 *
 * <p>Every number is <b>hand-set and arbitrary</b> (see {@link Badge}); the constants below are labelled the same
 * way. Rank is the season-value rank among every drafted player, 1 = best, so "better" is a SMALLER rank.
 *
 * <p>Titles: a title is {@code userOutcome == CHAMPION} only (amendment A8). First place in a season with no
 * playoffs is {@link Badge#TOP_SEED}, never a title. An old-format result (no {@code format}) has no bracket, so
 * it earns weekly-win XP and the result-only badges that need none.
 */
public final class AchievementRules {

    private AchievementRules() {}

    // Hand-set XP for the game itself (spec 064 "XP and levels"). Arbitrary.
    static final int GAME_XP = 100;
    static final int WIN_XP = 10;
    static final int PLAYOFF_WIN_BONUS_XP = 40;
    static final int TITLE_XP = 250;

    // Hand-set rule thresholds. Arbitrary. R = teams.
    static final int BUST_ROUNDS = 2;
    static final int BUST_ROUNDS_OF_GAP = 3;
    static final int MISS_ROUNDS_OF_GAP = 2;
    static final int GONE_EARLY_ROUNDS = 3;
    static final int GONE_EARLY_WEEK = 3;
    static final int GONE_EARLY_MIN_SEASON_WEEKS = 10;
    static final int PERFECT_MIN_WEEKS = 10;

    // ---- inputs ---------------------------------------------------------------------------------------

    /**
     * What {@code finishDraft} captured for one of the user's picks (amendment A1). {@code lastWeekPlayed} is the
     * last week with a box-score row for him in our data; null if he had none.
     */
    public record PickFact(int pickNo, int round, long playerId, String playerName, int boardRank,
                           int seasonValueRank, double seasonValue, Integer lastWeekPlayed) {}

    /** The stored {@code historical_game.pick_facts} document. */
    public record PickFacts(int seasonWeeks, List<PickFact> picks) {}

    /**
     * @param pickFacts null for a game finished before spec 064 or when the capture failed: the game then settles
     *                  from its saved result only
     */
    public record GameFacts(String sport, int teams, int rounds, int userSlot, HistoricalResult result,
                            PickFacts pickFacts) {}

    /** Career totals AFTER the game being settled is counted. */
    public record CareerTotals(int games, int weeklyWins, int titles, int nbaTitles, int nflTitles) {}

    // ---- outputs --------------------------------------------------------------------------------------

    public record XpLine(String label, int xp) {}

    /** {@code detail} always carries a {@code text} entry saying exactly what earned it. Mutable map, never Map.of. */
    public record BadgeEarn(Badge badge, Map<String, Object> detail) {}

    /** A per-game instance (every reach, bust, gem or early exit). Pays no XP and pops every game it happens. */
    public record Moment(String code, String name, String text, int pickNo, String playerName) {}

    public record GameAward(int xp, int weeklyWins, int playoffWins, boolean title, boolean fromSavedResult,
                            List<XpLine> lines, List<BadgeEarn> badges, List<Moment> moments) {}

    // ---- one game -------------------------------------------------------------------------------------

    public static GameAward evaluate(GameFacts f) {
        HistoricalResult r = f.result();
        boolean newFormat = r.format() != null;
        int slot = f.userSlot();

        Standing mine = null;
        for (Standing s : r.standings()) if (s.slot() == slot) mine = s;
        int regularWins = mine == null ? 0 : mine.wins();

        int playoffWins = 0;
        if (newFormat && r.playoffs() != null) {
            for (Round round : r.playoffs().rounds()) {
                for (PlayoffMatchup m : round.matchups()) if (m.winnerSlot() == slot) playoffWins++;
            }
        }
        boolean title = HistoricalResult.CHAMPION.equals(r.userOutcome());
        boolean madePlayoffs = newFormat && r.playoffs() != null
                && r.playoffs().seeds().stream().anyMatch(s -> s.slot() == slot);

        List<XpLine> lines = new ArrayList<>();
        lines.add(new XpLine("Game finished", GAME_XP));
        if (regularWins > 0) {
            lines.add(new XpLine(newFormat ? plural(regularWins, "regular-season win") : plural(regularWins, "win"),
                    regularWins * WIN_XP));
        }
        if (playoffWins > 0) {
            lines.add(new XpLine(plural(playoffWins, "playoff win") + " (" + (WIN_XP + PLAYOFF_WIN_BONUS_XP)
                    + " XP each)", playoffWins * (WIN_XP + PLAYOFF_WIN_BONUS_XP)));
        }
        if (title) lines.add(new XpLine("Title", TITLE_XP));
        int xp = lines.stream().mapToInt(XpLine::xp).sum();

        List<BadgeEarn> badges = new ArrayList<>();
        if (madePlayoffs) badges.add(earn(Badge.PLAYOFFS, "You were one of the six playoff seeds."));
        if (playoffWins > 0) {
            badges.add(earn(Badge.PLAYOFF_WIN, "You won " + plural(playoffWins, "playoff game") + "."));
        }
        if (title) badges.add(earn(Badge.CHAMPION, "You won the title."));
        if (mine != null && mine.place() == 1) {
            badges.add(earn(Badge.TOP_SEED, "You finished the " + (newFormat ? "regular season" : "season")
                    + " first, at " + record(mine) + "."));
        }
        if (mine != null && mine.losses() == 0 && mine.ties() == 0
                && mine.wins() + mine.losses() + mine.ties() >= PERFECT_MIN_WEEKS) {
            badges.add(earn(Badge.PERFECT_SEASON, "You went " + record(mine) + " in the "
                    + (newFormat ? "regular season" : "season") + "."));
        }
        if (r.hindsight() != null && r.hindsight().userBeatIt()) {
            badges.add(earn(Badge.BEAT_HINDSIGHT, "You scored " + oneDecimal(r.hindsight().userPointsFor())
                    + " against the hindsight draft's " + oneDecimal(r.hindsight().pointsFor()) + "."));
        }
        if (r.biggestBust() == null) {
            badges.add(earn(Badge.CLEAN_SHEET,
                    "None of your " + f.rounds() + " picks finished below where you took him."));
        }
        if (r.userFinish() == f.teams()) {
            badges.add(earn(Badge.WOODEN_SPOON, "You finished " + ordinal(f.teams()) + " of " + f.teams() + "."));
        }

        List<Moment> moments = new ArrayList<>();
        if (f.pickFacts() != null) {
            moments = moments(f);
        } else if (r.biggestBust() != null) {
            // No pick facts: only the single biggest bust is known, so only it can be checked.
            Bust b = r.biggestBust();
            int round = DraftSlot.round(b.pickNo(), f.teams());
            if (round <= BUST_ROUNDS && b.seasonValueRank() - b.pickNo() >= BUST_ROUNDS_OF_GAP * f.teams()) {
                badges.add(bustBadge(b.player() == null ? "Your pick" : b.player().name(), b.pickNo(),
                        b.seasonValueRank(), f.teams() * f.rounds()));
            }
        }
        for (Moment m : moments) {
            Badge b = Badge.valueOf(m.code());
            if (badges.stream().noneMatch(e -> e.badge() == b)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("text", m.text());
                d.put("pickNo", m.pickNo());
                d.put("playerName", m.playerName());
                badges.add(new BadgeEarn(b, d));
            }
        }
        return new GameAward(xp, regularWins + playoffWins, playoffWins, title, f.pickFacts() == null, lines,
                badges, moments);
    }

    /** Every pick-level instance in one game, in pick order. Needs pick facts. */
    static List<Moment> moments(GameFacts f) {
        int teams = f.teams();
        int total = teams * f.rounds();
        int half = f.rounds() / 2;
        List<Moment> out = new ArrayList<>();
        List<PickFact> picks = new ArrayList<>(f.pickFacts().picks());
        picks.sort(Comparator.comparingInt(PickFact::pickNo));
        for (PickFact p : picks) {
            String who = p.playerName();
            boolean reach = p.round() <= half && p.boardRank() - p.pickNo() >= teams;
            String reachText = who + " at pick " + p.pickNo() + ": you took him " + (p.boardRank() - p.pickNo())
                    + " picks ahead of his board rank (" + p.boardRank() + "), and he finished "
                    + ordinal(p.seasonValueRank()) + " of " + total + " drafted players by season value.";
            if (reach && p.seasonValueRank() <= p.pickNo() - hitMargin(p.pickNo(), teams)) {
                out.add(new Moment(Badge.REACH_HIT.code(), Badge.REACH_HIT.displayName(), reachText, p.pickNo(), who));
            }
            if (reach && p.seasonValueRank() - p.pickNo() >= MISS_ROUNDS_OF_GAP * teams) {
                out.add(new Moment(Badge.REACH_MISS.code(), Badge.REACH_MISS.displayName(), reachText, p.pickNo(), who));
            }
            if (p.round() > half && p.seasonValueRank() <= teams) {
                out.add(new Moment(Badge.LATE_GEM.code(), Badge.LATE_GEM.displayName(),
                        who + " at pick " + p.pickNo() + " in round " + p.round() + " finished "
                                + ordinal(p.seasonValueRank()) + " of " + total + " drafted players by season value.",
                        p.pickNo(), who));
            }
            if (p.round() <= GONE_EARLY_ROUNDS && p.lastWeekPlayed() != null
                    && p.lastWeekPlayed() <= GONE_EARLY_WEEK
                    && f.pickFacts().seasonWeeks() >= GONE_EARLY_MIN_SEASON_WEEKS) {
                out.add(new Moment(Badge.GONE_EARLY.code(), Badge.GONE_EARLY.displayName(),
                        who + " (pick " + p.pickNo() + ", round " + p.round()
                                + "): his last box score in our data came in week " + p.lastWeekPlayed() + ".",
                        p.pickNo(), who));
            }
            if (p.round() <= BUST_ROUNDS && p.seasonValueRank() - p.pickNo() >= BUST_ROUNDS_OF_GAP * teams) {
                BadgeEarn e = bustBadge(who, p.pickNo(), p.seasonValueRank(), total);
                out.add(new Moment(Badge.BIG_BUST.code(), Badge.BIG_BUST.displayName(),
                        (String) e.detail().get("text"), p.pickNo(), who));
            }
        }
        return out;
    }

    /**
     * How many places better than his pick number a reach must finish to count as a hit: a full round (R), but
     * never more than half the pick number, so a round-1 pick can be a hit (pick 12 needs a top-6 finish). The
     * "half" is a hand-set, arbitrary choice made by the build session, not by the product owner (spec 064 plan,
     * "Amended after code review").
     */
    static int hitMargin(int pickNo, int teams) {
        return Math.min(teams, pickNo / 2);
    }

    private static BadgeEarn bustBadge(String who, int pickNo, int rank, int total) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("text", who + ", pick " + pickNo + ", finished " + ordinal(rank) + " of " + total
                + " drafted players by season value, " + (rank - pickNo) + " places below where you took him.");
        d.put("pickNo", pickNo);
        d.put("playerName", who);
        return new BadgeEarn(Badge.BIG_BUST, d);
    }

    // ---- career ---------------------------------------------------------------------------------------

    /** The cumulative badges the totals have reached, in catalog order. Pure: the caller keeps only new ones. */
    public static List<BadgeEarn> cumulative(CareerTotals t) {
        List<BadgeEarn> out = new ArrayList<>();
        if (t.games() >= 1) out.add(earn(Badge.FIRST_GAME, "You finished your first game."));
        int[] steps = {1, 10, 25, 50, 100};
        Badge[] wins = {Badge.WINS_1, Badge.WINS_10, Badge.WINS_25, Badge.WINS_50, Badge.WINS_100};
        for (int i = 0; i < steps.length; i++) {
            if (t.weeklyWins() >= steps[i]) {
                out.add(earn(wins[i], "You have " + plural(t.weeklyWins(), "weekly win") + " across your games."));
            }
        }
        if (t.titles() >= 3) out.add(earn(Badge.TITLES_3, "You have won " + t.titles() + " titles."));
        if (t.nbaTitles() >= 1 && t.nflTitles() >= 1) {
            out.add(earn(Badge.BOTH_SPORTS, "You have won a basketball title and a football title."));
        }
        return out;
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private static BadgeEarn earn(Badge b, String text) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("text", text);
        return new BadgeEarn(b, d);
    }

    private static String record(Standing s) {
        return s.wins() + "-" + s.losses() + (s.ties() > 0 ? "-" + s.ties() : "");
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String oneDecimal(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = (mod100 >= 11 && mod100 <= 13) ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }
}
