# Contract: `GET /api/admin/digest`

Admin-only (`X-Admin-Token`; `AdminGateInterceptor` answers 403
`{"code":"admin_token_required"}` otherwise). Read-only; no writes, no upstream calls.

## Request

| Param | Required | Meaning |
|-------|----------|---------|
| `date` | no | `YYYY-MM-DD`. Omitted: each sport covers "last night" (research R2). Given: each sport covers that date. Malformed → 400 `{"code":"bad_date"}`. |

## Response 200

```json
{
  "generatedAt": "2026-10-21T12:00:00Z",
  "yesterday": "2026-10-20",
  "sports": [ SportDigest, ... ]          // NBA first, then NFL
}
```

### SportDigest

```json
{
  "sport": "nba",
  "season": 2026,
  "pointsBasis": "Sleeper default points",   // or "Sleeper PPR"
  "status": "READY",                          // READY | NO_GAMES_LAST_NIGHT | NOT_FETCHED | NIGHT_INCOMPLETE | NO_GAMES_ON_DATE | NO_SEASON | DATE_IN_FUTURE
  "statsFetchedAt": "2026-10-21T11:02:00Z",   // covered (or scheduled) week's sport_week_stats.fetched_at; null = never
  "date": "2026-10-20",                       // covered night; null unless READY
  "week": 1,                                  // fantasy week of the covered night; null unless READY
  "gamesCount": 2,                            // games that night (DateRow sum); null unless READY
  "lastSlateDate": "2026-10-19",              // newest complete night; null when none
  "inProgressDate": null,                     // a newer night that has rows but isn't complete
  "nextGameDate": "2026-10-22",               // first scheduled date ON or after today (default mode; amended S1) or after `date`; null if unknown
  "trending": TrendingCard | null,
  "topPerformers": [ Entry ],                 // up to 10, points desc then playerId; [] unless READY
  "keyPlayers": [ KeyEntry ],                 // key players whose team played; [] unless READY
  "positionLeaders": [ Entry ] | null         // NFL only: one per QB, RB, WR, TE present; null for NBA
}
```

Cards are populated only when `status = READY`. Every non-READY status carries no entries.

### Entry

```json
{ "playerId": "4866", "name": "Nikola Jokić", "position": "C", "team": "DEN",
  "opponent": "GSW", "isAway": false, "points": 62.5,
  "line": { "pts": 34, "reb": 12, "ast": 8, "stl": 3 } }
```

`line` holds only research-R7 headline keys with non-zero values. `opponent`/`isAway` nullable.

### TrendingCard

```json
{ "status": "OK",                     // OK | NEVER_FETCHED | TRENDING_NOT_HISTORICAL
  "lookbackHours": 24, "fetchedAt": "2026-10-20T23:48:48Z", "stale": false,
  "omittedUnknownPlayers": 0,
  "entries": [ { "rank": 1, "addCount": 4210, "outcome": "PLAYED",   // PLAYED | DID_NOT_PLAY | NO_GAME
                 "entry": Entry } ] }   // entry.points/line null unless PLAYED
```

Up to 10 entries in Sleeper's order (the frontend shows the first 5 that fit).

### KeyEntry

```json
{ "rank": 2, "entry": Entry, "played": true,
  "expectation": 51.3, "expectationBasis": "THIS_SEASON",   // THIS_SEASON | LAST_SEASON | NONE
  "verdict": "ABOVE" }                                        // ABOVE | BELOW | DNP | NO_BASELINE
```

`rank` is the Sleeper search rank. `played=false` ⇒ verdict `DNP`, `entry.points` null.

## Amended after review (2026-10-20, plan-review F1/F2/F4/F16)

- `unscoredRows` removed. A missing Sleeper points field scores 0, because Sleeper leaves out
  zero-valued fields (F1).
- Statuses add `NOT_FETCHED`: games were scheduled on the night (per `sport_schedule`) but no
  completed fetch covers it. `statsFetchedAt` was added (F2).
- `TEAM_*` and unresolved ids never appear. NFL `topPerformers` and `positionLeaders` cover QB,
  RB, WR and TE only (F4).
- In default mode, `trending` is returned whenever a snapshot exists, whatever `status` is. When
  the night isn't READY, each item's `outcome` is `NO_PERIOD` (F16).

# Contract: `POST /api/admin/digest/refresh-stats` (added after review, F2)

Admin-only. For each sport with a known current season, it runs
`PlayerGameIngestService.refreshSportSeason(sport, season, now)`. That call is single-flighted
and skips weeks already final. Response 200:

```json
{ "results": [ { "sport": "nba", "season": 2026, "weeksFetched": 1, "gamesStored": 61,
                 "weeksFailed": 0, "scheduleStoreFailed": false, "error": null } ] }
```

A sport whose refresh throws reports `error` (a message) and does not fail the other sport.

## Amended after code review (2026-10-20)

- **S1**: `nextGameDate` in default mode counts **today's** games (first counted game date on or after
  today, ET). The old "after today" told the page "No games last night. Next games: Oct 25" on a
  morning with a game that night. With `?date=` it stays "after `date`".
- **S3**: a `?date=` with no stored rows resolves its season from the schedules of the current and
  previous season (a game listed that day → that season, else the season whose span covers the date,
  else the gap before the current season's opener → current). If no known schedule covers it,
  `nextGameDate` is `null` instead of another season's date. Listed games with no rows are `NOT_FETCHED`.
- **L1**: new status `DATE_IN_FUTURE`: `?date=` is after today (ET) and no rows exist. Fetching cannot
  help, so the page says the date hasn't happened yet.
- **L2**: with no known season (`NO_SEASON`), default-mode `trending` is still returned when a
  snapshot exists (F16); an explicit date gets `TRENDING_NOT_HISTORICAL`.
- **S4**: `refresh-stats` rows carry `scheduleStoreFailed` (from `PlayerGameIngestService.Result`); the
  page shows it and `weeksFailed`.
