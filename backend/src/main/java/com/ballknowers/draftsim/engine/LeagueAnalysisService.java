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
    private final SleeperClient sleeper;
    private final SportRulesRegistry rulesRegistry;

    public LeagueAnalysisService(LeagueRepository leagues, RosterSeasonRepository rosterSeasons,
                                 RosterWeekPointsRepository weekPoints, PlayerProjectionRepository projections,
                                 PlayerRepository players, ManagerRepository managers,
                                 SleeperClient sleeper, SportRulesRegistry rulesRegistry) {
        this.leagues = leagues;
        this.rosterSeasons = rosterSeasons;
        this.weekPoints = weekPoints;
        this.projections = projections;
        this.players = players;
        this.managers = managers;
        this.sleeper = sleeper;
        this.rulesRegistry = rulesRegistry;
    }

    // ---- wire shapes ----

    public record Analysis(int season, String scoringKey, Window window,
                           RankingScores rankingScores, Projections projections) {}

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
     * @param missing      rostered players with no projection at all in the
     *                     window (IR, Out, PUP). Reported rather than hidden:
     *                     it is the honest explanation for a thin-looking bar.
     */
    public record RosterProjection(int rosterId, Long managerId, String manager, String avatarId, int rank,
                                   double total, Map<String, Double> byPosition,
                                   Map<String, Integer> rankByPosition, List<Starter> starters,
                                   int missing) {}

    public record Starter(String sleeperPlayerId, String name, String position, String slot, double points) {}

    // ---- entry point ----

    public Analysis analyse(String sleeperLeagueId) {
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

        return new Analysis(league.season(), key.name(), window,
                rankingScores(league, scored.size(), lastScored),
                rosterProjections(league, key, fromWeek, toWeek, playoffWeekStart));
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

    // ---- pieces 2 and 3 ----

    private Projections rosterProjections(LeagueRepository.LeagueRow league,
                                          PlayerProjectionRepository.ScoringKey key,
                                          int fromWeek, int toWeek, int playoffWeekStart) {
        List<String> groups = Position.forSport(league.sport()).stream().map(Enum::name).toList();

        if (playoffWeekStart < 2) {
            return new Projections(false,
                    "this league has no playoff_week_start, so there is no regular season to project to the end of",
                    groups, List.of());
        }
        if (fromWeek > toWeek) {
            return new Projections(false,
                    "the regular season is over (weeks run to " + toWeek + ", and " + (fromWeek - 1)
                            + " are scored), so there is nothing left to project",
                    groups, List.of());
        }

        // Said before the cache is consulted, because for a sport with no
        // projection source the cache will ALWAYS be empty and the "go and
        // ingest them" advice below would point at an endpoint that answers
        // 400. A reason the reader cannot act on is worse than no reason.
        if (league.sport() != Sport.NFL) {
            return new Projections(false,
                    "roster projections are football-only: the projection source wired up is Sleeper's"
                            + " own weekly points (pts_ppr and friends), which has no "
                            + league.sport().code() + " equivalent. See claude/league-analysis.md's non-goals.",
                    groups, List.of());
        }

        Map<String, Double> pointsByPlayer =
                projections.totalsByPlayer(league.sport().code(), league.season(), fromWeek, toWeek, key);
        if (pointsByPlayer.isEmpty()) {
            return new Projections(false,
                    "no projections stored for " + league.sport().code() + " " + league.season()
                            + " weeks " + fromWeek + "-" + toWeek + " -- run POST /api/ingest/projections?sport="
                            + league.sport().code() + "&season=" + league.season()
                            + "&fromWeek=" + fromWeek + "&toWeek=" + toWeek,
                    groups, List.of());
        }

        LeagueSettings settings = LeagueRepository.toSettings(league, league.rosterPositions().size());
        SportRules rules = rulesRegistry.get(league.sport());
        Map<String, Player> playerBySleeperId = new HashMap<>();
        players.findAll(league.sport()).forEach(p -> playerBySleeperId.put(p.sleeperId(), p));
        Map<String, Long> managerBySleeperUserId = managers.idsBySleeperUserId();
        Map<Integer, String> managerNames = new HashMap<>();
        Map<Integer, String> avatars = new HashMap<>();
        rosterSeasons.forLeague(league.id()).forEach(r -> {
            managerNames.put(r.rosterId(), r.managerName());
            avatars.put(r.rosterId(), r.avatarId());
        });

        List<RosterProjection> out = new ArrayList<>();
        for (Map<String, Object> roster : sleeper.rosters(league.sleeperId())) {
            int rosterId = asInt(roster.get("roster_id"));
            if (rosterId < 0) continue;
            Object ownerId = roster.get("owner_id");
            Long managerId = ownerId == null ? null : managerBySleeperUserId.get(String.valueOf(ownerId));

            @SuppressWarnings("unchecked")
            List<String> rosterPlayers = (List<String>) roster.getOrDefault("players", List.of());

            RosterState state = new RosterState();
            int missing = 0;
            for (String sleeperPlayerId : rosterPlayers) {
                Player p = playerBySleeperId.get(sleeperPlayerId);
                if (p == null) { missing++; continue; }
                if (!pointsByPlayer.containsKey(sleeperPlayerId)) missing++;
                // A synthetic board entry: this path values players by
                // projection, and a rostered player who is simply not on the
                // draft board (a waiver pickup, a rookie past the board's tail)
                // still scores points. ADP is a placeholder here and never
                // read -- startingLineup re-sorts by the supplied value
                // function precisely so this cannot matter.
                state.add(new BoardEntry(p, OFF_BOARD_ADP, 0));
            }

            List<SportRules.Assigned> lineup = rules.startingLineup(state, settings,
                    e -> pointsByPlayer.getOrDefault(e.player().sleeperId(), 0.0));

            Map<String, Double> byPosition = new LinkedHashMap<>();
            groups.forEach(g -> byPosition.put(g, 0.0));
            List<Starter> starters = new ArrayList<>();
            double total = 0;
            for (SportRules.Assigned a : lineup) {
                String group = a.entry().position().name();
                byPosition.merge(group, a.value(), Double::sum);
                total += a.value();
                starters.add(new Starter(a.entry().player().sleeperId(), a.entry().player().name(),
                        group, a.slot(), round(a.value(), 1)));
            }
            starters.sort(Comparator.comparingDouble(Starter::points).reversed());
            byPosition.replaceAll((g, v) -> round(v, 1));

            out.add(new RosterProjection(rosterId, managerId, managerNames.get(rosterId),
                    avatars.get(rosterId), 0, round(total, 1), byPosition, Map.of(), starters, missing));
        }

        log.info("league analysis: league {} projected weeks {}-{} using {} over {} players",
                league.id(), fromWeek, toWeek, key.column(), pointsByPlayer.size());
        return new Projections(true, null, groups, rankAll(out, groups));
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
                    p.total(), p.byPosition(), rankByRoster.get(p.rosterId()), p.starters(), p.missing()));
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
