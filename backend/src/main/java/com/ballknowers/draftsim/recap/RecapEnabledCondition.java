package com.ballknowers.draftsim.recap;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches iff {@code draftsim.recap.enabled} is true AND the {@code ANTHROPIC_API_KEY}
 * environment property is non-blank (review F12).
 *
 * <p>{@code @ConditionalOnProperty} cannot do the second half: it treats an empty string as
 * present, so a deploy with {@code ANTHROPIC_API_KEY=} would build a client that fails on every
 * call. The key is read through {@link Environment} (OS env vars and, in tests, test properties),
 * and is never a {@code draftsim.recap} property.
 */
public class RecapEnabledCondition implements Condition {

    static final String KEY_PROPERTY = "ANTHROPIC_API_KEY";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return disabledReason(context.getEnvironment()) == null;
    }

    /** Null when recap is on; otherwise a short human reason, for the one startup log line. */
    static String disabledReason(Environment env) {
        if (!Boolean.parseBoolean(env.getProperty("draftsim.recap.enabled", "false"))) return "flag off";
        String key = env.getProperty(KEY_PROPERTY);
        if (key == null || key.isBlank()) return "key blank";
        return null;
    }
}
