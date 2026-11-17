package com.ballknowers.draftsim.historical;

/**
 * What signing in did to a guest's games (spec 074, {@code POST /api/history/claim}). {@code leftBehind} counts the
 * games that could not move: a game of the day the Sleeper id had already played that day (one per user, sport and
 * day). They stay under the guest id, which the browser then discards. Mirrored in {@code web/src/api.ts}.
 */
public record ClaimResult(int moved, int leftBehind) {}
