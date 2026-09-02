package com.ballknowers.draftsim.engine;

import java.util.List;
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
}
