package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.store.*;
import java.util.*;
import java.util.stream.Collectors;
import static com.ballknowers.draftsim.util.Rounding.round2;
import com.ballknowers.draftsim.engine.SeasonSuperlativesService.*;

import static com.ballknowers.draftsim.engine.SeasonSuperlativesService.*;

/**
 * JOEL_EMBIID (absence cost): the builder, which reads player_absence and player_game
 * through the repositories it is handed, and the pure winners/coverage/game-window arithmetic.
 *
 * <p>Moved out of {@link SeasonSuperlativesService} by specs/021-codebase-cleanup (T051)
 * as a pure move, proven by live JSON parity against the pre-split build.
 */
final class SuperlativeAbsenceMath {

    private SuperlativeAbsenceMath() {}

    /**
     * JOEL_EMBIID (T048), per research R10 (amended 2026-09-23): every missed
     * game of a regular contributor, costed at his mean points per game
     * played. The rule itself is {@link AbsenceCost#compute} -- pure and
     * tested without Postgres (T046/T047); this method's own job is gathering
     * that pure function's inputs from this league-season's stored rows.
     *
     * <p><b>Never reads {@code Player.injuryStatus}</b> (FR-013): membership,
     * regularity and cost all come from stored {@code players_points},
     * {@code starters} and {@code player_absence}/{@code player_game} rows.
     */
    static Superlative absenceSuperlative(PlayerAbsenceRepository absences, PlayerGameRepository games,
                                          GameScoringService gameScoring, LeagueRepository leagues,
                                          LeagueRepository.LeagueRow league, String sleeperLeagueId,
                                           SportRules rules, Set<Integer> scoredWeeksFinal,
                                           List<ParsedWeek> parsedWeeks, Map<String, Player> playersBySleeperId,
                                           boolean early, Set<Integer> rosterIds, Map<Integer, String> nameByRoster,
                                           Map<Integer, String> avatarByRoster, Map<Integer, Long> managerByRoster) {
        Sport sport = league.sport();

        // Membership per (roster, player): rosteredWeeks are the weeks he is a
        // key in that roster's players_points; startedWeeks are the subset of
        // those he actually started (research R11/T019: players_points keys are
        // this feature's membership rule, IR slots included).
        Map<Integer, Map<String, Set<Integer>>> rosteredByRosterPlayer = new HashMap<>();
        Map<Integer, Map<String, Set<Integer>>> startedByRosterPlayer = new HashMap<>();
        Set<String> allPlayerIds = new HashSet<>();
        for (ParsedWeek w : parsedWeeks) {
            for (String pid : w.playersPoints().keySet()) {
                allPlayerIds.add(pid);
                rosteredByRosterPlayer.computeIfAbsent(w.rosterId(), k -> new HashMap<>())
                        .computeIfAbsent(pid, k -> new TreeSet<>()).add(w.week());
                if (w.starters().contains(pid)) {
                    startedByRosterPlayer.computeIfAbsent(w.rosterId(), k -> new HashMap<>())
                            .computeIfAbsent(pid, k -> new TreeSet<>()).add(w.week());
                }
            }
        }

        List<AbsenceCost.RosterMembership> memberships = new ArrayList<>();
        for (var rosterEntry : rosteredByRosterPlayer.entrySet()) {
            int rosterId = rosterEntry.getKey();
            Map<String, Set<Integer>> startedForRoster = startedByRosterPlayer.getOrDefault(rosterId, Map.of());
            for (var playerEntry : rosterEntry.getValue().entrySet()) {
                memberships.add(new AbsenceCost.RosterMembership(rosterId, playerEntry.getKey(),
                        playerEntry.getValue(), startedForRoster.getOrDefault(playerEntry.getKey(), Set.of())));
            }
        }

        // Coordinator follow-up 2026-09-23, item 3: availability and coverage
        // are PER LEAGUE, not sport-season-wide. The per-game backfill
        // (POST /api/ingest/player-games/{id}) walks one league's rostered
        // players at a time, so a second same-sport-season league (this DB
        // genuinely has two NFL 2025 leagues) getting ingested does not make
        // this league's players "walked" -- games.playersWithGames(sport,
        // season) used to gate on the SPORT-SEASON union, silently hiding a
        // partial award for whichever league wasn't the one last backfilled.
        // "Walked" now means: this league's own rostered player has at least
        // one player_game OR player_absence row for the season (whichever
        // basis) -- a player nobody ever asked Sleeper about has neither.
        List<PlayerGameRepository.Row> gameRows = games.forPlayers(sport, league.season(), allPlayerIds);
        List<PlayerAbsenceRepository.Row> allAbsenceRows = absences.forPlayers(sport, league.season(), allPlayerIds);
        Set<String> neverWalked = neverWalked(allPlayerIds,
                gameRows.stream().map(PlayerGameRepository.Row::sleeperPlayerId).collect(Collectors.toSet()),
                allAbsenceRows.stream().map(PlayerAbsenceRepository.Row::playerId).collect(Collectors.toSet()));

        if (!allPlayerIds.isEmpty() && neverWalked.size() == allPlayerIds.size()) {
            return unavailable(Kind.JOEL_EMBIID,
                    "Game-by-game records for this season haven't loaded yet.");
        }

        // Coordinator follow-up 2026-09-23, item 4: bounded to THIS payload's
        // regular-season window -- player_game rows are shared across leagues
        // and seasons' worth of playoff/consolation weeks would otherwise
        // silently drag the mean up or down. Item 1: playedWeeksByPlayer (the
        // same filtered rows) replaces the season-wide playedAtLeastOneGame
        // set, so AbsenceCost.isRegularContributor's denominator is only the
        // weeks he actually had a chance to play, not every rostered week.
        Map<String, Double> scoring = leagues.scoringOf(league.id());
        BoundedPlayerGames bounded = boundedPlayerGames(gameRows, scoredWeeksFinal, scoring, gameScoring);
        Map<String, Double> pointsPerGame = bounded.pointsPerGame();
        Map<String, Set<Integer>> playedWeeksByPlayer = bounded.playedWeeksByPlayer();

        // UNCLASSIFIED rows (V23, coordinator follow-up 2026-09-23) never cost
        // a roster anything, but they still get reported -- persisted rather
        // than only counted at ingest time, so this coverage note is backed by
        // a real number instead of a standing, unbacked caveat.
        List<AbsenceCost.Absence> absenceRows = allAbsenceRows.stream()
                .filter(r -> scoredWeeksFinal.contains(r.week()) && !"UNCLASSIFIED".equals(r.basis()))
                .map(r -> new AbsenceCost.Absence(r.playerId(), r.week()))
                .toList();

        Map<Integer, AbsenceCost.RosterCost> byRoster =
                AbsenceCost.compute(memberships, playedWeeksByPlayer, pointsPerGame, absenceRows);

        // Per-player unclassified weeks (regular season only), to attribute to
        // whichever roster(s) actually held that player and rely on him as a
        // regular contributor.
        Map<String, Set<Integer>> unclassifiedWeeksByPlayer = new HashMap<>();
        for (PlayerAbsenceRepository.Row r : allAbsenceRows) {
            if (!"UNCLASSIFIED".equals(r.basis()) || !scoredWeeksFinal.contains(r.week())) continue;
            unclassifiedWeeksByPlayer.computeIfAbsent(r.playerId(), k -> new TreeSet<>()).add(r.week());
        }
        Map<Integer, Set<Integer>> unclassifiedWeeksByRoster = new HashMap<>();
        for (AbsenceCost.RosterMembership m : memberships) {
            if (!AbsenceCost.isRegularContributor(m, playedWeeksByPlayer)) continue;
            Set<Integer> weeks = unclassifiedWeeksByPlayer.get(m.playerId());
            if (weeks == null) continue;
            for (Integer wk : weeks) {
                if (m.rosteredWeeks().contains(wk)) {
                    unclassifiedWeeksByRoster.computeIfAbsent(m.rosterId(), k -> new TreeSet<>()).add(wk);
                }
            }
        }

        // Item 3's "some, not all" case: a coverage reason naming how many of
        // this league's rostered players were never walked at all, distinct
        // from (and reported alongside) the per-holder unclassified-week notes.
        List<String> leagueLevelReasons = neverWalked.isEmpty() ? List.of() : List.of(
                neverWalked.size() + " rostered player" + (neverWalked.size() == 1 ? "" : "s")
                        + " have no game-by-game records yet.");

        return absenceWinners(byRoster, unclassifiedWeeksByRoster, leagueLevelReasons, scoredWeeksFinal.size(), early,
                rosterIds, playersBySleeperId, nameByRoster, avatarByRoster, managerByRoster);
    }

    /**
     * The "pick winners from the per-roster map" step of {@link #absenceSuperlative}, extracted so the
     * empty-state rule below is testable without Postgres (spec 010 T010).
     */
    static Superlative absenceWinners(Map<Integer, AbsenceCost.RosterCost> byRoster,
                                      Map<Integer, Set<Integer>> unclassifiedWeeksByRoster,
                                      List<String> leagueLevelReasons, int weeksScored, boolean early,
                                      Set<Integer> rosterIds, Map<String, Player> playersBySleeperId,
                                      Map<Integer, String> nameByRoster, Map<Integer, String> avatarByRoster,
                                      Map<Integer, Long> managerByRoster) {
        // B6: an empty card must not read as a flat zero when some rosters' weeks are unknown, not zero.
        long unclassifiedRosters = unclassifiedWeeksByRoster.values().stream().filter(w -> !w.isEmpty()).count();
        List<String> emptyReasons = new ArrayList<>(leagueLevelReasons);
        if (unclassifiedRosters > 0) {
            emptyReasons.add(unclassifiedRosters + (unclassifiedRosters == 1 ? " roster had" : " rosters had")
                    + " weeks that couldn't be classified as a bye or a missed game");
        }
        Coverage emptyCoverage = emptyReasons.isEmpty() ? null : new Coverage(weeksScored, 0, emptyReasons);
        if (byRoster.isEmpty()) {
            return new Superlative(Kind.JOEL_EMBIID, true, null, early, null, "POINTS", List.of(),
                    "nobody's been bitten yet", List.of(), emptyCoverage);
        }

        double max = byRoster.values().stream().mapToDouble(AbsenceCost.RosterCost::totalPointsLost).max().orElseThrow();
        // Plan amendment 9 (R4): same rule as the waiver award -- absent rosters are a real 0 in the
        // standings, so a best value of 0 or less crowns nobody.
        if (max <= 0) {
            return new Superlative(Kind.JOEL_EMBIID, true, null, early, null, "POINTS", List.of(),
                    "no absence has cost anyone points yet", List.of(), emptyCoverage);
        }
        List<Integer> topRosters = byRoster.entrySet().stream()
                .filter(e -> Double.compare(e.getValue().totalPointsLost(), max) == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        List<Holder> holders = topRosters.stream()
                .map(id -> holder(id, nameByRoster, avatarByRoster, managerByRoster))
                .toList();

        Coverage coverage = mergeCoverage(leagueLevelReasons,
                unclassifiedCoverage(topRosters, unclassifiedWeeksByRoster, weeksScored), weeksScored);

        List<DetailRow> detail = new ArrayList<>();
        for (int id : topRosters) {
            for (AbsenceCost.PlayerCost pc : byRoster.get(id).players().stream().limit(3).toList()) {
                Player p = playersBySleeperId.get(pc.playerId());
                detail.add(new AbsenceDetail(pc.playerId(), p == null ? "Unknown player" : p.name(),
                        p == null ? null : p.primary().name(), id, pc.gamesMissed(), pc.weeksAffected(),
                        pc.pointsPerGame(), pc.estimatedPointsLost(), true));
            }
        }

        return new Superlative(Kind.JOEL_EMBIID, true, null, early, round2(max), "POINTS", holders, null,
                detail, coverage, List.of(),
                absenceStandings(byRoster, unclassifiedWeeksByRoster, rosterIds, nameByRoster, avatarByRoster,
                        managerByRoster), List.of());
    }

    /**
     * Per holder, only when it actually happened (a count of 0 is not a
     * coverage gap): "N weeks couldn't be classified as a bye or a missed
     * game" for whichever holder(s) have an unclassified week among their own
     * regular contributors' rostered weeks (V23, coordinator follow-up
     * 2026-09-23) -- a caveat backed by a real, persisted count rather than a
     * standing, unbacked note. {@code null} when nobody has one. Package-
     * private so a test can drive it directly, without Postgres.
     */
    static Coverage unclassifiedCoverage(List<Integer> topRosters, Map<Integer, Set<Integer>> unclassifiedWeeksByRoster,
                                         int weeksScored) {
        List<String> reasons = topRosters.stream()
                .map(id -> Map.entry(id, unclassifiedWeeksByRoster.getOrDefault(id, Set.of()).size()))
                .filter(e -> e.getValue() > 0)
                .map(e -> "roster " + e.getKey() + ": " + e.getValue()
                        + (e.getValue() == 1 ? " week" : " weeks")
                        + " couldn't be classified as a bye or a missed game")
                .toList();
        return reasons.isEmpty() ? null : new Coverage(weeksScored, 0, reasons);
    }

    /**
     * Combines the league-level "N players never walked" reason (item 3) with
     * the per-holder unclassified-week reasons (already-built {@link Coverage}
     * or {@code null}) into one {@link Coverage}, since a {@code Superlative}
     * carries exactly one. {@code null} only when both sources are empty.
     * Package-private so a test can drive it directly, without Postgres.
     */
    static Coverage mergeCoverage(List<String> leagueLevelReasons, Coverage unclassified, int weeksScored) {
        if (leagueLevelReasons.isEmpty() && unclassified == null) return null;
        List<String> all = new ArrayList<>(leagueLevelReasons);
        if (unclassified != null) all.addAll(unclassified.reasons());
        return new Coverage(weeksScored, 0, all);
    }

    /**
     * Item 3 (coordinator follow-up 2026-09-23): which of THIS league's
     * rostered players have never been walked by the per-game backfill at
     * all -- no {@code player_game} row and no {@code player_absence} row for
     * the season, either basis. Deliberately not gated on {@code
     * games.playersWithGames(sport, season)} (sport-season-wide, shared with
     * every other league of the same sport and season): a second same-sport
     * -season league that HAS been backfilled would make that set non-empty
     * and silently hide the fact that THIS league was never walked. Package-
     * private and pure so a test can drive it directly, without Postgres.
     */
    static Set<String> neverWalked(Set<String> rosteredPlayerIds, Set<String> gamePlayerIds,
                                   Set<String> absencePlayerIds) {
        Set<String> out = new TreeSet<>();
        for (String pid : rosteredPlayerIds) {
            if (!gamePlayerIds.contains(pid) && !absencePlayerIds.contains(pid)) out.add(pid);
        }
        return out;
    }

    static BoundedPlayerGames boundedPlayerGames(List<PlayerGameRepository.Row> gameRows, Set<Integer> scoredWeeks,
                                                 Map<String, Double> scoring, GameScoringService gameScoring) {
        Map<String, List<Double>> scoresByPlayer = new HashMap<>();
        Map<String, Set<Integer>> playedWeeksByPlayer = new HashMap<>();
        for (PlayerGameRepository.Row row : gameRows) {
            if (!scoredWeeks.contains(row.week())) continue;
            double pts = gameScoring.score(scoring, JsonUtil.readMap(row.statsJson()));
            scoresByPlayer.computeIfAbsent(row.sleeperPlayerId(), k -> new ArrayList<>()).add(pts);
            playedWeeksByPlayer.computeIfAbsent(row.sleeperPlayerId(), k -> new TreeSet<>()).add(row.week());
        }
        Map<String, Double> pointsPerGame = new HashMap<>();
        scoresByPlayer.forEach((pid, scores) -> pointsPerGame.put(pid,
                round2(scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0))));
        return new BoundedPlayerGames(pointsPerGame, playedWeeksByPlayer);
    }
}
