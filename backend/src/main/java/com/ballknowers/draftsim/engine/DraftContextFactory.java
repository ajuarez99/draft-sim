package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.BoardEntry;
import com.ballknowers.draftsim.domain.LeagueSettings;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.sport.SportRules;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place a {@link DraftContext} is assembled.
 *
 * Everything that decides how a simulation behaves and is not the board itself
 * lands here: which seat is which manager, which are unmodelled, and whether
 * the requested shape is one the board can actually support. Three callers
 * want it — the existing league-backed {@code POST /api/sims}, the ad-hoc
 * league-size branch of the same endpoint, and the mock room's session start
 * (claude/next-features-roadmap.md §2b) — and the point of building it once is
 * that they cannot drift apart.
 *
 * Validation is here rather than in the controllers for the same reason: an
 * unsupported team count or a duplicated slot should fail identically no
 * matter which door it came through.
 */
@Component
public class DraftContextFactory {

    private final SportRules rules;
    private final ScoringProperties scoring;

    public DraftContextFactory(SportRules rules, ScoringProperties scoring) {
        this.rules = rules;
        this.scoring = scoring;
    }

    /**
     * @param seats one entry per occupied slot; slots not listed become
     *              league-average bots. Slots must be within 1..teams and
     *              must not repeat, and at most one may be {@code USER}.
     * @param fittedProfiles profiles by manager id, from
     *              {@link com.ballknowers.draftsim.profile.ProfileService.Fit#profiles()}.
     *              A manager with no entry is a modelled seat we happen to know
     *              nothing about, which is the neutral profile, not an error.
     * @param completedPicks pickNo -> player id for picks already made. Empty
     *              for a cold start.
     */
    public DraftContext build(LeagueSettings settings,
                              List<SeatSpec> seats,
                              Map<Long, ManagerProfile> fittedProfiles,
                              PositionalPriors priors,
                              List<BoardEntry> board,
                              Map<Integer, Long> completedPicks) {

        validate(settings, seats, board);

        Map<Integer, ManagerProfile> bySlot = new HashMap<>();
        for (SeatSpec seat : seats) {
            if (seat.managerId() == null) continue;   // BOT, or a USER with no Sleeper identity
            ManagerProfile p = fittedProfiles.get(seat.managerId());
            bySlot.put(seat.slot(),
                    p != null ? p : ManagerProfile.neutral(seat.managerId(), "seat " + seat.slot()));
        }
        // Every remaining seat is the league-average drafter. Filled in here
        // rather than left to DraftContext.profileFor's fallback so the map is
        // complete and inspectable -- the confidence copy counts these.
        for (int slot = 1; slot <= settings.teams(); slot++) {
            bySlot.putIfAbsent(slot, ManagerProfile.neutral(-1, "seat " + slot));
        }

        Map<Integer, Long> completed = completedPicks == null ? Map.of() : completedPicks;
        return new DraftContext(
                board, settings, bySlot, priors, rules, scoring.football(),
                completed.keySet().stream().sorted().toList(), completed);
    }

    /** Convenience for the ad-hoc path: a shape and its seats, no DB row anywhere. */
    public DraftContext build(LeagueShape shape,
                              List<SeatSpec> seats,
                              Map<Long, ManagerProfile> fittedProfiles,
                              PositionalPriors priors,
                              List<BoardEntry> board,
                              Map<Integer, Long> completedPicks) {
        return build(shape.toSettings(), seats, fittedProfiles, priors, board, completedPicks);
    }

    private void validate(LeagueSettings settings, List<SeatSpec> seats, List<BoardEntry> board) {
        if (board == null || board.isEmpty()) {
            throw new IllegalStateException("board is empty — run ingest first");
        }

        int totalPicks = settings.teams() * settings.rounds();
        if (board.size() < totalPicks) {
            // Not a technicality: the simulator draws candidates off the tail of
            // the board once it gets deep, and a board shorter than the draft
            // means the last rounds are chosen from nothing.
            throw new IllegalArgumentException(
                    "board has " + board.size() + " players but " + settings.teams() + " teams x "
                            + settings.rounds() + " rounds needs " + totalPicks);
        }

        Set<Integer> seen = new HashSet<>();
        List<Integer> userSlots = new ArrayList<>();
        for (SeatSpec seat : seats) {
            if (seat.slot() > settings.teams()) {
                throw new IllegalArgumentException(
                        "seat at slot " + seat.slot() + " is outside a " + settings.teams() + "-team draft");
            }
            if (!seen.add(seat.slot())) {
                throw new IllegalArgumentException("two seats claim slot " + seat.slot());
            }
            if (seat.type() == SeatSpec.Type.USER) userSlots.add(seat.slot());
        }
        if (userSlots.size() > 1) {
            throw new IllegalArgumentException("more than one USER seat: slots " + userSlots);
        }
    }
}
