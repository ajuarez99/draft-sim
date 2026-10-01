# Contract: API additions

> **Amended after adversarial review (2026-09-30):** this contract had four fields.
> Review found it needed eight: `isMe` on standings and weekly sides (B2, B3) and
> `adpAtDraft` on real picks (B1) were added. The analysis field is `entries`, not
> `rows` (S5). A missing grades config means null grades, not a failed startup (S7).

All changes are **additive fields** on existing responses. No endpoint is added,
removed or renamed, and no field is removed or retyped.

**Deploy order: backend first, then frontend.** A new frontend against an old
backend sees every new field as absent and must degrade as below. Without the
backend-first order, History's Compute would be hidden from the commissioner too
until the backend caught up.

| Field missing | Frontend behaviour |
|---|---|
| history `canCommission` | treat as `false`: Compute hidden (fails closed) |
| history `StandingRow.isMe` | league home shows no "your record" block |
| weekly `WeeklySide.isMe` | normal matchup order; league home shows no "latest matchup" |
| real-board `RealPick.adpAtDraft` | steals/reaches toggle hidden |
| expected-wins `allPlay` / `median` | standings show "—" in those columns |
| `grade` / `gradesEarly` | no grade chip; the number still shows |

`web/src/api.ts` types change **in the same commit** as each Java change
(AGENTS.md). Every new field is optional in TypeScript.

## GET /api/leagues/{sleeperId}/history
```jsonc
{
  "canCommission": false,                 // NEW. LeagueMembership.canCommission(visibleLeague row id, X-Sleeper-User)
  "seasons": [{ "standings": [{
      /* existing */ "isMe": true         // NEW. row's manager == caller's manager (X-Sleeper-User → manager id); false when signed out
  }]}]
}
```

## GET /api/leagues/{sleeperId}/weekly-report/{week}
```jsonc
{ "matchups": [{ "home": { /* existing WeeklySide */ "isMe": false },   // NEW, same owner rule
                 "away": { "isMe": true } }] }
```
(Exact nesting follows the existing `WeeklySide` placement. Only the new key is specified here.)

## GET /api/drafts/{draftId}/board (real board)
```jsonc
{ "picks": [{ /* existing RealPick */ "adpAtDraft": 23.4 }] }   // NEW. draft_pick.adp_at_time; null when never captured
```

## GET /api/leagues/{sleeperId}/expected-wins
```jsonc
{ "season": 2026,                          // existing; columns are filled only when it equals the standings row's season
  "teams": [{ /* existing */
    "allPlay": { "wins": 22, "losses": 11, "ties": 0 },   // NEW, regular season
    "median":  { "wins": 2,  "losses": 1,  "ties": 0 } }] }  // NEW, regular season
```
**Invariants:** per team, `allPlay.wins + losses + ties` = Σ over weeks the team
played of (rosters scored that week − 1). League-wide, Σ wins = Σ losses. With an
odd roster count, the median roster ties every week by construction (documented in
How this works).

## GET /api/leagues/{sleeperId}/roster-management
```jsonc
{ "gradesEarly": true,                    // NEW. weeksScored < SeasonWindow.EARLY_THRESHOLD_WEEKS
  "teams": [{ /* existing */ "grade": "B+" }] }   // NEW, nullable. Rank by efficiency desc
```

## GET /api/leagues/{sleeperId}/analysis (NFL only)
```jsonc
{ "rankingScores": { "gradesEarly": true,           // NEW
                     "entries": [{ /* existing ScoreEntry */ "grade": "A-" }] } }   // NEW record component, nullable
```

## GET /api/health
```jsonc
{ "status": "up", "weightsLoaded": true, "gradesLoaded": true }   // NEW. false → every grade is null
```

## Not changed (explicitly)
- `POST /api/leagues/{id}/power/backfill`: server gate unchanged (research R1).
- `PUT` reversal round: still open to any visible member. Out of scope, recorded as a follow-up (review S2).
- Commissioner key / `X-Admin-Token` flow: unchanged.
- `SimulationResult` shape: unchanged (parity baselines hash it).
