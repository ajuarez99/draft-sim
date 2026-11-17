package com.ballknowers.draftsim.historical;

/**
 * A finished game as anyone may read it ({@code GET /api/history/public/games/{id}}, spec 074). The state carries the
 * stored result as-is and no owner of any kind (the state record has no owner field).
 *
 * <p>{@code reducedUntil} is non-null while the game is today's game of the day, whose season and seed are the same
 * for everyone: the state then holds no picks, and the result only the finish, the record, the draft score inputs
 * and the pick squares (never a player, a lineup or the hindsight roster). It is the ISO-8601 instant the full
 * result shows, the next midnight in the daily's time zone. Null means the full result.
 */
public record PublicGame(HistoricalGameState state, String reducedUntil) {}
