# Data model: spec 045

No tables change. No migration. Everything below is an in-memory or response shape.

## Resolver rule (`LeagueSeasonResolver.Rule`)

| Value | A season qualifies when |
|---|---|
| `PLAYED_WEEKS` (existing) | it has ≥ 1 stored `roster_week_points` week |
| `STORED_GAMES` (existing) | its sport-season has stored `player_game` rows (`SeasonBoxCache.token(..).count() > 0`) |
| `LIVE_WEEK` (new) | `PLAYED_WEEKS`, **or** all of: not complete, `status = 'in_season'`, stored `settings_json.leg` ≥ 1, `STORED_GAMES` |

Every caller names one. There is no default (FR-006).

## LiveWeekSource.Live (new, engine)

| Field | Type | Notes |
|---|---|---|
| `week` | int | Sleeper's `settings.leg` at read time, ≥ 1 |
| `status` | `OK` \| `FAILED` | `FAILED` means the Sleeper read threw. `rows` is then empty. |
| `rows` | list of `(int rosterId, Integer matchupId, double points)` | `points` is Sleeper's `points`. `matchupId` is null for an unpaired roster. |
| `at` | Instant | read time. OK and empty results are cached for 60 s per Sleeper league id, `FAILED` for 044's `liveFailureTtlSeconds` (amended after plan review A4). |

It's empty (`Optional.empty()`) for an ESPN league, a complete league, or `leg < 1`.

States, per league, over a season: offseason (empty) → in season, week L live (`OK`, L) → week L stored
by ingest (the page switches to the stored path for L automatically, R3) → … → complete (empty).

## WeeklyReportService.Result: added fields

| Field | Type | Meaning |
|---|---|---|
| `live` | boolean | this response's scores were read live from Sleeper (an unstored, in-progress week) |
| `liveWeek` | Integer, nullable | the unstored live week, when one exists, on every shape. Null when none. **Amended after code review B2 (2026-10-23):** it also carries the REFUSED live week on the two refusal shapes ("live scores unavailable..." and "week L hasn't started"), so the page's upper bound includes it and an explicit request for that week shows the reason instead of being clamped to the week before. |
| `sleeperLeagueId` | String, nullable | the id of the season actually shown. It differs from the route id when the season fell back. Null only on the `unavailable` shape built before a league is resolved (never today). |

Invariants:
- `live ⇒ weekFinal = false`, and every ranking list and `awards` is empty.
- `live ⇒ requestedSeason = null` (the season shown is the one asked for).
- `liveWeek != null ⇒ liveWeek` is not a stored week.

## LockInService.LockInReport: added field

| Field | Type | Meaning |
|---|---|---|
| `weekCovered` | Boolean, nullable | only for `?week=N`: true when the status is MEASURED and N is one of the computed (final, scorable) weeks, so an empty `locks` means "no locks", not "can't tell". **Omitted** (`NON_NULL`) on every other form (amended after plan review F7/F8). |
