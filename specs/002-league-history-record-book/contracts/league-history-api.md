# Contract: League history API

**Branch**: `002-league-history-record-book` · **Date**: 2026-09-16

Two contracts change: the HTTP surface (`LeagueHistoryController`) and the UI contract of the
League History page. Entity fields and their rules live in [data-model.md](../data-model.md); this
document states the wire shape and the guarantees.

---

## 1. `GET /api/leagues/{sleeperId}/history` — EXTENDED

Existing behaviour is preserved in full. `seasons[]` keeps `season`, `leagueId`, `sleeperLeagueId`,
`name`, `standings[]`, and every existing field on a standings row. This is an **additive** change;
no existing field is renamed, retyped or removed.

**Headers**: `X-Sleeper-User` (optional) — unchanged; league visibility is still enforced by
`visibleLeague()` before the chain is walked.

**Responses**: `200` / `404` — unchanged.

### Added: `records` (top level, spans the whole chain)

```jsonc
{
  "sleeperLeagueId": "1346366555759341568",
  "records": {
    "limit": 10,
    "highestWeeks": [
      { "season": 2025, "week": 8, "rosterId": 7,
        "managerId": 41, "manager": "GraftonCarlson", "avatarId": "abc…",
        "points": 205.04 }
    ],
    "lowestWeeks":  [ /* same shape, ascending */ ],
    "closestMatchups": [
      { "season": 2025, "week": 17, "margin": 0.16,
        "winner": { "rosterId": 3, "managerId": 12, "manager": "…", "avatarId": "…", "points": 132.58 },
        "loser":  { "rosterId": 9, "managerId": 18, "manager": "…", "avatarId": "…", "points": 132.42 } }
    ],
    "biggestBlowouts": [ /* same shape, descending */ ],
    "marginsUnavailableReason": "Pairings have not been ingested for seasons before 2026."
  },
  "seasons": [ /* … */ ]
}
```

**Guarantees**

- `records` is always present on a `200`. It is never omitted to signal emptiness.
- `highestWeeks` and `lowestWeeks` have equal length, at most `limit` (FR-003).
- Entries span **every** season in the chain; they are not scoped to `seasons[0]` (FR-001).
- `points` and `margin` are JSON numbers with two decimal places of significance, matching the
  stored `numeric`. They are not pre-formatted strings.
- `manager` / `managerId` / `avatarId` are `null` for an unowned roster-season; `rosterId` is always
  present, so the client can always render something (data-model R1).
- `closestMatchups` / `biggestBlowouts` are `[]` when no pairings are stored, and
  `marginsUnavailableReason` is then a non-null sentence explaining why (FR-010). When margins are
  present, `marginsUnavailableReason` is `null`.
- No Sleeper request is made to serve this endpoint (FR-002).

### Added: `finalRank` fields on each standings row

```jsonc
{
  "rosterId": 7, "managerId": 41, "manager": "GraftonCarlson",
  "wins": 9, "losses": 5, "pointsFor": 1834.22, "champion": true,

  "finalRank": 1,
  "finalRankWeek": 17,
  "rankStatus": "RANKED"
}
```

`rankStatus` is one of:

| Value | Meaning | `finalRank` | Client renders |
|---|---|---|---|
| `RANKED` | A `COMPUTED_REALIZED` snapshot at `week > 0` exists | int | the rank |
| `IN_PROGRESS` | Season is the chain's newest and is not over | `null` | "season in progress" — not an error |
| `NOT_COMPUTED` | Season complete, no snapshot, week points present | `null` | reason **plus the compute control** |
| `UNAVAILABLE` | Season complete, no week points to compute from | `null` | reason, no control |

**Guarantees**

- `rankStatus` is present on every standings row. `finalRank` is non-null **iff** `RANKED`.
- Week 0 never produces `RANKED` (data-model R9) and a `COMMISSIONER` snapshot never produces
  `RANKED` (R10).
- "Season is over" is derived from league settings and sport state, never a hardcoded week
  (FR-011).

---

## 2. `POST /api/leagues/{sleeperId}/power/backfill` — NEW

Computes and stores the final `COMPUTED_REALIZED` snapshot for completed seasons in the chain that
lack one. Backs the control FR-006 requires, so the page never prints an endpoint for the reader to
run.

**Request**: `?season={int}` optional — omitted, every completed season in the chain lacking a
qualifying snapshot is backfilled.

**Headers**: `X-Sleeper-User` (optional), same visibility rule as `history`.

**Response `200`**

```jsonc
{ "backfilled": [ { "season": 2024, "week": 24, "entries": 12 } ],
  "skipped":    [ { "season": 2026, "reason": "season is still in progress" } ] }
```

**Guarantees**

- Idempotent. `PowerRankingRepository#save` upserts on `(league_id, season, week, kind)`, so
  re-running replaces rather than duplicates.
- Makes **no Sleeper call** — `computeRealized` reads only `roster_week_points`.
- Never writes an empty snapshot; a season with no stored week points is reported in `skipped` with
  a reason.
- Never writes to week 0 and never writes `COMMISSIONER`.
- A zero-length `backfilled` array is always accompanied by a populated `skipped` array explaining
  why — a bare zero reads as a broken feature.

**Errors**: `404` league not visible / not found. `400` `season` given but not in the chain.

---

## 3. `POST /api/ingest/league-history/{sleeperId}` — BEHAVIOUR CHANGE

The endpoint's request and response shapes are unchanged. What changes is what it stores.

**Before**: a week already present in `roster_week_points` was skipped entirely, taking the
`league_matchup` upsert with it. Past seasons therefore hold pairings for one week only, and
re-running never repaired it.

**After**: points and pairings are gated independently. A week is fetched when its points are
missing **or** its pairings are missing.

**Guarantees**

- Re-running against a league with complete points and absent pairings **stores the pairings**
  (FR-007). This is the contract's whole point, and it is directly observable:
  `(Foot) Ball Knowers` 2025 must go from 8 paired rows in week 17 to a full slate across weeks
  1–17.
- Re-running against a league that already has both stays cheap — a fully-cached season issues no
  per-week Sleeper request beyond the last scored leg, as today.
- `league_matchup.upsert` is `ON CONFLICT … DO UPDATE`, so repeated runs do not duplicate.
- A week Sleeper answers with all-null `matchup_id`s is still not counted as cached, preserving the
  existing guard against poisoning the cache with an unscheduled week.

---

## 4. UI contract — League History page

The page at `/leagues/:sleeperLeagueId/history` keeps its `PageHeader`, its per-season standings and
its existing "Load past seasons" error affordance. Added:

| Region | Shape | Empty-state requirement |
|---|---|---|
| Record book | Two ranked lists side by side (highest / lowest), each row: avatar, name, score, season/week | If a chain has no stored weeks, one stated sentence — never two empty lists |
| Matchup margins | Two groups of paired cards (closest / blowouts), each card: margin, season/week, both sides with scores | If `marginsUnavailableReason` is non-null, render that sentence in place of the cards |
| Rank column | One cell per standings row | Per `rankStatus`; `NOT_COMPUTED` renders the reason **and a button**, never an endpoint string |

**Binding conventions** (from the repo, recorded in the plan's Constitution Check):

- A control that fires the call — never instructions telling the reader to run it. `LeagueHistory.tsx`
  already carries this lesson in a comment about the ingest button; the backfill must not reintroduce
  the pattern.
- Every empty panel states its reason (FR-010).
- Ranked lists and paired cards are deliberately different shapes, matching the shape of their
  content rather than reusing one uniform card for both.
- Nothing renders a week count, season length or playoff boundary that assumes football (FR-011).

---

## Backward compatibility

All additions are new keys. A client built against the current response continues to work: it sees
extra fields on `seasons[].standings[]` and an extra top-level `records` object, and ignores both.
`web/src/api.ts`'s `StandingRow` and `LeagueHistory` types gain optional fields rather than required
ones, so `getManagerHistory` — which reuses `StandingRow` and will **not** carry rank data — still
typechecks.
