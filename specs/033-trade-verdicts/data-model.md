# Spec 033 data model

> **Renumbered at merge (2026-10-14): V32 → V34.** Spec 034 merged to main first with V33, and
> production applied it. Flyway (out-of-order off) would refuse a V32 arriving after V33, and fail
> startup. V32 had never reached main or prod, so it was renamed rather than edited. Every "V34" in
> this spec's docs was written as "V32" before the merge.

## Storage: V34 (append-only; V31 is the highest today)

`V34__trade_assets.sql`:

| Table | Column | Type | Meaning |
|---|---|---|---|
| `league_transaction` | `draft_picks` | `jsonb` null | Sleeper `draft_picks` verbatim. `null` = stored before V34, unknown. `[]` = none. |
| `league_transaction` | `waiver_budget` | `jsonb` null | Sleeper `waiver_budget` verbatim, with the same null/`[]` rule. |

No other schema change. Everything else is computed per request from existing tables:
- `roster_week_points`: `players_points` and `starters`;
- `league_transaction`;
- `draft` / `draft_pick`;
- `roster_season`;
- `league.status`.

## Engine types (pure; no DB access inside)

`OwnershipTimeline` is built from the inputs below, so the T-tests can feed it fixtures.

- **Inputs:**
  - `List<WeekRow(week, rosterId, Map<playerKey, points>, Set<starterKey>)>`
  - `List<Move(txId, type, leg, acceptedAt, Map<playerKey, toRoster>, Map<playerKey, fromRoster>)>`,
    ordered by `acceptedAt`, then id
  - `List<DraftedPick(round, slot, managerId, playerKey)>`
  - `Map<rosterId, managerId>`
  - `lastScoredWeek`
  - `seasonComplete`
- **`Attribution(week, playerKey) → {startedBy?: rosterId, listedOn: Set<rosterId>}`** (R1)
- **`Stretch(playerKey, rosterId, firstWeek, lastWeek, startedPoints, benchPoints,
  duplicateWeeks, how: DRAFT|TRADE|WAIVER|FREE_AGENT|COMMISSIONER|UNEXPLAINED, viaTxId?)`** (R2, R5)
- **`Reconciliation(playerWeeksChecked, unexplainedStretches, sameWeekInAndOut,
  neverOnAScoredRoster, examples: List<String> ≤ 10)`** (R5; fields renamed after plan
  review, F1)

`TradeVerdictService` (engine, Spring) loads the inputs, builds the timeline, and produces:

- **`TradeAsset`**: `kind` is PLAYER | PICK | FAAB.
  - `PLAYER`: `playerKey`, `name`, `position`, `window?{firstWeek, lastWeek}`,
    `startedPoints`, `benchPoints`, `weeksCounted`, `duplicateWeeks`, `postMoveRank`,
    `status`.
  - `PICK`: `season`, `round`, `originalRosterId`, plus a resolved `PLAYER` asset or
    `status = NOT_DRAFTED_YET | PICK_NOT_MATCHED`.
  - `FAAB`: `amount`, `fromRosterId`.
  - `status` is one of COUNTED | NO_WEEK_YET | UNDONE | ALREADY_HELD | LEFT_BEFORE_PLAYING |
    NEVER_PLAYED_FOR_THEM | NOT_DRAFTED_YET | PICK_NOT_MATCHED. *(LEFT_BEFORE_PLAYING added
    after plan review, F2.)* *Amended after live verification:* UNDONE only when the first
    later move (strictly after T by `created_at`, then id) that takes the player off the receiver
    is a TRADE or COMMISSIONER move putting him on a giving roster of T; any other first move off
    the receiver (a drop, waiver/free-agent churn, a trade elsewhere) is LEFT_BEFORE_PLAYING.
  - *Amended after plan review (F4/F5), then again at task generation:* only **final** weeks count, per `ScoredWeeks.of(leagueId).isFinal(week)`, the existing single owner of that rule. It is not a second read of `last_scored_leg`.
    `weeksCounted`, `startedPoints` and `benchPoints` start at the asset's first week
    **started** by the receiver. Leading listed-but-unstarted weeks are kept in the stretch,
    flagged.
- **`TradeSideResult`**: `rosterId`, `teamName`, `assets`, `startedTotal`, `benchTotal`,
  `weeksCounted` (distinct weeks with ≥1 counted asset).
- **`TradeVerdict`**:
  - `state`: WINNER | EVEN | NO_WEEK_YET | UNDONE | RESTORED | NONE_COUNTED | NOT_COMPARABLE;
  - `winnerRosterId?`, `margin?` (started points, first minus second, 1 dp);
  - `final` (`league.status` complete), `weeksCounted`.
  - Rules:
    1. NOT_COMPARABLE when any side has no PLAYER asset (resolved picks count as players).
    2. Else, when no asset on any side has a counted week (*amended during the build, plan-review F7*): RESTORED if every player/resolved-pick asset is ALREADY_HELD or UNDONE and at least one is ALREADY_HELD (*amended after live verification, plan-review F8*); UNDONE if every one is UNDONE; NO_WEEK_YET if any asset is NO_WEEK_YET; otherwise NONE_COUNTED.
    *ALREADY_HELD (F8):* if the receiver already held the player (R1) in the scored week before the trade's leg, that is, the window's stretch starts before the leg, the trade did not move him in scored terms. Nothing counts (0 weeks, no started or bench points). The check runs before the window counting and the cause logic.
    3. Else EVEN when |margin| < 0.05. *(Plan review F6:)* when every side's
       `startedTotal` is 0, the page says "Even: neither side started what it got", never a
       score.
    4. Else WINNER, the side with the most started points.
  - Sides are ranked by started total (FR-008).
- **`TradeResult`**: `txId`, `leg`, `acceptedAt`, `sides` (ranked), `verdict`,
  `assetsLoaded` (false when the row's `draft_picks` is null, per R6).

**Validation rules:**
- Started points are never summed across rosters.
- A duplicate week's bench points appear on both rosters, flagged, and count toward neither
  `startedTotal`.
- A pick whose drafter isn't the receiver's manager is PICK_NOT_MATCHED. It's never resolved
  to a guess.

## Existing records changed

`TransactionAnalysisService.Trade` / `TradeSide` (wire: `trades[]`) gains the fields in
[contracts/api.md](contracts/api.md). They're rebuilt from all of a trade's assets, not just
`adds`, so FAAB-only and picks-only trades appear (R7). `MovedPlayer.postMoveRank` for traded
players is computed over the R3 window (FR-013); for adds it's unchanged.
