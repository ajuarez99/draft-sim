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

    // decay^age for age in [0, runWindow), precomputed once per PickScorer
    // instance (one per simulation request, shared read-only across every
    // iteration/pick/candidate) instead of recomputed with Math.pow() inside
    // runPressure's per-candidate walk. Reading Math.pow(decay, k) from this
    // table is bit-identical to calling it inline -- it is the exact same
    // deterministic call, just made once instead of ~12.6M times.
    private final double[] decayPow;

    public PickScorer(ScoringProperties.Sport cfg, SportRules rules, PositionalPriors priors) {
        this.cfg = cfg;
        this.rules = rules;
        this.priors = priors;
        this.decayPow = new double[Math.max(1, cfg.runWindow())];
        for (int k = 0; k < decayPow.length; k++) decayPow[k] = Math.pow(cfg.runRecencyDecay(), k);
    }

    /**
     * @param lineup {@link SportRules#prepareLineup} for this roster, before
     *               {@code candidate} is added -- constant across every
     *               candidate at this pick, so compute it once per pick.
     * @param reachBias the seat's {@code profile.reachBias()} -- likewise
     *               constant across candidates at this pick.
     * @param positionalTerm {@code priors.logProbability(round, candidate.position())
     *               + log(tilt)} -- constant for every candidate sharing
     *               {@code candidate}'s position at this pick, so callers
     *               scoring a whole candidate pool should compute this once
     *               per position (see {@link #positionalTerm}) rather than
     *               once per candidate.
     * @param runTerm {@link #runPressure} for {@code candidate}'s position --
     *               likewise constant per position per pick.
     */
    public double score(BoardEntry candidate,
                        int pickNo,
                        Object lineup,
                        double reachBias,
                        double positionalTerm,
                        double runTerm) {

        ScoringProperties.Weights w = cfg.weights();

        double valueDelta = valueDelta(candidate, pickNo, reachBias);
        double need = rules.rosterNeed(candidate, lineup);

        return w.adp() * valueDelta
                + w.positionalPrior() * positionalTerm
                + w.rosterNeed() * need
                + w.runPressure() * runTerm;
    }

    /** {@code log P(pos | round) + log positionalTilt}, the position-only half of the score. */
    double positionalTerm(int round, Position pos, ManagerProfile profile) {
        return priors.logProbability(round, pos) + Math.log(Math.max(profile.tilt(pos), 1e-3));
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
        double matched = 0, total = 0;
        int age = 0;
        for (Position p : recent) {          // iteration order: most recent first
            double weight = decayPow[age++];
            total += weight;
            if (p == pos) matched += weight;
            if (age >= cfg.runWindow()) break;
        }
        return total == 0 ? 0 : matched / total;
    }
}
