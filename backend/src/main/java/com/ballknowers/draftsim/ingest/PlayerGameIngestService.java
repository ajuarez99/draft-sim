package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.SeasonBoxCache;
import com.ballknowers.draftsim.refresh.RefreshProperties;
import com.ballknowers.draftsim.refresh.SingleFlight;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerAbsenceRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import com.ballknowers.draftsim.store.SportScheduleRepository;
import com.ballknowers.draftsim.store.SportWeekStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import java.util.concurrent.CompletionException;


/**
 * Per-game stat lines, rebuilt on Sleeper's per-week stats plus the season
 * schedule (specs/009-auto-data-refresh, research R6; before that
 * specs/005-daily-weekly-top-players US1 and specs/008-season-superlatives R9).
 *
 * <p><b>What changed, and what did not.</b> The old version made one upstream
 * call per rostered player (331 for the reference league's 2025 season, ~101 s)
 * and could not be made incremental, because each call returns a player's whole
 * season. This one fetches one call per <i>week</i> of the sport-season -- every
 * player's entries at once -- plus one schedule call, and it skips weeks that
 * are already {@code final}. It is shared by every league in the sport, not run
 * per league.
 *
 * <p>The source of the entries, of {@code is_away} (now the schedule, because
 * the per-week payload has no {@code is_away_team}) and of bye evidence (now the
 * schedule, instead of other walked players' entries) changed. The rules did
 * not: {@link SportRules#playedIn} routing, the future-or-today guard,
 * the {@code deleteByGame}/{@code deleteWeeklyBasis} clean-up, and a
 * {@code None} week's team coming from the player's nearest stored entry are the
 * old walk's, kept so that a parity diff against the old walk's rows can be
 * empty (task T016).
 *
 * <p><b>Rows.</b> {@code player_game} and {@code ENTRY_WITHOUT_PLAY} absences are
 * written for <i>every</i> entry in a week's payload, not only rostered players
 * (which also means a player rostered mid-season already has his earlier games
 * stored). {@code TEAM_PLAYED_NO_ENTRY} and {@code UNCLASSIFIED} can only be
 * judged for players we know about, so they are computed only for players
 * rostered in any league of the sport-season.
 *
 * <p>Also writes {@code player_absence} rows (research R9 of spec 008): a played
 * entry becomes a {@code player_game} row, and anything else becomes a
 * {@code player_absence} row instead of being thrown away.
 */
@Service
public class PlayerGameIngestService {

    private static final Logger log = LoggerFactory.getLogger(PlayerGameIngestService.class);

    private final SleeperPlayerStatsClient stats;
    private final LeagueRepository leagues;
    private final RosterWeekPointsRepository weekPoints;
    private final PlayerGameRepository games;
    private final PlayerAbsenceRepository absences;
    private final SportWeekStatsRepository weekStats;
    private final SportRulesRegistry rulesRegistry;
    private final SportScheduleRepository scheduleRepository;
    private final SeasonBoxCache boxCache;

    /**
     * Single-flight keyed {@code sport:season} (specs/009 research R4, T013/T020):
     * a second caller for the same sport-season while one is running waits for
     * and shares that run's result rather than starting an interleaving walk
     * (research R11). Its own instance, not shared with the league-chain flight
     * that waits on it -- see {@link SingleFlight}.
     */
    private final SingleFlight inFlight = new SingleFlight();

    public PlayerGameIngestService(SleeperPlayerStatsClient stats, LeagueRepository leagues,
                                   RosterWeekPointsRepository weekPoints, PlayerGameRepository games,
                                   PlayerAbsenceRepository absences, SportWeekStatsRepository weekStats,
                                   SportRulesRegistry rulesRegistry,
                                   SportScheduleRepository scheduleRepository, SeasonBoxCache boxCache) {
        this.stats = stats;
        this.leagues = leagues;
        this.weekPoints = weekPoints;
        this.games = games;
        this.absences = absences;
        this.weekStats = weekStats;
        this.rulesRegistry = rulesRegistry;
        this.scheduleRepository = scheduleRepository;
        this.boxCache = boxCache;
    }

    /**
     * Counted and returned, not merely logged. A backfill nobody can see the
     * result of is how feature 004 ended up with five leagues holding zero
     * transactions while the suite stayed green.
     *
     * @param weeksFetched      per-week payloads fetched this run (final weeks are
     *                          skipped and not counted); was {@code playersWalked}
     * @param weeksFailed       weeks whose fetch failed and were left un-marked, so
     *                          the next run retries them; was {@code playersFailed}
     *                          when the unit of work was a player
     * @param absencesStored    {@code player_absence} rows written this run
     *                          (both bases combined)
     * @param weeksUnclassified football {@code None} weeks whose player's own
     *                          team could not be determined from any of his
     *                          other entries -- not counted either way, and
     *                          reported so a coverage gap is visible rather
     *                          than silently folded into "bye" (research R9)
     * @param scheduleStoreFailed the schedule could not be written to {@code sport_schedule}
     *                          (specs/017 F3). Caught, not thrown, so the per-game data above
     *                          still landed; the chain refresh turns it into a FAILED season
     *                          after everything else has run.
     */
    public record Result(int weeksFetched, int gamesStored, int weeksFailed,
                         int absencesStored, int weeksUnclassified, boolean scheduleStoreFailed) {}

    /**
     * The manual endpoint's entry point. Resolves the league's sport (and its
     * season, unless one is requested) and runs the shared sport-season
     * refresh. There is no separate per-league path (research R9).
     */
    public Result ingest(String sleeperLeagueId, Integer requestedSeason) {
        Optional<LeagueRepository.LeagueRow> found = leagues.bySleeperId(sleeperLeagueId);
        if (found.isEmpty()) return new Result(0, 0, 0, 0, 0, false);
        LeagueRepository.LeagueRow league = found.get();
        int season = requestedSeason == null ? league.season() : requestedSeason;
        return refreshSportSeason(league.sport(), season, Instant.now());
    }

    /**
     * Refreshes one sport-season's per-game data. Single-flight per
     * {@code sport:season}: a concurrent caller shares the running result.
     */
    public Result refreshSportSeason(Sport sport, int season, Instant now) {
        String key = sport.code() + ":" + season;
        // Every refresh re-stamps fetched_at on each non-final row, so the season cache must not chase its
        // validity token mid-run (markRefreshing); it is invalidated when the run ends, success or not
        // (specs/022 F8).
        boxCache.markRefreshing(sport, season, true);
        try {
            return inFlight.run(key, () -> doRefresh(sport, season, now)).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        } finally {
            boxCache.invalidate(sport, season);
            boxCache.markRefreshing(sport, season, false);
        }
    }

    private Result doRefresh(Sport sport, int season, Instant now) {
        SportRules rules = rulesRegistry.get(sport);
        String code = sport.code();
        // "Today" is read in UTC, chosen over the host machine's local zone so the
        // future-or-today guard does not drift with where the process is deployed;
        // taken from the caller's `now` so it is the same instant everything else
        // in the run is judged at.
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();

        // 1. The schedule, once. A schedule failure fails the whole run: without
        //    it there is no week range, no is_away and no bye evidence.
        SportSchedule schedule = SportSchedule.parse(stats.schedule(code, season));
        int lastWeek = schedule.lastStartedWeek(today);

        Set<String> rostered = rosteredPlayers(sport, season);

        Map<Integer, SportWeekStatsRepository.Row> known = new HashMap<>();
        for (SportWeekStatsRepository.Row r : weekStats.forSeason(sport, season)) known.put(r.week(), r);

        // The absence rows that already exist, read once, so the clean-up
        // deletes below run only when there is something to delete instead of
        // three statements per entry across ~2,000 entries a week. Nothing else
        // writes these tables during this run (single-flight per sport-season).
        Set<String> absenceByGame = new HashSet<>();   // "player|gameId"
        Set<String> weeklyBasis = new HashSet<>();     // "player|week", game_id null
        for (PlayerAbsenceRepository.Row a : absences.forSeason(sport, season)) {
            if (a.gameId() != null) absenceByGame.add(a.playerId() + "|" + a.gameId());
            else weeklyBasis.add(a.playerId() + "|" + a.week());
        }

        // What this run saw for rostered players, for the no-entry pass. Final
        // (skipped) weeks are covered by what is already stored.
        Map<String, TreeMap<Integer, String>> teamByPlayerWeek = new HashMap<>();
        Set<String> entrySeen = new HashSet<>();       // "player|week"

        // Weeks whose data is in storage: fetched before or fetched now. A week whose
        // fetch failed is not one, so nobody is judged "missing" from data we don't have.
        Set<Integer> loadedWeeks = new HashSet<>(known.keySet());

        int fetched = 0, failed = 0, stored = 0, absencesStored = 0, weeksUnclassified = 0;

        // 2. Weeks 1..(last week with a started game), skipping final ones.
        for (int week = 1; week <= lastWeek; week++) {
            SportWeekStatsRepository.Row prior = known.get(week);
            if (prior != null && prior.fin()) continue;

            List<Map<String, Object>> entries;
            try {
                entries = stats.week(code, season, week);
            } catch (RuntimeException e) {
                failed++;
                log.warn("player-games: {} {} week {} failed: {}", code, season, week, e.toString());
                continue;
            }
            // A schedule that says a game finished, against a payload with no entries at all,
            // is a bad answer (an empty list, or a non-list that mapsOf turned into one), not a
            // quiet week. Treated as a failed fetch: not marked, not judged (review 2026-09-28).
            if (entries.isEmpty() && schedule.hasCompleteGame(week)) {
                failed++;
                log.warn("player-games: {} {} week {} came back empty though the schedule has a completed game",
                        code, season, week);
                continue;
            }
            fetched++;
            loadedWeeks.add(week);

            Set<String> gameKeys = new HashSet<>();    // "player|gameId" already stored this week
            for (PlayerGameRepository.Row g : games.forWeek(sport, season, week)) {
                gameKeys.add(g.sleeperPlayerId() + "|" + g.gameId());
            }

            for (Map<String, Object> entry : entries) {
                Object pid = entry.get("player_id");
                if (pid == null) continue;
                String playerId = String.valueOf(pid);

                Integer entryWeek = weekOf(entry);
                String entryTeam = stringOrNull(entry.get("team"));
                if (rostered.contains(playerId) && entryWeek != null) {
                    entrySeen.add(playerId + "|" + entryWeek);
                    if (entryTeam != null) {
                        teamByPlayerWeek.computeIfAbsent(playerId, k -> new TreeMap<>()).put(entryWeek, entryTeam);
                    }
                }

                Object statsObj = entry.get("stats");
                if (!(statsObj instanceof Map<?, ?> rawStats)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> statsMap = (Map<String, Object>) rawStats;

                Boolean isAway = schedule.isAway(stringOrNull(entry.get("game_id")), entryTeam);
                PlayerGameRepository.Row row = toRow(sport, season, playerId, entry, isAway);
                if (row == null) continue; // missing an identifying field -- dropped, not invented

                String gameKey = playerId + "|" + row.gameId();
                String weekKey = playerId + "|" + row.week();

                if (rules.playedIn(statsMap)) {
                    games.upsert(row);
                    gameKeys.add(gameKey);
                    stored++;
                    // A game Sleeper once reported as an empty box score (stored
                    // as an ENTRY_WITHOUT_PLAY absence) may since have been
                    // played -- without this delete, both rows exist and the
                    // award charges a game he actually played.
                    if (absenceByGame.remove(gameKey)) {
                        absences.deleteByGame(sport, season, playerId, row.gameId());
                    }
                    // Real per-entry evidence for this week now exists, so any
                    // earlier neighbour-team-inferred week-level row is stale.
                    if (weeklyBasis.remove(weekKey)) {
                        absences.deleteWeeklyBasis(sport, season, playerId, row.week());
                    }
                } else if (!row.gameDate().isBefore(today)) {
                    // A scheduled game not yet played is not a missed one:
                    // nothing is written or deleted, there is no new evidence.
                    continue;
                } else {
                    absences.upsert(new PlayerAbsenceRepository.Row(
                            sport, season, row.week(), playerId, row.gameId(), row.gameDate(), entryTeam,
                            "ENTRY_WITHOUT_PLAY"));
                    absenceByGame.add(gameKey);
                    absencesStored++;
                    // The mirror of the played branch: a game once stored as
                    // PLAYED may since have been corrected to a DNP.
                    if (gameKeys.remove(gameKey)) {
                        games.deleteByGame(sport, season, playerId, row.gameId());
                    }
                    if (weeklyBasis.remove(weekKey)) {
                        absences.deleteWeeklyBasis(sport, season, playerId, row.week());
                    }
                }
            }

            weekStats.upsert(new SportWeekStatsRepository.Row(
                    sport, season, week, now, schedule.isFinal(week, now)));
        }

        // 3. Weeks a rostered player has no entry for: the football None week,
        //    now judged against the schedule instead of other players' entries.
        if (!rostered.isEmpty() && lastWeek > 0) {
            Map<String, TreeMap<Integer, String>> teams = new HashMap<>();
            Map<String, Set<Integer>> haveEntry = new HashMap<>();
            for (PlayerGameRepository.Row g : games.forPlayers(sport, season, rostered)) {
                haveEntry.computeIfAbsent(g.sleeperPlayerId(), k -> new HashSet<>()).add(g.week());
                String team = schedule.teamOf(g.gameId(), g.opponent());
                if (team != null) {
                    teams.computeIfAbsent(g.sleeperPlayerId(), k -> new TreeMap<>()).put(g.week(), team);
                }
            }
            for (PlayerAbsenceRepository.Row a : absences.forPlayers(sport, season, rostered)) {
                if (a.gameId() == null) continue; // week-level rows are conclusions, not evidence
                haveEntry.computeIfAbsent(a.playerId(), k -> new HashSet<>()).add(a.week());
                if (a.team() != null) {
                    teams.computeIfAbsent(a.playerId(), k -> new TreeMap<>()).put(a.week(), a.team());
                }
            }
            // This run's own entries win over what was derived from storage: they
            // carry the team as Sleeper stated it, and include entries that wrote
            // no row (today's or a future game).
            for (Map.Entry<String, TreeMap<Integer, String>> e : teamByPlayerWeek.entrySet()) {
                teams.computeIfAbsent(e.getKey(), k -> new TreeMap<>()).putAll(e.getValue());
            }

            for (String playerId : rostered) {
                TreeMap<Integer, String> teamWeeks = teams.getOrDefault(playerId, new TreeMap<>());
                Set<Integer> have = haveEntry.getOrDefault(playerId, Set.of());
                // Weeks 1..lastWeek is the range the old walk saw too: its payload
                // keyed every week of the season, including the empty ones. A week
                // after a player's season ended is one of those, so it is judged
                // the same way (his last team, and whether that team played).
                for (int week = 1; week <= lastWeek; week++) {
                    if (!loadedWeeks.contains(week)) continue;
                    if (have.contains(week) || entrySeen.contains(playerId + "|" + week)) continue;
                    String team = nearestTeam(teamWeeks, week);
                    if (team == null) {
                        absences.upsert(new PlayerAbsenceRepository.Row(
                                sport, season, week, playerId, null, null, null, "UNCLASSIFIED"));
                        weeksUnclassified++;
                        absencesStored++;
                    } else if (schedule.teamPlayed(week, team)) {
                        absences.upsert(new PlayerAbsenceRepository.Row(
                                sport, season, week, playerId, null, null, team, "TEAM_PLAYED_NO_ENTRY"));
                        absencesStored++;
                    }
                    // else: his team had no game that week -- a bye. Write nothing.
                }
            }
        }

        // 4. Store the schedule itself (specs/017). After the week loop and the no-entry pass,
        //    so a storage failure can never cost per-game data; caught and reported, never thrown.
        boolean scheduleStoreFailed = false;
        try {
            scheduleRepository.replaceSeason(code, season, schedule.games(),
                    OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        } catch (RuntimeException e) {
            scheduleStoreFailed = true;
            log.warn("player-games: {} {} schedule store failed: {}", code, season, e.toString(), e);
        }

        log.info("player-games: {} {} -- {} weeks fetched (of {}), {} games stored, {} weeks failed, "
                        + "{} absences stored, {} weeks unclassified",
                code, season, fetched, lastWeek, stored, failed, absencesStored, weeksUnclassified);
        return new Result(fetched, stored, failed, absencesStored, weeksUnclassified, scheduleStoreFailed);
    }

    /**
     * Every player who appears in any league of this sport-season's stored
     * weekly points -- the set whose no-entry weeks anyone could ask about.
     */
    private Set<String> rosteredPlayers(Sport sport, int season) {
        Set<String> ids = new LinkedHashSet<>();
        for (LeagueRepository.LeagueRow l : leagues.all()) {
            if (l.sport() != sport) continue;
            for (RosterWeekPointsRepository.WeekBreakdown w : weekPoints.breakdownsFor(l.id(), season)) {
                if (w.playersPointsJson() == null || w.playersPointsJson().isBlank()) continue;
                ids.addAll(JsonUtil.readMap(w.playersPointsJson()).keySet());
            }
        }
        return ids;
    }

    private static Integer weekOf(Map<String, Object> entry) {
        Object week = entry.get("week");
        return week instanceof Number w ? w.intValue() : null;
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * The nearest week (by absolute distance, ties favouring the earlier one)
     * this player is known to have played for -- the "neighbouring entry" the
     * data model describes for classifying a week with no entry.
     */
    static String nearestTeam(TreeMap<Integer, String> teamWeeks, int week) {
        if (teamWeeks.isEmpty()) return null;
        Map.Entry<Integer, String> floor = teamWeeks.floorEntry(week);
        Map.Entry<Integer, String> ceiling = teamWeeks.ceilingEntry(week);
        if (floor == null) return ceiling.getValue();
        if (ceiling == null) return floor.getValue();
        int df = week - floor.getKey();
        int dc = ceiling.getKey() - week;
        return df <= dc ? floor.getValue() : ceiling.getValue();
    }

    /**
     * One upstream entry to a row, or null if it is not a usable game.
     *
     * <p>The week comes from the entry's own {@code week} field and never from
     * {@code date}. That is what puts a postponed game in the week it was
     * played rather than the week it was scheduled, without this service
     * knowing anything about calendars (research R6).
     *
     * <p><b>Does not reject an empty {@code stats} map</b> (spec 008 research
     * R9): an empty map is exactly how basketball marks a missed game. Whether
     * an entry with usable stats counts as a game <i>played</i> is
     * {@link SportRules#playedIn}'s call, made by the caller -- this method only
     * says whether the entry is well-formed enough to become a row at all.
     *
     * @param isAway from the schedule (the per-week payload carries no
     *               {@code is_away_team}); null when unknown, never defaulted
     */
    static PlayerGameRepository.Row toRow(Sport sport, int season, String playerId,
                                          Map<String, Object> entry, Boolean isAway) {
        Object gameId = entry.get("game_id");
        Object date = entry.get("date");
        Object week = entry.get("week");
        // All three are required: a game with no id cannot be deduplicated, one
        // with no date cannot be a "night", and one with no week cannot be
        // placed. Dropping it is honest; inventing any of them is not.
        if (gameId == null || date == null || !(week instanceof Number w)) return null;

        Object statsObj = entry.get("stats");
        if (!(statsObj instanceof Map<?, ?> m)) return null;

        LocalDate gameDate;
        try {
            gameDate = LocalDate.parse(String.valueOf(date));
        } catch (RuntimeException e) {
            return null;
        }

        Object opponent = entry.get("opponent");

        return new PlayerGameRepository.Row(
                sport, season, w.intValue(), playerId, String.valueOf(gameId), gameDate,
                opponent == null ? null : String.valueOf(opponent),
                isAway,
                JsonUtil.write(m));
    }
}
