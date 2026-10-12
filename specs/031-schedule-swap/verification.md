# Verification: spec 031

**Pipeline:** plan → adversarial review ([plan-review.md](plan-review.md), F1–F11) → build (Sonnet)
→ bug-hunting review ([code-review.md](code-review.md), B1–B5) → fixes → live check. Run on
2026-10-12.

## Tests

- Backend full suite: **1,657 tests, 1 skipped** (`PlayerStatsLeaderboardReadIT`, not this spec),
  **1 failure**: `BoardRepositoryLatestBeforeIT`. That test hard-codes the NBA draft's pre-draft
  scheduled start (19:15:08Z), and the real draft's re-ingest set 21:16:19Z. It's a stale literal
  in spec 024-era code, not touched here, and named as a follow-up.
- `ScheduleSwapTest` (10):
  - the review's hand-worked 4×3 grid and difficulty 0.417/0.667/0.5/0.417;
  - self-pairing, the column bye, and the row's missing score (difficulty exactly
    [0.375, 0.625, 0.521, 0.5]);
  - a duplicate roster throws, and `compute()` then gives a null matrix rather than failing (B2);
  - the no-bye identities.
- `ScheduleSwapIT`: leagues 5, 9466, 211, 212, one named assumption each, none skipped. The full
  W-L-T diagonal equals the stored games' record (NBA 2024 includes real ties). The mean over the
  other N−1 schedules = expected wins, and mean difficulty = 0.5.
- `PlayerTrendsReadIT` (spec 019) was made state-independent. It had hard-coded NBA 2026 as
  pre-draft (B3 tightened it).
- Web: `tsc -b` clean, vitest **1,784** (112 files), build OK.

## Live

Backend from this worktree on :8087 (classpath checked, restarted after fixes; fresh PID 22912),
Vite :5187, signed in as `popsharky`.
- `expected-wins` for (Foot) Ball Knowers 2025 in 0.09 s. The diagonal equals each team's
  actual wins (jpelwell 9-5 / 9.0 …), and every cell sums to 14.
- The page: a 12×12 grid with exact W-L per cell, "ACTUAL" on the diagonal, and a win-rate row
  (66.1% … 36.3%). "Easiest schedule: jpelwell (66.1%). Toughest schedule: i Chase Brown kids
  (36.3%)". The rule, the expected-wins relation and the Schedule-column difference are in "How
  this works".
- 375px: document 375 wide, the grid scrolls in its card (1,128 px in 343), the sticky first
  column is opaque.
- After fixes: `scheduleSwap` is present for NFL 2025, NBA 2025 and NFL 2026 (in season).
- Console: the known visit-refresh 403s only.

## Found, not fixed here

- **Sleeper drift (code-review B1):** for NBA 2025, Sleeper's current `/matchups` points differ
  from the stored scores on most roster-weeks (14 flipped results). The diagonal is labelled as
  the stored-scores record. This is a follow-up across every page.
- **In-season "so far"** shows only while `early`. The payload has no in-season flag (build
  deviation 1), and that's accepted.
