package com.ballknowers.draftsim.profile;

/**
 * Where a seat's behaviour actually came from. Carried out to the API so the UI
 * can distinguish evidence from opinion — a hand-configured seat is neither the
 * league average nor a validated profile, and showing it as either would be a lie.
 */
public enum Provenance {
    /** No history, no input. The league-average drafter wearing someone's name. */
    NEUTRAL,
    /** The user said so. No history to check it against. */
    STATED,
    /** Fitted from draft history, shrunk toward the league mean. */
    FITTED,
    /** History exists and the user also has an opinion; the two are blended. */
    BLENDED
}
