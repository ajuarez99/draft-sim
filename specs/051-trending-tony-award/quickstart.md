# Quickstart / verification guide: The Trending Tony Award (spec 051)

Shape of evidence: what's measured against real Sleeper data vs. what's driven by seeded snapshots must be
labelled separately in `verification.md`. There is **no real trending history** anywhere before this ships,
so the winner path can only be exercised locally with seeded snapshots — say so in those words.

## Prerequisites

- Postgres on `localhost:5433` (trust auth, db/user `draftsim`). Never touch the unrelated 5432 cluster.
- Backend from **this worktree** (memory: `preview_start` from a worktree may run main's launch config —
  check the bootRun working directory before trusting a live check). Use a free port (8081–8084 entries).
- Both real leagues ingested (`POST /api/ingest/all/{id}`): "(Foot) Ball Knowers" NFL 2026 and
  "Ball Knowers" NBA 2026, so `league_transaction` has real pickups with `created_at`.

## V1 — Snapshot is written by a real fetch (real Sleeper, real DB)

1. `POST /api/leagues/{nbaLeagueId}/refresh` (or the daily route with the secret).
2. `select count(*), max(fetched_at) from sport_trending_snapshot where sport='nba';` → ≥1 row whose
   `fetched_at` equals `sport_trending_fetch.fetched_at`.
3. `select count(*) from sport_trending_snapshot_entry where snapshot_id = <that id>;` = rows in
   `sport_trending` for nba (25 or fewer).
4. Home page trending section unchanged (same players, same order) — FR-001.

## V2 — Real league, real history: coverage honesty (real data)

With only V1's snapshot(s) on file, open Awards for NBA 2026:
- The Trending Tony card is present, last.
- Either it's available with `judgingSinceAt` = today and a large `notJudged` (every pickup more than ~24h
  from the snapshot), or unavailable with "No pickups since trending history began on …".
- `judged + notJudged == totalPickups` (SC-002), and `totalPickups` equals the hand count via SQL of completed
  WAIVER/FREE_AGENT adds with `1 <= week <= throughWeek`, **excluding football DEF** (amended after review, F9).
- Record whether the live NBA week's pickups are inside `throughWeek` (F9: inferred not checked — measure it).

## V3 — Past season unavailable (real data)

Awards for NFL 2025 or NBA 2025 → Trending Tony unavailable with the history reason; never a winner, never
"nobody qualified" (SC-003).

## V4 — Winner path (SEEDED snapshots, labelled as such)

Include a seeded snapshot naming a player a manager got by **trade** — it must not count (F1).

Insert snapshots into `sport_trending_snapshot(_entry)` at the `created_at` of chosen real NBA 2026 pickups
so that one manager gets ≥2 trending pickups and another 1. Expect: that manager named, value = hand count,
"See all" → Teams shows every roster with `N of M judged pickups`, Players shows each counted pickup with
`#rank`. **Delete the seeded rows afterwards** (`delete from sport_trending_snapshot where id in (...)`).

## V5 — No other award moved (SC-004)

Capture `GET /api/leagues/{id}/superlatives` for NFL 2026 and NBA 2026 on main and on this branch; strip
`TRENDING_TONY` and every `trendingCoverage` key; the two JSON documents must be identical.

## V6 — ESPN league (if one is ingested locally)

Trending Tony unavailable with the ESPN reason.

## V7 — UI in a real browser

Players view reads "#N trending", Teams view "N trending pickups" (F6); Hall of Fame trophy case shows no
Trending Tony row for past seasons (F8); Home award block shows the PICKUPS unit (F7). Card copy, "How this works" text (FR-011), Sleeper credit (FR-012), See-all Teams/Players toggle, phone
width (375px) with no horizontal scroll. Screenshot for the verification record.

## Automated

```bash
cd backend && ./gradlew test --tests '*TrendingPickups*' --tests '*SportTrendingRepositoryIT*' --tests '*Superlatives*'
```

```bash
cd web && npx tsc -b && npx vitest run src/components/AwardWinners.test.tsx src/components/SuperlativeStandings.test.tsx src/pages/Superlatives.test.tsx
```

Check the skipped-test count: ITs skip silently when Postgres is down (memory: backend suite skips ITs).
