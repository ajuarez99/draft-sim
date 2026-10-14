# Contract: projection accuracy

## GET `/api/leagues/{sleeperId}/projection-accuracy`

Scoped like `/analysis` (`LeagueAnalysisController`): header `X-Sleeper-User`. It answers 404
when the league is unknown or the user can't see it.

**200**: a `ProjectionAccuracy` (data-model E3). Example, NBA, before N:

```json
{
  "sport": "nba", "season": 2026, "unit": "GAME",
  "status": "TOO_EARLY", "minSamples": 500, "minCell": 50,
  "source": "rotowire",
  "snapshotsFrom": "2026-10-20", "scoredThrough": "2026-10-21",
  "caveats": [
    "Morning-of projection: late scratches and inactives are not reflected.",
    "Sleeper credits one game per starter per week; this grades individual games."
  ],
  "series": [
    { "kind": "GAME", "label": "Game projection",
      "overall": { "key": "ALL", "n": 212, "mae": null, "lean": null },
      "byPosition": [ { "key": "PG", "n": 48, "mae": null, "lean": null } ],
      "byBand": [ { "key": "20–35", "n": 90, "mae": null, "lean": null } ],
      "didNotPlay": 17, "pending": 640 },
    { "kind": "SEASON_AVERAGE", "label": "Season average (what the rankings use)", "...": "same shape" }
  ]
}
```

`status = NO_SNAPSHOTS` gives `series: []` and null dates. It's never a 500 and never an empty body.

**Field nullability (for `api.ts`):** `mae`, `lean`, `snapshotsFrom`, `scoredThrough`, `source`
are nullable. Everything else is required.

## Change to `GET /api/leagues/{sleeperId}/analysis`

`projections` (record `LeagueAnalysisService.Projections`) gains:

```ts
accuracy: {
  status: 'TOO_EARLY' | 'MEASURED' | 'NO_SNAPSHOTS'
  n: number
  minSamples: number
  mae: number | null
  lean: number | null
  unit: 'GAME' | 'PLAYER_WEEK'
} | null
```

`null` only when the accuracy service itself threw. The page then keeps today's "accuracy for
this league not measured" line. A failure there must never take the projections block down.
The same field also appears on the preseason strength payload, because `PreseasonStrengthBlock`
renders `projectionNotes` from the same shape.
