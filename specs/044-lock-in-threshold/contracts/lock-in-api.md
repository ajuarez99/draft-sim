# Contract: `GET /api/leagues/{sleeperId}/lock-ins`

- **Query**: `player` (optional, a Sleeper player id). When present, `locks` holds that player's locks.
- **Access**: The same league-scoping as `/api/leagues/{sleeperId}/projection-accuracy`.
- **404**: Unknown league, returned in the same shape as projection-accuracy's 404.
- **200**: `LockInReport` (see [data-model.md](../data-model.md)).

```json
{
  "sport": "nba", "season": 2025, "status": "MEASURED", "reason": null,
  "minLocks": 10, "minCell": 15, "bands": [0, 0.10, 0.25, 0.50], "gamesLeftCap": 3,
  "totals": { "multiGame": 2171, "lock": 1195, "rodeOut": 890, "tieNonLast": 10, "tieWithLast": 62,
              "unmatched": 14, "singleGame": 89, "noGames": 1, "noBaselineStandIn": 35, "noSnapshot": 1195 },
  "managers": [ { "key": "123", "name": "popsharky",
                  "standIn": { "n": 87, "median": 0.468, "q1": 0.25, "q3": 0.71 },
                  "projection": { "n": 0, "median": null, "q1": null, "q3": null },
                  "rodeOut": 70, "tieNonLast": 1 } ],
  "players": [ "...same shape, key = sleeper player id..." ],
  "payoff": { "STAND_IN": [ { "band": "50+", "gamesLeft": 1, "n": 210, "rate": 0.06 } ], "PROJECTION": [] },
  "locks": [],
  "excludedCategories": ["bonus_ast_15p", "dd", "td"],
  "caveats": [
    "Locks are inferred: Sleeper doesn't publish which game a manager locked.",
    "A later game means a later game the player actually played.",
    "Season-average stand-in is not a projection.",
    "Describes this league's past weeks; not a prediction."
  ]
}
```

The example values are illustrative. The 2025 totals are the R2 measurements, and the `noBaselineStandIn` value is
a placeholder.

## Amended after plan review (2026-10-21)

- **Query**: `player` (optional) and `live` (optional, default `true`; `false` skips the live Sleeper call).
- With `player` set, the response is **only** that player's summary, `locks`, `thresholds` and caveats. It's never
  the whole report (F14).
- `totals` gains `rodeOutMissed`, and `unmatched` now means real failures only (F5).
- `payoff` cells are `{band, gamesLeft, n, rateLast, rateAny}`. `rateLast` is the headline ("riding it out beat
  it"), and `rateAny` is "in hindsight" (F4).
- New fields:

```json
"thresholds": { "status": "OK", "from": [2024, 2025], "pMissed": 0.031,
                "byGamesLeft": [ { "gamesLeft": 1, "threshold": 0.071, "n": 12937 }, "..." ] },
"live": { "week": 1, "asOf": "2026-10-22", "status": "OK",
          "calls": [ { "rosterId": 3, "managerId": "123", "manager": "popsharky", "playerId": "4881",
                       "player": "...", "team": "DEN",
                       "latestGame": { "gameId": "...", "gameDate": "2026-10-21", "points": 61.5 },
                       "baseline": 48.2, "baselineKind": "LAST_SEASON", "margin": 0.276,
                       "gamesLeft": 2, "threshold": 0.25, "call": "LOCK" } ] }
```

The values are illustrative. `live` is `null` for a complete league or when `live=false`.

## Admin digest addition (US5)

`GET /api/admin/digest` → each `SportDigest` gains `lockInCalls: LockInCard[]`, which is empty for NFL:
`{leagueName, sleeperLeagueId, week, status, entries: LiveCall[], tweet}`. `status` is one of
`OK | NONE_TONIGHT | NO_HISTORY | ROSTERS_FAILED`, and `tweet.length <= 280`.

Non-lock-in league: `{"status": "NOT_LOCK_IN", "reason": "Football league" | "NBA league not in lock-in mode
(game_mode != 1)", ...empty arrays}`.

## Amended after code review (2026-10-22)

- **`live` default**: `true` for the league report, **`false` when `player` is set** (a player answer has no use for a
  league's roster read). With `player` set and an explicit `live=true`, `live.calls` holds only that player's call, if any.
- **`totals` on a `?player=` answer are league-wide**, not the player's. They are the same figures the league report
  carries; only `players`, `locks` and `live.calls` are narrowed.
- **A live call exists only while its game can still be locked.** A starter gets no entry (the same "no decision"
  answer as a team with no game left) when his latest box game is not `complete` in `sport_schedule` (still in
  progress), or when his team has since started or finished a later game dated on or before `asOf` (the earlier
  score is gone, whether or not he has a box row for the later game). `gamesLeft` counts only scheduled games not yet
  started (`in_game`, `complete`, `postponed` and `canceled` are not counted).
- **`live.week`** is the schedule's week for the first counted game on or after the judged date, falling back to the
  league's stored `leg` when no schedule is stored.
- A failed roster read (`ROSTERS_FAILED`) is cached for `live-failure-ttl-seconds` (15, arbitrary) so an outage is
  not one Sleeper call per request.
