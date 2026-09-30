# Verification: 010 superlatives full standings

## Baseline (T001, 2026-09-29, branch at `587fbc3`, before any change)

These results were executed, not assumed.

- **Backend.** `./gradlew test` reported BUILD SUCCESSFUL.
  - **880 tests, 0 failures, 0 errors, 0 skipped.** The counts were summed from
    `backend/build/test-results/test/*.xml`.
  - Postgres was up on 5433 (`draftsim-pg`). `SuperlativesControllerIT` ran; it was not
    skipped.
- **Frontend.**
  - `npx tsc -b` was clean.
  - `npx vitest run src/pages/Superlatives.test.tsx` reported **43 passed / 43**.

## Full checks after the build (T027, 2026-09-29, commit `cfebb71`)

These results were executed by the parent session. They are not the builders' reports.

- **Frontend.** `npx tsc -b` was clean. `npm run build` succeeded. `npx vitest run` passed
  **630 / 630** across 52 files. `Superlatives.test.tsx` was 53 (43 baseline + 10 new).
- **Backend.** `./gradlew cleanTest test` ran **934 tests: 933 passed, 1 failed, 0 skipped**.
  - `SuperlativesStandingsIT` **ran**: 7 tests, 0 skipped, 0 failed.
  - The one failure is `RefreshControllerIT.aChainRunShowsRunningAtTheTopEvenWhenTheShownSeasonIsLoadedComplete`
    (`:267`). **It is pre-existing, not caused by this branch.**
    - The refresh code and test are byte-identical to `main`.
    - On a clean detached checkout of `origin/main` (`587fbc3`), the class ran 5 times in
      isolation. It **failed 4 of 5** runs, at the same test and the same line.
    - The builder's own full run had also hit it once, and passed on a re-run.
    - It is flagged as a separate follow-up task, not fixed here.
  - Note: the first `./gradlew test` after the build reported "up-to-date", which is stale XML
    from the builder's run. The counts above come from a forced `cleanTest test`.

## Live verification (T031, 2026-09-30)

**How it was run.** This worktree's own servers ran on the backend port **8084** and the vite
port **5184**. Other sessions' ports (8080/5173) were not touched.
- The first attempt used `preview_start draft-sim-api-8082`. **It was serving the main checkout's
  code, not this branch's.** The launch config does `cd backend` from the main repo root, which
  the classpath (`draft-sim\backend\build`) confirmed. It was stopped, and both servers were then
  started from the worktree directly. The 8084 classpath was confirmed as
  `worktrees\010-superlatives-full-standings\backend\build`.
- Signed in as `popsharky` (local dev, username only).

### Verified: rank 1 = the card, every roster once, on real data (API, both 2025 leagues)

These were executed through `GET /api/leagues/{id}/superlatives` against the real local data.

| League | Kinds with a winner | Rosters per list | Rank-1 vs holders mismatches |
|---|---|---|---|
| "(Foot) Ball Knowers" NFL 2025 (`1254190892974084096`) | 12 (UNETHICAL empty) | 12, all unique | **0** |
| "Ball Knowers" NBA 2025 (`1229352720222134272`) | 12 (UNETHICAL empty) | 12, all unique | **0** |

- **CLOSEST_GAME** uses the subset rule, as designed.
  - NFL: holders `[8]`, rank 1 `[2, 8]`. The 0.36-point game's loser shares rank 1.
  - NBA: holders `[7, 12]`, rank 1 `[7, 8, 10, 12]`.
- **Jabari:** the rank-1 players equal `playerHolders` in both leagues.

### Verified: figures checked by hand against other pages

- **Highest week, a non-winner (rank 4, popsharky):** the modal shows 182.82 in week 5. The
  Weekly report for week 5 shows popsharky at 182.82, and `roster_week_points` confirms 182.82 is
  that roster's max.
- **Unluckiest (ranks 4 and 5):**
  - "i Chase Brown kids": −1.27 (6 actual vs 7.27 expected);
  - popsharky: −0.64 (10 vs 10.64).

  Both match the Expected wins endpoint exactly. The full Unluckiest order equals Expected wins
  sorted by wins above expected, ascending.

### Verified: fallback season

"Ball Knowers" NBA **2026** (`1339351318115946496`) is pre-draft with 0 scored weeks. It returned
`season 2025, requestedSeason 2026`, and its standings are 2025's, with 0 mismatches. **Exercised,
not skipped.**

### Verified: the UI in the browser (NFL 2025)

- There are 12 "See all" buttons, one per award with a winner. UNETHICAL is empty and has none.
- **Desktop (1400×900):** Highest week opens a modal listing all 12 teams, high to low, with the
  winner tinted, exact figures and the week of each.
- Escape closes the modal, and focus lands on the close button when it opens.
- **Closest game:** "won vs / lost to X · week N", with a shared rank 1 and then 3, 3.
- **Unluckiest:** "−3.18 wins vs expected" with an "actual vs expected" note, low to high.
- **Phone (375×812):** there's no horizontal overflow (dialog scrollWidth 375). The rail is hidden
  (`bk-modal-fullscreen`).

### Found live and fixed in this pass (each executed, then re-checked in the browser)

1. **Jabari listed 47 players.** The backend's "top 10, including ties at the cutoff" is correct,
   but on NFL 2025 it's 6 players at 3 adds plus **41 tied at 2**. That's more than the T030
   review's rough estimate of about 24.
   - **Fix:** the modal shows whole groups up to 10 rows and collapses the rest into "Rank 7 · 41
     more players with 2 adds each — Show all". The backend still sends the complete list.
2. **The phone sheet stopped at 650 of 812px.** The phone rule's single-class selector lost to the
   shared `.modal-card.wide { max-height: 80vh }`.
   - **Fix:** raised the specificity. It now measures 812/812.
3. **"Show all" hid the leaders.** Expanding the tie group squeezed the rank-1 list to **0px**,
   because they were two flex children, and the 3,651px group overflowed a sheet that didn't
   scroll.
   - **Fix:** one scroll area for both. Leaders: 493px, visible. Scroll area: 705px holding 4,136px
     of content.
4. **"7 41 more players…" read as 741.** It's now "Rank 7 · 41 more …".
5. **Player-row team line was indented for an avatar player rows don't have.** It's now flush.

### Observed, not caused by this branch

- The console shows **403**s, all from `POST /api/leagues/1346366555759341568/refresh`: the
  visit-triggered refresh of the 2026 season, refused locally.
  - The refresh code is unchanged from `main` (ingest was gated behind the admin token in the audit
    waves, and no token is set locally).
  - Every superlatives and conduct-list request returned 200.
  - This also means the live check wrote nothing to the shared DB through refresh.

### Final checks after the T031 fixes

- **Frontend:** `tsc -b` clean, build OK, **635 / 635** tests (58 in `Superlatives.test.tsx`).
- **Backend** (re-run by the parent session after T031, `cleanTest test`, timestamp 05:08Z):
  **936 tests, 0 failures, 0 errors, 0 skipped**, and `SuperlativesStandingsIT` ran 7/7.
  - The pre-existing flaky `RefreshControllerIT` passed on this run. That doesn't change the finding
    that it fails 4 of 5 times on clean `main`.
  - (A first attempt at this re-run failed to `cd` and read the fix agent's stale XML. It was
    discarded and redone.)

### Not verified

- Real **orphaned** roster data: the local DB has none. The orphan path is covered only by the
  seeded `SuperlativesStandingsIT`.
- UNETHICAL on real data: both leagues' conduct lists are empty, so the award has no winner. It's
  covered only by the seeded IT.
- Keyboard-only walk-through with a screen reader: not done. Focus and Escape were checked with
  scripted events.
