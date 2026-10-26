# Data model: Weekly High-Score Ledger (spec 048)

> **Amended after review (2026-10-25)**: statuses `NOT_FINAL`/`MISSING` added; `NO_SCORES` = every
> stored score exactly 0; balances from `roster_season`; naming via `RosterOwners`; a row (even 0.0)
> is a payer; shortcut is per-component greedy (plan A1, A4–A6, A12).

## Stored: `league_weekly_pot` (migration V41)

| Column | Type | Rule |
|---|---|---|
| `league_id` | bigint PK, FK `league(id)` on delete cascade | one row per league-season |
| `enabled` | boolean not null | absent row = off (FR-001) |
| `stake_cents` | int not null, `check (stake_cents between 1 and 100000)` | default 100 |
| `updated_by` | bigint null, FK `manager(id)` | who last saved (null for the app owner with no manager row) |
| `updated_at` | timestamptz not null default now() | |

Writes are an upsert on `league_id`. Turning it off keeps the stake (so turning it back on
restores it).

## Derived (computed on every read, never stored — FR-015)

### `WeekResult`
- `week` int
- `status`: `DECIDED` | `NO_SCORES` (rows exist, every stored score is exactly 0) | `NOT_FINAL` | `MISSING` — only `DECIDED` moves money
- `winners`: list of `rosterId` (1 = untied, ≥2 = tie, empty for `NO_SCORES`)
- `winningPoints` decimal, `runnerUpPoints` decimal|null (best score not among winners; null when everyone tied)
- `payers` int — rosters with a row that week minus winners
- `potCents` long — `payers × stake`

### `Balance` (per roster)
- `rosterId`, `managerId` (nullable), `name` (from `RosterOwners`), `avatarId` (nullable), `isMe`, `noManager`
- Every roster in `roster_season` for the row, zeros before any decided week. A roster with a score row (even 0.0) is a payer.
- `weeksWon` — decided weeks where the roster is a winner (a tied week counts as won)
- `collectedCents`, `paidCents`, `netCents = collected − paid`
- Invariant: Σ `netCents` over the season = 0.

### `PairDebt`
- `fromRosterId`, `toRosterId`, `cents` > 0 — the netted amount `from` owes `to`.
- At most one entry per unordered pair; none for a pair that nets to 0.

### `Payment`
- `fromRosterId`, `toRosterId`, `cents` > 0 — one line of the shortcut list.
- Invariant (both lists): applying every entry to the balances leaves every roster at 0.

### Validation rules
- Only weeks in `ScoredWeeks.finalWeeks ∩ regularSeasonBound` are counted (R2, R3).
- Tie shares: `stake / k` each, the first `stake % k` co-winners by ascending `rosterId` get +1 cent (R4).
