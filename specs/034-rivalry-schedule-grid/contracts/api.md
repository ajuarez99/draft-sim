# Contracts: spec 034

Field names are the wire names. `web/src/api.ts` mirrors each record field for field, in the
same change (constitution).

## C1 — `GET /api/leagues/{sleeperId}/rivalries`

Header `X-Sleeper-User`. If `LeagueMembership.visibleLeague` is empty (no identity, a
stranger, or no league), the answer is **404**, the same as every league route.

```jsonc
{
  "sport": "nfl",                       // the requested league's sport; the chain never mixes sports
  "season": 2026,
  "seasonsCovered": [2026, 2025],       // the chain as stored, newest first
  "matrix": {
    "rows": [ /* MatrixRow, ordered by this season's standings rank, then managerId */ ],
    "pairs": [ /* PairRecord, one per unordered current pair */ ],
    "splitNotes": [                     // one entry per season whose playoff weeks are POSTSEASON_UNSPLIT
      { "season": 2026, "reason": "NO_BRACKET" }
    ]
  },
  "seasonGrid": {
    "scheduleStatus": "OK",             // OK | NOT_PUBLISHED
    "weeks": [ { "week": 1, "isPlayoffWeek": false } ],
    "playoffWindowNote": null,          // a sentence when lastPlayoffWeek() is refused (round type != 0)
    "cells": [ /* SeasonCell with managerId */ ]
  },
  "recommendation": { /* RivalryRecommendation */ }
}
```

Rules:
- `pairs` is unordered: the client looks up (x, y) as (min, max), and mirrors the record for
  (y, x). There's no mirrored duplicate on the wire.
- Every number the reason text prints is also in `reason.numbers`, so a test can check the
  text against the matrix (SC-004).
- Nullable fields (`teamName`, `avatarId`, `opponentManagerId`, `priorRecord`, `result`,
  `unpaired`, `bManagerId`, `playoffWindowNote`) are built in mutable maps or records, never
  `Map.of` (constitution).

## C2 — `POST /api/ingest/brackets/{sleeperLeagueId}`

Behind the existing admin gate for `/api/ingest/**`. It walks the stored chain and fetches
only `winners_bracket` per season. It never fetches `/matchups` (research R10).

```json
{ "seasons": [ { "season": 2025, "sleeperLeagueId": "125...", "matches": 7 } ] }
```

## C3 — Ingest side effect

`LeagueHistoryIngestService.ingestChain` also stores each non-skipped season's winners
bracket, through the same repository method C2 uses. That's one method, not two.

## C4 — Frontend route and destination

- Route `/leagues/:sleeperLeagueId/rivalries` → `Rivalries` page, keyed by league like its
  siblings.
- `LEAGUE_DESTINATIONS` gets `key: 'rivalries'`, `group: 'history'`, `sports: ['nfl','nba']`,
  label `Rivalries`, and an href built from `ctx.season` (the one-season rule).
  `destinations.test.ts` asserts both sports have it.
