# Verification: 012-draft-pick-insight

Every figure here is labelled **measured** (it was run) or **assumed** (it was not).

## Baseline (T001, measured 2026-09-30, worktree @ de1e1dd)

- **Environment**: Windows, Postgres on `localhost:5433` answering. Ports 8080 and 5173
  are already held by processes outside this worktree (another session or the main
  checkout). They were left alone. Live checks in this worktree must use their own ports.
- **Backend** `./gradlew test`: **936 tests, 1 failed, 0 errors, 0 skipped**.
  - The failure is `RefreshControllerIT.aChainRunShowsRunningAtTheTopEvenWhenTheShownSeasonIsLoadedComplete`, which is known to fail on the base branch (spec 011 memory).
  - Zero skipped means the Postgres-backed ITs actually ran.
- **Web** `npx vitest run`: **52 files, 671 tests, all passed**.

## Backend `/pool` (T004, T028–T031, measured)

- `./gradlew test`: **944 tests, 0 failed, 0 skipped**. The 8 new tests are 7 in `LeagueControllerPoolTest` and 1 in `MonteCarloRunnerSnapshotDepthTest`.
- `AccessControlMvcIT` ran for real (0 skipped). `/pool` returns 404 with no identity, 404 for a stranger, and 200 for a member.
- `RefreshControllerIT` **passed** this run after failing in the baseline, so its known failure is intermittent, not deterministic. This feature did not touch it.
- The diff was reviewed by the parent session.

## T023: per-pick re-projection cost and 429 rate (measured 2026-09-30)

**Setup.**
- **Machine and server**: Allan's Windows machine, 12 logical cores. Backend built from this worktree on :8082.
- **Draft**: "(Foot) Ball Knowers" 2026 (`1346366555776126976`, 12 × 15, completed, 180 real picks).
- **What was sent**: exactly what a live tab now sends. `POST /api/sims/stream`, 500 iterations, temperature 1.0, an explicit `startState` holding the real first *k* picks, one distinct member `X-Sleeper-User` per caller.
- **The client retry policy was reproduced**: up to 3 retries, `min(8s, Retry-After × attempt)`.
- **Script**: scratchpad `measure.mjs`. It is not committed.

**A measurement bug, caught and fixed before any number was kept.**
- The first run's `startState` ids carried a trailing `\r` from psql's Windows output. The backend's `resolveStartState` **silently dropped every id**, so pick 1 read 0.094 instead of 1.0, and those runs simulated the whole draft unlocked.
- After the fix, pick 1 and pick 24 read **1.0** and pick 25 is simulated (0.114). Only the post-fix numbers are below.
- The silent drop is R2's known gap and is backend behaviour this feature does not change. The client builds `startState` from `RealPick.player.sleeperId`, not from a text export.

**Serial (one caller), time to result, 5 runs each:**

| Picks already made (k) | min | median | max |
| --- | --- | --- | --- |
| 24 | 219 ms | 221 ms | 273 ms |
| 60 | 191 ms | 192 ms | 195 ms |
| 120 | 142 ms | 147 ms | 152 ms |

The backend log agrees: about 150–165 ms simulation loop, 40 ms aggregate, 40 ms setup.

**12 concurrent callers (a full room, all firing on one pick):**

| Permits | k | raw 429s | 429s after client retries | time to result min / median / max |
| --- | --- | --- | --- | --- |
| 3 (default, `max(2, 12/4)`) | 24 | 0 / 12 | 0 | 434 / 1405 / 2074 ms |
| 3 | 60 | 0 / 12 | 0 | 388 / 1047 / 1698 ms |
| **2** (forced, `--draftsim.sims.max-concurrent=2`, the floor) | 24 | 0 / 12 | 0 | 273 / 1368 / 2191 ms |
| **2** | 60 | 0 / 12 | 0 | 321 / 1241 / 1849 ms |

**Gate: PASS.**
- The worst case is ~2.2s, against a 30–120s gap between real picks.
- No caller was refused, even at the 2-permit floor, because a run is ~0.2s and the queue drains well inside the 3s permit wait.

**Correction to an earlier guess.** The spec, the plan and `LiveDraftView.tsx`'s header carried "~5s" for a 500-iteration resim. That figure came from the mock room before the engine's hot-path refactor, and it is wrong for this stack by about 20×. The same header comment already called it unmeasured.

**Also measured live (T042):** the browser's own round trip per re-projection, over five per-pick runs, was **274–456 ms**.

**Still assumed, not measured.**
- **Railway.** Its core count and per-core speed are unknown. A 429 needs the queue to take longer than 3s to drain, which with 2 permits and 12 callers means a run slower than ~0.5s, about 2.5× this machine. That is plausible on a small container, and it degrades gracefully: quiet retries, then `busy` on the card.
- **Production has not been measured.**

## T039–T042: suites, scope guard, live verification (measured 2026-09-30)

### Suites (T039)

- **Backend**: 945 tests, 0 skipped, 1 failure, which is `RefreshControllerIT` again. It failed at baseline, passed on the `/pool` run and failed here, so it is intermittent. It sits outside this feature's diff.
- **Web**: `tsc -b` is clean and the build passes. The count was 804, then **806** after the live-verification fixes and their regression tests.

### Harness: how "live" was driven without a real Sleeper draft

- **A synthetic draft** in the local dev database: `draft.id` 11402, `sleeper_draft_id` `9900000000000000012`, status `drafting`. It copies the settings and seat map of "(Foot) Ball Knowers" 2026.
- **A worktree backend on :8082** with `SLEEPER_BASE_URL` pointed at a fake Sleeper on :8790 (scratchpad `fake-sleeper.mjs`, not committed).
  - For that one draft id, the fake serves `status` and `draft_order` and releases the real 2026 draft's picks on command.
  - Every other path is proxied to the real Sleeper unchanged.
- **Real code paths.** The **real** `LiveDraftPoller` (10 s ticks), `draft_pick` upserts, SSE `state` frames, `/pool`, `/sims/stream`, and the React room on vite :5179 all ran for real.
- **Identity** was a league member's Sleeper user id in `bk.user.v1`. The app uses identity, not a password.
- **Artifact of the harness, not a bug.** The first projection returned **HTTP 403**: CORS allows only :5173 by default, and a browser POST carries `Origin`. Restarting with `CORS_ORIGINS` including :5179 fixed it.

### What was checked live, and the result

| Check | Result |
| --- | --- |
| Room at pick 31: feed, run callout, team strip, scarcity meter with its definition line, Room read | ✅ |
| Card arrives after a pick, through the real poller | ✅ about 7–10 s after release, which is one poll tick |
| Card content: fit, still-needs, summary, ADP tag, model share, on-brand, likely next, scarcity line | ✅ all present |
| **SC-003**: "Model had this at 11%" (Tee Higgins, 3.11) against the pre-pick run (asOf 34, cell 35) | ✅ exactly **0.110** |
| **SC-003**: "Likely next @ 4.02" (McMillan 9% · Nabers 8% · Egbuka 7%) against the post-pick run (asOf 35, cell 38) | ✅ **0.088 / 0.082 / 0.074** |
| "Updating…" then ready "Likely next", with "Wide open" under 25% | ✅ |
| Feed-row click opens that pick's card and moves focus into it | ✅ |
| **SC-004**: a burst of 3 picks in one tick gives one card, for the newest pick | ✅ at most 1 card at a time |
| On the clock (you at 5.03): no card for pick 50 (5.02) | ✅ |
| Picks arriving while the tab is hidden (the pane was closed) never open a card | ✅ found incidentally, `visibilityState: hidden` |
| Projected meter column opens once 75 or fewer starter-pool players are undrafted ("~16 at 5.03") | ✅; before that, "projected count from pick ~46" |
| **SC-007**: cards off, then reload: still off, no card on a new pick, meters still update, feed rows not clickable | ✅ |
| **SC-006**: 375 px: no horizontal scroll (document width = viewport), meter fits | ✅ |
| Basketball (NBA 2025, completed): PG/SG/SF/PF/C, "top 108 (12 teams × 9 starters)", no cards on a completed draft | ✅ |
| **T040**: pre-draft simulator and mock room (`/mock/638`): no cards, no meters, no pick-cards chip, feed rows not buttons | ✅ |

### Found by live verification (the tests had missed all three), fixed

1. **The board was blank before the first projection.**
   - The grid overlaid only the state frame's last 12 picks, so rounds 1–2 were empty at pick 31 even though the page held every pick.
   - `boardWithLive` now overlays the whole landed list, with a regression test in `LiveDraftView.landed.test.tsx`.
2. **An on-brand verdict called a non-reach "on brand".**
   - The card showed "reach +0.7 vs +10.0 … on brand": same sign, but +0.7 is not reaching.
   - The rule is now: on brand if both are within ±3, or both are beyond ±3 with the same sign. There is an ordering test, and data-model.md is amended visibly.
3. **On a phone the card was below the fold.**
   - `.board-stage` is 0 px tall at ≤700 px, so the card hung off it at y≈480–870, off-screen whenever the page was scrolled.
   - It is now a viewport-pinned fixed layer at ≤700 px (bottom, max 60vh, scrolls inside), added to the STACKING list as 44.
   - Re-measured: fully in view after scrolling, `position: fixed`, no horizontal scroll.

### Observed, not changed

- **NBA "SG 0 / 0".** No player whose primary position is SG is in the board's top 108. That is the board data (Sleeper's primary positions), and the meter states it accurately. It may read oddly to a user; this is a candidate follow-up.
- **The meter's projected figure blanks briefly after each pick**, until that pick's run lands (0.3–0.5 s here). It does not show the old value. This is code-review N2, and it was barely perceptible live.

### Not verified

- **Railway**: production cost, 429 rate, and CORS. The harness is local.
- **A real Sleeper draft end to end.** The poller logic is real, but Sleeper's own responses were stood in by the fake for the one draft.
- **Sound, and a real phone.** 375 px was emulated in the desktop pane.
