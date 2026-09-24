package com.ballknowers.draftsim.ingest;

import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.PlayerAbsenceRepository;
import com.ballknowers.draftsim.store.PlayerGameRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import com.ballknowers.draftsim.store.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Per-game stat lines for the players a league actually rostered
 * (specs/005-daily-weekly-top-players, US1; specs/008-season-superlatives,
 * research R9).
 *
 * <p><b>Deliberately not part of any ingest chain.</b> This costs one upstream
 * call per player -- measured 331 players for the reference league's 2025
 * season, 280 for 2024 -- and serves one page for one sport. Feature 004 made
 * the opposite call for transactions and wired them into
 * {@link LeagueHistoryIngestService}, correctly: those are week-level data on
 * the same cadence as the weekly points that walk already fetches. These are
 * not. Folding them in would make every routine league ingest hundreds of calls
 * slower for data most leagues never read.
 *
 * <p>The player set is league-scoped only in how it is <i>chosen</i>. The rows
 * written are not: a game belongs to a sport and a season, and two leagues
 * share it.
 *
 * <p><b>Also writes {@code player_absence} rows now (research R9).</b> The old
 * version of this walk discarded a missed game outright: basketball's
 * {@code stats: {}} failed the (pre-existing) "non-empty stats" gate in
 * {@link #toRow}, and football's DNP entries (non-empty {@code stats} with no
 * {@code gp}) would have been silently stored as games played had anything
 * ever read them. Both are fixed by routing the "did he play" question through
 * {@link SportRules#playedIn}: a played entry becomes a {@code player_game}
 * row, and anything else becomes a {@code player_absence} row instead of being
 * thrown away.
 */
@Service
public class PlayerGameIngestService {

    private static final Logger log = LoggerFactory.getLogger(PlayerGameIngestService.class);

    private final SleeperPlayerStatsClient stats;
    private final LeagueRepository leagues;
    private final RosterWeekPointsRepository weekPoints;
    private final PlayerGameRepository games;
    private final PlayerAbsenceRepository absences;
    private final SportRulesRegistry rulesRegistry;

    public PlayerGameIngestService(SleeperPlayerStatsClient stats, LeagueRepository leagues,
                                   RosterWeekPointsRepository weekPoints, PlayerGameRepository games,
                                   PlayerAbsenceRepository absences, SportRulesRegistry rulesRegistry) {
        this.stats = stats;
        this.leagues = leagues;
        this.weekPoints = weekPoints;
        this.games = games;
        this.absences = absences;
        this.rulesRegistry = rulesRegistry;
    }

    /**
     * Counted and returned, not merely logged. A backfill nobody can see the
     * result of is how feature 004 ended up with five leagues holding zero
     * transactions while the suite stayed green.
     *
     * @param absencesStored   {@code player_absence} rows written this run
     *                         (both bases combined)
     * @param weeksUnclassified football {@code None} weeks whose player's own
     *                          team could not be determined from any of his
     *                          other entries -- not counted either way, and
     *                          reported so a coverage gap is visible rather
     *                          than silently folded into "bye" (research R9)
     */
    public record Result(int playersWalked, int gamesStored, int playersFailed,
                         int absencesStored, int weeksUnclassified) {}

    public Result ingest(String sleeperLeagueId, Integer requestedSeason) {
        Optional<LeagueRepository.LeagueRow> found = leagues.bySleeperId(sleeperLeagueId);
        if (found.isEmpty()) return new Result(0, 0, 0, 0, 0);
        LeagueRepository.LeagueRow league = found.get();
        int season = requestedSeason == null ? league.season() : requestedSeason;
        Sport sport = league.sport();
        SportRules rules = rulesRegistry.get(sport);

        Set<String> playerIds = rosteredPlayers(league.id(), season);

        // Pass 1: fetch every walked player's raw season-by-week payload. One
        // upstream call each; a player's failure here must not cost the rest.
        //
        // Stored as Map<String, Object> per player, NOT the client's declared
        // Map<String, List<...>> -- that declared type is an unchecked cast
        // over a raw Jackson Map<String,Object> with no runtime enforcement,
        // and at least one real player's payload has carried a week value that
        // was not a JSON array. Iterating it as a generic List blows up with a
        // ClassCastException at the first element access (erasure inserts the
        // checkcast there), discovered live 2026-09-23 running this walk
        // against real Sleeper data -- every access below checks
        // {@code instanceof List} first rather than trusting the client's type.
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        int failed = 0;
        for (String playerId : playerIds) {
            try {
                Map<String, List<Map<String, Object>>> byWeek =
                        stats.seasonByWeek(sport.code(), playerId, season);
                if (byWeek != null) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Map<String, Object> asObjectMap = (Map) byWeek;
                    raw.put(playerId, asObjectMap);
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("player-games: {} season {} player {} failed: {}",
                        sleeperLeagueId, season, playerId, e.toString());
            }
        }

        // Pass 2: build the (team, week) played-set every walked entry gives
        // evidence for -- an entry present at all (played OR a DNP entry that
        // still carries team/opponent/date) means that team had a game that
        // week. Also record, per player, the (week -> team) pairs his own
        // entries carry, so a football `None` week can borrow the nearest
        // neighbour's team (research R9).
        Set<String> teamWeekPlayed = new HashSet<>();
        Map<String, TreeMap<Integer, String>> teamByPlayerWeek = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> pe : raw.entrySet()) {
            TreeMap<Integer, String> teamWeeks = new TreeMap<>();
            for (Object weekValue : pe.getValue().values()) {
                List<Map<String, Object>> entries = asEntryList(weekValue);
                if (entries == null) continue;
                for (Map<String, Object> entry : entries) {
                    Integer wk = weekOf(entry);
                    String team = stringOrNull(entry.get("team"));
                    if (wk == null || team == null) continue;
                    teamWeeks.put(wk, team);
                    teamWeekPlayed.add(team + "|" + wk);
                }
            }
            teamByPlayerWeek.put(pe.getKey(), teamWeeks);
        }

        // Pass 3: write player_game / player_absence rows.
        int stored = 0, absencesStored = 0, weeksUnclassified = 0;
        for (Map.Entry<String, Map<String, Object>> pe : raw.entrySet()) {
            String playerId = pe.getKey();
            TreeMap<Integer, String> teamWeeks = teamByPlayerWeek.getOrDefault(playerId, new TreeMap<>());

            for (Map.Entry<String, Object> we : pe.getValue().entrySet()) {
                List<Map<String, Object>> entries = asEntryList(we.getValue());

                if (entries == null || entries.isEmpty()) {
                    // A football `None` week (or an empty list): classify it,
                    // never invent it as a game.
                    Integer wk = parseWeekKey(we.getKey());
                    if (wk == null) continue;
                    String team = nearestTeam(teamWeeks, wk);
                    if (team == null) {
                        // Coordinator follow-up 2026-09-23: persisted, not just
                        // counted, so the superlatives payload can report a real
                        // number instead of a standing, unbacked caveat (V23).
                        absences.upsert(new PlayerAbsenceRepository.Row(
                                sport, season, wk, playerId, null, null, null, "UNCLASSIFIED"));
                        weeksUnclassified++;
                        absencesStored++;
                        continue;
                    }
                    if (teamWeekPlayed.contains(team + "|" + wk)) {
                        absences.upsert(new PlayerAbsenceRepository.Row(
                                sport, season, wk, playerId, null, null, team, "TEAM_PLAYED_NO_ENTRY"));
                        absencesStored++;
                    }
                    // else: his team had no evidence of a game that week -- a bye. Write nothing.
                    continue;
                }

                for (Map<String, Object> entry : entries) {
                    Object statsObj = entry.get("stats");
                    if (!(statsObj instanceof Map<?, ?> rawStats)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> statsMap = (Map<String, Object>) rawStats;

                    PlayerGameRepository.Row row = toRow(sport, season, playerId, entry);
                    if (row == null) continue; // missing an identifying field -- dropped, not invented

                    if (rules.playedIn(statsMap)) {
                        games.upsert(row);
                        stored++;
                        // Coordinator follow-up 2026-09-23, item 2: a game
                        // Sleeper once reported as {stats: {}} (stored as an
                        // ENTRY_WITHOUT_PLAY absence) may since have been
                        // played -- without this delete, both rows exist and
                        // the award charges a game he actually played.
                        absences.deleteByGame(sport, season, playerId, row.gameId());
                        // Real per-entry evidence for this week now exists, so
                        // any earlier neighbour-team-inferred week-level row
                        // (TEAM_PLAYED_NO_ENTRY/UNCLASSIFIED, no game_id) is
                        // stale -- clear it rather than double-count the week.
                        absences.deleteWeeklyBasis(sport, season, playerId, row.week());
                    } else if (isFutureOrToday(row.gameDate())) {
                        // A scheduled game that has not been played yet is not
                        // a missed one (coordinator follow-up 2026-09-23, item
                        // 2) -- Sleeper can list an upcoming game as
                        // {stats: {}} before it happens. Nothing is written or
                        // deleted here: there is no new evidence yet, either way.
                        continue;
                    } else {
                        String team = stringOrNull(entry.get("team"));
                        absences.upsert(new PlayerAbsenceRepository.Row(
                                sport, season, row.week(), playerId, row.gameId(), row.gameDate(), team,
                                "ENTRY_WITHOUT_PLAY"));
                        absencesStored++;
                        // The mirror of the played branch above: a game once
                        // stored as PLAYED may since have been corrected to a
                        // DNP -- delete the stale player_game row for it.
                        games.deleteByGame(sport, season, playerId, row.gameId());
                        absences.deleteWeeklyBasis(sport, season, playerId, row.week());
                    }
                }
            }
        }

        log.info("player-games: league {} season {} -- {} players walked, {} games stored, {} failed, "
                        + "{} absences stored, {} weeks unclassified",
                sleeperLeagueId, season, playerIds.size(), stored, failed, absencesStored, weeksUnclassified);
        return new Result(playerIds.size(), stored, failed, absencesStored, weeksUnclassified);
    }

    /**
     * Every player who appears in the league's stored weekly points for the
     * season -- which is exactly the set whose games anyone could ask about,
     * and no wider.
     */
    private Set<String> rosteredPlayers(long leagueId, int season) {
        Set<String> ids = new LinkedHashSet<>();
        for (RosterWeekPointsRepository.WeekBreakdown w : weekPoints.breakdownsFor(leagueId, season)) {
            if (w.playersPointsJson() == null || w.playersPointsJson().isBlank()) continue;
            ids.addAll(JsonUtil.readMap(w.playersPointsJson()).keySet());
        }
        return ids;
    }

    /**
     * A week's raw value, defensively normalized to a list of per-game entry
     * maps.
     *
     * <p><b>Measured live 2026-09-23, correcting research R9's description.</b>
     * R9 said football carries "one entry per week"; measured directly
     * against {@code /stats/nfl/player/4034}, a football week's value is a
     * bare JSON <i>object</i> (one entry, not wrapped in an array) --
     * basketball's is the one that comes back as an array, because several
     * games can fall in one fantasy week. A {@code null} value is a football
     * {@code None} week (or a genuinely absent key) either way. Anything else
     * this service cannot recognize is dropped, never guessed at.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asEntryList(Object weekValue) {
        if (weekValue instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
            return out;
        }
        if (weekValue instanceof Map<?, ?> m) {
            return List.of((Map<String, Object>) m);
        }
        return null;
    }

    private static Integer weekOf(Map<String, Object> entry) {
        Object week = entry.get("week");
        return week instanceof Number w ? w.intValue() : null;
    }

    private static Integer parseWeekKey(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Whether {@code gameDate} is today or later (coordinator follow-up
     * 2026-09-23, item 2). "Today" is read in UTC ({@link ZoneOffset#UTC}),
     * chosen over the host machine's local zone so this comparison does not
     * drift with wherever the process happens to be deployed -- a date-only
     * comparison has no meaningful "half a day early/late" case to get right,
     * just a stable, deployment-independent definition of "today."
     */
    private static boolean isFutureOrToday(LocalDate gameDate) {
        return !gameDate.isBefore(LocalDate.now(ZoneOffset.UTC));
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * The nearest week (by absolute distance, ties favouring the earlier one)
     * this player is known to have played for -- the "neighbouring entry" the
     * data model describes for classifying a football {@code None} week.
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
     * <p><b>No longer rejects an empty {@code stats} map</b> (research R9,
     * amended from spec 005): an empty map is exactly how basketball marks a
     * missed game, and dropping it here is what threw the Embiid award's data
     * away before it could ever be read. Whether an entry with usable stats
     * counts as a game <i>played</i> is {@link SportRules#playedIn}'s call, made
     * by the caller -- this method only says whether the entry is well-formed
     * enough to become a row at all.
     */
    static PlayerGameRepository.Row toRow(Sport sport, int season, String playerId,
                                          Map<String, Object> entry) {
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
        Object away = entry.get("is_away_team");

        return new PlayerGameRepository.Row(
                sport, season, w.intValue(), playerId, String.valueOf(gameId), gameDate,
                opponent == null ? null : String.valueOf(opponent),
                away instanceof Boolean b ? b : null,
                JsonUtil.write(m));
    }
}
