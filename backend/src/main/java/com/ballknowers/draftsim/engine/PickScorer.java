package com.ballknowers.draftsim.engine;

import com.ballknowers.draftsim.config.ScoringProperties;
import com.ballknowers.draftsim.domain.*;
import com.ballknowers.draftsim.profile.ManagerProfile;
import com.ballknowers.draftsim.profile.PositionalPriors;
import com.ballknowers.draftsim.sport.SportRules;

import java.util.Deque;

/**
 * score = w_adp  * valueDelta
 *       + w_pos  * (log P(position | round) + log positionalTilt)
 *       + w_need * rosterNeed
 *       + w_run  * runPressure
 *
 * Every term is deliberately on a comparable scale (roughly -3..+3) so the
 * weights in weights.yml mean something when you change them.
 */
public final class PickScorer {

    private final ScoringProperties.Sport cfg;
    private final SportRules rules;
    private final PositionalPriors priors;

    public PickScorer(ScoringProperties.Sport cfg, SportRules rules, PositionalPriors priors) {
        this.cfg = cfg;
        this.rules = rules;
        this.priors = priors;
    }

    public double score(BoardEntry candidate,
                        int pickNo,
                        int round,
                        RosterState roster,
                        ManagerProfile profile,
                        LeagueSettings settings,
                        Deque<Position> recentPicks) {

        ScoringProperties.Weights w = cfg.weights();
        Position pos = candidate.position();

        double valueDelta = valueDelta(candidate, pickNo, profile.reachBias());
        double positional = priors.logProbability(round, pos) + Math.log(Math.max(profile.tilt(pos), 1e-3));
        double need = rules.rosterNeed(roster, candidate, settings);
        double run = runPressure(recentPicks, pos);

        return w.adp() * valueDelta
                + w.positionalPrior() * positional
                + w.rosterNeed() * need
                + w.runPressure() * run;
    }

    /**
     * How much of a bargain this player is at this pick.
     *
     *     valueDelta = (pickNo - boardPosition + reachBias) / adpScale
     *
     * Positive means he has fallen past where the board says he goes, so taking
     * him is value. Negative means taking him here is a reach. reachBias shifts
     * the whole curve for managers who habitually take players early: a manager
     * with a bias of +10 treats a ten-pick reach the way the room treats an
     * on-board pick.
     *
     * The sign matters and is easy to get backwards. A player with board
     * position 60 taken at pick 30 is a REACH (-2.5 at the default scale), not a
     * bargain; a player with board position 1 still there at pick 30 is the
     * bargain (+2.4).
     */
    double valueDelta(BoardEntry candidate, int pickNo, double reachBias) {
        double raw = (pickNo - candidate.adp() + reachBias) / cfg.adpScale();
        double c = cfg.valueDeltaClamp();
        return Math.max(-c, Math.min(c, raw));
    }

    /**
     * Recency-weighted share of the last N picks spent on this position.
     * Returns [0,1]. Football runs are real, QB and TE especially: once two
     * TEs go off the board in four picks, the rest of the room notices.
     */
    double runPressure(Deque<Position> recent, Position pos) {
        if (recent.isEmpty()) return 0.0;
        double decay = cfg.runRecencyDecay();
        double matched = 0, total = 0;
        int age = 0;
        for (Position p : recent) {          // iteration order: most recent first
            double weight = Math.pow(decay, age++);
            total += weight;
            if (p == pos) matched += weight;
            if (age >= cfg.runWindow()) break;
        }
        return total == 0 ? 0 : matched / total;
    }
}
