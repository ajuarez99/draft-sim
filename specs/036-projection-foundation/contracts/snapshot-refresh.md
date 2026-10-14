# Contract: snapshot capture

## Daily refresh step

`POST /api/refresh/daily` (existing, `X-Refresh-Secret`, run by the 11:00 UTC GitHub cron)
gains one step per sport, after `SEASON_PROJECTIONS`:

```json
{ "sport": "NBA", "kind": "PROJECTION_SNAPSHOT", "outcome": "DONE",
  "detail": "weeks=[1] rowsWritten=936 rowsFrozenSkipped=0 rowsNotPreGame=0" }
```

Outcomes:
- `DONE`, including a sport with no current-season active league, whose detail is
  `"no current-season league needs projections"`.
- `DONE_PROJECTION_SNAPSHOT_FAILED_BEST_EFFORT`, when an upstream or DB error occurs. Its
  detail names the failure, and it never makes the run answer 500. Stored snapshots are
  untouched.

It writes no `daily_capture` row. Its idempotence comes from the guarded upsert, so a hand
re-run the same morning only refreshes open rows.

## Admin route

`POST /api/ingest/projection-snapshot?sport=nba|nfl` (the admin token, as every
`/api/ingest/**` route) runs the same `capture(sport, now)` and returns its `Result`:

```json
{ "sport": "nba", "season": 2026, "weeks": [1], "rowsWritten": 936,
  "rowsFrozenSkipped": 0, "rowsNotPreGame": 0 }
```

It can't write a post-cutoff line any more than the cron can. The guards live in the service
and the table, not in the route.
