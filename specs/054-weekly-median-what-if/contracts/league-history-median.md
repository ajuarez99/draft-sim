# Contract: `GET /api/leagues/{sleeperId}/history` — per-season `median` block (spec 054)

Additive. Each element of `seasons[]` gains one key, `median`, **always present** (an absent key
would make "nothing to show" indistinguishable from "older backend"; same rule as `records`).
Built with mutable maps: most what-if fields are legitimately null.

```json
{
  "season": 2025,
  "leagueId": 211,
  "sleeperLeagueId": "1229352720222134272",
  "name": "Ball Knowers",
  "standings": [ ... unchanged ... ],
  "median": {
    "status": "WHAT_IF",
    "weeksCounted": 18,
    "driftGames": 0,
    "regularSeasonWeeks": 18,
    "realSource": "OFFICIAL",
    "playoffTeams": 6,
    "playoffLineWithheld": null,
    "teams": [
      {
        "rosterId": 4,
        "gamesCounted": 18,
        "median":     { "wins": 11, "losses": 7, "ties": 0 },
        "allPlay":    { "wins": 120, "losses": 78, "ties": 0 },
        "real":       { "wins": 9, "losses": 9, "ties": 0 },
        "withMedian": { "wins": 20, "losses": 16, "ties": 0 },
        "winsGained": 11,
        "actualPlace": 7,
        "whatIfPlace": 5,
        "playoffCross": "MADE"
      }
    ]
  }
}
```

(Figures above are illustrative, not measured.)

- `ALREADY_ON`: `realSource`, and every team's `real`/`withMedian`/`winsGained`/`actualPlace`/
  `whatIfPlace`/`playoffCross` are `null`; `median` and `allPlay` are still filled.
- `NO_FINAL_WEEKS`: `weeksCounted: 0`, `teams: []`.
- A failure computing one season's block is logged and that season's `median` is
  `{"status":"UNAVAILABLE", "weeksCounted":0, "teams":[], ...nulls}`; it never fails the response
  (FR-012). The page renders dashes for it.

`web/src/api.ts`: `SeasonHistory` gains `median?: SeasonMedian` (optional on the type so an older
backend still type-checks and renders dashes), with `SeasonMedian`/`SeasonMedianTeam` mirroring
the Java records field for field, in the same change.

The Luck page's `/expected-wins` is unchanged.

## Amended after review (2026-11-02)

`status` ∈ `WHAT_IF` | `ALREADY_ON` | `NO_FINAL_WEEKS` | `NO_REGULAR_SEASON_BOUND` | `UNAVAILABLE`.
`realSource` ∈ `OFFICIAL` | `COUNTED_GAMES` | null. New fields `driftGames` (season) and
`gamesCounted` (team), shown in the example above. `playoffLineWithheld` is a code, see
data-model.md. Rules: plan.md "Amended after review".
