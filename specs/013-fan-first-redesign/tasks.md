---

description: "Task list for spec 013: fan-first redesign"
---

# Tasks: Fan-first redesign

**Input**: Design documents from `specs/013-fan-first-redesign/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Included. The spec and quickstart §3 require specific tests: grade ordering, all-play invariants, a single early-threshold constant, history `canCommission`, the PlayerFace fallback, destinations, archetype honesty and tiers. AGENTS.md's bar is a live browser check on top of a green suite, so every story ends with one.

**Organization**: grouped by user story. Story phases run in the spec's delivery order. US7 (faces) runs before US6 (draft room) because the panel uses `PlayerFace`. Both are P3.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US9 from spec.md
- Paths: `backend/src/main/java/com/ballknowers/draftsim/` is abbreviated **`BE/`**, `backend/src/test/java/com/ballknowers/draftsim/` is **`BT/`**, `web/src/` is **`WEB/`**, and `specs/013-fan-first-redesign/` is **`SPEC/`**

## Rules every task inherits (do not restate, do not break)

- Read [contracts/ui-rules.md](contracts/ui-rules.md) before any UI task. It is the acceptance contract.
- Never delete a caveat. Move it beside its number or into `<HowThisWorks>`.
- `WEB/api.ts` types change in the **same commit** as the Java record/map they mirror. New fields are optional in TS (rollout table in [contracts/api-additions.md](contracts/api-additions.md)).
- No page address changes. No migration. No change to the commissioner key, the backfill endpoint's server gate, or `SimulationResult`.
- Hand-set numbers are labelled arbitrary where they live. Pick them once and record the value and reason in `SPEC/build-notes.md`. Never tune one to match an expectation.
- After a Java change, restart `draft-sim-api`. After restarting the web server, hard-refresh open tabs.
- Coding subagents run on Sonnet (AGENTS.md). The parent reads every diff before marking a task done.

---

## Phase 1: Setup (baselines)

**Purpose**: measure before changing, so every later claim has a before value.

- [ ] T001 Run `cd web && npx tsc -b && npm test && npm run build` and `cd backend && ./gradlew test`. Record pass, fail and **skipped** counts in `SPEC/build-notes.md`; if ITs were skipped, start Postgres (5433) and re-run before recording.
- [ ] T002 Re-ingest `POST /api/ingest/all/1346366555759341568` (NFL) and `POST /api/ingest/all/1339351318115946496` (NBA) on the local backend; note the result in `SPEC/build-notes.md`.
- [ ] T003 [P] Build the caveat inventory: for every route in spec.md's "Route inventory", both sports, record every caveat sentence, badge and methodology paragraph as `route | location | exact text` in `SPEC/caveats-before.md` (quickstart §2).
- [ ] T004 [P] Measure baselines at 1440×900 for every route (NFL league): nested-surface depth (quickstart §4a script), horizontal scroll (§4b). Append a table to `SPEC/build-notes.md`, labelled measured.

---

## Phase 2: Foundational (blocks every story)

**Purpose**: the shared tokens, type rules and components every page task builds on.

- [ ] T005 Add color tokens to `:root` in `WEB/styles.css` exactly as in [data-model.md](data-model.md) "Color tokens": `--volt: oklch(88% 0.19 125)`, `--up: oklch(74% 0.16 150)`, `--down: oklch(66% 0.19 25)`, `--row-hover: oklch(22% 0.03 255)`; set `--bg: oklch(14% 0.025 255)` and `--panel: oklch(19% 0.03 255)`. Each token gets a one-line comment naming its single meaning.
- [ ] T006 Add `--crimson-fill` to `WEB/styles.css`: lower crimson's lightness until white text on it measures ≥ 4.5:1 (current `--crimson` is 4.07:1, research R10). Use it for every crimson *fill* behind text (`.col-head.mine`, the mock-setup "You" seat, any `.you` chip). Record the final value and measured ratio in `SPEC/build-notes.md`.
- [ ] T007 In `WEB/styles.css` change `.mono` to `font-family: inherit; font-variant-numeric: tabular-nums;` and add `.code` (the old monospace stack) for real code/ids. Grep `WEB/` for `.mono` uses that show ids or URLs and switch only those to `.code`.
- [ ] T008 In `WEB/styles.css` change `.panel h2` and other section-title rules that use Oswald uppercase (lines ~264, 353, 357, 370, 428) to Plus Jakarta 600, sentence case, ~15px, `--text`. Keep Oswald on `.page-title`/hero numbers only. Update section-title strings written in ALL CAPS in source to sentence case where the case is in the string rather than CSS.
- [ ] T009 Add surface utilities to `WEB/styles.css`: `.section` (unboxed section with heading and spacing), `.row-list` (rows separated by a 1px `--line` divider, `--row-hover` on hover, no per-row border), and a rule that a `.panel` nested inside a `.panel` renders without its own border or background.
- [ ] T010 [P] Create `WEB/components/HowThisWorks.tsx`: a `<details className="how">` with summary "How this works" and `children` body, closed by default. Style `.how` in `WEB/styles.css` (muted, small, no box).
- [ ] T011 [P] Test `WEB/components/HowThisWorks.test.tsx`: renders closed, opens on click, children present in the DOM when open.
- [ ] T012 [P] Add `PageHeader` guidance in `WEB/components/PageHeader.tsx`: document `sub` as "one sentence, the takeaway" (contracts/ui-rules.md "Words") and add a dev-only `console.warn` when `sub` is a string containing any forbidden word listed there.
- [ ] T013 Replace the house-style header comment at the top of `WEB/styles.css` with the rules in [contracts/ui-rules.md](contracts/ui-rules.md) (surfaces, type, one meaning per color, words, nav, faces, sizes, commissioner). Keep the existing STACKING and LAYOUT sections, which are still true.

**Checkpoint**: `npx tsc -b && npm test && npm run build` green. Spot-check one page in the browser for token/typography regressions.

---

## Phase 3: User Story 1 - Every page reads like a fan tool (Priority: P1) 🎯 MVP

**Goal**: two surfaces per page, takeaway subtitles, methods under "How this works", every caveat kept.

**Independent Test**: quickstart §4a depth ≤ 2 on every route; every subtitle is one sentence with no forbidden words; `caveats-after.md` maps every `caveats-before.md` row.

Each page task means: (1) rewrite the `PageHeader` `sub` as one fan sentence; (2) move methodology paragraphs into `<HowThisWorks>` at the end of their section, unchanged in claims; (3) keep caveat badges beside their numbers; (4) remove nested boxes using `.section`/`.row-list` (T009) until §4a depth ≤ 2; (5) use `--up`/`--down` for better/worse values, `--volt` for at most one primary action.

- [ ] T014 [P] [US1] Site home: `WEB/pages/DraftPicker.tsx`. Unbox the "Your leagues", "From Sleeper" and "Mock drafts" panels into `.section`s. "Start a mock draft" becomes the page's one `--volt` action.
- [ ] T015 [P] [US1] Power rankings: `WEB/pages/PowerRankings.tsx`. Hero headline unboxed; Riser/Free fall as inline callouts with `--up`/`--down` arrows; ladder on the surface as `.row-list`.
- [ ] T016 [P] [US1] Team strength: `WEB/pages/LeagueAnalysis.tsx`. Subtitle ("Who's actually built to win, from games played and lineups you'd start."); the "raw" sub-figure and composite explanation go into `<HowThisWorks>`; heat-tint best/worst High and Low with `--up`/`--down`.
- [ ] T017 [P] [US1] Bench points: `WEB/pages/RosterManagement.tsx`. Bars in one color (drop the rank rainbow); "left N on the bench" in `--down` for the three largest gaps; methodology into `<HowThisWorks>`.
- [ ] T018 [P] [US1] Luck: `WEB/pages/ExpectedWins.tsx`. Subtitle names the luckiest team from the data (e.g. "{team} has stolen {n} wins."); the schedule-strength footnote goes into `<HowThisWorks>`.
- [ ] T019 [P] [US1] Playoff odds: `WEB/pages/SeasonForecast.tsx`. Subtitle plus the empty state for non-commissioners: "Playoff odds land after week {latestFinalWeek + 1}'s games are scored." The "stored simulation" explanation goes into `<HowThisWorks>`.
- [ ] T020 [P] [US1] Weekly report copy and surfaces only: `WEB/pages/WeeklyReport.tsx` (layout changes are US5). Unbox the awards cards into a `.row-list`.
- [ ] T021 [P] [US1] Awards: `WEB/pages/Superlatives.tsx` copy and surfaces only (trophy list is US9). Remove per-card colored top borders and the panel-in-panel.
- [ ] T022 [P] [US1] Standings: `WEB/pages/LeagueHistory.tsx`. Subtitle; the "what this app thinks about a draft…" paragraph goes into `<HowThisWorks>`; tables on the surface.
- [ ] T023 [P] [US1] Scouting report: `WEB/pages/ManagerTendencies.tsx`. The standard-error lede goes into `<HowThisWorks>`; the `(±n)` text moves to the bar's `title` (the shaded band already encodes it).
- [ ] T024 [P] [US1] Manager profile: `WEB/pages/ManagerHistory.tsx`. The career-profile methodology paragraph goes into `<HowThisWorks>`; stat boxes become one unboxed row (header rework is US9).
- [ ] T025 [P] [US1] Manager comparison: `WEB/pages/ManagerComparison.tsx`. Apply the shared rules only (this page was never reviewed; research "Open").
- [ ] T026 [P] [US1] Draft rooms' surfaces: `WEB/components/DraftBoard.tsx` and the `.cell` rules in `WEB/styles.css`. Drop the inner cell border so the position tint is the cell; soften empty future cells. No layout change (US6 does that).
- [ ] T027 [P] [US1] Mock setup and completed board copy: `WEB/pages/MockSetup.tsx`, `WEB/pages/CompletedDraftBoard.tsx`. Subtitles and surfaces only.
- [ ] T028 [US1] Run `WEB/` tests and fix any that asserted old copy, updating the expected strings and **not** deleting assertions about caveats.
- [ ] T029 [US1] Write `SPEC/caveats-after.md`: for every row in `caveats-before.md`, its new location (beside number / HowThisWorks / unchanged). Any row with no location is a failure; restore it before continuing.
- [ ] T030 [US1] Live-check every route (quickstart §4a, §4b) at 1440, 768 and 375 on NFL and NBA leagues. Record depth and scroll results in `SPEC/build-notes.md`; fix any page with depth > 2 or horizontal scroll.

**Checkpoint**: US1 is shippable on its own (MVP).

---

## Phase 4: User Story 2 - Only commissioners see commissioner controls (Priority: P1)

**Goal**: History's Compute is shown only when the server says `canCommission` (research R1). The other controls are already gated.

**Independent Test**: quickstart §4c with the commissioner and a fan identity.

- [ ] T031 [US2] Backend: in `BE/api/LeagueHistoryController.java` `GET /leagues/{sleeperId}/history` (line ~87), add `canCommission` = `membership.canCommission(league.id(), sleeperUserId)` to the response, using a mutable map put (not `Map.of`), and reading the `X-Sleeper-User` header the same way `ballot()` (line ~662) does.
- [ ] T032 [US2] Same commit as T031: add `canCommission?: boolean` to the league-history response type in `WEB/api.ts`, with a comment pointing at `LeagueMembership.canCommission`.
- [ ] T033 [P] [US2] Backend test `BT/api/LeagueHistoryCanCommissionIT.java`: commissioner member → true; configured owner → true; ordinary member → false; no header → false.
- [ ] T034 [US2] `WEB/pages/LeagueHistory.tsx`: pass `canCommission` down to `RankCell` (line ~296) and render the Compute button only when it is `true`; when hidden, the cell shows only "not computed yet". Missing field → treated as false.
- [ ] T035 [P] [US2] Test in `WEB/pages/LeagueHistory.test.tsx`: Compute absent when `canCommission` is false or missing; present when true.
- [ ] T036 [US2] Audit `WEB/pages/` for any other control that writes league data (grep `commissionerFetch`, `backfill`, `compute`, `recompute`) and confirm each is gated by a server-provided flag; list the result in `SPEC/build-notes.md`.
- [ ] T037 [US2] Live check (quickstart §4c) as popsharky (commissioner) and, **with Allan's OK**, as a non-commissioner member of "(Foot) Ball Knowers". Record results.

---

## Phase 5: User Story 3 - Navigation grouped by what a fan is doing (Priority: P2)

**Goal**: grouped rail, fan names, switcher on top, labelled collapsed rail, every address unchanged.

**Independent Test**: from every league page reach every other in ≤ 2 clicks; every item identifiable at 3 sizes; old names still found in jump-to.

- [ ] T038 [US3] In `WEB/destinations.ts` add to `LeagueDestination`: `group: 'home' | 'thisWeek' | 'season' | 'draft' | 'history'` (required, no default) and `formerLabel?: string`. Set fan labels and groups per [data-model.md](data-model.md) "Destination": weeklyReport → "Matchups & awards"/thisWeek; power → thisWeek; history → "Standings"/season; analysis → "Team strength"/season; expectedWins → "Luck"/season; rosterManagement → "Bench points"/season; forecast → "Playoff odds"/season; superlatives → "Awards"/season; board/live/mock → draft. The old label goes into `formerLabel`.
- [ ] T039 [P] [US3] Extend `WEB/destinations.test.ts`: every row has a group; every group used is in the enum; every `formerLabel` differs from `label`.
- [ ] T040 [US3] `WEB/components/LeagueRailSection.tsx`: render destinations under group headings (This week / The season / Draft / History) in that order, each group collapsible, with the group containing the current route expanded and the others remembered per viewer in `localStorage` (try/catch, default expanded).
- [ ] T041 [US3] `WEB/components/LeagueRailSection.tsx` (or `LeagueSwitcher`): put the league + season switcher at the top of the league block.
- [ ] T042 [US3] `WEB/components/Rail.tsx` collapsed mode (line ~113): each item renders its glyph with `title` and `aria-label` set from the destination's label. No item may render without an accessible name.
- [ ] T043 [P] [US3] `WEB/searchIndex.ts`: match a destination by `label` **or** `formerLabel`; extend `WEB/searchIndex.test.ts` ("expected wins" finds Luck).
- [ ] T044 [P] [US3] Update tests that assert old labels: `WEB/components/LeagueRailSection.test.tsx`, `WEB/components/AppShellJumpTo.test.tsx`, `WEB/railLeague.test.ts`. Add one test that the collapsed rail has an accessible name on every item.
- [ ] T045 [US3] Live check: every league page at 3 sizes, both sports; every old address in spec.md's route inventory opens the same page (quickstart §4h).

---

## Phase 6: User Story 4 - A league home that starts with you (Priority: P2)

**Goal**: `/leagues/:id` leads with your record, rank and matchup. Site home lists leagues as rows.

**Independent Test**: quickstart §4d.

- [ ] T046 [US4] Create `WEB/pages/LeagueHome.tsx`. It composes `getLeagueAnalysis` (find your row via `isMe` for record, rank and this week's matchup), `getLeagueHistory` (current-season standings, top 5 plus your row), `getPowerRankings` headline and `getSuperlatives` (newest/top award). Each block loads and fails independently with its own empty state. No "you" block when no row has `isMe`. Before the season starts, show draft status and the draft-room link instead of a matchup.
- [ ] T047 [US4] Register the route `/leagues/:sleeperLeagueId` in `WEB/App.tsx` and add a `home` row to `WEB/destinations.ts` (group `home`, label "League home", all sports, `match` for exactly that path).
- [ ] T048 [P] [US4] Test `WEB/pages/LeagueHome.test.tsx`: member sees record/rank/opponent; non-member sees no "you" block and no error; one failing endpoint leaves the other blocks rendered; pre-season shows draft status.
- [ ] T049 [US4] Site home rows: `WEB/pages/DraftPicker.tsx`. Each league becomes one row (avatar/initial, name, sport pill, your record and rank when known, one primary action to its league home). Season pills and Refresh move off the row (they remain on league pages). The hero greets the signed-in user.
- [ ] T050 [P] [US4] Update `WEB/pages/DraftPicker.test.tsx` for the row layout; keep the existing behavioural assertions.
- [ ] T051 [US4] Live check (quickstart §4d) at 3 sizes, both sports.

---

## Phase 7: User Story 5 - Weekly report leads with your matchup (Priority: P2)

**Goal**: your matchup first as a scoreboard, then the rest as a strip, a week stepper and a label that says which week and why.

**Independent Test**: open as a member: your matchup first; step back and forward; the label is always right.

- [ ] T052 [US5] `WEB/pages/WeeklyReport.tsx`: render the matchup containing the signed-in user's roster first and full width (avatars, large tabular scores, win/loss in `--up`/`--down`). Identify "you" by the existing user identity against matchup rows (use `isMe` if the payload has it; otherwise match `currentUserId()` against the row's Sleeper user id, and add that field to the backend response plus `WEB/api.ts` in one commit if it is missing).
- [ ] T053 [US5] Same file: the remaining matchups as a compact scoreboard strip; awards and top performers as one "Week in review" `.row-list`.
- [ ] T054 [US5] Same file: replace the numeric week input (line ~92) with ‹ Week N › buttons, disabled at week 1 and at `latestScoredWeek`. The label reads "Week N · latest final week" when N is the default and a later week is scored but not final, adding "Week N+1 in progress →" as a link (research R2).
- [ ] T055 [P] [US5] Extend `WEB/pages/WeeklyReport.test.tsx`: your matchup first; the stepper's bounds are disabled; the label text for the default-week case (week 2 shown, week 3 scored and not final).
- [ ] T056 [US5] Live check at 3 sizes, both sports; record in `SPEC/build-notes.md`.

---

## Phase 8: User Story 7 - Players have faces (Priority: P3)

*(Runs before US6, which uses `PlayerFace`.)*

**Goal**: photo, then logo, then initials, everywhere a player appears, for both sports.

**Independent Test**: quickstart §4f: 0 broken images, 0 empty cells on an NFL board and an NBA mock.

- [ ] T057 [US7] Create `WEB/components/PlayerFace.tsx`, props `{ sport, sleeperId, team, position, name, size }`. States are only ever `photo → logo → initials`, advancing on `onError`. Photo: `https://sleepercdn.com/content/{sport}/players/thumb/{sleeperId}.jpg`. Logo: `https://sleepercdn.com/images/team_logos/{sport}/{team.toLowerCase()}.png`. `position === 'DEF'` starts at logo. `team == null` skips logo. Fixed width/height, `loading="lazy"`, `alt={name}`. Initials use the existing avatar initials style.
- [ ] T058 [P] [US7] Test `WEB/components/PlayerFace.test.tsx`: photo error → logo; logo error → initials; DEF starts at logo; null team → initials after photo error; NBA URLs use `nba`.
- [ ] T059 [P] [US7] Wire into board cells: `WEB/components/DraftBoard.tsx` (small size, no layout shift).
- [ ] T060 [P] [US7] Wire into `WEB/components/PlayerPicker.tsx`, `WEB/components/OnTheClockPickInput.tsx`, `WEB/components/PickFeed.tsx` and `WEB/components/TeamStrip.tsx`.
- [ ] T061 [P] [US7] Wire into weekly top performers in `WEB/pages/WeeklyReport.tsx` and player rows in `WEB/pages/Superlatives.tsx`, using the sleeper id where the payload already has one. Where it does not, use team logo/initials and note it in `SPEC/build-notes.md`; don't add backend fields for this.
- [ ] T062 [US7] Live check (quickstart §4f) on `/drafts/1346366555776126976/board` and an NBA mock; record image-failure and empty-cell counts.

---

## Phase 9: User Story 6 - "Who should I take?" stays on screen (Priority: P3)

**Goal**: a persistent "Your pick" panel with tiers, faces, run callout, and availability where a curve exists; a simulator loading state.

**Independent Test**: quickstart §4e in all three rooms at 3 sizes.

- [ ] T063 [P] [US6] Create `WEB/tiers.ts`: `export const TIER_ADP_GAP` (comment: "ARBITRARY, hand-set display grouping, not fitted"; choose the value once, record it in build-notes) and `tierPlayers(players)`, which sorts by ADP, starts a new tier when the gap to the previous player > `TIER_ADP_GAP`, and puts ADP 999 in a final "Unranked" group.
- [ ] T064 [P] [US6] Test `WEB/tiers.test.ts`: gap boundary starts a tier (gap equal to the constant does not); 999 goes to Unranked; empty input gives an empty result.
- [ ] T065 [US6] Create `WEB/components/YourPickPanel.tsx`: props `{ available: PlayerRef[], sport, availability?: AvailabilityCurve[], nextPickNo?: number, recentPicks, onPick, noAvailabilityReason?: string }`. It renders tiers (T063) with `PlayerFace`, position rank and, when `availability` and `nextPickNo` exist, a bar for "chance he's there at your next pick" (`--up` → `--down` by value, the exact % beside the bar). When `availability` is absent it shows `noAvailabilityReason`. When there's no next pick, it omits the bar. The run callout uses `positionRun` from `WEB/pickRun.ts`. Desktop/tablet: right-side column beside the board. Phone (< 768px): a persistent "Your pick" button opening a bottom sheet with a close control and the board still visible above.
- [ ] T066 [P] [US6] Test `WEB/components/YourPickPanel.test.tsx`: bars only with availability; reason shown without it; no bar when `nextPickNo` is undefined; the run callout appears when `positionRun` reports a run.
- [ ] T067 [US6] Simulator: `WEB/pages/DraftView.tsx`. Show `YourPickPanel` beside the board when the reveal is paused on your pick (where `onOpenPicker` is wired today, line ~543), fed by `result.availability`. Keep `PlayerPicker` reachable as "Full list". Keep the lower `AvailabilityPanel`.
- [ ] T068 [US6] Simulator loading: `WEB/pages/DraftView.tsx`. From first render until the first result, render a skeleton board plus "Simulating {iterations} drafts…" using `WEB/components/Skeleton.tsx` (`LoadingScreen` or `SkeletonRows`), so the panel is never blank. Measure time-to-message on prod-like data and record it (SC-007, ≤ 1s).
- [ ] T069 [US6] Mock: `WEB/pages/MockDraftView.tsx`. Replace the "Make your pick" button-first flow (line ~155) with `YourPickPanel` fed by `state.available`, with `noAvailabilityReason="Availability needs a simulation, which mock drafts don't run."` Keep the existing picker as "Full list".
- [ ] T070 [US6] Live room: `WEB/pages/LiveDraftView.tsx`. Show `YourPickPanel` when you are on the clock or next up, fed by the room's existing survival/availability data once your seat is known; before that, the reason "Availability appears once your seat is known."
- [ ] T071 [US6] Live check (quickstart §4e) in all three rooms at 3 sizes, NFL and NBA; record results.

---

## Phase 10: User Story 8 - Quick-read grades and verdicts (Priority: P3)

**Goal**: rank-based letter grades with the early badge; a steals/reaches board view.

**Independent Test**: ordering and early-badge tests pass; live at week 3 vs 4.

- [ ] T072 [US8] Create `BE/engine/SeasonWindow.java` with `public static final int EARLY_THRESHOLD_WEEKS = 4;` and its existing comment ("hand-set, arbitrary"). Change `BE/engine/SeasonSuperlativesService.java` to read `SeasonWindow.EARLY_THRESHOLD_WEEKS` and **delete** its own constant (line 35). Behavior is unchanged.
- [ ] T073 [P] [US8] Test `BT/engine/SeasonWindowSingleSourceTest.java`: scan `backend/src/main/java` sources for a declaration of `EARLY_THRESHOLD_WEEKS`; exactly one, in `SeasonWindow`.
- [ ] T074 [US8] Add to `config/weights.yml` a `draftsim.grades.cutoffs` block: an ordered list of `{maxPercentile, grade}` over rank percentile (0 = best) for A+, A, A-, B+, B, B-, C+, C, C-, D, F, under a comment "ARBITRARY: not fitted to anything". Bind it with `BE/config/GradeProperties.java` (`@ConfigurationProperties("draftsim.grades")`, validated: percentiles strictly increasing, last = 100). Record the chosen cutoffs in build-notes.
- [ ] T075 [US8] Create `BE/engine/LetterGrades.java`: `grade(rank, teamCount)` → grade string from `GradeProperties`, with percentile = (rank − 1) / max(1, teamCount − 1) × 100. Tied ranks share a grade.
- [ ] T076 [P] [US8] Test `BT/engine/LetterGradesTest.java`: for every pair, a higher rank never gets a lower grade (12- and 10-team leagues); ties share; rank 1 gets the top grade and the last rank the bottom; bad config (non-increasing cutoffs) fails to bind.
- [ ] T077 [US8] `BE/engine/RosterManagementService.java` and `BE/api/RosterManagementController.java`: each team row gets `grade` (rank by `efficiency` desc; null efficiency → null grade), and the payload gets `gradesEarly = weeksScored < SeasonWindow.EARLY_THRESHOLD_WEEKS`. Same commit: `RosterManagementTeam.grade?: string | null` and `RosterManagement.gradesEarly?: boolean` in `WEB/api.ts`.
- [ ] T078 [US8] `BE/engine/LeagueAnalysisService.java` and `BE/api/LeagueAnalysisController.java`: ranking-score rows get `grade` (rank by composite score desc) and `rankingScores.gradesEarly`. Same commit: matching optional fields in `WEB/api.ts` (`AnalysisRankingScores`).
- [ ] T079 [P] [US8] Backend tests: extend `BT/engine/LeagueAnalysisServiceTest.java` and add `BT/engine/RosterManagementGradesTest.java`. Grades follow rank order; `gradesEarly` is true at 3 scored weeks and false at 4.
- [ ] T080 [P] [US8] Create `WEB/components/GradeChip.tsx`: renders the grade beside the number it qualifies (never alone), with the existing "early — this is mostly noise" badge when `early`. Nothing renders when the grade is null or missing.
- [ ] T081 [US8] Wire `GradeChip` into `WEB/pages/LeagueAnalysis.tsx` and `WEB/pages/RosterManagement.tsx`; add tests in `WEB/pages/LeagueAnalysis.test.tsx` and `WEB/pages/RosterManagement.test.tsx` (early badge shown; no chip when the field is missing).
- [ ] T082 [US8] Steals/reaches: `WEB/pages/CompletedDraftBoard.tsx` plus `WEB/components/DraftBoard.tsx` get a toggle "Steals & reaches" that tints each pick cell by `pick_no − adp` with `--up` (later than ADP = steal) and `--down` (earlier = reach), showing the signed difference as text. A pick with no ADP (999 or null) stays untinted with the text "no ADP". Add an **ordering test**: a pick taken 20 after its ADP is never tinted as a reach (lessons class 1).
- [ ] T083 [US8] Live check: grades on Team strength and Bench points beside their numbers for NFL and NBA; record whether the early badge state matches `weeksScored`.

---

## Phase 11: User Story 9 - Fan-shaped versions of the remaining pages (Priority: P3)

**Goal**: trophy list, manager player card, scouting archetypes, standings columns, mock setup chips, sign-in welcome.

**Independent Test**: each page against its section of `claude/design-review-fan-first.md` §5, 3 sizes × 2 sports.

- [ ] T084 [US9] Backend: in `BE/engine/ExpectedWinsService.java` add `allPlay {wins, losses, ties}` and `median {wins, losses, ties}` to `TeamRow`, computed in the same walk as `expectedWins(List<Game>)` (line ~86). All-play compares each team to every other team each week. Median compares to that week's median score; equal to the median counts as a tie. Emit both in `BE/api/ExpectedWinsController.java` (line ~68). Same commit: `allPlay?` and `median?` on `ExpectedWinsTeam` in `WEB/api.ts`.
- [ ] T085 [P] [US9] Extend `BT/engine/ExpectedWinsServiceTest.java`: per team, all-play wins + losses + ties = weeks × (n − 1); league Σ all-play wins = Σ losses; a 4-team hand-built fixture with known median results.
- [ ] T086 [US9] Standings: `WEB/pages/LeagueHistory.tsx`. Show "season in progress" once, in the section title, not per row (`RANK_REASON` line ~290). Mark the best/worst numeric value per column with `--up`/`--down`. Add "Record vs all" and "Median record" columns for the current season from `getExpectedWins`, with "—" for other seasons and a `<HowThisWorks>` line saying why.
- [ ] T087 [P] [US9] Test in `WEB/pages/LeagueHistory.test.tsx`: in-progress text appears once; "—" for past seasons; the new columns render from the expected-wins fields.
- [ ] T088 [US9] Awards trophy list: `WEB/pages/Superlatives.tsx`. One row per award: `--fitted` trophy icon, award name, winner avatar and name, the stat in large tabular numbers, "See all" expanding in place (reuse the existing standings modal content inline). Early badges stay on their rows.
- [ ] T089 [P] [US9] Update `WEB/pages/Superlatives.test.tsx` for the row layout; keep every assertion about early badges and empty awards.
- [ ] T090 [US9] Manager profile header: `WEB/pages/ManagerHistory.tsx`. A player-card row with large avatar, name, career record, titles (trophy icons) and 3–4 headline stats, unboxed. League ranks are rendered as sentences ("3rd of 17 in lineup efficiency in West Coast Fantasy Football"). Draft tendencies sit under the header.
- [ ] T091 [US9] Archetypes: add `archetype(inputs: BehaviourInputs): { label: string; basis: 'reach' | 'tilt' | 'none' }` to `WEB/managerBehaviour.ts`, with labelled arbitrary cutoffs in that file. A reach label only when `picksScored > 0` **and** |relativeReachBias| exceeds both the cutoff and its std-err. Otherwise the strongest positional tilt above its cutoff ("Waits on QB", "Guards early"). Otherwise "Not enough history".
- [ ] T092 [P] [US9] Test `WEB/managerBehaviour.test.ts`: `picksScored = 0` never yields a reach label (the NBA case); the reach sign is not inverted (positive reach → "Reacher", lessons class 1); the tilt fallback; the none case.
- [ ] T093 [US9] Scouting report: `WEB/pages/ManagerTendencies.tsx` shows the archetype label per manager and sorts managers who share a league with the signed-in user first.
- [ ] T094 [P] [US9] Mock setup chips: `WEB/pages/MockSetup.tsx`. Seats as one row of chips on the surface; a real manager shows avatar plus archetype; "You" uses `--crimson-fill`; "Start the draft" is `--volt`. Update `WEB/pages/MockSetup.test.tsx`.
- [ ] T095 [P] [US9] Sign-in welcome: `WEB/pages/SignIn.tsx`. A centered hero with product name, one pitch line ("Your league's real managers, simulated. Know who's gone before you pick."), a large username field and a `--volt` Continue; keep the "no password" reassurance. Update `WEB/pages/SignIn.test.tsx`.
- [ ] T096 [US9] Live check each US9 page at 3 sizes × 2 sports; record in build-notes.

---

## Phase 12: Polish & cross-cutting

- [ ] T097 Full quickstart §4 matrix on every route (3 sizes × 2 sports): depth, scroll, contrast (§4g), addresses (§4h). Record all measured numbers in `SPEC/build-notes.md`.
- [ ] T098 Re-diff `SPEC/caveats-before.md` against the final UI and update `caveats-after.md`; zero unmapped rows.
- [ ] T099 [P] Run `cd web && npx tsc -b && npm test && npm run build` and `cd backend && ./gradlew test`; compare pass/skip counts with T001 and explain every difference in build-notes.
- [ ] T100 [P] Update `HANDOFF.md` and `README.md` (what changed, what is verified vs assumed, the open items from research "Open"). Add any durable lesson found during the build to `claude/lessons.md`.
- [ ] T101 Screenshots for proof (quickstart §5): home, power, weekly report, draft room at 1440 and 375, NFL and NBA.
- [ ] T102 Bug-hunting review of the full diff (AGENTS.md pipeline: not a style pass) and a fix pass for confirmed findings.

---

## Dependencies & execution order

### Phase dependencies
- **Setup (T001–T004)** → **Foundational (T005–T013)** → stories.
- **US1 (P1)** and **US2 (P1)** depend only on Foundational and can run in parallel; US2 touches `LeagueHistory.tsx`, which T022 also touches, so serialize those two.
- **US3** depends on Foundational. **US4** depends on US3 (the `home` row and grouped rail). **US5** depends on US1's T020 (same file).
- **US7** depends on Foundational. **US6** depends on US7 (`PlayerFace`) and US1's T026 (board cells).
- **US8** backend (T072–T079) is independent of every frontend story; T081 depends on US1's T016/T017 (same files); T082 depends on US1's T026.
- **US9**: T084–T087 depend on US2's T034 (same `LeagueHistory.tsx`); T088 depends on T021; T090 on T024; T093 on T023 and T091; T094 needs T091 and T006.
- **Polish** last.

### Within each story
Backend field → `api.ts` (same commit) → component → page wiring → tests green → live check.

### Parallel opportunities
- T003 and T004 (different outputs).
- T010, T011, T012 (different files) after T005–T009.
- US1 page tasks T014–T027 are all different files: run them in parallel, then T028–T030.
- US8 backend (T072–T079) in parallel with any frontend story.
- T058–T061 after T057; T063–T064 alongside US7.

## Parallel example: User Story 1

```text
After Foundational, launch together (each a separate Sonnet coding agent, each
reading contracts/ui-rules.md first):
  T015 PowerRankings.tsx   T016 LeagueAnalysis.tsx   T017 RosterManagement.tsx
  T018 ExpectedWins.tsx    T019 SeasonForecast.tsx   T023 ManagerTendencies.tsx
Then serially: T028 tests → T029 caveats-after → T030 live check.
```

## Implementation strategy

### MVP first
1. Setup + Foundational.
2. US1 (+ US2, it's small): the site stops looking engineery, and fans stop seeing commissioner controls.
3. **Stop and live-verify** (T030, T037). This alone answers the original request ("less engineery, fewer boxes, color").

### Incremental delivery (each phase live-checked before the next, per clarification)
- Phase A (look and wording): US1, US2
- Phase B (navigation and you-first): US3 → US4, US5
- Phase C (edge features): US7 → US6, US8, US9

### Commit cadence
Commit at the end of each story phase **only when Allan asks** (AGENTS.md). Before each phase, bring the branch up to date with `main` (concurrent sessions share this tree).

## Notes
- [P] = different files, no unfinished dependency.
- Every "live check" task records measured numbers. "Looks fine" is not a result.
- If a task finds the spec or plan wrong, add an "Amended during build" note to the doc instead of silently diverging.
