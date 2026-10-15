# Data model: rivalry week pick (spec 037)

No schema change and no migration. Everything is derived per request from data spec 034
already loads (research R1).

## Engine inputs (pure, no Spring): `RivalryWeekPicker`

| Input | Meaning |
|---|---|
| `managerIds` | Current-season managers, same list the matching uses today |
| `schedule` | `week → list of (aManagerId, bManagerId)` for regular-season weeks only (`week < playoffWeekStart`). Pairs with a null manager or a self-pair are dropped, as spec 034 F15 does. |
| `eligibleWeeks` | Ascending, per research R5 |
| `cap` | `max-regular-season-meetings` (2) |
| `score` | The existing `RivalryMatching.PairScore` (unchanged spec 034 score) |
| `maxManagers` | Existing cap on the DP |

## Engine output: `WeekPick`

```
WeekPick
  unconstrained : Matching            -- spec 034's history-best set (same as today's pairings)
  candidates    : List<Candidate>     -- one per eligible week, sorted total desc, week asc;
                                         infeasible weeks last, by week
  recommended   : Candidate | null    -- candidates[0] if feasible, else null
  overCapPairs  : List<(a, b, reg)>   -- pairs the PUBLISHED schedule already has above cap (R2: none today)

Candidate
  week          : int
  feasible      : boolean             -- a full matching exists with every chosen pair allowed
  matching      : Matching | null     -- null when infeasible
  total         : double              -- matching total; null/absent when infeasible
  heldBack      : List<(a, b)>        -- pairs in `unconstrained` that are NOT allowed this week
  displaced     : List<(a, b)>        -- this week's scheduled pairs not in `matching`
  alreadyIn     : Set<(a, b)>         -- pairs in `matching` that are scheduled this week anyway
  tiedWith      : int                 -- how many feasible candidates share this total (within EPS)
```

### Rules (validation)

- **Allowed(a, b, W)** ⇔ `reg(a,b) − inW(a,b, W) + 1 ≤ cap` (research R3).
- For any feasible candidate, every pair's regular-season count after substitution is `≤ cap`.
  The exception is a pair already above the cap in the published schedule and not chosen,
  whose count is unchanged. This is the invariant SC-001's script checks.
- `heldBack ⊆ unconstrained.pairs` and `heldBack ∩ matching.pairs = ∅`.
- `displaced ∩ alreadyIn = ∅`. Also `|displaced| + |alreadyIn| = scheduled pairs that week`,
  except for pairs with a manager outside `managerIds`, which are counted in `displaced`.
- **Odd league:** the matching's sit-out manager is never constrained. Their original
  opponent's pair lands in `displaced`.
- **Determinism:** candidates are computed in ascending week order. The recommended week
  replaces the running best only when its total is greater by more than `EPS`.

## `RivalryMatching` change

Add an overload `match(ids, fn, allowed, maxManagers) → Optional<Matching>`.

- `allowed(a, b)` is a predicate. A disallowed pair is never a DP edge.
- The result is empty when no full matching exists. In an odd league that means even with the
  best sit-out choice.
- The existing `match(ids, fn, max)` delegates with `allowed = always` and unwraps, so it
  behaves exactly as before. That's covered by the existing tests, which must pass unchanged.

## Wire records (Java, mirrored field-for-field in `web/src/api.ts`)

```
Recommendation (existing)  + week: WeekRecommendation | null   -- null whenever status != "OK"

WeekRecommendation
  status       : "OK" | "NOT_PUBLISHED" | "NO_SEASON_FORMAT" | "NO_ELIGIBLE_WEEK" | "NO_WEEK_FITS"
  cap          : int                       -- 2
  capLabel     : String                    -- "league rule"
  recommended  : WeekOption | null         -- non-null iff status == "OK"
  others       : List<WeekOption>          -- runner-ups (feasible), same shape, already ordered
  unfitWeeks   : List<Integer>             -- eligible weeks with no full set under the cap
  overCapNote  : String | null             -- set only when the published schedule already exceeds the cap
  note         : String                    -- "doesn't change your Sleeper schedule" (FR-011)

WeekOption
  week         : int
  total        : double (3 dp)
  bestTotal    : double (3 dp)             -- unconstrained total, so "x of a possible y" is printable
  reason       : Reason                    -- existing Reason record: code + text + numbers
  pairings     : List<WeekPairing>
  unpaired     : Unpaired | null
  heldBack     : List<HeldBack>
  displaced    : List<Displaced>

WeekPairing
  pairing          : Pairing               -- the existing record, reused (history reason, thin flag)
  alreadyScheduled : boolean

HeldBack   { aManagerId, bManagerId, meetsInWeeks: List<Integer> }   -- the weeks they already meet
Displaced  { aManagerId, bManagerId, stillMeetsInWeeks: List<Integer> } -- empty list = no other meeting
```

- **Reason codes:** `ALL_BEST_FIT` (total equals the best within EPS) or `BEST_UNDER_CAP`.
  `numbers` carries `heldBack`, `total`, `bestTotal` and `tiedWeeks`, and the text prints
  exactly those (spec 034's SC-004 convention).
- **Nullable fields** (`recommended`, `unpaired`, `overCapNote`, `week`) live in records, never
  `Map.of` (constitution).
