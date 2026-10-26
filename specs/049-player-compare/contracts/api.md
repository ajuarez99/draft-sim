# Contract: two additive fields on the player page response (spec 049)

> **Amended after review 2026-10-26** (plan A3, A7, A12, A13): `thisWeek` lost `firstDate`/`lastDate` and is now
> lock-ins' week rule, computed from the URL league; `windowQualification` is new.

`GET /api/leagues/{sleeperLeagueId}/players/{sleeperPlayerId}`. Nothing else in the response changes.

```jsonc
{
  // ...every existing field, unchanged...
  "thisWeek": {                 // or null
    "week": 2,                  // LockInService.liveWeek(URL season's schedule, today, leg)
    "played": 1,                // his URL-season box lines with week == 2
    "remaining": 3              // his team's counted games in week 2 from today on, gameId not in `played`
  },
  "windowQualification": {      // {} when available == false
    "SEASON":  { "qualified": true,  "reason": null,                  "lastN": null },
    "LAST_10": { "qualified": false, "reason": "NOT_QUALIFIED",       "lastN": 10 },
    "LAST_5":  { "qualified": false, "reason": "NOT_QUALIFIED_STALE", "lastN": 5 }
  }
}
```

- `thisWeek` is null when: the URL league is complete; there's no current team; there's no stored schedule and no
  `leg`; or the sport isn't basketball. It is **not** null on a fallback data season: it describes the URL season
  (A12).
- `played` lags the box-score ingest, and `remaining` lags the schedule refresh. A game that finished tonight
  can still count as remaining until both have run. The page shows `scheduleAsOf` beside the line.
- `windowQualification` is the server's existing `qualify` result, serialised as it is. `lastN` is
  `WindowKind.lastN()` (null for SEASON), so the client holds no copy of 10 or 5.
- Both fields are optional in `api.ts` (`thisWeek?: ThisWeek | null`,
  `windowQualification?: Partial<Record<PlayerWindowKind, WindowQualification>>`). For a frontend deployed
  before the backend, `undefined` reads as "unknown": no this-week line, and every LAST_N row withheld.
- No new endpoint. The chooser reuses `GET /api/leagues/{id}/stats?window=SEASON` unchanged.

> **Amended after code review 2026-10-26** (B2, S3): `windowQualification.<window>` gains `stale` (boolean, the
> recency rule on its own, independent of the minutes floor; optional in `api.ts`, fall back to
> `reason == NOT_QUALIFIED_STALE` when absent). `thisWeek` gains `scheduleStored` (boolean, false when no schedule
> is stored for the season, so `remaining` 0 means "unknown"). `thisWeek.remaining` also excludes a game whose
> status is `in_game`, matching lock-ins' games-left rule (N6).
