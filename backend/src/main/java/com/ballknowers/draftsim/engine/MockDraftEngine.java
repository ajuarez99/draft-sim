package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.DraftSlot;
import com.ballknowers.draftsim.domain.Position;
import com.ballknowers.draftsim.domain.RosterState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Advances a mock draft session up to the next {@code USER} seat, or to the
 * end of the draft if none remain. Pure and DB-free, like {@link DraftSimulator}
 * and {@link com.ballknowers.draftsim.engine.MonteCarloRunner} -- the mock
 * room's service layer owns persistence, this owns the decision.
 *
 * {@code run()} has no concept of "stop early for a human" -- its whole job is
 * to speculatively decide every remaining pick, including the viewing user's
 * own future ones, for the probability board. This type is genuinely new for
 * that reason, but its inner loop is 100% {@link PickDecider#decideAndApply} --
 * no second scoring implementation (claude/next-features-roadmap.md §4, Phase 3).
 */
@Component
public final class MockDraftEngine {

    private static final Logger log = LoggerFactory.getLogger(MockDraftEngine.class);

    /** Spreads a single session seed across picks, same constant MonteCarloRunner
     * uses to spread one seed across iterations -- not a shared sequence, just a
     * reused, already-established way to turn one seed into many independent ones. */
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    /** One pick decided by this advance -- either a replay is skipped (already
     * known) or a BOT/MANAGER seat's decision, which the caller persists. */
    public record Decision(int pickNo, int round, int slot, SeatSpec.Type seatType,
                           Long managerId, BoardEntry player) {}

    public record AdvanceResult(List<Decision> newPicks, int nextPickNo, boolean complete) {}

    /**
     * @param ctx built from the session's own snapshotted shape/seats/board,
     *            with {@code completedPicks} set to the session's own picks so far.
     * @param seats the same seat list {@code ctx} was built from -- passed
     *            separately because {@link DraftContext} only exposes profiles
     *            by slot, not seat type.
     * @param rngSeed the session's own stored seed; each newly-decided pick
     *            derives its own independent {@link SplittableRandom} from it,
     *            so bot decisions are reproducible across a stateless service
     *            with no long-lived RNG to carry between HTTP calls.
     */
    public AdvanceResult advanceUntilUserOrEnd(DraftContext ctx, List<SeatSpec> seats, long rngSeed) {
        int teams = ctx.settings().teams();
        int rounds = ctx.settings().rounds();
        int total = ctx.totalPicks();

        Map<Integer, SeatSpec> seatBySlot = new HashMap<>();
        for (SeatSpec s : seats) seatBySlot.put(s.slot(), s);

        PickScorer scorer = new PickScorer(ctx.cfg(), ctx.rules(), ctx.priors());
        PickDecider decider = new PickDecider(ctx, scorer, ctx.cfg().temperature());

        List<BoardEntry> available = new ArrayList<>(ctx.board());
        RosterState[] rosters = new RosterState[teams + 1];
        for (int s = 1; s <= teams; s++) rosters[s] = new RosterState();
        Deque<Position> recent = new ArrayDeque<>();
        Map<Long, BoardEntry> byId = ctx.byId();

        List<Decision> newPicks = new ArrayList<>();
        int pickNo = 1;
        boolean complete = false;
        for (; pickNo <= total; pickNo++) {
            int round = DraftSlot.round(pickNo, teams);
            int slot = DraftSlot.slot(pickNo, teams);

            long already = ctx.completedAt(pickNo);
            if (already != 0L) {
                BoardEntry e = byId.get(already);
                if (e != null && available.remove(e)) {
                    rosters[slot].add(e);
                    recent.addFirst(e.position());
                } else if (e != null) {
                    // Same class of silent corruption DraftSimulator's own
                    // WARNED_DUPLICATES guard exists to surface (see its
                    // comment) -- here it's a single call per HTTP request
                    // rather than one of thousands of iterations, so there's
                    // no log-spam risk and no need for that guard's dedup.
                    log.warn("mock session replay: pick {} names player {} who is already off the board"
                            + " -- skipping; the board may have changed since this pick was recorded",
                            pickNo, already);
                }
                continue;
            }

            SeatSpec seat = seatBySlot.get(slot);
            SeatSpec.Type type = seat != null ? seat.type() : SeatSpec.Type.BOT;
            if (type == SeatSpec.Type.USER) {
                break;   // waiting on the human; stop here without deciding it
            }

            SplittableRandom rng = new SplittableRandom(rngSeed + pickNo * GOLDEN);
            BoardEntry choice = decider.decideAndApply(
                    available, pickNo, round, rounds, total, slot, rosters[slot], recent, rng);
            if (choice == null) {
                complete = true;   // board exhausted -- nothing left to draft
                break;
            }
            newPicks.add(new Decision(pickNo, round, slot, type,
                    seat != null ? seat.managerId() : null, choice));
        }

        if (pickNo > total) complete = true;
        return new AdvanceResult(newPicks, pickNo, complete);
    }
}
