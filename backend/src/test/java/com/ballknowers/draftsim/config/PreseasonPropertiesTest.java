package com.ballknowers.draftsim.config;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * specs/030-post-draft-season-view T033 (plan review S10): the real weights.yml binds to the
 * documented values, and a missing or non-positive key is rejected instead of binding to 0.
 */
class PreseasonPropertiesTest {

    private static PreseasonProperties bind(PropertySource<?> source) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Binder binder = new Binder(ConfigurationPropertySources.from(source));
            return binder.bind("draftsim.preseason", Bindable.of(PreseasonProperties.class),
                    new ValidationBindHandler(new SpringValidatorAdapter(factory.getValidator()))).get();
        }
    }

    @Test
    void realWeightsYmlBindsBothValues() throws Exception {
        File f = new File("../config/weights.yml");
        if (!f.exists()) f = new File("config/weights.yml");
        assertTrue(f.exists(), "config/weights.yml not found from " + new File(".").getAbsolutePath());
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load("weights", new FileSystemResource(f));
        PreseasonProperties p = bind(loaded.get(0));
        assertEquals(3.0, p.favoriteMinGapPct(), 1e-9);
        assertEquals(3, p.staleAfterDays());
    }

    @Test
    void aMisspelledKeyFailsInsteadOfBindingToZero() {
        var source = new MapPropertySource("t", Map.of(
                "draftsim.preseason.favorite-min-gap-pcts", "3.0", "draftsim.preseason.stale-after-days", "3"));
        assertThrows(BindException.class, () -> bind(source));
    }

    @Test
    void aNonPositiveValueFails() {
        var source = new MapPropertySource("t", Map.of(
                "draftsim.preseason.favorite-min-gap-pct", "3.0", "draftsim.preseason.stale-after-days", "0"));
        assertThrows(BindException.class, () -> bind(source));
    }
}
