package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.historical.AchievementRules.BadgeEarn;
import com.ballknowers.draftsim.historical.AchievementRules.CareerTotals;
import com.ballknowers.draftsim.historical.AchievementRules.GameAward;
import com.ballknowers.draftsim.historical.AchievementRules.GameFacts;
import com.ballknowers.draftsim.historical.AchievementRules.Moment;
import com.ballknowers.draftsim.historical.AchievementRules.PickFacts;
import com.ballknowers.draftsim.historical.AchievementRules.XpLine;
import com.ballknowers.draftsim.historical.HistoricalAchievementRepository.AchievementRow;
import com.ballknowers.draftsim.historical.HistoricalAchievementRepository.AwardRow;
import com.ballknowers.draftsim.historical.HistoricalAchievementRepository.Totals;
import com.ballknowers.draftsim.historical.HistoricalAchievementRepository.UnsettledGame;
import com.ballknowers.draftsim.historical.HistoricalGameRepository.GameRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Time Machine achievements (spec 064): lazy settle, the profile, a game's award and the "seen" marks.
 *
 * <p><b>Lazy settle.</b> Awards are never computed inside {@code finishDraft}; the two reads below settle first.
 * {@link #settle} takes one advisory transaction lock per user so one user's settles are serial, then settles each
 * unsettled COMPLETE game in its own {@code PROPAGATION_NESTED} savepoint (amendment A2): a game that throws rolls
 * back to its savepoint, is logged and counted, and the others still settle. The catch sits OUTSIDE the nested
 * {@code execute}; a catch inside would leave Postgres in "current transaction is aborted" and poison every later
 * game. NESTED rather than REQUIRES_NEW because the latter takes a second pooled connection while the first holds
 * the lock, and the pool is small. The template is built locally; the shared bean is never mutated.
 */
@Service
public class HistoricalAchievementService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalAchievementService.class);
    private static final TypeReference<List<XpLine>> LINES = new TypeReference<>() {};
    private static final TypeReference<List<Moment>> MOMENTS = new TypeReference<>() {};

    private final HistoricalAchievementRepository repo;
    private final HistoricalGameRepository games;
    private final ObjectMapper json;
    private final TransactionTemplate perGame;

    public HistoricalAchievementService(HistoricalAchievementRepository repo, HistoricalGameRepository games,
                                        ObjectMapper json, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.games = games;
        this.json = json;
        this.perGame = new TransactionTemplate(txManager);
        this.perGame.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    // ---- wire types (the contract in plan.md "Wire contract (final)") -----------------------------------

    public record BadgeView(String code, String name, String rule, int xp, String source, boolean unlocked,
                            OffsetDateTime unlockedAt, Long gameId, JsonNode detail, boolean seen) {}

    public record TotalsView(int games, int gamesFromSavedResults, int weeklyWins, int playoffWins, int titles) {}

    public record ProfileView(long xp, int level, String levelName, int levelStartXp, int nextLevelXp, int seenLevel,
                              TotalsView totals, List<BadgeView> badges, int settleFailures) {}

    public record AwardView(long gameId, boolean settled, boolean fromSavedResult, int xp, List<XpLine> lines,
                            List<BadgeView> badges, List<Moment> moments, boolean momentsSeen, int total) {}

    public record SeenRequest(List<String> codes, Integer level, Long momentsGameId) {}

    // ---- settle -----------------------------------------------------------------------------------------

    /**
     * Settles every unsettled COMPLETE game of this user, oldest first. Idempotent and safe to call concurrently.
     *
     * @return how many games could not be settled this call (each is retried on the next call)
     */
    @Transactional
    public int settle(String sleeperUserId) {
        repo.lockUser(sleeperUserId);
        int failures = 0;
        for (UnsettledGame g : repo.unsettled(sleeperUserId)) {
            try {
                perGame.executeWithoutResult(status -> settleOne(sleeperUserId, g));
            } catch (RuntimeException e) {
                failures++;
                log.warn("historical game {}: awards could not be settled; it stays unsettled and is retried on"
                        + " the next read: {}", g.id(), e.toString());
            }
        }
        return failures;
    }

    private void settleOne(String userId, UnsettledGame g) {
        HistoricalResult result = read(g.resultJson(), HistoricalResult.class);
        PickFacts facts = g.pickFactsJson() == null ? null : read(g.pickFactsJson(), PickFacts.class);
        GameAward a = AchievementRules.evaluate(
                new GameFacts(g.sport(), g.teams(), g.rounds(), g.userSlot(), result, facts));
        boolean quiet = g.preAchievements();

        int inserted = repo.insertAward(g.id(), userId, g.sport(), a.xp(), a.weeklyWins(), a.playoffWins(), a.title(),
                a.fromSavedResult(), write(a.lines()), write(a.moments()), quiet);
        if (inserted == 0) return;   // another settle already wrote this game: do not touch its badges

        for (BadgeEarn e : a.badges()) {
            repo.insertBadge(userId, e.badge().code(), g.id(), e.badge().xp(), write(e.detail()), g.completedAt(), quiet);
        }
        Totals t = repo.totals(userId);
        for (BadgeEarn e : AchievementRules.cumulative(
                new CareerTotals(t.games(), t.weeklyWins(), t.titles(), t.nbaTitles(), t.nflTitles()))) {
            repo.insertBadge(userId, e.badge().code(), g.id(), e.badge().xp(), write(e.detail()), g.completedAt(), quiet);
        }
        // Amendment A9: after each game that predates achievements, raise seen_level to the level so far, so old
        // games never produce a level-up toast even when a new game is settled in the same batch.
        if (quiet) repo.raiseSeenLevel(userId, Levels.levelFor(repo.totalXp(userId)));
    }

    // ---- reads ------------------------------------------------------------------------------------------

    /** Settles, then reads. One transaction, so the read sees what the settle wrote. */
    @Transactional
    public ProfileView profile(String sleeperUserId) {
        int failures = settle(sleeperUserId);
        long xp = repo.totalXp(sleeperUserId);
        int level = Levels.levelFor(xp);
        Totals t = repo.totals(sleeperUserId);
        int seen = Math.min(repo.seenLevel(sleeperUserId), level);   // amendment A15: clamped to the actual level
        return new ProfileView(xp, level, Levels.name(level), Levels.startXp(level), Levels.startXp(level + 1), seen,
                new TotalsView(t.games(), t.gamesFromSavedResults(), t.weeklyWins(), t.playoffWins(), t.titles()),
                catalog(repo.achievements(sleeperUserId)), failures);
    }

    /**
     * One game's award. Empty for an unknown game or another user's (404, before any status check); a game that
     * is not COMPLETE is an {@link IllegalStateException} (409); a COMPLETE game that could not settle answers
     * {@code settled: false} (amendment A3).
     */
    @Transactional
    public Optional<AwardView> award(long gameId, String sleeperUserId) {
        Optional<GameRow> row = games.find(gameId).filter(r -> r.sleeperUserId().equals(sleeperUserId));
        if (row.isEmpty()) return Optional.empty();
        if (!"COMPLETE".equals(row.get().status())) {
            throw new IllegalStateException("historical game " + gameId + " is " + row.get().status()
                    + ", so it has no award yet");
        }
        settle(sleeperUserId);
        Optional<AwardRow> award = repo.award(gameId);
        if (award.isEmpty()) {
            return Optional.of(new AwardView(gameId, false, false, 0, List.of(), List.of(), List.of(), true, 0));
        }
        AwardRow a = award.get();
        List<BadgeView> unlockedHere = new ArrayList<>();
        for (BadgeView b : catalog(repo.achievements(sleeperUserId))) {
            if (b.unlocked() && b.gameId() != null && b.gameId() == gameId) unlockedHere.add(b);
        }
        int total = a.xp() + unlockedHere.stream().mapToInt(BadgeView::xp).sum();
        return Optional.of(new AwardView(gameId, true, a.fromSavedResult(), a.xp(), read(a.xpLinesJson(), LINES),
                unlockedHere, read(a.momentsJson(), MOMENTS), a.momentsSeen(), total));
    }

    /**
     * Marks things seen. Monotonic: a badge already seen keeps its time, {@code seen_level} only rises and never
     * past the user's actual level, and codes the caller has not unlocked are ignored. Another user's game id
     * matches nothing.
     */
    @Transactional
    public void markSeen(String sleeperUserId, SeenRequest req) {
        if (req == null) return;
        if (req.codes() != null && !req.codes().isEmpty()) {
            repo.markBadgesSeen(sleeperUserId, req.codes().stream().filter(c -> c != null).toList());
        }
        if (req.level() != null && req.level() > 1) {
            int actual = Levels.levelFor(repo.totalXp(sleeperUserId));
            repo.raiseSeenLevel(sleeperUserId, Math.min(req.level(), actual));
        }
        if (req.momentsGameId() != null) repo.markMomentsSeen(sleeperUserId, req.momentsGameId());
    }

    // ---- plumbing ---------------------------------------------------------------------------------------

    /** The whole catalog in catalog order, with the user's unlocks merged in. */
    private List<BadgeView> catalog(List<AchievementRow> rows) {
        Map<String, AchievementRow> byCode = new HashMap<>();
        for (AchievementRow r : rows) byCode.put(r.code(), r);
        List<BadgeView> out = new ArrayList<>();
        for (Badge b : Badge.values()) {
            AchievementRow r = byCode.get(b.code());
            if (r == null) {
                out.add(new BadgeView(b.code(), b.displayName(), b.rule(), b.xp(), b.source().name(), false, null,
                        null, null, false));
            } else {
                out.add(new BadgeView(b.code(), b.displayName(), b.rule(), r.xp(), b.source().name(), true,
                        r.unlockedAt(), r.gameId(), r.detailJson() == null ? null : readTree(r.detailJson()),
                        r.seen()));
            }
        }
        return out;
    }

    private <T> T read(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored achievement JSON is not valid: " + e.getOriginalMessage());
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored achievement JSON is not valid: " + e.getOriginalMessage());
        }
    }

    private JsonNode readTree(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored achievement JSON is not valid: " + e.getOriginalMessage());
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise achievement JSON: " + e.getOriginalMessage());
        }
    }
}
