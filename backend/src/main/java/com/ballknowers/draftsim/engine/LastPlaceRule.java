package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueBracketRepository;
import com.ballknowers.draftsim.store.LeagueLosersBracketRepository;
import com.ballknowers.draftsim.store.LeagueRepository.PlayoffFormat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Who finished last, from Sleeper's losers bracket -- or the reason we won't say (specs/047-hall-of-fame T008,
 * research R2 amended, plan A1). Pure: no Spring, no DB.
 *
 * <p>Why a rule at all: Sleeper stores the losers bracket and its placements but not which end of it is "last"
 * (its {@code loser_bracket_type} setting is null for every real league). Measured: a toilet bowl (losers
 * advance, FBK 2025) and a consolation bracket (winners advance, West Coast 2025) both look like the same
 * 7-match shape. So the direction is read from the stored week scores, and the season refuses to name anyone
 * unless everything lines up. Naming the wrong manager last, publicly, is worse than naming nobody.
 *
 * <p>The rules, in order (the first to fail decides the reason):
 * <ol>
 *   <li>a losers bracket is stored ({@code NO_LOSERS_BRACKET}) and the winners bracket is known, so the
 *       non-playoff set can be derived ({@code NO_WINNERS_BRACKET});
 *   <li>every match is decided ({@code INCOMPLETE});
 *   <li>the rosters in the bracket are exactly the non-playoff rosters ({@code UNCOVERED_ROSTERS});
 *   <li>EVERY match votes, and the votes agree (amended after code review S2/S3): the winner scored less than
 *       the loser means a toilet bowl, more means a consolation bracket. A match with no stored points on either
 *       side is {@code NO_SCORES}; an exact tie, or votes for both directions, is {@code MIXED_DIRECTION}.
 *       Weeks come from {@link RivalryGameKind#roundOfWeek} (two-week finals summed), so there is no second
 *       round-to-week rule. A round type we can't map, or a match whose two rosters did not actually share a
 *       matchup in every mapped week (the round-to-week alignment is wrong for this shape), is
 *       {@code UNSUPPORTED_SHAPE};
 *   <li>the bottom-placement arithmetic holds ({@code UNSUPPORTED_SHAPE}): a consolation bracket needs
 *       {@code playoff_teams + max(p) + 1 == total_rosters}, a toilet bowl needs a decided p=1 match.
 * </ol>
 * Then a toilet bowl names the p=1 winner and a consolation names the loser of the highest-p match.
 */
public final class LastPlaceRule {

    private LastPlaceRule() {}

    public static final String TOILET_BOWL = "TOILET_BOWL";
    public static final String CONSOLATION = "CONSOLATION";

    public static final String NO_LOSERS_BRACKET = "NO_LOSERS_BRACKET";
    public static final String NO_WINNERS_BRACKET = "NO_WINNERS_BRACKET";
    public static final String INCOMPLETE = "INCOMPLETE";
    public static final String UNCOVERED_ROSTERS = "UNCOVERED_ROSTERS";
    public static final String MIXED_DIRECTION = "MIXED_DIRECTION";
    public static final String NO_SCORES = "NO_SCORES";
    public static final String UNSUPPORTED_SHAPE = "UNSUPPORTED_SHAPE";

    /** Stored week points for one roster, or null when that (week, roster) has none. */
    @FunctionalInterface
    public interface WeekPoints {
        Double points(int week, int rosterId);
    }

    /** True when the two rosters were paired against each other (same matchup id) in that week. */
    @FunctionalInterface
    public interface Paired {
        boolean met(int week, int rosterA, int rosterB);
    }

    /**
     * Exactly one of {@code rosterId} / {@code reason} is non-null. {@code bracket} (TOILET_BOWL or CONSOLATION)
     * is set whenever the direction was read, even when a later rule refused.
     */
    public record Result(Integer rosterId, String bracket, String reason) {
        static Result named(int rosterId, String bracket) {
            return new Result(rosterId, bracket, null);
        }

        static Result refused(String reason, String bracket) {
            return new Result(null, bracket, reason);
        }
    }

    /**
     * @param nonPlayoffRosters the season's rosters minus every roster of its stored winners bracket; null when
     *                          there is no winners bracket to subtract
     */
    public static Result evaluate(List<LeagueLosersBracketRepository.Match> matches, PlayoffFormat format,
                                  int totalRosters, Set<Integer> nonPlayoffRosters, WeekPoints points,
                                  Paired paired) {
        if (matches == null || matches.isEmpty()) return Result.refused(NO_LOSERS_BRACKET, null);
        if (nonPlayoffRosters == null) return Result.refused(NO_WINNERS_BRACKET, null);

        // 1. every match decided
        for (var m : matches) {
            if (m.winnerRosterId() == null || m.loserRosterId() == null) return Result.refused(INCOMPLETE, null);
        }

        // 2. exactly the non-playoff rosters
        Set<Integer> inBracket = new HashSet<>();
        for (var m : matches) {
            for (Integer r : new Integer[]{m.t1RosterId(), m.t2RosterId(), m.winnerRosterId(), m.loserRosterId()}) {
                if (r != null) inBracket.add(r);
            }
        }
        if (!inBracket.equals(nonPlayoffRosters)) return Result.refused(UNCOVERED_ROSTERS, null);

        // 3. direction, from the stored scores
        if (format == null || format.playoffWeekStart() < 2 || format.playoffRoundType() == null) {
            return Result.refused(UNSUPPORTED_SHAPE, null);
        }
        List<LeagueBracketRepository.Match> asBracket = new ArrayList<>();
        for (var m : matches) {
            asBracket.add(new LeagueBracketRepository.Match(m.leagueId(), m.matchId(), m.round(), m.t1RosterId(),
                    m.t2RosterId(), m.winnerRosterId(), m.loserRosterId(), m.placement()));
        }
        int maxRound = matches.stream().mapToInt(LeagueLosersBracketRepository.Match::round).max().orElse(0);
        boolean toilet = false, consolation = false, unscored = false, tied = false;
        for (var m : matches) {
            List<Integer> weeks = new ArrayList<>();
            // Candidate weeks: the format's start through one past the last round (a two-week final).
            for (int wk = format.playoffWeekStart(); wk <= format.playoffWeekStart() + maxRound; wk++) {
                RivalryGameKind.Round rd = RivalryGameKind.roundOfWeek(format, asBracket, wk);
                if (rd.round() != null && rd.round() == m.round()) weeks.add(wk);
            }
            if (weeks.isEmpty()) return Result.refused(UNSUPPORTED_SHAPE, null);
            // S3: the mapping is only trusted when the two rosters really played each other in every mapped week.
            for (int wk : weeks) {
                if (paired == null || !paired.met(wk, m.winnerRosterId(), m.loserRosterId())) {
                    return Result.refused(UNSUPPORTED_SHAPE, null);
                }
            }
            Double w = sum(points, weeks, m.winnerRosterId());
            Double l = sum(points, weeks, m.loserRosterId());
            if (w == null || l == null) { unscored = true; continue; }   // S2: every match must vote
            int cmp = Double.compare(w, l);
            if (cmp < 0) toilet = true;
            else if (cmp > 0) consolation = true;
            else tied = true;
        }
        if (toilet && consolation) return Result.refused(MIXED_DIRECTION, null);
        if (unscored) return Result.refused(NO_SCORES, null);
        if (tied) return Result.refused(MIXED_DIRECTION, null);
        String bracket = toilet ? TOILET_BOWL : CONSOLATION;

        // 4. the bottom-placement arithmetic
        if (toilet) {
            for (var m : matches) {
                if (m.placement() != null && m.placement() == 1) return Result.named(m.winnerRosterId(), bracket);
            }
            return Result.refused(UNSUPPORTED_SHAPE, bracket);
        }
        int maxP = 0;
        LeagueLosersBracketRepository.Match bottom = null;
        for (var m : matches) {
            if (m.placement() != null && m.placement() > maxP) {
                maxP = m.placement();
                bottom = m;
            }
        }
        if (bottom == null || format.playoffTeams() + maxP + 1 != totalRosters) {
            return Result.refused(UNSUPPORTED_SHAPE, bracket);
        }
        return Result.named(bottom.loserRosterId(), bracket);
    }

    /** Sum over the weeks, or null if any week lacks stored points for the roster. */
    private static Double sum(WeekPoints points, List<Integer> weeks, int roster) {
        double total = 0;
        for (int wk : weeks) {
            Double p = points.points(wk, roster);
            if (p == null) return null;
            total += p;
        }
        return total;
    }
}
