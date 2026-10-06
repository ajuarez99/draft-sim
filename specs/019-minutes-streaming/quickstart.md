# Quickstart: validating spec 019

Each check is recorded in `verification.md` as **run** (with output) or **not run** (and why).

Prerequisites: Postgres 5433 (docker `draftsim-pg`), a backend started **from this worktree**
(`cd backend && ./gradlew bootRun --args="--server.port=<free>"`). Confirm the java command line
contains `worktrees\019-minutes-streaming`, and after any restart confirm the listening PID is
the new process (spec 018: a stopped Gradle task left the old JVM serving). Vite from this
worktree with `DRAFTSIM_PROXY_TARGET`. Identity: `X-Sleeper-User: 1122386008709910528`
(popsharky, Allan).

## V1. Migration + roster players (US3)

Restart (Flyway applies V28), then trigger a refresh of NBA 2026
(`POST /api/leagues/1339351318115946496/refresh` needs the local refresh secret, or wait for a
visit refresh). Then:
`select roster_id, cardinality(players), players_fetched_at from roster_season where league_id = 210`.
Before the 10-10 draft, 12 rows with 0 players. After it, compare each roster's set with
`curl https://api.sleeper.app/v1/league/1339351318115946496/rosters` (players ∪ reserve ∪ taxi).

## V2. Unit tests

`./gradlew test --tests '*PlayerTrends*' --tests '*RosterSeason*'`. They must cover: the US1
riser/injury-game cases, `TEAM_*` excluded, usage from the team row (including overtime team
minutes), the form window needing 3, season fallback, list sizes/totals, the streaming exclusion
of rostered players, and the `ROSTERS_NOT_LOADED` / `NOT_DRAFTED` reasons. Report skipped = 0.

## V3. Endpoint, NBA 2025 (league `1229352720222134272`)

`curl -s -H "X-Sleeper-User: 1122386008709910528" localhost:<port>/api/leagues/1229352720222134272/player-trends`
- `rolesFallback: false`, `rolesSeason: 2025`, risers/fallers present, no `TEAM_` id (invariant 1).
- Jokić 1658: recentMin and seasonMin plausible. His 2025-10-23 game alone is 40.83 min (SC-001,
  checked through a unit test on the real row or a direct query).
- Streaming: `streamingReason: SEASON_COMPLETE`, `streaming: []`, every row `rostered: null` (amended
  after review F3/F4).
- No All-Star game in any row (F9). `excludedStale`/`excludedNoTeam` are reported (F2).
- Streaming sorted by `seasonPts` (F7).
- Time the request and report it.

## V4. Endpoint, NBA 2026 (pre-season fallback)

Same call for `1339351318115946496`: `rolesFallback`/`streamingFallback: true`, seasons 2025,
`currentWeek` present. `streamingReason: NOT_DRAFTED` while the status is `pre_draft`/`drafting` (F5).
`oneGameCredit` present (from 2025's stored weeks). Between 10-20 and ~10-29, check the per-list
cutover flips (F1) and record the dates. After the draft, streaming excludes every
drafted player (SC-003). That's a dated check: run it between 10-11 and 10-19, and record the
date.

## V5. Grid agreement (SC-004)

For 3 streaming rows, `gamesThisWeek` = `/schedule`'s cell for that team at `currentWeek`.

## V6. Football

NFL league: `available: false`, `NOT_BASKETBALL`. No Trends link in the rail.

## V7. UI

`/leagues/1339351318115946496/trends` and the 2025 league:
- the fallback label;
- the streaming table with the game-count note;
- the risers/fallers split rostered / free agent;
- labels on every number;
- 375px with no page scroll;
- console clean.

Take screenshots.

## V8. Regression + production

Full backend suite (skipped = 0), web tsc/vitest/build. After merge: both services redeployed,
and V4 re-run against production before 2026-10-20.
