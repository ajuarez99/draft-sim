package com.ballknowers.draftsim.recap;

/**
 * One model call outcome. {@code output} is null unless the model finished normally
 * ({@code end_turn}) and its body parsed; {@code parseError} then says why not, when it was a
 * parse problem rather than a refusal or truncation.
 */
public record RecapCallResult(
        String stopReason,
        RecapOutput output,
        String parseError,
        int inputTokens,
        int outputTokens,
        long latencyMs) {}
