# Contract: data refresh

**Feature**: `009-auto-data-refresh` | **Date**: 2026-09-28

Java records and `web/src/api.ts` mirror each other field-for-field, in the same change
(AGENTS.md). Response maps are built mutably, since several fields are nullable (AGENTS.md
`Map.of` rule).

---

## `POST /api/leagues/{sleeperId}/refresh` — refresh on visit

Called by the rail once when a league page becomes the current league (research R1). Membership
is checked like every league endpoint: a league the caller can't see gets **404**.

**Behaviour:**
- Resolve the chain seasons to refresh (research R2).
- For each one: if it's stale (data-model `league_refresh`) and no refresh is already running for
  it, start one in the background.
- Return at once, without waiting for any refresh to finish (FR-001).
- **Retry after a failure** waits until `lastFailureAt` is at least **10 minutes** old (hand-set,
  arbitrary). A Sleeper outage then costs one attempt per league every 10 minutes, not one per page
  view.

**200 body:**

```json
{
  "state": "RUNNING",
  "leagueSleeperId": "1229352720222134272",
  "season": 2025,
  "lastSuccessAt": "2026-09-28T15:02:11Z",
  "lastFailureAt": null,
  "seasons": [
    { "leagueSleeperId": "1229352720222134272", "season": 2025, "state": "RUNNING" }
  ]
}
```

| Field | Rule |
|---|---|
| `state` | `RUNNING` whenever the chain's refresh is in flight; otherwise the displayed season's state: `FRESH`, `FAILED` or `COMPLETE`. `FAILED` means the last attempt failed and no newer success exists. *(Amended after code review, 2026-09-28: previously a `loaded_complete` displayed season reported `COMPLETE` even while a newer season of the chain refreshed, so the rail never saw `RUNNING`.)* |
| `leagueSleeperId`, `season` | the league-season the page shows (after the resolver walks back), not necessarily the URL's |
| `lastSuccessAt`, `lastFailureAt` | nullable ISO-8601. `lastSuccessAt` is the newest success across the chain's seasons; `lastFailureAt` is the newest failure across the chain, and only when it is newer than that success. *(Amended after code review, 2026-09-28: these were the displayed season's own timestamps, which never move while a newer season refreshes.)* |
| `seasons[]` | every chain season, including completed ones, with its state. The page shows "still loading" for a past season from this (US4 scenario 2) |

**When refresh on visit is off** (`refresh.on-visit.enabled=false`, research R12): 200 with the
stored state, and nothing started.

## `GET /api/leagues/{sleeperId}/refresh`

Same body. It never starts anything. The rail polls it every **3 seconds** while `state` is
`RUNNING`, then stops. On the transition to `FRESH` or `COMPLETE` it bumps the league's
`dataVersion`.

## `POST /api/refresh/daily` — the scheduled job's only call

**Headers:** `X-Refresh-Secret: <REFRESH_SECRET>`. Exempt from `ApiTokenFilter` (research R8).

| Status | When |
|---|---|
| 200 | ran; body below |
| 401 | secret missing or wrong (constant-time compare) |
| 404 | the backend has no `REFRESH_SECRET` configured, so the route doesn't exist (local default) |

**It runs synchronously**, so the job's exit status reflects the result. For each sport, in order:
1. `PLAYERS`, skipped if today's (UTC) `daily_capture` row exists;
2. `BOARD`, which is FFC ADP, then the board rebuild, then fitted profiles, all through
   `BoardRefresh.run`, the same method as `POST /api/ingest/board` (research R15).

*(Amended after analysis: ADP is no longer its own step.)*

A failure in one step is recorded and the next still runs. An FFC ADP failure inside `BOARD` is
`FAILED_BEST_EFFORT` in `detail` and doesn't count as a failure (spec amendment 8). The status is
200 only if every non-skipped step succeeded, and **500** otherwise, so GitHub marks the run
failed and emails Allan (research R7).

```json
{
  "date": "2026-09-29",
  "steps": [
    { "sport": "nba", "kind": "PLAYERS", "outcome": "DONE", "detail": "playersWritten=2091 suspendedCount=0" },
    { "sport": "nba", "kind": "ADP", "outcome": "DONE", "detail": "…" },
    { "sport": "nba", "kind": "BOARD", "outcome": "DONE", "detail": "…" },
    { "sport": "nfl", "kind": "PLAYERS", "outcome": "SKIPPED_ALREADY_TODAY", "detail": null }
  ]
}
```

`outcome` is one of `DONE`, `DONE_ADP_FAILED_BEST_EFFORT`, `SKIPPED_ALREADY_TODAY` or `FAILED`.

## `POST /api/refresh/players?sport=nba|nfl` — the setup flow's gated player list

*Added after analysis (G1).* Setup calls this instead of `/api/ingest/players`. It runs the daily
job's `PLAYERS` step for that sport: it's skipped if today's `daily_capture` row exists, and
records one if it runs. The body is `{ "outcome": "DONE" | "SKIPPED_ALREADY_TODAY" | "FAILED",
"detail": … }`.
- No secret is needed: its worst case is one player-list fetch per sport per day.
- `/api/ingest/players` itself is unchanged (FR-015).

## GitHub workflow: `.github/workflows/daily-refresh.yml`

- **Triggers**: `schedule: cron "0 11 * * *"` and `workflow_dispatch`, for a manual run.
- **Secret**: `REFRESH_SECRET`, a repository secret.
- **Step**: one `curl -fsS -X POST … -H "X-Refresh-Secret: …" --retry 30 --retry-all-errors
  --retry-delay 30 --retry-max-time 900 --max-time 180`. It fails the job on a final non-2xx.
  The job has `timeout-minutes: 20`. *(Amended after analysis: production's cold start measured
  at about 4.7 min, research R7.)*
  - *(Amended at implementation, 2026-09-28)*: not `curl --retry`. curl counts 500 as retryable,
    and `--retry-all-errors` counts everything, so a real step failure (500) would re-run the
    whole daily job up to 30 times, and `-f` also hid the error body.
  - The step is now a shell loop: up to 30 attempts, 30 s apart, retrying **only** on
    `000/502/503/504` (no connection or a cold start).
  - It stops on any other status, prints the response body, and fails the job unless the final
    status is 200.
- **No other steps.** The repo is public, so the workflow file must contain nothing sensitive.

## Web: the league data version

- A React context exposes `useLeagueDataVersion(sleeperLeagueId): number`. It starts at 0, and the
  rail increments it when a refresh for that league completes (research R1).
- Each league page adds the value to its data-fetch effect's dependency list. Nothing else
  changes on the pages.
- **The rail's indicator** sits under the league name:

| State | Shows |
|---|---|
| `RUNNING` | "Updating…" |
| `FRESH` / `COMPLETE` | "Updated 12 min ago" (relative) |
| `FAILED` | "Couldn't reach Sleeper — data from 3 h ago" |

## Replaced message strings (research R10)

Each backend string that names an endpoint changes to plain wording. The guard test
`NoIngestHintsInMessagesTest` fails the build if `/api/ingest` or `POST /api/` appears in a string
literal under `engine/` or `api/`.

The backend strings state only what's missing. They never claim a refresh is running, because
the service computing them doesn't know whether one is: it might be off, failed or finished. The
"updating" state is shown by the rail, from the refresh endpoint, which does know.

| Where | New wording |
|---|---|
| Superlatives, transactions missing | "Transactions for this season haven't loaded yet." |
| Superlatives, per-game records missing | "Game-by-game records for this season haven't loaded yet." |
| League analysis / Power rankings, no weeks yet | "No week of this season has loaded yet." |
| League analysis, no projections | "Projections aren't available for weeks N–M." (a fact, not a loading state) |
| Draft pages, player missing | "This player isn't in the player list yet. It refreshes daily." |
