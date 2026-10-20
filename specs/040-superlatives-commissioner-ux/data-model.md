# Data model: spec 040

## Stored

### `league_conduct_entry` (V22) — one column added in V38

| column | type | change |
|---|---|---|
| `applies_through_week` | `int null` | **new**. Null = rest of season. `check (applies_through_week is null or applies_through_week >= applies_from_week)` |

Everything else unchanged: one row per (league row, player) (`unique (league_id, sleeper_player_id)`), reason
1–140 chars, `applies_from_week >= 1`, `added_by_manager_id` never rewritten on edit.

Migration: `V38__conduct_entry_through_week.sql`. Append-only; V37 is the highest on every branch.

## Computed (no storage)

### AbsenceCost (pure, replaces the per-game rule)

- `PlayerCost(playerId, weeksMissed, gamesMissed, pointsPerGame, estimatedPointsLost)`
  - `weeksMissed` — counted weeks per research R3 (zero games played, ≥1 non-UNCLASSIFIED absence, rostered).
  - `gamesMissed` — context only: absence rows inside those counted weeks. Never multiplied.
  - `pointsPerGame` — unchanged input and meaning (amended 2026-10-20, R2).
  - `estimatedPointsLost = round2(weeksMissed × pointsPerGame)`.
- `compute(...)` signature unchanged; only the counting changes (zero-play weeks, R3).
- `weeksAffected` is removed: under the new rule it equals `weeksMissed`.

### `AbsenceDetail` (wire, `DetailRow`) — changed

`playerId, playerName, position, rosterId, weeksMissed, gamesMissed, pointsPerGame, estimatedPointsLost, estimated`
(was `gamesMissed, weeksAffected, pointsPerGame, …`). `web/src/api.ts` mirrors it in the same change.

### `PlayerContribution` (wire, new) — on `Superlative.playerContributions`

`rank:int, playerId, playerName, position:nullable, team:Holder (wire: rosterId, managerId, teamName, username, avatarId), value:double, note:nullable string`

- JOEL_EMBIID: value = estimatedPointsLost; note = "N weeks missed · X pts/game".
- WAIVER_WIRE_WARRIOR: value = that pickup's started points for that team; note = "added wk N (waiver/free agent)".
- UNETHICAL: value = distinct flagged weeks for that team (R7); note = sources + week span.
- Ranked descending, competition ranks (1, 1, 3), ties broken by player name for stable order.
- Invariant (SC-004): for each team, Σ value over its rows = that team's `standings` value (within 0.01).
- `[]` for every other kind.

### Conduct list response — extended

Entry gains `appliesThroughWeek:int|null`, `charges: [{team:Holder, weeks:int[]}]`, `playerWeeks:int`,
`position`, `nflOrNbaTeam` (named `proTeam`). List gains `season:int`, `candidates: [{playerId, playerName,
position, proTeam, fantasyTeam: Holder|null}]`.

`charges` only cover weeks already scored for that league-season (same window as the award); an entry with none
gets `playerWeeks: 0` and the UI says "not rostered in these weeks yet".

## State transitions

Conduct entry: *absent* → (POST) → *listed* → (PUT) → *listed (edited)* → (DELETE, confirmed in UI) → *absent*.
Only the commissioner (`LeagueMembership.canCommission`) performs any transition; unchanged.
