package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.store.DraftRepository;

/**
 * Spec 046 US4: the one place that refuses an auction draft where the simulator, a fork-to-mock or a
 * manual pick would treat its pick numbers as a snake order. Pick numbers in an auction are nomination
 * order, so a simulation of one answers a question nobody asked.
 *
 * <p>The rule itself is {@link DraftRepository#modelsSnakeOrder}; this class only turns it into the one
 * 409 body three entry points share ({@code ErrorHandler} maps {@link AuctionDraft}). It is deliberately
 * NOT applied to {@code GET /api/drafts/{id}/board} (spec 046 F1): that is the real completed board, not
 * the simulator, and must keep answering 200 for an auction draft.
 */
public final class AuctionGuard {

    public static final String CODE = "auction_draft";
    public static final String MESSAGE =
            "This is an auction draft. The simulator models snake pick order, which an auction doesn't have.";

    private AuctionGuard() {}

    /** Throws {@link AuctionDraft} when this draft's pick order is not a snake order. */
    public static void requireSnake(DraftRepository.DraftRow draft) {
        if (!DraftRepository.modelsSnakeOrder(draft.draftType())) throw new AuctionDraft();
    }

    /** Answered as 409 {@code {"error":"auction_draft","message":...}} by {@code ErrorHandler}. */
    public static class AuctionDraft extends RuntimeException {
        public AuctionDraft() {
            super(MESSAGE);
        }
    }
}
