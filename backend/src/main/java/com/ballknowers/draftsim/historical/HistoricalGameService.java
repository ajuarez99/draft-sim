package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.HistoricalProperties;
import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.DraftSlot;
import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.domain.RosterState;
import com.ballknowers.draftsim.engine.DraftContext;
import com.ballknowers.draftsim.engine.DraftContextFactory;
import com.ballknowers.draftsim.engine.MockDraftEngine;
import com.ballknowers.draftsim.engine.SeatSpec;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import com.ballknowers.draftsim.historical.HistoricalGameRepository.GameRow;
import com.ballknowers.draftsim.historical.HistoricalGameRepository.PickRow;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.sport.SportRules;
import com.ballknowers.draftsim.sport.SportRulesRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The historical draft game's flow (spec 053): create a game on a random past season, take the user's picks,
 * run the bots, and replay the real season at the last pick. Sport-agnostic since spec 056: every league shape,
 * scoring and data read goes through the game row's {@link HistoricalSport}, never a literal.
 *
 * <p>Modelled on {@code MockDraftService}: every mutating call is one transaction under one
 * {@code lockForUpdate}, and the draft state is rebuilt from the stored rows on every call. Differences that
 * matter:
 * <ul>
 *   <li>The BOARD is a snapshot taken at creation (plan F9). Every call rebuilds its {@code BoardEntry}s from
 *       {@code historical_game.board} and never reads the reference tables for the draft, so a re-import
 *       cannot change a game in progress.
 *   <li>Every seat but the user's is an unmodelled bot with a neutral profile and UNIFORM positional priors
 *       (research R9): fitted priors describe a 2024-26 league, not 1985.
 *   <li>Turn, myPicks and 409 checks all use {@link DraftSlot} with the game's stored {@code reversal_round}
 *       (F8).
 *   <li>Pick, auto and abandon require status DRAFTING under the row lock (F10); the replay runs only on the
 *       locked DRAFTING row at the final pick, in the same transaction.
 * </ul>
 */
@Service
public class HistoricalGameService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalGameService.class);
    private static final TypeReference<List<HistoricalPlayerCard>> CARDS = new TypeReference<>() {};
    private static final TypeReference<LinkedHashMap<String, Double>> SCORING = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Double>> DOUBLE_MAP = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    /** What an auto action covers: one pick, or every remaining pick. */
    public enum Scope { PICK, FINISH }

    private final HistoricalDataRepository data;
    private final HistoricalGameRepository games;
    private final DraftContextFactory contexts;
    private final MockDraftEngine engine;
    private final SportRulesRegistry rulesRegistry;
    private final SeasonReplay replay;
    private final HistoricalSports sports;
    private final ObjectMapper json;
    private final HistoricalProperties props;
    private final Clock clock;
    private final TransactionTemplate claimTx;
    private final SecureRandom random = new SecureRandom();

    public HistoricalGameService(HistoricalDataRepository data, HistoricalGameRepository games,
                                 DraftContextFactory contexts, MockDraftEngine engine,
                                 SportRulesRegistry rulesRegistry, SeasonReplay replay,
                                 HistoricalSports sports, ObjectMapper json, HistoricalProperties props, Clock clock,
                                 PlatformTransactionManager txManager) {
        this.data = data;
        this.games = games;
        this.contexts = contexts;
        this.engine = engine;
        this.rulesRegistry = rulesRegistry;
        this.replay = replay;
        this.sports = sports;
        this.json = json;
        this.props = props;
        this.clock = clock;
        this.claimTx = new TransactionTemplate(txManager);
    }

    // ---- create -----------------------------------------------------------------------------------------

    /**
     * Starts a game on a random offered season of the given sport (weighted by the sport's {@link SeasonSkew},
     * which is a flat draw unless configured), in a random slot unless one is given, and
     * advances the bots to the user's first turn.
     *
     * @param sportCode      "NBA" or "NFL"; anything else is an {@link IllegalArgumentException} (400). No default.
     * @param userSlot       1..teams, or null for random
     * @param seasonOverride a specific offered season, for tests and tooling; the HTTP API never passes one
     */
    @Transactional
    public HistoricalGameState create(String sleeperUserId, String sportCode, Integer userSlot,
                                      Integer seasonOverride) {
        return create(sleeperUserId, sportCode, userSlot, seasonOverride, null, null, false);
    }

    /**
     * The HTTP create (spec 074): the owner key may be a guest's, and the player may pick the season.
     *
     * <ul>
     *   <li>{@code seasonStart == null}: a random season. Refused to a guest ({@link GuestLimitException}).
     *   <li>{@code seasonStart != null}: that season, stored with {@code chosen_season = true}, so it never counts
     *       toward achievements or records. It must be OFFERED: committed with prior-season lines (the same rule as
     *       the season list) AND inside the sport's offered range, which a season imported for tooling may not be
     *       (PR-16). A guest may do this only while they own no game that is not a game of the day, in any status.
     * </ul>
     *
     * @throws IllegalArgumentException unknown sport, a bad slot, or a season that is not offered (400)
     * @throws GuestLimitException      a guest asking for a random season, or for a second chosen one (403)
     */
    @Transactional
    public HistoricalGameState createFor(String ownerKey, String sportCode, Integer userSlot, Integer seasonStart) {
        HistoricalSport sport = sports.get(sportCode);
        boolean guest = Identity.isGuest(ownerKey);
        if (seasonStart == null) {
            if (guest) {
                throw new GuestLimitException("Sign in to draft a random season. You can play the game of the day,"
                        + " or one season you choose.");
            }
            return create(ownerKey, sportCode, userSlot, null, null, null, false);
        }
        boolean offered = seasonStart >= sport.firstOffered() && seasonStart <= sport.lastOffered()
                && sport.offeredSeasons().stream().anyMatch(s -> s.seasonStart() == seasonStart);
        if (!offered) {
            throw new IllegalArgumentException("season " + seasonStart + " is not offered for " + sport.code());
        }
        if (guest) {
            // The count and the insert must not interleave with the same guest's other tab.
            games.lockOwner(ownerKey);
            if (games.countNonDaily(ownerKey) >= props.guestChosenSeasons()) {
                throw new GuestLimitException("You've used your free season draft. Sign in with your Sleeper username to"
                        + " draft any season, or play today's game.");
            }
        }
        return create(ownerKey, sportCode, userSlot, seasonStart, null, null, true);
    }

    /**
     * Moves a guest's games to the Sleeper id they just signed in as (spec 074). One transaction: it takes the
     * target's owner lock, locks the guest's rows, then moves every one that does not collide with a game of the day
     * the Sleeper id already played, and counts the rest. A second call moves nothing. A unique-index race (the
     * Sleeper id starting that day's game in another tab at the same instant) rolls that attempt back and is run
     * again, where the collision is now visible; it is never a 500 (PR-18).
     *
     * <p>Known property, accepted (CR-02, specs/074-redraft-seo/code-review.md): a guest id costs nothing, so the
     * game of the day can be replayed under throwaway guest ids before signing in, and the best attempt claimed. There
     * is no leaderboard, so nobody is ranked against it; the decision is recorded in that file and the behaviour is
     * deliberately unchanged.
     */
    public ClaimResult claim(String guestKey, String sleeperId) {
        if (!Identity.isGuest(guestKey) || sleeperId == null || sleeperId.isBlank() || Identity.isGuest(sleeperId)) {
            throw new IllegalArgumentException("claim needs a guest key and a Sleeper id");
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return claimTx.execute(status -> {
                    games.lockOwner(sleeperId);
                    games.lockOwnedGames(guestKey);
                    int moved = games.moveOwner(guestKey, sleeperId);
                    return new ClaimResult(moved, games.countOwned(guestKey));
                });
            } catch (DuplicateKeyException race) {
                // The guest key is a bearer credential (CR-11): log a short hash of it, never the key itself.
                log.info("historical claim {} -> {} lost a race on the daily index (attempt {}); running it again",
                        Identity.shortHash(guestKey), sleeperId, attempt);
            }
        }
        // Three losses in a row cannot be a race any more. Report what is true now, without a write.
        return new ClaimResult(0, games.countOwned(guestKey));
    }

    /**
     * The game of the day (spec 071): the day's dealt season and seed, a random slot, and the puzzle date stamped
     * on the row. A second one for the same user, sport and day throws
     * {@link org.springframework.dao.DuplicateKeyException} (the unique index), which rolls this transaction back.
     */
    @Transactional
    public HistoricalGameState createDaily(String sleeperUserId, String sportCode, int seasonStart, long seed,
                                           LocalDate dailyDate) {
        if (dailyDate == null) throw new IllegalArgumentException("dailyDate is required for a daily game");
        return create(sleeperUserId, sportCode, null, seasonStart, seed, dailyDate, false);
    }

    /**
     * @param chosenSeason true only when the player picked the season ({@link #createFor}); a required argument with
     *                     no default, because inferring it from {@code seasonOverride} would mark every game of the
     *                     day (and every test's season) as chosen and take it out of achievements (PR-16)
     */
    private HistoricalGameState create(String sleeperUserId, String sportCode, Integer userSlot,
                                       Integer seasonOverride, Long seedOverride, LocalDate dailyDate,
                                       boolean chosenSeason) {
        long t0 = System.nanoTime();
        HistoricalSport sport = sports.get(sportCode);
        int teams = sport.teams(), rounds = sport.rounds();
        if (userSlot != null && (userSlot < 1 || userSlot > teams)) {
            throw new IllegalArgumentException("userSlot must be 1.." + teams + ", got " + userSlot);
        }

        SeasonRow season;
        if (seasonOverride != null) {
            // The same "committed with prior-season lines" rule as the offered list, over just that season
            // (so tooling and tests can use a season outside the real offered range).
            season = sport.offeredSeasons(seasonOverride, seasonOverride).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("season " + seasonOverride + " is not offered"));
        } else {
            List<SeasonRow> offered = sport.offeredSeasons();
            if (offered.isEmpty()) {
                throw new IllegalStateException("no historical " + sport.code() + " seasons have been imported yet");
            }
            season = sport.seasonSkew().pick(offered, random.nextDouble());
        }
        int s = season.seasonStart();

        // The scoring snapshot: the base map restricted to this season's counted keys.
        Map<String, Double> scoring = sport.scoringFor(season);

        long seed = seedOverride != null ? seedOverride : random.nextLong();
        List<HistoricalPlayerCard> cards = sport.buildBoard(season, seed, scoring);
        if (cards.size() < teams * rounds) {
            throw new IllegalStateException("season " + s + " yields a board of " + cards.size() + " players but "
                    + teams + " x " + rounds + " needs " + teams * rounds);
        }
        int slot = userSlot != null ? userSlot : 1 + random.nextInt(teams);

        long id = games.create(sleeperUserId, sport.code(), s, teams, rounds, sport.reversalRound(),
                sport.rosterPositions(), write(scoring), slot, seed, writeSnapshot(cards, season, sport),
                OffsetDateTime.now(), dailyDate, chosenSeason);
        GameRow row = games.lockForUpdate(id).orElseThrow();
        List<BoardEntry> board = entries(cards, sport);
        advanceBots(row, cards, board, new HashMap<>());

        HistoricalGameState state = state(games.find(id).orElseThrow());
        log.info("historical game {} created: {} season {} slot {} board {} players, {} ms", id, sport.code(),
                season.label(), slot, cards.size(), (System.nanoTime() - t0) / 1_000_000);
        return state;
    }

    // ---- reads ------------------------------------------------------------------------------------------

    /** Someone else's game collapses to empty, the same as one that does not exist (404). */
    @Transactional(readOnly = true)
    public Optional<HistoricalGameState> get(long id, String sleeperUserId) {
        return games.find(id).filter(r -> r.sleeperUserId().equals(sleeperUserId)).map(this::state);
    }

    @Transactional(readOnly = true)
    public List<HistoricalGameState.GameSummary> list(String sleeperUserId) {
        return games.summaries(sleeperUserId).stream()
                .map(r -> new HistoricalGameState.GameSummary(r.id(), r.sport(), r.seasonLabel(), r.status(),
                        r.userSlot(), r.finish(), r.formatVersion(), r.outcome(), r.createdAt(),
                        r.draftScore(), r.dailyDate() == null ? null : r.dailyDate().toString(),
                        r.chosenSeason()))
                .toList();
    }

    // ---- the shared result (spec 074) -------------------------------------------------------------------

    /**
     * A finished game for anyone to read, with no owner in it. Empty unless the game is COMPLETE: a drafting,
     * abandoned or missing game is the same answer (404). While the game is today's game of the day (everyone
     * drafts the same season and seed, so a full result is that day's answer key) it is reduced: see
     * {@link PublicGame}. Read-only.
     */
    @Transactional(readOnly = true)
    public Optional<PublicGame> publicGame(long id) {
        Optional<GameRow> found = games.find(id).filter(r -> "COMPLETE".equals(r.status()));
        if (found.isEmpty()) return Optional.empty();
        GameRow row = found.get();
        HistoricalGameState full = state(row);
        LocalDate today = LocalDate.now(clock.withZone(HistoricalDailyService.ZONE));
        if (row.dailyDate() == null || row.dailyDate().isBefore(today)) {
            return Optional.of(new PublicGame(full, null));
        }
        String until = row.dailyDate().plusDays(1).atStartOfDay(HistoricalDailyService.ZONE).toInstant().toString();
        return Optional.of(new PublicGame(new HistoricalGameState(full.id(), full.sport(), full.status(), full.season(),
                full.teams(), full.rounds(), full.rosterPositions(), full.userSlot(), full.myPicks(),
                full.currentPickNo(), full.onTheClockSlot(), full.isUsersTurn(), List.of(), List.of(),
                reduced(full.result(), full.userSlot()), full.reversalRound(), full.dailyDate(),
                full.chosenSeason()), until));
    }

    /**
     * The part of a result that does not give away the day's answer: the finish, the user's own record and the
     * inputs of the draft score and the pick squares. No player, lineup, hindsight roster, bust or steal.
     */
    private ObjectNode reduced(JsonNode result, int userSlot) {
        ObjectNode out = json.createObjectNode();
        if (result == null || !result.isObject()) return out;
        for (String k : List.of("userFinish", "userOutcome", "format")) {
            out.set(k, result.has(k) ? result.get(k) : json.nullNode());
        }
        ArrayNode mine = json.createArrayNode();
        if (result.has("standings")) {
            for (JsonNode s : result.get("standings")) if (s.path("slot").asInt() == userSlot) mine.add(s);
        }
        out.set("standings", mine);
        ObjectNode hindsight = json.createObjectNode();
        JsonNode h = result.path("hindsight");
        // The best roster's own total (hindsight.pointsFor) IS today's puzzle answer, so it is not sent. The draft
        // score is the already-computed share of it, so the page can still show how the game went.
        // Accepted (review-fixes B2): "userPointsFor" with the draft score still lets a reader back-calculate the best
        // roster's total. That is one number, not a roster, and the season pages are the open answer key by Allan's
        // choice (spec 074 clarifications), so this is not closed further.
        for (String k : List.of("label", "userPointsFor", "userBeatIt")) {
            hindsight.set(k, h.has(k) ? h.get(k) : json.nullNode());
        }
        Integer score = DraftScore.of(h.path("userPointsFor").isNumber() ? h.get("userPointsFor").asDouble() : null,
                h.path("pointsFor").isNumber() ? h.get("pointsFor").asDouble() : null);
        if (score == null) hindsight.set("draftScore", json.nullNode());
        else hindsight.put("draftScore", score.intValue());
        hindsight.set("picks", json.createArrayNode());
        out.set("hindsight", hindsight);
        // pickFinishes: absent in a result stored before spec 065. A row keeps only the pick number and one mark
        // (ahead / same / behind): the raw position and exact finish rank would let a reader resolve the pick to a
        // player on the public season page (CR-03), and a square needs nothing more.
        if (result.has("pickFinishes") && result.get("pickFinishes").isArray()) {
            ArrayNode rows = json.createArrayNode();
            for (JsonNode f : result.get("pickFinishes")) {
                ObjectNode r = json.createObjectNode();
                r.set("pickNo", f.has("pickNo") ? f.get("pickNo") : json.nullNode());
                r.put("mark", markOf(f.path("draftedPositionRank").asInt(), f.path("finishPositionRank").asInt()));
                rows.add(r);
            }
            out.set("pickFinishes", rows);
        }
        out.set("weeks", json.createArrayNode());
        out.set("lineups", json.createObjectNode());
        out.set("finalPlaces", json.createArrayNode());
        return out;
    }

    /** Lower rank is better: a finish at a smaller rank than the draft rank is "ahead". Mirrors web pickMark(). */
    static String markOf(int draftedPositionRank, int finishPositionRank) {
        if (finishPositionRank < draftedPositionRank) return "ahead";
        if (finishPositionRank > draftedPositionRank) return "behind";
        return "same";
    }

    // ---- the user's moves -------------------------------------------------------------------------------

    /**
     * Records the user's pick and advances the bots to the user's next turn, or finishes the draft and replays
     * the season.
     *
     * @throws IllegalStateException not DRAFTING, not the user's turn, or the player is already taken (409)
     * @throws IllegalArgumentException the player is not on this board (400)
     */
    @Transactional
    public Optional<HistoricalGameState> pick(long id, long playerId, String sleeperUserId) {
        long t0 = System.nanoTime();
        Optional<GameRow> locked = lock(id, sleeperUserId);
        if (locked.isEmpty()) return Optional.empty();
        GameRow row = locked.get();
        requireUsersTurn(row);

        List<HistoricalPlayerCard> cards = readBoard(row);
        List<BoardEntry> board = entries(cards, sportOf(row));
        if (board.stream().noneMatch(e -> e.player().id() == playerId)) {
            throw new IllegalArgumentException("player " + playerId + " is not on this board");
        }
        Map<Integer, Long> completed = completed(row.id());
        if (completed.containsValue(playerId)) {
            throw new IllegalStateException("player " + playerId + " is already drafted in this game");
        }

        place(row, cards, board, completed, row.currentPickNo(), playerId);
        HistoricalGameState state = state(games.find(id).orElseThrow());
        if (!"DRAFTING".equals(state.status())) {
            log.info("historical game {} finished by pick: {} ms total for the final pick", id,
                    (System.nanoTime() - t0) / 1_000_000);
        }
        return Optional.of(state);
    }

    /**
     * Picks for the user: the best-ranked draftable player who would start for this roster, else the best-ranked
     * draftable, else the best available. {@code PICK} makes one pick (it must be the user's turn);
     * {@code FINISH} makes every remaining pick.
     */
    @Transactional
    public Optional<HistoricalGameState> auto(long id, Scope scope, String sleeperUserId) {
        long t0 = System.nanoTime();
        Optional<GameRow> locked = lock(id, sleeperUserId);
        if (locked.isEmpty()) return Optional.empty();
        GameRow row = locked.get();
        requireUsersTurn(row);

        List<HistoricalPlayerCard> cards = readBoard(row);
        List<BoardEntry> board = entries(cards, sportOf(row));
        Map<Integer, Long> completed = completed(row.id());
        int pickNo = row.currentPickNo();
        int total = row.teams() * row.rounds();

        while (pickNo <= total) {
            int slot = DraftSlot.slot(pickNo, row.teams(), row.reversalRound());
            if (slot != row.userSlot()) break;   // the bots were advanced to the user's turn: this is the end
            DraftContext ctx = context(row, board, completed);
            BoardEntry choice = chooseAuto(ctx, board, completed, row.userSlot(),
                    DraftSlot.round(pickNo, row.teams()));
            if (choice == null) throw new IllegalStateException("no player left to draft at pick " + pickNo);
            MockDraftEngine.AdvanceResult adv =
                    place(row, cards, board, completed, pickNo, choice.player().id());
            pickNo = adv.nextPickNo();
            if (adv.complete() || scope == Scope.PICK) break;
        }
        HistoricalGameState state = state(games.find(id).orElseThrow());
        if (!"DRAFTING".equals(state.status())) {
            log.info("historical game {} finished by auto {}: {} ms total", id, scope,
                    (System.nanoTime() - t0) / 1_000_000);
        }
        return Optional.of(state);
    }

    /** DRAFTING to ABANDONED. Empty if there is no such game for this user. */
    @Transactional
    public boolean abandon(long id, String sleeperUserId) {
        Optional<GameRow> locked = lock(id, sleeperUserId);
        if (locked.isEmpty()) return false;
        requireDrafting(locked.get());
        if (locked.get().dailyDate() != null) {
            // One daily game per day: abandoning it would spend the day with nothing to show (spec 071).
            throw new IllegalStateException("the game of the day cannot be abandoned");
        }
        games.advance(id, locked.get().currentPickNo(), "ABANDONED");
        return true;
    }

    // ---- the engine -------------------------------------------------------------------------------------

    /** Records the user's pick at {@code pickNo}, then advances the bots (and finishes, if that was the last). */
    private MockDraftEngine.AdvanceResult place(GameRow row, List<HistoricalPlayerCard> cards,
                                                List<BoardEntry> board, Map<Integer, Long> completed,
                                                int pickNo, long playerId) {
        int slot = DraftSlot.slot(pickNo, row.teams(), row.reversalRound());
        games.insertPicks(List.of(new PickRow(row.id(), pickNo, DraftSlot.round(pickNo, row.teams()), slot,
                "USER", playerId)));
        completed.put(pickNo, playerId);
        return advanceBots(row, cards, board, completed);
    }

    /** Runs the bots up to the user's next turn or the end, persists them, and finishes a complete draft. */
    private MockDraftEngine.AdvanceResult advanceBots(GameRow row, List<HistoricalPlayerCard> cards,
                                                      List<BoardEntry> board, Map<Integer, Long> completed) {
        List<SeatSpec> seats = seats(row);
        DraftContext ctx = context(row, board, completed);
        MockDraftEngine.AdvanceResult adv = engine.advanceUntilUserOrEnd(ctx, seats, row.rngSeed());

        List<PickRow> rows = new ArrayList<>();
        for (MockDraftEngine.Decision d : adv.newPicks()) {
            rows.add(new PickRow(row.id(), d.pickNo(), d.round(), d.slot(), "BOT", d.player().player().id()));
            completed.put(d.pickNo(), d.player().player().id());
        }
        games.insertPicks(rows);
        games.advance(row.id(), adv.nextPickNo(), "DRAFTING");
        if (adv.complete()) finishDraft(row, cards);
        return adv;
    }

    private DraftContext context(GameRow row, List<BoardEntry> board, Map<Integer, Long> completed) {
        // Bots only: no manager seats, so every profile is neutral, and positional priors are uniform because
        // fitted ones describe a 2024-26 league (research R9). DraftContext keeps the map it is given, so it
        // gets a copy.
        HistoricalSport sport = sportOf(row);
        return contexts.build(settingsOf(row), seats(row), Map.of(), PositionalPriors.uniform(sport.sport()),
                board, new HashMap<>(completed), sport.botTuning()::apply);
    }

    private static List<SeatSpec> seats(GameRow row) {
        return List.of(SeatSpec.user(row.userSlot(), null));
    }

    private LeagueSettings settingsOf(GameRow row) {
        return new LeagueSettings(sportOf(row).sport(), row.teams(), row.rounds(), row.rosterPositions(), 0.0,
                row.reversalRound());
    }

    /** The game row's own sport: every league-shape read goes through this, never a literal. */
    private HistoricalSport sportOf(GameRow row) {
        return sports.get(row.sport());
    }

    /**
     * The same rule as the mock room's auto-pick, without targets: a draftable player who would start for this
     * roster, else the best draftable, else the best available. (A second copy of that rule, because
     * {@code MockDraftService.chooseAuto} is package-private to a service with other dependencies.)
     */
    private BoardEntry chooseAuto(DraftContext ctx, List<BoardEntry> byRank, Map<Integer, Long> completed,
                                  int userSlot, int round) {
        Set<Long> drafted = new HashSet<>(completed.values());
        LeagueSettings settings = ctx.settings();
        SportRules rules = ctx.rules();

        RosterState roster = new RosterState();
        for (Map.Entry<Integer, Long> e : completed.entrySet()) {
            if (DraftSlot.slot(e.getKey(), settings.teams(), settings.reversalRound()) != userSlot) continue;
            BoardEntry picked = ctx.byId().get(e.getValue());
            if (picked != null) roster.add(picked);
        }
        Object lineup = rules.prepareLineup(roster, settings, ctx::valueOf);

        double benchFloor = ctx.cfg().benchFloor();
        BoardEntry firstDraftable = null;
        for (BoardEntry e : byRank) {
            if (drafted.contains(e.player().id())) continue;
            if (!rules.isDraftable(e, lineup, round, settings.rounds())) continue;
            if (firstDraftable == null) firstDraftable = e;
            if (rules.rosterNeed(e, lineup) > benchFloor + 1e-9) return e;
        }
        if (firstDraftable != null) return firstDraftable;
        for (BoardEntry e : byRank) if (!drafted.contains(e.player().id())) return e;
        return null;
    }

    // ---- the replay -------------------------------------------------------------------------------------

    private void finishDraft(GameRow row, List<HistoricalPlayerCard> cards) {
        long t0 = System.nanoTime();
        HistoricalSport sport = sportOf(row);
        SeasonRow season = sport.findSeason(row.seasonStart()).orElseThrow();

        Map<Long, HistoricalPlayerCard> byId = new HashMap<>();
        for (HistoricalPlayerCard c : cards) byId.put(c.id(), c);
        List<SeasonReplay.Pick> picks = games.picks(row.id()).stream()
                .map(p -> new SeasonReplay.Pick(p.pickNo(), p.draftSlot(), p.playerId())).toList();

        List<? extends WeekStatLine> played = sport.playedWeeks(row.seasonStart(), byId.keySet());
        // The board holds only players who played that season, so a drafted player with no game rows means a
        // re-import re-keyed him since this game was created. Say so loudly rather than score him 0 (finding 8).
        Set<Long> withRows = new HashSet<>();
        for (WeekStatLine g : played) withRows.add(g.playerId());
        for (SeasonReplay.Pick p : picks) {
            if (!withRows.contains(p.playerId())) {
                log.error("historical game {}: drafted player {} has no game rows in season {}; a re-import changed"
                        + " identities while this game was drafting", row.id(), p.playerId(), row.seasonStart());
                throw new IllegalStateException("historical data for season " + row.seasonStart()
                        + " was re-imported while this game was in progress; player "
                        + byId.get(p.playerId()).name() + " no longer has game rows, so the season cannot be replayed");
            }
        }
        SeasonReplay.Input in = new SeasonReplay.Input(settingsOf(row), rulesRegistry.get(sport.sport()),
                read(row.scoringJson(), SCORING), season.weekCount(), sport.regularSeasonWeeks(), row.rngSeed(), row.userSlot(), picks, byId,
                played, sport.weekStarts(row.seasonStart()));
        HistoricalResult result = replay.replay(in);
        games.writeResult(row.id(), write(result), capturePickFacts(row, picks, byId, played, season.weekCount(),
                in.scoring()), OffsetDateTime.now());
        log.info("historical game {} replayed season {} in {} ms", row.id(), season.label(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * Spec 064 amendment A1: the user's per-pick season facts, captured here where they are guaranteed consistent
     * with the frozen result, so a later settle never recomputes from reference tables a re-import may have
     * changed. Never blocks completion: any failure is logged and the column stays null (the game then settles from
     * its saved result alone). Returns the JSON text, or null.
     */
    private String capturePickFacts(GameRow row, List<SeasonReplay.Pick> picks, Map<Long, HistoricalPlayerCard> byId,
                                    List<? extends WeekStatLine> played, int seasonWeeks,
                                    Map<String, Double> scoring) {
        try {
            SeasonReplay.PlayerWeeks pw = replay.scorePlayerWeeks(scoring, played);
            Map<Long, Double> value = new HashMap<>();
            for (long id : byId.keySet()) value.put(id, pw.seasonValue(id));
            Set<Long> drafted = new HashSet<>();
            for (SeasonReplay.Pick p : picks) drafted.add(p.playerId());
            Map<Long, Integer> rank = Comparisons.valueRanks(drafted, value);
            Map<Long, Integer> lastWeek = new HashMap<>();
            for (WeekStatLine g : played) lastWeek.merge(g.playerId(), g.week(), Math::max);

            List<AchievementRules.PickFact> facts = new ArrayList<>();
            for (SeasonReplay.Pick p : picks) {
                if (p.slot() != row.userSlot()) continue;
                HistoricalPlayerCard card = byId.get(p.playerId());
                facts.add(new AchievementRules.PickFact(p.pickNo(), DraftSlot.round(p.pickNo(), row.teams()),
                        p.playerId(), card.name(), card.rank(), rank.get(p.playerId()), value.get(p.playerId()),
                        lastWeek.get(p.playerId())));
            }
            return write(new AchievementRules.PickFacts(seasonWeeks, facts));
        } catch (RuntimeException e) {
            log.error("historical game {}: pick facts could not be captured; its awards will settle from the saved"
                    + " result only", row.id(), e);
            return null;
        }
    }

    // ---- state ------------------------------------------------------------------------------------------

    HistoricalGameState state(GameRow row) {
        List<HistoricalPlayerCard> cards = readBoard(row);
        Map<Long, HistoricalPlayerCard> byId = new HashMap<>();
        for (HistoricalPlayerCard c : cards) byId.put(c.id(), c);

        List<PickRow> pickRows = games.picks(row.id());
        Set<Long> picked = new HashSet<>();
        List<HistoricalGameState.PickView> views = new ArrayList<>();
        for (PickRow p : pickRows) {
            picked.add(p.playerId());
            views.add(new HistoricalGameState.PickView(p.pickNo(), p.round(), p.draftSlot(), p.seatType(),
                    byId.get(p.playerId())));
        }

        boolean drafting = "DRAFTING".equals(row.status());
        List<HistoricalPlayerCard> available = drafting
                ? cards.stream().filter(c -> !picked.contains(c.id())).toList()
                : List.of();
        Integer onTheClock = drafting ? DraftSlot.slot(row.currentPickNo(), row.teams(), row.reversalRound()) : null;
        boolean usersTurn = onTheClock != null && onTheClock == row.userSlot();
        List<Integer> myPicks = Arrays.stream(
                DraftSlot.picksForSlot(row.userSlot(), row.teams(), row.rounds(), row.reversalRound()))
                .boxed().toList();

        HistoricalSport sport = sportOf(row);
        SeasonRow season = sport.findSeason(row.seasonStart()).orElseThrow();
        Map<String, Double> scoring = read(row.scoringJson(), SCORING);
        Snapshot snap = readSnapshot(row);
        Map<String, String> excluded = snap.excludedStats() != null ? snap.excludedStats()
                : readMap(season.excludedStatsJson(), STRING_MAP);
        Map<String, Double> coverage = snap.statCoverage() != null ? snap.statCoverage()
                : readMap(season.statCoverageJson(), DOUBLE_MAP);
        HistoricalGameState.SeasonView view = new HistoricalGameState.SeasonView(season.label(),
                StatOrder.sorted(scoring.keySet()), StatOrder.sorted(excluded), StatOrder.sorted(coverage),
                season.rankingSource(), sport.rankingLabel(), sport.settingsSource(), snap.dataNotes(),
                snap.attribution());

        JsonNode result = null;
        if (row.resultJson() != null) {
            try {
                result = json.readTree(row.resultJson());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("stored result of game " + row.id() + " is not valid JSON");
            }
        }
        return new HistoricalGameState(row.id(), row.sport(), row.status(), view, row.teams(), row.rounds(),
                row.rosterPositions(), row.userSlot(), myPicks, row.currentPickNo(), onTheClock, usersTurn,
                views, available, result, row.reversalRound(),
                row.dailyDate() == null ? null : row.dailyDate().toString(), row.chosenSeason());
    }

    // ---- plumbing ---------------------------------------------------------------------------------------

    private Optional<GameRow> lock(long id, String sleeperUserId) {
        return games.lockForUpdate(id).filter(r -> r.sleeperUserId().equals(sleeperUserId));
    }

    private static void requireDrafting(GameRow row) {
        if (!"DRAFTING".equals(row.status())) {
            throw new IllegalStateException("historical game " + row.id() + " is " + row.status() + ", not DRAFTING");
        }
    }

    private static void requireUsersTurn(GameRow row) {
        requireDrafting(row);
        int onTheClock = DraftSlot.slot(row.currentPickNo(), row.teams(), row.reversalRound());
        if (onTheClock != row.userSlot()) {
            throw new IllegalStateException("pick " + row.currentPickNo() + " is not the user's turn");
        }
    }

    private Map<Integer, Long> completed(long gameId) {
        Map<Integer, Long> m = new HashMap<>();
        for (PickRow p : games.picks(gameId)) m.put(p.pickNo(), p.playerId());
        return m;
    }

    /**
     * The board snapshot, written to the existing {@code board} jsonb as an object: the rank-ascending cards plus the
     * season's {@code excludedStats} and {@code statCoverage} as they stood at creation, so a re-import can never
     * make a finished (or in-progress) game's disclosure contradict its frozen scoring (review finding 7). Since
     * spec 056 it also freezes {@code dataNotes} and {@code attribution}. Built in a LinkedHashMap, never Map.of:
     * {@code attribution} is null for basketball.
     */
    private String writeSnapshot(List<HistoricalPlayerCard> cards, SeasonRow season, HistoricalSport sport) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("cards", cards);
        o.put("excludedStats", readMap(season.excludedStatsJson(), STRING_MAP));
        o.put("statCoverage", readMap(season.statCoverageJson(), DOUBLE_MAP));
        o.put("dataNotes", sport.dataNotes(season));
        o.put("attribution", sport.attribution());
        return write(o);
    }

    private record Snapshot(List<HistoricalPlayerCard> cards, Map<String, String> excludedStats,
                            Map<String, Double> statCoverage, List<String> dataNotes, String attribution) {}

    /**
     * Reads the object form above or the older bare array of cards (disclosure then null: read live). A board
     * written before spec 056 has no {@code dataNotes}/{@code attribution} keys: they read as {@code []}/null (F9),
     * never as a missing-key failure on reopening a game that was created before V46.
     */
    private Snapshot readSnapshot(GameRow row) {
        try {
            JsonNode n = json.readTree(row.boardJson());
            if (n.isArray()) return new Snapshot(read(row.boardJson(), CARDS), null, null, List.of(), null);
            List<String> notes = n.has("dataNotes") && !n.get("dataNotes").isNull()
                    ? json.readValue(n.get("dataNotes").toString(), STRING_LIST) : List.of();
            String attribution = n.has("attribution") && !n.get("attribution").isNull()
                    ? n.get("attribution").asText() : null;
            return new Snapshot(json.readValue(n.get("cards").toString(), CARDS),
                    json.readValue(n.get("excludedStats").toString(), STRING_MAP),
                    json.readValue(n.get("statCoverage").toString(), DOUBLE_MAP), notes, attribution);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored historical JSON is not valid: " + e.getOriginalMessage());
        }
    }

    /** The snapshot is the board: rank-ascending, exactly as stored. */
    private List<HistoricalPlayerCard> readBoard(GameRow row) {
        return readSnapshot(row).cards();
    }

    private static List<BoardEntry> entries(List<HistoricalPlayerCard> cards, HistoricalSport sport) {
        return cards.stream().map(c -> HistoricalBoardBuilder.toEntry(c, sport.sport()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored historical JSON is not valid: " + e.getOriginalMessage());
        }
    }

    private <T> Map<String, T> readMap(String text, TypeReference<Map<String, T>> type) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        return read(text, type);
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise historical JSON: " + e.getOriginalMessage());
        }
    }
}
