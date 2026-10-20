# Live verification: spec 040 (2026-10-20)

Backend `bootRun` from this worktree (boot log confirms the classpath is
`.claude/worktrees/040-superlatives-commissioner-ux/backend/build/classes`). Vite dev server from this worktree's
`web/`. Docker Postgres on 5433. The browser was the in-app browser pane, signed in as popsharky (commissioner of
every league below). All numbers are measured unless marked otherwise.

## US1: Embiid cost per week (SC-002, SC-003)

Before is the baseline captured from this branch before any code change (`baseline/*.json`). After is the live payload.

| League | Rosters changed | Holder before → after | Top value before → after |
|---|---|---|---|
| NBA 2025 | 12 of 12 | Shaolintonio Spurs → Shaolintonio Spurs | 3980.55 → 776.12 |
| NBA 2024 | 12 of 12 | Fent Machines → **Al Queta** | 4492.97 → 734.04 |
| NFL 2025 (Foot) Ball Knowers | **0 of 12** | jstrobe → jstrobe | 218.85 → 218.85 |
| NFL 2025 West Coast | **0 of 12** | Boutte Man → Boutte Man | 267.86 → 267.86 |
| NFL 2026 (Foot) Ball Knowers | **0 of 12** | Khatt Stafford → Khatt Stafford | 90.57 → 90.57 |

- **SC-003 passes.** Every roster in all three NFL leagues is identical to the baseline.
- **NBA:** totals fell to about 16–20% of the old per-game figures. Research R1's "about a quarter" was an
  indicator, and the measured drop is somewhat larger. NBA 2024's winner changes.
- Per-roster NBA tables are in the `verify_api.py` output in the session scratchpad. Example rows: NBA 2025 roster 9
  went 1573.52 → 140.19 and roster 5 went 3955.13 → 723.49.

**Hand check (SC-002): Domantas Sabonis, NBA 2025, roster 8.**
- **Method:** SQL over `roster_week_points`, `player_absence` (non-UNCLASSIFIED) and `player_game`, weeks 1–18.
- **Counted weeks:** 5–12 and 17, all with 0 games played, so 9 weeks. The games sat in those weeks total 27.
- **Payload:** `9 wks, 27 games, ppg 24.42 → 219.78`. That is 9 × 24.42 = 219.78, an exact match.
- **Partial weeks:** weeks 1, 3, 13, 14, 15 and 16 each had 1–2 missed games and 1–3 played, and all cost 0.
  Week 13, for example, was 2 sat and 2 played.

**No (player, week) is counted for two rosters.**
- In league 211, four player-weeks sit on two rosters (week 1: players 1830, 1845 and 2012; week 3: player 2136).
  These are mid-week trades.
- Every one of them had ≥2 games played and 0 absences, so none is counted.
- League 212 has none.
- **LOW, not fixed:** a traded player who missed every game of the trade week would be charged to both rosters.
  The old rule had the same behaviour.

## US3: Every player behind an award (SC-004, FR-015)

| League | Embiid rows | Waiver rows | Mismatches (Σ players vs team figure) | Rows without username |
|---|---|---|---|---|
| NBA 2025 | 76 | 255 | 0 | 0 |
| NBA 2024 | 85 | 144 | 0 | 0 |
| NFL 2025 (Foot) | 47 | 175 | 0 | 0 |
| NFL 2025 West Coast | 46 | 178 | 0 | 0 |
| NFL 2026 (Foot) | 13 | 32 | 0 | 0 |

- **Code-review M1 is confirmed fixed.** NFL 2025 (Foot) previously failed on Waiver rosters 5 and 11, where
  negative pickups had been dropped.
- **Unethical:** it has 0 rows everywhere, because the leagues have no entries and no rostered suspensions. Its sums
  were checked during the SC-005 run below, at 3, then 13, then 13.
- **Jabari** `playerStandings` is uncapped. NBA 2025 went 13 → 182 rows, NBA 2024 25 → 96, and West Coast 11 → 49.
  The minimum is 2 adds everywhere, the floor from spec 008 (FR-015 as amended after code review).
- **UI:** the Embiid "See all" opens on **Players**, with Teams one click away. Trae Young ranks 1st at 296.10 and
  Tyler Herro 2nd at 225.06.

## US2: Commissioner's list (SC-001, SC-005, SC-006)

**New season (NBA 2026, no scored week), in the real browser:**
- The cards show 2025. The Unethical card's "Commissioner's list · 0" opens a panel headed **"Commissioner's list ·
  2026"**, with the note "The awards above show 2025. This 2026 list starts counting once 2026 has a scored week."
- **Add:**
  - Typing "embi" found Joel Embiid (C · PHI · The Flaggots), and ArrowDown + Enter selected him. No id was typed.
  - Reason, from 2 and through 3 were entered, then Save.
  - The POST returned 200. The button changed to "· 1" without a reload, and the entry read "wk 2–3 · 0 player-weeks ·
    not rostered in these weeks yet". That is correct, since 2026 has no scored week.
- **SC-001 (under 30 s):** the clock read 33.4 s, but that includes the browser tool's own round-trips and a
  stale-element retry on my side. It took six interactions. I'm reporting it as **not cleanly measured**, not as
  passed.
- **Edit, invalid range:** through 1 < from 2 is blocked inline with "The through week can't be before the from week",
  and no request is sent.
- **Edit, valid:** through 4 went out as a `PUT …/conduct-list/995` → 200 from the real browser, and the row shows
  "wk 2–4".
- **Remove:** an inline "Remove? Yes / No" appears. Yes sent DELETE → 204, and the count returned to 0.
- **CORS:** the cross-origin preflight `OPTIONS` for PUT, from Origin `http://localhost:5173` against `:8080`, returned
  200. Allow-Methods includes PUT and Allow-Headers includes `x-sleeper-user`. The browser's own requests went through
  the vite proxy, which is same-origin, so this curl is what covers the split-service production shape.

**SC-005, week range on NBA 2025 (scored):**
- **Entry:** POST for Sabonis, weeks 5–7.
- **Result:** the entry shows `playerWeeks 3`, charged to Shaolintonio Spurs for [5, 6, 7]. The award value is 3.0, the
  holder is Shaolintonio Spurs, and the Players row reads "Commissioner · wks 5–7" at 3.0.
- **Through-week cleared:** a PUT with `appliesThroughWeek: null` returned 200. The entry then showed 13 player-weeks,
  weeks 5–17, which are the rostered weeks from the SQL above. The award moved to 13.0. All three agree.
- **In the browser:** the 2025 panel showed the entry with a "charged to Shaolintonio Spurs wk 5–17" chip, and the
  Unethical card listed Sabonis.

**SC-006, as non-commissioner nuggetchef on NBA 2026:**
- PUT, POST and DELETE each returned 403, with "only this league's Sleeper commissioner may edit the conduct list".
- GET returned `canEdit: false`, with 187 candidates.
- **Not driven in the UI:** the read-only panel itself, for a non-commissioner. That is covered by the component test
  "a non-commissioner sees no controls".

**Server validation:** a PUT with through 2 < from 3 returned 400 "appliesThroughWeek must be >= appliesFromWeek", not
a 500.

**Cleanup:** both test entries (995, 996) were deleted, and `select count(*) from league_conduct_entry` returned 0.

## Found and fixed during verification

1. **Layout: the card body column collapsed at desktop width (MEDIUM, fixed in `web/src/styles.css`).**
   - At a 1024 px viewport the Embiid row's middle column was 74 px and the Unethical row's was 58 px. The holder
     name truncated to "Sh…".
   - **Cause:** the stat ("776.12 estimated points lost") had `white-space: nowrap` and took 332 px. That was already
     true on main, but this spec's longer detail lines made it obvious. The Unethical card's new second button also
     widened the action column to 209 px.
   - **Fix:** the body column gets `minmax(220px, 1fr)`; the stat wraps within `max-width: 210px`; the action buttons
     stack vertically.
   - **Re-measured:** the Embiid body is 220 px and the Unethical body 220 px, with the stat at 101 px and actions at
     144 px.
2. **Layout: phone width (MEDIUM, fixed).** At 375 px the stat sat beside the body and squeezed it to about 120 px.
   On phones the headline figure now takes its own row under the title. `scrollWidth` is 375 = `innerWidth`, so there
   is no horizontal scroll.
3. **Retracted:** I briefly flagged the through-week input as unlabelled, because the find tool listed it by its
   placeholder. `input.labels` returns "Through week (optional)", so the markup is correct. **Not a bug.**

After the CSS fixes, `tsc -b` is clean, vitest passes 2247 of 2247, and `npm run build` succeeds.

## Open, not fixed

- **LOW: Remove's "Yes" renders in the same spot as "Remove"**, so a double-click confirms the delete. The inline
  confirm still blocks a single misclick.
- **LOW: "player-weeks" breaks at the hyphen** when the stat wraps. This is cosmetic.
- **Code review L4–L6** stand as written in `code-review.md`. L5 needs checking after the first live NBA 2026 week: a
  player who sat Monday reads as a whole week missed until he plays.
- **Backend suite:** 1 failure, `BoardRepositoryLatestBeforeIT`. It **also fails on clean `main`** (run in the
  `main-merge-006` worktree: 5 tests, 1 failed), so it is a shared-DB timestamp issue and not this branch. There is
  also 1 known self-skip, `PlayerStatsLeaderboardReadIT`.
- **Not done:** a deploy and a prod check. Commit, push and PR are waiting on Allan.
