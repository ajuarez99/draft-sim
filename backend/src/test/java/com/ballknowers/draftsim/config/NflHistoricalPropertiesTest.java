package com.ballknowers.draftsim.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 056 T007: the real application.yml binds into {@link NflHistoricalProperties}, and bad values are refused. */
class NflHistoricalPropertiesTest {

    private static NflHistoricalProperties bound() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("historical-nfl", NflHistoricalProperties.class).get();
    }

    @Test
    void scoringKeysInTheYmlAreExactlyTheCountingKeysInTheirDisplayOrder() throws IOException {
        // review N3: the yml, NflHistoricalSport.COUNTING and StatOrder's football tail are one list.
        assertEquals(com.ballknowers.draftsim.historical.NflHistoricalSport.COUNTING,
                List.copyOf(bound().scoring().keySet()));
    }

    @Test
    void applicationYmlBindsWithTheLabelledValues() throws IOException {
        NflHistoricalProperties p = bound();
        assertTrue(p.statsUrlTemplate().contains("{season}"));
        assertTrue(p.statsUrlTemplate().endsWith(".csv.gz"));
        assertEquals(12, p.teams());
        assertEquals(15, p.rounds());
        assertEquals(0, p.reversalRound());
        assertEquals(List.of("QB", "RB", "RB", "WR", "WR", "TE", "FLEX"), p.roster().subList(0, 7));
        assertEquals(15, p.roster().size(), "7 starters + 8 bench = one slot per round");
        assertEquals(8, p.roster().stream().filter("BN"::equals).count());
        assertEquals(13, p.scoring().size());
        assertEquals(0.04, p.scoring().get("pass_yd"), 1e-12);
        assertEquals(-1.0, p.scoring().get("pass_int"), 1e-12);
        assertEquals(-2.0, p.scoring().get("fum_lost"), 1e-12);
        assertEquals(6.0, p.scoring().get("st_td"), 1e-12);
        assertEquals(2.0, p.scoring().get("rec_2pt"), 1e-12);
        assertEquals(17, p.rankStandardGames());
        assertEquals(6, p.rankMinGames());
        assertEquals(3, p.rankLookbackSeasons());
        assertEquals(12, p.rookiePositionalOffset());
        assertEquals(2, p.rookieMaxRound());
        assertEquals(2000, p.firstOffered());
        assertEquals(2025, p.lastOffered());
        assertEquals(248, p.expectedGames().get(2000));
        assertEquals(248, p.expectedGames().get(2001));
        assertEquals(256, p.expectedGames().get(2002));
        assertEquals(256, p.expectedGames().get(2020));
        assertEquals(272, p.expectedGames().get(2021));
        assertEquals(271, p.expectedGames().get(2022));
        assertEquals(272, p.expectedGames().get(2025));
        for (int y = 2000; y <= 2025; y++) assertNotNull(p.expectedGames().get(y), "year " + y);
    }

    private static NflHistoricalProperties with(String url, Integer teams, Map<Integer, Integer> games) {
        Map<String, Double> scoring = new LinkedHashMap<>();
        scoring.put("rec", 1.0);
        return new NflHistoricalProperties(url, "https://example.test/draft_picks.csv", teams, 15,
                List.of("QB", "BN"), 0, scoring, 17, 6, 3, 12, 2, games, 2000, 2001);
    }

    @Test
    void validationRefusesBadValues() {
        Map<Integer, Integer> ok = Map.of(2000, 248, 2001, 248);
        assertDoesNotThrow(() -> with("https://x.test/{season}.csv.gz", 12, ok));
        assertThrows(IllegalArgumentException.class, () -> with("https://x.test/stats.csv.gz", 12, ok), "no {season}");
        assertThrows(IllegalArgumentException.class, () -> with("https://x.test/{season}.csv.gz", 0, ok), "zero teams");
        assertThrows(IllegalArgumentException.class, () -> with("https://x.test/{season}.csv.gz", null, ok), "null teams");
        assertThrows(IllegalArgumentException.class,
                () -> with("https://x.test/{season}.csv.gz", 12, Map.of(2000, 248)), "an offered season has no game count");
        assertThrows(IllegalArgumentException.class,
                () -> with("https://x.test/{season}.csv.gz", 12, Map.of(2000, 248, 2001, 0)), "non-positive game count");
    }
}
