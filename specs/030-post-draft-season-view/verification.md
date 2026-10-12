# Spec 030 verification

The local runs were on 2026-10-11, against the worktree's backend on :8080, the worktree's
vite on :5179, and Postgres 5433. Each item is marked **verified** (executed) or
**not verified**.

## Local data had to be brought up to date first (not a code issue)

- The local DB still had both NBA 2026 drafts as `pre_draft`, while Sleeper says `complete`.
  The DB hadn't been ingested since before the 10-10 draft, so `season-start` answered
  `PRE_DRAFT`. Ran `POST /api/ingest/all/{id}` for both NBA 2026 leagues locally, and the
  state became `DRAFTED_UNSCORED`. The code was right for its inputs.
- Locally, `ingest/all` doesn't run the league-history ingest, so no week-1 pairings were
  stored and Next opponent said "Pairings for week 1 aren't out yet". Running
  `POST /api/ingest/league-history/{id}` stored the weeks 1–19 pairings (228 rows), after
  which it read "Week 1 against LeTikka Masala". On prod this is the refresh's job. Re-check
  it on prod (T048).
- The 529 NBA projection rows were already in the dev DB, fetched at 22:24 UTC, before this
  backend started. Most likely the build agent's first, unmocked `RefreshControllerDailyIT`
  run did a real ingest into the shared dev DB. That test is now mocked. A forced re-fetch
  was run by hand anyway (V1).

## V1 ingest: verified

The forced fetch returned `fetched:true, rowsStored:529, skippedNoStats:1591`. 529 + 1591 =
2120, the upstream row count. Source `rotowire`, `sourceModifiedAt 2026-10-11T11:50:12.719Z`.
A repeat call answers `fresh:true` with no fetch, and `sport=nfl` returns 400 with a sentence.

## V2 season state: verified

| League | State |
|---|---|
| NBA "Ball Knowers" 2026 | `DRAFTED_UNSCORED`, week 1, startDate 2026-10-20 |
| NBA "Test" 2026 | `DRAFTED_UNSCORED` |
| NFL "(Foot) Ball Knowers" 2026 | `IN_SEASON`, grades best A+ / worst F, `gradesEarly` |
| NFL "test" 2026 | `PRE_DRAFT` |
| NBA 2025 | `COMPLETE` |

## V3 hand rebuild: verified

Ranks 1, 2, 3 and 12 of "Ball Knowers" were rebuilt in Python, independently of the Java code,
from the stored per-game lines and Sleeper's live `scoring_settings`:

| Rank | Manager | API total | Hand sum |
|---|---|---|---|
| 1 | GraftonCarlson | 222.4 | 222.36 |
| 2 | ImReallyHarry | 219.8 | 219.73 |
| 3 | jstrobe | 216.4 | 216.37 |
| 12 | wags707 | 179.6 | 179.54 |

- The per-starter differences are only the 1-decimal display rounding.
- **108 of 108 starters are eligible** for their slot (checked against `player.positions`).
- No reserve (IR) player was started. wags707 has Giannis Antetokounmpo and Jimmy Butler on IR,
  listed at 0 and excluded.
- Ranks are strictly descending, from 222.4 down to 179.6.

**What the result says**: a projected three-way dead heat (222.4 / 219.8 / 216.4), with 4th at
213.6. The gap from the leaders to the next team is 4.1%, against the ARBITRARY 3% cut.

`startersWithoutGame` is 0 for every roster. The schedule grid confirms that all 30 NBA teams
play 2–4 games in week 1 (Oct 20–25), so the zero is real, not a missing-data artifact.

## V4 ordering test: verified by unit test

The SC-004 preference-ordering test and the IR-exclusion test run through the static core with
the real `BasketballRules` (backend suite green, 1627 tests, 0 skipped).

## V5 browser, league home: verified, with defects fixed after

Desktop 1280 and mobile 375, signed in as popsharky (a member, roster 1):

- **Correct**: the header reads "2026 · draft complete · first games Oct 20". The page shows
  the "Projected dead heat" headline with both totals, the top 3 plus "you" at 7 of 12, and the
  provenance, 15%-basis, not-projected and IR footer lines. It has one Next opponent block, the
  idle-starters line, and the Draft block. No 2025 matchup, power or awards block. No
  horizontal scroll at 375. A JS check found no probability words in `<main>`.
- **Defects found**: team names are truncated to 3–5 characters in the strength rows, and the
  "you" row shows the username instead of the team name. `RosterProjection` has no team name,
  and the per-row "pts/week (projected)" squeezes the name column.

## V5b browser, Analysis page (NBA): verified

- Per-week labels, the IR and unprojected badges, and the footer lines all show.
- The week strip and week picker are hidden. The real week 1 matchups show "projected ahead by"
  with the not-a-probability note, and head to head works.
- **Honesty observation, by design**: popsharky's lineup starts Kyrie Irving at 21.7 pts/game.
  His injury isn't reflected, because he isn't on IR. That's what "injuries aren't modelled"
  means, and the footer says so.

## V6 NFL parity: verified, with one difference that predates this branch

- `main` 943b303 was built in a separate worktree on :8081 and captured back to back with the
  branch on :8080, against the same DB.
- Two captures from `main` matched each other, so there was no live roster drift.
- Branch vs `main`, with the new fields removed: **one difference**. Roster 6's total read
  1156.7 on main and 1156.6 on the branch.
- **Root cause, measured**: the unrounded sum is exactly 1156.65. `LeagueSettings.dedicatedStarters()`
  is a `Map.of`, whose iteration order is salted per JVM. So the floating-point summation order,
  and therefore the rounding, changes between restarts. **`main` alone gave 1156.7, 1156.6,
  1156.6, 1156.7 across four fresh JVM starts.**
- Conclusion: this was there before this branch and isn't a parity break. The fix (stable
  slot order, or summing rounded values in a fixed order) would change football's numbers, so
  it's flagged separately rather than folded in here.

## Not verified yet

- V7, deleting the projections and checking the empty state, hasn't been run live. It's covered
  by unit tests only.
- T048 (prod) and T049 (after tip-off).

## After the code-review fix pass (code-review.md B1, B2, R2, R4–R7, N1–N7, plus team names)

**T043 full suites, final code:**
- Web: `tsc -b` clean, vitest **112 files / 1779 tests passed**, `npm run build` ok.
- Backend: **1639 tests, 2 failed, 1 skipped.** These three are **not caused by this branch**.
  `main` 943b303, built in a fresh worktree and run against the same dev DB, gives exactly the
  same 2 failures and 1 skip:
  - FAIL `PlayerTrendsReadIT.nba2026ReadsLastSeasonUntilItHasGames`
  - FAIL `BoardRepositoryLatestBeforeIT.theDraftRowCarriesTheScheduledStartTimeFromBothQueries`
  - SKIP `PlayerStatsLeaderboardReadIT.aSeasonThatFellBackReadsTheRequestedSeasonsDraftAndAdp`

  All three read **live league data from the shared dev DB** and assume the 2026 NBA draft
  hasn't happened yet. That stopped being true locally once these NBA 2026 leagues were
  re-ingested this session (draft `complete`, real start time 21:16 UTC). They're
  date-coupled ITs, a pre-existing fragility. Fixing them is out of scope here, but they
  will fail for anyone whose dev DB is current. The baseline before the re-ingest (T001)
  was 1574 / 0 / 0 / 0.

**V5 re-check in the browser, after restarting the backend:**
- Every strength row now carries the team name.
- "Projected points per week" is stated once as a caption, with a compact `pts/wk` per row.
- At 1280 px and at 375 px, 3 of the 4 names fit. "the Sheppard said Amen" plus the "you"
  pill still ellipsizes, and has a `title` with the full name. Recorded as a known residual.
- No horizontal scroll at 375 px, and no probability words in `<main>`.
- The headline now reads "222.4 pts/week (projected) at the top. Next-best roster: 213.6
  pts/week (projected)", with the unit coming from `strength.unit` (B1).

**R3 measured, not restructured:** `/season-start` is 44–55 ms locally on the in-season NFL
league (including the grades read) and 54–101 ms on NBA. `/history` is about 42–53 ms. That's
accepted.

**Process note:** an `./gradlew --stop` run in a throwaway worktree stopped every Gradle
daemon on the machine, including this session's bootRun. Any other session's Gradle build
running at that moment would have been stopped too. Don't use `--stop` on a shared machine.
The throwaway folders `.claude/worktrees/030-parity-base` and `030-main-check` are
unregistered from git but still locked on disk. Delete them once nothing holds them.

## After the injury-status change (2026-10-12): verified live

Rule: Out, IR and Sus count as 0 wherever they sit. DTD, GTD and untagged players count in
full, including on IR+ (plan.md, "Amended after live check"). Tags were refreshed locally
from Sleeper (`POST /api/ingest/players?sport=nba`) before measuring.

| Rank | Manager | Now | Before this change | Out → 0 | Starting from IR+ |
|---|---|---|---|---|---|
| 1 | ChedddaBob | 222.9 | #6 211.8 | Raynaud | Wiggins (DTD), Kessler (DTD) |
| 2 | GraftonCarlson | 222.4 | #1 222.4 | Nurkić | |
| 3 | ImReallyHarry | 219.8 | #2 219.8 | Ingram, Stewart | |
| 6 | popsharky | 214.5 | #7 208.3 | | Markkanen (GTD) |
| 11 | wags707 | 197.6 | #12 179.6 | Butler | **Giannis (DTD · Rest)** |

- **Zero Out-tagged starters** across all 12 rosters (checked programmatically).
- The favorite is still a projected dead heat: FentMachines5, The Flaggots and Amen,
  Halilujah! at 222.9, with LeTikka Masala next at 216.4. The gap is 3.0%, measured to the
  lower total. It sits just at the ARBITRARY 3% cut, so LeTikka is outside the leader group.
  This edge is real and moves with tiny projection changes, which is exactly what "dead
  heat" is meant to communicate.
- Browser, Analysis page: the new footer sentence is present, "N out" shows on the 6
  affected rosters, and none of the old "on IR" wording remains.
- Suites: backend 1643 tests, 2 failed + 1 skipped (the same three DB-coupled ITs as on
  `main`). Web: 1779/1779 passed, plus `tsc -b` and the build.
