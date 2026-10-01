# Build notes: spec 013

Measured values recorded during the build. Each entry says whether it was
**measured** (run or observed) or **chosen** (a hand-set value, with reason).

## T001 baseline (measured, 2026-09-30, worktree @ 5b3f446)

| Suite | Result |
|---|---|
| `web`: `npx tsc -b` | OK |
| `web`: `npx vitest run` | 66 files, 806 tests, 806 passed |
| `web`: `npm run build` | OK, main chunk 442.02 kB (132.91 kB gzip) |
| `backend`: `./gradlew test` | 945 tests, 0 failures, 0 errors, **0 skipped** (Postgres on 5433 was up) |

## T002 data (measured)

Explicit re-ingest was refused locally (`403 admin_token_required`; `ADMIN_TOKEN` is blank in
local dev by design). Not needed: the local DB already matches production for the NFL league:
expected-wins `weeksScored: 3`, weekly-report default `week: 2` with `latestScoredWeek: 3`
(identical to prod, research R2). NFL and NBA history both have 12-row standings for 2026.
Unlike prod, the local NFL league *has* a stored season forecast.

## T004 baseline depth (measured, harness `measure.js`, 1440×900, before any change)

`depth` = worst count of bordered/filled containers above a text leaf, outside the board.
`boardDepth` = same, inside the draft board. Harness limits are under review (adversarial review, quickstart §4a).

| Route | depth | worst at | boardDepth | h-scroll |
|---|---|---|---|---|
| / | 3 | "Draft complete" | – | no |
| /managers | 2 | manager row | – | no |
| /mock/new | 2 | seat summary | – | no |
| /mock/653 | 3 | pick feed "2.11" | 3 | no |
| /drafts/{nba} (sim, pre-start) | 0 | – | 3 | no |
| /drafts/{nba}/live | 2 | "Waiting for the draft to start" | 3 | no |
| /drafts/{nfl}/board | 1 | "Draft board" | 2 | no |
| NFL history | 3 | a PF cell | – | no |
| NFL power | 2 | ladder header | – | no |
| NFL analysis | 2 | team row | – | no |
| NFL roster-management | 3 | "left on the bench" | – | no |
| NFL expected-wins | 3 | luck value | – | no |
| NFL forecast | 3 | win range | – | no |
| NFL weekly-report | 3 | matchup team | – | no |
| NFL superlatives | 1 | section header | – | no |
| NBA history | 3 | a PF cell | – | no |
| NBA power | 1 | "The ladder" | – | no |
| NBA weekly-report | 3 | matchup team | – | no |
| NBA superlatives | 2 | season label | – | no |

## T004 re-baseline with the amended harness (measured, 1440×900, before any change)

The harness was amended after adversarial review (B4). **The table above used the old
harness and is superseded by this one.**

| Route | depth | worst at | gridDepth | h-scroll |
|---|---|---|---|---|
| / | 3 | league card status line | 0 | no |
| /managers | 1 | intro text | 0 | no |
| /mock/new | 2 | seat chip | 0 | no |
| /mock/653 | 2 | "You" seat | 1 | no |
| NBA sim (pre-start) | 2 | "▾ Players" | 1 | no |
| NBA live | 2 | availability heading | 2 | no |
| NFL completed board | 1 | header | 1 | no |
| NFL history | 3 | a manager name | 1 | no |
| NFL power | 2 | segmented control | 0 | no |
| NFL analysis | 3 | a disclosure | 1 | no |
| NFL roster-management | 2 | week chip | 1 | no |
| NFL expected-wins | 2 | footnote | 1 | no |
| NFL forecast | 1 | header line | 1 | no |
| NFL weekly-report | 2 | matchup team | 0 | no |
| NFL superlatives | 1 | section header | 0 | no |
| NBA history | 3 | a manager name | 1 | no |
| NBA power | 2 | segmented control | 0 | no |
| NBA weekly-report | 2 | season label | 0 | no |
| NBA superlatives | 2 | season label | 0 | no |
| NBA roster-management | 2 | season label | 1 | no |

**Harness limitation (measured, stated so it isn't over-read):** the harness measures
*nesting* only. Superlatives scores 1, yet it is the most boxed page: 13 sibling
cards in a grid. SC-001 can't see sibling-box density, so "fewer boxes" on card
grids is judged per page against the design review (US9's trophy list), not by
this number.


## Phase 2 (Foundational) — build agent

T005–T013 done. All ratios below are **measured**, computed from OKLCH math
(OKLab matrices to linear sRGB, channel clamp, WCAG 2 relative luminance), not read
from a browser. Out-of-gamut colors are clamped, so a browser's own gamut mapping
could differ slightly. Tuned lightness only; chroma and hue unchanged.

| Token | Final value | Measured ratio |
|---|---|---|
| `--bg` | `oklch(14% 0.025 255)` | `--text` on it 17.21:1 |
| `--panel` | `oklch(19% 0.03 255)` | `--text` on it 15.96:1 |
| `--volt` | `oklch(88% 0.19 125)` | `--bg` text on it 14.44:1 |
| `--up` | `oklch(74% 0.16 150)` | 8.52:1 on `--panel` |
| `--down` | `oklch(68% 0.18 350)` (start value kept) | 5.85:1 on `--panel` |
| `--row-hover` | `oklch(22% 0.03 255)` | n/a (hover tint) |
| `--crimson-fill` | `oklch(54% 0.19 25)` (was 58%: 4.07:1) | `--text` on it 4.83:1 |
| `--crimson-text` | `oklch(64% 0.19 25)` | 5.41:1 on `--bg`, 5.02:1 on `--panel` |
| `--crimson` (unchanged) | `oklch(58% 0.19 25)` | text on `--panel` 3.92:1; so ring/non-text only |

Lightness sweeps: crimson-fill passes at 54% (4.83) and fails at 56% (4.43);
crimson-text at 62% gives 4.63 on `--panel` (passes), 64% chosen for margin;
`--down` at 62% would already pass (4.61).

**Position-tint cell** (22% of each position token, `color-mix` over the new
`--panel`, approximated by OKLCH interpolation, `--text` on top): QB 12.04, RB 11.76,
WR 12.04, TE 12.12, K 12.14, DEF 12.78, PG 11.93, SG 11.97, SF 12.01, PF 12.06,
C 12.09. **Minimum 11.76:1 (RB).** The header's old "22% clears 12:1" claim was
therefore slightly off for the new panel; it now says ">= 11.7:1 for every position
token (measured, OKLCH math)".

### What was applied (T006)
- Crimson text to `--crimson-text`: `.rank-grid-rank.mine`, `.board .col-head-you`,
  `.seat .who.mine-name`, `.on-clock.mine .on-clock-kicker`, `.bump-end-label-me`
  (SVG fill), `.pr-your-team-label`, `.pr-rank.mine`, `.pr-you-tag`.
- Crimson fills behind text to `--crimson-fill` (with `--text` instead of `--bg`):
  `.pause-banner .chip.on`, `.mock-seat.mine .mock-seat-face`. The 16%/22% tint on
  `.board .col-head.mine` now mixes from `--crimson-fill` (it is a tint, not solid).
- Better/worse remapped crimson to `--down` (beyond the one line named in the task,
  same hue-collision reason, S15): `.rank-move.down`, `.pr-move.down`,
  `.pr-your-ballot .pr-move.down` tint, `.pr-homer-fill.down`, `.homers-fill.neg`.
  The four hard-coded `oklch(72% 0.14 150)` "up" greens now use `var(--up)`
  (`.rank-move.up`, `.pr-move.up`, `.homers-fill.pos`, `.pr-homer-fill.up`).
- **Left alone (error colors, as instructed):** `.track-note.failed`,
  `.live-fresh.stale`, `.live-waiting.offline`, `.onbrand-verdict.off`. Also left
  `--crimson` for rings, bars (`.pr-bar-fill.mine`), tints and borders.
- Note: `--down` (hue 350) is 9 degrees from the NBA center token `--c` (hue 359);
  they live in different contexts (position vs. delta) but never get a dedicated
  check. Flag for the live pass.

### T007 `.mono` / `.code`
`.mono` is now `font-family: inherit; tabular-nums`. `.code` carries the old
monospace stack. Switched to `.code`: `LeagueAnalysis.tsx:145` (the ffwrapped
formula `<code>`), `PowerRankings.verify.tsx:223` (`row.id`). Every other of the
~125 `.mono` uses is a number. Board header fit at 1440 was **not** checked in a
browser here (no live pass in this phase); `.col-head-name` already has an
ellipsis rule and `.mono` is no longer wider, so the risk went down, not up.

### T008 section titles
`.section-title` added; `.panel h2` uses the same declarations (Plus Jakarta 600,
15px, sentence case, `--text`). `h2/h3/h4.cond` to `.section-title` in:
`components/PlayerCard.tsx`, `pages/DraftView.tsx` (the "Simulation settings" label
only), `ExpectedWins.tsx`, `LeagueHistory.tsx`, `PowerRankings.tsx` (Riser / Free
fall / Nobody agrees), `PowerRankings.verify.tsx`, `RosterManagement.tsx`,
`SeasonForecast.tsx`, `Superlatives.tsx`, `WeeklyReport.tsx`. Kept `.cond` (titles
and heroes, not sections): `PageHeader` h1, `PowerRankings` hero headline,
`StartMockModal` title, `DraftView` "Ready when you are" overlay, `LiveDraftView`
waiting title. The task's "~70 sites" estimate was an overcount for headings: 38
heading elements existed. No uppercase literals needed case changes (all were
already sentence case).
Oswald rules reviewed: kept `.power-eyebrow`, `.power-title`, `.home-hero-title`,
`.page-eyebrow.accent` (titles/heroes). Switched off Oswald: `.power-tab`,
`.league-data-summary span:first-child` (now sentence-case section title look),
`.rank-grid-header`, `.pr-row-head`, `.pr-focus-row.head` (small column headers now
Plus Jakarta, still uppercase). `.loading-screen .cond` and
`.start-overlay-status .cond` left (state hero text).

### T009 / T010 / T012
`.section`, `.row-list`, `.panel .panel` reset added. `PowerRankings.verify.tsx`
nests three `.verify-state-card.panel` inside a `.panel` section; they now render
as unboxed text blocks (flattened, still readable; it is a dev-only harness page).
`HowThisWorks.tsx` + `.how` styles added. `PageHeader` documents `sub` and
`console.warn`s in dev for forbidden words.

### T013
House-style header rewritten from contracts/ui-rules.md; STACKING and LAYOUT kept;
position-color system and CONTROL HIERARCHY kept too (still load-bearing); DENSITY
and SHAPE/ELEVATION folded into SURFACES.

### Results
From `web/`: `npx tsc -b` clean; `npx vitest run` 67 files, **808 passed, 0 failed**
(includes the 2 new HowThisWorks tests); `npm run build` succeeded. Not done: no
visual spot check of home/board in a browser (checkpoint asks for one).

## Backend fields — build agent

All paths under `backend/src/main/java/com/ballknowers/draftsim/`. Every new key is present on the wire (null where the contract says nullable), built with mutable `LinkedHashMap` puts, never `Map.of`. `web/src/api.ts` mirrors each one as an optional field with a comment naming its Java source.

| Field | Computed at |
|---|---|
| history `canCommission` | `api/LeagueHistoryController.java:162` — `membership.canCommission(visibleLeague.id(), X-Sleeper-User)` |
| history `StandingRow.isMe` | `api/LeagueHistoryController.java:138` — caller's manager id (`managers.idsBySleeperUserId()`) equals the row's `managerId`; false when signed out |
| weekly `WeeklySide.isMe` | `engine/WeeklyReportService.java` (`myRosters` set, built in the `rosterSeasons.forLeague` loop; new `forWeek(.., sleeperUserId)` overload); emitted `api/WeeklyReportController.java:183` |
| `RealPick.adpAtDraft` | `api/LeagueController.java:356` (`PickNaming.row`, from `PickRow.adpAtTime()` = `draft_pick.adp_at_time`). Also rides the live-stream pick rows, which share that method (additive) |
| expected-wins `allPlay`, `median` | `engine/ExpectedWinsService.java` `allPlay(..)` / `median(..)`, same `scoresByWeek` walk as `expectedWins`; emitted `api/ExpectedWinsController.java:88` |
| roster-management `grade`, `gradesEarly` | grade in `engine/RosterManagementService.java` (rank by efficiency, null efficiency unranked); `gradesEarly` in `api/RosterManagementController.java:74` |
| analysis `entries[].grade`, `rankingScores.gradesEarly` | `engine/LeagueAnalysisService.java` `normalise(..)` (rank by composite score) and `rankingScores(..)` (:342, :377) |
| `/api/health` `gradesLoaded` | `api/HealthController.java` (`GradeProperties.loaded()`) |
| single early threshold | `engine/SeasonWindow.java` (`EARLY_THRESHOLD_WEEKS = 4`, `isEarly`); `SeasonSuperlativesService`'s own constant deleted |

### Grade cutoffs (`config/weights.yml`, `draftsim.grades.cutoffs`)

Labelled "ARBITRARY: not fitted to anything". Percentile = (rank-1)/(teams-1)*100, 0 = best; a team takes the first entry whose `maxPercentile` >= its percentile.

A+ 8, A 17, A- 25, B+ 33, B 42, B- 50, C+ 58, C 67, C- 75, D 92, F 100.

Reason: a roughly even ladder (about 8 percentile points per step through C-), with D wide and F reserved for the last place in a 12-team league (percentile 100; second-to-last is 90.9 and gets a D). Chosen once, to look like a school ladder, not tuned against any measured number. On 12 teams this gives A+, A, A-, B+, B, B-, C+, C, C-, D, D, F.

Config class `config/GradeProperties.java` (registered in `DraftSimApplication`): a missing block binds to an empty list (all grades null, startup succeeds); a present block is validated in the record's constructor (strictly increasing, last = 100, non-blank grade) and a bad one fails startup.

### Tests

`./gradlew test` (backend): **964 tests, 0 failures, 0 errors, 0 skipped** (Postgres on 5433 reachable, so the ITs ran). `npx tsc -b` in `web/`: clean. New tests: `LeagueHistoryCanCommissionIT` (3), `WeeklyReportShapeTest` (+1), `SeasonWindowSingleSourceTest`, `LetterGradesTest` (6), `LeagueAnalysisServiceTest` (+3), `LeagueAnalyticsContractTest` (+1, existing ones extended), `LeagueControllerRealBoardTest` (+2), `ExpectedWinsServiceTest` (+2).

One observation, not investigated: `RefreshControllerIT.aChainRunShowsRunningAtTheTopEvenWhenTheShownSeasonIsLoadedComplete` failed in the first full run and passed in the next two, with no change to that area. It is known to be unstable on the base branch too; treat a one-off failure there as unrelated.

### Deviations from tasks.md

- T031: `canCommission` uses the `visibleLeague` row's id (as the task says), not the chain head's.
- T043: the `WeeklySide` change is in `WeeklyReportService.Side` (a new `isMe` component) plus a new `forWeek(sleeperId, week, sleeperUserId)` overload (the old two-arg form delegates with null). `WeeklyReportService` gained a `ManagerRepository` constructor parameter. The shape test covers the controller map only; the manager-id matching itself is not covered by a DB test for the weekly report (the history IT covers the same rule on standings).
- T032: no-header case is asserted as 404 from the scoping gate (a signed-out caller never gets a body to carry `canCommission`), plus `membership.canCommission(.., null) == false`.
- T074/T075: `gradesEarly` on roster-management is emitted on the unavailable shape too (weeksScored 0, so true). Constructors of `RosterManagementService` and `LeagueAnalysisService` gained a `LetterGrades` parameter. `LeagueAnalysisService.rankingScores` and `normalise` became package-private for tests.
- T076: the analysis service's 3-week/4-week check uses Mockito mocks of the two repositories (the gate is reached with an empty league); roster-management's is asserted at the controller.
- Existing positional constructors fixed: `LeagueAnalyticsContractTest` (RosterManagement `TeamRow` x3, ExpectedWins `TeamRow` x2), `SeasonSuperlativesLuckTest` and `SeasonSuperlativesStandingsTotalsTest` (ExpectedWins `TeamRow`). `LeagueAnalysisServiceTest` had no `ScoreEntry` constructions to fix.

### Unverified

Not run against a live server: no HTTP call to any of these endpoints, and no real-league data (the real standings/weekly report/grades values are unchecked). `/api/health` `gradesLoaded` is not asserted by a test (HealthController has none); the shipped `weights.yml` block is verified only by binding it through Spring's `Binder` in `LetterGradesTest`.

## Backend fields: live verification by the parent session (measured, local bk013-api, 2026-09-30)

- `/api/health`: `weightsLoaded: true, gradesLoaded: true`.
- History: `canCommission: true` for popsharky on NFL and NBA; exactly one `isMe` standings row (popsharky, 2-1).
- Weekly report week 0 (→ week 2): exactly one `WeeklySide.isMe`.
- Team strength (NFL): grades A+ … F strictly in composite-score order, `gradesEarly: true` (3 scored weeks).
- Bench points (NFL): grades follow efficiency order exactly; `gradesEarly: true`. **Observation:** the middle
  of the league is tight (0.934 → A-, 0.918 → B-). Rank-based grading (clarification Q5) turns small gaps into
  letter steps. The early badge is on now. Revisit with Allan once a full season is in, and don't tune the cutoffs to hide it.
- Expected wins: Σ all-play wins = Σ losses = 198. All-play records **match ffwrapped's "Record vs all" exactly**
  (Master Bates 28-5, jpelwell 22-11, Hunter? 8-25). **Median differs by definition:** ffwrapped's "Median
  record" adds the median games to the real record (Master Bates 6-0); ours is median games only (3-0). The UI
  label must say "vs weekly median" so a reader comparing the two sites isn't misled (T084).
- Real board: `adpAtDraft` is present on 180/180 picks of the 2026 NFL draft and differs from today's ADP
  (pick 2: today 2.0, at draft 4.0). This confirms review B1 was a real bug in the plan.

## US1 part A: parent review (2026-09-30)

- **Rejected and fixed:** the Luck page shipped a frontend copy of the 4-week threshold (`EARLY_WEEKS`).
  That's a second implementation of `SeasonWindow.EARLY_THRESHOLD_WEEKS`. The expected-wins response now
  carries `early` (computed server-side). The page reads it, and a missing flag counts as early, so an old
  backend never names a "luckiest" team. Two tests added (`ExpectedWins.test.tsx`).
- Browser (1440, local): home shows the `--volt` CTA and sentence-case sections; Power rankings hero, callouts
  and ladder unboxed; Luck shows the neutral subtitle plus the early badge at 3 weeks. Full matrix still owed (T030).

## Write-control audit (T035, 2026-09-30)

Every non-GET call in `web/src/api.ts`, with the control that fires it and what each side checks. Read from the
source (controllers under `backend/src/main/java/com/ballknowers/draftsim/`), **not executed against a running
server**: this table is "read", not "verified". "Sleeper commissioner" below is `LeagueMembership.canCommission`
(the `is_owner` flag, or the configured app owner). Sign-in here is a typed username (`X-Sleeper-User`), not auth,
so any gate that rests on that header alone is a visibility gate, not a security one.

Shared/league-level writes:

| api.ts call (line) | Fired from (UI file:line) | UI gate | Server gate (controller) | Gate kind |
|---|---|---|---|---|
| `computePowerRankings` (1534) | `PowerRankings.tsx:931` (Recompute); `SeasonForecast.tsx:278` (Recompute) | `ballot?.canCommission` (`PowerRankings.tsx:927`); `data.canCommission` (`SeasonForecast.tsx:271`) | `LeagueHistoryController.compute` (~869): league visible, **admin token** (`isAdminRequest`), then `canCommission` | admin token + commissioner |
| `saveCommissionerRanking` (1554) | `PowerRankings.tsx:544` | `editingCommissioner` = `ballot?.canCommission` (`PowerRankings.tsx:729`) | `LeagueHistoryController.commissioner` (~960): league visible, **admin token**, `canCommission`, current week only | admin token + commissioner |
| `saveConductEntry` (2246) | `Superlatives.tsx:705` | `canEdit` = `list?.canEdit` (`Superlatives.tsx:730`, form at 779) | `SuperlativesController.saveConductEntry` (83): league visible, **admin token**, `canCommission` | admin token + commissioner |
| `deleteConductEntry` (2254) | `Superlatives.tsx:722` | `canEdit` (`Superlatives.tsx:730`, button at 765) | `SuperlativesController.deleteConductEntry` (118): league visible, **admin token**, `canCommission` | admin token + commissioner |
| `backfillFinalRanks` (959) | `LeagueHistory.tsx:449` via the Compute button | `canCommission === true` (`LeagueHistory.tsx:327`, added by T033) | `LeagueHistoryController.backfillFinalRanks` (828): `membership.visibleLeague` only. **No admin token, no commissioner check.** | **visibility only** |
| `setReversalRound` (559) | `DraftView.tsx:412` (settings popover select at 746) | none: shown to anyone who can open the draft room | `LeagueController.setReversalRound` (231): `membership.visibleDraft` only; 409 on a complete draft; value range-checked. **No admin token, no commissioner check.** | **visibility only** (open follow-up, out of scope, review S2) |
| `trackDraft` (438) | `DraftPicker.tsx:197`; `LiveStatusBar.tsx:59` | none (any member's Track button) | `LeagueController.track` (394): `membership.visibleDraft`; starts or confirms the shared live poller | visibility only |
| `ingestLeague` (449, `/api/setup/league`) | `DraftPicker.tsx:41` and `:226` (setup stages, Add a draft) | none beyond signed-in | `MemberSetupController.league` (78): `requireLeagueMember` (signed in AND in our DB, or on Sleeper's league-users list for that league) | membership |
| `ingestLeagueHistory` (488, `/api/setup/league-history`) | `LeagueHistory.tsx:429` ("Load past seasons" on the error state) | none: shown on the error banner to any visitor | `MemberSetupController.leagueHistory` (88): `requireLeagueMember`; re-ingests the whole chain | membership |
| `ingestAdp` (478, `/api/setup/adp`) | `DraftPicker.tsx:42` | none | `MemberSetupController.adp` (99): signed in and a member of at least one league (`requireKnownMember`); rebuilds shared ADP for a sport | any known member |
| `ingestBoard` (481, `/api/setup/board`) | `DraftPicker.tsx:43` | none | `MemberSetupController.board` (108): same as `ingestAdp`; rebuilds the shared board | any known member |
| `refreshPlayers` (475) | `DraftPicker.tsx:40` | none | `RefreshController.players` (81): signed in only (`X-Sleeper-User` present); bounded to once per sport per UTC day in steady state | any signed-in caller |
| `refreshLeague` (2192) | `DraftPicker.tsx:48`; `LeagueRailSection.tsx:89` (refresh on visit) | none | `RefreshController.trigger` (104): `membership.visibleLeague`; starts a background refresh only if the league is stale | visibility only |

Writes scoped to the caller's own data (listed for completeness, none writes league-level data):

| api.ts call (line) | Server gate | Note |
|---|---|---|
| `submitBallot` (1697) | signed in, league member, current week only (`LeagueHistoryController.submitBallot` 758) | one ballot per member |
| `setTendencies` / `clearTendencies` (542 / 548) | `canSeeManager`; the note is keyed to the caller's id (`ManagerController` 79, 111) | private note |
| `createMockSession` / `createMockSessionFromDraft` / `submitMockPick` (717 / 737 / 747) | signed in to create; a session is its owner's, 404 for anyone else (`MockDraftController`) | own session |
| `/api/sims/stream` POST (1566) | draft visible (`requireVisible`) plus per-user permits (`SimulationController`) | read-shaped compute, no stored league data |

Findings from the audit (not fixed here):
- `backfillFinalRanks` is the only call T033 hides in the UI whose server side does **not** require the commissioner: any
  member who can see the league can POST it. After 013 a regular member no longer sees the button, but the route still
  accepts them. It writes only the derived final-rank snapshot (idempotent, `power/backfill` makes no Sleeper call), so the
  worst case is a member forcing a recompute, but the UI gate is not a server gate. Open follow-up; the tasks say the
  backfill server gate is unchanged in this spec.
- `setReversalRound`: **open follow-up (out of scope, review S2).** Any visitor to a draft room can change the snake
  reversal round for every viewer of an unfinished draft. Unchanged by this spec.
- `/api/ingest/**` (the operator routes) sit behind `AdminGateInterceptor` and are not reachable from the web client.

## US1 part B: parent review (2026-09-30)

- **Reverted:** the build agent flattened the board cell's solid position badge (`.pos`, "RB4") to plain text
  and dropped the "second choice" dotted underline to pass the depth rule. Neither was needed: a badge has no
  element children, so the amended harness never counts it as a surface, and both carry meaning (the
  position-color system; uncertainty). Restored; only the softened empty cells remain.

## T030: US1 live check (measured, amended harness, local, 23 routes × 3 sizes = 69 loads)

- **Depth:** max `depth` 2 and max `gridDepth` 2 at 1440, 768 and 375. Every route passes SC-001
  (baseline had depth 3 on home, NFL/NBA history and analysis).
- **Horizontal scroll at 375: 7 routes failed on the first run.**
  - Cause 1, a regression from this branch: the stat tables on Bench points, Luck and Playoff odds lost
    their scroll container when `.panel` became `.section`.
  - Cause 2, likely pre-existing (files untouched by this branch, not proven against base): the live
    status bar, the weekly performer rows and the waiver-add rows didn't wrap.

  Fixed in `styles.css` ("013 US1: phone width"). Re-measured: **0 of 7 scroll.** 768 and 1440 had none.
- Routes: /, /managers, /managers/13/history, /managers/13/versus/1, /mock/new, /mock/653, NBA sim, NBA live,
  NFL completed board, NFL history/power/analysis/roster-management/expected-wins/forecast/weekly-report/
  superlatives, NBA history/power/weekly-report/superlatives/roster-management/expected-wins.

## T036: US2 live check (measured, local)

- Non-commissioner path, checked without impersonating anyone: popsharky is a member, not the commissioner,
  of "West Coast Fantasy Football" (Sleeper `is_owner` → thebritkid). `/history` returns `canCommission: false`;
  the page shows "Final ranks appear once the commissioner computes them." and 0 Compute buttons, with
  12 "not computed yet" cells.
- Commissioner path: Power rankings shows "Recompute week 4 (commissioner)" for popsharky ("(Foot) Ball
  Knowers", `is_owner`). **History's Compute for a commissioner wasn't seen live:** no local league popsharky
  commissions has uncomputed ranks. It's covered by `LeagueHistory.test.tsx` (canCommission true → button).
