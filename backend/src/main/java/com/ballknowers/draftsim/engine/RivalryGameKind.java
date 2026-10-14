package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.LeagueBracketRepository.Match;
import com.ballknowers.draftsim.store.LeagueRepository.PlayoffFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which kind of game a scored, paired meeting was (specs/034-rivalry-schedule-grid T011, data-model
 * "GameKind" as amended by plan.md F2/F4).
 *
 * <p>A playoff WEEK is not a playoff GAME: Sleeper also pairs the consolation teams in those weeks
 * (research R2, measured: 7 of 14 in a 6-team league). So a game in a playoff week is classified against
 * the stored winners bracket, and only against the bracket row(s) of THAT WEEK'S ROUND (F2) -- matching on
 * the pair alone would call a stale provisional bracket's pairing a fact.
 *
 * <ul>
 *   <li>{@link Kind#REGULAR}: before the playoff start, or the league has no playoffs (start &lt; 2).
 *   <li>{@link Kind#PLAYOFF}: a bracket row of the week's round has the pair, with placement null
 *       (elimination) or 1 (the final).
 *   <li>{@link Kind#PLACEMENT}: same, but a 3rd/5th-place game (F4): not a "playoff meeting" for scoring
 *       or for the PLAYOFF_HISTORY reason.
 *   <li>{@link Kind#CONSOLATION}: the round's rows are all settled and none has the pair.
 *   <li>{@link Kind#POSTSEASON_UNSPLIT}: it can't be told, with a reason code: {@code NO_BRACKET},
 *       {@code BRACKET_PENDING} (a slot of that round is still unfilled), {@code ROUND_TYPE_UNKNOWN},
 *       {@code ROUND_TYPE_UNSUPPORTED}, {@code ROUND_UNKNOWN} (the week maps to no bracket round).
 * </ul>
 *
 * <p>Week to round: round type 0 is {@code start + r - 1}. Type 1 is the same, except the last round
 * covers two weeks (research R2, measured on West Coast 2025, where the two legs of the final were paired
 * in weeks 17 and 18). Any other type is refused, not guessed. This class holds no Spring and no DB.
 */
public final class RivalryGameKind {

    public enum Kind { REGULAR, PLAYOFF, PLACEMENT, CONSOLATION, POSTSEASON_UNSPLIT }

    private RivalryGameKind() {}

    /** {@code match} is the bracket row for PLAYOFF/PLACEMENT, else null. {@code unsplitReason} only for UNSPLIT. */
    public record Classification(Kind kind, String unsplitReason, Match match) {
        static Classification of(Kind k) {
            return new Classification(k, null, null);
        }
    }

    /** A week's bracket round, or the reason it has none. */
    public record Round(Integer round, String reason) {}

    public static boolean isPlayoffWeek(PlayoffFormat format, int week) {
        return format != null && format.playoffWeekStart() >= 2 && week >= format.playoffWeekStart();
    }

    public static Classification classify(PlayoffFormat format, List<Match> bracket, int week,
                                          int rosterA, int rosterB) {
        if (!isPlayoffWeek(format, week)) return Classification.of(Kind.REGULAR);
        if (bracket == null || bracket.isEmpty()) return unsplit("NO_BRACKET");
        Round rd = roundOfWeek(format, bracket, week);
        if (rd.round() == null) return unsplit(rd.reason());
        List<Match> rows = inRound(bracket, rd.round());
        if (rows.isEmpty()) return unsplit("ROUND_UNKNOWN");
        // A round with a still-unfilled slot is a bracket that has not settled: the whole week is unsplit,
        // never "consolation" (F2). Pre-playoff seasons hold a provisional bracket exactly like this.
        for (Match m : rows) {
            if (m.t1RosterId() == null || m.t2RosterId() == null) return unsplit("BRACKET_PENDING");
        }
        for (Match m : rows) {
            boolean same = (m.t1RosterId() == rosterA && m.t2RosterId() == rosterB)
                    || (m.t1RosterId() == rosterB && m.t2RosterId() == rosterA);
            if (!same) continue;
            boolean elimination = m.placement() == null || m.placement() == 1;
            return new Classification(elimination ? Kind.PLAYOFF : Kind.PLACEMENT, null, m);
        }
        return Classification.of(Kind.CONSOLATION);
    }

    private static Classification unsplit(String reason) {
        return new Classification(Kind.POSTSEASON_UNSPLIT, reason, null);
    }

    /** The bracket round a playoff week belongs to, or why it has none. */
    public static Round roundOfWeek(PlayoffFormat format, List<Match> bracket, int week) {
        Integer type = format.playoffRoundType();
        if (type == null) return new Round(null, "ROUND_TYPE_UNKNOWN");
        int maxRound = bracket.stream().mapToInt(Match::round).max().orElse(0);
        int r = week - format.playoffWeekStart() + 1;
        if (type == 0) {
            return r >= 1 && r <= maxRound ? new Round(r, null) : new Round(null, "ROUND_UNKNOWN");
        }
        if (type == 1) {
            // The last round spans two weeks: the week after it maps to the same (final) round.
            return r >= 1 && r <= maxRound + 1 ? new Round(Math.min(r, maxRound), null) : new Round(null, "ROUND_UNKNOWN");
        }
        return new Round(null, "ROUND_TYPE_UNSUPPORTED");
    }

    public static List<Match> inRound(List<Match> bracket, int round) {
        List<Match> out = new ArrayList<>();
        for (Match m : bracket) if (m.round() == round) out.add(m);
        return out;
    }

    /**
     * The opponent the bracket itself names for {@code roster} in this week's round, only when BOTH slots of
     * that row are set. Empty when the round is unmapped or the roster isn't in a settled row. Used for the
     * season grid's week being played, whose pairings are stored only once it is scored (F7).
     */
    public static Optional<Integer> bracketOpponent(PlayoffFormat format, List<Match> bracket, int week, int roster) {
        if (!isPlayoffWeek(format, week) || bracket == null || bracket.isEmpty()) return Optional.empty();
        Round rd = roundOfWeek(format, bracket, week);
        if (rd.round() == null) return Optional.empty();
        for (Match m : inRound(bracket, rd.round())) {
            if (m.t1RosterId() == null || m.t2RosterId() == null) continue;
            if (m.t1RosterId() == roster) return Optional.of(m.t2RosterId());
            if (m.t2RosterId() == roster) return Optional.of(m.t1RosterId());
        }
        return Optional.empty();
    }

    /**
     * Whether the bracket itself says {@code roster} sits this week out (F8): it holds a slot in round
     * {@code r + 1} but none in round {@code r}, i.e. it was seeded straight past this round. Derived from
     * the slots alone, so it needs no {@code *_from} column. A roster merely missing from the bracket was
     * eliminated or never qualified, which is not a bye.
     */
    public static boolean bracketBye(PlayoffFormat format, List<Match> bracket, int week, int roster) {
        if (!isPlayoffWeek(format, week) || bracket == null || bracket.isEmpty()) return false;
        Round rd = roundOfWeek(format, bracket, week);
        if (rd.round() == null) return false;
        int r = rd.round();
        if (holds(inRound(bracket, r), roster)) return false;
        return holds(inRound(bracket, r + 1), roster);
    }

    private static boolean holds(List<Match> rows, int roster) {
        for (Match m : rows) {
            if ((m.t1RosterId() != null && m.t1RosterId() == roster)
                    || (m.t2RosterId() != null && m.t2RosterId() == roster)) return true;
        }
        return false;
    }
}
