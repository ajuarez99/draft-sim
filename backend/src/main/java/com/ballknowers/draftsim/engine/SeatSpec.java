package com.ballknowers.draftsim.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Who is sitting in one draft slot.
 *
 * Three states, not two, and deliberately so. A batch simulation only needs to
 * know "modelled manager or league-average bot" — but the interactive mock room
 * (claude/next-features-roadmap.md, feature C) additionally needs to know which
 * single seat requires synchronous human input, and if the two features invent
 * their own seat shapes then the shared context builder ends up with two
 * callers passing different-shaped seat maps and the UI has nowhere consistent
 * to render into. §3.2 of that doc settles it: one 3-state shape everywhere.
 *
 * {@code USER} is modelled exactly like a {@code MANAGER} seat for scoring
 * purposes — it may carry a managerId, and does when the user's own Sleeper
 * account is one of the league's managers. The type says who supplies the
 * decision, not how the seat is scored.
 */
public record SeatSpec(int slot, Type type, Long managerId) {

    public enum Type {
        /** The human running this session. At most one per draft. */
        USER,
        /** A modelled manager with a profile fit from history and/or stated tendencies. */
        MANAGER,
        /** An unmodelled seat: the league-average drafter. */
        BOT
    }

    public SeatSpec {
        Objects.requireNonNull(type, "seat type");
        if (slot < 1) throw new IllegalArgumentException("slot must be 1-indexed, got " + slot);
        if (type == Type.MANAGER && managerId == null) {
            throw new IllegalArgumentException("MANAGER seat at slot " + slot + " needs a managerId");
        }
        if (type == Type.BOT && managerId != null) {
            throw new IllegalArgumentException("BOT seat at slot " + slot + " must not carry a managerId");
        }
    }

    public static SeatSpec user(int slot, Long managerId) {
        return new SeatSpec(slot, Type.USER, managerId);
    }

    public static SeatSpec manager(int slot, long managerId) {
        return new SeatSpec(slot, Type.MANAGER, managerId);
    }

    public static SeatSpec bot(int slot) {
        return new SeatSpec(slot, Type.BOT, null);
    }

    /** The slot the human occupies, or -1 if no seat claims to be the user's. */
    public static int userSlot(List<SeatSpec> seats) {
        for (SeatSpec s : seats) if (s.type() == Type.USER) return s.slot();
        return -1;
    }

    /**
     * A real draft's own {@code draft_order}, as seats: every slot Sleeper has
     * mapped to a manager becomes a modelled {@code MANAGER} seat, {@code mySlot}
     * is marked {@code USER} (carrying the manager id too, if Sleeper mapped it),
     * and anything left unmapped falls through to a league-average bot in
     * {@link DraftContextFactory#build}.
     *
     * Extracted out of {@code SimulationService.seatsOf} so the real-draft
     * simulation path and the mock room's "fork a live draft" path
     * (claude/next-features-roadmap.md's Phase 3/4 bridge) share one
     * implementation rather than two.
     *
     * @param slotToManager {@code DraftRepository.DraftRow.slotToManager()} --
     *                      String slot -> Number managerId, Jackson's shape for
     *                      a JSON object read back out of {@code draft.slot_to_manager}.
     */
    public static List<SeatSpec> fromDraftOrder(Map<String, Object> slotToManager, int mySlot) {
        List<SeatSpec> seats = new ArrayList<>();
        slotToManager.forEach((slot, managerId) -> {
            int s = Integer.parseInt(slot);
            long id = ((Number) managerId).longValue();
            seats.add(s == mySlot ? SeatSpec.user(s, id) : SeatSpec.manager(s, id));
        });
        if (seats.stream().noneMatch(x -> x.slot() == mySlot) && mySlot >= 1) {
            seats.add(SeatSpec.user(mySlot, null));
        }
        return seats;
    }
}
