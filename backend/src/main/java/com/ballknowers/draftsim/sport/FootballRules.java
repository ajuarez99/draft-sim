package com.ballknowers.draftsim.sport;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Football need model.
 *
 * Starting slots are rigid and positional value drops sharply, so need is the
 * marginal improvement to expected starting-lineup value from adding a player,
 * expressed as a fraction of that player's own value.
 *
 * Value is derived from board position, not from projected points: we have no
 * projection source wired up. That is a real approximation — the board encodes
 * the market's cross-positional view, which is close to but not the same as
 * expected fantasy points. Swap {@link #value} for a projection lookup when one
 * exists and nothing else here has to change.
 */
@Component
public class FootballRules implements SportRules {

    private final ScoringProperties.Sport cfg;

    public FootballRules(ScoringProperties props) {
        this.cfg = props.football();
    }

    @Override
    public Sport sport() {
        return Sport.NFL;
    }

    @Override
    public double value(BoardEntry entry) {
        return Math.exp(-entry.adp() / cfg.valueDecay());
    }

    @Override
    public double rosterNeed(RosterState roster, BoardEntry candidate, LeagueSettings settings) {
        double before = startingLineupValue(roster, settings);
        RosterState after = roster.copy();
        after.add(candidate);
        double delta = startingLineupValue(after, settings) - before;

        double own = value(candidate);
        if (own <= 0) return cfg.benchFloor();

        double captured = Math.max(0.0, Math.min(1.0, delta / own));
        // Depth is not worthless: byes, injuries, and upside all make a bench
        // player worth something. benchFloor keeps late-round picks from
        // scoring at exactly zero need.
        return cfg.benchFloor() + (1.0 - cfg.benchFloor()) * captured;
    }

    /**
     * Greedy assignment: fill dedicated slots with the best player at each
     * position, then fill FLEX from whatever RB/WR/TE are left. Greedy is
     * optimal here because FLEX accepts a superset of the dedicated slots it
     * competes with, so no dedicated slot ever wants a player FLEX took.
     */
    double startingLineupValue(RosterState roster, LeagueSettings settings) {
        double total = 0;
        List<BoardEntry> flexPool = new ArrayList<>();

        for (Map.Entry<Position, Integer> e : settings.dedicatedStarters().entrySet()) {
            Position pos = e.getKey();
            int slots = e.getValue();
            List<BoardEntry> have = roster.at(pos);
            for (int i = 0; i < have.size(); i++) {
                if (i < slots) {
                    total += value(have.get(i));
                } else if (pos.isFlexEligible()) {
                    flexPool.add(have.get(i));
                }
            }
        }

        flexPool.sort((a, b) -> Double.compare(value(b), value(a)));
        int flex = settings.flexSlots();
        for (int i = 0; i < Math.min(flex, flexPool.size()); i++) {
            total += value(flexPool.get(i));
        }
        return total;
    }

    @Override
    public boolean isDraftable(BoardEntry entry, int round) {
        Integer min = cfg.earliestRound().get(entry.position().name());
        return min == null || round >= min;
    }

    @Override
    public boolean isEligible(Player player, String rosterSlot) {
        if ("BN".equals(rosterSlot)) return true;
        if ("FLEX".equals(rosterSlot)) {
            return player.positions().stream().anyMatch(Position::isFlexEligible);
        }
        return player.positions().stream().anyMatch(p -> p.name().equals(rosterSlot));
    }
}
