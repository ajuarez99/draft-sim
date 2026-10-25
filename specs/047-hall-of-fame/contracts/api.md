# Contracts: spec 047

The shapes are in [data-model.md](../data-model.md). All responses are JSON. Nullable fields are
emitted as `null`, never omitted.

## C1. `GET /api/leagues/{sleeperId}/trophy-case` (new)

> **Amended after review (F1, F5, F7):**
> - The chain is `chainBySleeperId(sleeperId)`, which runs from this season **backward**, never
>   forward. The rail links the newest season.
> - `lastPlaceNote` is removed. Each row carries `lastPlace` and `lastPlaceReason` instead.
> - `runnerUpUnavailable` becomes `runnerUpReason`.
>
> See plan.md A1/A5/A6.

- **200** with `TrophyCase`.
  - (superseded) "any season's id gives the same chain."
  - `status: "NO_COMPLETED_SEASON"` comes with `seasons: []`.
- **404** when the league isn't stored, or is out of the caller's scope (same as `/rivalries`).
- (Amended after code review N1.) There is no `lastPlaceNote`. Each row carries `lastPlace` (a manager or
  null) and `lastPlaceReason` (non-null exactly when `lastPlace` is null), plus `lastPlaceBracket`.

## C2. `GET /api/leagues/{sleeperId}/rivalries` (existing, one field added)

- Adds `discovery: RivalryDiscovery`. It's always present.
- Every other field must be byte-identical to main (before/after capture).

## C3. `GET /api/managers/{managerId}/rivals` (new)

- **200** with `ManagerRivals`. `chains` is in the same order as the manager page's careers (sport,
  then newest season).
- Covers only chains visible to the caller, under the same scope `/managers/{id}/history` applies.
- **404** for an unknown manager.

## C4. `GET /api/leagues/{sleeperId}/history`, `GET /api/managers/{id}/history` (existing, unchanged)

- `champion` and `titles` must be identical before and after the `ChampionRule` extraction.
