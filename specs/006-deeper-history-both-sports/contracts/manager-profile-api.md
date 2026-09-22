# Contract: `GET /api/managers/{managerId}/history`

**Kind**: HTTP, read-only. **A breaking change to a shipped endpoint** — see [Migration](#migration).

**Stories**: US1 (sport on every row), US2 (titles), US3 (career profile), US6 (waiver tendencies).

**Auth**: unchanged. `X-Sleeper-User` header, scoped through `LeagueMembership#canSeeManager`.
404 for both "no such manager" and "not visible to you", as today.

---

## What is wrong with the response today

Run live on 2026-09-21 for manager 7 (popsharky), abridged:

```json
{
  "managerId": 7, "manager": "popsharky",
  "seasons": [
    { "season": 2026, "wins": 1, "losses": 0, "pointsFor": 189.90, "sleeperLeagueId": "1389361939561332736" },
    { "season": 2026, "wins": 0, "losses": 0, "pointsFor": 0.00,   "sleeperLeagueId": "1339351318115946496" },
    { "season": 2026, "wins": 1, "losses": 0, "pointsFor": 157.40, "sleeperLeagueId": "1346366555759341568", "champion": true },
    { "season": 2025, "wins": 8, "losses": 10, "pointsFor": 4370.00, "sleeperLeagueId": "1229352720222134272" },
    { "season": 2025, "wins": 10, "losses": 4, "pointsFor": 2042.84, "sleeperLeagueId": "1254190892974084096", "champion": true },
    { "season": 2024, "wins": 9, "losses": 11, "pointsFor": 5146.00, "sleeperLeagueId": "1141438340626231296" }
  ],
  "draftHistory": [ { "sport": "nfl", … }, { "sport": "nba", … } ]
}
```

Three rows say `2026`, two say `2025`, no row says which sport it is, `pointsFor` mixes two scales, and
`champion` is true for a season one week old. `draftHistory` — the *opinion* half — already gets this
right with one entry per sport. The record half does not.

---

## Response shape

```jsonc
{
  "managerId": 7,
  "manager": "popsharky",
  "avatarId": "e1d4ebf9ea0760f248119d4ec2ac5a63",

  // One entry per sport this manager has played. Never a top-level total.
  // Sports with no roster-seasons are omitted, matching draftHistory's own rule.
  "careers": [
    {
      "sport": "nfl",

      // Every figure below rests on exactly these seasons.
      "seasonsCounted": 2,
      "seasons": [
        {
          "sport": "nfl", "season": 2025, "leagueName": "(Foot) Ball Knowers",
          "sleeperLeagueId": "1254190892974084096", "leagueId": 5, "rosterId": 1,
          "wins": 10, "losses": 4, "ties": 0,
          "pointsFor": 2042.84, "pointsAgainst": 1771.16,
          "complete": true, "champion": true, "counted": true
        },
        {
          "sport": "nfl", "season": 2026, "leagueName": "(Foot) Ball Knowers",
          "sleeperLeagueId": "1346366555759341568", "leagueId": 4, "rosterId": 1,
          "wins": 1, "losses": 0, "ties": 0,
          "pointsFor": 157.40, "pointsAgainst": 147.90,
          "complete": false, "champion": false, "counted": true
        },
        {
          "sport": "nfl", "season": 2026, "leagueName": "West Coast Fantasy Football",
          "sleeperLeagueId": "1389361939561332736", "leagueId": 1, "rosterId": 7,
          "wins": 1, "losses": 0, "ties": 0,
          "pointsFor": 189.90, "pointsAgainst": 140.00,
          "complete": false, "champion": false, "counted": true
        }
      ],

      "wins": 12, "losses": 4, "ties": 0,
      "winRate": 0.75,
      "pointsFor": 2390.14, "pointsAgainst": 2059.06,
      "pointsPerSeason": 796.71,

      "averageEfficiency": 0.916,
      "weeksCounted": 19,
      "weeksExcluded": 0,

      "winsAboveExpected": 1.82,
      "titles": 1,

      // Absent figures name themselves rather than reading 0.
      "unavailable": [
        { "figure": "playoffAppearances",
          "reason": "only the champion's placement is stored; the bracket is not parsed" },
        { "figure": "tradesPerSeason",
          "reason": "trades are not attributed to a manager (a trade names several rosters)" }
      ],

      // One rank per league chain this manager plays in, never one global rank.
      "ranks": [
        { "figure": "winRate",          "position": 2, "population": 12,
          "leagueName": "(Foot) Ball Knowers", "sleeperLeagueId": "1346366555759341568" },
        { "figure": "averageEfficiency","position": 4, "population": 12,
          "leagueName": "(Foot) Ball Knowers", "sleeperLeagueId": "1346366555759341568" }
      ],

      "waivers": {
        "movesPerSeason": 29.5,
        "seasonsCounted": 2,
        "faab": {
          "budgetBasis": "percent-of-season-budget",
          "typicalBidPct": 0.26, "largestBidPct": 0.26,
          "spentPerSeasonPct": 0.26, "claimsPerSeason": 1.0,
          "bidSuccessRate": 0.5
        },
        "faabExcludedSeasons": [
          { "season": 2025, "leagueName": "(Foot) Ball Knowers",
            "reason": "used waiver priority, not FAAB" }
        ]
      }
    },
    { "sport": "nba", "...": "same shape, its own totals" }
  ],

  // Unchanged, already per-sport.
  "draftHistory": [ { "sport": "nfl", "...": "…" } ],

  // Retained for one release. See Migration.
  "seasons": [ "…flat list, now with sport on every row…" ]
}
```

## Field rules

| Field | Rule |
|---|---|
| `careers[].sport` | From `league.sport`. Never inferred from the league name — "Ball Knowers" and "(Foot) Ball Knowers" differ by a parenthesis. |
| `careers[].seasons[].complete` | `league.status == "complete"`. A null status is **not** complete. |
| `careers[].seasons[].champion` | `finalPlacement == 1 && complete`. False for every in-progress season. |
| `careers[].seasons[].counted` | False for a season with no scored weeks (NBA 2026, ingested and unplayed). Uncounted seasons are listed but contribute to nothing. |
| `careers[].seasonsCounted` | The count of `counted: true` seasons, and the divisor behind every average in the same object. One source for the number and its label. |
| `winRate` | `wins / (wins + losses + ties)`. Null, not 0, when no games have been played. |
| `averageEfficiency` | Weeks-weighted mean of per-season efficiency from `RosterManagementService`. **Never** derived from `roster_season.points_possible` — that column gives 92.7% where this gives 91.6% for the same manager-season, and having both would be two implementations of one rule. Null, never 1.0, when there is no potential to divide by. |
| `weeksCounted` / `weeksExcluded` | Carried from `RosterManagementService.TeamRow`; an excluded week must be visible, not silently absent. |
| `winsAboveExpected` | Sum of per-season `ExpectedWinsService` figures. Across a whole league the figure sums to zero; a rollup that breaks that has double-counted. |
| `unavailable[]` | Every figure the reference page shows that this app genuinely cannot attribute. Present even when empty, so "no answer" is distinguishable from "an older server". |
| `ranks[]` | Always carries `population` and `leagueName`. A manager in two football chains gets two entries per figure. |
| `waivers.faab` | Null when no season used FAAB. All figures are fractions of each season's own `waiver_budget` — budgets in this database range 100 to 10000, so dollars do not compare across one manager's seasons. |
| `waivers.faabExcludedSeasons[]` | Each names its reason. `waiver_type 0` is waiver priority: NFL 2025 holds 321 waiver rows and zero bids, and that is correct. |

## Migration

`seasons` is a **shipped field with a live consumer** (`ManagerHistory.tsx`), so it is not removed in
the same change that adds `careers`:

1. Add `sport`, `leagueName` and `complete` to every existing `seasons[]` row, and correct `champion`.
   `ManagerHistory.tsx` switches to per-sport blocks off that alone. This is US1 + US2 and it ships
   first.
2. Add `careers[]` (US3, US6). The page reads `careers`.
3. Remove `seasons[]` once nothing reads it, in a change of its own.

At no point does a caller see a career total spanning two sports.

## Verification

- `GET /api/managers/7/history` — `careers[sport=nfl]` reads `wins: 12, losses: 4` including the West
  Coast 2026 row. Against **(Foot) Ball Knowers alone** the figures are `11-4`, `pointsFor 2200.24`,
  `winRate 0.733`, which reconcile exactly with ffwrapped's published profile for league
  `1346366555759341568` (`RECORD 11-4`, `POINTS FOR 2200.2`, `WIN RATE 73.3%`). The difference is that
  ffwrapped scopes a career to one league and this endpoint scopes it to one sport — the per-league
  scoping lives in `ranks[]`.
- `titles` reads **1**, not 2. ffwrapped prints 2, which is what
  `metadata.latest_league_winner_roster_id` produces on an in-season league.
- `careers[sport=nba]` for the same manager reports its own totals, through the same code path, with
  no sport branch in the service layer.
