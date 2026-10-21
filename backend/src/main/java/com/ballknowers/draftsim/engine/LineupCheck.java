package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.engine.PlayerStatsService.PlayerRef;
import com.ballknowers.draftsim.sport.SportRules;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lineup check (specs/042-fantasypros-design-features US3, data-model "LineupCheck"): the viewer's live
 * starters that are empty, out, listed, or have no game in the week, each with the bench players who could
 * fill that slot. The payload and the pure rule in one file, like {@code PlayerOwnership}: the rule takes
 * everything as arguments and is unit-tested without a database; {@link LineupCheckService} is the loader.
 *
 * <p><b>Facts, not verdicts</b> (SC-006). A flag says what Sleeper's tag or the schedule says, an option
 * says who is eligible and what he has averaged. Nothing here says who should start. Ordering is the one
 * judgement, and it is named on the flag ({@code optionsOrder}) so the client can say how the list is sorted.
 *
 * <p><b>One "out" rule</b> (review F4): {@link LeagueAnalysisService#isUnavailable} on the injury tag, or
 * {@link SportRules#isSuspended} (NBA suspension is {@code player.status = "SUS"} with no injury tag,
 * measured 2026-10-20, t001 (a)). Any other non-null injury tag is LISTED and is shown as Sleeper wrote it.
 *
 * @param available  false: only {@code reason} (and {@code week}) mean anything
 * @param reason     NOT_MEMBER, NOT_IN_SEASON or ROSTERS_FAILED; null when available
 * @param week       the league's current leg; null when Sleeper has not said
 * @param statusAsOf when the player rows were last refreshed; null = unknown, never read as fresh
 * @param scheduleAsOf when the stored schedule was fetched (research R5: a game played earlier today can still
 *                   read pre_game until the next refresh); null when no schedule is stored
 * @param gameCheck  whether NO_GAME_IN_WEEK could be evaluated (the sport/season has a stored schedule, the
 *                   week is known, and the schedule holds that week)
 * @param gameCheckReason why {@code gameCheck} is false: NO_SCHEDULE, WEEK_UNKNOWN or WEEK_NOT_IN_SCHEDULE;
 *                   null exactly when {@code gameCheck} is true (and on an unavailable check)
 * @param flags      starters with a reason, in starter order; empty means "all starters are active"
 */
public record LineupCheck(boolean available, String reason, Integer week, OffsetDateTime statusAsOf,
                          OffsetDateTime scheduleAsOf, boolean gameCheck, String gameCheckReason,
                          List<LineupFlag> flags) {

    public static final String NOT_MEMBER = "NOT_MEMBER";
    public static final String NOT_IN_SEASON = "NOT_IN_SEASON";
    public static final String ROSTERS_FAILED = "ROSTERS_FAILED";

    /** Flag reasons, in the order a starter's single reason is picked. */
    public static final String EMPTY_SLOT = "EMPTY_SLOT";
    public static final String OUT = "OUT";
    public static final String LISTED = "LISTED";
    public static final String NO_GAME_IN_WEEK = "NO_GAME_IN_WEEK";
    /** A starter with no player row: nothing is known about him, so "all active" can't be said (review S4). */
    public static final String UNKNOWN_PLAYER = "UNKNOWN_PLAYER";

    /** Why the game check didn't run (review S5). */
    public static final String NO_SCHEDULE = "NO_SCHEDULE";
    public static final String WEEK_UNKNOWN = "WEEK_UNKNOWN";
    public static final String WEEK_NOT_IN_SCHEDULE = "WEEK_NOT_IN_SCHEDULE";

    public static final String FP_PER_GAME = "FP_PER_GAME";
    public static final String NAME = "NAME";
    /** {@code isEligible} cannot answer for this slot name (SUPER_FLEX, REC_FLEX, ...), so the options are not "none". */
    public static final String SLOT_UNSUPPORTED = "SLOT_UNSUPPORTED";

    /**
     * One flagged starter. {@code player} is null for an empty slot. {@code playerStatus} is Sleeper's
     * {@code player.status} as stored (NBA suspension reads SUS with a null {@code injuryStatus}).
     */
    public record LineupFlag(String slot, PlayerRef player, String reason, String injuryStatus, String playerStatus,
                             List<BenchOption> options, String optionsOrder) {}

    /**
     * A bench player eligible for the slot. {@code gamesLeft} is null when there is no game check, and is a fact,
     * never a sort key (F6). {@code fpPerGame} is the season average the leaderboard answered, from
     * {@code fpSeason} over {@code fpGames} games: early in a season that is last season's or a handful of
     * games, so both ride along (F10). Null/null/0 when he has no average. {@code injuryStatus} is Sleeper's raw
     * tag for a LISTED bench player (DTD, GTD, Questionable, ...), null for an untagged one; an OUT bench player is
     * not an option at all (review B1).
     */
    public record BenchOption(PlayerRef player, Integer gamesLeft, Double fpPerGame, Integer fpSeason, int fpGames,
                              String injuryStatus) {}

    /** A season average with the season it is from and the games behind it. */
    public record FpAvg(double fpPerGame, int season, int games) {}

    /** What the schedule says about one player's team in the check's week. */
    public interface Schedule {
        boolean hasGameInWeek(Player player);

        /** Counted, unsettled games from today on, in the week. A fact shown beside an option. */
        int gamesLeftInWeek(Player player);
    }

    /** The roster's lists as Sleeper sent them; {@code "0"} in {@code starters} is an empty slot. */
    public record Roster(List<String> starters, List<String> players, List<String> reserve, List<String> taxi) {}

    public static LineupCheck unavailable(String reason, Integer week) {
        return new LineupCheck(false, reason, week, null, null, false, null, List.of());
    }

    // ------------------------------------------------------------------ the rule

    /**
     * @param startingSlots {@code league.rosterPositions()} minus {@code NON_STARTING_SLOTS}; {@code starters[i]}
     *                      sits in {@code startingSlots[i]} (t001 (c): the order matches, the 117 mismatches in
     *                      2,268 entries are position drift). A starter past the end of the slots has no
     *                      label to be judged by and is read as an unsupported slot.
     * @param schedule      null when there is no game check
     * @param fp            sleeper id to average; empty for football or when the leaderboard is unavailable
     * @return flags in starter order. A starter whose player row is missing is flagged UNKNOWN_PLAYER, with no
     *         options: "all starters are active" must not be said about a player nothing is known about (S4).
     */
    public static List<LineupFlag> flags(Roster roster, List<String> startingSlots, Map<String, Player> players,
                                         SportRules rules, Schedule schedule, Map<String, FpAvg> fp) {
        Set<String> starterIds = new HashSet<>();
        for (String s : roster.starters()) if (!isEmpty(s)) starterIds.add(s);
        Set<String> parked = new HashSet<>();
        if (roster.reserve() != null) parked.addAll(roster.reserve());
        if (roster.taxi() != null) parked.addAll(roster.taxi());
        boolean anyAverages = rules.playsMultipleGamesPerScoringPeriod() && !fp.isEmpty();

        List<LineupFlag> out = new ArrayList<>();
        for (int i = 0; i < roster.starters().size(); i++) {
            String id = roster.starters().get(i);
            String slot = i < startingSlots.size() ? startingSlots.get(i) : "?";
            if (isEmpty(id)) {
                out.add(flag(slot, null, EMPTY_SLOT, null, null, roster, starterIds, parked, players, rules,
                        schedule, fp, anyAverages));
                continue;
            }
            Player p = players.get(id);
            if (p == null) {
                out.add(new LineupFlag(slot, new PlayerRef(id, null, List.of(), null, false), UNKNOWN_PLAYER, null,
                        null, List.of(), NAME));
                continue;
            }
            String reason = reasonOf(p, rules, schedule);
            if (reason == null) continue;
            out.add(flag(slot, p, reason, p.injuryStatus(), p.status(), roster, starterIds, parked, players, rules,
                    schedule, fp, anyAverages));
        }
        return out;
    }

    /** OUT, LISTED or NO_GAME_IN_WEEK in that order; null when the player has none (EMPTY_SLOT is the slot's, not his). */
    static String reasonOf(Player p, SportRules rules, Schedule schedule) {
        if (LeagueAnalysisService.isUnavailable(p.injuryStatus()) || rules.isSuspended(p)) return OUT;
        if (p.injuryStatus() != null && !p.injuryStatus().isBlank()) return LISTED;
        if (schedule != null && !schedule.hasGameInWeek(p)) return NO_GAME_IN_WEEK;
        return null;
    }

    private static LineupFlag flag(String slot, Player starter, String reason, String injuryStatus, String status,
                                   Roster roster, Set<String> starterIds, Set<String> parked,
                                   Map<String, Player> players, SportRules rules, Schedule schedule,
                                   Map<String, FpAvg> fp, boolean anyAverages) {
        PlayerRef ref = starter == null ? null : ref(starter);
        if (!slotKnown(rules, slot)) {
            return new LineupFlag(slot, ref, reason, injuryStatus, status, List.of(), SLOT_UNSUPPORTED);
        }
        List<BenchOption> options = new ArrayList<>();
        for (String id : roster.players()) {
            if (starterIds.contains(id) || parked.contains(id)) continue;
            Player p = players.get(id);
            if (p == null || !rules.isEligible(p, slot)) continue;
            // Only a player who can't play is no option (data-model validation, amended B1): a LISTED (DTD, GTD,
            // Questionable) bench player stays and carries his tag.
            if (LeagueAnalysisService.isUnavailable(p.injuryStatus()) || rules.isSuspended(p)) continue;
            if (schedule != null && !schedule.hasGameInWeek(p)) continue;
            FpAvg avg = fp.get(id);
            String tag = p.injuryStatus() == null || p.injuryStatus().isBlank() ? null : p.injuryStatus();
            options.add(new BenchOption(ref(p), schedule == null ? null : schedule.gamesLeftInWeek(p),
                    avg == null ? null : avg.fpPerGame(), avg == null ? null : avg.season(),
                    avg == null ? 0 : avg.games(), tag));
        }
        // Sorting by fp/g claims "highest value first", which only holds when every option has an average from
        // the same season; early in a year some have this season's and some none (review B2). Otherwise by name.
        boolean byFp = anyAverages && !options.isEmpty() && options.stream().allMatch(o -> o.fpPerGame() != null)
                && options.stream().map(BenchOption::fpSeason).distinct().count() == 1;
        Comparator<BenchOption> byName = Comparator
                .comparing((BenchOption o) -> o.player().name(), Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(o -> o.player().sleeperPlayerId());
        // Higher average first; a missing average sorts last rather than first (lessons class 1: the direction
        // is what the ordering test pins).
        options.sort(byFp
                ? Comparator.comparing(BenchOption::fpPerGame, Comparator.nullsLast(Comparator.<Double>reverseOrder()))
                        .thenComparing(byName)
                : byName);
        return new LineupFlag(slot, ref, reason, injuryStatus, status, options, byFp ? FP_PER_GAME : NAME);
    }

    /**
     * Whether the sport's {@code isEligible} knows this slot name at all. It answers false, not "unknown", for
     * a name it does not handle, so the only way to tell "nobody fits" from "can't say" is to ask it about one
     * stand-in player per position: if none qualifies, the slot is a name it has no rule for (review F3).
     */
    static boolean slotKnown(SportRules rules, String slot) {
        // Assumes isEligible reads only player.positions(): the probe has a null team, status and injury tag. A
        // rule that also reads those would make this misclassify a real slot as unknown (review nit).
        if ("BN".equals(slot)) return true;
        for (Position pos : Position.values()) {
            Player probe = new Player(0, rules.sport(), "probe", "probe", List.of(pos), null, null, null, null, null);
            if (rules.isEligible(probe, slot)) return true;
        }
        return false;
    }

    private static boolean isEmpty(String starterId) {
        return starterId == null || starterId.isBlank() || "0".equals(starterId);
    }

    private static PlayerRef ref(Player p) {
        return new PlayerRef(p.sleeperId(), p.name(), p.positions().stream().map(Enum::name).toList(), p.team(), true);
    }
}
