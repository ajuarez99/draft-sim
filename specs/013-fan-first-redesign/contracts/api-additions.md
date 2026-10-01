# Contract: API additions

All changes are **additive fields** on existing responses. No endpoint is added,
removed or renamed, and no field is removed or retyped. An older frontend ignores
the new fields. A newer frontend against an older backend (the services deploy
independently on Railway) must treat each new field as absent and degrade:

| Field missing | Frontend behaviour |
|---|---|
| history `canCommission` | treat as `false`: hide Compute (fails closed, like today's other gates) |
| expected-wins `allPlay` / `median` | standings show "—" in those columns |
| `grade` / `gradesEarly` | no grade chip; the number still shows |

`web/src/api.ts` types are updated **in the same commit** as each Java change
(AGENTS.md hard rule). Every new field is optional in TypeScript for the
rollout window described above.

## GET /api/leagues/{sleeperId}/history
```jsonc
{
  // ...existing fields unchanged...
  "canCommission": false   // NEW. LeagueMembership.canCommission(league, X-Sleeper-User)
}
```

## GET /api/leagues/{sleeperId}/expected-wins
```jsonc
{
  "teams": [{
    // ...existing fields unchanged...
    "allPlay": { "wins": 22, "losses": 11, "ties": 0 },   // NEW
    "median":  { "wins": 2,  "losses": 1,  "ties": 0 }    // NEW
  }]
}
```
`median.ties` counts a score exactly equal to the week's median (possible only with
an odd team count, or equal scores at the middle).

## GET /api/leagues/{sleeperId}/roster-management
```jsonc
{
  "gradesEarly": true,            // NEW. weeksScored < SeasonWindow.EARLY_THRESHOLD_WEEKS
  "teams": [{ /* existing */ "grade": "B+" }]   // NEW, nullable
}
```
Rank basis: `efficiency`, descending. A null efficiency means a null grade.

## GET /api/leagues/{sleeperId}/analysis
```jsonc
{
  "rankingScores": {
    "gradesEarly": true,          // NEW
    "rows": [{ /* existing */ "grade": "A-" }]   // NEW, nullable
  }
}
```
Rank basis: the existing composite ranking score, descending.

## Not changed (explicitly)
- `POST /api/leagues/{id}/power/backfill`: server gate unchanged (research R1).
- Commissioner key / `X-Admin-Token` flow: unchanged.
- `SimulationResult` shape: unchanged (parity baselines hash it).
