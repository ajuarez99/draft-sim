package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.HistoricalSeasonPickProperties;
import com.ballknowers.draftsim.historical.HistoricalDataRepository.SeasonRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The random-year draw's lean toward recent seasons. The tests that matter are about DIRECTION (lessons: a weighting
 * can be well-formed and pointed backwards): recent seasons must come up MORE, by exactly the configured ratio, and
 * old seasons must stay reachable. The draw is swept on an even grid of u, so every count here is exact, not sampled.
 */
class SeasonSkewTest {

    /** Basketball's real offered range. */
    private static final List<SeasonRow> NBA = seasons(1970, 2025);

    private static List<SeasonRow> seasons(int from, int to) {
        List<SeasonRow> out = new ArrayList<>();
        for (int s = from; s <= to; s++) {
            out.add(new SeasonRow("NBA", s, s + "-" + (s + 1) % 100, "KAGGLE", null, null, 24, 1230, List.of(),
                    "{}", "{}", "PRIOR", null, null, 0, null));
        }
        return out;
    }

    /** How often each season comes up when u walks an even grid of {@code steps} points over [0, 1). */
    private static Map<Integer, Integer> sweep(SeasonSkew skew, List<SeasonRow> offered, int steps) {
        Map<Integer, Integer> hits = new HashMap<>();
        for (int i = 0; i < steps; i++) {
            hits.merge(skew.pick(offered, (i + 0.5) / steps).seasonStart(), 1, Integer::sum);
        }
        return hits;
    }

    @Test
    void recentSeasonsComeUpMoreByExactlyTheConfiguredRatio() {
        SeasonSkew skew = new SeasonSkew(25, 4.0);
        // 25 recent x 4 + 31 old x 1 = 131 units; 100 grid points per unit.
        Map<Integer, Integer> hits = sweep(skew, NBA, 13_100);

        for (int s = 1970; s <= 2000; s++) assertEquals(100, hits.get(s), "old season " + s);
        for (int s = 2001; s <= 2025; s++) assertEquals(400, hits.get(s), "recent season " + s);

        int recent = hits.entrySet().stream().filter(e -> e.getKey() >= 2001).mapToInt(Map.Entry::getValue).sum();
        assertEquals(10_000, recent, "100 of every 131 draws is 2001 or later (76%)");
    }

    @Test
    void theWindowIsTheLast25SeasonsCountedBackFromTheNewestOffered() {
        SeasonSkew skew = new SeasonSkew(25, 4.0);
        assertEquals(1.0, skew.weight(2000, 2025), "2000-01 is the 26th newest: old");
        assertEquals(4.0, skew.weight(2001, 2025), "2001-02 is the 25th newest: recent");
        assertEquals(4.0, skew.weight(2025, 2025));
        // A partial import moves the window with it, instead of leaving it pinned to a year.
        assertEquals(4.0, skew.weight(1996, 2020));
        assertEquals(1.0, skew.weight(1995, 2020));
    }

    @Test
    void noneIsAFlatDrawOverEverySeason() {
        Map<Integer, Integer> hits = sweep(SeasonSkew.NONE, NBA, 5_600);
        assertEquals(56, hits.size());
        hits.forEach((s, n) -> assertEquals(100, n, "season " + s));
    }

    @Test
    void theEndsOfTheDrawLandOnTheFirstAndLastSeason() {
        SeasonSkew skew = new SeasonSkew(25, 4.0);
        assertEquals(1970, skew.pick(NBA, 0.0).seasonStart());
        assertEquals(2025, skew.pick(NBA, Math.nextDown(1.0)).seasonStart());
        assertEquals(2025, skew.pick(NBA, 1.0).seasonStart(), "an out-of-contract u must not walk off the list");
    }

    @Test
    void orderOfTheOfferedListDoesNotChangeASeasonsShare() {
        List<SeasonRow> reversed = new ArrayList<>(NBA);
        java.util.Collections.reverse(reversed);
        assertEquals(sweep(new SeasonSkew(25, 4.0), NBA, 13_100), sweep(new SeasonSkew(25, 4.0), reversed, 13_100));
    }

    @Test
    void aSingleSeasonIsAlwaysPicked() {
        assertEquals(1895, new SeasonSkew(25, 4.0).pick(seasons(1895, 1895), 0.7).seasonStart());
    }

    @Test
    void badNumbersAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new SeasonSkew(-1, 4.0));
        assertThrows(IllegalArgumentException.class, () -> new SeasonSkew(25, 0));
        assertThrows(IllegalArgumentException.class, () -> new SeasonSkew(25, -2));
        assertThrows(IllegalArgumentException.class, () -> new SeasonSkew(25, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new SeasonSkew(25, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> SeasonSkew.NONE.pick(List.of(), 0.5));
    }

    /** The real application.yml binds to the labelled values (kebab-case keys onto a nested record). */
    @Test
    void applicationYmlBindsTheBasketballSkew() throws java.io.IOException {
        var sources = new org.springframework.core.env.MutablePropertySources();
        new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        HistoricalSeasonPickProperties p = new org.springframework.boot.context.properties.bind.Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources))
                .bind("historical-season-pick", HistoricalSeasonPickProperties.class).get();
        assertEquals(new SeasonSkew(25, 4.0), p.nba());
    }

    @Test
    void aMissingConfigBlockIsAFlatDraw() {
        assertSame(SeasonSkew.NONE, new HistoricalSeasonPickProperties(null).nba());
    }
}
