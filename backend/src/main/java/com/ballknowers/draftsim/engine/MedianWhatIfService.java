package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.engine.ExpectedWinsService.Game;
import com.ballknowers.draftsim.engine.ExpectedWinsService.WinLossTie;
import com.ballknowers.draftsim.store.LeagueBracketRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.WeekBound;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The League history page's per-season median block (spec 054): each roster's weekly-median and all-play
 * record for ANY season of the chain, plus, for a season that did not play median games, what its record
 * and regular-season place would have been had it.
 *
 * <p><b>One median rule.</b> The median and all-play figures come from {@link ExpectedWinsService#median}
 * and {@link ExpectedWinsService#allPlay} over the games {@link ExpectedWinsService#finalGames} returns,
 * the same ones the Luck page uses. Nothing here re-implements either, or the "which weeks are final" test.
 *
 * <p><b>The what-if never touches a real figure.</b> It adds the median record to the real record to get a
 * "with median" column beside the real ones; the real W-L-T is Sleeper's own where that can be proven to
 * cover the same regular-season games, else re-counted from the stored scores (see {@code realSource}).
 */
@Service
public class MedianWhatIfService {

    /**
     * @param status               WHAT_IF | ALREADY_ON | NO_FINAL_WEEKS | NO_REGULAR_SEASON_BOUND | UNAVAILABLE
     * @param weeksCounted         distinct final weeks in the games used
     * @param driftGames           under OFFICIAL: rosters whose re-counted head-to-head differs from the official record
     * @param realSource           OFFICIAL | COUNTED_GAMES, null unless status is WHAT_IF
     * @param playoffTeams         null when the format cannot be modelled
     * @param playoffLineWithheld  DIVISIONS | SEED_TYPE | NO_PLAYOFF_TEAMS | BRACKET_UNKNOWN (finished season, no stored
     *                             winners bracket to check against) | ORDER_DISAGREES (the stored bracket's field differs
     *                             from this app's top N), null when the line is shown
     */
    public record SeasonMedian(String status, int weeksCounted, int driftGames, Integer regularSeasonWeeks,
                               String realSource, Integer playoffTeams, String playoffLineWithheld,
                               List<SeasonMedianTeam> teams) {

        /** The block a failed computation degrades to: the page renders dashes (FR-012). */
        public static SeasonMedian unavailable() {
            return new SeasonMedian("UNAVAILABLE", 0, 0, null, null, null, null, List.of());
        }
    }

    /**
     * @param gamesCounted weeks this roster appears in among the games used; the median record always covers
     *                     exactly this many comparisons
     * @param playoffCross MADE | MISSED, only when the line is shown and the two places sit on opposite sides of it
     */
    public record SeasonMedianTeam(int rosterId, int gamesCounted, WinLossTie median, WinLossTie allPlay,
                                   WinLossTie real, WinLossTie withMedian, Integer winsGained,
                                   Integer actualPlace, Integer whatIfPlace, String playoffCross) {}

    private final ExpectedWinsService expectedWins;
    private final LeagueBracketRepository brackets;

    public MedianWhatIfService(ExpectedWinsService expectedWins, LeagueBracketRepository brackets) {
        this.expectedWins = expectedWins;
        this.brackets = brackets;
    }

    /** Takes the standings rows and format the caller already loaded rather than re-querying them. */
    public SeasonMedian forSeason(LeagueRepository.LeagueRow league,
                                  List<RosterSeasonRepository.StandingRow> standings,
                                  Optional<LeagueRepository.PlayoffFormat> format) {
        // Same rule as ExpectedWinsService.regularSeasonBound, from the format already loaded (no 2nd query).
        WeekBound bound = format.filter(f -> f.playoffWeekStart() >= 2)
                .map(f -> WeekBound.through(f.playoffWeekStart() - 1))
                .orElse(WeekBound.ALL_WEEKS);
        List<Game> games = expectedWins.finalGames(league, bound);
        // Who made the playoffs = every roster in the stored winners bracket. Only a finished season is checked.
        Optional<Set<Integer>> field = Optional.empty();
        if (league.complete()) {
            Set<Integer> ids = new HashSet<>();
            for (LeagueBracketRepository.Match m : brackets.forLeagues(List.of(league.id()))) {
                if (m.t1RosterId() != null) ids.add(m.t1RosterId());
                if (m.t2RosterId() != null) ids.add(m.t2RosterId());
            }
            if (!ids.isEmpty()) field = Optional.of(ids);
        }
        return whatIf(games, standings, format, league.complete(), field);
    }

    // ---------------------------------------------------------------- pure core

    /** A roster's line for ranking: record plus the points-for tie-break. */
    private record Line(int rosterId, WinLossTie record, double pointsFor) {}

    /**
     * Every rule in one pure function (no Spring, no DB) so the unit tests pin it directly.
     *
     * @param games          final regular-season games, as {@link ExpectedWinsService#finalGames} returns them
     * @param official       the season's stored standings (Sleeper's or ESPN's own W-L-T and points for)
     * @param seasonComplete whether the season is over, which is what makes the playoff line checkable
     * @param playoffField   the rosters in the stored winners bracket; empty = no bracket stored (only read
     *                       when {@code seasonComplete})
     */
    static SeasonMedian whatIf(List<Game> games, List<RosterSeasonRepository.StandingRow> official,
                               Optional<LeagueRepository.PlayoffFormat> format, boolean seasonComplete,
                               Optional<Set<Integer>> playoffField) {
        Integer regularSeasonWeeks = format.filter(f -> f.playoffWeekStart() >= 2)
                .map(f -> f.playoffWeekStart() - 1).orElse(null);
        if (games.isEmpty()) {
            return new SeasonMedian("NO_FINAL_WEEKS", 0, 0, regularSeasonWeeks, null, null, null, List.of());
        }

        int weeksCounted = (int) games.stream().mapToInt(Game::week).distinct().count();
        Map<Integer, WinLossTie> median = ExpectedWinsService.median(games);
        Map<Integer, WinLossTie> allPlay = ExpectedWinsService.allPlay(games);
        Map<Integer, Set<Integer>> weeksByRoster = new TreeMap<>();
        for (Game g : games) {
            weeksByRoster.computeIfAbsent(g.aRosterId(), k -> new HashSet<>()).add(g.week());
            weeksByRoster.computeIfAbsent(g.bRosterId(), k -> new HashSet<>()).add(g.week());
        }

        String status;
        if (format.map(LeagueRepository.PlayoffFormat::medianMatch).orElse(false)) status = "ALREADY_ON";
        else if (regularSeasonWeeks == null) status = "NO_REGULAR_SEASON_BOUND";
        else status = "WHAT_IF";

        if (!status.equals("WHAT_IF")) {
            List<SeasonMedianTeam> teams = new ArrayList<>();
            for (Map.Entry<Integer, Set<Integer>> e : weeksByRoster.entrySet()) {
                teams.add(new SeasonMedianTeam(e.getKey(), e.getValue().size(), median.get(e.getKey()),
                        allPlay.get(e.getKey()), null, null, null, null, null, null));
            }
            return new SeasonMedian(status, weeksCounted, 0, regularSeasonWeeks, null, null, null, teams);
        }

        // ---- the real half: Sleeper's own record only when it provably covers the same games.
        Map<Integer, WinLossTie> counted = ExpectedWinsService.headToHead(games);
        Map<Integer, RosterSeasonRepository.StandingRow> officialByRoster = new HashMap<>();
        for (RosterSeasonRepository.StandingRow r : official) officialByRoster.put(r.rosterId(), r);
        boolean useOfficial = officialCoversTheCountedGames(weeksByRoster, officialByRoster, regularSeasonWeeks);

        Map<Integer, WinLossTie> real = new HashMap<>();
        int drift = 0;
        for (Integer rosterId : weeksByRoster.keySet()) {
            if (useOfficial) {
                RosterSeasonRepository.StandingRow o = officialByRoster.get(rosterId);
                WinLossTie off = new WinLossTie(o.wins(), o.losses(), o.ties());
                real.put(rosterId, off);
                if (!off.equals(counted.getOrDefault(rosterId, new WinLossTie(0, 0, 0)))) drift++;
            } else {
                real.put(rosterId, counted.getOrDefault(rosterId, new WinLossTie(0, 0, 0)));
            }
        }

        // ---- places: one comparator for both orders; points for from the same source as the record.
        Map<Integer, Double> countedPoints = new HashMap<>();
        for (Game g : games) {
            countedPoints.merge(g.aRosterId(), g.aPoints(), Double::sum);
            countedPoints.merge(g.bRosterId(), g.bPoints(), Double::sum);
        }
        List<Line> actualLines = new ArrayList<>();
        List<Line> whatIfLines = new ArrayList<>();
        for (Integer rosterId : weeksByRoster.keySet()) {
            double pf = useOfficial ? officialByRoster.get(rosterId).pointsFor() : countedPoints.getOrDefault(rosterId, 0.0);
            WinLossTie r = real.get(rosterId);
            WinLossTie m = median.getOrDefault(rosterId, new WinLossTie(0, 0, 0));
            actualLines.add(new Line(rosterId, r, pf));
            whatIfLines.add(new Line(rosterId, plus(r, m), pf));
        }
        Map<Integer, Integer> actualPlace = places(actualLines);
        Map<Integer, Integer> whatIfPlace = places(whatIfLines);

        // ---- the playoff line, only when the format is one this app can seed and the order is checkable.
        LeagueRepository.PlayoffFormat f = format.get();
        String withheld = null;
        Integer playoffTeams = null;
        if (f.hasDivisions()) withheld = "DIVISIONS";
        else if (f.seedType() != 0) withheld = "SEED_TYPE";
        else if (f.playoffTeams() <= 0) withheld = "NO_PLAYOFF_TEAMS";
        else {
            playoffTeams = f.playoffTeams();
            if (seasonComplete) {
                if (playoffField.isEmpty()) {
                    withheld = "BRACKET_UNKNOWN";
                    playoffTeams = null;
                } else if (!orderAgreesWithTheBracket(actualPlace, playoffField.get(), playoffTeams)) {
                    withheld = "ORDER_DISAGREES";
                    playoffTeams = null;
                }
            }
        }

        List<SeasonMedianTeam> teams = new ArrayList<>();
        for (Map.Entry<Integer, Set<Integer>> e : weeksByRoster.entrySet()) {
            int id = e.getKey();
            WinLossTie m = median.getOrDefault(id, new WinLossTie(0, 0, 0));
            String cross = null;
            if (playoffTeams != null) {
                boolean wasIn = actualPlace.get(id) <= playoffTeams;
                boolean isIn = whatIfPlace.get(id) <= playoffTeams;
                if (isIn && !wasIn) cross = "MADE";
                else if (wasIn && !isIn) cross = "MISSED";
            }
            teams.add(new SeasonMedianTeam(id, e.getValue().size(), m, allPlay.get(id), real.get(id),
                    plus(real.get(id), m), m.wins(), actualPlace.get(id), whatIfPlace.get(id), cross));
        }
        return new SeasonMedian("WHAT_IF", weeksCounted, useOfficial ? drift : 0, regularSeasonWeeks,
                useOfficial ? "OFFICIAL" : "COUNTED_GAMES", playoffTeams, withheld, teams);
    }

    /**
     * Sleeper's W-L-T is used only when it demonstrably covers the same regular-season games as the median
     * half: every roster has exactly {@code regularSeasonWeeks} counted games AND an official record that adds
     * up to the same number (so it holds no playoff games), no field is null, and both sides name the same
     * rosters. Anything else falls back to the re-counted record, never to an exception.
     */
    private static boolean officialCoversTheCountedGames(Map<Integer, Set<Integer>> weeksByRoster,
                                                         Map<Integer, RosterSeasonRepository.StandingRow> official,
                                                         int regularSeasonWeeks) {
        if (!official.keySet().equals(weeksByRoster.keySet())) return false;
        for (Map.Entry<Integer, Set<Integer>> e : weeksByRoster.entrySet()) {
            if (e.getValue().size() != regularSeasonWeeks) return false;
            RosterSeasonRepository.StandingRow o = official.get(e.getKey());
            if (o.wins() == null || o.losses() == null || o.ties() == null || o.pointsFor() == null) return false;
            if (o.wins() + o.losses() + o.ties() != regularSeasonWeeks) return false;
        }
        return true;
    }

    /**
     * For a finished season, this app's top {@code playoffTeams} by actual place must be exactly the rosters
     * that appear in the stored winners bracket (the real playoff field; byes enter in round 2, so every
     * qualifier is in some match). A mismatch withholds the line: a "would have missed the playoffs" tag
     * about a manager who in fact made them would be a fabricated fact, not a what-if.
     */
    private static boolean orderAgreesWithTheBracket(Map<Integer, Integer> actualPlace, Set<Integer> field,
                                                     int playoffTeams) {
        Set<Integer> byOrder = new HashSet<>();
        actualPlace.forEach((id, place) -> { if (place <= playoffTeams) byOrder.add(id); });
        return byOrder.equals(field);
    }

    /**
     * Win share (a tie is half a win) descending, then points for descending, then roster id. This app's
     * ordering, not Sleeper's seeding; the page says so.
     */
    private static Map<Integer, Integer> places(List<Line> lines) {
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble((Line l) -> share(l.record())).reversed()
                .thenComparing(Comparator.comparingDouble(Line::pointsFor).reversed())
                .thenComparingInt(Line::rosterId));
        Map<Integer, Integer> out = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) out.put(sorted.get(i).rosterId(), i + 1);
        return out;
    }

    private static double share(WinLossTie r) {
        int games = r.wins() + r.losses() + r.ties();
        return games == 0 ? 0 : (r.wins() + 0.5 * r.ties()) / games;
    }

    private static WinLossTie plus(WinLossTie a, WinLossTie b) {
        return new WinLossTie(a.wins() + b.wins(), a.losses() + b.losses(), a.ties() + b.ties());
    }
}
