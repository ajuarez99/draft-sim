# Verification: spec 037 (2026-10-15)

Everything below was **run** against this worktree's build unless a line says otherwise.

## Setup

- **Scratch DB.** Container `draftsim037-pg` on `localhost:5436` (named volume
  `draftsim037-pgdata`). It's a `pg_dump`/`pg_restore` copy of the shared 5433 DB: schema V34
  on both, 1748 `league_matchup` rows on both. The shared DB was only read.
- **Backend.** Worktree `bootRun` on **8087**, run with:
  - `DB_URL=…5436`
  - `ADMIN_TOKEN=local037`
  - `--refresh.on-visit.enabled=false`

  `/api/health` reported `weightsLoaded: true`.
- **Frontend.** Worktree vite on **5187**, proxied to 8087. Signed in as popsharky (identity
  only, via localStorage).
- **Ports 8081/5174** belonged to another session and weren't touched.
- **T001 (concurrent branches).** Only `adp-multi-source` touches a watched file
  (`config/weights.yml`, a different block). Expect a textual merge there, nothing semantic.
- **T002 (baseline).** No full baseline run was taken. The one non-rivalry failure the full
  suite shows was checked on untouched `origin/main` in a temporary detached worktree (since
  removed):
  - `BoardRepositoryLatestBeforeIT.theDraftRowCarriesTheScheduledStartTimeFromBothQueries`
    fails there identically (5 tests, 1 failed). It's **pre-existing**, not this change.

## Test suites (T022, after the fix pass)

| Suite | Result |
|---|---|
| `./gradlew test --tests "*Rivalry*"` | 127 tests, 0 failed, **0 skipped** (`RivalryParityIT` included, against Postgres) |
| `./gradlew test` (full) | 1846 tests, 1 failed (the pre-existing IT above), 1 skipped (`PlayerStatsLeaderboardReadIT`, a data-dependent `assumeTrue`, not a Rivalry test) |
| `npx tsc -b` | clean |
| `npx vitest run` | 125 files, 2147 tests passed |
| `npm run build` | OK |

`RivalryMatchingTest`'s pre-existing test methods are **unedited** and pass. That's the proof the
`allowed` overload didn't move spec 034's answer. `RivalryServiceWeekTest` also asserts that
`rec.pairings` is identical with and without the week pick.

## Live payload invariants (T023, quickstart §2)

The checks are in `check-week-pick.mjs`. It recounts **only** from `seasonGrid.cells`, and compares
offered weeks against Sleeper's **live** state (plan F8a): NFL `week = 6` at 2026-10-15 ~19:00Z.

| League | Status | Recommended | Reason (as served) | Result |
|---|---|---|---|---|
| "(Foot) Ball Knowers" 2026 | OK | **Week 13** | "3 of the best pairings would go over the 2-meeting limit in week 13, so they're swapped out. This set scores 12.75 of a possible 13.25." | **ALL CHECKS PASS** (8 weeks offered) |
| West Coast 2026 | OK | **Week 14** | "All 7 of the best pairings fit week 14 without any pair meeting more than 2 times." | PASS after re-ingest (see below) |
| NBA "Ball Knowers" 2026 | OK | **Week 8** | "…scores 22.167 of a possible 22.75. It's the earliest of 2 equally strong weeks." | Cap, shape, held-back and displaced checks all **pass** (18 weeks offered). Its 2 "eligibility" failures were mine (below). |
| fantasy😍 2026 | `NO_HISTORY` | n/a | n/a | `week == null`, as specified (F14) |

The script checked for every offered week:
- **Cap.** After substituting the week's pairings, no pair rises above 2.
- **Shape.** Everyone is paired exactly once.
- **Already scheduled.** `alreadyScheduled` matches the grid.
- **Held back.** Every `heldBack` pair is in the history-best set and isn't chosen. Its
  `wouldMeet` and `meetsInWeeks` match the grid. Every disallowed best pair is listed.
- **Displaced.** `displaced` equals the scheduled pairs minus the chosen ones, and the
  `metInWeeks`/`stillMeetsInWeeks` split is consistent with `asOfWeek`.

### Two eligibility failures, both explained, neither a logic bug

1. **West Coast: stale `leg`, exactly plan-review F8.**
   - The scratch copy had `leg = 4`, so weeks 5 and 6 were offered while Sleeper was on week 6.
   - The page said "as of week 4" (F8b working as intended).
   - I re-ingested West Coast into the **scratch** DB (`POST /api/ingest/all`, 19:03:47Z).
     `leg` became 6, weeks 5–6 dropped out, and **all checks passed**.
   - In prod, on-visit refresh keeps `leg` current. This run had it off on purpose.
2. **NBA: my check's reference was wrong.**
   - Sleeper's NBA state is `season_type: pre`, `week: 3`. That's the preseason calendar, not
     the fantasy week.
   - The fantasy season starts 10-20 (`leg = 1`), so weeks 2–3 haven't started, and the check
     shouldn't have flagged them.

**The re-check after tip-off is owed.** Once tip-off has happened, it should use the fantasy
`leg` from a refreshed league, not `/state/nba`.

### Measured numbers (T024)

| League | History-best pairs already at 2 meetings | Eligible weeks | Unfit | Recommended week's held back / kept as is | Tied |
|---|---|---|---|---|---|
| "(Foot) Ball Knowers" | **5 of 6** | 8 (7–14) | 0 | 3 held back, 2 of 6 kept | 1 (outright) |
| West Coast | **1 of 7** | 8 (7–14, after re-ingest) | 0 | 0 held back, 7 of 7 kept | 1 (weeks 7–13 tie on score at 9.9 but keep only 5 of 7, so F2's second key separates them) |
| NBA "Ball Knowers" | **4 of 6** | 18 (2–19) | 0 | 3 held back, 1 of 6 kept | 2 (weeks 8 and 19; 8 is earlier) |

- **Research R2's labelled guess, checked.** It said "football loses none in most weeks". That
  was **wrong** for "(Foot) Ball Knowers": the recommended week holds back 3, and the other
  weeks hold back 2–5. Plan-review F3 already corrected it visibly in research.md. These numbers
  are from the Java path and agree with that correction.
- **West Coast is the exception.** Its best set fits week 14 intact. Without F2's tie-break,
  "earliest" would have picked week 7, which keeps only 5 of the 7.
- **These numbers differ from plan-review's.** That review measured "(Foot) Ball Knowers" at
  16.917/17.25, against a different DB (034's scratch on 5434). Here it's 12.75/13.25, from
  today's 5433 copy. Same recommended week (13). The inputs differ, so the totals differ.
  Nothing was retuned.
- **Tie-break.** The F2 key decided West Coast. The plan's original R7 fear, "earliest = next
  week", didn't occur in any league.

**Latency and size** (second call, warm):

| League | Latency | Payload |
|---|---|---|
| fantasy😍 | 98 ms | 78 KB |
| "(Foot) Ball Knowers" | 106 ms | 95 KB |
| NBA | 108 ms | 139 KB |
| West Coast | 104 ms | 108 KB |

These are inside spec 034's measured 25–250 ms. **Payload size before this change wasn't
measured**, so the increase isn't stated.

## Edge statuses (quickstart §3)

| Case | Result |
|---|---|
| "(Foot) Ball Knowers" 2025, NBA 2024 (no predecessor) | `recommendation.status = NO_HISTORY`, `week = null` |
| NBA "Ball Knowers" 2025 (complete, has history) | `recommendation.status = OK`, `week.status = NO_ELIGIBLE_WEEK`, spec 034's 6 pairings still sent |
| Backend on 8088 with a `weights.yml` copy lacking `max-regular-season-meetings` | `recommendation.status = OK`, spec 034's 6 pairings intact, `week.status = CONFIG_MISSING`, `cap = null` (F7: the missing cap no longer blanks 034's answer). 8088 was stopped afterwards. |
| Anonymous caller | 404, unchanged |

Statuses that are **not** exercised live, because no real league is in that state:
`NOT_PUBLISHED`, `UNSUPPORTED_ROSTERS`, `NO_SEASON_FORMAT`, `NO_WEEK_FITS`, and an over-cap
pair. Each has unit tests in `RivalryServiceWeekTest` and `RivalryWeekPickerTest`.

## Browser (quickstart §4)

The page was `/leagues/{id}/rivalries` on 5187, against the 8087 backend.

**"(Foot) Ball Knowers" 2026, desktop pane width.** These all render and match the payload:
- "Recommended: Week 13" and "as of week 6"
- the reason sentence
- the rule line "Nobody plays the same manager more than 2 times in the regular season (league
  rule)."
- the thin headline, counted on the **shown** set (4 of 6)
- 4 "already on the schedule" tags
- three held-back sentences, e.g. "Master Bates and Khatt Stafford already meet in weeks 3 and
  14, so they'd play a third time."
- "What this replaces", listing 2 displaced matchups, each "already met in week 2"
- "Other weeks", expanding to week 12 ("11 of 13.25 · 5 held back") with its own pairings
- the "doesn't change your Sleeper schedule" note, once

**NBA "Ball Knowers" 2026 at 375 px.**
- "Recommended: Week 8", "as of week 1", and the tie clause "It's the earliest of 2 equally
  strong weeks."
- `document.documentElement.scrollWidth == 375`, with no element in the section overflowing
- no console errors

Screenshots were taken in the browser pane during the session. They aren't saved as files.

## Not verified

- **Prod.** Nothing is deployed.
- **The split-deploy guard** (new web against an old backend) is covered only by the vitest
  case with `week` absent, not by a real mixed deploy.
- **NBA eligibility** against a real post-tip-off fantasy week (owed after 10-20).
