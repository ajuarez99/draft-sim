package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.engine.GameScoringService;
import com.ballknowers.draftsim.engine.RealizedLineupService;
import com.ballknowers.draftsim.engine.RealizedLineupService.ScoredPlayer;
import com.ballknowers.draftsim.engine.RealizedLineupService.WeekLineup;
import com.ballknowers.draftsim.historical.HistoricalResult.FinalPlace;
import com.ballknowers.draftsim.historical.HistoricalResult.LineupWeek;
import com.ballknowers.draftsim.historical.HistoricalResult.Matchup;
import com.ballknowers.draftsim.historical.HistoricalResult.PlayoffMatchup;
import com.ballknowers.draftsim.historical.HistoricalResult.Playoffs;
import com.ballknowers.draftsim.historical.HistoricalResult.Round;
import com.ballknowers.draftsim.historical.HistoricalResult.Seed;
import com.ballknowers.draftsim.historical.HistoricalResult.Standing;
import com.ballknowers.draftsim.historical.HistoricalResult.Starter;
import com.ballknowers.draftsim.historical.HistoricalResult.Week;
import com.ballknowers.draftsim.sport.SportRules;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Replays one real season for a finished historical draft (spec 053), as lock-in best ball: every rostered
 * player's best SINGLE game of each week counts, the best lineup is seated for each team automatically, and the
 * teams meet in a seeded round robin. From spec 063 the round robin covers the regular season only and the top six
 * teams then play a three-week bracket (see {@link SeasonFormat}). Pure given its input (no database), so the whole result is a function of
 * {@link Input}: the same seed gives byte-identical JSON.
 *
 * <p>Scoring is {@link GameScoringService#score} over the game's frozen scoring snapshot; the lineup is
 * {@link RealizedLineupService#bestLineup(List, LeagueSettings, SportRules)}. Neither is reimplemented here.
 */
@Component
public class SeasonReplay {

    private final GameScoringService scorer;
    private final RealizedLineupService lineups;
    private final Comparisons comparisons;

    public SeasonReplay(GameScoringService scorer, RealizedLineupService lineups) {
        this.scorer = scorer;
        this.lineups = lineups;
        this.comparisons = new Comparisons(lineups);
    }

    /** One draft pick: who took whom, where. */
    public record Pick(int pickNo, int slot, long playerId) {}

    /**
     * @param settings   the game's league settings (carries the stored reversal round)
     * @param regularSeasonWeeks the configured regular-season length (a league-format choice); the replay derives the
     *                   actual {@link SeasonFormat} from it and {@code weekCount}, so this function stays pure
     * @param scoring    the era-filtered scoring snapshot, in the order its JSON was stored
     * @param weekStarts first game date seen in each week, from the reference data
     * @param cards      the board snapshot by player id
     * @param games      the season's played games for every board player (any sport's {@link WeekStatLine})
     */
    public record Input(LeagueSettings settings, SportRules rules, Map<String, Double> scoring, int weekCount,
                        int regularSeasonWeeks, long seed, int userSlot, List<Pick> picks, Map<Long, HistoricalPlayerCard> cards,
                        List<? extends WeekStatLine> games, Map<Integer, LocalDate> weekStarts) {}

    /** The single game locked for a player in a week. {@code date} is null for football (no dates in the source). */
    public record Locked(double points, LocalDate date) {}

    /** Player-week scores: {@code playerId -> week -> locked game}. A week without a game has no entry. */
    public static final class PlayerWeeks {
        private final Map<Long, Map<Integer, Locked>> byPlayer = new HashMap<>();

        void put(long id, int week, Locked l) {
            byPlayer.computeIfAbsent(id, k -> new HashMap<>()).put(week, l);
        }

        /** The locked game, or null when the player had no game that week. */
        public Locked get(long id, int week) {
            Map<Integer, Locked> m = byPlayer.get(id);
            return m == null ? null : m.get(week);
        }

        /** Sum of his weekly scores: the season value used by the comparisons. */
        public double seasonValue(long id) {
            double t = 0;
            Map<Integer, Locked> m = byPlayer.get(id);
            if (m != null) for (Locked l : m.values()) t += l.points();
            return Math.round(t * 100.0) / 100.0;
        }

        /** Sum of his positive weekly scores: an upper bound on what he can add to any roster. */
        public double positiveValue(long id) {
            double t = 0;
            Map<Integer, Locked> m = byPlayer.get(id);
            if (m != null) for (Locked l : m.values()) if (l.points() > 0) t += l.points();
            return t;
        }
    }

    // ---- player-week scoring ----------------------------------------------------------------------------

    /**
     * Player-week score = the MAX over that week's games of the game's score under {@code scoring} (lock-in).
     *
     * <p>A key the season counts but a box score did not record is 0 (FR-007); a key the season does not count
     * is not in {@code scoring}, so it can never contribute. An all-negative week keeps its negative maximum, as
     * in real lock-in. On equal scores the earlier game is the locked one ({@code games} arrive ordered by
     * week, then date).
     */
    public PlayerWeeks scorePlayerWeeks(Map<String, Double> scoring, List<? extends WeekStatLine> games) {
        PlayerWeeks pw = new PlayerWeeks();
        for (WeekStatLine g : games) {
            double pts = scorer.score(scoring, statMap(g, scoring.keySet()));
            Locked cur = pw.get(g.playerId(), g.week());
            if (cur == null || pts > cur.points()) pw.put(g.playerId(), g.week(), new Locked(pts, g.gameDate()));
        }
        return pw;
    }

    /** The counted keys of one game as numbers; unrecorded counted keys are 0 (FR-007). Mutable on purpose. */
    static Map<String, Object> statMap(WeekStatLine g, Collection<String> keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) {
            Double v = g.stat(k);   // the per-sport switch lives on the line (spec 056 T005)
            m.put(k, v == null ? 0.0 : v);
        }
        return m;
    }

    // ---- one team's weeks -------------------------------------------------------------------------------

    /** Seats a roster's best lineup for any week, from the player-week scores. Shared with the comparisons. */
    public static final class TeamScorer {
        private final RealizedLineupService lineups;
        private final LeagueSettings settings;
        private final SportRules rules;
        private final Map<Long, Player> players;
        private final PlayerWeeks pw;

        public TeamScorer(RealizedLineupService lineups, LeagueSettings settings, SportRules rules,
                          Map<Long, Player> players, PlayerWeeks pw) {
            this.lineups = lineups;
            this.settings = settings;
            this.rules = rules;
            this.players = players;
            this.pw = pw;
        }

        /** The best lineup for one team-week, points rounded to two decimals. */
        public WeekLineup week(Collection<Long> roster, int week) {
            List<ScoredPlayer> scored = new ArrayList<>();
            for (long id : roster) {
                Locked l = pw.get(id, week);
                // A player with no game this week is simply not passed. And if NOBODY on the roster played,
                // the team scored 0: an empty roster-week is zero points, NOT "absent" (the JSON path of
                // bestLineup treats an empty blob as absent; that is the wrong reading here, plan F20).
                if (l != null) scored.add(new ScoredPlayer(players.get(id), l.points()));
            }
            WeekLineup w = lineups.bestLineup(scored, settings, rules);
            return new WeekLineup(true, Math.round(w.points() * 100.0) / 100.0, w.lineup(), null);
        }

        /** A roster's season total: the sum of its weekly best lineups. */
        public double season(Collection<Long> roster, int weekCount) {
            double t = 0;
            for (int w = 1; w <= weekCount; w++) t += week(roster, w).points();
            return Math.round(t * 100.0) / 100.0;
        }

        public PlayerWeeks scores() {
            return pw;
        }
    }

    // ---- the whole replay -------------------------------------------------------------------------------

    public HistoricalResult replay(Input in) {
        int teams = in.settings().teams();
        if (teams % 2 != 0) throw new IllegalArgumentException("a round robin needs an even team count: " + teams);
        if (in.regularSeasonWeeks() <= 0) {
            throw new IllegalArgumentException("regularSeasonWeeks must be positive: " + in.regularSeasonWeeks());
        }
        SeasonFormat fmt = SeasonFormat.of(in.weekCount(), in.regularSeasonWeeks(), teams);
        int regularWeeks = fmt.regularSeasonWeeks();

        Map<Long, Player> players = new HashMap<>();
        for (HistoricalPlayerCard c : in.cards().values()) {
            players.put(c.id(), HistoricalBoardBuilder.toEntry(c, in.settings().sport()).player());
        }
        PlayerWeeks pw = scorePlayerWeeks(in.scoring(), in.games());
        TeamScorer team = new TeamScorer(lineups, in.settings(), in.rules(), players, pw);

        Map<Integer, List<Long>> rosters = new HashMap<>();
        for (int s = 1; s <= teams; s++) rosters.put(s, new ArrayList<>());
        for (Pick p : in.picks()) rosters.get(p.slot()).add(p.playerId());

        // Weekly points through the last week that decides anything (the final, or the regular season's end when
        // there are no playoffs); weeks after it are not scored for standings or the bracket. The user's regular
        // season lineups are stored as they are built; playoff-week lineups are added below for weeks the user played.
        int lastPlayed = fmt.lastPlayedWeek();
        double[][] points = new double[in.weekCount() + 1][teams + 1];
        List<LineupWeek> userLineups = new ArrayList<>();
        for (int w = 1; w <= lastPlayed; w++) {
            for (int s = 1; s <= teams; s++) {
                WeekLineup wl = team.week(rosters.get(s), w);
                points[w][s] = wl.points();
                if (s == in.userSlot() && w <= regularWeeks) userLineups.add(lineupWeek(w, wl, pw));
            }
        }

        // Seeded circle-method round robin: slot order shuffled once, then team 0 fixed and the rest rotate.
        List<Integer> order = new ArrayList<>();
        for (int s = 1; s <= teams; s++) order.add(s);
        Collections.shuffle(order, new Random(in.seed()));

        int[] wins = new int[teams + 1], losses = new int[teams + 1], ties = new int[teams + 1];
        double[] pf = new double[teams + 1];
        List<Week> weeks = new ArrayList<>();
        for (int w = 1; w <= regularWeeks; w++) {
            List<Matchup> matchups = new ArrayList<>();
            for (int[] pair : pairings(order, (w - 1) % (teams - 1), w)) {
                int home = pair[0], away = pair[1];
                double hp = points[w][home], ap = points[w][away];
                matchups.add(new Matchup(home, away, hp, ap));
                long h = Math.round(hp * 100), a = Math.round(ap * 100);
                if (h > a) { wins[home]++; losses[away]++; }
                else if (h < a) { wins[away]++; losses[home]++; }
                else { ties[home]++; ties[away]++; }
            }
            for (int s = 1; s <= teams; s++) pf[s] += points[w][s];
            weeks.add(new Week(w, monday(in.weekStarts().get(w)), matchups, PHASE_REGULAR));
        }

        // W-L-T, then points for, then a seeded coin (F16). Regular season only.
        Random coinRng = new Random(in.seed() ^ 0x5DEECE66DL);
        double[] coin = new double[teams + 1];
        for (int s = 1; s <= teams; s++) coin[s] = coinRng.nextDouble();
        List<Integer> bySlot = new ArrayList<>(order);
        bySlot.sort(Comparator
                .comparingDouble((Integer s) -> wins[s] + 0.5 * ties[s]).reversed()
                .thenComparing(Comparator.comparingLong((Integer s) -> Math.round(pf[s] * 100)).reversed())
                .thenComparing(Comparator.comparingDouble((Integer s) -> coin[s]).reversed()));
        List<Standing> standings = new ArrayList<>();
        int userRank = 0;
        for (int i = 0; i < bySlot.size(); i++) {
            int s = bySlot.get(i);
            standings.add(new Standing(s, wins[s], losses[s], ties[s], Math.round(pf[s] * 100) / 100.0, i + 1));
            if (s == in.userSlot()) userRank = i + 1;
        }

        Playoffs playoffs = null;
        List<FinalPlace> finalPlaces = new ArrayList<>();
        int userFinish = userRank;
        String userOutcome;
        if (fmt.hasPlayoffs()) {
            Bracket b = bracket(bySlot, fmt.playoffWeeks(), points);
            playoffs = b.playoffs();
            finalPlaces = b.finalPlaces();
            for (Round r : playoffs.rounds()) {
                List<Matchup> matchups = new ArrayList<>();
                boolean userPlays = false;
                for (PlayoffMatchup m : r.matchups()) {
                    matchups.add(new Matchup(m.highSlot(), m.lowSlot(), m.highPoints(), m.lowPoints()));
                    if (m.highSlot() == in.userSlot() || m.lowSlot() == in.userSlot()) userPlays = true;
                }
                weeks.add(new Week(r.week(), monday(in.weekStarts().get(r.week())), matchups, PHASE_PLAYOFF));
                if (userPlays) {
                    userLineups.add(lineupWeek(r.week(), team.week(rosters.get(in.userSlot()), r.week()), pw));
                }
            }
            FinalPlace mine = finalPlaces.stream().filter(f -> f.slot() == in.userSlot()).findFirst().orElseThrow();
            userFinish = mine.place();
            userOutcome = mine.outcome();
        } else {
            for (int i = 0; i < bySlot.size(); i++) {
                finalPlaces.add(new FinalPlace(bySlot.get(i), i + 1, HistoricalResult.NO_PLAYOFFS));
            }
            userOutcome = HistoricalResult.NO_PLAYOFFS;
        }

        // The comparisons keep the WHOLE real season (spec 063 PR-13): the user's total is every season week, not the
        // regular-season points for in the standings. Passing pf here would shrink "You" against a whole-season
        // hindsight draft.
        Comparisons.Computed cmp = comparisons.compute(in, team,
                team.season(rosters.get(in.userSlot()), in.weekCount()));

        Map<String, List<LineupWeek>> lineupMap = new LinkedHashMap<>();
        lineupMap.put(String.valueOf(in.userSlot()), userLineups);
        return new HistoricalResult(weeks, standings, userFinish, lineupMap, cmp.hindsight(), cmp.bust(),
                cmp.steal(), fmt.toResult(), playoffs, finalPlaces, userOutcome);
    }

    public static final String PHASE_REGULAR = "REGULAR";
    public static final String PHASE_PLAYOFF = "PLAYOFF";

    private static LineupWeek lineupWeek(int week, WeekLineup wl, PlayerWeeks pw) {
        List<Starter> starters = new ArrayList<>();
        for (SportRules.Assigned a : wl.lineup()) {
            long id = a.entry().player().id();
            Locked l = pw.get(id, week);
            // Football lines have no game date (F4): a null date is "no date", never a crash. A starter
            // always has a game (review N2): the lineup is built only from players passed in `scored`,
            // i.e. those with a Locked entry, so l is never null here and a null date therefore means
            // "played, but the source carries no date". The web shows "Week N" for that; a 0-point
            // starter played and scored 0. The l == null arm is defensive only.
            starters.add(new Starter(id, a.slot(), a.value(),
                    l == null || l.date() == null ? null : l.date().toString()));
        }
        return new LineupWeek(week, starters);
    }

    // ---- the bracket (spec 063) -------------------------------------------------------------------------

    private record Bracket(Playoffs playoffs, List<FinalPlace> finalPlaces) {}

    /**
     * Six seeds: round one 3v6 and 4v5 (seeds 1 and 2 have byes); round two reseeds, seed 1 against the
     * highest-numbered survivor and seed 2 against the other; then the final. A level score (to the hundredth) goes
     * to the better seed. Draws no random numbers.
     */
    private static Bracket bracket(List<Integer> bySlot, List<Integer> playoffWeeks, double[][] points) {
        int[] slotOf = new int[SeasonFormat.PLAYOFF_TEAMS + 1];
        List<Seed> seeds = new ArrayList<>();
        for (int seed = 1; seed <= SeasonFormat.PLAYOFF_TEAMS; seed++) {
            slotOf[seed] = bySlot.get(seed - 1);
            seeds.add(new Seed(seed, slotOf[seed]));
        }

        int w1 = playoffWeeks.get(0), w2 = playoffWeeks.get(1), w3 = playoffWeeks.get(2);
        PlayoffMatchup a = playoffGame(3, 6, slotOf, points[w1]);
        PlayoffMatchup b = playoffGame(4, 5, slotOf, points[w1]);
        int winA = winnerSeed(a), winB = winnerSeed(b);
        Round one = new Round(1, w1, "ROUND_ONE", List.of(slotOf[1], slotOf[2]), List.of(a, b));

        int lowSurvivor = Math.max(winA, winB), highSurvivor = Math.min(winA, winB);
        PlayoffMatchup c = playoffGame(1, lowSurvivor, slotOf, points[w2]);
        PlayoffMatchup d = playoffGame(2, highSurvivor, slotOf, points[w2]);
        Round two = new Round(2, w2, "SEMIFINAL", List.of(), List.of(c, d));

        int winC = winnerSeed(c), winD = winnerSeed(d);
        PlayoffMatchup f = playoffGame(Math.min(winC, winD), Math.max(winC, winD), slotOf, points[w3]);
        Round three = new Round(3, w3, "FINAL", List.of(), List.of(f));
        int champion = winnerSeed(f);
        int runnerUp = loserSeed(f);

        // Final places: champion, final loser, semifinal losers by seed, round-one losers by seed, then the rest in
        // standings order.
        List<FinalPlace> places = new ArrayList<>();
        int place = 1;
        places.add(new FinalPlace(slotOf[champion], place++, HistoricalResult.CHAMPION));
        places.add(new FinalPlace(slotOf[runnerUp], place++, HistoricalResult.LOST_FINAL));
        List<Integer> semiLosers = new ArrayList<>(List.of(loserSeed(c), loserSeed(d)));
        Collections.sort(semiLosers);
        for (int seed : semiLosers) places.add(new FinalPlace(slotOf[seed], place++, HistoricalResult.LOST_SEMIFINAL));
        List<Integer> oneLosers = new ArrayList<>(List.of(loserSeed(a), loserSeed(b)));
        Collections.sort(oneLosers);
        for (int seed : oneLosers) places.add(new FinalPlace(slotOf[seed], place++, HistoricalResult.LOST_ROUND_ONE));
        for (int i = SeasonFormat.PLAYOFF_TEAMS; i < bySlot.size(); i++) {
            places.add(new FinalPlace(bySlot.get(i), place++, HistoricalResult.MISSED_PLAYOFFS));
        }
        return new Bracket(new Playoffs(seeds, List.of(one, two, three), slotOf[champion]), places);
    }

    private static PlayoffMatchup playoffGame(int highSeed, int lowSeed, int[] slotOf, double[] week) {
        int hs = slotOf[highSeed], ls = slotOf[lowSeed];
        double hp = week[hs], lp = week[ls];
        long h = Math.round(hp * 100), l = Math.round(lp * 100);
        boolean bySeed = h == l;
        int winner = bySeed || h > l ? hs : ls;
        return new PlayoffMatchup(highSeed, lowSeed, hs, ls, hp, lp, winner, bySeed);
    }

    private static int winnerSeed(PlayoffMatchup m) {
        return m.winnerSlot() == m.highSlot() ? m.highSeed() : m.lowSeed();
    }

    private static int loserSeed(PlayoffMatchup m) {
        return m.winnerSlot() == m.highSlot() ? m.lowSeed() : m.highSeed();
    }

    /** Circle method: team 0 fixed, the others rotated by {@code round}; first with last, second with second-to-last. */
    static List<int[]> pairings(List<Integer> order, int round, int week) {
        int n = order.size();
        List<Integer> arr = new ArrayList<>(n);
        arr.add(order.get(0));
        for (int i = 0; i < n - 1; i++) arr.add(order.get(1 + (i + round) % (n - 1)));
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n / 2; i++) {
            int a = arr.get(i), b = arr.get(n - 1 - i);
            // Alternate home and away from one cycle to the next so nobody is always home against a given team.
            boolean flip = ((week - 1) / (n - 1)) % 2 == 1;
            out.add(flip ? new int[]{b, a} : new int[]{a, b});
        }
        return out;
    }

    private static String monday(LocalDate firstGame) {
        return firstGame == null ? null : firstGame.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
    }
}
