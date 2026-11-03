package com.ballknowers.draftsim.historical;

import com.ballknowers.draftsim.config.HistoricalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 053 T002: the real application.yml binds, including scoring keys that contain underscores. */
class HistoricalPropertiesTest {

    @Test
    void applicationYmlBindsWithTheLabelledValues() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        // A placeholder default (${ENV:default}) is resolved by the Binder's own placeholder handling.
        HistoricalProperties p = new Binder(ConfigurationPropertySources.from(sources),
                new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(sources))
                .bind("historical", HistoricalProperties.class).get();

        assertEquals(0.90, p.eraThreshold(), 1e-12);
        assertEquals(72, p.rankStandardGames());
        assertEquals(20, p.rankMinGames());
        assertEquals(3, p.rankLookbackSeasons());
        assertEquals(12, p.teams());
        assertEquals(14, p.rounds());
        assertEquals(0, p.reversalRound());
        assertEquals(15, p.scoring().size());
        assertEquals(2.0, p.scoring().get("bonus_pt_40p"), 1e-12);
        assertEquals(-1.0, p.scoring().get("to"), 1e-12);
        assertEquals(0.5, p.scoring().get("tpm"), 1e-12);
        assertEquals("7a127548ce7143e85540ea899c93bada25a5944344d7adb4cfc89645ef3d46a4", p.kaggleZipSha256());
        assertTrue(p.kaggleZipUrl().contains("datasetVersionNumber=2"));
    }
}
