package com.ballknowers.draftsim.recap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Duration;

/**
 * Builds the {@link RecapClient} only when {@link RecapEnabledCondition} matches, so with the
 * flag off or the key blank no Anthropic class is instantiated at all. The key comes from the
 * environment and is passed straight to the SDK; it is never logged or stored here.
 */
@Configuration
public class RecapConfig {

    private static final Logger log = LoggerFactory.getLogger(RecapConfig.class);

    public RecapConfig(Environment env, RecapProperties props) {
        String reason = RecapEnabledCondition.disabledReason(env);
        if (reason == null) {
            log.info("recap: enabled with model {}", props.model());
        } else {
            log.info("recap: disabled ({})", reason);
        }
    }

    @Bean
    @Conditional(RecapEnabledCondition.class)
    public RecapClient recapClient(Environment env, RecapProperties props) {
        return new AnthropicRecapClient(env.getProperty(RecapEnabledCondition.KEY_PROPERTY),
                Duration.ofSeconds(props.timeoutSeconds()));
    }
}
