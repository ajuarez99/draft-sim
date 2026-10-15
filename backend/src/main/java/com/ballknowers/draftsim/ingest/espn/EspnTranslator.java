package com.ballknowers.draftsim.ingest.espn;

import com.ballknowers.draftsim.domain.Sport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ONLY place an ESPN payload is read (spec 035 T026). Pure functions from the parsed JSON to an
 * {@link EspnSeason}; no database, no network.
 *
 * <p><b>Every shape here is unverified until a real league has been read</b> (research R9: it comes
 * from a 2018 third-party fixture). Each required field is fetched through a helper that throws
 * {@link EspnShapeChanged} naming the field path, so a wrong guess fails loudly instead of writing
 * nulls. Where the plan says a value must be measured first, the code takes the stated assumption and
 * carries an {@code unverified} comment pointing at T053.
 *
 * <p>Output maps are mutable (the repo's {@code Map.of} rule: a nullable value in a JSON response path
 * throws).
 */
public final class EspnTranslator {

    private EspnTranslator() {}

    /** unverified: confirm against real league (T053). Only this seeding rule is read as the plain record ladder. */
    static final Set<String> PLAIN_LADDER_SEEDING_RULES = Set.of("TOTAL_WINS");

    // unverified: confirm against real league (T053). Slot ids are espn-api's.
    private static final Map<Integer, String> NFL_SLOTS = orderedSlots(
            0, "QB", 2, "RB", 4, "WR", 6, "TE", 23, "FLEX", 17, "K", 16, "DEF");
    private static final Map<Integer, String> NBA_SLOTS = orderedSlots(
            0, "PG", 1, "SG", 5, "G", 2, "SF", 3, "PF", 6, "F", 4, "C", 11, "UTIL");
    private static final Map<Integer, String> NFL_UNMODELLED = Map.ofEntries(
            Map.entry(1, "TQB"), Map.entry(3, "RB/WR"), Map.entry(5, "WR/TE"), Map.entry(7, "OP"),
            Map.entry(18, "P"), Map.entry(19, "HC"));
    private static final Map<Integer, String> NBA_UNMODELLED = Map.ofEntries(
            Map.entry(7, "SG/SF"), Map.entry(8, "G/F"), Map.entry(9, "PF/C"), Map.entry(10, "F/C"));
    private static final int NFL_BENCH = 20, NFL_IR = 21, NBA_BENCH = 12, NBA_IR = 13;

    private static Map<Integer, String> orderedSlots(Object... kv) {
        Map<Integer, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((Integer) kv[i], (String) kv[i + 1]);
        return m;
    }

    // ---- field access: every read names its path ------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o, String path) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new EspnShapeChanged(path);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object o, String path) {
        if (!(o instanceof List<?> l)) throw new EspnShapeChanged(path);
        for (Object e : l) if (!(e instanceof Map)) throw new EspnShapeChanged(path + "[]");
        return (List<Map<String, Object>>) l;
    }

    static String str(Object o, String path) {
        if (o instanceof String s && !s.isBlank()) return s;
        throw new EspnShapeChanged(path);
    }

    static int integer(Object o, String path) {
        if (o instanceof Number n) return n.intValue();
        throw new EspnShapeChanged(path);
    }

    static double dbl(Object o, String path) {
        if (o instanceof Number n) return n.doubleValue();
        throw new EspnShapeChanged(path);
    }

    private static Object at(Map<String, Object> m, String key) {
        return m == null ? null : m.get(key);
    }

    // ---- accept / refuse --------------------------------------------------------------------------

    /** FR-012: only points leagues. Throws {@link EspnUnsupported} naming the type otherwise. */
    public static void requirePointsScoring(Map<String, Object> league) {
        Map<String, Object> settings = map(at(league, "settings"), "settings");
        Map<String, Object> scoring = map(at(settings, "scoringSettings"), "settings.scoringSettings");
        String type = str(scoring.get("scoringType"), "settings.scoringSettings.scoringType");
        // unverified: confirm against real league (T053) -- only H2H_POINTS was seen (2018 fixture).
        if (!type.equals("H2H_POINTS") && !type.equals("TOTAL_POINTS")) {
            throw new EspnUnsupported(EspnUnsupported.UNSUPPORTED_SCORING, type);
        }
    }

    // ---- the season ---------------------------------------------------------------------------------

    /**
     * @param ref the league and the APP season this payload is for (see {@link EspnSeasons})
     */
    public static EspnSeason translate(Map<String, Object> league, EspnLeagueRef ref) {
        requirePointsScoring(league);
        Sport sport = ref.sport();
        Map<String, Object> settings = map(at(league, "settings"), "settings");
        Map<String, Object> status = map(at(league, "status"), "status");
        Map<String, Object> schedSettings = map(at(settings, "scheduleSettings"), "settings.scheduleSettings");
        Map<String, Object> rosterSettings = map(at(settings, "rosterSettings"), "settings.rosterSettings");
        Map<String, Object> scoringSettings = map(at(settings, "scoringSettings"), "settings.scoringSettings");

        String name = str(settings.get("name"), "settings.name");
        int size = integer(settings.get("size"), "settings.size");
        int currentMatchupPeriod = integer(status.get("currentMatchupPeriod"), "status.currentMatchupPeriod");
        Object isActive = status.get("isActive");
        if (!(isActive instanceof Boolean active)) throw new EspnShapeChanged("status.isActive");

        // Draft ----------------------------------------------------------------------------------
        Map<String, Object> draftDetail = map(at(league, "draftDetail"), "draftDetail");
        boolean drafted = bool(draftDetail.get("drafted"), "draftDetail.drafted");
        boolean inProgress = bool(draftDetail.get("inProgress"), "draftDetail.inProgress");
        // unverified: confirm against real league (T053). status.isActive false after the draft is read as "season over".
        String leagueStatus = inProgress ? "drafting" : !drafted ? "pre_draft" : active ? "in_season" : "complete";
        boolean complete = "complete".equals(leagueStatus);

        // Roster slots -----------------------------------------------------------------------------
        Map<String, Object> counts = map(at(rosterSettings, "lineupSlotCounts"), "settings.rosterSettings.lineupSlotCounts");
        List<String> rosterPositions = new ArrayList<>();
        List<String> unmodelled = new ArrayList<>();
        slots(sport, counts, rosterPositions, unmodelled);

        // Scoring ----------------------------------------------------------------------------------
        List<Map<String, Object>> items = list(at(scoringSettings, "scoringItems"), "settings.scoringSettings.scoringItems");
        EspnScoringMap.Result scoring = EspnScoringMap.translate(sport, items);

        // Schedule / settings_json ------------------------------------------------------------------
        int matchupPeriodCount = integer(schedSettings.get("matchupPeriodCount"), "settings.scheduleSettings.matchupPeriodCount");
        int playoffTeams = integer(schedSettings.get("playoffTeamCount"), "settings.scheduleSettings.playoffTeamCount");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leg", currentMatchupPeriod);
        out.put("playoff_teams", playoffTeams);
        out.put("playoff_week_start", matchupPeriodCount + 1);
        out.put("playoff_seed_type", seedType(schedSettings));
        out.put("playoff_round_type", roundType(schedSettings));
        // A league with no divisions probably still lists one default division, so only 2+ count as real
        // divisions (code review S8). unverified (T053): confirm against a real league what ESPN sends.
        int divisionCount = list(at(schedSettings, "divisions"), "settings.scheduleSettings.divisions").size();
        out.put("divisions", divisionCount > 1 ? divisionCount : 0);
        out.put("league_average_match", 0);   // ESPN has no median game
        waiver(settings, out);

        // Weeks (F9) --------------------------------------------------------------------------------
        Map<Integer, Integer> weekOfPeriod = new LinkedHashMap<>();
        // matchup period -> the scoring period a transactions fetch asks for (its last one: for NBA the last
        // day, for single-period NFL weeks the week itself). unverified: confirm against real league (T053).
        Map<Integer, Integer> txPeriod = new LinkedHashMap<>();
        boolean multiWeek = false;
        Object mp = schedSettings.get("matchupPeriods");
        if (mp instanceof Map<?, ?> periods) {
            for (Map.Entry<?, ?> e : periods.entrySet()) {
                int pid;
                try {
                    pid = Integer.parseInt(String.valueOf(e.getKey()));
                } catch (NumberFormatException ex) {
                    throw new EspnShapeChanged("settings.scheduleSettings.matchupPeriods");
                }
                if (!(e.getValue() instanceof List<?> sp) || sp.isEmpty()) {
                    throw new EspnShapeChanged("settings.scheduleSettings.matchupPeriods[]");
                }
                if (sp.size() > 1) multiWeek = true;
                weekOfPeriod.put(pid, integer(sp.get(0), "settings.scheduleSettings.matchupPeriods[]"));
                txPeriod.put(pid, integer(sp.get(sp.size() - 1), "settings.scheduleSettings.matchupPeriods[]"));
            }
        } else if (sport == Sport.NFL) {
            throw new EspnShapeChanged("settings.scheduleSettings.matchupPeriods");
        }

        // F9: an NFL matchup period spanning more than one scoring period cannot be read as an NFL week; the
        // analysis pages say so (LeagueAvailability reads this flag). Never written for Sleeper, never for NBA.
        if (sport == Sport.NFL && multiWeek) out.put("espn_multi_week_periods", true);

        List<EspnSeason.Period> periodsOut = periods(sport, list(at(league, "schedule"), "schedule"), weekOfPeriod, multiWeek);

        // People and teams ----------------------------------------------------------------------------
        List<EspnSeason.Member> members = new ArrayList<>();
        for (Map<String, Object> m : list(at(league, "members"), "members")) {
            String id = str(m.get("id"), "members[].id");
            String display = m.get("displayName") instanceof String s && !s.isBlank() ? s
                    : str(m.get("firstName"), "members[].displayName");
            // unverified: confirm against real league (T053) -- espn-api reads isLeagueManager.
            members.add(new EspnSeason.Member(id, display, Boolean.TRUE.equals(m.get("isLeagueManager"))));
        }
        List<EspnSeason.Team> teams = new ArrayList<>();
        for (Map<String, Object> t : list(at(league, "teams"), "teams")) teams.add(team(sport, t, complete));

        // Previous season link ---------------------------------------------------------------------
        String previousKey = null;
        Object prev = status.get("previousSeasons");
        if (prev instanceof List<?> ps) {
            int espnPrev = EspnSeasons.toEspn(sport, ref.season() - 1);
            for (Object p : ps) {
                if (p instanceof Number n && n.intValue() == espnPrev) previousKey = ref.keyFor(ref.season() - 1);
            }
        }

        return new EspnSeason(ref.key(), ref.leagueId(), sport, ref.season(), previousKey, name, size, leagueStatus,
                out, new LinkedHashMap<>(scoring.scoring()), rosterPositions, scoring.untranslated(), unmodelled,
                members, teams, periodsOut, draft(sport, league, draftDetail, settings, size, counts, leagueStatus),
                multiWeek, currentMatchupPeriod, complete, txPeriod);
    }

    // ---- transactions (spec 035 T039) -----------------------------------------------------------------

    // unverified: confirm against real league (T053). Every string below is a guess from espn-api's
    // vocabulary; the transactions view (mTransactions2) is the least certain shape in this client (R9).
    private static final String TX_FREE_AGENT = "FREEAGENT";
    private static final String TX_WAIVER = "WAIVER";
    private static final Set<String> TX_TRADE_DONE = Set.of("TRADE_ACCEPT", "TRADE");
    private static final String TX_ROSTER = "ROSTER";
    private static final String TX_EXECUTED = "EXECUTED";

    /**
     * One scoring period's transactions payload as app rows. Lineup-only moves, proposals and anything
     * not executed or failed are skipped and counted, with the TYPE string (never an id or a name) kept
     * so a wrong guess is visible in the log. {@code week} is the app week the caller fetched for.
     *
     * <p>A missing {@code transactions} list is a shape change, not "no moves": reading it as empty would
     * silently turn a wrong guess into an empty page.
     *
     * @param faabLeague {@code settings_json.waiver_type == 2}
     */
    public static EspnSeason.TxResult transactions(Map<String, Object> payload, int week, boolean faabLeague) {
        List<Map<String, Object>> raw = list(at(payload, "transactions"), "transactions");
        List<EspnSeason.Tx> out = new ArrayList<>();
        Set<String> skippedTypes = new LinkedHashSet<>();
        int skipped = 0;
        for (Map<String, Object> t : raw) {
            String espnType = str(t.get("type"), "transactions[].type");
            String type = txType(espnType);
            String status = txStatus(t.get("status"));
            if (type == null || status == null) {
                skipped++;
                skippedTypes.add(espnType + (status == null ? "/status" : ""));
                continue;
            }
            String id = t.get("id") instanceof Number n ? String.valueOf(n.longValue())
                    : str(t.get("id"), "transactions[].id");
            Integer actor = t.get("teamId") instanceof Number n ? n.intValue() : null;
            boolean trade = type.equals("TRADE");
            Map<String, Integer> adds = new LinkedHashMap<>();
            Map<String, Integer> drops = new LinkedHashMap<>();
            Object items = t.get("items");
            if (items != null) {
                for (Map<String, Object> item : list(items, "transactions[].items")) {
                    String itemType = str(item.get("type"), "transactions[].items[].type");
                    String pid = String.valueOf(num(item.get("playerId"), "transactions[].items[].playerId"));
                    Integer to = item.get("toTeamId") instanceof Number n ? n.intValue() : actor;
                    Integer from = item.get("fromTeamId") instanceof Number n ? n.intValue() : actor;
                    switch (itemType) {
                        case "ADD" -> { if (to != null) adds.put(pid, to); }
                        case "DROP" -> { if (from != null) drops.put(pid, from); }
                        case "TRADE" -> {
                            if (to != null) adds.put(pid, to);
                            if (from != null) drops.put(pid, from);
                        }
                        default -> { /* LINEUP and anything else is not a roster move */ }
                    }
                }
            }
            if (adds.isEmpty() && drops.isEmpty()) {   // a lineup-only move
                skipped++;
                skippedTypes.add(espnType + "/lineup-only");
                continue;
            }
            Integer rosterId = trade ? null : actor;
            Integer bid = null;
            if (faabLeague && type.equals("WAIVER") && t.get("bidAmount") instanceof Number b) bid = b.intValue();
            java.time.Instant created = t.get("proposedDate") instanceof Number d ? java.time.Instant.ofEpochMilli(d.longValue()) : null;
            out.add(new EspnSeason.Tx(id, week, type, status, rosterId, adds, drops, bid, created));
        }
        return new EspnSeason.TxResult(out, skipped, skippedTypes);
    }

    /** The V19 type set; null for a type that is not a roster move this app records. */
    static String txType(String espn) {
        if (espn.equals(TX_FREE_AGENT)) return "FREE_AGENT";
        if (espn.equals(TX_WAIVER)) return "WAIVER";
        if (TX_TRADE_DONE.contains(espn)) return "TRADE";
        if (espn.equals(TX_ROSTER)) return "COMMISSIONER";   // unverified: league-manager roster moves
        return null;
    }

    /** Sleeper's two stored statuses; null for a move still pending or cancelled (not a record of anything yet). */
    static String txStatus(Object o) {
        if (!(o instanceof String s)) return null;
        if (s.equals(TX_EXECUTED)) return "complete";
        if (s.startsWith("FAILED")) return "failed";
        return null;
    }

    /**
     * The public player catalogue ({@code EspnClient.players}) as entries, for naming draft-pick players
     * who are on no roster. Shape per research R2 (measured on the public endpoint 2026-10-14):
     * {@code id}, {@code fullName}, {@code defaultPositionId}, {@code proTeamId}.
     */
    public static List<EspnSeason.Entry> catalogue(Sport sport, Map<String, Object> payload) {
        List<EspnSeason.Entry> out = new ArrayList<>();
        for (Map<String, Object> p : list(payload.get("players"), "players")) {
            out.add(new EspnSeason.Entry(String.valueOf(num(p.get("id"), "players[].id")), -1,
                    str(p.get("fullName"), "players[].fullName"), positionLabel(sport, p.get("defaultPositionId")),
                    p.get("proTeamId") instanceof Number n ? n.intValue() : null));
        }
        return out;
    }

    private static boolean bool(Object o, String path) {
        if (o instanceof Boolean b) return b;
        throw new EspnShapeChanged(path);
    }

    /** F8: 0 only for the plain record ladder; -1 (not modelable) for anything else or absent. */
    static int seedType(Map<String, Object> sched) {
        Object rule = sched.get("playoffSeedingRule");
        return rule instanceof String s && PLAIN_LADDER_SEEDING_RULES.contains(s) ? 0 : -1;
    }

    /** F8: one week per round (0) only when each playoff matchup lasts one period; else -1. */
    static int roundType(Map<String, Object> sched) {
        Object len = sched.get("playoffMatchupPeriodLength");
        return len instanceof Number n && n.intValue() == 1 ? 0 : -1;
    }

    private static void waiver(Map<String, Object> settings, Map<String, Object> out) {
        Map<String, Object> acq = map(at(settings, "acquisitionSettings"), "settings.acquisitionSettings");
        Object budgetFlag = acq.get("isUsingAcquisitionBudget");
        int type;
        double budget = 0;
        if (Boolean.TRUE.equals(budgetFlag)) {
            type = 2;
            budget = dbl(acq.get("acquisitionBudget"), "settings.acquisitionSettings.acquisitionBudget");
        } else if (Boolean.FALSE.equals(budgetFlag) && acq.get("acquisitionType") instanceof String t && t.contains("WAIVER")) {
            // unverified: confirm against real league (T053) -- rolling vs priority is not told apart.
            type = 0;
        } else {
            type = -1;
        }
        out.put("waiver_type", type);
        out.put("waiver_budget", budget);
    }

    private static void slots(Sport sport, Map<String, Object> counts, List<String> positions, List<String> unmodelled) {
        Map<Integer, String> modelled = sport == Sport.NFL ? NFL_SLOTS : NBA_SLOTS;
        Map<Integer, String> notModelled = sport == Sport.NFL ? NFL_UNMODELLED : NBA_UNMODELLED;
        int bench = sport == Sport.NFL ? NFL_BENCH : NBA_BENCH;
        int ir = sport == Sport.NFL ? NFL_IR : NBA_IR;
        Map<Integer, Integer> byId = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : counts.entrySet()) {
            try {
                byId.put(Integer.parseInt(e.getKey()), integer(e.getValue(), "settings.rosterSettings.lineupSlotCounts"));
            } catch (NumberFormatException ex) {
                throw new EspnShapeChanged("settings.rosterSettings.lineupSlotCounts");
            }
        }
        for (Map.Entry<Integer, String> s : modelled.entrySet()) {
            for (int i = 0; i < byId.getOrDefault(s.getKey(), 0); i++) positions.add(s.getValue());
        }
        for (int i = 0; i < byId.getOrDefault(bench, 0); i++) positions.add("BN");
        // IR is not a roster_positions entry in Sleeper-stored leagues either: it is the roster's reserve list.
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<Integer, Integer> e : byId.entrySet()) {
            int id = e.getKey();
            if (e.getValue() <= 0 || modelled.containsKey(id) || id == bench || id == ir) continue;
            seen.add(notModelled.getOrDefault(id, "SLOT_" + id));
        }
        unmodelled.addAll(seen);
    }

    private static List<EspnSeason.Period> periods(Sport sport, List<Map<String, Object>> schedule,
                                                   Map<Integer, Integer> weekOfPeriod, boolean multiWeek) {
        Map<Integer, List<EspnSeason.Matchup>> byPeriod = new LinkedHashMap<>();
        for (Map<String, Object> g : schedule) {
            int periodId = integer(g.get("matchupPeriodId"), "schedule[].matchupPeriodId");
            int id = integer(g.get("id"), "schedule[].id");
            Map<String, Object> home = map(g.get("home"), "schedule[].home");
            EspnSeason.Side h = new EspnSeason.Side(integer(home.get("teamId"), "schedule[].home.teamId"),
                    pointsOf(home));
            EspnSeason.Side a = null;
            if (g.get("away") != null) {   // a bye has no away
                Map<String, Object> away = map(g.get("away"), "schedule[].away");
                a = new EspnSeason.Side(integer(away.get("teamId"), "schedule[].away.teamId"), pointsOf(away));
            }
            // unverified: confirm against real league (T053) -- the "not yet decided" value.
            boolean decided = g.get("winner") instanceof String w && !w.equals("UNDECIDED");
            if (g.get("winner") != null && !(g.get("winner") instanceof String)) throw new EspnShapeChanged("schedule[].winner");
            byPeriod.computeIfAbsent(periodId, k -> new ArrayList<>()).add(new EspnSeason.Matchup(id, h, a, decided));
        }
        List<EspnSeason.Period> out = new ArrayList<>();
        for (Map.Entry<Integer, List<EspnSeason.Matchup>> e : byPeriod.entrySet()) {
            int week = sport == Sport.NBA || multiWeek ? e.getKey() : weekOfPeriod.getOrDefault(e.getKey(), e.getKey());
            out.add(new EspnSeason.Period(e.getKey(), week, e.getValue()));
        }
        return out;
    }

    /** A side's score; a not-yet-played side may carry no totalPoints, which reads as 0 only because the matchup is undecided. */
    private static double pointsOf(Map<String, Object> side) {
        Object p = side.get("totalPoints");
        return p == null ? 0.0 : dbl(p, "schedule[].totalPoints");
    }

    private static EspnSeason.Team team(Sport sport, Map<String, Object> t, boolean complete) {
        int id = integer(t.get("id"), "teams[].id");
        String teamName;
        if (t.get("name") instanceof String n && !n.isBlank()) teamName = n;
        else if (t.get("location") instanceof String loc && t.get("nickname") instanceof String nick
                && !(loc + nick).isBlank()) teamName = (loc + " " + nick).trim();
        else throw new EspnShapeChanged("teams[].name");
        String owner = str(t.get("primaryOwner"), "teams[].primaryOwner");

        Map<String, Object> overall = map(at(map(t.get("record"), "teams[].record"), "overall"), "teams[].record.overall");
        Integer rank = t.get("rankCalculatedFinal") instanceof Number r ? r.intValue() : null;
        Integer placement = complete && rank != null && rank == 1 ? 1 : null;

        List<EspnSeason.Entry> entries = new ArrayList<>();
        Map<String, Object> roster = map(t.get("roster"), "teams[].roster");
        for (Map<String, Object> e : list(roster.get("entries"), "teams[].roster.entries")) {
            Map<String, Object> player = map(at(map(e.get("playerPoolEntry"), "teams[].roster.entries[].playerPoolEntry"), "player"),
                    "teams[].roster.entries[].playerPoolEntry.player");
            String pid = String.valueOf(num(e.get("playerId"), "teams[].roster.entries[].playerId"));
            entries.add(new EspnSeason.Entry(pid, integer(e.get("lineupSlotId"), "teams[].roster.entries[].lineupSlotId"),
                    str(player.get("fullName"), "teams[].roster.entries[].playerPoolEntry.player.fullName"),
                    positionLabel(sport, player.get("defaultPositionId")),
                    player.get("proTeamId") instanceof Number n ? n.intValue() : null));
        }
        List<String> coOwners = new ArrayList<>();
        if (t.get("owners") instanceof List<?> os) {
            for (Object o : os) if (o instanceof String s && !s.equals(owner)) coOwners.add(s);
        }
        return new EspnSeason.Team(id, teamName, owner, coOwners,
                integer(overall.get("wins"), "teams[].record.overall.wins"),
                integer(overall.get("losses"), "teams[].record.overall.losses"),
                integer(overall.get("ties"), "teams[].record.overall.ties"),
                dbl(overall.get("pointsFor"), "teams[].record.overall.pointsFor"),
                dbl(overall.get("pointsAgainst"), "teams[].record.overall.pointsAgainst"),
                placement, entries);
    }

    private static long num(Object o, String path) {
        if (o instanceof Number n) return n.longValue();
        throw new EspnShapeChanged(path);
    }

    /** IR slot id for a sport: those entries go to {@code reserve}. */
    public static int irSlot(Sport sport) {
        return sport == Sport.NFL ? NFL_IR : NBA_IR;
    }

    /**
     * ESPN {@code defaultPositionId} to a position label. A modelled one is a Sleeper position code;
     * anything else is {@code OTHER_<id>}, which the strict matcher reads as ESPN_ONLY_POSITION (F11).
     * Null when ESPN gave none. unverified: confirm against real league (T053).
     */
    static String positionLabel(Sport sport, Object id) {
        if (!(id instanceof Number n)) return null;
        int v = n.intValue();
        if (sport == Sport.NFL) {
            return switch (v) {
                case 1 -> "QB"; case 2 -> "RB"; case 3 -> "WR"; case 4 -> "TE"; case 5 -> "K"; case 16 -> "DEF";
                default -> "OTHER_" + v;
            };
        }
        return switch (v) {
            case 1 -> "PG"; case 2 -> "SG"; case 3 -> "SF"; case 4 -> "PF"; case 5 -> "C";
            default -> "OTHER_" + v;
        };
    }

    private static EspnSeason.Draft draft(Sport sport, Map<String, Object> league, Map<String, Object> draftDetail,
                                          Map<String, Object> settings, int size, Map<String, Object> counts,
                                          String leagueStatus) {
        Map<String, Object> draftSettings = map(at(settings, "draftSettings"), "settings.draftSettings");
        String rawType = str(draftSettings.get("type"), "settings.draftSettings.type");
        String type = switch (rawType) {
            case "SNAKE" -> "snake";
            case "AUCTION" -> "auction";
            default -> "linear";   // raw value is logged by the ingest service
        };
        List<Integer> order = new ArrayList<>();
        if (draftSettings.get("pickOrder") instanceof List<?> po) {
            for (Object o : po) order.add(integer(o, "settings.draftSettings.pickOrder[]"));
        }
        List<EspnSeason.DraftPick> picks = new ArrayList<>();
        if (draftDetail.get("picks") != null) {
            for (Map<String, Object> p : list(draftDetail.get("picks"), "draftDetail.picks")) {
                picks.add(new EspnSeason.DraftPick(integer(p.get("overallPickNumber"), "draftDetail.picks[].overallPickNumber"),
                        integer(p.get("roundId"), "draftDetail.picks[].roundId"),
                        integer(p.get("teamId"), "draftDetail.picks[].teamId"),
                        String.valueOf(num(p.get("playerId"), "draftDetail.picks[].playerId"))));
            }
        }
        int rounds = picks.stream().mapToInt(EspnSeason.DraftPick::round).max().orElse(0);
        if (rounds == 0) {
            // unverified: confirm against real league (T053) -- no picks yet, so rounds is the roster size.
            int total = 0;
            for (Map.Entry<String, Object> e : counts.entrySet()) {
                int slot = Integer.parseInt(e.getKey());
                if (slot != irSlot(sport)) total += integer(e.getValue(), "settings.rosterSettings.lineupSlotCounts");
            }
            rounds = total;
        }
        return new EspnSeason.Draft(leagueStatus.equals("complete") || leagueStatus.equals("in_season") ? "complete"
                : leagueStatus, type, rounds, size, order, picks);
    }
}
