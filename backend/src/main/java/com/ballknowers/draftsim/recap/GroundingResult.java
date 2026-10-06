package com.ballknowers.draftsim.recap;

import java.util.List;

/**
 * Outcome of {@link GroundingCheck}. {@code unmatched} holds the offending body tokens as written
 * (e.g. {@code 3rd}), {@code badCites} the cites that are not items or do not resolve (or a note
 * that a section has too many), {@code unboundNames} names used outside their section's cited
 * items, {@code basisViolations} NBA wording the basis forbids. These are what a retry lists back
 * to the model.
 */
public record GroundingResult(boolean ok, List<String> unmatched, List<String> badCites,
                              List<String> unboundNames, List<String> basisViolations) {}
