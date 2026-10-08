package com.ballknowers.draftsim.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 022 Cup-final decision: the excluded-game-ids list binds as strings, from the real weights.yml too. */
class NbaGamePropertiesTest {

    private static NbaGameProperties bind(PropertySource<?> source) {
        Binder binder = new Binder(ConfigurationPropertySources.from(source));
        return binder.bind("draftsim.nba-games", NbaGameProperties.class).orElseGet(NbaGameProperties::none);
    }

    @Test
    void missingListBindsToEmpty() {
        NbaGameProperties p = bind(new MapPropertySource("t", Map.of("draftsim.other", "x")));
        assertEquals(List.of(), p.excludedGameIds());
        assertTrue(p.excludedGameIdSet().isEmpty());
        assertEquals(List.of(), new NbaGameProperties(null).excludedGameIds());
    }

    @Test
    void realWeightsYmlBindsBothCupFinalsAsStrings() throws Exception {
        File f = new File("../config/weights.yml");
        if (!f.exists()) f = new File("config/weights.yml");
        assertTrue(f.exists(), "config/weights.yml not found from " + new File(".").getAbsolutePath());
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load("weights", new FileSystemResource(f));
        NbaGameProperties p = bind(loaded.get(0));
        assertEquals(List.of("20241217_OKC_MIL", "1305814461864501248"), p.excludedGameIds());
        assertTrue(p.excludedGameIdSet().contains("1305814461864501248"), "the numeric-looking id stays a string");
    }
}
