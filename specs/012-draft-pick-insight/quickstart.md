# Quickstart: verifying live draft pick insight

A green suite is not this project's bar for "verified" (AGENTS.md). Q1–Q3 are the
automated floor; Q4–Q7 need a real draft, and only they count toward the success
criteria.

## Prerequisites

- Postgres on `localhost:5433` (trust auth, db/user `draftsim`). Without it, the
  backend ITs **skip silently** and the suite still says BUILD SUCCESSFUL.
- Backend restarted after any Java change: `bootRun` does not hot-reload. Check
  whether 8080 already has a stale process.
- A browser tab hard-refreshed (Ctrl+Shift+R) after any restart of the vite dev
  server.
- The worktree's own servers. `preview_start` from a worktree runs the **main**
  checkout's `launch.json` (memory: worktree preview serves main). Confirm the
  `bootRun` classpath points at this worktree before trusting a live check.

## Q1 — backend

```bash
cd backend && ./gradlew test
```

Expected: the new `/pool` tests (see [contracts/pool-endpoint.md](contracts/pool-endpoint.md)) and the
`SNAPSHOT_DEPTH` pin pass. **Read the skipped count.** Any skipped IT means
Postgres was not reachable, and the run proves nothing about the endpoint.
`RefreshControllerIT` fails on the base branch too (memory, spec 011), so note it
rather than chasing it.

## Q2 — frontend

```bash
cd web && npx tsc -b && npx vitest run && npm run build
```

Expected: the tests for the pure derivations (`pickInsight`, `scarcity`,
`onBrand`) pass. That includes the **ordering** tests: a reach reads as "before
ADP", not after; a rising RB share reads as on brand for an RB lean, not off.

## Q3 — endpoint by hand

```bash
curl -s -D - "localhost:8080/api/drafts/<draftId>/pool?limit=5" -H "X-Sleeper-User: <id>"
```

Expected: 200 with a real `application/json` body (lessons.md class 4: check the
header against the body), 5 `PlayerRef`s in board order, and `team: null` where
it applies. The same call with an identity outside the league returns 404.

## Q4 — per-pick re-projection cost and 429 rate (measure; do not assume)

1. With the live room open on a drafting draft, note the wall-clock time from a
   pick landing in the feed to the card's "Likely next" filling in. Take ≥ 10
   picks and record min / median / max.
2. Open the same live room in **12 tabs** as 12 identities, or script 12
   concurrent `POST /api/sims/stream` calls with distinct `X-Sleeper-User`
   headers and the same draft. Count 429s per pick.
3. Record both numbers in `verification.md`, labelled as **measured**, with the
   machine they came from. Local is not Railway.

**Decision gate.** If the median exceeds the typical gap between picks, or most
tabs see "busy", stop and raise R1-alt (a shared server-side projection) with the
numbers. Do not lower `RESIM_ITERATIONS` or retune `SimulationPermits` to make
the numbers fit (AGENTS.md: never retune a constant to match a guess).

## Q5 — a real draft, end to end (SC-001, SC-002, SC-004)

Use the 2026-09-02 recipe: create a disposable 4-team Sleeper mock draft,
ingest its league, track it, and open `/live/<draftId>`. Make picks on Sleeper
for every seat, including a burst of 3 or more within 10 seconds.

Check at every pick:

- The card appears within ~1s of the feed row (SC-002). Its "Fills" slot and
  "Still needs" list match that seat's column on the board (SC-001).
- During the burst, exactly one card is open at a time (SC-004).
- When your seat goes on the clock, the card closes and none opens. Clicking the
  newest feed row opens it.
- "Likely next" goes from updating to ready, or to busy. It never shows a
  candidate who was already drafted.
- For one pick, record the pre-pick projection's share for the picked player
  (network tab → the previous `simulate` response → the board cell at that
  `pickNo`). Compare it with the card's "Model had this at N%" (SC-003).

## Q6 — meters (SC-005)

At three points (early, around pick 46+ where the projected column appears, and
late):

- Count by hand: from `/pool?limit=S`, the players at one position not in the
  landed list. It must equal the meter's "left now".
- Before the R7 gate, confirm the projected column is absent and shows the "from
  pick ~N" line.
- On-brand: pick one manager. Compute the mean of (ADP − pickNo) over their picks
  by hand and compare it with the room read. Confirm a neutral seat shows no
  verdict and a stated seat shows no lean.

## Q7 — phone width, both sports, preference (SC-006, SC-007)

- `resize_window` preset mobile (375px): no horizontal scroll, and the card does
  not cover the availability sheet. Reset to desktop afterwards.
- A basketball draft, or a completed NBA draft opened for the meters only
  (completed drafts raise no cards): positions are PG/SG/SF/PF/C, and G/F/UTIL
  fits read the same as the team strip.
- Turn "Pick cards" off and make a pick: no card, and the meters still update.
  Reload: still off.

Record everything, pass or fail, in `specs/012-draft-pick-insight/verification.md`,
and keep **measured** and **assumed** apart.
