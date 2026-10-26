# Data model: Compare two players (spec 049)

> **Amended after review 2026-10-26** (plan A3, A7, A12, A14). `ThisWeek` lost its date range and is now lock-ins'
> rule; `WindowQualification` is new; the client window-full rule is gone.

Nothing is stored. There is no table and no migration.

## ThisWeek (new, on the player page response)

Java: the `PlayerStatsService.ThisWeek(int week, int played, int remaining)` record, as the nullable
`PlayerStatsPage.thisWeek` field. It is mirrored in `api.ts`. Computed from the **URL** league:

- `week`: `LockInService.liveWeek(schedule, NextGames.today(clock), leg)`.
- `played`: his URL-season box lines with that `week`.
- `remaining`: `NextGames.forTeam(schedule, team, today, ∞)`, keeping games with that `week` whose `gameId` is
  not already played.

It is null when the URL league is complete, he has no team, there is neither a schedule nor a `leg`, or the sport
isn't basketball.

## WindowQualification (new, on the player page response)

`Map<WindowKind, WindowQualification(boolean qualified, String reason, Integer lastN)>`, from the
`Computed.qualification` the server already computes, plus `WindowKind.lastN()`. It is empty when the page is
unavailable.

## Comparison (client only, from the URL)

| Field | Source |
|---|---|
| `leagueId` | the route param |
| `a`, `b` | the query params; either may be missing, in which case that side shows the chooser |
| `pages[a]`, `pages[b]` | two `PlayerStatsPage` responses, each loading, loaded or failed on its own, refetched on `useLeagueDataVersion` |

## Row and mark (client only, `web/src/compare.ts`)

- **`Row`**: `{ key, label, window | null, direction: 'HIGHER' | 'LOWER' | 'NONE', value(page), display(page) }`.
- **`markRow(row, pageA, pageB) → { mark: 'A' | 'B' | null, withheld: { A?: why, B?: why } }`.** `mark` is null
  unless every one of these holds:
  - both pages are loaded
  - `season` and `dataAsOf` are equal
  - `direction != NONE`
  - both values are non-null (a `Rate` with a `reason` counts as null)
  - the displayed strings differ
  - neither window is `smallSample`
  - for LAST_N, neither has `games < lastN`, and neither has `reason == NOT_QUALIFIED_STALE`
