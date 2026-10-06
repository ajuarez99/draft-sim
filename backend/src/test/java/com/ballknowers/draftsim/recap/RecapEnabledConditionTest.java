package com.ballknowers.draftsim.recap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three contexts (review F12): flag off, flag on with no usable key, flag on with a key. Runs
 * RecapConfig alone through ApplicationContextRunner rather than three full @SpringBootTest
 * contexts: each of those would cache a Hikari pool for the whole JVM (see build.gradle.kts),
 * and nothing here needs the database. The key arrives as an Environment property, the same
 * place the condition reads an OS environment variable from.
 */
class RecapEnabledConditionTest {

    private static final String DUMMY_KEY = "test-dummy-key-do-not-print";

    @Configuration
    @EnableConfigurationProperties(RecapProperties.class)
    static class Props {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class, RecapConfig.class)
            .withPropertyValues(
                    "draftsim.recap.model=claude-haiku-4-5",
                    "draftsim.recap.max-calls-per-league-per-day=10",
                    "draftsim.recap.max-calls-per-day=50",
                    "draftsim.recap.transient-retry-minutes=15",
                    "draftsim.recap.timeout-seconds=60",
                    "draftsim.recap.max-tokens=2048");

    @Test
    void flagOffMeansNoClientEvenWithAKey() {
        runner.withPropertyValues("draftsim.recap.enabled=false", "ANTHROPIC_API_KEY=" + DUMMY_KEY)
                .run(ctx -> assertTrue(ctx.getBeansOfType(RecapClient.class).isEmpty()));
    }

    @Test
    void flagOnButKeyBlankOrUnsetMeansNoClient() {
        runner.withPropertyValues("draftsim.recap.enabled=true", "ANTHROPIC_API_KEY=")
                .run(ctx -> assertTrue(ctx.getBeansOfType(RecapClient.class).isEmpty()));
        runner.withPropertyValues("draftsim.recap.enabled=true", "ANTHROPIC_API_KEY=   ")
                .run(ctx -> assertTrue(ctx.getBeansOfType(RecapClient.class).isEmpty()));
        // Only meaningful when the machine running the suite has no real key of its own.
        if (System.getenv("ANTHROPIC_API_KEY") == null) {
            runner.withPropertyValues("draftsim.recap.enabled=true")
                    .run(ctx -> assertTrue(ctx.getBeansOfType(RecapClient.class).isEmpty()));
        }
    }

    @Test
    void flagOnWithAKeyBuildsTheClientAndTheKeyNeverAppearsInAToString() {
        runner.withPropertyValues("draftsim.recap.enabled=true", "ANTHROPIC_API_KEY=" + DUMMY_KEY)
                .run(ctx -> {
                    assertEquals(1, ctx.getBeansOfType(RecapClient.class).size());
                    assertFalse(ctx.getBean(RecapProperties.class).toString().contains(DUMMY_KEY));
                    assertFalse(ctx.getBean(RecapClient.class).toString().contains(DUMMY_KEY));
                });
    }
}
