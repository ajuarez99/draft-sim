package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.refresh.RefreshDecision;
import com.ballknowers.draftsim.store.LeagueCredentialRepository;
import com.ballknowers.draftsim.store.LeagueRefreshRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.RosterSeasonRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Which pages can this league honestly show?" (spec 035 FR-008, contracts/availability.md). The ONE
 * answer: {@code GET /api/leagues/{key}/availability} serializes {@link #of}, the server-side refusals
 * ({@code PlatformGuard} for the routes in {@link #ROUTES}) read the same {@link #of}, and the frontend
 * gate reads the same JSON. There is no second list of ESPN-unavailable pages.
 *
 * <p>Sleeper leagues answer {@code unavailable: {}} and are never refused (FR-013).
 *
 * <p><b>Two kinds of unavailable for an ESPN league.</b>
 * <ul>
 *   <li><i>Fixed</i>: the page is built on Sleeper-only inputs (the draft engine, Sleeper's per-game stat
 *       credit, Sleeper's NBA week calendar). {@code PLATFORM_NOT_SUPPORTED}.</li>
 *   <li><i>Data-driven</i>: the page could work, but this league's stored data cannot support it yet:
 *       scoring rules with no confirmed translation ({@code SCORING_UNTRANSLATED}), no per-player points
 *       for any week ({@code NO_PLAYER_POINTS}), NFL matchup periods spanning several weeks
 *       ({@code MULTI_WEEK_PERIODS}), a playoff format the odds model does not seed
 *       ({@code PLAYOFF_FORMAT_UNSUPPORTED}).</li>
 * </ul>
 */
@Component
public class LeagueAvailability {

    public static final String PLATFORM_NOT_SUPPORTED = "PLATFORM_NOT_SUPPORTED";
    public static final String SCORING_UNTRANSLATED = "SCORING_UNTRANSLATED";
    public static final String NO_PLAYER_POINTS = "NO_PLAYER_POINTS";
    public static final String MULTI_WEEK_PERIODS = "MULTI_WEEK_PERIODS";
    public static final String PLAYOFF_FORMAT_UNSUPPORTED = "PLAYOFF_FORMAT_UNSUPPORTED";

    /** What a page key governs: a code for machines and a sentence for the page. The sentence never carries ESPN data. */
    public record Reason(String code, String message) {}

    public record LastRefresh(Instant succeededAt, Instant failedAt, String failure) {}

    /**
     * @param canReplaceCookies true only for the person who stored this league's cookies (the one who may
     *                          replace or delete them); false in every other case, including every Sleeper
     *                          league. The page shows "replace cookies" from this, the server still enforces it.
     * @param unmatched the refused players named (FR-007's visible list); empty for Sleeper
     */
    public record View(String leagueKey, String platform, Map<String, Reason> unavailable, int unmatchedPlayers,
                       LastRefresh lastRefresh, boolean canReplaceCookies,
                       List<com.ballknowers.draftsim.store.PlatformPlayerLinkRepository.UnmatchedPlayer> unmatched) {}

    /**
     * Page key to the {@code /api/leagues/{key}/<first segment>} routes that key governs. THE list the
     * server refuses by; the keys with an empty list are drafts (guarded under {@code /api/drafts}) or
     * a part of a page that the page itself hides ({@code lineups}, {@code playoffOdds}).
     *
     * <p>{@code next-matchup} sits under {@code schedule}: it counts each starter's games in a week from
     * the sport-wide NBA calendar, and spec 017's one-credited-game rule is a Sleeper rule.
     */
    public static final Map<String, List<String>> ROUTES = orderedRoutes();

    private static Map<String, List<String>> orderedRoutes() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("draftRoom", List.of());
        m.put("draftGrades", List.of());
        // "projection-accuracy" (spec 036, merged in after 035) grades projections against league scoring.
        // "lock-ins" (spec 044) reads Sleeper's per-game credit; its service also answers NOT_SLEEPER on its own.
        m.put("stats", List.of("stats", "players", "player-spotlight", "player-trends", "projection-accuracy",
                "lock-ins"));
        m.put("schedule", List.of("schedule", "next-matchup"));
        m.put("superlatives", List.of("superlatives", "conduct-list"));
        // spec 048: the pot follows Sleeper's weekly scores; "weekly-pot" governs the settings sub-path too
        // (pageKeyOf matches the first segment).
        m.put("weeklyPot", List.of("weekly-pot"));
        m.put("weeklyReport", List.of("weekly-report"));
        m.put("expectedWins", List.of("expected-wins"));
        m.put("forecast", List.of("forecast"));
        m.put("recap", List.of("recap"));
        m.put("analysis", List.of("analysis"));
        // specs/042 US3 (review F12): the lineup check reads a roster's live `starters`, which ESPN rosters
        // do not carry here; "not available for ESPN leagues yet" rather than a service-only NO_STARTERS.
        m.put("lineupCheck", List.of("lineup-check"));
        m.put("powerBaseline", List.of());
        m.put("lineups", List.of());
        m.put("playoffOdds", List.of());
        return m;
    }

    /** Fixed for every ESPN league. */
    private static final Map<String, String> FIXED = orderedFixed();

    private static Map<String, String> orderedFixed() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("draftRoom", "Live and simulated drafts follow Sleeper drafts only.");
        m.put("draftGrades", "Draft grades are built from Sleeper drafts only.");
        m.put("stats", "Player stats, the stat calculator, the spotlight and trends follow Sleeper leagues only.");
        m.put("schedule", "The schedule grid and next-matchup game counts follow Sleeper leagues only.");
        m.put("superlatives", "Season superlatives follow Sleeper leagues only.");
        m.put("weeklyPot", "The weekly pot follows Sleeper leagues only.");
        m.put("weeklyReport", "The weekly report follows Sleeper leagues only.");
        m.put("expectedWins", "Expected wins follows Sleeper leagues only.");
        m.put("forecast", "The season forecast follows Sleeper leagues only.");
        m.put("recap", "The weekly recap follows Sleeper leagues only.");
        m.put("lineupCheck", "The lineup check is not available for ESPN leagues yet.");
        return m;
    }

    private final LeagueRepository leagues;
    private final RosterWeekPointsRepository weekPoints;
    private final RosterSeasonRepository rosterSeasons;
    private final LeagueRefreshRepository refreshes;
    private final LeagueCredentialRepository credentials;
    private final ManagerRepository managers;

    public LeagueAvailability(LeagueRepository leagues, RosterWeekPointsRepository weekPoints,
                              RosterSeasonRepository rosterSeasons, LeagueRefreshRepository refreshes,
                              LeagueCredentialRepository credentials, ManagerRepository managers) {
        this.credentials = credentials;
        this.managers = managers;
        this.leagues = leagues;
        this.weekPoints = weekPoints;
        this.rosterSeasons = rosterSeasons;
        this.refreshes = refreshes;
    }

    /** The league's availability. Sleeper: nothing unavailable and no further reads. */
    public View of(LeagueRepository.LeagueRow league) {
        return of(league, null);
    }

    /** As {@link #of(LeagueRepository.LeagueRow)}, with {@code canReplaceCookies} answered for this caller. */
    public View of(LeagueRepository.LeagueRow league, String callerSleeperUserId) {
        if (league.isSleeper()) {
            return new View(league.sleeperId(), "SLEEPER", new LinkedHashMap<>(), 0, null, false, List.of());
        }
        Map<String, Reason> out = new LinkedHashMap<>();
        FIXED.forEach((key, message) -> out.put(key, new Reason(PLATFORM_NOT_SUPPORTED, message)));

        Optional<Reason> untranslated = scoringUntranslated(league);
        if (untranslated.isPresent()) {
            // More specific than the fixed reason: the scoring is also what a future translation would fix.
            out.put("stats", untranslated.get());
            out.put("powerBaseline", untranslated.get());
        }
        if (!weekPoints.hasPlayerPoints(league.id())) {
            out.put("lineups", new Reason(NO_PLAYER_POINTS,
                    "Per-player points for this ESPN league's weeks have not been loaded, so lineups can't be shown."));
        }
        if (leagues.settingsFlag(league.id(), "espn_multi_week_periods")) {
            out.put("analysis", new Reason(MULTI_WEEK_PERIODS,
                    "This ESPN league's matchups span more than one NFL week, so week-by-week analysis can't be shown."));
        }
        boolean modelable = leagues.playoffFormat(league.id()).map(LeagueRepository.PlayoffFormat::modelable).orElse(false);
        if (!modelable) {
            out.put("playoffOdds", new Reason(PLAYOFF_FORMAT_UNSUPPORTED,
                    "Playoff odds aren't available: this ESPN league's playoff format isn't one Ball Knowers can seed."));
        }

        LeagueRefreshRepository.Row r = refreshes.find(league.id()).orElse(null);
        LastRefresh last = r == null ? null : new LastRefresh(r.lastSuccessAt(),
                // A failure is shown only while it is the latest word, the same rule the rail uses.
                RefreshDecision.failed(r) ? r.lastFailureAt() : null,
                RefreshDecision.failed(r) ? r.lastFailure() : null);
        boolean owner = false;
        if (!com.ballknowers.draftsim.store.LeagueMembership.isAnonymous(callerSleeperUserId)) {
            Long me = managers.idsBySleeperUserId().get(callerSleeperUserId);
            owner = me != null && credentials.owner(league.sport().code(), league.platformLeagueId())
                    .map(me::equals).orElse(false);
        }
        return new View(league.sleeperId(), "ESPN", out, rosterSeasons.unmatchedPlayerCount(league.id()), last, owner,
                rosterSeasons.unmatchedPlayers(league.id(), league.sport().code()));
    }

    /**
     * Non-empty for an ESPN league whose scoring has rules with no confirmed translation. The one place the
     * sentence lives, so the availability map, the preseason baseline and the season-start strength block
     * all say the same thing.
     */
    public static Optional<Reason> scoringUntranslated(LeagueRepository.LeagueRow league) {
        if (league.isSleeper() || league.scoringUntranslated() == null || league.scoringUntranslated().isEmpty()) {
            return Optional.empty();
        }
        List<String> items = league.scoringUntranslated();
        String shown = String.join(", ", items.subList(0, Math.min(8, items.size())));
        return Optional.of(new Reason(SCORING_UNTRANSLATED,
                "This league's ESPN scoring has rules Ball Knowers can't translate yet (" + shown
                        + (items.size() > 8 ? ", and " + (items.size() - 8) + " more" : "") + "), so anything built on scoring is withheld."));
    }

    /**
     * The page key whose routes include this path, where {@code path} is what follows
     * {@code /api/leagues/{key}/} (for example {@code players/123} or {@code weekly-report/0}).
     */
    public static Optional<String> pageKeyOf(String path) {
        if (path == null || path.isEmpty()) return Optional.empty();
        int slash = path.indexOf('/');
        String first = slash < 0 ? path : path.substring(0, slash);
        for (Map.Entry<String, List<String>> e : ROUTES.entrySet()) {
            if (e.getValue().contains(first)) return Optional.of(e.getKey());
        }
        return Optional.empty();
    }

    /** The refusal for this page on this league, empty when the league can show it. */
    public Optional<Reason> refusal(LeagueRepository.LeagueRow league, String pageKey) {
        if (league.isSleeper()) return Optional.empty();
        return Optional.ofNullable(of(league).unavailable().get(pageKey));
    }
}
