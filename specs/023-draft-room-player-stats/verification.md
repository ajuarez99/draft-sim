# Verification: Player stats in the draft room

Every number here was measured by running the thing named. Anything not yet run is marked
**not yet run**.

## Baseline (T001, 2026-10-09, worktree `draft-sim-023` at 555592e, before any 023 change)

- **Backend**: `./gradlew test` passed: **1515 tests, 0 skipped, 0 failures, 0 errors**, in
  2m 49s. Postgres on 5433 was up, so the ITs actually ran.
- **Frontend**: `npx tsc -b` is clean. `npx vitest run`: **91 files, 1228 tests, all passed**.

## Phase 2 (T002–T007)

Built by a Sonnet agent, and the diff was read by the parent session.

- **Targeted backend run**: `PlayerStatsLeaderboardReadIT`, `LeagueControllerSeats*` and
  `PlayerStats*Test` passed: **39 tests, 0 skipped, 0 failures**. It includes the three new C2
  cases:
  - (a) no fallback means a null match flag;
  - (b) a fallback with identical scoring gives `true`;
  - (c) one differing key gives `false`.
- **Test side effect, checked**: cases (b) and (c) briefly rewrite the local 2026 NBA league's
  `scoring_json` and restore it in `finally`. The column is `jsonb`, so the text round trip is
  lossless. `md5(scoring_json::text)` for league 210 was `1daad45a9f4287d75eb968893b645565`
  before and after the run.
- **Frontend**: `tsc -b` is clean. `StatLeaderboard.test.tsx` passed **unchanged** after the
  `statCells.tsx` move, along with `AvailabilityPanel.test.tsx` (32 tests).
- **Wire check (quickstart V1) against a running server**: **not yet run**. It's done in T017.

## Quickstart V1: wire fields (2026-10-09, backend from this worktree on :8093)

Called as a member of the 2026 league (the popsharky account):

- `GET /api/drafts/1339351318128517120/seats` returned `sleeperLeagueId: "1339351318115946496"`,
  with `sport: nba`, `mySlot: 5`, 12 teams, 14 rounds and status `pre_draft`. ✅
- `GET /api/leagues/1339351318115946496/stats?window=SEASON` returned `season 2025`,
  `requestedSeason 2026`, `scoringSeason 2025`, `scoringMatchesRequested true`, `available true`
  and **582 rows**. ✅
- **Measured cost of the room's one stats request**: the first call took **1.24 s** (cold JVM and
  cache), then **0.21 s and 0.20 s**. The body is **1,464,303 B** raw and **327,334 B** gzipped
  (0.24 s). It's fetched once per window and never per pick, so the size is paid once.

## US1: live room (T017, 2026-10-09, backend :8093 + vite :5193 from this worktree, real browser pane)

Identity: the popsharky member id, set in `bk.user.v1`. That's identity only, with no password.

### Findings that changed the build (each one was fixed, then re-checked)

1. **The pool was wrong (found reading the diff, before the browser check).** The first build used
   the tier list's rows, which are capped at 60 and filtered to players with survival above 1%. On
   top of that, the live room's `availability` only holds players the simulation's snapshots
   surfaced (`MonteCarloRunner.java:200-221`), so it isn't the undrafted pool at all. Fixed: an
   NBA-only `GET /drafts/{id}/pool?limit=400` (the server's cap) feeds the stats view. Survival is
   looked up by player id, and an untracked player shows none.
   **Spec amendment (FR-005)**: "every undrafted player" means every undrafted player in the
   board's top 400, which is the existing pool cap. The 2026 draft has 168 picks.
2. **The available-players sheet was unusable on a phone, and this was already broken before
   023.** At 375 px, `.board-stage` measures 0 px tall, so the absolute sheet's `max-height: 46%`
   resolved to 0. Its content spilled out below and the board painted over it. `elementFromPoint`
   on a tier row hit a board cell (`.rnd` "R2"). The same was measured on the 022 server (:5192).
   `main` has the same sheet CSS (`git diff main 022` touches neither file), so production has it
   too. Fixed in `styles.css`: at ≤700 px the sheet is `position: fixed` at the viewport bottom,
   `max-height: 55vh`, z-index 43. That's a new page-level layer, recorded in the STACKING list,
   one below the phone pick card.
3. **The stats table re-rendered on the room's 1-second clock** (see SC-006 below). The fix is in
   progress.

### Checks

| Check | Result |
|---|---|
| Stats switch appears in the NBA room. Header names the season and window, says "Last season's play, not a projection", and names the scoring ("Fantasy points under 2025–26 league scoring.") | ✅ |
| Row count: **400** rows, the undrafted top-400 pool, before any pick | ✅ |
| **SC-002**: 10 players at rows 1, 2, 3, 4, 11, 41, 81, 121, 161 and 201 (Jokić … Sensabaugh), compared against `/leagues/1339351318115946496/stats` (Basic, Fantasy and Shooting groups, qualified filter off) | ✅ **120 of 120 cells identical** |
| Players with no 2025 games (e.g. Sergio De Larrea, ADP 369) read "No NBA games in 2025–26" in one spanning cell and sort last | ✅ |
| Names link to `/leagues/1339351318115946496/players/{sleeperId}` with `target="_blank"` | ✅ in the markup. ⚠️ **The pane opened it in the same tab.** Real-browser new-tab behaviour is **not yet verified** |
| **SC-007** at 375 px after fix 2: page `scrollWidth` 375 = viewport; table scrolls inside itself (961 px of content in 357 px); name column stays at x=9 after scrolling 300 px; a tap on "Stats" lands on the button | ✅ |
| **SC-003**, picks landing (harness below). Clean run of 20 picks: every logged frame has `landed + rows = 400`, so the table **never** showed a player the board had taken, not even for one frame. Frame-by-frame logging covered picks 1–11; `requestAnimationFrame` pauses while the pane is hidden, so 12–20 were checked by end state: **20 landed, 380 rows** ✅. The earlier run (before the fix) frame-logged picks 1–18 with 0 mismatches as well. | ✅ (28 picks frame-checked across two runs, 0 mismatches) |
| Sort kept across picks (PTS▼ through all 20), and across a Tiers↔Stats round trip | ✅ |
| Scroll across picks: `scrollTop` 600 → 375 after 8 picks. That's the browser's scroll anchoring keeping the same players in view as rows above are removed (8 × ~28 px), which is correct, not a reset | ✅ |
| **SC-006**, main-thread cost. **Before fix:** with Stats open, **18 long tasks (65–147 ms) per 10 s**. With Tiers, **0**. The room's 1-second re-render was re-rendering 400 rows. **After fix** (`memo` on the table and on each row; stable props and callbacks; `NO_AVAILABILITY` constant): **0 long tasks per 10 s** with Stats open, the same as Tiers. Pick-landing bursts (resimulation and pick card) are present in both views. Their open-vs-closed cost per pick was **not separately measured**. | ✅ idle, ⚠️ per-pick not measured |

### Harness: how "live" was driven

The same approach as spec 012's harness, rebuilt in this session's scratchpad (`fake-sleeper.mjs`, not committed):

- A fake Sleeper on 127.0.0.1:8793 serves draft `9900000000000000023`. It returns the real 2026 NBA draft object with `status: drafting`, plus a scripted pick list released on command (rounds 1–2 only, because this draft has `reversal_round` 3). Every other path is proxied to the real Sleeper.
- A synthetic local `draft` row (id 17900) copies draft 230's settings and seat map.
- The backend from this worktree ran on :8093 with `SLEEPER_BASE_URL` pointed at the fake. That means the real `LiveDraftPoller`, SSE, `/pool`, `/sims/stream` and the room on :5193.
- **Side effect, measured.** While row 17900 existed, `PlayerStatsLeaderboardReadIT.aSeasonThatFellBackReadsTheRequestedSeasonsDraftAndAdp` **skipped**: its assumption saw "the 2026 draft has been held". The local DB is shared, so the synthetic row is deleted after verification. The re-run is recorded below.

## US2: the stat picker modal (T025, quickstart V4)

| Check | Result |
|---|---|
| V4.1: opens over the room; 32 stats in Basic, Shooting, Advanced and Fantasy; no duplicate labels; **USG present**; full names beside short labels; focus moves into the dialog | ✅ |
| V4.2: FP/G, MIN, USG, TS% chosen and ordered; the table updates at once; sorted by USG (Jaylen Brown 36.4%, Embiid 34.2%, Kawhi 33.8% … descending) | ✅ |
| V4.3: **two picks landed with the modal open**. It stayed open, the four toggles were intact, the USG sort was kept, and rows went 380 → 378 | ✅ |
| V4.4: reload, then open the **other** room (the real 2026 draft): the same four columns in the same order | ✅ |
| V4.5: all off shows names, "No stats chosen" and Reset, and stores `[]`. Reset restores the 12 defaults and stores them | ✅ (sort falls back to name and stays there after Reset. Minor, left as is) |
| V4.6: stored `["gp","bogus","gp","pick"]` shows only GP, with no app errors (`pick` is a Draft-group id, so it's not pickable) | ✅ |
| 375 px: the modal is 351×690 inside 375×812, its body scrolls inside itself, Done is visible, no sideways page scroll, ▲▼ buttons are 32 px | ✅ |
| Escape closes it (keydown observed, dialog gone) | ✅. One earlier non-close, read with no wait after the key, was not reproduced |

**Bug found live and fixed:** ten "Remove" clicks fired back to back left nine of the ten
applied **un**done. Each toggle computed from the same stale render's list, so the last one won.
Fix: the modal passes updaters (`cur => next`), resolved in the panel against a ref holding the
latest list. Moves are by id, not index. The regression test (two removes in one `act`) **fails on
the old code** (verified by temporarily restoring it) and passes on the fix. Re-checked live: all
ten applied.

**Suites after US2 and the fixes**: `tsc -b` clean; vitest **94 files, 1292 tests passed**; `npm run
build` OK. Backend full run: **1518 tests, 0 failures, 1 skipped** (the harness-caused skip above).

## US3: mock rooms (T028, quickstart V5)

| Check | Result |
|---|---|
| Basketball mock forked from the (synthetic, `drafting`) 2026 draft. Mock 3624, `sourceSleeperLeagueId` = `1339351318115946496`. Stats is offered with the same stored columns over **522** rows: the mock's own undrafted pool, which isn't capped at 400 | ✅ |
| "Likely there" is disabled, and the room's existing "Availability needs a simulation, which mock drafts don't run." explains it | ✅ |
| The sheet mounts only on your turn. After a pick (Mobley, via `POST /api/mocks/3624/pick`) the bots advanced to pick 41. On the remount, **Stats was still selected** (new: the view is remembered in `sessionStorage`, key `bk.availView`), rows went 522 → 513 and Mobley was gone | ✅ |
| Basketball mock with **no** league (3625): Stats is disabled with "Stats need a league: start the mock from a league to see them." Tiers shows even though the remembered view is Stats | ✅ |
| Football live room (`1400986984582754304`): **no** Tiers/Stats switch, QB/RB/WR/TE/K chips, no console errors (FR-017) | ✅ |

Added during US3: a mock with no league (`null`) and an older backend (`undefined`) now give
different reasons. The view is remembered per browser session, so the mock room no longer opens
on Tiers every turn. Three new panel tests cover this (33/33 in `AvailabilityPanel.test.tsx`).

## Split deploy (T029, quickstart V6)

This branch's frontend (vite :5194) ran against the **022** backend (:8092), which predates the
new fields. `seats` has no `sleeperLeagueId`. The room renders (168 board cells). Stats is
disabled with "Stats aren't available on this server yet." Nothing throws. The three 403s in the
console are POSTs (`/refresh`, `/sims/stream`) refused by the 022 backend's `CORS_ORIGINS`, which
lists only :5192. That's an artifact of borrowing that server, the same one spec 012 recorded. ✅

## Harness cleanup (2026-10-09)

Deleted in one transaction: draft 17900 (cascading its `draft_pick` rows) and mock sessions
3624 and 3625. All three were created by this verification, and a re-count confirmed `0|0|0`. The
fake Sleeper is stopped, and the backend no longer points at it.
`PlayerStatsLeaderboardReadIT` re-run: **9 tests, 0 skipped, 0 failures**. The harness-caused skip
is gone.

## After the code review (T030 fixes, re-verified live 2026-10-09; backend restarted from this worktree)

| Finding | Live check | Result |
|---|---|---|
| S1: window race | A 3 s delay was injected on the Last 5 response, then Season was chosen while it was in flight. The view stayed on **Season**, 400 rows, not stuck on loading | ✅ |
| S1/S5: cache | Season → Last 10 → Season → Last 10 made **2** requests in total (one per window, cached after that) | ✅ |
| S6: pre-draft page | `/drafts/1339351318128517120` (DraftView) offers Stats: 400 rows, and Jokić's line is identical to the earlier verified one | ✅ |
| S7: phone | At 375 px, the open sheet ends at y=736, clear of the jump-to FAB at 752–796; the collapsed pill sits bottom-left (8, 765); `elementFromPoint` hits the sheet at all 4 corners and the centre, in both states; no sideways scroll | ✅ |
| S2 / N5 | In Stats, no "No players survive…" tier message and no Next 1–6 depth chips | ✅ |
| SC-006 again | **0 long tasks in 10 s idle** with Stats open, after the fixes | ✅ |
| S3, S4, S8 | Covered by component tests. S8 measured **0 cell re-renders** for surviving rows after an unrelated pick (the review measured 108 of 108 before). Not separately re-observed live | ✅ tests |

**Final suites**: backend `./gradlew test` **1522 tests, 0 skipped, 0 failures** (1515 baseline + 7
new); web `tsc -b` clean, vitest **94 files, 1305 tests**, `npm run build` OK.
