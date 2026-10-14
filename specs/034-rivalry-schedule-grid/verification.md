# Verification: spec 034 (2026-10-14)

Everything below was **run**, against the worktree build. Where a line is inferred, it says so.

## Setup

- **Scratch DB.** Container `draftsim034-pg` serves `localhost:5434`, on a Docker named volume. It holds a copy of the shared
  5433 `draftsim` DB, with 033's uncommitted V32 removed from the copy only (plan F1). Copying
  into the *shared* server first made it PANIC once and auto-recover in about 20 s (lessons #42). Its
  row counts were checked unchanged afterwards.
- **Servers.**
  - Backend: worktree `bootRun` on **8081**, with `DB_URL=…5434`, `ADMIN_TOKEN=local034` and
    `--refresh.on-visit.enabled=false`, so the server never writes Sleeper data into the scratch DB.
    The two console 403s in the browser are that refresh POST being refused, not this feature.
  - Frontend: worktree `vite` on **5174**, proxied to 8081.
  - Signed in as popsharky.

## Quickstart §1: bracket backfill, scores untouched

`POST /api/ingest/brackets/{id}` with `X-Admin-Token`, for the three 2026 heads:

| Chain | Result |
|---|---|
| "(Foot) Ball Knowers" | 2026: 7 (provisional). 2025: skipped (stored bracket complete, frozen per F5). |
| NBA "Ball Knowers" | 2026: 7 (provisional). 2025 and 2024: skipped (complete). |
| West Coast | 2026: 7 (provisional). 2025: **fetched for the first time**, 7 matches, all with a winner. |

- Without the token: **403**.
- `sum(starters_points)` per league was **identical before and after** for every real league.
  The only diff lines were two synthetic IT-fixture leagues (40863/40864 → 41266/41267) that the
  test suite recreated between snapshots.

## Quickstart §2–§4: invariants on live payloads (5 leagues)

`GET /api/leagues/{id}/rivalries` returned 200 for all 5, in 25–250 ms (the first call was cold).
Anonymous and stranger callers get **404**.

A script checked every payload for the following, and **all five pass**:
- `byKind` sums to W/L/T, and W+L+T equals the meeting count.
- One pair per unordered current-manager pair.
- MET exactly when meetings > 0.
- Every OPPONENT grid cell is mirrored.
- Every `priorRecord` equals the meetings strictly before that week.
- The recommendation pairs everyone exactly once.
- `thin` equals meetings < N, and `thinCount` matches.

| League | Rows | Statuses | Kinds (meetings) | Recommendation |
|---|---|---|---|---|
| Foot 2026 | 12 | MET 59, NOT_YET_MET 7 | REG 94, PLAYOFF 5, PLACEMENT 2, CONSOL 4 | OK, thin **2/6** |
| Foot 2025 | 12 | MET 66 | REG 84, PLAYOFF 5, PLACEMENT 2, CONSOL 7 | NO_HISTORY (no earlier season, per F10) |
| West Coast 2026 | 14 | MET 48, NOT_YET_MET 43 | REG 66, PLAYOFF 4, CONSOL 6 | OK, thin 4/7 |
| West Coast 2025 | 12 | MET 66 | REG 84, PLAYOFF 6, PLACEMENT 3, CONSOL 9 | NO_HISTORY |
| NBA 2026 | 12 | MET 66 | REG 213, PLAYOFF 10, PLACEMENT 4, CONSOL 12 | OK, thin 1/6 |

- **West Coast 2025's two-week final and 3rd-place game:** 9 bracket meetings from 7 matches. The
  playoff weeks hold 18 games, and 18 − 9 = 9 are consolation, which matches.
- **Wrong prediction, corrected visibly.** quickstart §4 predicted that most "(Foot) Ball Knowers"
  pairings would be thin. Measured: 2 of 6. The matching selects the most-met pairs, so it lands on
  the 8 that clear N = 3. N was not retuned.
- **Parity spot check:** NBA pair 32 v 49 is 5 meetings, 4-1, both on this page and on
  `/api/managers/32/versus/49` restricted to the NBA chain. The full check is
  `RivalryParityIT`.
- **Reason text checked against raw data:** "Met in the 2025 final: Dart has hit anotha Bower beat
  Osama Bin Loveland 132.58 to 132.42". `roster_week_points` league 5, week 17: roster 1
  (popsharky) 132.58, roster 3 132.42. Sleeper's bracket `p:1` gives `w:1`. All three agree.

## Quickstart §5: browser

- Desktop:
  - **Matrix:** renders, and mirroring reads correctly (Master Bates v Play with the Klittle is 1-0
    one way and 0-1 the other). Cells link to the versus page.
  - **Season grid:** "Week 6 · this week" is marked (`currentWeek` 6), your own row is marked,
    cells print the opponent, "n-m before" and the result.
  - **Rivalry week:** prints N "(hand-set)", the Sleeper note, and the thin tags.
  - **Rail:** "Rivalries" appears under History for both sports, and the year links keep you on
    the page.
- NBA: the two disputed finals show "final disputed" (see the open question below).
- **375 px:** `scrollWidth` 375 equals `innerWidth`. The tables scroll inside their own panels. After
  the fix, the sticky name column is 120 px.
- **Two bugs found here and fixed** (code-review.md, "Found in live verification"):
  - "An even series after 3 meetings: 2, 1" now reads "Separated by one game…". Re-checked live
    after a backend restart.
  - The phone name column took about half the screen. It's now capped at 120 px. Re-measured.

## Suites (run by the parent, final tree)

- **Backend** on 5434: **1745 tests, 1 failed, 1 skipped, 0 errors.**
  - The failure is `BoardRepositoryLatestBeforeIT`. It asserts the NBA 2026 draft started at
    `2026-10-10T19:15:08Z`. Both the scratch DB **and the shared 5433 DB** hold `21:16:19.144Z`
    (measured). It reads only the `draft` table, which 034 doesn't touch. So it fails on `main`
    against the same data (inferred from that; not run on `main`).
  - The skip is the data-dependent `PlayerStatsLeaderboardReadIT`.
- **Web:** `tsc -b` is clean and `npm run build` is OK. vitest: **124 files, 2101/2101 passed.**

## Not verified

- Production. Nothing is deployed or committed.
- The provisional-bracket opponent fill (F7) for a playoff week that's in progress. No league is in
  its playoffs, so that path is covered only by unit tests.
- A league with an odd manager count (covered only by a unit test with 11 managers). Same for
  a manager with two rosters in one season (B8).
- The B7 cost (a season with playoffs whose bracket Sleeper answers `[]` is asked again on every
  refresh) wasn't observed live.

## Open data question (not a code bug)

**NBA 2025 and 2024: both stored finals disagree with Sleeper's bracket winner** (code-review B2,
measured). 14 of the 16 other bracket games agree with the stored scores, and Sleeper's live
`/matchups` agrees with the bracket for both finals. Stored NBA scores were only ever reconciled
for weeks 1–18 (memory `project_sleeper_nba_2025_matchup_drift`). The page counts the stored
scores, which keeps parity with the versus page. It names Sleeper's winner and prints both scores,
with a "final disputed" tag. Which source is right for NBA playoff weeks is undiagnosed.
