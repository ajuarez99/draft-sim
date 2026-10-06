# Contract: draft grades

> **Amended after review, 2026-10-06** (dispositions in [plan.md](plan.md#amended-after-review-2026-10-06)):
> `neighborWindow` → `neighborsPerSide` (F1). `early` → `gradesEarly` plus
> `earlyThresholdWeeks` (N5). `firstWeek`/`lastWeek` → `countedWeeks` (N6). New fields:
> `creditedForYou` (F2), `weeksUnknownForYou` (F10), `weeksMissingGameData` (F7),
> `unmappedPicks` (N1), `unpositionedPicks` (N11), `averageTeamRawValue` (F4). Two new
> invariants. The UI's two value views are now exclusive (F8).

> **Amended during build, 2026-10-06:** `neighborsPerSide` → **`minPicksPerPosition`**. The baseline
> is a per-position log fit (data-model). `slotBaseline` is the fitted value for that pick number
> at that position. The PlayerCard baseline line reads "vs. what a {position} taken at pick
> {pickNo} scored in this draft (fitted)".

## C1. `GET /api/drafts/{sleeperDraftId}/grades`

Header `X-Sleeper-User` as on every draft route. Scoped by `LeagueMembership.visibleDraft`:
a draft the caller can't see is a **404**, the same as `/board`. Added to
`AccessControlMvcIT`'s hand-listed draft routes (F11).

### 200, available

```json
{
  "draftId": "1229352720230514688",
  "sport": "nba",
  "season": 2025,
  "available": true,
  "reason": null,
  "productionBasis": "WEEKLY_AVERAGE_GAME",
  "countedWeeks": [1, 2, 3, "…", 21],
  "weeksCounted": 21,
  "weeksMissingGameData": [],
  "gradesEarly": false,
  "earlyThresholdWeeks": 4,
  "minPicksPerPosition": 8,
  "excludedPicks": 0,
  "unmappedPicks": 0,
  "unpositionedPicks": 0,
  "averageTeamRawValue": -300.0,
  "picks": [
    {
      "pickNo": 43, "round": 4, "slot": 7,
      "sleeperPlayerId": "4635", "playerName": "Jamal Murray", "position": "PG",
      "production": 563.1, "weeksPlayed": 21,
      "slotBaseline": 400.0, "valueOverSlot": 163.1,
      "positionDrafted": 4, "positionFinish": 2,
      "countedForYou": 480.0, "creditedForYou": 560.0,
      "weeksStartedForYou": 18, "weeksUnknownForYou": 0
    }
  ],
  "teams": [
    {
      "slot": 7, "manager": "…", "avatarId": "…",
      "draftValue": 412.6, "rank": 2, "grade": "A",
      "bestPickNo": 43, "worstPickNo": 79
    }
  ],
  "steals": [43, 114, 168, 110, 25],
  "busts": [57, 9, 8, 120, 109]
}
```

Every number in the example is illustrative. The baseline changed after review (F1), so
the research R4 prototype's values no longer apply.

### 200, unavailable

```json
{
  "draftId": "…", "sport": "nfl", "season": 2026,
  "available": false, "reason": "NO_SCORED_WEEKS",
  "productionBasis": "WEEKLY_GAME",
  "countedWeeks": [], "weeksCounted": 0, "weeksMissingGameData": [],
  "gradesEarly": true, "earlyThresholdWeeks": 4,
  "minPicksPerPosition": 8,
  "excludedPicks": 0, "unmappedPicks": 0, "unpositionedPicks": 0,
  "averageTeamRawValue": null,
  "picks": [], "teams": [], "steals": [], "busts": []
}
```

### Nullable fields

`reason`, `minPicksPerPosition` (null only under `NOT_CONFIGURED`), `averageTeamRawValue`,
`position`, `slotBaseline`, `valueOverSlot`, `positionDrafted`, `positionFinish`,
`countedForYou`, `creditedForYou`, `weeksStartedForYou`, `weeksUnknownForYou`, `manager`,
`avatarId`, `draftValue`, `rank`, `grade`, `bestPickNo`, `worstPickNo`. The response is
built from records, not `Map.of` (AGENTS.md hard rule).

### Invariants (test these)

1. When `available`: `picks.length + excludedPicks` = the draft's stored pick count. Unavailable
   responses carry empty lists (amended after code review B6).
2. For a complete draft with config: `available = false ∧ reason = NO_SCORED_WEEKS` ⇔
   `weeksCounted = 0`. Under `NOT_CONFIGURED` and `DRAFT_NOT_COMPLETE`, `weeksCounted` is 0
   and every list is empty (check order in data-model).
3. `gradesEarly = weeksCounted < earlyThresholdWeeks`, and `earlyThresholdWeeks` comes from
   `SeasonWindow.EARLY_THRESHOLD_WEEKS`, never a local constant.
4. `production ≥ 0`, `weeksPlayed ≤ weeksCounted`, and
   `weeksStartedForYou + weeksUnknownForYou ≤ weeksCounted`.
5. Σ over teams of non-null `draftValue` ≈ 0 (centred, F4, within rounding).
6. `steals` and `busts` hold ≤ 5 pick numbers each, all present in `picks`, and are
   disjoint (N8).
7. Football: `productionBasis = WEEKLY_GAME`. Basketball: `WEEKLY_AVERAGE_GAME`.
8. **`countedForYou ≤ production`** whenever it's non-null (F2).
   *Amended during live verification, 2026-10-06:* this holds only when the player has no
   negative-scoring week. Counted-for-you sums a **subset** of production's weeks, and a
   subset can exceed the total when a left-out week is negative. NFL 2026 pick 129 (Tyrone
   Tracy): production −0.3 (weeks 1–3, week 1 −0.6), never started for his drafter, so
   counted 0.0. NFL 2025 has 139 negative-scoring games. The structural rule (the started weeks
   are a subset of the counted weeks, valued by the same weekly rule) is what the unit tests
   check. The IT asserts the inequality on NBA 2025 and NFL 2025, where it holds. Code review
   found a basketball case too: NBA 2024 Trey Murphy, counted 410.21 > production 409.21.
9. `countedWeeks` ∩ `weeksMissingGameData` = ∅, and `weeksCounted = countedWeeks.length`.

## TypeScript mirror (`web/src/api.ts`, same change, FR-011)

```ts
export type ProductionBasis = 'WEEKLY_GAME' | 'WEEKLY_AVERAGE_GAME'
export type DraftGradesReason = 'NOT_CONFIGURED' | 'DRAFT_NOT_COMPLETE' | 'NO_SCORED_WEEKS'

export type PickGrade = {
  pickNo: number
  round: number
  slot: number
  sleeperPlayerId: string
  playerName: string
  position: string | null
  production: number
  weeksPlayed: number
  slotBaseline: number | null
  valueOverSlot: number | null
  positionDrafted: number | null
  positionFinish: number | null
  countedForYou: number | null
  creditedForYou: number | null
  weeksStartedForYou: number | null
  weeksUnknownForYou: number | null
}

export type TeamGrade = {
  slot: number
  manager: string | null
  avatarId: string | null
  draftValue: number | null
  rank: number | null
  grade: string | null
  bestPickNo: number | null
  worstPickNo: number | null
}

export type DraftGrades = {
  draftId: string
  sport: Sport            // the existing union in api.ts
  season: number
  available: boolean
  reason: DraftGradesReason | null
  productionBasis: ProductionBasis
  countedWeeks: number[]
  weeksCounted: number
  weeksMissingGameData: number[]
  gradesEarly: boolean
  earlyThresholdWeeks: number
  minPicksPerPosition: number | null
  excludedPicks: number
  unmappedPicks: number
  unpositionedPicks: number
  averageTeamRawValue: number | null
  picks: PickGrade[]
  teams: TeamGrade[]
  steals: number[]
  busts: number[]
}

export function getDraftGrades(draftId: string): Promise<DraftGrades>
```

## UI contract (`CompletedDraftBoard`)

- **One value view at a time (F8).** When the draft has ADP, a segmented control shows
  "Off / Steals & reaches / How it played out". Without ADP it's a single "How it played
  out" chip. A cell never carries two signed numbers or two tints. A Vitest covers
  switching between the two views.
- "How it played out" shows only for a complete draft. First selection fetches C1.
  Unavailable shows one muted sentence from the reason code.
- On: each cell shows the signed value over slot, and its tint has **its own scale**,
  relative to this draft's spread (e.g. |value| / the 90th percentile of |value|, capped).
  It doesn't reuse `tintPercent`, which is scaled in picks and would saturate on points.
- The PlayerCard for a pick lists, each labelled: season production ("season points,
  counting each week's average game" in NBA, F5), weeks played, the baseline ("vs. the
  N {position}s drafted around him"), counted for you, weeks started for you, Sleeper's
  credited points (NBA only, labelled "credited by Sleeper, a different scale"), and
  positional ranks ("4th PG drafted, finished 2nd"). Unknown weeks are named when
  non-zero.
- A grade strip above the board, in a **new** component and class (`DraftGradeStrip`,
  `.draft-grade-strip`; `TeamStrip`/`.team-strip` already exist, N10). It has one item per
  slot: draft value "vs. the average team", a `GradeChip`, and best and worst pick.
  `GradesEarlyBadge` shows once when `gradesEarly`, using `gradesEarlySentence(earlyThresholdWeeks)`.
- Legend: the basis, plus `weeksCounted` (or the list when `countedWeeks` isn't
  contiguous, N6), plus any `weeksMissingGameData`. NBA wording states only what was
  measured (F5): "Season points, counting each week's average game. In the basketball
  leagues measured so far, Sleeper credited one game per starter per week."
- Phone width: the strip scrolls inside itself, not the page.
