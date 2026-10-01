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

## US3 + US4 — build agent

Tasks T037-T041 and T044-T048 built. T042 and T049 (live checks) are the parent's.

### Navigation: final groups and labels (destinations.ts is still the one table)

| Group (heading) | Row (new label) | Former label (searchable) | Sports |
|---|---|---|---|
| home (none) | League home | none | nfl, nba |
| thisWeek ("This week") | Matchups & awards | Weekly report | nfl, nba |
| thisWeek | Power rankings | none | nfl, nba |
| season ("The season") | Team strength | Analysis | nfl only |
| season | Luck | Expected wins | nfl, nba |
| season | Bench points | Roster management | nfl, nba |
| season | Playoff odds | Season forecast | nfl, nba |
| season | Awards | Superlatives | nfl, nba |
| draft ("Draft") | Draft room / Draft board / Mock draft (unchanged), Follow live, Mock it | none | nfl, nba |
| history ("History") | Standings (the all-seasons history page) | History | nfl, nba |

- League home: route `/leagues/:sleeperLeagueId`, whole-chain href (`lineage.current`), like History. `switchTarget`'s non-draft fallback is now `home` (was `history`).
- Desktop: a heading button per group; closed groups are not rendered. State is per viewer in `localStorage` key `bk.rail.groups.v1` (try/catch, default expanded). Landing on a page re-opens its group.
- Phone lane (<= 860px) and collapsed rail: every group is rendered open. The phone decision is made in JS (`matchMedia('(max-width: 860px)')`, `PHONE_LANE_QUERY`), mirroring the CSS lane breakpoint; headings become non-interactive separators. If the CSS breakpoint moves, move the constant (comment says so).
- Jump-to matches `label` and `formerLabel` (searchIndex terms).

### Standings position
`getLeagueHistory` walks the chain from the requested league, so `seasons[0]` is the URL's league; League home looks the season up by `sleeperLeagueId` first, then falls back to `seasons[0]`. Position = (index of the `isMe` row in that season's `standings`) + 1. The server orders standings with `RosterSeasonRepository.forLeague`: `final_placement` (nulls last), then wins desc, then points_for desc. So mid-season the position is "by wins, then points for" (no ties/losses tiebreak); the number carries a `title` saying so. Not verified against Sleeper's own displayed standings.

### Power headline
`getPowerRankings` entries, MEMBER kind, latest season, latest MEMBER week vs the week before, fed to PowerRankings.tsx's exported `computeWeeklyStory` and `buildHeadline` (the same functions the Power page's `<h1>` uses). The week selection (about 8 lines) is re-written in `LeagueHome.powerHeadline`, not shared: the page does it inline in its component. One difference: the page takes `season` as the max over all entries, League home as the max over MEMBER entries. No MEMBER entries => "The league vote hasn't started yet."

### Other League home sources
- Latest matchup: `getWeeklyReport(id, 0)`, side with `isMe`. Won/Lost/Tied by points. Non-member (no `isMe` side and no `isMe` standings row): block omitted. Member without a game that week: "You had no game in week N."
- Next opponent: `getLeagueAnalysis(id)` matchups side with `isMe`; only fetched when `LEAGUE_DESTINATIONS` says `analysis` offers the league's sport (sport from the draft list, falling back to the weekly report's sport). Labelled as a projection.
- Top award: first superlative in payload order with `available` and non-empty `holders` (so the player-headed Jabari award is never the pick); titles reuse `TITLES` from Superlatives.tsx (now exported). `early` shows a caveat beside it.
- Pre-season (draft for this league not `complete`): draft status + "Open the draft room" (the page's one `--volt`, via `.home-hero-cta`) and "Follow live" when pre_draft/drafting; the latest-matchup block is not rendered.
- 404 from the history endpoint renders the shared NotFound; any other failure stays in its own block.

### Site home (T047)
One `.row-list` row per league: crest, name (link to League home), sport pill, season, managers, draft status (live rows link to follow live), "Open league" (-> `/leagues/:id`), and "Mock it" (kept, opens the seeded modal). Removed from rows: season pills, Refresh/`track` state (the same `trackDraft` call still lives in LiveStatusBar in the live room), the per-league History/Power/Analysis chips, record/rank (none shown, per S17). Hero: title "Welcome back, <display name>", eyebrow carries the sport filter ("All sports" / "NFL leagues"). "From Sleeper" set-up cards are unchanged.

### Files
destinations.ts (+test), searchIndex.ts (+test), railLeague.test.ts, components/LeagueRailSection.tsx (+test), LeagueSwitcher.test.tsx, AppShellJumpTo.test.tsx, App.tsx, pages/LeagueHome.tsx (new) + LeagueHome.test.tsx (new), pages/DraftPicker.tsx (+test), pages/Superlatives.tsx (one word: `export` on TITLES), styles.css (blocks "013 US3: rail groups" and "013 US4: League home" / "site home league rows"). No backend or api.ts edits.

### Tests (measured)
`npx tsc -b` clean; `npx vitest run`: 842 passed, 0 failed (68 files; baseline before this work was 813 across 67 files, +29 incl. 10 LeagueHome, 4 DraftPicker rows, 6 rail groups, 6 destinations groups, 2 searchIndex); `npm run build` green. Existing tests changed only for the renames (labels, History -> League home fallback, `/leagues/:id` now a league route); no behavioural assertion deleted.
Not verified: any browser rendering, the three screen sizes, contrast of new CSS.

## US3 + US4: parent review and live check (2026-09-30)

**Rejected and fixed in review (two-implementations class):**
- League home re-derived the Power page's hero week rule, and already disagreed with it: the season came from
  MEMBER entries vs all entries. Extracted `leagueVoteHero()` in PowerRankings.tsx; both pages call it.
- The rail added a third JS copy of the 860px phone breakpoint (AppShell had one). Moved to one
  `web/src/useNarrow.ts` used by both. `useNarrow.test.ts` reads styles.css from disk and fails if the CSS stops
  using that query. (Vitest stubs CSS, so `?raw` returned "" and the first version of that test passed vacuously
  against nothing. Caught because it failed when expected to pass.)

**Found live, fixed (not caught by the agent's tests):**
- NBA pre-season said "You're 1st of 12 at 0-0." That's a position claimed from zero games. Now: no position and
  no record block until any roster has played. The subtitle reads "Your season hasn't started yet."
- The NBA award block showed 2025's "Highest week" unlabelled on the 2026 home. It now says "From the 2025 season;
  2026 has no games yet." The early caveat uses the site's standard "early — this is mostly noise".
- Order: your record → latest matchup → next opponent → standings, so "you" fits the first screen.
Tests added for both honesty fixes (845 pass).

**Measured (local, NFL "(Foot) Ball Knowers", block positions in px vs screen height):**

| Size | you | latest matchup | next opponent | screen |
|---|---|---|---|---|
| 375×812 | 383–476 | 508–674 | 706–877 | 812 |
| 768×1024 | 355–448 | 355–521 | 553–724 | 1024 |
| 1440×900 | 120–213 | 120–286 | 120–291 | 900 |

SC-005 passes at all three sizes (record, position and latest matchup on the first screen; the next opponent too, except
at 375 where it starts on screen and runs ~65px past). No horizontal scroll on any size, either sport.

**Nav (SC-004):** at 1440, 768 and 375, every league destination is a visible one-click link in the rail
(League home, Matchups & awards, Power rankings, Team strength, Luck, Bench points, Playoff odds, Awards,
Draft board, Standings). Group headings show on desktop; the phone lane shows every row.
Old addresses still load (all US1 harness routes use them).

## US5 + US7 — build agent

Verified: `npx tsc -b` clean, `npx vitest run` 70 files / 858 tests passed, `npm run build` ok. Not browser-checked (T053, T059 left to the parent).

**US5 (WeeklyReport.tsx, styles.css section "013 US5")**
- T050: when a matchup has an `isMe` side it renders first as a full-width `Scoreboard` (both avatars, 34px tabular scores, "Won"/"Lost"/"Tied" as a word with `--up`/`--down` and "by N.NN"); the other matchups follow in the existing `.wr-games` grid as a strip. Awards and football top performers become one "Week in review" `.row-list` (`WeekInReview`). Basketball's Best nights / Best week sections are unchanged (they keep their HowThisWorks and caveats). No `isMe` leaves the original sections and order.
- T051: numeric input replaced by a `‹ Week N ›` group (`aria-label` "Previous week"/"Next week", disabled at 1 and at `latestScoredWeek`). URL `?week=` and the clamp-to-latest effect are untouched. Label "Week N · latest final week" shows only when there is no `?week=`, the week is final, and it equals `latestFinalWeek`; if `latestScoredWeek > N` a link "Week M in progress →" follows. The existing "In progress — scores can still change" caveat is kept for `!weekFinal`. The payload does carry `weekFinal`/`latestFinalWeek`.
- T052: tests rewritten for the stepper (the old input tests no longer apply) plus new ones: scoreboard first (Lost / Won / Tied), merged review list, no-isMe order, stepper bounds, default-week label with week 2 shown and week 3 scored.

**US7**
- T054/T055: `components/PlayerFace.tsx` (+ test). Sport required. Stage only advances on `onError`; DEF starts at logo; null team skips logo; fixed box in all states (inline-block, negative vertical margin so it never grows a text line); `loading="lazy"`, `alt={name}`; initials styled like Avatar. 6 tests.
- T056: `DraftBoard` cell: 16px face inside `.name`, before the name. Cell height: face margin box is 8px (16px minus 2x4px negative margin), below the name line height, so it adds no height; not measured live. It costs about 21px of name width in a 96px column, so check truncation at 1440 with 14 teams.
- T057: faces added in `PlayerPicker` rows (20px) and filled `TeamStrip` slots (16px), `OnTheClockPickInput` rows and its inline team strip, `PickFeed` rows (18px). Sport threading: `DraftBoard.sport` and `PickFeed.sport` were optional with a silent `'nfl'` default; both are now required (all real callers already passed it; `PickFeed.test.tsx` now passes `sport="nfl"`). `TeamStrip` gained a required `sport` prop, passed from `PlayerPicker` and `LiveDraftView` (its `sport` variable).
- T058 finding: YES, both are Sleeper player ids. `WeeklyPerformer.playerId` is the key of `playersBySleeperId` (backend/src/main/java/com/ballknowers/draftsim/engine/WeeklyReportService.java:237-253; same for NightPerformance/PlayerWeek). Superlative player ids come from `playersBySleeperId.get(r.playerId())` (SeasonSuperlativesService.java:822-823, 843-844, and 765 for pickups). So PlayerFace is used in WeeklyReport (top performers, best nights, best week) and in Superlatives' JABARI_SMITH_JR `playerHolders` (these carry the pro `team`, so they get the logo step). Weekly performers carry only the FANTASY team name, so they pass `team={null}`: photo then initials. Not done: the SuperlativeStandingsModal player rows and per-detail rows (PICKUP/ABSENCE/CONDUCT) in Superlatives, left as is to keep scope small.

## US5 + US7: parent live check (measured, local, 2026-10-01)

- **Faces:** 0 broken images on the NFL completed board (180 cells, 1440 and 375), the NBA mock (/mock/653) and the
  NFL weekly report. No horizontal scroll on any.
- **Fixed in review:** the agent put the 16px face on the board's *name* line. Measured: it truncated
  **16 of 180** names at 1440 vs **1** without faces. Moved it to the meta line beside the team code.
  Re-measured: 1 of 180 truncated at 1440 and 768; cell height unchanged at 63px.
- **Weekly report:** the NBA league (2026 not started) shows "League · 2025", a fallback note, the stepper at
  week 21 and "Week 21 · latest final week", with popsharky's matchup leading as a scoreboard ("WON by 9.00").
  It's honest about which season it is.
- The agent also removed silent `sport = 'nfl'` defaults from DraftBoard and PickFeed (every caller already
  passed it). This is the "optional params that encode rules" class, closed rather than worked around.

## US6 + US8 frontend — build agent

**TIER_ADP_GAP = 4** (`web/src/tiers.ts`, labelled ARBITRARY there). A new tier starts when the next player's ADP is more than 4 picks past the previous one's (a gap of exactly 4 does not split). Reason: with a 12-team room, 4 breaks the dense top of a draft into a few tiers per round without making every player his own tier. It is a display grouping only: not fitted, not measured against anything. 999 ("no rank") goes in one trailing "Unranked" group and never takes part in gap arithmetic.

### Files
- New: `web/src/tiers.ts` (+ test), `web/src/stealsReaches.ts` (+ test), `web/src/components/GradeChip.tsx`, `web/src/components/AvailabilityPanel.test.tsx`, `web/src/pages/CompletedDraftBoard.test.tsx`.
- Edited: `pickRun.ts` (+ test: `sport` is now required, no default; the only non-test callers `PickFeed.tsx` and `scarcity.ts` already passed it), `components/AvailabilityPanel.tsx`, `components/Skeleton.tsx` (`SkeletonBoard`), `components/DraftBoard.tsx`, `pages/DraftView.tsx`, `MockDraftView.tsx`, `LiveDraftView.tsx`, `CompletedDraftBoard.tsx`, `LeagueAnalysis.tsx`, `RosterManagement.tsx`, tests for both, `styles.css` (blocks `013 US6`, `013 US8` x2, plus a skeleton-board block under US6).

### AvailabilityPanel (one panel, extended)
Props added, all optional: `availability`, `players`, `noAvailabilityReason`, `recentPicks`. `pickedPlayerIds` is optional too. Survival numbers show only when `availability` exists AND no reason is given (`showSurvival`). Verdict thresholds and the survival strip are untouched. Rows are grouped by `tierPlayers`; inside a tier, with survival numbers, the old "most at risk first at your next pick" order is kept. Run callout is `positionRun(recentPicks, 6, 4, sport)`.
- Simulator (DraftView): same as before plus tiers, faces and the run callout. Skeleton board (`SkeletonBoard`, no text) while `getSeats` is pending and there is no error. The existing "Simulating your draft..." overlay still only shows after Start; the running progress bar is unchanged.
- Mock (MockDraftView): shown only when `isUsersTurn && !complete`; fed `players={state.available}`, reason "Availability needs a simulation, which mock drafts don't run.", title becomes "Best available", no depth chips/strip/verdict. The picker button is now labelled "Full list".
- Live (LiveDraftView): tiers and faces always; when `!slotKnown` it passes the reason "Availability appears once your seat is known." and survival is held back even though a projection exists (it would answer for an assumed seat).

### US8 frontend
- `GradeChip` takes the number as `children`, so a grade cannot be drawn alone. null/empty grade renders the number untouched. Early badge text is exactly "early — this is mostly noise" with `sl-early`, driven by the payload's `gradesEarly`.
- Wired into Team strength (the score pill) and Bench points (the efficiency %). Both pages' How this works gained a sentence on grades.
- Steals & reaches: `pickNo - adpAtDraft`, positive = steal (`--up`, "+n"), negative = reach (`--down`, minus sign + n). Under half a pick is "even", untinted. null adpAtDraft: untinted, cell shows "no ADP" with title "no ADP at draft time". Toggle hidden when no pick has adpAtDraft. Never reads `player.adp`. Ordering tests in `stealsReaches.test.ts` and the page test.

### Deviations and open items
- The grade-early threshold (4) is NOT in the analysis or roster-management payloads (only `gradesEarly`), and I may not edit `api.ts` or the backend. `GRADES_EARLY_UNDER_WEEKS = 4` in `GradeChip.tsx` mirrors `SeasonWindow.EARLY_THRESHOLD_WEEKS` for the How-this-works sentence only; the badge itself never compares against it. The rankings threshold is read from `rankingScores.weeksRequired`.
- The early badge repeats on every graded row while early (the caveat stays beside each number, per the contract). Parent should look at whether that is too noisy at 375px.
- "no ADP" is the visible text; the full phrase "no ADP at draft time" is the `title` (the cell is ~96px wide).
- Steal/reach tint is a gradient over the position fill. Its contrast against `--text` was NOT measured (max overlay 26%); the parent should measure.
- No unit test for the DraftView skeleton (DraftView is too heavy to mount cheaply); needs a browser check, as does SC-007 time-to-skeleton. Mock/Live panel behavior is covered through AvailabilityPanel tests, not page tests.
- Not browser-verified at 1440/768/375.

### Measured
`npx tsc -b` clean; `npx vitest run`: 74 files, 885 tests, all pass (was 71 files / 863 before this agent, plus 22 new... 6 AvailabilityPanel, 5 tiers, 8 stealsReaches, 3 CompletedDraftBoard, 3+3 grade page tests); `npm run build` ok.

## US6 + US8 frontend: parent review and live check (measured, local, 2026-10-01)

**Rejected and fixed in review:**
- `GradeChip.tsx` kept a client copy of the early threshold (`GRADES_EARLY_UNDER_WEEKS = 4`) for its How-this-works
  sentence. That's the third time this class appeared in this build. Roster-management and analysis responses now carry
  `earlyThresholdWeeks` (from `SeasonWindow`); the sentence reads it, and without it says "for the first weeks of the
  season" rather than inventing a number. api.ts is mirrored. Backend: 964 tests, 0 skipped.
- The early badge repeated on every graded row (12× on one table). It now sits once, on the grade column's header,
  beside every grade it qualifies. Tests assert exactly one.

**Found live, fixed:**
- **Encoding corruption.** `AvailabilityPanel.tsx` came back with mixed encodings: lines 55, 59 and 322 were saved
  as Windows-1252 bytes inside a UTF-8 file. The tier label rendered "ADP 1�60" and the pick tooltip separator was
  broken. Lines were re-decoded individually; a whole-file convert would have double-encoded the valid ▾/▴ arrows.
  Added `sourceEncoding.test.ts`: every `src/**/*.{ts,tsx,css}` must decode as strict UTF-8. Proven to fail on an
  injected 0x96 byte and pass on the clean tree.
- **Tiers chained across 60 picks** in a dense mock list ("ADP 23–83"). Added `TIER_MAX_SPAN = 12` (ARBITRARY, one
  12-team round): a tier also splits once it spans more than that from its first player. The same mock now reads
  16–18, 23–35, 36–48, 49–61, 62–74, 75–83. Test added.

**Measured:**
- Simulator (NBA, local): skeleton at **242 ms**, board at **307 ms**; no "Simulating" text before Start (SC-007).
- Mock (/mock/653): "Best available", tiers and faces, plus the reason "Availability needs a simulation, which mock drafts don't run."
- Live (NBA): tiers, survival and verdicts (seat known).
- Steals & reaches (NFL 2026 board): 69 steals, 94 reaches, 0 "no ADP" (all 180 picks have `adpAtDraft`). The sign
  checks out: B. Robinson, pick 4 at draft-time ADP 2, shows **+2** (steal). Tinted cell bg oklch(29.8% .054 232) with
  `--text` oklch(95%) on top: high contrast, not separately ratio-measured.
- Grades: one header badge per table at 3 scored weeks; Team strength states "fewer than 4 weeks", read from the payload.

## US9 — build agent

Scope: T084–T093 (web only; T094 live check is the parent's). No commit made.

**Archetype rules (`web/src/managerBehaviour.ts`, `archetype()` returns `{ label, basis }`):**
1. Reach, only with `picksScored > 0` AND a read from `relativeReachRead(...)` that is not `thin`: `early` → "Reacher", `late` → "Waits", `room` → "Drafts like the room". `basis` is the read's own text, so label and caption can't disagree.
2. Otherwise the single strongest positional tilt (largest |v − 1|), if |v − 1| ≥ `ARCHETYPE_TILT_CUTOFF` = **0.25** → "QB early" (v > 1) or "TE late" (v < 1).
3. Otherwise "Not enough history".
- **0.25 is ARBITRARY** (hand-set, not fitted, not backtested; labelled so in the code). Reason: `behaviourText` already lists any lean over 0.05, but a label is a headline, so it needs a clearly non-neutral tilt. Not measured against the real distribution of tilts; revisit with data, and don't retune it to make a particular manager read a particular way.
- NBA case: every NBA manager has `picksScored = 0`, so they can never get a reach label; they get a tilt label or "Not enough history" (tested, including with a reach number present).

**How standings decide the season match:** `LeagueHistory` fetches `getExpectedWins(league)` once (failure leaves the columns as dashes). Each season table gets `season={s.season}` (the `SeasonHistory.season`, since per-league `StandingRow.season` is null). A team's figures are used only if `expected.available && expected.season === s.season`, matched by `rosterId`; otherwise "—". The median column is "Vs weekly median (reg. season)" and shows median games only; How this works says ffwrapped's "Median record" adds them to the real record (3-0 here vs 6-0 there).

**Other decisions:**
- "season in progress" appears once, in the `<h3>`; an in-progress rank cell is "—" with `title="Season in progress"`. The old test that said "never an unexplained dash" now accepts a dash only with that title.
- Best/worst marks (W, L, PF, PA, vs-all, vs-median): `▲`/`▼` with `role="img"` + aria-label, tinted `--up`/`--down`. None when the column has no spread. Rank and T are not marked.
- Superlatives: modal replaced by an in-place `region` (`SuperlativeStandingsModal.tsx` git-mv'd to `SuperlativeStandings.tsx`). "See all" toggles to "Hide" (`aria-expanded`). The modal's repeated early badge and coverage line are not repeated in the panel (the row above already shows both). The Embiid total line moved from the holder line to the headline figure (otherwise printed twice); it still says "estimated".
- Archetype on the Scouting report: managers of the rail's league first. **Deviation/finding:** on `/managers` the rail has no selected league (the rail derives its league from the path or a route-state hint, and `/managers` accepts neither). So `Rail.tsx` now puts `{ railLeagueId }` in the Managers link's route state (`AppShell` passes `railLeague.season.sleeperLeagueId`) and `ManagerTendencies` reads that same key, the one `AppShell` already reads for manager history. Members come from `getLeagueHistory` standings, sport and name from `cachedDrafts()` (the rail's own draft list). A direct visit to `/managers` has no league and no grouping (never a guess). Not covered by a Rail unit test; the parent should click through it.
- Manager profile: player card (big avatar + name, one row per sport: record, trophy icons + "N title(s)", four headline figures, each with "over N seasons"); ranks are sentences ("2nd of 12 for win rate in <league>"); the drafting section (with archetype) moved directly under the card.
- MockSetup: seat strip is one scrolling row; a seated real manager shows avatar + archetype; the per-seat hue tint on the face was dropped (neutral raised surface) so colour keeps one meaning.
- SignIn: centered; kicker "Ball Knowers", H1 pitch exactly as specified, large field, `--volt` Continue, no-password sentence kept verbatim (under the field). `App.test.tsx` heading assertions changed from "Who are you?" to the new pitch.
- Line endings: several files are CRLF in the working tree (index is LF, `core.autocrlf=true`); files I rewrote via script are LF. Git normalises on commit.

**Files changed:** `web/src/managerBehaviour.ts`(+test), `web/src/pages/{LeagueHistory,Superlatives,ManagerHistory,ManagerTendencies,MockSetup,SignIn}.tsx` (+ their tests), `web/src/components/SuperlativeStandings.tsx` (renamed from SuperlativeStandingsModal), `web/src/components/{Rail,AppShell}.tsx`, `web/src/App.test.tsx`, `web/src/styles.css` (five `/* --- 013 US9: ... */` blocks at the end), `specs/013-fan-first-redesign/caveats-after.md` (rows C016–C018, C065, C157, C359 + US9 section; still 488 distinct ids).

**Verified:** `npx tsc -b` clean; `npx vitest run` 75 files / 913 tests passed (includes `sourceEncoding.test.ts`); `npm run build` ok. **Not verified:** nothing was viewed in a browser (parent does T094).

## US9: parent review and live check (measured, local, 2026-10-01)

- Diff grep for new numeric constants (lessons #25): only `ARCHETYPE_TILT_CUTOFF = 0.25` (new, labelled ARBITRARY)
  and `MAX_PLAYER_ROWS = 10` (predates the branch; moved with the SuperlativeStandings rename). No duplicated rules.
- **Standings (NFL):** "season in progress" appears once; 2026 shows "Record vs all (reg. season)" and "Vs weekly
  median (reg. season)", e.g. Master Bates 28-5 / 3-0. 2025 rows show "—" (expected-wins season ≠ row season).
  The ▲ for best all-play sits on Play with the Klittle (29-4 > 28-5), **matching ffwrapped's own table.**
- **Scouting report via the rail's Managers link:** "In (Foot) Ball Knowers" is grouped first. 18 rows were checked
  label-vs-caption: every Reacher has "picks earlier", every Waits has "picks later", and every "Drafts like the room"
  has a reach within its ± band. No sign inversion. Direct visits to /managers (no rail state) show no league group,
  by design.
- **Awards:** one row per award (28 list rows with expansions), 6 early badges kept, "See all" expands in place.
- **Manager profile, mock setup:** render; no horizontal scroll at 1440. **Sign-in** (identity removed, then
  restored): centered pitch "Your league's real managers, simulated. See who's likely gone before you pick.",
  Continue in `--volt` (oklch(0.88 0.19 125)), no-password line kept.
