# Spec 033 wire contract

No new route. `GET /api/leagues/{sleeperId}/transactions` keeps its scoping, the same 404 for
non-members (`RosterManagementController.java:111-119`). It extends `trades[]` and adds two
top-level fields. `web/src/api.ts` changes in the same commit (constitution: types mirror the
records field-for-field).

## Top level (added)

```jsonc
{
  // ...existing: available, season, sport, rankDirection, byManager, adds
  "trades": [ /* LeagueTrade, below */ ],
  "seasonFinal": true,                 // league.status complete; verdicts read "final", else "so far"
  "tradeAssetsLoaded": true,           // false if any TRADE row predates V34 (picks/FAAB unknown, R6)
  "throughWeek": 21,                   // last_scored_leg; weeks after it never count (plan review F5)
  "reconciliation": {                  // R5 (renamed after plan review F1); numbers, never hidden
    "playerWeeksChecked": 3894,
    "unexplainedStretches": 0,
    "sameWeekInAndOut": 0,             // (illustrative) streams added and dropped within one leg: expected, not errors
    "neverOnAScoredRoster": 2,         // listed in examples with the next move
    "examples": []                     // <= 10 human-readable strings
  }
}
```

## `LeagueTrade` (replaces `{week, sides}`)

```jsonc
{
  "txId": "1289869016390725632",
  "week": 2,                     // kept for compatibility: Sleeper's leg = the week it was PROPOSED
  "acceptedAt": "2025-11-02T01:20:48.212Z",   // shown on the page instead of "Week N"
  "assetsLoaded": true,
  "verdict": {
    "state": "WINNER",           // WINNER | EVEN | NO_WEEK_YET | UNDONE | RESTORED | NONE_COUNTED | NOT_COMPARABLE (UNDONE/NONE_COUNTED: plan-review F7; RESTORED: F8)
    "winnerRosterId": 8,         // null unless WINNER
    "margin": 41.5,              // started points, winner minus runner-up; null unless WINNER/EVEN
    "weeksCounted": 6,
    "final": true
  },
  "sides": [                     // ranked by startedTotal, descending
    {
      "rosterId": 8,
      "teamName": "…",
      "startedTotal": 120.5,
      "benchTotal": 14.0,
      "weeksCounted": 6,
      "assets": [ /* TradeAsset */ ]
    }
  ]
}
```

## `TradeAsset`

```jsonc
// PLAYER
{ "kind": "PLAYER", "playerId": "2334", "playerName": "…", "position": "SF",
  "status": "COUNTED",          // COUNTED | NO_WEEK_YET | UNDONE | LEFT_BEFORE_PLAYING | NEVER_PLAYED_FOR_THEM | ALREADY_HELD (F8)
  "firstWeek": 6, "lastWeek": 19,   // null unless COUNTED
  "startedPoints": 88.0, "benchPoints": 12.5, "weeksCounted": 9, "duplicateWeeks": [6],
  "postMovePositionalRank": 14.2,   // over the same window (FR-013); null unless COUNTED
  "stretches": [                     // this player's whole season (US2), ordered
    { "rosterId": 1, "teamName": "…", "firstWeek": 1, "lastWeek": 5,
      "how": "DRAFT", "viaTxId": null, "startedPoints": 70.0, "benchPoints": 3.0,
      "duplicateWeeks": [],          // added during the build: weeks two rosters listed him, neither started him
      "leadingUnstarted": false }    // added during the build: not started in the stretch's first week
  ] }

// PICK
{ "kind": "PICK", "season": 2026, "round": 1, "originalRosterId": 11,
  "status": "COUNTED",          // NOT_DRAFTED_YET | PICK_NOT_MATCHED, else the drafted player's own status (COUNTED | NO_WEEK_YET | LEFT_BEFORE_PLAYING | ...; code review R2)
  "pickNo": 10,                 // null unless resolved
  "player": { /* PLAYER asset, or null */ } }

// FAAB
{ "kind": "FAAB", "amount": 67, "fromRosterId": 8,
  "fromTeamName": "…" }         // added during the build: "Roster N" if the roster has no name
```

**Nullability.** `winnerRosterId`, `margin`, `firstWeek`, `lastWeek`, `postMovePositionalRank`,
`pickNo`, `player`, `position` and `viaTxId` are legitimately null. The controller builds these
maps mutably (`LinkedHashMap`), never with `Map.of` (constitution).

**Order.** `trades[]` is ordered by `acceptedAt`, newest first, then `txId`. It's never ordered
by stored id (lessons #43).
