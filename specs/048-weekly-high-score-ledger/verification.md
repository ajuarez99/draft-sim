# Verification: spec 048 (weekly high-score pot)

Live run on 2026-10-26 against the real server built from this worktree (`048-api` on 8148,
`048-web` on 5248). The java process's classpath was checked first:
`048-weekly-high-score-ledger\backend\build\classes\java\main`. Postgres was the shared local one
on 5433 (`draftsim-pg`). Before the checks I re-ingested "(Foot) Ball Knowers" 2026 and "Ball
Knowers" NBA 2026 history with `POST /api/ingest/league-history/{id}`, which brought NFL 2026 up to
week 6 stored.

Everything below was **executed**. Anything not executed is listed at the end under "Not verified".

## Test suites

| Run | Result |
|---|---|
| Full backend `./gradlew test` (after the fix pass) | 2471 tests, **6 skipped, 1 failure** |
| `WeeklyPotControllerIT` | 11 tests, **0 skipped**, 0 failures |
| `WeeklyPotLedgerTest` | 9 tests, incl. a 200-season seeded property loop |
| `WeeklyPotServiceTest` | 18 tests |
| `WeeklyReport*` (4 suites, T005 refactor guard) | 46 tests, all pass |
| `npx tsc -b`, `npm run build` | clean (only the existing chunk-size warning) |
| vitest, `WeeklyPot` / `destinations` / `LeagueRailSection` | 112 passed |

- **The 6 skips** are all in `PlayerStatsLeaderboardReadIT`, as already known.
- **The 1 failure** is `HallOfFameServiceIT.nameLastPlaceOnlyWhenTheRuleCouldReadIt`, which is **local data, not
  048**. League `1262506916429430784` (West Coast 2025) has 7 winners-bracket rows and 0 losers-bracket
  rows on 5433, because spec 047's bracket backfill was never run locally. Measured by SQL. Nothing in 048
  touches brackets.
- The full vitest suite ran once (2554 passed, 1 failed). That failure was the rail test's hard-coded row list,
  which was then fixed. After the fix only the three affected files were re-run, not the whole suite.

## Quickstart scenarios

| # | Scenario | Result |
|---|---|---|
| 1 | Off by default | `GET` as member `xonicboom` → `{season, sport, enabled:false, stakeCents:100, canEdit:false, commissionerKnown:true}`; no ledger keys. The page shows only the explanation, no pot amount. **PASS** |
| – | Stranger | 404. **PASS** |
| 2 | Non-commissioner PUT | 403 `{"message":"only this league's Sleeper commissioner may change the weekly pot","commissionerKnown":true}`. **PASS** |
| – | Bad bodies | missing stake, `0`, `100001`, `150.7`, `"yes"` → each 400 with a message. **PASS** |
| 3 | Commissioner turns on | 200 with the full ledger. Also done through the UI (checkbox + Save) on NBA 2026. **PASS** |
| 4 | Winners match (SC-001) | SQL `rank() … = 1` against `weeks[].winners` (detail below). **PASS** |
| 5 | Zero-sum (SC-002) | Σ net = 0 on all three seasons. **PASS** |
| 6 | Settle-up clears (SC-003) | Both lists clear every balance on all three seasons; shortcut ≤ pairwise; the shortcut only pays from a debtor to a creditor (detail below). **PASS** |
| 7 | $2 stake | Every balance exactly doubled. There were no ties in this season, so the tied-week rule was covered by unit tests only. Off at $2, then back on, restores the stake. **PASS** |
| 8 | Regular season only | NFL 2025: weeks 1–14 counted, of 17 stored (playoffs start week 15). NBA 2025: 18 counted, of 21 stored (start 19). Both `seasonFinal: true`. **PASS** |
| 9 | Live leader (US4) | Two checks against Sleeper's own matchups (detail below). **PASS** |
| 10 | UI, desktop | Rail entry "Weekly pot" lit; standings table with tinted net pills; viewer row highlighted from `isMe`; competition ranks (1, 2, 2, 2, 2, 6…); week list; settle-up tabs; no console errors. **PASS** |
| 10 | UI, 375px | Standings collapse to rank/name/net with wins under the name; `scrollWidth` 375 = viewport (no sideways scroll); "Every pair" shows 11 payer groups, collapsed by default, 39 rows. **PASS** |
| 11 | ESPN refusal | `WeeklyPotControllerIT` only: 422 `PLATFORM_NOT_SUPPORTED` on GET and PUT. No local ESPN league was opened in the browser. **PASS (IT)** |
| 12 | ITs actually ran | 0 skipped in `WeeklyPotControllerIT`. **PASS** |

**Scenario 4 detail.** Every DECIDED week matched, with no mismatches and no ties:

- "(Foot) Ball Knowers" 2026: weeks 1–6;
- NFL 2025: 14 weeks;
- NBA 2025: 18 weeks.

**Scenario 6 detail.** Shortcut against pairwise, by number of payments:

- NFL 2026: 7 vs 39;
- NFL 2025: 10 vs 47;
- NBA 2025: 7 vs 54.

**Scenario 9 detail.**

- **NFL week 7** (in progress): page says Khatt Stafford (roster 4), 140.2. Sleeper `/matchups/7` top is roster 4 at
  140.2, then 140.08.
- **NBA week 1** (in progress, not stored): roster 2 at 272.5. Sleeper `/matchups/1` top is roster 2 at 272.5. The
  first view after a cold cache showed no line (`peek`, plan A3) and the next one did, as designed.

**FR-005.** With the pot on for NBA 2026 before any decided week, all 12 teams are listed at $0.00, with "No week is
decided yet."

## "To measure" items from research and review

- **R3, playoff start.** Every Sleeper season on 5433 records `playoff_week_start` (FBK 15/15, NBA 22/19/20, West
  Coast 15/15, fantasy 15). The "playoffs start isn't recorded" path is not reached by any real league today.
- **F13, commissioners.** Every one of those seasons has at least one `is_commissioner` member.

## Corrections made during verification

- **A wrong bug report, retracted.** I reported "Save stuck on Saving…", and it was wrong. The page text had been read
  mid-request, and re-reading showed "Save". Separately, the browser tool's `form_input` doesn't flip React's
  controlled checkbox, so my first two saves sent `enabled:false`. A real click worked.
- **A launch.json escape bug.** The entry I added had a `\b` JSON escape in the backend path, so the first start
  failed. Fixed. The 048 entries live in the main checkout's `.claude/launch.json` (already dirty there), like the
  other worktree entries.

## Not verified

- **The tied-week cent split on real data.** No real season has a top-score tie, so it is covered by
  `WeeklyPotLedgerTest` (3-way at 100¢ and 200¢) and `WeeklyPotControllerIT` only.
- **`NOT_FINAL` / `MISSING` weeks on real data.** Neither occurred today; covered by `WeeklyPotServiceTest`.
- **ESPN in a browser.** Covered by the IT only.
- **Leagues whose `start_week` > 1** (code-review S2, deferred). Every local league starts in week 1, and what
  Sleeper returns before `start_week` is unmeasured.
- **Production.** Nothing is deployed.

## Shared-DB footprint

- Migration **V41** is now applied to the shared 5433 database. An earlier test run applied it.
- The test pot rows I inserted (league ids 4, 5, 210, 211) were deleted afterwards. The NFL and NBA 2026
  history re-ingest wrote fresher weekly rows, which is the normal idempotent ingest.
