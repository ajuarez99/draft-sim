package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.Sport;
import com.ballknowers.draftsim.ingest.DraftOrderMapper;
import com.ballknowers.draftsim.store.BoardRepository;
import com.ballknowers.draftsim.store.DraftRepository;
import com.ballknowers.draftsim.store.LeagueRepository;
import com.ballknowers.draftsim.store.ManagerRepository;
import com.ballknowers.draftsim.store.PlayerRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spec 046 US1: the ADP compare payload -- Sleeper dynasty, Sleeper redraft and Yahoo side by side, with the
 * provenance of each (contracts/adp-compare-api.md, amended).
 *
 * <p>Plain records on purpose and no {@code Map.of} anywhere on this path: every player value, the team and
 * the draft are legitimately null, and {@code Map.of} throws on a null value.
 *
 * <p>The blend, the gap and the tints are computed client-side ({@code web/src/adpBlend.ts}) so the sliders
 * answer instantly; this class sends the inputs and does no judgement. The one number it carries for that
 * judgement is {@link #YAHOO_MIN_PERCENT_DRAFTED}, so the client's default and the server's note can't drift.
 */
@Service
public class AdpCompareService {

    /**
     * A Yahoo value from fewer than this share of leagues is not trusted: both the tint and the blend treat it
     * as unranked (research R6 amended, F7). Hand-set and ARBITRARY -- it is roughly where the tail compression
     * starts in the 2026-10-23 capture, not a fitted figure.
     */
    public static final double YAHOO_MIN_PERCENT_DRAFTED = 0.5;

    /** Every cost is floored here before sorting (F9): Yahoo rounds the cheap end to 0.x, and an auction minimum is $1. */
    static final double COST_FLOOR = 1.0;

    /**
     * @param key               sleeper_dynasty | sleeper_redraft | yahoo -- always all three, even with no capture
     * @param capturedOn        null when there is no capture, which the page words as "unavailable"
     * @param ranked            players the source ranked (for Yahoo: matched plus unmatched); 0 with no capture
     * @param scoring           the scoring the source's drafters play, a fact about the source: dynasty / redraft
     * @param unmatched         Yahoo only: ranked players no local player matched; null for the others
     * @param deepestAdp        Yahoo only: the deepest ADP it ranks, as context (no longer a tint rule); null otherwise
     * @param minPercentDrafted Yahoo only: {@link #YAHOO_MIN_PERCENT_DRAFTED}; null otherwise
     */
    public record Source(String key, String label, LocalDate capturedOn, int ranked, String scoring,
                         Integer unmatched, Double deepestAdp, Double minPercentDrafted) {}

    /**
     * One row. Every ADP field is null when that source doesn't rank the player. {@code playerId} and
     * {@code sleeperId} are null for a Yahoo player no local player matched, which carries only Yahoo fields.
     */
    public record Player(Long playerId, String sleeperId, String name, String team, List<String> positions,
                         Integer yearsExp, Double dynasty, Double redraft, Double yahooAdp, Double yahooCost,
                         Double yahooPctDrafted, Sale sale) {}

    /** What one player sold for in this league's auction (spec 046 US3); null on a row nobody has bought. */
    public record Sale(Integer amount, Long managerId, int pickNo) {}

    /**
     * One draft slot's money (spec 046 US3, data-model E3 amended: keyed by slot, not by seat, so the cards
     * render before the order is set). {@code managerId}/{@code name}/{@code avatar} are null while the slot has
     * no manager. {@code left} and {@code maxBid} are null only when the draft reports no budget.
     *
     * @param spent        sum of the slot's prices; a pick with no price counts as 0 and in {@code priceMissing}
     * @param priceMissing picks whose price Sleeper didn't send, so {@code spent} is a floor, not a fact
     * @param maxBid       what the slot can open at and still afford $1 for every other empty spot
     * @param mine         whether the caller (X-Sleeper-User) is this slot's manager, so the page can outline
     *                     their card; false for a signed-out caller or a slot with no manager
     */
    public record TeamState(Long managerId, String name, String avatar, int slot, int spent, int filled,
                            int spotsLeft, Integer left, Integer maxBid, int priceMissing, boolean mine) {}

    /** One row of the latest-sales feed. {@code name} is never blank: player, else Sleeper's name, else "Unknown player". */
    public record SaleRow(int pickNo, Integer amount, Long managerId, Long playerId, String name, boolean priceMissing) {}

    /**
     * The league's draft, when it has one and it is a Sleeper draft. {@code teamsState} and {@code sales} exist
     * only for an auction and are OMITTED (not empty) for a snake draft, so a snake page can't render an empty
     * budget grid.
     */
    public record Draft(String sleeperDraftId, String type, String status, int teams, int rounds, Integer budget,
                        @JsonInclude(JsonInclude.Include.NON_NULL) List<TeamState> teamsState,
                        @JsonInclude(JsonInclude.Include.NON_NULL) List<SaleRow> sales) {}

    public record Response(String sport, List<Source> sources, List<Player> players, List<Double> costCurve,
                           Draft draft) {}

    private final BoardRepository boards;
    private final PlayerRepository players;
    private final DraftRepository drafts;
    private final ManagerRepository managers;

    public AdpCompareService(BoardRepository boards, PlayerRepository players, DraftRepository drafts,
                             ManagerRepository managers) {
        this.boards = boards;
        this.players = players;
        this.drafts = drafts;
        this.managers = managers;
    }

    /** Empty for a sport with no compare sources (football): the controller answers 404. */
    public Optional<Response> forLeague(LeagueRepository.LeagueRow league) {
        return forLeague(league, null);
    }

    /** @param sleeperUserId the caller's public, unverified Sleeper id, or null; only used to mark their card */
    public Optional<Response> forLeague(LeagueRepository.LeagueRow league, String sleeperUserId) {
        if (league.sport() != Sport.NBA) return Optional.empty();
        Sport sport = league.sport();

        Optional<BoardRepository.LatestCapture> dyn = boards.latestCaptureDetail(sport, BoardRepository.SOURCE_SLEEPER_DYNASTY);
        Optional<BoardRepository.LatestCapture> red = boards.latestCaptureDetail(sport, BoardRepository.SOURCE_SLEEPER_REDRAFT);
        Optional<BoardRepository.LatestCapture> yah = boards.latestCaptureDetail(sport, BoardRepository.SOURCE_YAHOO);

        // One mutable accumulator per local player id; the unmatched Yahoo rows are appended after.
        Map<Long, Acc> byId = new LinkedHashMap<>();
        Optional<DraftRepository.DraftRow> draftRow = league.isSleeper() ? drafts.forLeague(league.id()) : Optional.empty();
        boolean auction = draftRow.isPresent() && !DraftRepository.modelsSnakeOrder(draftRow.get().draftType());
        List<DraftRepository.PickRow> picks = auction ? drafts.picks(draftRow.get().id()) : List.of();
        Map<Long, Sale> saleByPlayer = new HashMap<>();
        for (DraftRepository.PickRow pk : picks) {
            if (pk.playerId() != null) saleByPlayer.putIfAbsent(pk.playerId(), new Sale(pk.amount(), pk.managerId(), pk.pickNo()));
        }
        dyn.ifPresent(c -> c.rows().forEach(r -> byId.computeIfAbsent(r.playerId(), k -> new Acc()).dynasty = r.adp()));
        red.ifPresent(c -> c.rows().forEach(r -> byId.computeIfAbsent(r.playerId(), k -> new Acc()).redraft = r.adp()));
        yah.ifPresent(c -> c.rows().forEach(r -> {
            Acc a = byId.computeIfAbsent(r.playerId(), k -> new Acc());
            a.yahooAdp = r.adp();
            a.yahooCost = r.avgCost();
            a.yahooPct = r.percentDrafted();
        }));

        Map<Long, com.ballknowers.draftsim.domain.Player> known = new HashMap<>();
        if (!byId.isEmpty() || !picks.isEmpty()) {
            for (com.ballknowers.draftsim.domain.Player p : this.players.findAll(sport)) known.put(p.id(), p);
        }

        List<Player> rows = new ArrayList<>();
        for (Map.Entry<Long, Acc> e : byId.entrySet()) {
            com.ballknowers.draftsim.domain.Player p = known.get(e.getKey());
            if (p == null) continue;   // a snapshot row whose player is gone (the cascade makes this unreachable)
            Acc a = e.getValue();
            rows.add(new Player(p.id(), p.sleeperId(), p.name(), p.team(),
                    p.positions().stream().map(Position::name).toList(), p.yearsExp(),
                    a.dynasty, a.redraft, a.yahooAdp, a.yahooCost, a.yahooPct, saleByPlayer.get(p.id())));
        }
        if (yah.isPresent()) {
            for (BoardRepository.UnmatchedRow u : yah.get().unmatched()) {
                rows.add(new Player(null, null, u.name(), u.team(), splitPositions(u.positions()), null,
                        null, null, u.adp(), u.avgCost(), u.percentDrafted(), null));
            }
        }
        rows.sort(Comparator
                .comparing(Player::dynasty, Comparator.nullsLast(Comparator.<Double>naturalOrder()))
                .thenComparing(Player::redraft, Comparator.nullsLast(Comparator.<Double>naturalOrder()))
                .thenComparing(Player::yahooAdp, Comparator.nullsLast(Comparator.<Double>naturalOrder()))
                .thenComparing(Player::name, Comparator.nullsLast(Comparator.<String>naturalOrder())));

        Double deepest = null;
        int unmatched = 0;
        if (yah.isPresent()) {
            for (BoardRepository.CaptureRow r : yah.get().rows()) if (deepest == null || r.adp() > deepest) deepest = r.adp();
            for (BoardRepository.UnmatchedRow u : yah.get().unmatched()) {
                unmatched++;
                if (u.adp() != null && (deepest == null || u.adp() > deepest)) deepest = u.adp();
            }
        }

        List<Source> sources = List.of(
                new Source(BoardRepository.SOURCE_SLEEPER_DYNASTY, "Sleeper dynasty ADP",
                        dyn.map(BoardRepository.LatestCapture::capturedOn).orElse(null),
                        dyn.map(c -> c.rows().size()).orElse(0), "dynasty", null, null, null),
                new Source(BoardRepository.SOURCE_SLEEPER_REDRAFT, "Sleeper redraft ADP",
                        red.map(BoardRepository.LatestCapture::capturedOn).orElse(null),
                        red.map(c -> c.rows().size()).orElse(0), "redraft", null, null, null),
                new Source(BoardRepository.SOURCE_YAHOO, "Yahoo ADP",
                        yah.map(BoardRepository.LatestCapture::capturedOn).orElse(null),
                        yah.map(c -> c.rows().size() + c.unmatched().size()).orElse(0), "redraft",
                        unmatched, deepest, YAHOO_MIN_PERCENT_DRAFTED));

        // Yahoo's price curve: every ranked player's average cost (matched or not -- it is Yahoo's distribution),
        // floored at $1 before sorting, descending. Empty without a capture: the client says "unavailable".
        List<Double> curve = new ArrayList<>();
        yah.ifPresent(c -> {
            c.rows().forEach(r -> { if (r.avgCost() != null) curve.add(Math.max(COST_FLOOR, r.avgCost())); });
            c.unmatched().forEach(u -> { if (u.avgCost() != null) curve.add(Math.max(COST_FLOOR, u.avgCost())); });
        });
        curve.sort(Comparator.reverseOrder());

        return Optional.of(new Response(sport.code(), sources, rows, curve,
                draftRow.map(d -> draftOf(d, picks, auction, known, sleeperUserId)).orElse(null)));
    }

    /**
     * The draft block (an ESPN league passes an empty row: no ESPN draft here). For an auction it adds the
     * per-slot money and the sales feed.
     */
    private Draft draftOf(DraftRepository.DraftRow d, List<DraftRepository.PickRow> picks, boolean auction,
                          Map<Long, com.ballknowers.draftsim.domain.Player> known, String sleeperUserId) {
        if (!auction) return new Draft(d.sleeperDraftId(), d.draftType(), d.status(), d.teams(), d.rounds(), d.budget(), null, null);
        return new Draft(d.sleeperDraftId(), d.draftType(), d.status(), d.teams(), d.rounds(), d.budget(),
                teamsState(d, picks, sleeperUserId), sales(picks, known));
    }

    /**
     * One entry per draft slot 1..teams, from the picks alone (spec 046 FR-011, E3 amended). The manager is
     * decoration read from {@code slot_to_manager}: the money never depends on it, so a draft whose order isn't
     * set yet still shows untouched budgets.
     */
    private List<TeamState> teamsState(DraftRepository.DraftRow d, List<DraftRepository.PickRow> picks, String sleeperUserId) {
        Map<String, Long> bySlot = DraftOrderMapper.normalize(d.slotToManager());
        Map<Long, String> names = managers.names();
        Map<Long, String> avatars = managers.avatarIds();
        Long viewer = sleeperUserId == null || sleeperUserId.isBlank() ? null : managers.idsBySleeperUserId().get(sleeperUserId);
        List<TeamState> out = new ArrayList<>();
        for (int slot = 1; slot <= d.teams(); slot++) {
            int spent = 0, filled = 0, missing = 0;
            for (DraftRepository.PickRow pk : picks) {
                if (pk.draftSlot() != slot) continue;
                filled++;
                if (pk.amount() == null) missing++; else spent += pk.amount();
            }
            int spotsLeft = d.rounds() - filled;
            Integer left = d.budget() == null ? null : d.budget() - spent;
            // Every other empty spot must still be able to cost $1, so one spot can open at left - (spotsLeft - 1).
            Integer maxBid = left == null ? null : spotsLeft > 0 ? Math.max(0, left - (spotsLeft - 1)) : 0;
            Long managerId = bySlot.get(String.valueOf(slot));
            out.add(new TeamState(managerId, managerId == null ? null : names.get(managerId),
                    managerId == null ? null : avatars.get(managerId), slot, spent, filled, spotsLeft, left, maxBid, missing,
                    managerId != null && managerId.equals(viewer)));
        }
        return out;
    }

    /** Newest sale first. A sale whose player never resolved is still named from what Sleeper called him (F4). */
    private static List<SaleRow> sales(List<DraftRepository.PickRow> picks, Map<Long, com.ballknowers.draftsim.domain.Player> known) {
        List<SaleRow> out = new ArrayList<>();
        for (DraftRepository.PickRow pk : picks) {
            com.ballknowers.draftsim.domain.Player p = pk.playerId() == null ? null : known.get(pk.playerId());
            String name = p != null ? p.name()
                    : pk.playerName() != null && !pk.playerName().isBlank() ? pk.playerName() : "Unknown player";
            out.add(new SaleRow(pk.pickNo(), pk.amount(), pk.managerId(), pk.playerId(), name, pk.amount() == null));
        }
        out.sort(Comparator.comparingInt(SaleRow::pickNo).reversed());
        return out;
    }

    private static List<String> splitPositions(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : raw.split(",")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    private static final class Acc {
        Double dynasty, redraft, yahooAdp, yahooCost, yahooPct;
    }
}
