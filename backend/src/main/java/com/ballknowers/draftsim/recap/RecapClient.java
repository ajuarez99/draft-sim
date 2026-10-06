package com.ballknowers.draftsim.recap;

/**
 * The one seam to the model. A bean exists only when {@link RecapEnabledCondition} matches, so
 * "no bean" is how the rest of the app knows recap is off. Tests supply a fake.
 */
public interface RecapClient {

    /**
     * @param sendLowEffort send {@code effort: low}; never for Haiku (it rejects the parameter)
     * @throws RecapUpstreamException for a transport or API error (never carries the key)
     */
    RecapCallResult call(String model, int maxTokens, String systemPrompt, String userTurn, boolean sendLowEffort);
}
