# Quickstart: validating automatic data refresh

This is the live-verification guide, not implementation. Shapes are in
[contracts/refresh-api.md](contracts/refresh-api.md) and tables in [data-model.md](data-model.md).
The bar is AGENTS.md's: a green suite isn't "verified". Record in `verification.md` which checks
ran and which didn't.

## Prerequisites

- Postgres on 5433 (`docker compose up -d postgres`). Docker Desktop is at
  `~/AppData/Local/Programs/DockerDesktop/`.
- Backend and web through `.claude/launch.json` (`draft-sim-api`, `draft-sim-web`).
- Reference leagues:
  - NBA 2025: `1229352720222134272` (complete)
  - NFL 2025: `1254190892974084096` (complete)
  - NFL 2026: `1346366555759341568` (in season)

## 1. Before the change (capture first; these are lost once the ingest is replaced)

1. Run the current per-player walk (`POST /api/ingest/player-games/{id}`) for NBA 2025 and NFL
   2025. Export `player_game` and `player_absence` for both seasons, restricted to players rostered
   in those leagues, to CSV in the scratchpad.
2. Save each league's Superlatives payload. The Embiid award must later match: Jokić 16, Embiid 26
   (spec 008).
3. **Measure** the costs research R7 and R13 left open: `POST /api/ingest/adp?sport=…` and
   `POST /api/ingest/board?sport=…`, wall time and backend CPU, using the same method as the spec's
   Budget (process CPU before and after).

## 2. Per-game parity (research R6's acceptance gate)

1. Rebuild NBA 2025 and NFL 2025 with the new per-week ingest into an empty
   `player_game`/`player_absence`, so no rows are left over from the old walk.
2. Diff against step 1's exports, rostered players only: **zero differences in both tables**,
   `is_away` included. Any difference gets reported and decided, not tuned away.
3. Superlatives: Embiid Jokić 16 and Embiid 26 unchanged; every other kind byte-identical to
   step 1.2.
4. Record the Sleeper call count and wall time for a full season rebuild. Expect about 23 calls
   per sport-season versus 331 before.
5. **SC-004**: re-run the refresh on the now-current NBA 2025. Every week is `final`, so it
   fetches nothing and takes well under 10.1 s (10% of 101 s).
6. Record `player_game` row counts and table size per season. Research R6 estimated about 26 MB
   for NBA; report the real figure.

## 3. Refresh on visit

1. **Stale**:
   - Set NBA 2025's `league_refresh` row to `loaded_complete = false`, with `last_success_at`
     2 hours ago.
   - Delete one week of its transactions.
   - Open `/leagues/1229352720222134272/superlatives`. The rail shows "Updating…", then "Updated
     just now", and the page refetches by itself.
   - The Jabari Smith Jr. Award is back to LaRavia and Sensabaugh at 14, and the row is
     `loaded_complete = true`.
2. **Fresh**: reopen within the hour. The network log shows a POST `/refresh` returning `FRESH`,
   and no Sleeper traffic (backend logs).
3. **Single-flight**: open the same stale league in two tabs at once. The backend logs show one
   refresh.
4. **Failure**:
   - Point `sleeper.base-url` at an unreachable host and restart.
   - Open a stale league. The rail shows "Couldn't reach Sleeper — data from …", the stored
     figures stay on the page, and `last_failure_at` is set.
   - Reload within 10 minutes: no new attempt.
5. **Complete**: open NBA 2025 again after step 3.1. The response is `COMPLETE`, and no fetch
   happens.
6. **Resolver case**: open NFL 2026's id before week 1 (or simulate it). The season the page shows
   is the one refreshed.
7. **SC-003**: time from opening a stale NFL 2026 page to the refreshed figures appearing.
   Target: under 30 s.

## 4. New league loads completely (US4)

On a database with the reference league removed (or a fresh one):
1. Add it through the setup flow.
2. After setup, every chain season becomes `loaded_complete` or `FRESH` with no page visit.
   Check `league_refresh`.
3. Its past season's Superlatives match step 1.2 without any manual ingest.

## 5. The daily endpoint

1. With no `REFRESH_SECRET`: `POST /api/refresh/daily` returns 404.
2. With it set:
   - A wrong secret returns 401.
   - The right one returns 200 with a `DONE` step per sport and kind.
   - `daily_capture` gets today's rows, and suspension captures are written (spec 008's
     `status_capture`).
   - A second call the same day shows `PLAYERS` as `SKIPPED_ALREADY_TODAY`, and no player-list
     fetch appears in the logs.
3. **With `API_TOKEN` set**: `/api/refresh/daily` still works with only `X-Refresh-Secret` (exempt),
   and every other route still requires the bearer token.
4. **Make ADP fail** (block FFC): the step is `FAILED`, the others still run, and the response is
   500.

## 6. The workflow, against production (after deploy)

1. Set `REFRESH_SECRET` in Railway (backend service) and in GitHub repository secrets.
2. Wait until the backend has slept (10+ minutes with no traffic), then run the workflow with
   `workflow_dispatch`.
   - The job log shows the retries through the cold start, then success.
   - Record how long the first success took, which is research R7's unmeasured cold-start time.
3. Check that the backend sleeps again afterwards (Railway service activity), for SC-007.
4. The next morning, the scheduled run appears in the Actions history at about 11:00 UTC.

## 7. No developer text left (SC-002)

- `NoIngestHintsInMessagesTest` passes.
- In the browser, open every league page for a league with no per-game records and no
  transactions. Nothing mentions `/api/` or "ingest". Record what each page says instead.

## 8. Production, the motivating case (SC-001)

After deploy, open `https://www.ballknowers.co/leagues/1229352720222134272/superlatives` and let
it refresh. The Jabari Smith Jr. Award shows LaRavia and Sensabaugh at 14, and the Waiver Wire
Warrior shows 1,587.5 for KATastrophe Krew. Check it through the API too:
`curl https://api.ballknowers.co/api/leagues/1229352720222134272/superlatives`.

## 9. After a month (SC-005, SC-006)

- Railway usage page: the month's compute is within $5, and the increase over the ~$1.20 pace is
  under $1.
- Actions history: at least 29 of 30 scheduled runs green.

## 10. Suites

- `cd backend && ./gradlew cleanTest test`. Read the skip count, which must be 0 with Postgres up
  (memory: the backend suite skips integration tests silently).
- `cd web && npx tsc -b && npm run build && npx vitest run`.
