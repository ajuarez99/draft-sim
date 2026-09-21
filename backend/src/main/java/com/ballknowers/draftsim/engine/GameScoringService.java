package com.ballknowers.draftsim.engine;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * What one game is worth under one league's scoring
 * (specs/005-daily-weekly-top-players, research R3).
 *
 * <p>Split out rather than inlined into the weekly report because three callers
 * need it -- Best Nights, Best Week, and anything later that reads a game -- and
 * "what is this game worth" must have one implementation. Two implementations of
 * one rule is the defect this repo has shipped under a different name three
 * times; {@code FootballRules.startingLineup}'s javadoc names it.
 *
 * <p><b>Generic by measurement, not by taste.</b> The sum runs key-by-key over
 * whatever the league scores, and that is what lets one method serve leagues
 * with different settings. Verified 2026-09-19 against both ingested NBA
 * leagues, whose scoring genuinely differs -- {@code dd} is 1.0 in one and 2.0
 * in the other, {@code td} 2.0 against 3.0, and only one has
 * {@code bonus_ast_15p} at all. The stored weekly value matched a computed game
 * in 10 of 10 weeks for the 2024 league and 18 of 18 across two players for
 * 2025.
 *
 * <p>Sleeper's precomputed {@code pts_std} is deliberately never read: it is
 * <i>standard</i> scoring and matches neither league. For one week of Jokić it
 * gives 56.5/32.0/43.0/44.5 where the league scores 58.5/34.0/44.0/45.5.
 */
@Service
public class GameScoringService {

    /**
     * @param scoring the league's own {@code scoring_json}, category to multiplier
     * @param stats   one game's raw stat line
     * @return the game's points under that scoring
     */
    public double score(Map<String, ? extends Number> scoring, Map<String, ?> stats) {
        if (scoring == null || stats == null) return 0.0;

        double total = 0.0;
        for (Map.Entry<String, ? extends Number> e : scoring.entrySet()) {
            // Iterating the SCORING keys, not the stat keys, is the direction that
            // matters. A stat the league does not score must contribute nothing,
            // and a category the league scores but the game did not produce is a
            // zero rather than a missing entry -- which is also why a box score
            // gaining a new field upstream cannot silently change a total.
            Object raw = stats.get(e.getKey());
            if (!(raw instanceof Number n)) continue;
            total += e.getValue().doubleValue() * n.doubleValue();
        }
        // Half-point categories make exact ties common and floating error visible;
        // two decimals is well inside what any fantasy scoring expresses.
        return Math.round(total * 100.0) / 100.0;
    }
}
