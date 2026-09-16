package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The League analysis page's three computed blocks (claude/league-analysis.md):
 * a composite ranking score, rest-of-season roster projections broken out by
 * position group, and the position-group ranking matrix that falls out of them.
 *
 * <p>Read-only and computed on request. Unlike {@link PowerRankingService} and
 * {@link PlayoffOddsService} nothing here is snapshotted, because nothing here
 * is a claim about a moment: a projection is about the future and is only ever
 * interesting in its current form, and the ranking score is a pure function of
 * results already snapshotted in {@code roster_week_points}.
 */
@Service
public class LeagueAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(LeagueAnalysisService.class);

    /**
     * Scored weeks needed before a ranking score is published.
     *
     * At one week {@code high == low == avg} and ffwrapped's formula collapses
     * into a rescaling of a single game -- a confident-looking 1-100 number
     * with one data point under it. Three is the point at which high and low
     * are actually distinct from the mean often enough to carry their own
     * weight in the formula. Same discipline as playoff odds refusing to write
     * before a week is scored: the honest answer early is no answer.
     */
    static final int MIN_SCORED_WEEKS = 3;

    /**
     * ffwrapped publishes this formula on their own page. Reproduced verbatim
     * rather than "improved": the point of this block is to be the thing Allan
     * pointed at, and a tweaked formula that renders slightly different numbers
     * would be neither theirs nor honestly ours.
     */
    static double rawScore(double avgWeekly, double high, double low, double winPct) {
        return ((avgWeekly * 6) + ((high + low) * 2) + (winPct * 400)) / 10;
    }

    private final LeagueRepository leagues;
    private final RosterSeasonRepository rosterSeasons;
    private final RosterWeekPointsRepository weekPoints;
    private final PlayerProjectionRepository projections;
    private final PlayerRepository players;
    private final ManagerRepository managers;
    private final LeagueMatchupRepository matchupRepo;
    private final SleeperClient sleeper;
    private final SportRulesRegistry rulesRegistry;

    public LeagueAnalysisService(LeagueRepository leagues, RosterSeasonRepository rosterSeasons,
                                 RosterWeekPointsRepository weekPoints, PlayerProjectionRepository projections,
                                 PlayerRepository players, ManagerRepository managers,
                                 LeagueMatchupRepository matchupRepo,
                                 SleeperClient sleeper, SportRulesRegistry rulesRegistry) {
        this.leagues = leagues;
        this.rosterSeasons = rosterSeasons;
        this.weekPoints = weekPoints;
        this.projections = projections;
        this.players = players;
        this.managers = managers;
        this.matchupRepo = matchupRepo;
        this.sleeper = sleeper;
        this.rulesRegistry = rulesRegistry;
    }

    // ---- wire shapes ----

    public record Analysis(int season, String scoringKey, Window window,
                           RankingScores rankingScores, Projections projections, Matchups matchups,
                           Scores scores) {}

    /** The rest-of-season window, named so the page can say what it covered. */
    public record Window(int fromWeek, int toWeek, int weeks, int scoredWeeks) {}

    /**
     * {@code available} false carries {@code reason} and an empty list, rather
     * than a list of zeroes -- a ladder of twelve identical numbers reads as a
     * broken feature, not as "too early to say".
     */
    public record RankingScores(boolean available, String reason, String formula,
                                int weeksScored, int weeksRequired, List<ScoreEntry> entries) {}

    public record ScoreEntry(int rank, int rosterId, Long managerId, String manager, String avatarId,
                             double score, double raw, double avgWeekly, double high, double low,
                             double winPct, int wins, int losses, int ties) {}

    public record Projections(boolean available, String reason, List<String> positionGroups,
                              List<RosterProjection> rosters) {}

    /**
     * @param byPosition   projected points this roster's STARTERS contribute,
     *                     keyed by the starter's own position -- a RB filling a
     *                     FLEX slot counts under RB, which is what makes the
     *                     position-group comparison mean "how good are their
     *                     running backs" rather than "how is their flex".
     * @param rankByPosition this roster's rank in the league at each position
     *                     group, 1 = most projected. Piece 3 of the page is
     *                     this field read down the columns.
     * @param isMe         this is the signed-in reader's own roster. Decided by
     *                     Sleeper's {@code owner_id}, which IS a Sleeper user
     *                     id, against the caller's -- so it needs no lookup and
     *                     is simply false for a signed-out reader.
     * @param bench        every rostered player who did not start, valued over
     *                     the same window and sorted descending. A zero at the
     *                     bottom of this list IS the explanation for a thin
     *                     bar, in place, where {@code missing} is only a count.
     * @param missing      rostered players with no projection at all in the
     *                     window (IR, Out, PUP). Reported rather than hidden:
     *                     it is the honest explanation for a thin-looking bar.
     */
    public record RosterProjection(int rosterId, Long managerId, String manager, String avatarId, int rank,
                                   boolean isMe, double total, Map<String, Double> byPosition,
                                   Map<String, Integer> rankByPosition, List<LineupPlayer> starters,
                                   List<LineupPlayer> bench, List<WeekTotal> byWeek, int missing) {}

    /**
     * One remaining week of one roster's projection
     * (claude/league-analysis-week-by-week.md).
     *
     * <p>The rest-of-season total is the sum of these, but the sum cannot be
     * taken apart again: a bye week is a hole in this list and is invisible in
     * the total, which is the whole reason the list exists.
     */
    public record WeekTotal(int week, double points) {}

    /**
     * One rostered player, valued over whichever window the pass ran on.
     *
     * @param slot         the roster slot filled -- a league slot name for a
     *                     starter ("QB", "FLEX"), and "BN" for the bench.
     * @param injuryStatus Sleeper's own tag as of the last player ingest, or
     *                     null. Carried because it is the explanation for a
     *                     zero: a bench line reading "A.J. Brown IR 0.0" says
     *                     what a count of "6 unprojected" cannot.
     */
    public record LineupPlayer(String sleeperPlayerId, String name, String position, String team,
                               String slot, double points, String injuryStatus) {}

    /**
     * The next unplayed week's real pairings, each side valued for THAT WEEK
     * alone (claude/league-analysis-lineups-and-matchups.md piece 3).
     *
     * <p>Sides rather than home/away: Sleeper has no home team, only a
     * {@code matchup_id} grouping two rosters, and inventing a side would be
     * asserting something the source does not say. A group of one is a bye and
     * is carried rather than dropped -- a manager with no game next week needs
     * telling.
     */
    public record Matchups(boolean available, String reason, int week, List<Matchup> matchups) {}

    public record Matchup(int matchupId, List<Side> sides) {}

    /**
     * Every scored week, read across (the grid) and ranked down (the bump
     * chart's series). Both fall out of {@code roster_week_points} in one pass,
     * the same way the position matrix falls out of the projections.
     *
     * <p>Deliberately NOT power rankings' ladder re-drawn here. That one is a
     * composite the commissioner and the voters feed; this is what each roster
     * actually scored, which is a different claim with a different source.
     */
    public record Scores(boolean available, String reason, List<Integer> weeks, List<ScoreRow> rosters) {}

    public record ScoreRow(int rosterId, Long managerId, String manager, String avatarId, boolean isMe,
                           List<WeekScore> weeks, double total, double avg, double high, double low) {}

    /** @param rank this roster's scoring rank THAT WEEK, 1 = highest. */
    public record WeekScore(int week, double points, int rank) {}

    public record Side(int rosterId, Long managerId, String manager, String avatarId, boolean isMe,
                       double projected, Map<String, Double> byPosition, List<LineupPlayer> starters) {}

    // ---- entry point ----

    public Analysis analyse(String sleeperLeagueId, String sleeperUserId) {
        return analyse(sleeperLeagueId, sleeperUserId, null);
    }

    /**
     * @param week which week the matchup block should be valued for, or null
     *             for the next unplayed one. Only that block moves: the
     *             rest-of-season projections and the scores grid are not
     *             statements about a chosen week and do not change with it.
     */
    public Analysis analyse(String sleeperLeagueId, String sleeperUserId, Integer week) {
        LeagueRepository.LeagueRow league = leagues.bySleeperId(sleeperLeagueId)
                .orElseThrow(() -> new IllegalArgumentException("no such league: " + sleeperLeagueId));

        PlayerProjectionRepository.ScoringKey key =
                PlayerProjectionRepository.ScoringKey.forReceptionPoints(league.ppr());

        // Scored weeks come from what this DB actually holds rather than from
        // Sleeper's last_scored_leg. The two disagree exactly when ingest is
        // behind, and every number below is computed from the stored rows --
        // so reporting the stored count is reporting what the page is showing.
        Set<Integer> scored = weekPoints.storedWeeks(league.id());
        int lastScored = scored.stream().mapToInt(Integer::intValue).max().orElse(0);

        int playoffWeekStart = leagues.playoffFormat(league.id())
                .map(LeagueRepository.PlayoffFormat::playoffWeekStart)
                .orElse(0);
        int fromWeek = lastScored + 1;
        // Rest-of-season ends where the league's regular season does, not where
        // the NFL's does. Sleeper will serve weeks 15-18 happily; counting them
        // would credit a roster for games this league never plays.
        int toWeek = playoffWeekStart - 1;

        Window window = new Window(fromWeek, toWeek, Math.max(0, toWeek - fromWeek + 1), scored.size());

        // Both projection blocks come out of one call: they share every gate,
        // every lookup table and the roster list itself, and differ only in
        // which weeks they value. Computing them apart would fetch the same
        // twelve Sleeper rosters twice to answer one request.
        Blocks blocks = projectionBlocks(league, key, fromWeek, toWeek, playoffWeekStart, sleeperUserId,
                week == null ? fromWeek : week);

        return new Analysis(league.season(), key.name(), window,
                rankingScores(league, scored.size(), lastScored),
                blocks.projections(), blocks.matchups(),
                scores(league, lastScored, sleeperUserId));
    }

    // ---- the scores grid, and the rank-by-week series under it ----

    /**
     * What every roster actually scored, week by week.
     *
     * <p>Reads only {@code roster_week_points}, which is settled data -- a week
     * that has been played does not change -- so unlike the projections this
     * needs no staleness rule and no refresh button. The per-week rank goes
     * through {@link Ranker} for the same reason every other rank on this page
     * does: two rosters that tied on points in a week must not be numbered as
     * though they did not.
     */
    private Scores scores(LeagueRepository.LeagueRow league, int lastScored, String sleeperUserId) {
        if (lastScored < 1) {
            return new Scores(false, "no week of this season has been ingested yet, so there is nothing to read"
                    + " week by week", List.of(), List.of());
        }

        Map<Integer, List<RosterWeekPointsRepository.WeekPoint>> byWeek = new TreeMap<>();
        for (RosterWeekPointsRepository.WeekPoint wp : weekPoints.through(league.id(), lastScored)) {
            byWeek.computeIfAbsent(wp.week(), w -> new ArrayList<>()).add(wp);
        }

        // rosterId -> week -> (points, rank). Ranked within each week first,
        // because a roster's rank in week 3 is a fact about week 3 and not
        // about the roster's own history.
        Map<Integer, List<WeekScore>> rowsByRoster = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<RosterWeekPointsRepository.WeekPoint>> e : byWeek.entrySet()) {
            for (Ranker.Ranked<RosterWeekPointsRepository.WeekPoint> r : Ranker.rank(e.getValue(),
                    Comparator.comparingDouble(RosterWeekPointsRepository.WeekPoint::startersPoints).reversed(),
                    RosterWeekPointsRepository.WeekPoint::startersPoints)) {
                rowsByRoster.computeIfAbsent(r.item().rosterId(), k -> new ArrayList<>())
                        .add(new WeekScore(e.getKey(), round(r.item().startersPoints(), 2), r.rank()));
            }
        }

        // "Mine" by manager id here rather than by Sleeper owner_id: this block
        // reads stored standings and must work for a league whose projections
        // are unavailable, so it cannot depend on the live roster fetch the
        // projection pass makes. `manager` is unique on sleeper_user_id, so the
        // two routes name the same person.
        Long myManagerId = sleeperUserId == null ? null : managers.idsBySleeperUserId().get(sleeperUserId);

        List<ScoreRow> rows = new ArrayList<>();
        for (RosterSeasonRepository.StandingRow r : rosterSeasons.forLeague(league.id())) {
            List<WeekScore> weeks = rowsByRoster.getOrDefault(r.rosterId(), List.of());
            if (weeks.isEmpty()) continue;
            double total = weeks.stream().mapToDouble(WeekScore::points).sum();
            double high = weeks.stream().mapToDouble(WeekScore::points).max().orElse(0);
            double low = weeks.stream().mapToDouble(WeekScore::points).min().orElse(0);
            rows.add(new ScoreRow(r.rosterId(), r.managerId(), r.managerName(), r.avatarId(),
                    myManagerId != null && myManagerId.equals(r.managerId()), List.copyOf(weeks),
                    round(total, 2), round(total / weeks.size(), 2), high, low));
        }
        rows.sort(Comparator.comparingDouble(ScoreRow::total).reversed());

        return new Scores(true, null, List.copyOf(byWeek.keySet()), List.copyOf(rows));
    }


    // ---- piece 1 ----

    private RankingScores rankingScores(LeagueRepository.LeagueRow league, int weeksScored, int lastScored) {
        String formula = "((avgWeeklyScore * 6) + ((highScore + lowScore) * 2) + (winPct * 400)) / 10";
        if (weeksScored < MIN_SCORED_WEEKS) {
            String reason = weeksScored == 0
                    ? "no week of this season has been ingested yet -- run POST /api/ingest/league-history/"
                            + league.sleeperId()
                    : weeksScored + " week" + (weeksScored == 1 ? "" : "s")
                            + " scored. The formula weighs a team's best and worst week against its average,"
                            + " which cannot mean anything until those are three different numbers;"
                            + " " + MIN_SCORED_WEEKS + " weeks are needed.";
            return new RankingScores(false, reason, formula, weeksScored, MIN_SCORED_WEEKS, List.of());
        }

        Map<Integer, List<Double>> weeklyByRoster = new HashMap<>();
        for (RosterWeekPointsRepository.WeekPoint wp : weekPoints.through(league.id(), lastScored)) {
            weeklyByRoster.computeIfAbsent(wp.rosterId(), k -> new ArrayList<>()).add(wp.startersPoints());
        }

        Map<Integer, String> managerNames = new HashMap<>();
        Map<Integer, String> avatars = new HashMap<>();
        Map<Integer, Long> managerIds = new HashMap<>();
        Map<Integer, int[]> records = new HashMap<>();
        for (RosterSeasonRepository.StandingRow r : rosterSeasons.forLeague(league.id())) {
            records.put(r.rosterId(), new int[]{nz(r.wins()), nz(r.losses()), nz(r.ties())});
            managerIds.put(r.rosterId(), r.managerId());
            managerNames.put(r.rosterId(), r.managerName());
            avatars.put(r.rosterId(), r.avatarId());
        }

        List<ScoreEntry> raw = new ArrayList<>();
        for (Map.Entry<Integer, List<Double>> e : weeklyByRoster.entrySet()) {
            List<Double> weeks = e.getValue();
            if (weeks.isEmpty()) continue;
            double avg = weeks.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double high = weeks.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double low = weeks.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            int[] rec = records.getOrDefault(e.getKey(), new int[]{0, 0, 0});
            int games = rec[0] + rec[1] + rec[2];
            double winPct = games == 0 ? 0 : (rec[0] + rec[2] / 2.0) / games;
            raw.add(new ScoreEntry(0, e.getKey(), managerIds.get(e.getKey()), managerNames.get(e.getKey()),
                    avatars.get(e.getKey()), 0, rawScore(avg, high, low, winPct), avg, high, low, winPct,
                    rec[0], rec[1], rec[2]));
        }

        return new RankingScores(true, null, formula, weeksScored, MIN_SCORED_WEEKS, normalise(raw));
    }

    /**
     * Maps the raw formula onto 1-100 with the league mean at 50.
     *
     * ffwrapped shows a 1-100 score and publishes the formula that feeds it,
     * but not the mapping between the two -- so this half is ours, and is
     * reported alongside {@code raw} precisely so the page can show the number
     * their formula actually produced next to the one we scaled it to.
     *
     * Scaled by the largest deviation from the mean, which puts the most
     * extreme team at 100 or 1 and everyone else proportionally between. A
     * league whose teams are all identical has no spread to scale by and gets
     * a flat 50 rather than a division by zero.
     */
    private static List<ScoreEntry> normalise(List<ScoreEntry> raw) {
        if (raw.isEmpty()) return List.of();
        double mean = raw.stream().mapToDouble(ScoreEntry::raw).average().orElse(0);
        double maxDev = raw.stream().mapToDouble(e -> Math.abs(e.raw() - mean)).max().orElse(0);

        List<ScoreEntry> scored = new ArrayList<>();
        for (ScoreEntry e : raw) {
            double score = maxDev == 0 ? 50 : 50 + 50 * (e.raw() - mean) / maxDev;
            score = Math.max(1, Math.min(100, score));
            scored.add(new ScoreEntry(0, e.rosterId(), e.managerId(), e.manager(), e.avatarId(),
                    round(score, 1), round(e.raw(), 2), round(e.avgWeekly(), 2), round(e.high(), 2),
                    round(e.low(), 2), round(e.winPct(), 4), e.wins(), e.losses(), e.ties()));
        }
        List<ScoreEntry> ranked = new ArrayList<>();
        for (Ranker.Ranked<ScoreEntry> r : Ranker.rank(scored,
                Comparator.comparingDouble(ScoreEntry::score).reversed(), ScoreEntry::score)) {
            ScoreEntry e = r.item();
            ranked.add(new ScoreEntry(r.rank(), e.rosterId(), e.managerId(), e.manager(), e.avatarId(),
                    e.score(), e.raw(), e.avgWeekly(), e.high(), e.low(), e.winPct(),
                    e.wins(), e.losses(), e.ties()));
        }
        return ranked;
    }

    // ---- pieces 2, 3 and the matchup preview ----

    /** The two blocks that read projections, which share every gate below. */
    private record Blocks(Projections projections, Matchups matchups) {}

    private Blocks projectionBlocks(LeagueRepository.LeagueRow league,
                                    PlayerProjectionRepository.ScoringKey key,
                                    int fromWeek, int toWeek, int playoffWeekStart,
                                    String sleeperUserId, int matchupWeek) {
        List<String> groups = Position.forSport(league.sport()).stream().map(Enum::name).toList();

        if (playoffWeekStart < 2) {
            return unavailable(groups, matchupWeek,
                    "this league has no playoff_week_start, so there is no regular season to project to the end of");
        }
        if (fromWeek > toWeek) {
            return unavailable(groups, matchupWeek,
                    "the regular season is over (weeks run to " + toWeek + ", and " + (fromWeek - 1)
                            + " are scored), so there is nothing left to project");
        }

        // Said before the cache is consulted, because for a sport with no
        // projection source the cache will ALWAYS be empty and the "go and
        // ingest them" advice below would point at an endpoint that answers
        // 400. A reason the reader cannot act on is worse than no reason.
        if (league.sport() != Sport.NFL) {
            return unavailable(groups, matchupWeek,
                    "roster projections are football-only: the projection source wired up is Sleeper's"
                            + " own weekly points (pts_ppr and friends), which has no "
                            + league.sport().code() + " equivalent. See claude/league-analysis.md's non-goals.");
        }

        Map<String, Double> restOfSeason =
                projections.totalsByPlayer(league.sport().code(), league.season(), fromWeek, toWeek, key);
        if (restOfSeason.isEmpty()) {
            return unavailable(groups, matchupWeek, noProjectionsStored(league, fromWeek, toWeek));
        }

        LineupPass pass = new LineupPass(league, groups, sleeperUserId);
        List<RosterProjection> rosters = pass.over(restOfSeason);

        Map<Integer, Map<String, Double>> perWeek = projections.totalsByPlayerPerWeek(
                league.sport().code(), league.season(), fromWeek, toWeek, key);

        List<RosterProjection> ranked = rankAll(withWeekly(rosters, perWeek), groups);

        log.info("league analysis: league {} projected weeks {}-{} using {} over {} players",
                league.id(), fromWeek, toWeek, key.column(), restOfSeason.size());

        return new Blocks(new Projections(true, null, groups, ranked),
                matchupBlock(league, key, matchupWeek, pass));
    }

    /**
     * One reason, both blocks. Every gate the matchup preview would apply for
     * itself is upstream of it, so restating them in its own words would be two
     * explanations of one fact, free to drift apart.
     */
    private static Blocks unavailable(List<String> groups, int week, String reason) {
        return new Blocks(new Projections(false, reason, groups, List.of()),
                new Matchups(false, reason, week, List.of()));
    }

    private static String noProjectionsStored(LeagueRepository.LeagueRow league, int fromWeek, int toWeek) {
        return "no projections stored for " + league.sport().code() + " " + league.season()
                + " weeks " + fromWeek + "-" + toWeek + " -- run POST /api/ingest/projections?sport="
                + league.sport().code() + "&season=" + league.season()
                + "&fromWeek=" + fromWeek + "&toWeek=" + toWeek;
    }

    /**
     * The matchup preview. The pairings are already stored: claude/playoff-odds.md
     * built {@code league_matchup} because {@link RosterWeekPointsRepository}
     * caches what every roster scored and drops who they played, and the league
     * history ingest walks unplayed weeks to store theirs. So this is a read,
     * not an ingest.
     *
     * <p><b>The week is valued on its own.</b> A roster's best starter over
     * thirteen weeks is not necessarily its best starter in week 2 -- a bye or
     * a one-week injury moves it -- so this re-runs the same greedy assembly
     * against a one-week points map rather than dividing a rest-of-season total
     * by anything.
     */
    private Matchups matchupBlock(LeagueRepository.LeagueRow league,
                                  PlayerProjectionRepository.ScoringKey key,
                                  int week, LineupPass pass) {
        List<LeagueMatchupRepository.Fixture> fixtures =
                matchupRepo.between(league.id(), league.season(), week, week);
        if (fixtures.isEmpty()) {
            // Reachable, and not an error: Sleeper answers a week it has not
            // scheduled yet with every matchup_id null, and
            // LeagueMatchupRepository deliberately refuses to count that as
            // cached rather than poisoning the schedule forever.
            return new Matchups(false, "no pairings stored for week " + week
                    + " -- Sleeper publishes a week's schedule shortly before it, and it is stored by"
                    + " POST /api/ingest/league-history/" + league.sleeperId(), week, List.of());
        }

        Map<String, Double> weekly =
                projections.totalsByPlayer(league.sport().code(), league.season(), week, week, key);
        if (weekly.isEmpty()) {
            // Its own gate, not implied by the rest-of-season one above: a
            // thirteen-week window can hold rows while this single week does not.
            return new Matchups(false, noProjectionsStored(league, week, week), week, List.of());
        }

        Map<Integer, RosterProjection> byRoster = new HashMap<>();
        pass.over(weekly).forEach(r -> byRoster.put(r.rosterId(), r));

        List<Matchup> out = pair(fixtures, byRoster);

        log.info("league analysis: league {} week {} matchups: {} pairings over {} projected players",
                league.id(), week, out.size(), weekly.size());
        return new Matchups(true, null, week, List.copyOf(out));
    }

    /**
     * The rest-of-season bar taken apart into the weeks that made it.
     *
     * <p><b>It values the lineup the bar is already made of, week by week --
     * it does not re-optimise each week.</b> The first cut did re-optimise, and
     * the numbers immediately disagreed: kieriskash's weeks summed to 1777.1
     * under a bar reading 1683.9, because setting the best lineup every week
     * beats locking one lineup for thirteen, always. Both numbers were correct
     * answers to different questions, printed a centimetre apart with nothing
     * saying so. This block asks the bar's question, so the strip under a bar
     * now sums to it exactly, and a bye week still shows as the dip it is.
     *
     * <p>The week-by-week optimum is a real number and it has a home: the
     * matchup preview, where the question genuinely is "what would this roster
     * start in THAT game".
     */
    static List<RosterProjection> withWeekly(List<RosterProjection> rosters,
                                             Map<Integer, Map<String, Double>> perWeek) {
        List<RosterProjection> out = new ArrayList<>();
        for (RosterProjection p : rosters) {
            List<WeekTotal> series = new ArrayList<>();
            perWeek.forEach((week, points) -> {
                double total = 0;
                for (LineupPlayer starter : p.starters()) {
                    total += points.getOrDefault(starter.sleeperPlayerId(), 0.0);
                }
                series.add(new WeekTotal(week, round(total, 1)));
            });
            series.sort(Comparator.comparingInt(WeekTotal::week));
            out.add(new RosterProjection(p.rosterId(), p.managerId(), p.manager(), p.avatarId(), p.rank(),
                    p.isMe(), p.total(), p.byPosition(), p.rankByPosition(), p.starters(), p.bench(),
                    List.copyOf(series), p.missing()));
        }
        return out;
    }

    /**
     * Fixtures plus valued rosters into the pairings the page draws.
     *
     * <p>Static and package-private because this is the half of the matchup
     * block with rules in it -- who is grouped with whom, what a group of one
     * means, what order any of it comes out in -- while everything around it is
     * Sleeper and Postgres. It is tested directly for that reason.
     *
     * <p>A fixture whose roster has no valued lineup is dropped rather than
     * shown at zero: that only happens when Sleeper's roster list and the
     * stored schedule disagree, and a roster projected to score nothing is a
     * claim, where a missing row is an absence.
     */
    static List<Matchup> pair(List<LeagueMatchupRepository.Fixture> fixtures,
                              Map<Integer, RosterProjection> byRoster) {
        Map<Integer, List<Side>> grouped = new LinkedHashMap<>();
        for (LeagueMatchupRepository.Fixture f : fixtures) {
            RosterProjection p = byRoster.get(f.rosterId());
            if (p == null) continue;
            grouped.computeIfAbsent(f.matchupId(), k -> new ArrayList<>())
                    .add(new Side(p.rosterId(), p.managerId(), p.manager(), p.avatarId(),
                            p.isMe(), p.total(), p.byPosition(), p.starters()));
        }

        List<Matchup> out = new ArrayList<>();
        grouped.forEach((matchupId, sides) -> {
            sides.sort(Comparator.comparingDouble(Side::projected).reversed());
            out.add(new Matchup(matchupId, List.copyOf(sides)));
        });
        // Heaviest game first. Sleeper's matchup_id is an arbitrary key, so some
        // order has to be chosen, and this one at least means something.
        out.sort(Comparator.comparingDouble(
                (Matchup m) -> m.sides().stream().mapToDouble(Side::projected).sum()).reversed());
        return List.copyOf(out);
    }

    /**
     * The league's own lineup card order: a starter sits at the first slot of
     * its own name in {@code roster_positions}, ties broken by points.
     *
     * <p>The order {@link SportRules#startingLineup} returns cannot answer
     * this: it iterates {@link LeagueSettings#dedicatedStarters()}, which is a
     * {@code Map.of(...)} whose iteration order is unspecified and salted per
     * JVM run -- the points-descending sort this replaces was hiding that, and
     * a lineup card reading RB, WR, QB, FLEX is not a lineup card.
     * {@code roster_positions} is an ordered list read straight off Sleeper
     * (QB, RB, RB, WR, WR, TE, FLEX, FLEX, K, DEF), so the league answers it.
     *
     * <p>An unrecognised slot sorts to the END rather than the front, so a slot
     * this app has never heard of (SUPER_FLEX, a bench line) can never
     * masquerade as the quarterback.
     */
    static Comparator<LineupPlayer> byLineupCard(List<String> slotOrder) {
        return Comparator.comparingInt((LineupPlayer l) -> {
                    int i = slotOrder.indexOf(l.slot());
                    return i < 0 ? slotOrder.size() : i;
                })
                .thenComparing(Comparator.comparingDouble(LineupPlayer::points).reversed());
    }

    /**
     * Everything a lineup pass needs that does not depend on WHICH weeks are
     * being valued, so that the rest-of-season block and the weekly matchup
     * block are the same assembly run twice rather than two assemblies.
     *
     * <p>A second loop valued weekly would be two implementations of "what does
     * this roster start" -- the bug this repo has shipped three times under
     * three names, and the reason {@link SportRules#startingLineup} exists as
     * one shared rule in the first place.
     */
    private final class LineupPass {

        private final List<Map<String, Object>> rosters;
        private final LeagueSettings settings;
        private final SportRules rules;
        private final List<String> groups;
        private final List<String> slotOrder;
        private final Map<String, Player> playerBySleeperId = new HashMap<>();
        private final Map<String, Long> managerBySleeperUserId;
        private final Map<Integer, String> managerNames = new HashMap<>();
        private final Map<Integer, String> avatars = new HashMap<>();
        private final String sleeperUserId;

        LineupPass(LeagueRepository.LeagueRow league, List<String> groups, String sleeperUserId) {
            this.groups = groups;
            this.sleeperUserId = sleeperUserId;
            this.settings = LeagueRepository.toSettings(league, league.rosterPositions().size());
            this.rules = rulesRegistry.get(league.sport());
            this.slotOrder = league.rosterPositions();
            this.rosters = sleeper.rosters(league.sleeperId());
            this.managerBySleeperUserId = managers.idsBySleeperUserId();
            players.findAll(league.sport()).forEach(p -> playerBySleeperId.put(p.sleeperId(), p));
            rosterSeasons.forLeague(league.id()).forEach(r -> {
                managerNames.put(r.rosterId(), r.managerName());
                avatars.put(r.rosterId(), r.avatarId());
            });
        }

        /** Unranked: ranking is the rest-of-season block's business, not the weekly one's. */
        List<RosterProjection> over(Map<String, Double> pointsByPlayer) {
            List<RosterProjection> out = new ArrayList<>();
            for (Map<String, Object> roster : rosters) {
                int rosterId = asInt(roster.get("roster_id"));
                if (rosterId < 0) continue;
                Object ownerId = roster.get("owner_id");
                String owner = ownerId == null ? null : String.valueOf(ownerId);
                Long managerId = owner == null ? null : managerBySleeperUserId.get(owner);
                // owner_id IS a Sleeper user id, which is exactly what the
                // caller's header carries, so "is this mine" is a string
                // comparison and not a manager lookup. A signed-out reader has
                // no id and therefore owns nothing, which is the right answer.
                boolean isMe = owner != null && owner.equals(sleeperUserId);

                @SuppressWarnings("unchecked")
                List<String> rosterPlayers = (List<String>) roster.getOrDefault("players", List.of());

                RosterState state = new RosterState();
                List<Player> known = new ArrayList<>();
                int missing = 0;
                for (String sleeperPlayerId : rosterPlayers) {
                    Player p = playerBySleeperId.get(sleeperPlayerId);
                    if (p == null) { missing++; continue; }
                    if (!pointsByPlayer.containsKey(sleeperPlayerId)) missing++;
                    known.add(p);
                    // A synthetic board entry: this path values players by
                    // projection, and a rostered player who is simply not on the
                    // draft board (a waiver pickup, a rookie past the board's
                    // tail) still scores points. ADP is a placeholder here and
                    // never read -- startingLineup re-sorts by the supplied
                    // value function precisely so this cannot matter.
                    state.add(new BoardEntry(p, OFF_BOARD_ADP, 0));
                }

                List<SportRules.Assigned> lineup = rules.startingLineup(state, settings,
                        e -> pointsByPlayer.getOrDefault(e.player().sleeperId(), 0.0));

                Map<String, Double> byPosition = new LinkedHashMap<>();
                groups.forEach(g -> byPosition.put(g, 0.0));
                List<LineupPlayer> starters = new ArrayList<>();
                Set<String> started = new HashSet<>();
                double total = 0;
                for (SportRules.Assigned a : lineup) {
                    Player p = a.entry().player();
                    String group = a.entry().position().name();
                    byPosition.merge(group, a.value(), Double::sum);
                    total += a.value();
                    started.add(p.sleeperId());
                    starters.add(lineupPlayer(p, group, a.slot(), a.value()));
                }
                starters.sort(byLineupCard(slotOrder));

                List<LineupPlayer> bench = new ArrayList<>();
                for (Player p : known) {
                    if (started.contains(p.sleeperId())) continue;
                    bench.add(lineupPlayer(p, p.primary().name(), "BN",
                            pointsByPlayer.getOrDefault(p.sleeperId(), 0.0)));
                }
                bench.sort(Comparator.comparingDouble(LineupPlayer::points).reversed());

                byPosition.replaceAll((g, v) -> round(v, 1));
                out.add(new RosterProjection(rosterId, managerId, managerNames.get(rosterId),
                        avatars.get(rosterId), 0, isMe, round(total, 1), byPosition, Map.of(),
                        List.copyOf(starters), List.copyOf(bench), List.of(), missing));
            }
            return out;
        }

        private LineupPlayer lineupPlayer(Player p, String group, String slot, double points) {
            return new LineupPlayer(p.sleeperId(), p.name(), group, p.team(), slot,
                    round(points, 1), p.injuryStatus());
        }

    }

    /**
     * Overall rank, plus piece 3's matrix: each roster's standing at each
     * position group. Computed here rather than in the page so that "3rd at RB"
     * and the points it came from can never disagree -- they are derived from
     * one list in one pass.
     *
     * <p>Ties resolve through {@link Ranker}, the same competition ranking
     * (1, 2, 2, 4) power rankings and member ballots already use, rather than a
     * third copy of the rule -- that class exists because the second copy was
     * wrong. It matters here in practice, not just in principle: three rosters
     * in the real league project to the same 81.3 at K, and numbering them 4th,
     * 5th and 6th would assert a difference the number beside it denies.
     */
    private static List<RosterProjection> rankAll(List<RosterProjection> rosters, List<String> groups) {
        Map<Integer, Map<String, Integer>> rankByRoster = new HashMap<>();
        rosters.forEach(r -> rankByRoster.put(r.rosterId(), new LinkedHashMap<>()));

        for (String group : groups) {
            java.util.function.ToDoubleFunction<RosterProjection> pointsAt =
                    r -> r.byPosition().getOrDefault(group, 0.0);
            for (Ranker.Ranked<RosterProjection> r : Ranker.rank(rosters,
                    Comparator.comparingDouble(pointsAt).reversed(), pointsAt)) {
                rankByRoster.get(r.item().rosterId()).put(group, r.rank());
            }
        }

        List<RosterProjection> out = new ArrayList<>();
        for (Ranker.Ranked<RosterProjection> r : Ranker.rank(rosters,
                Comparator.comparingDouble(RosterProjection::total).reversed(), RosterProjection::total)) {
            RosterProjection p = r.item();
            out.add(new RosterProjection(p.rosterId(), p.managerId(), p.manager(), p.avatarId(), r.rank(),
                    p.isMe(), p.total(), p.byPosition(), rankByRoster.get(p.rosterId()), p.starters(),
                    p.bench(), p.byWeek(), p.missing()));
        }
        return out;
    }

    /**
     * Where a rostered player who is not on the draft board is placed. Only
     * ever used as {@link RosterState}'s insertion key, which
     * {@link SportRules#startingLineup} discards by re-sorting.
     */
    private static final double OFF_BOARD_ADP = 9999;

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : -1;
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
