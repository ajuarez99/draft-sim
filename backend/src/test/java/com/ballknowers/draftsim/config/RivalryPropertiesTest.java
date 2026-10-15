package com.ballknowers.draftsim.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** specs/034 T008 (plan.md F17): a missing or partial block is not loaded; a bad value fails startup. */
class RivalryPropertiesTest {

    @Test
    void absentBlockBindsNullAndIsNotLoaded() {
        assertFalse(new RivalryProperties(null, null, null, null, 2).loaded());
    }

    @Test
    void aPartialBlockIsNotLoaded() {
        assertFalse(new RivalryProperties(3, 1.5, 2.5, null, 2).loaded());
        assertFalse(new RivalryProperties(3, 1.5, null, 16, 2).loaded());
        assertFalse(new RivalryProperties(3, null, 2.5, 16, 2).loaded());
        assertFalse(new RivalryProperties(null, 1.5, 2.5, 16, 2).loaded());
    }

    @Test
    void theHandSetStartingValuesLoad() {
        RivalryProperties p = new RivalryProperties(3, 1.5, 2.5, 16, 2);
        assertTrue(p.loaded());
        assertEquals(3, p.minMeetings());
        assertEquals(16, p.maxManagers());
    }

    @Test
    void outOfRangeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(0, 1.5, 2.5, 16, 2));
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(11, 1.5, 2.5, 16, 2));
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(3, -0.1, 2.5, 16, 2));
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(3, 1.5, 10.5, 16, 2));
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(3, 1.5, 2.5, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(3, 1.5, 2.5, 21, 2));
        assertDoesNotThrow(() -> new RivalryProperties(1, 0.0, 0.0, 2, 2));
        assertDoesNotThrow(() -> new RivalryProperties(10, 10.0, 10.0, 20, 2));
    }

    // ---- spec 037: the league-rule cap (plan F7)

    @Test
    void aMissingCapLeavesLoadedAloneButNotWeekLoaded() {
        RivalryProperties p = new RivalryProperties(3, 1.5, 2.5, 16, null);
        assertTrue(p.loaded(), "loaded() is unchanged: spec 034's pairings must not depend on the cap");
        assertFalse(p.weekLoaded());
        assertTrue(new RivalryProperties(3, 1.5, 2.5, 16, 2).weekLoaded());
        assertFalse(new RivalryProperties(null, null, null, null, 2).weekLoaded(), "no block, no week");
    }

    @Test
    void theCapIsRangeCheckedOneToFive() {
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(3, 1.5, 2.5, 16, 0));
        assertThrows(IllegalArgumentException.class, () -> new RivalryProperties(3, 1.5, 2.5, 16, 6));
        assertDoesNotThrow(() -> new RivalryProperties(3, 1.5, 2.5, 16, 1));
        assertDoesNotThrow(() -> new RivalryProperties(3, 1.5, 2.5, 16, 5));
    }

    @Test
    void theRealWeightsYmlBindsTheCapAsTwo() throws Exception {
        File f = new File("../config/weights.yml");
        if (!f.exists()) f = new File("config/weights.yml");
        assertTrue(f.exists(), "config/weights.yml not found from " + new File(".").getAbsolutePath());
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load("weights", new FileSystemResource(f));
        RivalryProperties p = new Binder(ConfigurationPropertySources.from(loaded.get(0)))
                .bind("draftsim.rivalry", Bindable.of(RivalryProperties.class)).get();
        assertEquals(2, p.maxRegularSeasonMeetings());
        assertTrue(p.weekLoaded());
        assertEquals(3, p.minMeetings());
    }
}
