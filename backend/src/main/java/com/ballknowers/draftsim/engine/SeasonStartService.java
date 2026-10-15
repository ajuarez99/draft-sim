package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.PreseasonProperties;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.engine.LeagueAnalysisService.LineupPlayer;
import com.ballknowers.draftsim.engine.LeagueAnalysisService.ProjectionProvenance;
import com.ballknowers.draftsim.engine.LeagueAnalysisService.Projections;
import com.ballknowers.draftsim.engine.LeagueAnalysisService.RosterProjection;
import com.ballknowers.draftsim.engine.SeasonStateService.SeasonState;
import com.ballknowers.draftsim.ingest.SleeperClient;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the league home needs to know about where the season is (specs/030-post-draft-season-view,
 * contracts/wire-final.md section 3): the state ({@link SeasonStateService}, the one implementation of
 * the rule), and, only in the window between the draft and the first final week, the first week's date,
 * the projected strength ranking with its favorite, and which starters have no game that week.
 *
 * <p><b>Per-state work is deliberate</b> (plan review S2): {@code firstWeek} and {@code strength} are
 * computed only in {@code DRAFTED_UNSCORED}, so the live in-season football league does not pay a
 * second lineup pass and Sleeper roster fetch on every home load. {@code draft.grades} is read only in
 * {@code IN_SEASON}, because grades are made of realized production and do not exist before a week is
 * final (research R6).
 *
 * <p>The strength block reuses {@link LeagueAnalysisService#projectionsRead}: the same pass the Analysis
 * page runs, so the home and the page cannot disagree and there is no second implementation of "best
 * lineup". Display names come from {@link LeagueAnalysisService#teamLabels}, the same lookup the Analysis page uses.
 */
@Service
public class SeasonStartService {

    private static final Logger log = LoggerFactory.getLogger(SeasonStartService.class);

    /** Strength rows shown on the home; the rest are one tap away on the Analysis page. */
    static final int TOP_ROWS = 3;

    // ---- wire shapes (contracts/wire-final.md section 3) ----

    public record SeasonStart(String sport, int season, String state, FirstWeek firstWeek,
                              SeasonStrength strength, SeasonDraft draft) {}

    /** @param startDate "YYYY-MM-DD" or null; @param startersWithoutGame null for football or when the schedule is not loaded */
    public record FirstWeek(Integer week, String startDate, StartersWithoutGame startersWithoutGame) {}

    /**
     * Starters (from the projected best lineup) whose NBA team has no game in {@code week}. Under
     * one-credited-game a count of games would mislead; a starter with none scores nothing.
     *
     * @param mine          the caller's idle starters; null (not empty) when the caller has no roster or no
     *                      projected starters, so the client says nothing rather than "all your starters play"
     *                      (amended after code review, N6)
     * @param countByRoster rosterId to how many of that roster's starters are idle
     */
    public record StartersWithoutGame(int week, List<IdleStarter> mine, Map<Integer, Integer> countByRoster) {}

    public record IdleStarter(String sleeperPlayerId, String name, String team) {}

    /**
     * @param top first {@value #TOP_ROWS} rows with {@code starters} and {@code bench} emptied
     * @param me  the caller's row when it is not in {@code top}, else null
     * @param teamLabels every roster's display name (team name, else username, else "Roster N"), the same
     *                   lookup the Analysis page uses; empty when unavailable (amended after code review, N4)
     * @param accuracy   spec 036 (plan S3): the SAME {@code Projections.accuracy} object, copied and never
     *                   recomputed, so this block and the Analysis page cannot disagree; null on a failed pass
     */
    public record SeasonStrength(boolean available, String reason, String reasonCode, String unit,
                                 List<RosterProjection> top, RosterProjection me, int teams, Favorite favorite,
                                 ProjectionProvenance provenance, List<String> unprojectedCategories,
                                 String basisNote, int staleAfterDays,
                                 List<LeagueAnalysisService.TeamLabel> teamLabels,
                                 ProjectionAccuracyService.AccuracySummary accuracy) {}

    /**
     * {@code nextTotal} and {@code marginPoints} are null when every roster is a leader; {@code marginPct} is
     * also null when the next roster's total is zero or less (the gap is infinite).
     */
    public record Favorite(List<Leader> leaders, boolean tie, double leaderTotal, Double nextTotal,
                           Double marginPoints, Double marginPct, double minGapPct) {}

    public record Leader(int rosterId, String teamName, String username, String avatarId, double total) {}

    public record SeasonDraft(String sleeperDraftId, DraftGradeSummary grades) {}

    public record DraftGradeSummary(TeamGradeLite best, TeamGradeLite worst, boolean gradesEarly,
                                    int earlyThresholdWeeks) {}

    public record TeamGradeLite(String manager, String avatarId, String grade, Integer rank) {}

    // ---- collaborators ----

    private final LeagueRepository leagues;
    private final SeasonStateService seasonState;
    private final ScheduleGridService schedule;
    private final SleeperClient sleeper;
    private final LeagueAnalysisService analysis;
    private final DraftRepository drafts;
    private final DraftGradesService draftGrades;
    private final ScoredWeeks scoredWeeks;
    private final PreseasonProperties preseason;

    public SeasonStartService(LeagueRepository leagues, SeasonStateService seasonState,
                              ScheduleGridService schedule, SleeperClient sleeper,
                              LeagueAnalysisService analysis, DraftRepository drafts,
                              DraftGradesService draftGrades, ScoredWeeks scoredWeeks,
                              PreseasonProperties preseason) {
        this.leagues = leagues;
        this.seasonState = seasonState;
        this.schedule = schedule;
        this.sleeper = sleeper;
        this.analysis = analysis;
        this.drafts = drafts;
        this.draftGrades = draftGrades;
        this.scoredWeeks = scoredWeeks;
        this.preseason = preseason;
    }

    /** Empty for an unknown league; the caller's visibility is the controller's check. */
    public Optional<SeasonStart> read(String sleeperLeagueId, String sleeperUserId) {
        Optional<LeagueRepository.LeagueRow> found = leagues.bySleeperId(sleeperLeagueId);
        if (found.isEmpty()) return Optional.empty();
        LeagueRepository.LeagueRow league = found.get();
        SeasonState state = seasonState.stateOf(league);

        FirstWeek firstWeek = null;
        SeasonStrength strength = null;
        if (state == SeasonState.DRAFTED_UNSCORED) {
            LeagueAnalysisService.ProjectionsRead read = null;
            Optional<LeagueAvailability.Reason> untranslated = LeagueAvailability.scoringUntranslated(league);
            if (untranslated.isPresent()) {
                // Spec 035 F16: ESPN scoring we could not translate would read as plain standard scoring here.
                strength = new SeasonStrength(false, untranslated.get().message(), untranslated.get().code(),
                        unitOf(league), List.of(), null, 0, null, null, List.of(), null, preseason.staleAfterDays(),
                        List.of(), null);
            } else try {
                read = analysis.projectionsRead(league, sleeperUserId);
                strength = strength(league, read);
            } catch (RuntimeException e) {
                // A Sleeper or DB failure in the lineup pass must not take the state down with it (R2).
                log.warn("season start: strength pass failed for league {}: {}", league.sleeperId(), e.toString());
                read = null;
                strength = new SeasonStrength(false,
                        "Roster strength couldn't be worked out just now. Try again in a minute.",
                        "STRENGTH_FAILED", unitOf(league), List.of(), null, 0, null, null, List.of(), null,
                        preseason.staleAfterDays(), List.of(), null);
            }
            firstWeek = firstWeek(league, read == null ? null : read.projections());
        }

        Optional<DraftRepository.DraftRow> draft = drafts.forLeague(league.id());
        DraftGradeSummary grades = null;
        if (state == SeasonState.IN_SEASON) {
            try {
                grades = draft.map(this::gradeSummary).orElse(null);
            } catch (RuntimeException e) {
                log.warn("season start: draft grades failed for league {}: {}", league.sleeperId(), e.toString());
            }
        }
        SeasonDraft seasonDraft = new SeasonDraft(draft.map(DraftRepository.DraftRow::sleeperDraftId).orElse(null), grades);

        return Optional.of(new SeasonStart(league.sport().code(), league.season(), state.name(),
                firstWeek, strength, seasonDraft));
    }

    // ---- first week ----

    private static String unitOf(LeagueRepository.LeagueRow league) {
        return league.sport() == Sport.NFL ? LeagueAnalysisService.UNIT_REST_OF_SEASON : LeagueAnalysisService.UNIT_PER_WEEK;
    }

    /**
     * The first week is the one after the latest FINAL week (week 1 in {@code DRAFTED_UNSCORED}), not Sleeper's
     * current leg, which can move before week 1 is final (amended after code review, R7).
     */
    private FirstWeek firstWeek(LeagueRepository.LeagueRow league, Projections projections) {
        Integer week = scoredWeeks.of(league.id()).latestFinal() + 1;
        Optional<ScheduleGridService.Result> grid = schedule.forLeague(league.sleeperId());

        // The date comes from the same grid the schedule page draws (week `leg`'s first date), so the two
        // cannot disagree. Sleeper's sport state is the fallback, and only when it is about THIS season:
        // its previous_season is 2025 and would answer for the wrong year (plan review N5).
        String startDate = null;
        if (grid.isPresent()) {
            startDate = grid.get().weeks().stream().filter(w -> w.week() == week).map(ScheduleGridService.Week::firstDate)
                    .filter(d -> d != null).findFirst().map(LocalDate::toString).orElse(null);
        }
        if (startDate == null) startDate = stateStartDate(league);

        StartersWithoutGame idle = null;
        if (projections != null && league.sport() == Sport.NBA && grid.isPresent()) {
            idle = startersWithoutGame(grid.get(), week, projections);
        }
        return new FirstWeek(week, startDate, idle);
    }

    private String stateStartDate(LeagueRepository.LeagueRow league) {
        // Spec 035: an ESPN league has no Sleeper state to read; the start date is simply unknown.
        if (!league.isSleeper()) return null;
        try {
            Map<String, Object> state = sleeper.state(league.sport().code());
            if (state == null) return null;
            if (!String.valueOf(league.season()).equals(String.valueOf(state.get("league_season")))) return null;
            Object d = state.get("season_start_date");
            return d == null ? null : String.valueOf(d);
        } catch (RuntimeException e) {
            // A date the page can live without must not fail the whole read.
            log.warn("season start: sport state unavailable for {}: {}", league.sport().code(), e.toString());
            return null;
        }
    }

    private static StartersWithoutGame startersWithoutGame(ScheduleGridService.Result grid, int week,
                                                           Projections projections) {
        if (!projections.available()) return null;
        List<String> teams = new ArrayList<>();
        for (RosterProjection r : projections.rosters()) {
            for (LineupPlayer s : r.starters()) teams.add(s.team());
        }
        Optional<Set<String>> idle = ScheduleGridService.teamsWithoutGame(grid, week, teams);
        if (idle.isEmpty()) return null;

        List<IdleStarter> mine = new ArrayList<>();
        boolean haveMine = false;
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (RosterProjection r : projections.rosters()) {
            int n = 0;
            if (r.isMe() && !r.starters().isEmpty()) haveMine = true;
            for (LineupPlayer s : r.starters()) {
                if (s.team() == null || !idle.get().contains(s.team())) continue;
                n++;
                if (r.isMe()) mine.add(new IdleStarter(s.sleeperPlayerId(), s.name(), s.team()));
            }
            counts.put(r.rosterId(), n);
        }
        return new StartersWithoutGame(week, haveMine ? List.copyOf(mine) : null, counts);
    }

    // ---- strength and favorite ----

    private SeasonStrength strength(LeagueRepository.LeagueRow league, LeagueAnalysisService.ProjectionsRead read) {
        Projections p = read.projections();
        if (!p.available()) {
            return new SeasonStrength(false, p.reason(), p.reasonCode(), p.unit(), List.of(), null, 0, null,
                    p.provenance(), p.unprojectedCategories(), p.basisNote(), p.staleAfterDays(), List.of(),
                    p.accuracy());
        }
        List<RosterProjection> ranked = p.rosters();
        List<RosterProjection> top = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_ROWS, ranked.size()); i++) top.add(trimmed(ranked.get(i)));
        RosterProjection me = null;
        for (int i = TOP_ROWS; i < ranked.size(); i++) {
            if (ranked.get(i).isMe()) { me = trimmed(ranked.get(i)); break; }
        }

        int weeksForPerWeek = LeagueAnalysisService.UNIT_PER_WEEK.equals(p.unit())
                ? 1 : Math.max(1, read.window().weeks());
        List<LeagueAnalysisService.TeamLabel> labels = analysis.teamLabels(league);
        Favorite favorite = withTeamNames(favorite(ranked, preseason.favoriteMinGapPct(), weeksForPerWeek), labels);

        return new SeasonStrength(true, null, null, p.unit(), List.copyOf(top), me, ranked.size(), favorite,
                p.provenance(), p.unprojectedCategories(), p.basisNote(), p.staleAfterDays(), labels,
                p.accuracy());
    }

    /** Lineups are the Analysis page's business; the home's rows carry empty lists, never null (N11). */
    private static RosterProjection trimmed(RosterProjection r) {
        return new RosterProjection(r.rosterId(), r.managerId(), r.manager(), r.avatarId(), r.rank(), r.isMe(),
                r.total(), r.byPosition(), r.rankByPosition(), List.of(), List.of(), List.of(), r.missing(),
                r.unavailable());
    }

    /**
     * Who the projections name as the strongest roster, and by how much (wire-final section 4). Pure.
     *
     * <p>One denominator throughout: {@code gapPct(a, b) = (a - b) / b * 100} with {@code b} the lower
     * total, and infinite when {@code b <= 0} (unless the two are equal, which is a zero gap). The leaders
     * are rank 1 plus every roster whose gap below it is under {@code minGapPct}: a margin the projections
     * cannot resolve is reported as a tie, not as a favorite. {@code marginPoints} runs from rank 1 to the
     * first non-leader, per week ({@code weeksForPerWeek} is 1 for basketball and the window's week count
     * for football, whose totals are rest-of-season).
     *
     * @return null for fewer than two rosters
     */
    static Favorite favorite(List<RosterProjection> ranked, double minGapPct, int weeksForPerWeek) {
        if (ranked == null || ranked.size() < 2) return null;
        List<RosterProjection> byTotal = new ArrayList<>(ranked);
        byTotal.sort(Comparator.comparingDouble(RosterProjection::total).reversed());

        double t1 = byTotal.getFirst().total();
        List<Leader> leaders = new ArrayList<>();
        int i = 0;
        for (; i < byTotal.size(); i++) {
            RosterProjection r = byTotal.get(i);
            if (i > 0 && gapPct(t1, r.total()) >= minGapPct) break;
            leaders.add(new Leader(r.rosterId(), null, r.manager(), r.avatarId(), r.total()));
        }
        RosterProjection next = i < byTotal.size() ? byTotal.get(i) : null;
        if (next == null) {
            return new Favorite(List.copyOf(leaders), true, t1, null, null, null, minGapPct);
        }
        double gap = gapPct(t1, next.total());
        Double marginPct = Double.isInfinite(gap) ? null : round1(gap);
        double perWeek = (t1 - next.total()) / Math.max(1, weeksForPerWeek);
        return new Favorite(List.copyOf(leaders), leaders.size() > 1, t1, next.total(), round1(perWeek),
                marginPct, minGapPct);
    }

    private static double gapPct(double a, double b) {
        if (a == b) return 0.0;
        if (b <= 0) return Double.POSITIVE_INFINITY;
        return (a - b) / b * 100.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** The pure helper carries usernames only; the display name comes from the shared team-label lookup. */
    private static Favorite withTeamNames(Favorite f, List<LeagueAnalysisService.TeamLabel> labels) {
        if (f == null) return null;
        Map<Integer, String> nameByRoster = new LinkedHashMap<>();
        for (LeagueAnalysisService.TeamLabel l : labels) nameByRoster.put(l.rosterId(), l.teamName());
        List<Leader> named = new ArrayList<>();
        for (Leader l : f.leaders()) {
            named.add(new Leader(l.rosterId(), nameByRoster.get(l.rosterId()), l.username(), l.avatarId(), l.total()));
        }
        return new Favorite(List.copyOf(named), f.tie(), f.leaderTotal(), f.nextTotal(), f.marginPoints(),
                f.marginPct(), f.minGapPct());
    }

    // ---- draft grades (IN_SEASON only) ----

    private DraftGradeSummary gradeSummary(DraftRepository.DraftRow draft) {
        DraftGradesService.DraftGrades g = draftGrades.read(draft);
        if (!g.available()) return null;
        List<DraftGradesService.TeamGrade> ranked = g.teams().stream().filter(t -> t.rank() != null).toList();
        if (ranked.isEmpty()) return null;
        int best = ranked.stream().mapToInt(DraftGradesService.TeamGrade::rank).min().getAsInt();
        int worst = ranked.stream().mapToInt(DraftGradesService.TeamGrade::rank).max().getAsInt();
        DraftGradesService.TeamGrade b = ranked.stream().filter(t -> t.rank() == best).findFirst().orElseThrow();
        DraftGradesService.TeamGrade w = ranked.stream().filter(t -> t.rank() == worst).reduce((x, y) -> y).orElseThrow();
        return new DraftGradeSummary(lite(b), lite(w), g.gradesEarly(), g.earlyThresholdWeeks());
    }

    private static TeamGradeLite lite(DraftGradesService.TeamGrade t) {
        return new TeamGradeLite(t.manager(), t.avatarId(), t.grade(), t.rank());
    }
}
