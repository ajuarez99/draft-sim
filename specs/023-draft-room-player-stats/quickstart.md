# Quickstart: verifying player stats in the draft room

This project's bar for "verified" is driving the real thing. A passing suite is not enough
(AGENTS.md).

## Prerequisites

- Local Postgres on **5433** (`draftsim`/`draftsim`). Not the unrelated cluster on 5432.
- The NBA leagues ingested: "Ball Knowers" 2024 (`1141438340626231296`), 2025
  (`1229352720222134272`) and 2026 (`1339351318115946496`). If the 2025 box scores are missing,
  re-run 022's backfill per `specs/022-player-stat-analysis/quickstart.md`.
- The backend restarted from **this worktree** (`bootRun` doesn't hot-reload). Check the
  classpath, because a preview started from a worktree can serve main's launch config (memory:
  "Worktree preview serves main").

```bash
cd backend && ./gradlew test
```

```bash
cd web && npx tsc -b && npx vitest run && npm run build
```

Check the backend suite's **skip count**, not just "BUILD SUCCESSFUL". The ITs skip silently when
Postgres is down.

## V1. Wire fields (C1, C2)

```bash
curl -s -H "X-Sleeper-User: <member id>" localhost:8080/api/drafts/1339351318128517120/seats
```

Expect `sleeperLeagueId` = `1339351318115946496`.

```bash
curl -s -H "X-Sleeper-User: <member id>" "localhost:8080/api/leagues/1339351318115946496/stats?window=SEASON"
```

Expect `season` 2025, `requestedSeason` 2026, `scoringSeason` 2025 and
`scoringMatchesRequested` **true** (the scoring is identical, research R2).

## V2. US1 in the live room (the 2026 draft, pre-draft)

1. Open `/drafts/1339351318128517120/live` and switch the sheet to **Stats**.
2. The header reads 2025–26, says it's last season's play and not a projection, and names the
   scoring season.
3. Compare **10 players'** values against `/leagues/1339351318115946496/stats` for the same
   window and mode. Expect 10/10 identical (SC-002).
4. The table's row count equals the tier list's player count. Players with no 2025 games read
   "No NBA games in 2025–26" and sort last both ways (SC-005).
5. Resize to 375 px. The name column stays pinned, the table scrolls sideways, and the page
   doesn't (SC-007).
6. Click a name. The player page opens in a new tab, and the room tab is still connected.

## V3. Picks landing (SC-003, SC-006)

The 2026 draft has no picks until 10-10, so use a **replay**. Fork the completed 2025 NBA draft
into a mock with "Continue as a mock", or drive a mock with the 2026 league as its source, and
make picks.

- Each pick removes the player from the table in the same render as the board, 20 of 20 picks.
  The sort, filters and scroll are kept.
- Measure pick-to-board-update time with the stats view closed, then open, over the same 20
  picks. Expect within 10%.
- Time the first leaderboard request (network panel) and record the measured number in
  verification.md.

On 10-10 itself, repeat the checks from the room during the real draft only if it's safe to
watch. The draft is the priority, not the check.

## V4. US2 (the stat picker modal)

1. Open the picker. It shows Basic, Shooting, Advanced and Fantasy with no duplicates, and
   **USG** is present.
2. Choose FP/G, MIN, USG and TS% in that order, and sort by USG. Check the order against the
   leaderboard's Advanced group.
3. With the modal open, make a pick in the replay. The modal stays open, toggles are kept, and
   the table behind it updates.
4. Reload, then open a different basketball room. The same four columns appear in the same order.
5. Turn every column off. Names remain, with "No stats chosen" and a reset. Reset restores the
   defaults.
6. In devtools, put `"columns":["gp","bogus"]` into `bk.draftStats.v1` and reload. GP shows, and
   nothing errors.

## V5. US3 (mock rooms)

- A basketball mock started from the "Ball Knowers" league has the Stats view and the same
  stored choice, and no next-pick filter, with a reason given.
- A mock with no source league has the Stats switch disabled, with its reason.
- A football room has no Stats switch at all (FR-017).

## V6. Split-deploy check

Point the dev frontend at a backend built from `022-player-stat-analysis`, which has no new
fields. The room loads and the Stats switch says it isn't available yet. Nothing throws.
