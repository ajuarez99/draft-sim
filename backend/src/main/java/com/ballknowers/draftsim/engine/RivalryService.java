package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.RivalryProperties;
import com.ballknowers.draftsim.engine.RivalryGameKind.Classification;
import com.ballknowers.draftsim.engine.RivalryGameKind.Kind;
import com.ballknowers.draftsim.engine.RivalryMatching.PairStats;
import com.ballknowers.draftsim.store.LeagueBracketRepository;
import com.ballknowers.draftsim.store.LeagueMatchupRepository;
import com.ballknowers.draftsim.store.LeagueMatchupRepository.PairedGame;
import com.ballknowers.draftsim.store.LeagueMatchupRepository.ScheduledPair;
import com.ballknowers.draftsim.store.LeagueMemberRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueRepository.LeagueRow;
import com.ballknowers.draftsim.store.LeagueRepository.PlayoffFormat;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository.StandingRow;
import com.ballknowers.draftsim.store.WeekBound;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The rivalry page (specs/034-rivalry-schedule-grid): an all-time manager x manager matrix, the requested
 * season as manager x week with the record going into each game, and a recommended set of rivalry week
 * pairings. Contracts C1; plan.md "Amended after review" overrides research/data-model where they differ.
 *
 * <p><b>The league row is the URL's.</b> This reads {@code leagues.bySleeperId(urlId)} and never
 * {@link LeagueSeasonResolver} (F9): the resolver walks back to the newest season with scored weeks, which
 * for NBA 2026 is 2025, and would answer about the wrong season. {@code seasonGrid.currentWeek} is that
 * row's own {@code leg}, never defaulted.
 *
 * <p><b>One definition of a meeting.</b> Every meeting comes from {@link LeagueMatchupRepository#pairedWithScores}
 * -- the query the versus page uses -- once per league of the chain (F15, at most three queries), and the
 * winner from {@link HeadToHeadService#outcome}. A parity test pins the two against drifting (SC-001).
 * Self-pairs (the schema allows one manager on two rosters) and games where neither side has a manager are
 * dropped; a game against a manager we can't name still counts in that manager's {@code allOpponents} (as a
 * "former" opponent) but cannot form a matrix cell.
 *
 * <p>Totals and {@code byKind} are per WEEK, which keeps parity with the versus page. {@code finals[]}, every
 * reason and the playoff bonus are per bracket MATCH (F3): a two-week final is one final whose winner is the
 * bracket's {@code w} and whose points are the summed legs.
 *
 * <p>User-facing sentences here must not tell the reader to run an ingest ({@code NoIngestHintsInMessagesTest}).
 */
@Service
public class RivalryService {

    static final String MINIMUM_LABEL = "hand-set";

    static final String RECOMMENDATION_NOTE =
            "This is a suggestion for a rivalry week; it doesn't change your Sleeper schedule.";

    static final String TBD_NOTE =
            "Playoff pairings appear once the week has been scored. Until then only winners-bracket opponents"
                    + " that the bracket already names are shown.";

    /** B8: one grid row per manager; a second roster of the same manager isn't shown as its own row. */
    static final String ROSTER_NOTE =
            "One manager owns more than one team this season; the grid shows each manager's first team only,"
                    + " though every game still counts in the matrix.";

    static final String MEDIAN_NOTE =
            "This league also scores every team against the weekly median, so a team's standings record can"
                    + " include games that aren't against any opponent shown here.";

    private final LeagueRepository leagues;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueMatchupRepository fixtures;
    private final LeagueBracketRepository brackets;
    private final LeagueMemberRepository members;
    private final ManagerRepository managers;
    private final RivalryProperties props;

    public RivalryService(LeagueRepository leagues, RosterSeasonRepository rosterSeasons,
                          LeagueMatchupRepository fixtures, LeagueBracketRepository brackets,
                          LeagueMemberRepository members, ManagerRepository managers, RivalryProperties props) {
        this.leagues = leagues;
        this.rosterSeasons = rosterSeasons;
        this.fixtures = fixtures;
        this.brackets = brackets;
        this.members = members;
        this.managers = managers;
        this.props = props;
    }

    // ---------------------------------------------------------------- wire records (contracts C1)

    /** {@code meetings} = wins + losses + ties. */
    public record Tally(int wins, int losses, int ties, int meetings) {}

    public record Row(long managerId, String managerName, String teamName, String avatarId, boolean isMe,
                      Tally allOpponents, int formerOpponentMeetings) {}

    public record KindTally(int aWins, int bWins, int ties) {}

    /** {@code winner} is the bracket's {@code w} as A/B, or null while undecided; points are the summed legs. */
    /**
     * {@code scoresAgreeWithWinner} is null while {@code winner} is undecided; false when Sleeper's bracket
     * gives the final to the side with fewer stored points (code-review B2: measured on NBA 2024/2025).
     */
    public record Final(int season, double aPoints, double bPoints, String winner, boolean twoWeek,
                        Boolean scoresAgreeWithWinner) {}

    public record GameLine(int season, int week, double aPoints, double bPoints, String winner, String kind) {}

    /** {@code a < b} by manager id. {@code status}: MET | NEVER_MET | NOT_YET_MET | NO_SHARED_HISTORY. */
    public record PairRecord(long aManagerId, long bManagerId, int meetings, int aWins, int bWins, int ties,
                             Map<String, KindTally> byKind, String status, List<Final> finals, List<GameLine> games) {}

    public record SplitNote(int season, String reason, String text) {}

    public record Matrix(List<Row> rows, List<PairRecord> pairs, List<SplitNote> splitNotes) {}

    public record WeekColumn(int week, boolean isPlayoffWeek) {}

    /** {@code bracketDisagrees}: this game is a final whose stored scores contradict Sleeper's bracket winner (B2). */
    public record CellResult(double points, double opponentPoints, String outcome, String kind,
                             boolean bracketDisagrees) {}

    /**
     * {@code state}: OPPONENT | BYE | NO_GAME | TBD. {@code source} (SCHEDULE | BRACKET) and {@code priorRecord}
     * only for OPPONENT; {@code opponentManagerId} null for an opponent roster with no manager.
     */
    public record Cell(long managerId, int week, String state, String source, Long opponentManagerId,
                       Tally priorRecord, CellResult result) {}

    public record SeasonGrid(String scheduleStatus, Integer currentWeek, List<WeekColumn> weeks,
                             String playoffWindowNote, String tbdNote, String medianNote, String rosterNote,
                             List<Cell> cells) {}

    public record Reason(String code, String text, Map<String, Object> numbers) {}

    public record Pairing(long aManagerId, long bManagerId, int meetings, double score, Reason reason, boolean thin) {}

    public record Unpaired(long managerId, String why) {}

    public record Recommendation(String status, Integer minMeetings, String minMeetingsLabel,
                                 List<Pairing> pairings, Unpaired unpaired, int thinCount, String note) {}

    public record Response(String sport, int season, List<Integer> seasonsCovered, Matrix matrix,
                           SeasonGrid seasonGrid, Recommendation recommendation) {}

    // ---------------------------------------------------------------- entry point

    /** Empty when the league isn't stored. {@code sleeperUserId} (the caller) only decides {@code isMe}. */
    public Optional<Response> forLeague(String sleeperLeagueId, String sleeperUserId) {
        Optional<LeagueRow> found = leagues.bySleeperId(sleeperLeagueId);
        if (found.isEmpty()) return Optional.empty();
        LeagueRow league = found.get();
        List<LeagueRow> chain = leagues.chainBySleeperId(sleeperLeagueId);
        if (chain.isEmpty()) chain = List.of(league);

        Long callerManagerId = sleeperUserId == null ? null : managers.idsBySleeperUserId().get(sleeperUserId);

        // Current managers: whoever owns a roster in the REQUESTED season (R4), in standings order.
        List<StandingRow> standings = new ArrayList<>(rosterSeasons.forLeague(league.id()));
        standings.sort(STANDINGS_ORDER);
        Map<Long, StandingRow> currentByManager = new LinkedHashMap<>();
        Map<Integer, Long> managerOfRoster = new HashMap<>();
        Map<Long, Integer> rosterOfManager = new HashMap<>();
        boolean twoRosters = false;
        for (StandingRow s : standings) {
            managerOfRoster.put(s.rosterId(), s.managerId());
            if (s.managerId() == null) continue;
            currentByManager.putIfAbsent(s.managerId(), s);
            if (rosterOfManager.putIfAbsent(s.managerId(), s.rosterId()) != null) twoRosters = true;
        }

        // Per chain league: formats, brackets, shared-season membership, and the classified games.
        List<Long> chainIds = chain.stream().map(LeagueRow::id).toList();
        Map<Long, List<LeagueBracketRepository.Match>> bracketByLeague = new HashMap<>();
        for (LeagueBracketRepository.Match m : brackets.forLeagues(chainIds)) {
            bracketByLeague.computeIfAbsent(m.leagueId(), k -> new ArrayList<>()).add(m);
        }
        List<Game> games = new ArrayList<>();
        Map<Long, Set<Long>> managersOfScoredLeague = new HashMap<>();
        Map<Long, PlayoffFormat> formatByLeague = new HashMap<>();
        for (LeagueRow l : chain) {
            PlayoffFormat fmt = leagues.playoffFormat(l.id()).orElse(null);
            formatByLeague.put(l.id(), fmt);
            List<LeagueBracketRepository.Match> bracket = bracketByLeague.getOrDefault(l.id(), List.of());
            List<PairedGame> played = fixtures.pairedWithScores(List.of(l.id()), WeekBound.ALL_WEEKS);
            for (PairedGame g : played) {
                if (g.aManagerId() == null && g.bManagerId() == null) continue;
                if (g.aManagerId() != null && g.aManagerId().equals(g.bManagerId())) continue;
                Classification c = RivalryGameKind.classify(fmt, bracket, g.week(), g.aRosterId(), g.bRosterId());
                games.add(new Game(l.id(), l.season(), g.week(), g.aRosterId(), g.bRosterId(),
                        g.aManagerId(), g.bManagerId(), g.aPoints().doubleValue(), g.bPoints().doubleValue(), c));
            }
            if (!played.isEmpty()) {
                Set<Long> owners = new HashSet<>();
                for (StandingRow s : rosterSeasons.forLeague(l.id())) if (s.managerId() != null) owners.add(s.managerId());
                managersOfScoredLeague.put(l.id(), owners);
            }
        }
        games.sort(Comparator.comparingInt(Game::season).thenComparingInt(Game::week));

        Map<PairKey, List<Game>> byPair = new HashMap<>();
        for (Game g : games) {
            if (g.aMgr() == null || g.bMgr() == null) continue;
            byPair.computeIfAbsent(PairKey.of(g.aMgr(), g.bMgr()), k -> new ArrayList<>()).add(g);
        }

        List<ScheduledPair> scheduled = fixtures.scheduledPairs(league.id());
        Map<PairKey, Integer> scheduledWeek = new HashMap<>();
        // B10: "not yet met" means scheduled AFTER the last scored week of this season; a past week whose
        // game never got a score is not a game to wait for.
        int lastScoredWeek = 0;
        for (Game g : games) if (g.leagueId() == league.id()) lastScoredWeek = Math.max(lastScoredWeek, g.week());
        for (ScheduledPair p : scheduled) {
            if (p.week() <= lastScoredWeek) continue;
            if (p.aManagerId() == null || p.bManagerId() == null || p.aManagerId().equals(p.bManagerId())) continue;
            scheduledWeek.merge(PairKey.of(p.aManagerId(), p.bManagerId()), p.week(), Math::min);
        }

        Map<Long, String> teamNames = members.teamNamesByManager(league.id());
        List<Long> managerIds = new ArrayList<>(currentByManager.keySet());
        Map<Long, String> nameOf = new HashMap<>();
        for (Map.Entry<Long, StandingRow> e : currentByManager.entrySet()) {
            String n = e.getValue().managerName();
            String display = n == null || n.isBlank() ? "Manager " + e.getKey() : n;
            // B3: the same team-name-first lookup the rows use, so a sentence and its card name one person once.
            String team = teamNames.get(e.getKey());
            nameOf.put(e.getKey(), team != null ? team : display);
        }

        // ---- matrix
        List<Long> sortedIds = managerIds.stream().sorted().toList();
        Map<PairKey, PairCalc> calc = new LinkedHashMap<>();
        List<PairRecord> pairs = new ArrayList<>();
        for (int i = 0; i < sortedIds.size(); i++) {
            for (int j = i + 1; j < sortedIds.size(); j++) {
                long a = sortedIds.get(i), b = sortedIds.get(j);
                PairKey key = new PairKey(a, b);
                PairCalc pc = pairCalc(key, byPair.getOrDefault(key, List.of()), scheduledWeek.get(key),
                        managersOfScoredLeague, formatByLeague);
                calc.put(key, pc);
                pairs.add(pc.record());
            }
        }
        List<Row> rows = new ArrayList<>();
        for (Long id : managerIds) {
            StandingRow s = currentByManager.get(id);
            int w = 0, l = 0, t = 0, former = 0;
            for (Game g : games) {
                boolean isA = id.equals(g.aMgr());
                boolean isB = id.equals(g.bMgr());
                if (!isA && !isB) continue;
                String out = HeadToHeadService.outcome(isA ? g.aPts() : g.bPts(), isA ? g.bPts() : g.aPts());
                switch (out) {
                    case "A" -> w++;
                    case "B" -> l++;
                    default -> t++;
                }
                Long opp = isA ? g.bMgr() : g.aMgr();
                if (opp == null || !currentByManager.containsKey(opp)) former++;
            }
            String team = teamNames.get(id);
            String display = s.managerName() == null || s.managerName().isBlank() ? "Manager " + id : s.managerName();
            rows.add(new Row(id, display, team != null ? team : display, s.avatarId(),
                    callerManagerId != null && callerManagerId.equals(id), new Tally(w, l, t, w + l + t), former));
        }
        Matrix matrix = new Matrix(rows, pairs, splitNotes(games));

        // ---- season grid
        SeasonGrid grid = seasonGrid(league, formatByLeague.get(league.id()), bracketByLeague.getOrDefault(league.id(), List.of()),
                managerIds, rosterOfManager, managerOfRoster, scheduled, games, byPair,
                twoRosters ? ROSTER_NOTE : null);

        // ---- recommendation
        Recommendation rec = recommendation(chain, managerIds, nameOf, calc);

        List<Integer> seasons = chain.stream().map(LeagueRow::season).toList();
        return Optional.of(new Response(league.sport().code(), league.season(), seasons, matrix, grid, rec));
    }

    // ---------------------------------------------------------------- internals

    private record PairKey(long lo, long hi) {
        static PairKey of(long x, long y) {
            return new PairKey(Math.min(x, y), Math.max(x, y));
        }
    }

    /** One classified, deduplicated meeting. {@code aMgr}/{@code bMgr} follow roster order, not manager order. */
    private record Game(long leagueId, int season, int week, int aRoster, int bRoster, Long aMgr, Long bMgr,
                        double aPts, double bPts, Classification c) {}

    private static final Comparator<StandingRow> STANDINGS_ORDER = Comparator
            .comparing((StandingRow s) -> s.finalPlacement() == null)
            .thenComparing(s -> s.finalPlacement() == null ? 0 : s.finalPlacement())
            .thenComparing(s -> -(s.wins() == null ? Integer.MIN_VALUE / 2 : s.wins()))
            .thenComparing(s -> -(s.pointsFor() == null ? -1e18 : s.pointsFor()))
            .thenComparing(s -> s.managerId() == null ? Long.MAX_VALUE : s.managerId());

    /** A pair's wire record plus the extras the reasons need. */
    private record PairCalc(PairRecord record, PairStats stats, List<Game> games, int playoffAWins, int playoffBWins,
                            List<int[]> placementGames, Integer scheduledWeek, String streakSide, int streakLength) {}

    private PairCalc pairCalc(PairKey key, List<Game> games, Integer scheduledWeek,
                              Map<Long, Set<Long>> managersOfScoredLeague, Map<Long, PlayoffFormat> formats) {
        int aw = 0, bw = 0, ties = 0;
        EnumMap<Kind, int[]> by = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) by.put(k, new int[3]);
        List<GameLine> lines = new ArrayList<>();
        // Bracket match -> legs, keyed by (league, match id); LinkedHashMap keeps chronological order.
        Map<String, List<Game>> legsOfMatch = new LinkedHashMap<>();
        for (Game g : games) {
            boolean loIsA = g.aMgr() == key.lo();
            double aPts = loIsA ? g.aPts() : g.bPts();
            double bPts = loIsA ? g.bPts() : g.aPts();
            String out = HeadToHeadService.outcome(aPts, bPts);
            int idx = switch (out) {
                case "A" -> { aw++; yield 0; }
                case "B" -> { bw++; yield 1; }
                default -> { ties++; yield 2; }
            };
            by.get(g.c().kind())[idx]++;
            lines.add(new GameLine(g.season(), g.week(), round2(aPts), round2(bPts), out, g.c().kind().name()));
            if (g.c().match() != null) {
                legsOfMatch.computeIfAbsent(g.leagueId() + ":" + g.c().match().matchId(), k -> new ArrayList<>()).add(g);
            }
        }
        List<Final> finals = new ArrayList<>();
        int playoffMeetings = 0, playoffAWins = 0, playoffBWins = 0;
        List<int[]> placementGames = new ArrayList<>();   // {season, placement}
        for (List<Game> legs : legsOfMatch.values()) {
            Game first = legs.get(0);
            var match = first.c().match();
            boolean loIsA = first.aMgr() == key.lo();
            int loRoster = loIsA ? first.aRoster() : first.bRoster();
            int hiRoster = loIsA ? first.bRoster() : first.aRoster();
            String winner = null;
            if (match.winnerRosterId() != null) {
                if (match.winnerRosterId() == loRoster) winner = "A";
                else if (match.winnerRosterId() == hiRoster) winner = "B";
            }
            if (first.c().kind() == Kind.PLACEMENT) {
                placementGames.add(new int[]{first.season(), match.placement()});
                continue;
            }
            playoffMeetings++;
            if ("A".equals(winner)) playoffAWins++;
            if ("B".equals(winner)) playoffBWins++;
            if (match.placement() != null && match.placement() == 1) {
                double a = 0, b = 0;
                for (Game g : legs) {
                    boolean lo = g.aMgr() == key.lo();
                    a += lo ? g.aPts() : g.bPts();
                    b += lo ? g.bPts() : g.aPts();
                }
                Boolean agrees = winner == null ? null : ("A".equals(winner) ? a > b : b > a);
                finals.add(new Final(first.season(), round2(a), round2(b), winner, legs.size() > 1, agrees));
            }
        }

        // Streak: consecutive most-recent wins by one side; a tie ends it (F16).
        String streakSide = null;
        int streak = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String w = lines.get(i).winner();
            if ("TIE".equals(w)) break;
            if (streakSide == null) streakSide = w;
            if (!streakSide.equals(w)) break;
            streak++;
        }

        int m = games.size();
        String status;
        if (m >= 1) status = "MET";
        else if (scheduledWeek != null) status = "NOT_YET_MET";
        else if (sharedScoredSeason(key, managersOfScoredLeague)) status = "NEVER_MET";
        else status = "NO_SHARED_HISTORY";

        Map<String, KindTally> byKind = new LinkedHashMap<>();
        for (Kind k : Kind.values()) byKind.put(k.name(), new KindTally(by.get(k)[0], by.get(k)[1], by.get(k)[2]));
        PairRecord rec = new PairRecord(key.lo(), key.hi(), m, aw, bw, ties, byKind, status, finals, lines);
        PairStats stats = new PairStats(m, aw, bw, ties, playoffMeetings, !finals.isEmpty());
        return new PairCalc(rec, stats, games, playoffAWins, playoffBWins, placementGames, scheduledWeek,
                streakSide, streak);
    }

    private static boolean sharedScoredSeason(PairKey key, Map<Long, Set<Long>> managersOfScoredLeague) {
        for (Set<Long> owners : managersOfScoredLeague.values()) {
            if (owners.contains(key.lo()) && owners.contains(key.hi())) return true;
        }
        return false;
    }

    // ---- split notes

    private static final List<String> UNSPLIT_PRECEDENCE = List.of(
            "NO_BRACKET", "ROUND_TYPE_UNSUPPORTED", "ROUND_TYPE_UNKNOWN", "BRACKET_PENDING", "ROUND_UNKNOWN");

    private static List<SplitNote> splitNotes(List<Game> games) {
        Map<Integer, Set<String>> reasonsBySeason = new TreeMap<>(Comparator.reverseOrder());
        for (Game g : games) {
            if (g.c().kind() != Kind.POSTSEASON_UNSPLIT) continue;
            reasonsBySeason.computeIfAbsent(g.season(), k -> new HashSet<>()).add(g.c().unsplitReason());
        }
        List<SplitNote> out = new ArrayList<>();
        for (Map.Entry<Integer, Set<String>> e : reasonsBySeason.entrySet()) {
            String reason = UNSPLIT_PRECEDENCE.stream().filter(e.getValue()::contains).findFirst().orElse("NO_BRACKET");
            out.add(new SplitNote(e.getKey(), reason, splitText(e.getKey(), reason)));
        }
        return out;
    }

    static String splitText(int season, String reason) {
        String lead = "The " + season + " playoff weeks can't be split into playoff and consolation games";
        String tail = "; those games are counted in the totals as unsplit.";
        return switch (reason) {
            case "NO_BRACKET" -> lead + " because that season's bracket isn't stored" + tail;
            case "BRACKET_PENDING" -> lead + " yet because the bracket for that round isn't settled" + tail;
            case "ROUND_TYPE_UNSUPPORTED", "ROUND_TYPE_UNKNOWN" ->
                    lead + " because this league's playoff round format isn't one the app maps to weeks" + tail;
            default -> lead + " because those weeks don't map to a bracket round" + tail;
        };
    }

    // ---- season grid

    private SeasonGrid seasonGrid(LeagueRow league, PlayoffFormat fmt, List<LeagueBracketRepository.Match> bracket,
                                  List<Long> managerIds, Map<Long, Integer> rosterOfManager,
                                  Map<Integer, Long> managerOfRoster, List<ScheduledPair> scheduled,
                                  List<Game> games, Map<PairKey, List<Game>> byPair, String rosterNote) {
        var leg = leagues.currentLeg(league.id());
        Integer currentWeek = leg.isPresent() ? leg.getAsInt() : null;
        String median = fmt != null && fmt.medianMatch() ? MEDIAN_NOTE : null;
        if (scheduled.isEmpty()) {
            return new SeasonGrid("NOT_PUBLISHED", currentWeek, List.of(), null, null, median, rosterNote, List.of());
        }

        // roster-level lookups for this league-season
        Map<Integer, Map<Integer, Integer>> oppByWeek = new HashMap<>();   // week -> roster -> opponent roster
        int maxStored = 0;
        for (ScheduledPair p : scheduled) {
            maxStored = Math.max(maxStored, p.week());
            Map<Integer, Integer> w = oppByWeek.computeIfAbsent(p.week(), k -> new HashMap<>());
            w.put(p.aRosterId(), p.bRosterId());
            w.put(p.bRosterId(), p.aRosterId());
        }
        int last = maxStored;
        String windowNote = null;
        if (fmt != null && fmt.playoffWeekStart() >= 2) {
            var end = fmt.lastPlayoffWeek();
            if (end.isPresent()) {
                last = Math.max(last, end.getAsInt());
            } else if (fmt.playoffWindowRefusal().filter(r -> r.startsWith("ROUND_TYPE")).isPresent()) {
                windowNote = "Only playoff weeks that already have pairings are shown: this league's playoff round"
                        + " format doesn't tell the app how many playoff weeks there will be.";
            }
        }
        List<WeekColumn> weeks = new ArrayList<>();
        for (int w = 1; w <= last; w++) weeks.add(new WeekColumn(w, RivalryGameKind.isPlayoffWeek(fmt, w)));

        Map<String, Game> scoredByWeekPair = new HashMap<>();
        for (Game g : games) {
            if (g.leagueId() != league.id()) continue;
            scoredByWeekPair.put(g.week() + ":" + Math.min(g.aRoster(), g.bRoster()) + ":" + Math.max(g.aRoster(), g.bRoster()), g);
        }

        boolean playoffsBegun = fmt != null && currentWeek != null && fmt.playoffWeekStart() >= 2
                && currentWeek >= fmt.playoffWeekStart();
        boolean anyTbd = false;
        List<Cell> cells = new ArrayList<>();
        for (Long mid : managerIds) {
            int roster = rosterOfManager.get(mid);
            for (WeekColumn col : weeks) {
                int w = col.week();
                Integer oppRoster = oppByWeek.getOrDefault(w, Map.of()).get(roster);
                boolean stored = oppByWeek.containsKey(w);
                if (oppRoster != null) {
                    cells.add(opponentCell(league, mid, w, oppRoster, "SCHEDULE", managerOfRoster, scoredByWeekPair,
                            roster, byPair));
                } else if (!col.isPlayoffWeek()) {
                    if (stored) {
                        cells.add(new Cell(mid, w, "BYE", null, null, null, null));
                    } else {
                        cells.add(new Cell(mid, w, "TBD", null, null, null, null));
                        anyTbd = true;
                    }
                } else if (stored) {
                    // F8: in a playoff week a roster without a game is usually out, not on a bye.
                    boolean bye = RivalryGameKind.bracketBye(fmt, bracket, w, roster);
                    cells.add(new Cell(mid, w, bye ? "BYE" : "NO_GAME", null, null, null, null));
                } else {
                    // F7: the week being played has no stored pairing until it is scored. A bracket row of this
                    // round with both slots set names the opponent, but only once the playoffs have begun: before
                    // that Sleeper's bracket is a provisional seeding that moves with the standings (F2).
                    Optional<Integer> bracketOpp = playoffsBegun
                            ? RivalryGameKind.bracketOpponent(fmt, bracket, w, roster) : Optional.empty();
                    if (bracketOpp.isPresent()) {
                        cells.add(opponentCell(league, mid, w, bracketOpp.get(), "BRACKET", managerOfRoster,
                                scoredByWeekPair, roster, byPair));
                    } else if (playoffsBegun && RivalryGameKind.bracketBye(fmt, bracket, w, roster)) {
                        cells.add(new Cell(mid, w, "BYE", null, null, null, null));
                    } else {
                        cells.add(new Cell(mid, w, "TBD", null, null, null, null));
                        anyTbd = true;
                    }
                }
            }
        }
        return new SeasonGrid("OK", currentWeek, weeks, windowNote, anyTbd ? TBD_NOTE : null, median, rosterNote, cells);
    }

    private Cell opponentCell(LeagueRow league, long mid, int week, int oppRoster, String source,
                              Map<Integer, Long> managerOfRoster, Map<String, Game> scoredByWeekPair, int myRoster,
                              Map<PairKey, List<Game>> byPair) {
        Long oppMgr = managerOfRoster.get(oppRoster);
        Tally prior = null;
        if (oppMgr != null && oppMgr != mid) prior = priorRecord(mid, oppMgr, league.season(), week, byPair);
        Game g = scoredByWeekPair.get(week + ":" + Math.min(myRoster, oppRoster) + ":" + Math.max(myRoster, oppRoster));
        CellResult result = null;
        if (g != null) {
            boolean mineIsA = g.aRoster() == myRoster;
            double mine = mineIsA ? g.aPts() : g.bPts();
            double theirs = mineIsA ? g.bPts() : g.aPts();
            String out = switch (HeadToHeadService.outcome(mine, theirs)) {
                case "A" -> "W";
                case "B" -> "L";
                default -> "T";
            };
            result = new CellResult(round2(mine), round2(theirs), out, g.c().kind().name(), finalDisagrees(g, byPair));
        }
        return new Cell(mid, week, "OPPONENT", source, oppMgr, prior, result);
    }

    /** B2: whether {@code g} is a final whose summed stored scores contradict the bracket's winner. */
    private static boolean finalDisagrees(Game g, Map<PairKey, List<Game>> byPair) {
        var match = g.c().match();
        if (match == null || match.placement() == null || match.placement() != 1 || match.winnerRosterId() == null
                || g.aMgr() == null || g.bMgr() == null) return false;
        double a = 0, b = 0;   // by roster a/b of this game
        for (Game x : byPair.getOrDefault(PairKey.of(g.aMgr(), g.bMgr()), List.of())) {
            if (x.leagueId() != g.leagueId() || x.c().match() == null || x.c().match().matchId() != match.matchId()) continue;
            boolean same = x.aRoster() == g.aRoster();
            a += same ? x.aPts() : x.bPts();
            b += same ? x.bPts() : x.aPts();
        }
        int winnerRoster = match.winnerRosterId();
        double winnerPts = winnerRoster == g.aRoster() ? a : b;
        double loserPts = winnerRoster == g.aRoster() ? b : a;
        return winnerPts <= loserPts;
    }

    /** The record of {@code mid} against {@code opp} counting only meetings strictly before (season, week). */
    private static Tally priorRecord(long mid, long opp, int season, int week, Map<PairKey, List<Game>> byPair) {
        int w = 0, l = 0, t = 0;
        for (Game g : byPair.getOrDefault(PairKey.of(mid, opp), List.of())) {
            if (g.season() > season || (g.season() == season && g.week() >= week)) continue;
            boolean mineIsA = g.aMgr() == mid;
            switch (HeadToHeadService.outcome(mineIsA ? g.aPts() : g.bPts(), mineIsA ? g.bPts() : g.aPts())) {
                case "A" -> w++;
                case "B" -> l++;
                default -> t++;
            }
        }
        return new Tally(w, l, t, w + l + t);
    }

    // ---- recommendation

    private Recommendation recommendation(List<LeagueRow> chain, List<Long> managerIds, Map<Long, String> nameOf,
                                          Map<PairKey, PairCalc> calc) {
        if (!props.loaded()) return refusal("CONFIG_MISSING", null);
        int n = managerIds.size();
        int minMeetings = props.minMeetings();
        if (n < 2) return refusal("TOO_FEW_MANAGERS", minMeetings);
        if (n > props.maxManagers()) return refusal("TOO_MANY_MANAGERS", minMeetings);
        // F10: the spec's version holds. A league with no earlier season has a schedule, not a rivalry.
        if (chain.size() < 2) return refusal("NO_HISTORY", minMeetings);

        double playoffBonus = props.playoffBonus();
        double titleBonus = props.championshipBonus();
        RivalryMatching.PairScore fn = (a, b) ->
                RivalryMatching.score(calc.get(new PairKey(a, b)).stats(), playoffBonus, titleBonus);
        RivalryMatching.Matching matching = RivalryMatching.match(managerIds, fn, props.maxManagers());

        int maxMeetings = 0;
        for (PairCalc pc : calc.values()) maxMeetings = Math.max(maxMeetings, pc.stats().meetings());
        int atMax = 0;
        for (PairCalc pc : calc.values()) if (pc.stats().meetings() == maxMeetings) atMax++;
        // F16: only a UNIQUE maximum earns the reason, and a maximum of one meeting isn't "the most" of anything.
        boolean mostIsUnique = maxMeetings >= 2 && atMax == 1;

        List<Pairing> pairings = new ArrayList<>();
        int thin = 0;
        for (RivalryMatching.Pair p : matching.pairs()) {
            PairCalc pc = calc.get(new PairKey(p.a(), p.b()));
            boolean isThin = pc.stats().meetings() < minMeetings;
            if (isThin) thin++;
            pairings.add(new Pairing(p.a(), p.b(), pc.stats().meetings(), round3(p.score()),
                    reason(pc, nameOf, minMeetings, maxMeetings, mostIsUnique), isThin));
        }
        pairings.sort(Comparator.comparingDouble((Pairing p) -> -p.score()).thenComparingLong(Pairing::aManagerId));

        Unpaired unpaired = null;
        if (matching.unpaired() != null) {
            String name = nameOf.get(matching.unpaired());
            unpaired = new Unpaired(matching.unpaired(), "Leaving " + name + " out gives the highest total; " + name
                    + "'s best available pairing would score " + num3(matching.unpairedBestScore()) + ".");
        }
        return new Recommendation("OK", minMeetings, MINIMUM_LABEL, pairings, unpaired, thin, RECOMMENDATION_NOTE);
    }

    private Recommendation refusal(String status, Integer minMeetings) {
        return new Recommendation(status, minMeetings, minMeetings == null ? null : MINIMUM_LABEL, List.of(), null, 0,
                RECOMMENDATION_NOTE);
    }

    /**
     * One primary reason by priority (research R7 with plan.md F16): the engine builds the code and the
     * numbers, and the sentence prints those same numbers.
     */
    private Reason reason(PairCalc pc, Map<Long, String> nameOf, int minMeetings, int maxMeetings, boolean mostIsUnique) {
        PairRecord r = pc.record();
        String a = nameOf.get(r.aManagerId());
        String b = nameOf.get(r.bManagerId());
        int m = r.meetings();
        Map<String, Object> nums = new LinkedHashMap<>();
        nums.put("meetings", m);
        nums.put("aWins", r.aWins());
        nums.put("bWins", r.bWins());
        nums.put("ties", r.ties());
        String code;
        String text;
        if (!r.finals().isEmpty()) {
            Final f = r.finals().get(r.finals().size() - 1);   // the most recent final
            nums.put("finalSeason", f.season());
            nums.put("finalAPoints", f.aPoints());
            nums.put("finalBPoints", f.bPoints());
            nums.put("twoWeekFinal", f.twoWeek());
            code = "CHAMPIONSHIP_REMATCH";
            String label = f.twoWeek() ? "two-week final" : "final";
            if (f.winner() != null) {
                boolean aWon = "A".equals(f.winner());
                nums.put("finalWinnerManagerId", aWon ? r.aManagerId() : r.bManagerId());
                double winnerPts = aWon ? f.aPoints() : f.bPoints();
                double loserPts = aWon ? f.bPoints() : f.aPoints();
                String winnerName = aWon ? a : b;
                String loserName = aWon ? b : a;
                boolean agree = winnerPts > loserPts;
                nums.put("scoresAgreeWithWinner", agree);
                if (agree) {
                    text = "Met in the " + f.season() + " " + label + ": " + winnerName + " beat " + loserName + " "
                            + pts(winnerPts) + " to " + pts(loserPts) + (f.twoWeek() ? " over the two weeks." : ".");
                } else {
                    // The bracket's winner is Sleeper's word (F3), but measured on NBA 2024 and 2025 the stored
                    // weekly scores can read the other way. Say both rather than print a "beat" that is false.
                    text = "Met in the " + f.season() + " " + label + ", which Sleeper's bracket gave to " + winnerName
                            + "; the stored scores read " + winnerName + " " + pts(winnerPts) + ", " + loserName + " "
                            + pts(loserPts) + (f.twoWeek() ? " over the two weeks." : ".");
                }
            } else {
                text = "Meeting in the " + f.season() + " " + label + ", not decided yet: " + a + " " + pts(f.aPoints())
                        + ", " + b + " " + pts(f.bPoints()) + " so far.";
            }
        } else if (pc.stats().playoffMeetings() >= 1) {
            int k = pc.stats().playoffMeetings();
            nums.put("playoffMeetings", k);
            nums.put("playoffAWins", pc.playoffAWins());
            nums.put("playoffBWins", pc.playoffBWins());
            code = "PLAYOFF_HISTORY";
            int undecided = k - pc.playoffAWins() - pc.playoffBWins();
            nums.put("playoffUndecided", undecided);
            text = "Have met " + (k == 1 ? "once" : k + " times") + " in the playoffs: " + a + " won " + pc.playoffAWins()
                    + ", " + b + " won " + pc.playoffBWins() + (undecided > 0 ? ", " + undecided + " undecided" : "") + ".";
        } else if (m >= minMeetings && Math.abs(r.aWins() - r.bWins()) <= 1) {
            code = "EVEN";
            // EVEN is the |w-l| <= 1 rule; the sentence says "even" only when the wins really are level.
            text = (r.aWins() == r.bWins() ? "An even series after " : "Separated by one game after ")
                    + m + " meetings: " + record(a, b, r) + ".";
        } else if (mostIsUnique && m == maxMeetings) {
            code = "MOST_MEETINGS";
            text = "The most meetings of any two current managers: " + m + " (" + record(a, b, r) + ").";
        } else if (pc.streakLength() >= 3) {
            boolean aStreak = "A".equals(pc.streakSide());
            nums.put("streakLength", pc.streakLength());
            nums.put("streakManagerId", aStreak ? r.aManagerId() : r.bManagerId());
            code = "STREAK";
            text = (aStreak ? a : b) + " has won the last " + pc.streakLength() + " meetings.";
        } else if (m >= minMeetings) {
            boolean aLeads = r.aWins() > r.bWins();
            code = "LOPSIDED";
            text = "One-sided so far: " + (aLeads ? a : b) + " leads " + (aLeads ? r.aWins() : r.bWins()) + " to "
                    + (aLeads ? r.bWins() : r.aWins()) + (r.ties() > 0 ? " with " + r.ties() + " tied" : "") + " after "
                    + m + " meetings.";
        } else if (m >= 1) {
            code = "THIN";
            if (m == 1) {
                GameLine g = r.games().get(0);
                boolean aWon = "A".equals(g.winner());
                text = g.winner().equals("TIE")
                        ? "Met once and tied " + pts(g.aPoints()) + " to " + pts(g.bPoints()) + "."
                        : "Met once: " + (aWon ? a : b) + " won " + pts(aWon ? g.aPoints() : g.bPoints()) + " to "
                                + pts(aWon ? g.bPoints() : g.aPoints()) + ".";
            } else {
                text = "Only met " + m + " times (" + record(a, b, r) + "), so the history is thin.";
            }
        } else {
            switch (r.status()) {
                case "NOT_YET_MET" -> {
                    code = "NOT_YET_MET";
                    nums.put("scheduledWeek", pc.scheduledWeek());
                    text = "Haven't played each other yet; the schedule has them meeting in week " + pc.scheduledWeek() + ".";
                }
                case "NO_SHARED_HISTORY" -> {
                    code = "NO_SHARED_HISTORY";
                    text = "No season in common with a scored game yet.";
                }
                default -> {
                    code = "NEVER_MET";
                    text = "Never played each other in this league.";
                }
            }
        }
        if (!pc.placementGames().isEmpty()) {
            nums.put("placementGames", pc.placementGames().size());
            List<String> bits = new ArrayList<>();
            for (int[] pg : pc.placementGames()) bits.add("the " + ordinal(pg[1]) + "-place game in " + pg[0]);
            text += " They also met in " + String.join(" and ", bits) + ".";
        }
        return new Reason(code, text, nums);
    }

    private static String record(String a, String b, PairRecord r) {
        return a + " " + r.aWins() + ", " + b + " " + r.bWins()
                + (r.ties() > 0 ? ", " + r.ties() + (r.ties() == 1 ? " tie" : " ties") : "");
    }

    private static String ordinal(int n) {
        return switch (n) {
            case 1 -> "1st";
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> n + "th";
        };
    }

    private static String pts(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private static String num3(double d) {
        BigDecimal b = BigDecimal.valueOf(round3(d)).stripTrailingZeros();
        return b.scale() < 0 ? b.setScale(0).toPlainString() : b.toPlainString();
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    private static double round3(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }
}
