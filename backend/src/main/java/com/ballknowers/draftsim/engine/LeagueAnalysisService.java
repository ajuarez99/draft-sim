package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.PreseasonProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.ballknowers.draftsim.store.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
    private final LeagueRosterSource rosterSource;
    private final SportRulesRegistry rulesRegistry;
    private final LeagueMemberRepository members;
    private final LetterGrades letterGrades;
    private final PlayerSeasonProjectionRepository seasonProjections;
    private final GameScoringService gameScoring;
    private final PreseasonProperties preseason;
    private final ProjectionAccuracyService accuracy;
    /** specs/036 US3: value football projections by exact league scoring instead of the PPR column pick. Off by default. */
    private final boolean nflExactScoring;

    public LeagueAnalysisService(LeagueRepository leagues, RosterSeasonRepository rosterSeasons,
                                 RosterWeekPointsRepository weekPoints, PlayerProjectionRepository projections,
                                 PlayerRepository players, ManagerRepository managers,
                                 LeagueMatchupRepository matchupRepo,
                                 SleeperClient sleeper, SportRulesRegistry rulesRegistry,
                                 LeagueMemberRepository members, LetterGrades letterGrades,
                                 PlayerSeasonProjectionRepository seasonProjections,
                                 GameScoringService gameScoring, PreseasonProperties preseason,
                                 ProjectionAccuracyService accuracy,
                                 @Value("${draftsim.projections.nfl-exact-scoring:false}") boolean nflExactScoring) {
        this.accuracy = accuracy;
        this.nflExactScoring = nflExactScoring;
        this.letterGrades = letterGrades;
        this.seasonProjections = seasonProjections;
        this.gameScoring = gameScoring;
        this.preseason = preseason;
        this.leagues = leagues;
        this.rosterSeasons = rosterSeasons;
        this.weekPoints = weekPoints;
        this.projections = projections;
        this.players = players;
        this.managers = managers;
        this.matchupRepo = matchupRepo;
        this.sleeper = sleeper;
        this.rosterSource = new LeagueRosterSource(sleeper, leagues, rosterSeasons);
        this.rulesRegistry = rulesRegistry;
        this.members = members;
    }

    // ---- wire shapes ----

    public record Analysis(int season, String scoringKey, Window window,
                           RankingScores rankingScores, Projections projections, Matchups matchups,
                           Scores scores, List<TeamLabel> teams) {}

    /**
     * One roster's two names, so every block on the page can put the team name first and the
     * Sleeper username under it. A directory rather than a field on each of the four record
     * shapes that carry {@code manager}: it is present even when the ranking block is unavailable.
     * {@code teamName} falls back to the username, then "Roster N", exactly like the other league pages.
     */
    public record TeamLabel(int rosterId, String teamName, String username) {}

    /** The rest-of-season window, named so the page can say what it covered. */
    public record Window(int fromWeek, int toWeek, int weeks, int scoredWeeks) {}

    /**
     * {@code available} false carries {@code reason} and an empty list, rather
     * than a list of zeroes -- a ladder of twelve identical numbers reads as a
     * broken feature, not as "too early to say".
     */
    public record RankingScores(boolean available, String reason, String formula,
                                int weeksScored, int weeksRequired, List<ScoreEntry> entries,
                                /** Spec 013: weeksScored < SeasonWindow.EARLY_THRESHOLD_WEEKS. Grades are badged early while true. */
                                boolean gradesEarly,
                                /** Spec 013: SeasonWindow.EARLY_THRESHOLD_WEEKS, so the page can state the rule without a copy. */
                                int earlyThresholdWeeks) {}

    public record ScoreEntry(int rank, int rosterId, Long managerId, String manager, String avatarId,
                             double score, double raw, double avgWeekly, double high, double low,
                             double winPct, int wins, int losses, int ties,
                             /** Spec 013: letter for this rank, null when no cutoffs are configured. */
                             String grade) {}

    /**
     * @param reason      an English sentence, always, for the reader. Never a code.
     * @param reasonCode  the machine-readable twin of {@code reason} (spec 030, plan review S3), so the
     *                    client can show richer copy where it has some and falls back to the sentence;
     *                    null when {@code available}. One of {@code NOT_BUILT_FOR_SPORT},
     *                    {@code PROJECTIONS_NOT_LOADED}, {@code SCORING_NOT_LOADED}, {@code ROSTERS_EMPTY}, {@code UNSUPPORTED_SLOTS},
     *                    {@code NO_PLAYOFF_WEEK_START}, {@code SEASON_OVER}.
     * @param unit        what each number means: {@link #UNIT_REST_OF_SEASON} (football: points summed
     *                    over the weeks left) or {@link #UNIT_PER_WEEK} (basketball: one projected game per
     *                    starter, which is what Sleeper credits a week). The page labels every number with it.
     * @param basisNote   a code for a caveat the numbers carry, or null.
     * @param provenance  where the numbers came from; null when none are stored.
     * @param unprojectedCategories scored stat keys that appear on no stored projection line, sorted;
     *                    always present, empty for football.
     * @param staleAfterDays {@code draftsim.preseason.stale-after-days}, so the page can say a source is
     *                    N days old without a second copy of the number.
     * @param accuracy    spec 036: the measured accuracy of the pre-game projections, one line (the full
     *                    breakdown is {@code /projection-accuracy}); derived from the same computation as that
     *                    route. Null when unavailable or when the accuracy read itself failed -- a failure
     *                    there never takes the projections down.
     */
    public record Projections(boolean available, String reason, String reasonCode,
                              List<String> positionGroups, List<RosterProjection> rosters,
                              String unit, String basisNote, ProjectionProvenance provenance,
                              List<String> unprojectedCategories, int staleAfterDays,
                              ProjectionAccuracyService.AccuracySummary accuracy) {}

    public record ProjectionProvenance(String source, Instant sourceModifiedAt, Instant fetchedAt) {}

    public static final String UNIT_REST_OF_SEASON = "REST_OF_SEASON";
    public static final String UNIT_PER_WEEK = "PER_WEEK";
    public static final String BASIS_ONE_AVERAGE_GAME = "ONE_AVERAGE_GAME_PER_STARTER";

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
     * @param unavailable  rostered players set to 0 because Sleeper tags them Out, IR or Sus, wherever
     *                     they sit (spec 030, amended after live check 2026-10-12). Basketball only: a
     *                     per-game line has no games-played count to zero an injured star's weeks. They
     *                     appear in {@code bench} at 0 with slot "OUT" and are not counted in
     *                     {@code missing}. Day-to-day and game-time players are NOT here: this league has
     *                     IR+ (reserve_allow_dtd=1) and 11 of its 17 IR-slotted players were only DTD/GTD
     *                     (Giannis "DTD / Rest"), so the IR slot says nothing about availability. They
     *                     count in full, with no discount: one game a week is credited per starter, and
     *                     there is no injury history to fit an availability share from. Always 0 for
     *                     football, whose weekly projections already zero an injured week.
     */
    public record RosterProjection(int rosterId, Long managerId, String manager, String avatarId, int rank,
                                   boolean isMe, double total, Map<String, Double> byPosition,
                                   Map<String, Integer> rankByPosition, List<LineupPlayer> starters,
                                   List<LineupPlayer> bench, List<WeekTotal> byWeek, int missing,
                                   int unavailable) {}

    /**
     * One remaining week of one roster's projection
     * (claude/league-analysis-week-by-week.md).
     *
     * <p>The rest-of-season total is the sum of these, but the sum cannot be
     * taken apart again: a bye week is a hole in this list and is invisible in
     * the total, which is the whole reason the list exists.
     */
    public record WeekTotal(int week, double points, int rank) {}

    /**
     * One rostered player, valued over whichever window the pass ran on.
     *
     * @param slot         the roster slot filled -- a league slot name for a
     *                     starter ("QB", "FLEX"), and "BN" for the bench.
     * @param injuryStatus Sleeper's own tag as of the last player ingest, or
     *                     null. Carried because it is the explanation for a
     *                     zero: a bench line reading "A.J. Brown IR 0.0" says
     *                     what a count of "6 unprojected" cannot.
     * @param onReserve    the player sits in the roster's reserve (IR+) slot. True only for basketball;
     *                     a starter with this set is a day-to-day or untagged player parked on IR+.
     */
    public record LineupPlayer(String sleeperPlayerId, String name, String position, String team,
                               String slot, double points, String injuryStatus, boolean onReserve) {}

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

        WindowCalc calc = windowOf(league);

        // Both projection blocks come out of one call: they share every gate,
        // every lookup table and the roster list itself, and differ only in
        // which weeks they value. Computing them apart would fetch the same
        // twelve Sleeper rosters twice to answer one request.
        Blocks blocks = projectionBlocks(league, calc.key(), calc.window().fromWeek(), calc.window().toWeek(),
                calc.playoffWeekStart(), sleeperUserId,
                week == null ? calc.window().fromWeek() : week, true);

        return new Analysis(league.season(), calc.key().name(), calc.window(),
                rankingScores(league, calc.window().scoredWeeks(), calc.lastScored()),
                blocks.projections(), blocks.matchups(),
                scores(league, calc.lastScored(), sleeperUserId), teamLabels(league));
    }

    /** The rest-of-season window and what it was derived from, shared by {@link #analyse} and {@link #projectionsRead}. */
    private record WindowCalc(PlayerProjectionRepository.ScoringKey key, Window window, int lastScored,
                              int playoffWeekStart) {}

    private WindowCalc windowOf(LeagueRepository.LeagueRow league) {
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
        return new WindowCalc(key, window, lastScored, playoffWeekStart);
    }

    /** The projection block and the window it ran over (the favorite's per-week divisor for football). */
    public record ProjectionsRead(Projections projections, Window window) {}

    /**
     * Just the roster-projection block, for {@code SeasonStartService} (specs/030 T028). The SAME pass
     * {@link #analyse} runs, not a second lineup computation: same window, same gates, same availability rule.
     * The matchup preview is skipped because nothing here reads it.
     */
    public ProjectionsRead projectionsRead(LeagueRepository.LeagueRow league, String sleeperUserId) {
        WindowCalc calc = windowOf(league);
        Blocks blocks = projectionBlocks(league, calc.key(), calc.window().fromWeek(), calc.window().toWeek(),
                calc.playoffWeekStart(), sleeperUserId, calc.window().fromWeek(), false);
        return new ProjectionsRead(blocks.projections(), calc.window());
    }

    public List<TeamLabel> teamLabels(LeagueRepository.LeagueRow league) {
        Map<Long, String> teamNameByManager = new HashMap<>();
        for (LeagueMemberRepository.MemberRow m : members.forLeague(league.id())) {
            if (m.teamName() != null && !m.teamName().isBlank()) {
                teamNameByManager.put(m.managerId(), m.teamName());
            }
        }
        List<TeamLabel> out = new ArrayList<>();
        for (RosterSeasonRepository.StandingRow r : rosterSeasons.forLeague(league.id())) {
            String name = r.managerId() == null ? null : teamNameByManager.get(r.managerId());
            if (name == null) name = r.managerName();
            if (name == null || name.isBlank()) name = "Roster " + r.rosterId();
            out.add(new TeamLabel(r.rosterId(), name, r.managerName()));
        }
        return out;
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
            return new Scores(false, "No week of this season has loaded yet, so there is nothing to read"
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
        Long myManagerId = sleeperUserId == null ? null : ManagerRepository.caller(managers, league.id(), sleeperUserId);

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

    /* package-private so LeagueAnalysisServiceTest can pin the grade fields without a database. */
    RankingScores rankingScores(LeagueRepository.LeagueRow league, int weeksScored, int lastScored) {
        String formula = "((avgWeeklyScore * 6) + ((highScore + lowScore) * 2) + (winPct * 400)) / 10";
        if (weeksScored < MIN_SCORED_WEEKS) {
            String reason = weeksScored == 0
                    ? "No week of this season has loaded yet."
                    : weeksScored + " week" + (weeksScored == 1 ? "" : "s")
                            + " scored. The formula weighs a team's best and worst week against its average,"
                            + " which cannot mean anything until those are three different numbers;"
                            + " " + MIN_SCORED_WEEKS + " weeks are needed.";
            return new RankingScores(false, reason, formula, weeksScored, MIN_SCORED_WEEKS, List.of(),
                    SeasonWindow.isEarly(weeksScored), SeasonWindow.EARLY_THRESHOLD_WEEKS);
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
                    rec[0], rec[1], rec[2], null));
        }

        return new RankingScores(true, null, formula, weeksScored, MIN_SCORED_WEEKS, normalise(raw),
                SeasonWindow.isEarly(weeksScored), SeasonWindow.EARLY_THRESHOLD_WEEKS);
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
    List<ScoreEntry> normalise(List<ScoreEntry> raw) {
        if (raw.isEmpty()) return List.of();
        double mean = raw.stream().mapToDouble(ScoreEntry::raw).average().orElse(0);
        double maxDev = raw.stream().mapToDouble(e -> Math.abs(e.raw() - mean)).max().orElse(0);

        List<ScoreEntry> scored = new ArrayList<>();
        for (ScoreEntry e : raw) {
            double score = maxDev == 0 ? 50 : 50 + 50 * (e.raw() - mean) / maxDev;
            score = Math.max(1, Math.min(100, score));
            scored.add(new ScoreEntry(0, e.rosterId(), e.managerId(), e.manager(), e.avatarId(),
                    round(score, 1), round(e.raw(), 2), round(e.avgWeekly(), 2), round(e.high(), 2),
                    round(e.low(), 2), round(e.winPct(), 4), e.wins(), e.losses(), e.ties(), null));
        }
        List<ScoreEntry> ranked = new ArrayList<>();
        for (Ranker.Ranked<ScoreEntry> r : Ranker.rank(scored,
                Comparator.comparingDouble(ScoreEntry::score).reversed(), ScoreEntry::score)) {
            ScoreEntry e = r.item();
            ranked.add(new ScoreEntry(r.rank(), e.rosterId(), e.managerId(), e.manager(), e.avatarId(),
                    e.score(), e.raw(), e.avgWeekly(), e.high(), e.low(), e.winPct(),
                    e.wins(), e.losses(), e.ties(),
                    // Spec 013 T075: rank by composite score, tied ranks share a grade.
                    letterGrades.grade(r.rank(), scored.size())));
        }
        return ranked;
    }

    // ---- pieces 2, 3 and the matchup preview ----

    /** The two blocks that read projections, which share every gate below. */
    private record Blocks(Projections projections, Matchups matchups) {}

    private Blocks projectionBlocks(LeagueRepository.LeagueRow league,
                                    PlayerProjectionRepository.ScoringKey key,
                                    int fromWeek, int toWeek, int playoffWeekStart,
                                    String sleeperUserId, int matchupWeek, boolean withMatchups) {
        List<String> groups = Position.forSport(league.sport()).stream().map(Enum::name).toList();
        String unit = league.sport() == Sport.NFL ? UNIT_REST_OF_SEASON : UNIT_PER_WEEK;

        // Spec 035 FR-012 (code review B1): an ESPN league whose scoring has untranslated rules is stored with an
        // empty/partial scoring map, so league.ppr() is 0 and these blocks would project under STANDARD scoring.
        // Refuse before any window or projection gate, in the one sentence LeagueAvailability owns.
        Optional<LeagueAvailability.Reason> untranslated = LeagueAvailability.scoringUntranslated(league);
        if (untranslated.isPresent()) {
            return unavailable(groups, matchupWeek, unit, untranslated.get().code(), untranslated.get().message());
        }

        // Basketball's strength is per-game and values no window, so the football window gates do not apply
        // to it: its season is over when the league is complete (amended after code review, R6).
        if (league.sport() == Sport.NBA) {
            if (league.complete()) {
                return unavailable(groups, matchupWeek, unit, "SEASON_OVER",
                        "this league's season is complete, so there is nothing left to project");
            }
            return basketballBlocks(league, groups, sleeperUserId, matchupWeek, withMatchups);
        }
        if (playoffWeekStart < 2) {
            return unavailable(groups, matchupWeek, unit, "NO_PLAYOFF_WEEK_START",
                    "this league has no playoff_week_start, so there is no regular season to project to the end of");
        }
        if (fromWeek > toWeek) {
            return unavailable(groups, matchupWeek, unit, "SEASON_OVER",
                    "the regular season is over (weeks run to " + toWeek + ", and " + (fromWeek - 1)
                            + " are scored), so there is nothing left to project");
        }

        // Said before the cache is consulted, because for a sport whose
        // projections aren't ingested the cache will ALWAYS be empty and the "go and
        // ingest them" advice below would point at an endpoint that answers
        // 400. A reason the reader cannot act on is worse than no reason.
        if (league.sport() != Sport.NFL) {
            return unavailable(groups, matchupWeek, unit, "NOT_BUILT_FOR_SPORT",
                    projectionsNotBuiltReason(league.sport()));
        }

        Map<String, Double> restOfSeason = windowTotals(league, key, fromWeek, toWeek);
        if (restOfSeason.isEmpty()) {
            return unavailable(groups, matchupWeek, unit, "PROJECTIONS_NOT_LOADED",
                    noProjectionsStored(league, fromWeek, toWeek));
        }

        LineupPass pass = new LineupPass(league, groups, sleeperUserId);
        if (pass.allRostersEmpty()) {
            return unavailable(groups, matchupWeek, unit, "ROSTERS_EMPTY", ROSTERS_EMPTY_REASON);
        }
        List<RosterProjection> rosters = pass.over(restOfSeason);

        Map<Integer, Map<String, Double>> perWeek = windowPerWeek(league, key, fromWeek, toWeek);

        List<RosterProjection> ranked = rankAll(withWeekly(rosters, perWeek), groups);

        log.info("league analysis: league {} projected weeks {}-{} using {} over {} players",
                league.id(), fromWeek, toWeek, key.column(), restOfSeason.size());

        ProjectionProvenance provenance = projections
                .provenance(league.sport().code(), league.season(), fromWeek, toWeek)
                .map(p -> new ProjectionProvenance(p.source(), null, p.fetchedAt()))
                .orElse(null);
        Projections block = new Projections(true, null, null, groups, ranked, UNIT_REST_OF_SEASON, null,
                provenance, List.of(), preseason.staleAfterDays(), accuracySummary(league));
        return new Blocks(block, withMatchups
                ? matchupBlock(league, matchupWeek, pass,
                        () -> windowTotals(league, key, matchupWeek, matchupWeek))
                : null);
    }

    /**
     * The basketball path (specs/030 R1, R3, R4): the same lineup pass football uses, valued by each
     * player's projected PER-GAME line scored under THIS league's scoring through
     * {@link GameScoringService}, the one "what is a game worth" implementation. One game per starter,
     * never multiplied by games in a week, because Sleeper credits one game a week (measured 540/540).
     * No week-by-week series: the same per-game line applies to every week, and a flat series would
     * imply it was computed week by week.
     *
     * <p>Availability is Sleeper's injury tag, not the roster slot: Out/IR/Sus players are valued at 0
     * wherever they sit, and everyone else (including DTD/GTD players parked in the IR+ slot) is valued
     * in full. See {@link RosterProjection#unavailable()}.
     */
    private Blocks basketballBlocks(LeagueRepository.LeagueRow league, List<String> groups, String sleeperUserId,
                                    int matchupWeek, boolean withMatchups) {
        // BasketballRules hard-codes the nine starting slots and ignores roster_positions, so a league
        // with any other lineup would be ranked under rules it does not play (plan review N9).
        List<String> starting = league.rosterPositions().stream().filter(s -> !NON_STARTING_SLOTS.contains(s)).toList();
        // Compared as multisets: the same nine slots in another order are the same lineup (N3).
        if (!sortedCopy(starting).equals(sortedCopy(BASKETBALL_STARTING_SLOTS))) {
            return unavailable(groups, matchupWeek, UNIT_PER_WEEK, "UNSUPPORTED_SLOTS",
                    "This league's starting lineup (" + String.join(", ", starting) + ") isn't the nine-slot "
                            + "basketball lineup the lineup builder supports (" + String.join(", ", BASKETBALL_STARTING_SLOTS)
                            + "), so roster strength can't be worked out for it.");
        }

        Map<String, Double> scoring = leagues.scoringOf(league.id());
        Map<String, Map<String, Double>> lines = seasonProjections.statsByPlayer(league.sport().code(), league.season());
        if (lines.isEmpty()) {
            return unavailable(groups, matchupWeek, UNIT_PER_WEEK, "PROJECTIONS_NOT_LOADED",
                    "Season projections haven't loaded for the " + league.season()
                            + " season yet, so rosters can't be ranked.");
        }
        if (scoring.isEmpty()) {
            return unavailable(groups, matchupWeek, UNIT_PER_WEEK, "SCORING_NOT_LOADED",
                    "This league's scoring settings aren't stored, so projections can't be scored.");
        }

        Map<String, Double> pointsByPlayer = new HashMap<>();
        Set<String> projectedKeys = new HashSet<>();
        lines.forEach((playerId, line) -> {
            pointsByPlayer.put(playerId, gameScoring.score(scoring, line));
            projectedKeys.addAll(line.keySet());
        });
        // Scored keys carried by NO stored line, from the league's own scoring map -- never a hard-coded list.
        // A key on even one line is not listed (it is an implicit zero for everyone else, like dd and td).
        List<String> unprojected = ProjectedCategories.unprojected(scoring, projectedKeys);

        LineupPass pass = new LineupPass(league, groups, sleeperUserId);
        if (pass.allRostersEmpty()) {
            return unavailable(groups, matchupWeek, UNIT_PER_WEEK, "ROSTERS_EMPTY", ROSTERS_EMPTY_REASON);
        }
        List<RosterProjection> ranked = rankAll(pass.over(pointsByPlayer), groups);

        log.info("league analysis: league {} per-game projections over {} players, {} unprojected scored categories",
                league.id(), lines.size(), unprojected.size());

        ProjectionProvenance provenance = seasonProjections.provenance(league.sport().code(), league.season())
                .map(p -> new ProjectionProvenance(p.source(), p.sourceModifiedAt(), p.fetchedAt()))
                .orElse(null);
        Projections block = new Projections(true, null, null, groups, ranked, UNIT_PER_WEEK, BASIS_ONE_AVERAGE_GAME,
                provenance, unprojected, preseason.staleAfterDays(), accuracySummary(league));
        // The matchup preview is fed the SAME points map, so the home and the matchup cannot disagree.
        return new Blocks(block, withMatchups
                ? matchupBlock(league, matchupWeek, pass, () -> pointsByPlayer) : null);
    }

    /**
     * Football points per player over a window (spec 036 US3). With {@code draftsim.projections.nfl-exact-scoring}
     * OFF (the default) this is exactly the column pick it always was. With it ON, a row that carries a stat line
     * is valued by {@link GameScoringService} under THIS league's scoring, and a row without one (written before
     * V36) falls back to the column pick, row by row. The flag stays off until the parity check says it may flip
     * (specs/036 contracts/parity.md).
     */
    Map<String, Double> windowTotals(LeagueRepository.LeagueRow league, PlayerProjectionRepository.ScoringKey key,
                                     int fromWeek, int toWeek) {
        Map<String, Double> scoring = nflExactScoring ? leagues.scoringOf(league.id()) : Map.of();
        if (scoring.isEmpty()) {
            return projections.totalsByPlayer(league.sport().code(), league.season(), fromWeek, toWeek, key);
        }
        Map<String, Double> out = new HashMap<>();
        for (PlayerProjectionRepository.WeekLine l : projections.linesInWindow(
                league.sport().code(), league.season(), fromWeek, toWeek)) {
            out.merge(l.sleeperPlayerId(), exactValue(l, key, scoring), Double::sum);
        }
        return out;
    }

    /** {@link #windowTotals} kept week by week. */
    Map<Integer, Map<String, Double>> windowPerWeek(LeagueRepository.LeagueRow league,
                                                    PlayerProjectionRepository.ScoringKey key,
                                                    int fromWeek, int toWeek) {
        Map<String, Double> scoring = nflExactScoring ? leagues.scoringOf(league.id()) : Map.of();
        if (scoring.isEmpty()) {
            return projections.totalsByPlayerPerWeek(league.sport().code(), league.season(), fromWeek, toWeek, key);
        }
        Map<Integer, Map<String, Double>> byWeek = new LinkedHashMap<>();
        for (PlayerProjectionRepository.WeekLine l : projections.linesInWindow(
                league.sport().code(), league.season(), fromWeek, toWeek)) {
            byWeek.computeIfAbsent(l.week(), w -> new HashMap<>()).put(l.sleeperPlayerId(), exactValue(l, key, scoring));
        }
        return byWeek;
    }

    private double exactValue(PlayerProjectionRepository.WeekLine l, PlayerProjectionRepository.ScoringKey key,
                              Map<String, Double> scoring) {
        return l.stats() == null ? l.column(key) : gameScoring.score(scoring, l.stats());
    }

    /** Null on any failure: the accuracy line is an extra, and must never take the projections block down. */
    private ProjectionAccuracyService.AccuracySummary accuracySummary(LeagueRepository.LeagueRow league) {
        try {
            return accuracy.summary(league);
        } catch (RuntimeException e) {
            log.warn("league analysis: projection accuracy failed for league {}: {}", league.id(), e.toString());
            return null;
        }
    }

    private static List<String> sortedCopy(List<String> l) {
        List<String> c = new ArrayList<>(l);
        Collections.sort(c);
        return c;
    }

    /** Slots in {@code roster_positions} that are not starting slots. */
    private static final Set<String> NON_STARTING_SLOTS = Set.of("BN", "IR", "TAXI");

    /** The nine slots {@code BasketballRules} hard-codes, in Sleeper's order. */
    static final List<String> BASKETBALL_STARTING_SLOTS =
            List.of("PG", "SG", "G", "SF", "PF", "F", "C", "UTIL", "UTIL");

    private static final String ROSTERS_EMPTY_REASON =
            "No roster in this league has any players on it yet, so there is nothing to rank.";

    /**
     * One reason, both blocks. Every gate the matchup preview would apply for
     * itself is upstream of it, so restating them in its own words would be two
     * explanations of one fact, free to drift apart.
     */
    private Blocks unavailable(List<String> groups, int week, String unit, String reasonCode, String reason) {
        return new Blocks(new Projections(false, reason, reasonCode, groups, List.of(), unit, null, null, List.of(),
                preseason.staleAfterDays(), null),
                new Matchups(false, reason, week, List.of()));
    }

    /**
     * What a non-football league is told in place of roster projections
     * (specs/016). It says only what is true of this app -- projections are
     * not built for that sport yet -- and deliberately nothing about whether a
     * source exists upstream. That claim used to be made ("no nba equivalent")
     * and was false: Sleeper serves per-game NBA projections, measured
     * 2026-10-05 (claude/projection-tools.md). For any other sport it would be
     * unmeasured, and for basketball it goes stale the day they are wired up.
     */
    static String projectionsNotBuiltReason(Sport sport) {
        return "Roster projections aren't built for " + sport.code()
                + " leagues yet: so far this app only has football projections.";
    }

    private static String noProjectionsStored(LeagueRepository.LeagueRow league, int fromWeek, int toWeek) {
        return "Projections aren't available for weeks " + fromWeek + "–" + toWeek + ".";
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
    private Matchups matchupBlock(LeagueRepository.LeagueRow league, int week, LineupPass pass,
                                  java.util.function.Supplier<Map<String, Double>> weeklyPoints) {
        List<LeagueMatchupRepository.Fixture> fixtures =
                matchupRepo.between(league.id(), league.season(), week, week);
        if (fixtures.isEmpty()) {
            // Reachable, and not an error: Sleeper answers a week it has not
            // scheduled yet with every matchup_id null, and
            // LeagueMatchupRepository deliberately refuses to count that as
            // cached rather than poisoning the schedule forever.
            return new Matchups(false, "No pairings for week " + week
                    + " yet. Sleeper publishes a week's schedule shortly before it starts.", week, List.of());
        }

        // A supplier because football reads one week's rows from the DB only AFTER the fixtures check
        // above, while basketball hands over the per-game points it already has.
        Map<String, Double> weekly = weeklyPoints.get();
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
        // One week's points for every roster, then that week ranked across
        // them. The rank is a fact about the WEEK -- who was best in week 6 --
        // so it is computed down the column and not along a roster's own row.
        record Scored(int rosterId, double points) {}
        Map<Integer, List<WeekTotal>> seriesByRoster = new HashMap<>();

        List<Integer> weeks = new ArrayList<>(perWeek.keySet());
        Collections.sort(weeks);
        for (int week : weeks) {
            Map<String, Double> points = perWeek.get(week);
            List<Scored> scored = new ArrayList<>();
            for (RosterProjection p : rosters) {
                double total = 0;
                for (LineupPlayer starter : p.starters()) {
                    total += points.getOrDefault(starter.sleeperPlayerId(), 0.0);
                }
                scored.add(new Scored(p.rosterId(), round(total, 1)));
            }
            // Through Ranker like every other rank on this page: two rosters
            // projected to the same tenth of a point in a week must not be
            // numbered as though one were ahead. It exists because the second
            // copy of that rule was wrong.
            for (Ranker.Ranked<Scored> r : Ranker.rank(scored,
                    Comparator.comparingDouble(Scored::points).reversed(), Scored::points)) {
                seriesByRoster.computeIfAbsent(r.item().rosterId(), k -> new ArrayList<>())
                        .add(new WeekTotal(week, r.item().points(), r.rank()));
            }
        }

        List<RosterProjection> out = new ArrayList<>();
        for (RosterProjection p : rosters) {
            out.add(new RosterProjection(p.rosterId(), p.managerId(), p.manager(), p.avatarId(), p.rank(),
                    p.isMe(), p.total(), p.byPosition(), p.rankByPosition(), p.starters(), p.bench(),
                    List.copyOf(seriesByRoster.getOrDefault(p.rosterId(), List.of())), p.missing(), p.unavailable()));
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
        private final Long callerManagerId;
        private final boolean basketball;

        LineupPass(LeagueRepository.LeagueRow league, List<String> groups, String sleeperUserId) {
            this.groups = groups;
            this.basketball = league.sport() == Sport.NBA;
            // S4: a header shaped like another platform's key is no identity at all.
            this.sleeperUserId = LeagueMembership.isAnonymous(sleeperUserId) ? null : sleeperUserId;
            this.settings = LeagueRepository.toSettings(league, league.rosterPositions().size());
            this.rules = rulesRegistry.get(league.sport());
            this.slotOrder = league.rosterPositions();
            this.rosters = rosterSource.rosters(league);
            this.managerBySleeperUserId = managers.idsBySleeperUserId();
            // S6 (spec 035 FR-004): "you" is the manager who owns a roster in this league, which for an ESPN
            // league is the claimed link's manager, not the header string. Identical for Sleeper.
            this.callerManagerId = this.sleeperUserId == null ? null
                    : ManagerRepository.caller(managers, league.id(), this.sleeperUserId);
            players.findAll(league.sport()).forEach(p -> playerBySleeperId.put(p.sleeperId(), p));
            rosterSeasons.forLeague(league.id()).forEach(r -> {
                managerNames.put(r.rosterId(), r.managerName());
                avatars.put(r.rosterId(), r.avatarId());
            });
        }

        /** Unranked: ranking is the rest-of-season block's business, not the weekly one's. */
        List<RosterProjection> over(Map<String, Double> pointsByPlayer) {
            List<RosterInput> inputs = new ArrayList<>();
            for (Map<String, Object> roster : rosters) {
                int rosterId = asInt(roster.get("roster_id"));
                if (rosterId < 0) continue;
                Object ownerId = roster.get("owner_id");
                String owner = ownerId == null ? null : String.valueOf(ownerId);
                Long managerId = owner == null ? null : managerBySleeperUserId.get(owner);
                // Only basketball reads the reserve slot, and only to flag a starter parked there
                // (LineupPlayer#onReserve); availability comes from the injury tag (see
                // RosterProjection#unavailable). Football's weekly projections already zero an
                // injured week, so it carries no reserve at all.
                List<String> reserve = basketball ? ids(roster.get("reserve")) : List.of();
                inputs.add(new RosterInput(rosterId, owner, managerId, managerNames.get(rosterId),
                        avatars.get(rosterId), ids(roster.get("players")), reserve));
            }
            return projectRosters(inputs, playerBySleeperId, pointsByPlayer, rules, settings, groups,
                    slotOrder, sleeperUserId, callerManagerId);
        }

        /** Every roster has no players: Sleeper's list is empty, or the key is absent or JSON null. */
        boolean allRostersEmpty() {
            return rosters.stream().allMatch(r -> ids(r.get("players")).isEmpty());
        }

    }

    /** A Sleeper player-id list; a missing key or a JSON null is empty, not an error (plan review S9). */
    private static List<String> ids(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object e : list) if (e != null) out.add(String.valueOf(e));
        return out;
    }

    /**
     * One roster as the lineup core sees it: who owns it, what to call it, and who is on it.
     *
     * @param reserve ids in the roster's reserve (IR+) slot; used only to flag a starter as parked there.
     *                Empty for football. Sitting here does not make a player unavailable.
     */
    record RosterInput(int rosterId, String ownerId, Long managerId, String manager, String avatarId,
                       List<String> players, List<String> reserve) {}

    /**
     * The per-roster body of the lineup pass, extracted as a static so the preference ordering can be
     * tested without Sleeper or Postgres (specs/030 plan review S8). {@code LineupPass} supplies the
     * inputs; football's output through here is unchanged from before the extraction.
     *
     * <p>Unranked: {@link #rankAll} ranks, so a test runs the two back to back.
     */
    static List<RosterProjection> projectRosters(List<RosterInput> inputs, Map<String, Player> playerBySleeperId,
                                                 Map<String, Double> pointsByPlayer, SportRules rules,
                                                 LeagueSettings settings, List<String> groups,
                                                 List<String> slotOrder, String sleeperUserId) {
        return projectRosters(inputs, playerBySleeperId, pointsByPlayer, rules, settings, groups, slotOrder,
                sleeperUserId, null);
    }

    static List<RosterProjection> projectRosters(List<RosterInput> inputs, Map<String, Player> playerBySleeperId,
                                                 Map<String, Double> pointsByPlayer, SportRules rules,
                                                 LeagueSettings settings, List<String> groups,
                                                 List<String> slotOrder, String sleeperUserId, Long callerManagerId) {
        List<RosterProjection> out = new ArrayList<>();
        for (RosterInput in : inputs) {
            String owner = in.ownerId();
            // owner_id IS a Sleeper user id, which is exactly what the
            // caller's header carries, so "is this mine" is a string
            // comparison and not a manager lookup. A signed-out reader has
            // no id and therefore owns nothing, which is the right answer.
            // Spec 035 S6: an ESPN claimant owns no roster by owner_id; they are the linked manager.
            boolean isMe = (owner != null && owner.equals(sleeperUserId))
                    || (callerManagerId != null && callerManagerId.equals(in.managerId()));

            Set<String> reserve = new HashSet<>(in.reserve());
            boolean basketball = settings.sport() == Sport.NBA;

            RosterState state = new RosterState();
            List<Player> known = new ArrayList<>();
            List<Player> outPlayers = new ArrayList<>();
            int missing = 0;
            for (String sleeperPlayerId : in.players()) {
                Player p = playerBySleeperId.get(sleeperPlayerId);
                if (p == null) { missing++; continue; }
                // Basketball only: a player Sleeper tags Out/IR/Sus is set aside before anything is
                // valued, wherever he sits. He is not a candidate starter and not "missing" a
                // projection either -- he was left out on purpose.
                if (basketball && isUnavailable(p.injuryStatus())) { outPlayers.add(p); continue; }
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
                starters.add(lineupPlayer(p, group, a.slot(), a.value(), reserve.contains(p.sleeperId())));
            }
            starters.sort(byLineupCard(slotOrder));

            List<LineupPlayer> bench = new ArrayList<>();
            for (Player p : known) {
                if (started.contains(p.sleeperId())) continue;
                bench.add(lineupPlayer(p, p.primary().name(), "BN",
                        pointsByPlayer.getOrDefault(p.sleeperId(), 0.0), reserve.contains(p.sleeperId())));
            }
            // Out players are listed at 0 so the reader sees where an injured star went, not a hole.
            for (Player p : outPlayers) {
                bench.add(lineupPlayer(p, p.primary().name(), "OUT", 0.0, reserve.contains(p.sleeperId())));
            }
            bench.sort(Comparator.comparingDouble(LineupPlayer::points).reversed());

            byPosition.replaceAll((g, v) -> round(v, 1));
            out.add(new RosterProjection(in.rosterId(), in.managerId(), in.manager(),
                    in.avatarId(), 0, isMe, round(total, 1), byPosition, Map.of(),
                    List.copyOf(starters), List.copyOf(bench), List.of(), missing, outPlayers.size()));
        }
        return out;
    }

    private static LineupPlayer lineupPlayer(Player p, String group, String slot, double points,
                                             boolean onReserve) {
        return new LineupPlayer(p.sleeperId(), p.name(), group, p.team(), slot,
                round(points, 1), p.injuryStatus(), onReserve);
    }

    /** Sleeper's tags that mean he will not play: Out, IR, Sus. DTD/GTD/Questionable count in full. */
    static boolean isUnavailable(String injuryStatus) {
        if (injuryStatus == null) return false;
        String t = injuryStatus.trim();
        return t.equalsIgnoreCase("Out") || t.equalsIgnoreCase("IR") || t.equalsIgnoreCase("Sus");
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
    static List<RosterProjection> rankAll(List<RosterProjection> rosters, List<String> groups) {
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
                    p.bench(), p.byWeek(), p.missing(), p.unavailable()));
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
