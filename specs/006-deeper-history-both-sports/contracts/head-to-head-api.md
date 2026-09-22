# Contract: `GET /api/managers/{aId}/versus/{bId}`

**Kind**: HTTP, read-only, new. **Story**: US4.

**Auth**: the caller must pass `LeagueMembership#canSeeManager` for **both** managers. 404 otherwise,
for the same reason the manager history route does it: a manager id is a small integer.

---

## Why this is buildable now and was not in 002

002 measured **8 paired rows per season** — the final week only — because the fixture upsert sat inside
a loop that skipped weeks already present in `roster_week_points`. That gate has since been fixed and
the seasons backfilled. Measured 2026-09-21:

| League | Season | paired rows | paired weeks |
|---|---|---|---|
| (Foot) Ball Knowers | 2025 | 196 | 17 |
| (Foot) Ball Knowers | 2026 | 168 | 14 (scheduled, unscored) |
| Ball Knowers (nba) | 2024 | 280 | 24 |
| Ball Knowers (nba) | 2025 | 244 | 21 |
| West Coast | 2025 | 204 | 18 |

So every played season in both sports can answer head-to-head today, with no ingest work.

---

## Response shape

```jsonc
{
  "a": { "managerId": 7,  "manager": "popsharky",  "avatarId": "…" },
  "b": { "managerId": 43, "manager": "jpelwell",   "avatarId": "…" },

  // One entry per sport the two have actually shared a league in.
  // Never one combined record (US4.2).
  "sports": [
    {
      "sport": "nfl",
      "aWins": 2, "bWins": 1, "ties": 0,
      "meetings": [
        { "season": 2025, "week": 4,  "leagueName": "(Foot) Ball Knowers",
          "sleeperLeagueId": "1254190892974084096",
          "aPoints": 141.68, "bPoints": 118.02, "winner": "A" },
        { "season": 2025, "week": 11, "leagueName": "(Foot) Ball Knowers",
          "sleeperLeagueId": "1254190892974084096",
          "aPoints": 96.34,  "bPoints": 130.50, "winner": "B" }
      ],
      "seasonsExcluded": [
        { "season": 2026, "leagueName": "(Foot) Ball Knowers",
          "reason": "fixtures are scheduled but no week is scored yet" }
      ],

      // The side-by-side comparison, each side from the same CareerProfile
      // the manager-profile contract defines. Included so the page needs one
      // call, not three.
      "comparison": {
        "titles":             { "a": 1,       "b": 0 },
        "record":             { "a": "12-4",  "b": "10-5" },
        "pointsFor":          { "a": 2390.14, "b": 1734.40 },
        "pointsPerGame":      { "a": 149.38,  "b": 115.63 },
        "winsAboveExpected":  { "a": 1.82,    "b": -0.41 },
        "averageEfficiency":  { "a": 0.916,   "b": 0.929 },
        "seasonsCounted":     { "a": 2,       "b": 2 }
      }
    }
  ],

  // Present and empty rather than absent, so "never met" is distinguishable
  // from an older server (US4.3).
  "sharedNothing": false
}
```

## Field rules

| Field | Rule |
|---|---|
| `sports[]` | One entry per sport in which the two managers have shared at least one league season. Empty array plus `sharedNothing: true` when they never have — never a `0-0` record. |
| `meetings[]` | A pairing counts only when **both** sides have a stored `starters_points`. The 2026 chains hold scheduled fixtures with no scores; those are excluded and named. |
| `winner` | `"A"`, `"B"` or `"TIE"`, decided by `starters_points`. Ties are counted and shown, never folded into losses (US4.5). |
| `seasonsExcluded[]` | Any shared season that contributed no meetings, with its reason. A season that contributed nothing must be named, not silently absent (US4.4). |
| `comparison` | Each side is the `CareerProfile` figure for that sport, from the same service the manager profile uses. Two pages showing the same manager must not show two numbers. |
| `pointsPerGame` | `pointsFor ÷ (wins + losses + ties)`, not `÷ weeks`, so it matches the record beside it. |

## Derivation

```sql
-- pairing: two rosters sharing a matchup_id within one league-season-week
select m.season, m.week, m.roster_id, m.matchup_id
from league_matchup m
where m.league_id = ? and m.matchup_id is not null

-- score: joined per roster-week
join roster_week_points p
  on p.league_id = m.league_id and p.week = m.week and p.roster_id = m.roster_id
```

The rosters a manager owned are resolved through `roster_season.manager_id`, per league-season — a
manager can own different roster ids in different seasons (popsharky is roster 1 in (Foot) Ball
Knowers and roster 7 in West Coast).

## Verification

- **Conservation check (SC-006)**: summed across every pair of managers in a season, `meetings` equals
  that season's paired-fixture count. For (Foot) Ball Knowers 2025 that is 196 roster-rows → 98
  meetings. A join that double-counts or drops a pairing fails this.
- Each meeting's `winner` matches the higher `starters_points` for that league-season-week.
- Two managers who shared both a football and a basketball league return two `sports[]` entries with
  separate records — never one combined `aWins`.

## Frontend registration

The comparison page is registered in `web/src/destinations.ts` with `sports: ['nfl', 'nba']` written
out explicitly, not inherited from `ALL_SPORTS`. A defaulted sport list asserts a rule rather than a
value, and `destinations.test.ts` already fails a league-scoped route in `App.tsx` with no matching row.
