package com.ballknowers.draftsim.sport;

import com.ballknowers.draftsim.domain.Sport;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the substitution seam ({@link SportRules}, a bare interface) into a
 * selection seam. Before this existed, {@code FootballRules} was injected by
 * type in two places ({@code DraftContextFactory}, {@code PowerRankingService}),
 * so a second {@code @Component} implementing the same interface would fail
 * application startup with {@code NoUniqueBeanDefinitionException} rather than
 * just being wrong at runtime. Indexing by {@link SportRules#sport()} here
 * means adding {@code BasketballRules} is an implementation, not a refactor of
 * every injection site.
 */
@Component
public class SportRulesRegistry {

    private final Map<Sport, SportRules> byRules;

    public SportRulesRegistry(List<SportRules> rules) {
        Map<Sport, SportRules> map = new EnumMap<>(Sport.class);
        for (SportRules r : rules) {
            Sport sport = r.sport();
            SportRules existing = map.put(sport, r);
            if (existing != null) {
                throw new IllegalStateException(
                        "Two SportRules implementations claim " + sport + ": "
                                + existing.getClass().getSimpleName() + " and "
                                + r.getClass().getSimpleName());
            }
        }
        this.byRules = map;
    }

    public SportRules get(Sport sport) {
        SportRules rules = byRules.get(sport);
        if (rules == null) {
            throw new IllegalStateException("no SportRules registered for " + sport);
        }
        return rules;
    }
}
