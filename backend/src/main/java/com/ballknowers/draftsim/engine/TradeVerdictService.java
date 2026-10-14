package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Player;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.JsonUtil;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.LeagueTransactionRepository;
import com.ballknowers.draftsim.store.RosterWeekPointsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Who won each trade, from the points the traded assets actually scored in the receiving
 * manager's lineup (specs/033-trade-verdicts).
 *
 * <p>Loads nothing a caller already holds: {@link TransactionAnalysisService} has the
 * transactions, week breakdowns, players and roster names in hand, so they are passed in rather
 * than read a second time. This class adds the draft, the final-week set, the
 * {@link OwnershipTimeline} run and the translation to names. Every rule lives in
 * {@code OwnershipTimeline}; this is plumbing.
 *
 * <p>Realized started points only: no projections, no trade values.
 */
@Service
public class TradeVerdictService {

    private final DraftRepository drafts;
    private final ScoredWeeks scoredWeeks;

    public TradeVerdictService(DraftRepository drafts, ScoredWeeks scoredWeeks) {
        this.drafts = drafts;
        this.scoredWeeks = scoredWeeks;
    }

    /** Wire shapes: {@code OwnershipTimeline}'s results plus names, positions and post-move ranks. */
    public record Result(List<TradeResult> trades, int throughWeek, boolean seasonFinal,
                         boolean tradeAssetsLoaded, OwnershipTimeline.Reconciliation reconciliation) {}

    /** {@code week} is Sleeper's leg: the week the trade was PROPOSED, kept for compatibility. */
    public record TradeResult(String txId, int week, Instant acceptedAt, boolean assetsLoaded,
                              VerdictResult verdict, List<SideResult> sides) {}

    public record VerdictResult(String state, Integer winnerRosterId, Double margin, int weeksCounted,
                                boolean isFinal) {}

    public record SideResult(int rosterId, String teamName, double startedTotal, double benchTotal,
                             int weeksCounted, List<AssetResult> assets) {}

    public sealed interface AssetResult permits PlayerResult, PickResult, FaabResult {}

    /** {@code postMoveRank}: average positional rank over the counted weeks only; null unless COUNTED. */
    public record PlayerResult(String playerId, String playerName, String position, String status,
                               Integer firstWeek, Integer lastWeek, double startedPoints,
                               double benchPoints, int weeksCounted, List<Integer> duplicateWeeks,
                               Double postMoveRank, List<StretchResult> stretches) implements AssetResult {}

    /** One roster's unbroken run with the player this season (US2). {@code viaTxId} null for a draft pick. */
    public record StretchResult(int rosterId, String teamName, int firstWeek, int lastWeek, String how,
                                String viaTxId, double startedPoints, double benchPoints,
                                List<Integer> duplicateWeeks, boolean leadingUnstarted) {}

    public record PickResult(int season, int round, int originalRosterId, String status,
                             Integer pickNo, PlayerResult player) implements AssetResult {}

    public record FaabResult(int amount, int fromRosterId, String fromTeamName) implements AssetResult {}

    public Result forLeague(LeagueRepository.LeagueRow league,
                            List<LeagueTransactionRepository.Row> rows,
                            List<RosterWeekPointsRepository.WeekBreakdown> breakdowns,
                            Map<String, Player> playersBySleeperId,
                            Map<Integer, String> nameByRoster,
                            Map<Integer, Long> managerByRoster,
                            Map<Integer, Map<String, Integer>> rankByWeek) {
        ScoredWeeks.Snapshot snapshot = scoredWeeks.of(league.id());

        // ---- moves (every completed one, in acceptance order) and trades (with their picks and FAAB)
        List<OwnershipTimeline.Move> moves = new ArrayList<>();
        List<OwnershipTimeline.Trade> trades = new ArrayList<>();
        boolean assetsLoaded = true;
        for (LeagueTransactionRepository.Row r : rows) {
            // A failed waiver claim names a player it never moved.
            if (!"complete".equals(r.status())) continue;
            Map<String, Integer> adds = idMap(r.addsJson());
            Map<String, Integer> drops = idMap(r.dropsJson());
            String txId = r.sleeperTransactionId();
            moves.add(new OwnershipTimeline.Move(txId, r.type(), r.week(), r.createdAt(), adds, drops));
            if (!"TRADE".equals(r.type())) continue;
            boolean loaded = r.draftPicksJson() != null;
            if (!loaded) assetsLoaded = false;
            trades.add(new OwnershipTimeline.Trade(txId, r.week(), r.createdAt(), adds, drops,
                    picks(r.draftPicksJson()), faab(r.waiverBudgetJson()), loaded));
        }

        // ---- the season's draft, for resolving picks to players
        Map<Long, Player> playerById = new HashMap<>();
        for (Player p : playersBySleeperId.values()) playerById.put(p.id(), p);
        Optional<DraftRepository.DraftRow> draft = drafts.forLeague(league.id());
        List<OwnershipTimeline.DraftedPick> drafted = new ArrayList<>();
        Map<Integer, Long> slotToManager = new HashMap<>();
        boolean draftComplete = false;
        if (draft.isPresent() && draft.get().season() == league.season()) {
            draftComplete = "complete".equals(draft.get().status());
            draft.get().slotToManager().forEach((slot, manager) -> {
                if (manager instanceof Number n) slotToManager.put(Integer.parseInt(slot), n.longValue());
            });
            for (DraftRepository.PickRow p : drafts.picks(draft.get().id())) {
                Player pl = p.playerId() == null ? null : playerById.get(p.playerId());
                drafted.add(new OwnershipTimeline.DraftedPick(p.round(), p.draftSlot(), p.pickNo(),
                        p.managerId(), pl == null ? null : pl.sleeperId()));
            }
        }

        List<OwnershipTimeline.WeekRow> weeks = new ArrayList<>();
        for (RosterWeekPointsRepository.WeekBreakdown b : breakdowns) weeks.add(weekRow(b));

        boolean seasonFinal = league.complete();
        OwnershipTimeline.Inputs inputs = new OwnershipTimeline.Inputs(
                league.season(), weeks, moves, trades, drafted, draftComplete, slotToManager,
                managerByRoster, snapshot.finalWeeks(), seasonFinal);
        List<OwnershipTimeline.Evaluated> evaluated = OwnershipTimeline.evaluate(inputs);
        OwnershipTimeline.Analysis analysis = OwnershipTimeline.analyze(inputs,
                key -> playersBySleeperId.containsKey(key) ? playersBySleeperId.get(key).name() : "Player " + key,
                rosterId -> nameByRoster.getOrDefault(rosterId, "Roster " + rosterId));

        List<TradeResult> out = new ArrayList<>();
        for (OwnershipTimeline.Evaluated e : evaluated) {
            out.add(translate(e, playersBySleeperId, nameByRoster, rankByWeek, analysis));
        }
        // Newest accepted first. Never by stored id: ids are not chronological (lessons #42).
        out.sort(Comparator.comparing(TradeResult::acceptedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(TradeResult::txId));
        return new Result(out, snapshot.latestFinal(), seasonFinal, assetsLoaded, analysis.reconciliation());
    }

    // ------------------------------------------------------------------ translation

    private static TradeResult translate(OwnershipTimeline.Evaluated e, Map<String, Player> players,
                                         Map<Integer, String> nameByRoster,
                                         Map<Integer, Map<String, Integer>> rankByWeek,
                                         OwnershipTimeline.Analysis analysis) {
        List<SideResult> sides = new ArrayList<>();
        for (OwnershipTimeline.Side s : e.sides()) {
            List<AssetResult> assets = new ArrayList<>();
            for (OwnershipTimeline.Asset a : s.assets()) {
                switch (a) {
                    case OwnershipTimeline.PlayerAsset p -> assets.add(player(p, players, rankByWeek, analysis, nameByRoster));
                    case OwnershipTimeline.PickAsset k -> assets.add(new PickResult(k.season(), k.round(),
                            k.originalRosterId(), k.status().name(), k.pickNo(),
                            k.player() == null ? null : player(k.player(), players, rankByWeek, analysis, nameByRoster)));
                    case OwnershipTimeline.FaabAsset f -> assets.add(new FaabResult(f.amount(), f.fromRosterId(),
                            nameByRoster.getOrDefault(f.fromRosterId(), "Roster " + f.fromRosterId())));
                }
            }
            sides.add(new SideResult(s.rosterId(), nameByRoster.getOrDefault(s.rosterId(), "Roster " + s.rosterId()),
                    s.startedTotal(), s.benchTotal(), s.weeksCounted(), assets));
        }
        OwnershipTimeline.Verdict v = e.verdict();
        return new TradeResult(e.txId(), e.leg(), e.acceptedAt(), e.assetsLoaded(),
                new VerdictResult(v.state().name(), v.winnerRosterId(), v.margin(), v.weeksCounted(), v.isFinal()),
                sides);
    }

    private static PlayerResult player(OwnershipTimeline.PlayerAsset p, Map<String, Player> players,
                                       Map<Integer, Map<String, Integer>> rankByWeek,
                                       OwnershipTimeline.Analysis analysis, Map<Integer, String> nameByRoster) {
        Player pl = players.get(p.playerKey());
        // FR-013: a traded player is ranked over his counted window only. Waiver adds keep the
        // inclusive-from-the-move-week rule in TransactionAnalysisService.moved().
        List<Integer> ranks = new ArrayList<>();
        for (int wk : p.countedWeeks()) {
            Integer rank = rankByWeek.getOrDefault(wk, Map.of()).get(p.playerKey());
            if (rank != null) ranks.add(rank);
        }
        Double avg = ranks.isEmpty() ? null
                : Math.round(ranks.stream().mapToInt(Integer::intValue).average().orElse(0) * 10.0) / 10.0;
        return new PlayerResult(p.playerKey(), pl == null ? "Unknown player" : pl.name(),
                pl == null ? null : pl.primary().name(), p.status().name(), p.firstWeek(), p.lastWeek(),
                OwnershipTimeline.round1(p.startedPoints()), OwnershipTimeline.round1(p.benchPoints()), p.countedWeeks().size(), p.duplicateWeeks(), avg,
                stretches(analysis.stretchesByPlayer().getOrDefault(p.playerKey(), List.of()), nameByRoster));
    }

    private static List<StretchResult> stretches(List<OwnershipTimeline.Stretch> list, Map<Integer, String> nameByRoster) {
        List<StretchResult> out = new ArrayList<>();
        for (OwnershipTimeline.Stretch s : list) {
            out.add(new StretchResult(s.rosterId(), nameByRoster.getOrDefault(s.rosterId(), "Roster " + s.rosterId()),
                    s.firstWeek(), s.lastWeek(), s.how().name(), s.viaTxId(), s.startedPoints(), s.benchPoints(),
                    s.duplicateWeeks(), s.leadingUnstarted()));
        }
        return out;
    }

    // ------------------------------------------------------------------ parsing

    private static OwnershipTimeline.WeekRow weekRow(RosterWeekPointsRepository.WeekBreakdown b) {
        Map<String, Double> points = new LinkedHashMap<>();
        if (b.playersPointsJson() != null && !b.playersPointsJson().isBlank()) {
            JsonUtil.readMap(b.playersPointsJson()).forEach((k, v) -> {
                if (v instanceof Number n) points.put(k, n.doubleValue());
            });
        }
        Set<String> starters = b.startersJson() == null ? Set.of() : WeeklyReportService.startersOf(b.startersJson());
        return new OwnershipTimeline.WeekRow(b.week(), b.rosterId(), points, starters);
    }

    private static Map<String, Integer> idMap(String json) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        JsonUtil.readMap(json).forEach((k, v) -> {
            if (v instanceof Number n) out.put(k, n.intValue());
        });
        return out;
    }

    private static List<OwnershipTimeline.PickRef> picks(String json) {
        List<OwnershipTimeline.PickRef> out = new ArrayList<>();
        for (Map<String, Object> m : list(json)) {
            Integer season = intOf(m.get("season")), round = intOf(m.get("round"));
            Integer original = intOf(m.get("roster_id")), owner = intOf(m.get("owner_id"));
            if (season == null || round == null || original == null || owner == null) continue;
            out.add(new OwnershipTimeline.PickRef(season, round, original, owner));
        }
        return out;
    }

    private static List<OwnershipTimeline.FaabRef> faab(String json) {
        List<OwnershipTimeline.FaabRef> out = new ArrayList<>();
        for (Map<String, Object> m : list(json)) {
            Integer sender = intOf(m.get("sender")), receiver = intOf(m.get("receiver")), amount = intOf(m.get("amount"));
            if (sender == null || receiver == null || amount == null) continue;
            out.add(new OwnershipTimeline.FaabRef(sender, receiver, amount));
        }
        return out;
    }

    private static List<Map<String, Object>> list(String json) {
        if (json == null || json.isBlank()) return List.of();
        return JsonUtil.read(json, new TypeReference<List<Map<String, Object>>>() {});
    }

    /** Sleeper sends a pick's season as a string and its roster ids as numbers. */
    private static Integer intOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
