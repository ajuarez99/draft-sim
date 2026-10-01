---

description: "Task list for spec 013: fan-first redesign"
---

# Tasks: Fan-first redesign

**Input**: Design documents from `specs/013-fan-first-redesign/`

**Prerequisites**: [plan.md](plan.md) (read its "Amended after adversarial review" table first; it overrides older text), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

> **Amended after adversarial review (2026-09-30).** This list was regenerated from
> the review's decisions (plan.md B1–B6, S1–S18, N1–N5):
> - `YourPickPanel` is gone; `AvailabilityPanel` is extended instead (S10).
> - US1 page tasks are sequential, because they all touch `styles.css` (S16).
> - Four fields were added to the backend work (B1–B3).
> - The site-home record/rank and the T041 switcher move were dropped (S17, S4).
>
> T001–T004 keep their IDs and status. Later IDs are renumbered.

**Tests**: included (spec + quickstart §3). A live browser check closes every story.

**Organization**: grouped by user story. US7 (faces) runs before US6 (draft room), both P3.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallel-safe (different files **and** no shared `styles.css` edits, no unfinished dependency)
- Paths: **`BE/`** = `backend/src/main/java/com/ballknowers/draftsim/`, **`BT/`** = `backend/src/test/java/com/ballknowers/draftsim/`, **`WEB/`** = `web/src/`, **`SPEC/`** = `specs/013-fan-first-redesign/`

## Rules every task inherits

- Read [contracts/ui-rules.md](contracts/ui-rules.md) before any UI task. It is the acceptance contract.
- Never delete a caveat. Move it beside its number (visible on touch too, not tooltip-only) or into `<HowThisWorks>`.
- `WEB/api.ts` changes in the **same commit** as the Java it mirrors. New fields are optional in TS. Degradation follows [contracts/api-additions.md](contracts/api-additions.md).
- No page address changes. No migration. The commissioner key, the backfill server gate, reversal-round gating and `SimulationResult` are unchanged.
- Hand-set numbers are labelled arbitrary where they live. Choose once and record value + reason in `SPEC/build-notes.md`.
- **Sport is never defaulted** in new code (memory: optional params that encode rules).
- `styles.css` edits: each task adds or edits only a clearly commented section for its page or component. Never run two `styles.css` tasks concurrently.
- After a Java change, restart `bk013-api`. After restarting `bk013-web`, hard-refresh tabs.
- Coding subagents run on Sonnet. The parent reads every diff before ticking a task.

---

## Phase 1: Setup (baselines)

- [X] T001 Run `cd web && npx tsc -b && npm test && npm run build` and `cd backend && ./gradlew test`. Record pass, fail and **skipped** counts in `SPEC/build-notes.md`; if ITs were skipped, start Postgres (5433) and re-run before recording.
- [X] T002 Re-ingest `POST /api/ingest/all/1346366555759341568` (NFL) and `POST /api/ingest/all/1339351318115946496` (NBA) on the local backend; note the result in `SPEC/build-notes.md`.
- [X] T003 [P] Build the caveat inventory **from source** (conditional caveats included): every caveat sentence, badge and methodology paragraph as `C### | kind | file:line | condition | exact text` in `SPEC/caveats-before.md`.
- [X] T004 [P] Measure baselines at 1440×900 for every route (NFL league): nested-surface depth (quickstart §4a script), horizontal scroll (§4b). Append a table to `SPEC/build-notes.md`, labelled measured.

---

## Phase 2: Foundational (blocks every story)

- [X] T005 Tokens in `:root` of `WEB/styles.css`:
  - add `--volt: oklch(88% 0.19 125)`, `--up: oklch(74% 0.16 150)`, `--row-hover: oklch(22% 0.03 255)`, and `--down` at **hue 350** (start `oklch(68% 0.18 350)`);
  - set `--bg: oklch(14% 0.025 255)` and `--panel: oklch(19% 0.03 255)`.

  Each token gets a one-line comment naming its single meaning.
- [X] T006 Contrast tokens in `WEB/styles.css`:
  - `--crimson-fill` (crimson darkened until `--text` on it is ≥ 4.5:1);
  - `--crimson-text` (crimson lightened until it is ≥ 4.5:1 on both `--bg` and `--panel`);
  - `--down` adjusted until ≥ 4.5:1 on `--panel`.

  Then apply them:
  - crimson *fills* behind text (`.col-head.mine` and other `.mine`/`.you` fills) → `--crimson-fill`;
  - crimson *text* (`.rank-grid-rank.mine`, `.seat .who.mine-name`, `.col-head-you`, and any other `color: var(--crimson)`) → `--crimson-text`;
  - `.rank-move.down` (~line 454) → `--down`.

  Leave error colors alone and note that in build-notes. Record every final value and its measured ratio.
- [X] T007 In `WEB/styles.css`, change `.mono` to `font-family: inherit; font-variant-numeric: tabular-nums;` and add `.code` (the old monospace stack). Switch only real ids/URLs in `WEB/` to `.code`. Then check the draft board header names still fit the `minmax(96px)` columns at 1440 (`.col-head-name` ellipsis); fix with ellipsis, not by widening.
- [X] T008 Section titles:
  - add `.section-title` (Plus Jakarta 600, sentence case, ~15px, `--text`) to `WEB/styles.css`, and point `.panel h2` at the same declarations;
  - change section headings in `WEB/` that use `.cond` (`h2.cond`/`h3.cond`/`h4.cond` inside sections, ~70 sites) to `.section-title`;
  - keep `.cond` for page titles and hero numbers;
  - review the other Oswald rules at styles.css ~269, 358, 362, 375, 433, 1707, 1933, 2230, 2721, 2916, and keep each only if it styles a page title or hero.
- [X] T009 Add surface utilities to `WEB/styles.css`:
  - `.section` (unboxed: heading + spacing);
  - `.row-list` (1px `--line` divider *between* rows via `border-bottom` on all but the last, `--row-hover` on hover, no per-row box);
  - `.panel .panel { border: 0; background: transparent; box-shadow: none; padding: 0; }` with a comment that modals use `.modal-card`, not `.panel`.

  Check that `WEB/pages/PowerRankings.verify.tsx` (nested panels) still renders sensibly.
- [X] T010 Create `WEB/components/HowThisWorks.tsx` (`<details className="how">`, summary "How this works", children body, closed by default) and its `.how` styles in `WEB/styles.css` (muted, small, no box).
- [X] T011 [P] Test `WEB/components/HowThisWorks.test.tsx`: closed by default, opens on click, children present when open.
- [X] T012 [P] `WEB/components/PageHeader.tsx`: document `sub` as "one sentence, the takeaway", and `console.warn` in dev when a string `sub` contains a forbidden word from contracts/ui-rules.md.
- [X] T013 Replace the house-style header comment in `WEB/styles.css` with contracts/ui-rules.md's rules (surfaces, type, one meaning per color including `--crimson-fill`/`--crimson-text`/`--down` hue 350, words, nav, faces, sizes, commissioner). Keep the STACKING and LAYOUT sections. Re-measure the 22% position-tint cell against the new `--panel` and update the header's "12:1" claim with the measured value.

**Checkpoint**: `npx tsc -b && npm test && npm run build` green, plus a visual spot check of home and board.

---

## Phase 3: User Story 1 - Every page reads like a fan tool (Priority: P1) 🎯 MVP

**Goal**: two surfaces per page, takeaway subtitles, methods under How this works, every caveat kept.
**Independent test**: harness `depth ≤ 2`, `gridDepth ≤ 2` on every route; one-sentence subtitles; `caveats-after.md` maps every row.

Every page task does the same five things:
1. Rewrite the `PageHeader` `sub` as one fan sentence, and set the page title to the fan name.
2. Move methodology into `<HowThisWorks>` at the end of its section.
3. Keep caveat badges beside their numbers, visibly.
4. Remove nested boxes with `.section` and `.row-list`.
5. Use `--up`/`--down` for better/worse and `--volt` for at most one primary action.

**Run these tasks one at a time, in order** (all of them touch `styles.css`).

- [X] T014 [US1] Site home `WEB/pages/DraftPicker.tsx`: unbox the "Your leagues", "From Sleeper" and "Mock drafts" sections; "Start a mock draft" is the one `--volt`.
- [X] T015 [US1] Power rankings `WEB/pages/PowerRankings.tsx`: hero headline unboxed; Riser/Free fall as inline callouts with `--up`/`--down` arrows plus signed numbers; ladder as `.row-list`.
- [X] T016 [US1] Team strength `WEB/pages/LeagueAnalysis.tsx` (title "Team strength"): one-sentence subtitle. "Raw" sub-figures and composite method go into `<HowThisWorks>`, which also states that rankings appear after 3 scored weeks. Heat-tint best/worst High/Low.
- [X] T017 [US1] Bench points `WEB/pages/RosterManagement.tsx` (title "Bench points"): single bar color; "left N on the bench" in `--down` for the three largest gaps; method into `<HowThisWorks>`.
- [X] T018 [US1] Luck `WEB/pages/ExpectedWins.tsx` (title "Luck"): the subtitle names the luckiest team **only when `weeksScored` ≥ 4**; otherwise a neutral sentence plus the early badge. The schedule footnote goes into `<HowThisWorks>`.
- [X] T019 [US1] Playoff odds `WEB/pages/SeasonForecast.tsx` (title "Playoff odds"):
  - subtitle;
  - refusal copy per reason: `NOT_COMPUTED` → "Odds appear when the commissioner updates them."; keep the existing text for no-scored-week and `UNMODELLED_SEEDING`;
  - keep StaleNotice;
  - the "stored simulation" explanation goes into `<HowThisWorks>`.
- [X] T020 [US1] Weekly report `WEB/pages/WeeklyReport.tsx` (title "Matchups & awards"): copy and surfaces only; awards cards → `.row-list`.
- [X] T021 [US1] Awards `WEB/pages/Superlatives.tsx` (title "Awards"): copy and surfaces only; remove per-card colored top borders and panel-in-panel.
- [X] T022 [US1] Standings `WEB/pages/LeagueHistory.tsx` (title stays the league name, eyebrow "Standings"): subtitle; the draft-opinion paragraph goes into `<HowThisWorks>`; tables on the surface.
- [X] T023 [US1] Scouting report `WEB/pages/ManagerTendencies.tsx` (title "Scouting report"): the standard-error lede goes into `<HowThisWorks>`; **`(±n)` stays visible** beside the reach text.
- [X] T024 [US1] Manager profile `WEB/pages/ManagerHistory.tsx`: method paragraph → `<HowThisWorks>`; stat boxes → one unboxed row.
- [X] T025 [US1] Manager comparison `WEB/pages/ManagerComparison.tsx`: shared rules only.
- [X] T026 [US1] Draft board `WEB/components/DraftBoard.tsx` plus its `.board`/`.cell` CSS: the cell's position tint is its only surface (drop the inner border); soften empty future cells. Must reach `gridDepth ≤ 2`.
- [X] T027 [US1] `WEB/pages/MockSetup.tsx`, `WEB/pages/CompletedDraftBoard.tsx`, `WEB/pages/MockDraftView.tsx`, `WEB/pages/DraftView.tsx`, `WEB/pages/LiveDraftView.tsx`: subtitles/surfaces only (pick-panel work is US6).
- [X] T028 [US1] `cd web && npm test`: fix tests that asserted old copy by updating strings, **never deleting caveat assertions**.
- [X] T029 [US1] Write `SPEC/caveats-after.md`: every `caveats-before.md` row → its new location. Restore any unmapped row before continuing.
- [X] T030 [US1] Harness (quickstart §4a/§4b) on every route at 1440, 768 and 375, NFL and NBA; record in build-notes; fix failures.

---

## Phase 4: User Story 2 - Only commissioners see commissioner controls (Priority: P1)

- [X] T031 [US2] `BE/api/LeagueHistoryController.java` `history()` (line ~87):
  - add `canCommission` = `membership.canCommission(<visibleLeague row>.id(), sleeperUserId)`, reading `X-Sleeper-User` like `ballot()` (~662);
  - add `isMe` to each standings row (row's manager id == caller's manager id via the managers repository's `idsBySleeperUserId`; false when signed out).

  Use mutable map puts. Same commit: `canCommission?: boolean` on the history type and `isMe?: boolean` on `StandingRow` in `WEB/api.ts`.
- [X] T032 [P] [US2] `BT/api/LeagueHistoryCanCommissionIT.java`: `canCommission` is true for the Sleeper commissioner and the configured owner, false for a member and with no header; `isMe` is true on exactly the caller's row.
- [X] T033 [US2] `WEB/pages/LeagueHistory.tsx` `RankCell` (~296): Compute only when `canCommission === true`. Otherwise the text is "Final ranks appear once the commissioner computes them." (FR-011). Missing field → hidden.
- [X] T034 [P] [US2] `WEB/pages/LeagueHistory.test.tsx`: Compute hidden when false/missing, shown when true; the non-commissioner message.
- [X] T035 [US2] Write the write-control audit in `SPEC/build-notes.md`, covering every non-GET call in `WEB/api.ts` that writes league-level data, with its UI gate and server gate (including `setReversalRound`, `refreshLeague`, `ingestLeague`, `trackDraft`). Record `setReversalRound` as an open follow-up (out of scope, review S2).
- [X] T036 [US2] Live check (quickstart §4c) as popsharky and, **with Allan's OK**, as a non-commissioner member.

---

## Phase 5: User Story 3 - Navigation grouped by what a fan is doing (Priority: P2)

- [ ] T037 [US3] `WEB/destinations.ts`:
  - add a required `group: 'home' | 'thisWeek' | 'season' | 'draft' | 'history'` and an optional `formerLabel`;
  - groups and labels: thisWeek = weeklyReport "Matchups & awards", power "Power rankings"; season = analysis "Team strength", expectedWins "Luck", rosterManagement "Bench points", forecast "Playoff odds", superlatives "Awards"; draft = board/live/mock (labels unchanged); history = history "Standings";
  - each old label → `formerLabel`.
- [ ] T038 [P] [US3] `WEB/destinations.test.ts`: every row has a group; every non-home group is non-empty for an NFL league; `formerLabel` ≠ `label`.
- [ ] T039 [US3] `WEB/components/LeagueRailSection.tsx`:
  - render the four groups in order with headings; the current group is expanded, and other groups' state is kept per viewer in `localStorage` (try/catch, default expanded);
  - **phone lane (~470-490): all groups expanded**, headings as small separators;
  - collapsed desktop rail: keep the existing `title`/`aria-label` (~322-342), now fed by the fan labels.
- [ ] T040 [P] [US3] `WEB/searchIndex.ts`: match `label` or `formerLabel`. Tests: `WEB/searchIndex.test.ts` ("expected wins" → Luck), `WEB/components/JumpTo.test.tsx`, `WEB/components/AppShellJumpTo.test.tsx`.
- [ ] T041 [P] [US3] Update `WEB/components/LeagueRailSection.test.tsx` for groups and fan labels; add a test that groups are all expanded in the phone lane.
- [ ] T042 [US3] Live check at 3 sizes × 2 sports: ≤ 2 clicks/taps between any league pages (SC-004), and every old address works (§4h).

---

## Phase 6: User Story 4 - A league home that starts with you (Priority: P2)

- [X] T043 [US4] Backend: add `isMe` to `WeeklySide` in the weekly-report response (owner rule as in `LeagueAnalysisService.java:687`), in `BE/` (weekly report service/controller). Same commit: `isMe?: boolean` on `WeeklySide` in `WEB/api.ts`. Extend `BT/api/WeeklyReportShapeTest.java`: exactly one side is `isMe` for the caller's matchup, none for a non-member.
- [ ] T044 [US4] Create `WEB/pages/LeagueHome.tsx`, composed from:
  - (a) `getLeagueHistory`: your record and **standings position** from the current season's `isMe` row, plus a top-5 snippet;
  - (b) `getWeeklyReport(id, 0)`: your latest matchup via `WeeklySide.isMe`;
  - (c) NFL only: next opponent from `getLeagueAnalysis` matchups `isMe`;
  - (d) the power headline from the existing power response (verify which field carries it; record it);
  - (e) the top award from `fetchSuperlatives`.

  Each block loads and fails on its own. No "you" blocks for a non-member. Pre-season: draft status plus a draft-room link.
- [ ] T045 [US4] Route `/leagues/:sleeperLeagueId` in `WEB/App.tsx`. `home` row in `WEB/destinations.ts` (group `home`, "League home", sports `['nfl','nba']` listed explicitly). `switchTarget`'s fallback (`LeagueRailSection.tsx:150`) → `home`.
- [ ] T046 [P] [US4] `WEB/pages/LeagueHome.test.tsx`: member sees record/position/latest matchup; NFL adds the next opponent and NBA doesn't; non-member sees no "you" blocks; one failing endpoint leaves the others; pre-season shows draft status.
- [ ] T047 [US4] Site home rows `WEB/pages/DraftPicker.tsx`: one row per league (initial/avatar, name, sport pill, season, draft status, one action → league home). Season pills and Refresh leave the row. The hero greets the user. **No record/rank** (FR-017 amended).
- [ ] T048 [P] [US4] Update `WEB/pages/DraftPicker.test.tsx`, keeping behavioural assertions.
- [ ] T049 [US4] Live check (§4d) at 3 sizes × 2 sports.

---

## Phase 7: User Story 5 - Weekly report leads with your matchup (Priority: P2)

- [ ] T050 [US5] `WEB/pages/WeeklyReport.tsx`: the matchup with an `isMe` side goes first, full width (avatars, large tabular scores, win/loss with `--up`/`--down` and the words "Won"/"Lost"). The rest is a compact scoreboard strip; awards and top performers form one "Week in review" `.row-list`. Missing `isMe` → today's order.
- [ ] T051 [US5] Same file: replace the numeric input (~92) with ‹ Week N › (disabled at 1 and at `latestScoredWeek`). Label: "Week N · latest final week", plus "Week N+1 in progress →" when a later week is scored and not final.
- [ ] T052 [P] [US5] `WEB/pages/WeeklyReport.test.tsx`: yours first; no `isMe` keeps the order; stepper bounds; the label for week 2 shown with week 3 scored and not final.
- [ ] T053 [US5] Live check at 3 sizes × 2 sports.

---

## Phase 8: User Story 7 - Players have faces (Priority: P3)

- [ ] T054 [US7] `WEB/components/PlayerFace.tsx`:
  - props `{ sport, sleeperId, team, position, name, size }` (sport required);
  - states only ever go `photo → logo → initials`, advancing on `onError`;
  - photo `https://sleepercdn.com/content/{sport}/players/thumb/{sleeperId}.jpg`; logo `https://sleepercdn.com/images/team_logos/{sport}/{team.toLowerCase()}.png`;
  - `DEF` starts at logo; null team skips logo;
  - fixed size, `loading="lazy"`, `alt={name}`.
- [ ] T055 [P] [US7] `WEB/components/PlayerFace.test.tsx`: photo→logo→initials; DEF starts at logo; null team → initials; NBA URL.
- [ ] T056 [US7] Wire into `WEB/components/DraftBoard.tsx` cells (small, no layout shift, still `gridDepth ≤ 2`, since the face is an image leaf, not a surface).
- [ ] T057 [US7] Wire into `WEB/components/PlayerPicker.tsx`, `WEB/components/OnTheClockPickInput.tsx`, `WEB/components/PickFeed.tsx` and `WEB/components/TeamStrip.tsx`.
- [ ] T058 [US7] Weekly top performers (`WEB/pages/WeeklyReport.tsx`) and player rows in `WEB/pages/Superlatives.tsx`: first verify whether `WeeklyPerformer.playerId` and the superlative player ids are Sleeper ids (record the answer). Use `PlayerFace` only if they are; otherwise logo/initials.
- [ ] T059 [US7] Live check (§4f), NFL board plus NBA mock: count broken images and empty cells.

---

## Phase 9: User Story 6 - "Who should I take?" stays on screen (Priority: P3)

- [ ] T060 [P] [US6] `WEB/tiers.ts`: `export const TIER_ADP_GAP` ("ARBITRARY, hand-set display grouping"; record the value) and `tierPlayers(players)` (sort by ADP; a new tier when the gap > the constant; 999 → "Unranked").
- [ ] T061 [P] [US6] `WEB/tiers.test.ts`: a gap equal to the constant doesn't split; one greater does; 999 → Unranked; empty → empty.
- [ ] T062 [US6] Remove the `sport` default from `positionRun` in `WEB/pickRun.ts` (all current callers already pass it: `PickFeed.tsx:64`, `scarcity.ts:78`). Fix the type errors and tests.
- [ ] T063 [US6] Extend `WEB/components/AvailabilityPanel.tsx` (don't create a second panel):
  - group its rows with `tierPlayers` under tier headings, and show `PlayerFace`;
  - keep its existing survival % and Act now/Coin flip/Safe verdict exactly;
  - accept `availability` as optional plus `noAvailabilityReason?: string` (shown instead of survival when absent), so a mock can list best-available players by tier without curves;
  - add the run callout via `positionRun(..., sport)`.

  Respect the stacking order in the `styles.css` header (`.avail-sheet` layer 4; phone `.pick-card` 44).
- [ ] T064 [P] [US6] `WEB/components/AvailabilityPanel.test.tsx` (create if absent): tier headings; verdict unchanged for known survival values; reason shown without availability; run callout.
- [ ] T065 [US6] Simulator `WEB/pages/DraftView.tsx`:
  - while `getSeats` is pending (the `seats && …` branch ~549), render a skeleton board via `WEB/components/Skeleton.tsx`, never a blank panel;
  - keep the existing running overlay (~589-594);
  - no "running" text before Start;
  - measure time-to-skeleton (SC-007) and record it.
- [ ] T066 [US6] Mock `WEB/pages/MockDraftView.tsx`: when on the clock, show the extended `AvailabilityPanel` fed by `state.available` with `noAvailabilityReason="Availability needs a simulation, which mock drafts don't run."`. The existing picker stays as "Full list".
- [ ] T067 [US6] Live room `WEB/pages/LiveDraftView.tsx`: confirm the extended panel (already mounted ~890) shows tiers and faces. Before the seat is known: "Availability appears once your seat is known."
- [ ] T068 [US6] Live check (§4e), all three rooms × 3 sizes × 2 sports.

---

## Phase 10: User Story 8 - Quick-read grades and verdicts (Priority: P3)

- [X] T069 [US8] `BE/engine/SeasonWindow.java` with `public static final int EARLY_THRESHOLD_WEEKS = 4` (comment: hand-set, arbitrary). `BE/engine/SeasonSuperlativesService.java` reads it, and its own constant (line 35) is **deleted**.
- [X] T070 [P] [US8] `BT/engine/SeasonWindowSingleSourceTest.java`: exactly one declaration of `EARLY_THRESHOLD_WEEKS` under `backend/src/main/java`.
- [X] T071 [US8] Grade config:
  - `config/weights.yml`: a `draftsim.grades.cutoffs` block (an ordered list of `{maxPercentile, grade}`, A+ … F) with the comment "ARBITRARY: not fitted";
  - `BE/config/GradeProperties.java`, registered in `BE/DraftSimApplication.java`'s `@EnableConfigurationProperties`;
  - **a missing block binds to empty → all grades null and startup still succeeds**; a present block is validated (percentiles strictly increasing, last = 100);
  - add `gradesLoaded` to `/api/health`;
  - record the cutoffs in build-notes.
- [X] T072 [US8] `BE/engine/LetterGrades.java`: `grade(rank, teamCount)` with percentile = (rank − 1) / max(1, teamCount − 1) × 100; tied ranks share; empty config → null.
- [X] T073 [P] [US8] `BT/engine/LetterGradesTest.java`:
  - higher rank never gets a lower grade (10/12/14 teams);
  - ties share;
  - extremes get the first/last grade;
  - an invalid present block fails;
  - a missing block gives null.
- [X] T074 [US8] Bench points:
  - `BE/engine/RosterManagementService.java` and `BE/api/RosterManagementController.java`: per-team `grade` (rank by efficiency desc; null efficiency → null) and `gradesEarly`;
  - fix positional `TeamRow` constructions in `BT/api/LeagueAnalyticsContractTest.java`;
  - same commit: `WEB/api.ts`.
- [X] T075 [US8] Team strength (NFL only): `BE/engine/LeagueAnalysisService.java` adds a `grade` component to `ScoreEntry` (rank by composite score) and `gradesEarly` to the ranking-scores block. Fix every `ScoreEntry` construction (`BT/engine/LeagueAnalysisServiceTest.java`, `BT/api/LeagueAnalyticsContractTest.java`). Same commit: `WEB/api.ts` (`entries[].grade`, `gradesEarly`).
- [X] T076 [P] [US8] Backend tests: grade order follows rank; `gradesEarly` is true at 3 weeks and false at 4, for both services.
- [X] T077 [US8] Steals/reaches data:
  - add `adpAtDraft` (from `draft_pick.adp_at_time`, null-safe) to the real-board pick in `BE/api/LeagueController.java` (~313-341);
  - same commit: `RealPick.adpAtDraft?: number | null` in `WEB/api.ts`;
  - test in `BT/api/LeagueControllerRealBoardTest.java`: the value comes from `adp_at_time`, not the current board; null when absent.
- [ ] T078 [P] [US8] `WEB/components/GradeChip.tsx`: a grade beside its number (never alone), with the existing early badge when `early`; null/missing → nothing.
- [ ] T079 [US8] Wire `GradeChip` into `WEB/pages/LeagueAnalysis.tsx` and `WEB/pages/RosterManagement.tsx`. The How this works on Team strength states both thresholds (rankings after 3 weeks; grades early under 4). Tests in `WEB/pages/LeagueAnalysis.test.tsx` and `WEB/pages/RosterManagement.test.tsx`.
- [ ] T080 [US8] Steals & reaches toggle in `WEB/pages/CompletedDraftBoard.tsx` and `WEB/components/DraftBoard.tsx`:
  - tint by `pickNo − adpAtDraft`: positive (taken after ADP) = steal → `--up`; negative = reach → `--down`;
  - the signed difference is shown as text;
  - null → untinted, "no ADP at draft time";
  - the toggle is hidden when no pick has `adpAtDraft`.

  **Ordering test:** a pick 20 after its draft-time ADP is never tinted as a reach.
- [ ] T081 [US8] Live check: grades on Bench points (NFL + NBA) and Team strength (NFL); early state matches `weeksScored`; steals/reaches on the NFL board and the count of untinted picks.

---

## Phase 11: User Story 9 - Fan-shaped versions of the remaining pages (Priority: P3)

- [X] T082 [US9] `BE/engine/ExpectedWinsService.java`: add `allPlay` and `median` (wins/losses/ties) to `TeamRow` from the same walk as `expectedWins(List<Game>)` (~86), using only rosters scored that week. Emit them in `BE/api/ExpectedWinsController.java` (~68). Fix the positional `ExpectedWinsService.TeamRow` in `BT/api/LeagueAnalyticsContractTest.java:182`. Same commit: `WEB/api.ts`.
- [X] T083 [P] [US9] `BT/engine/ExpectedWinsServiceTest.java`:
  - per team, total = Σ over weeks played of (n_w − 1);
  - league Σ wins = Σ losses;
  - a bye week;
  - an odd-n fixture where the median roster ties.
- [ ] T084 [US9] Standings `WEB/pages/LeagueHistory.tsx`:
  - "season in progress" once, in the section title;
  - best/worst per numeric column;
  - "Record vs all (reg. season)" and "Vs weekly median (reg. season)" (median games only, NOT added to the real record; ffwrapped's "Median record" adds them, so say so in How this works) filled only where expected-wins `season` equals the row's season, otherwise "—";
  - `<HowThisWorks>` explains both.
- [ ] T085 [P] [US9] `WEB/pages/LeagueHistory.test.tsx`: in-progress text appears once; "—" when the season differs; columns render.
- [ ] T086 [US9] Awards trophy list `WEB/pages/Superlatives.tsx`: one row per award (`--fitted` trophy, name, winner avatar + name, the stat in big tabular numbers, "See all" expanding in place). Early badges stay.
- [ ] T087 [P] [US9] `WEB/pages/Superlatives.test.tsx`: update layout assertions; keep every early-badge and empty-award assertion.
- [ ] T088 [US9] Manager profile header `WEB/pages/ManagerHistory.tsx`: a player-card row (avatar, name, career record, trophies, 3–4 headline stats); ranks as sentences; tendencies beneath.
- [ ] T089 [US9] `WEB/managerBehaviour.ts`: `archetype(inputs)` built on `relativeReachRead(...).kind` (`early` → "Reacher", `late` → "Waits", `room` → "Drafts like the room", `thin`/null → fall through to tilt). Tilt above a labelled cutoff gives e.g. "QB early"; otherwise "Not enough history". Returns `{ label, basis }`.
- [ ] T090 [P] [US9] `WEB/managerBehaviour.test.ts`:
  - `picksScored = 0` / `thin` never gives a reach label (the NBA case);
  - `early` → "Reacher" (sign not inverted);
  - tilt fallback;
  - none.
- [ ] T091 [US9] `WEB/pages/ManagerTendencies.tsx`: archetype label per manager; managers in the rail's **currently selected league** first.
- [ ] T092 [US9] `WEB/pages/MockSetup.tsx` plus test: a single row of seat chips; a real manager shows avatar plus archetype; "You" uses `--crimson-fill`; "Start the draft" is `--volt`.
- [ ] T093 [US9] `WEB/pages/SignIn.tsx` plus test: a centered welcome, pitch "Your league's real managers, simulated. See who's likely gone before you pick.", a big field, a `--volt` Continue, and the "no password" line kept.
- [ ] T094 [US9] Live check of each US9 page at 3 sizes × 2 sports.

---

## Phase 12: Polish & cross-cutting

- [ ] T095 Full quickstart §4 matrix on every route (3 sizes × 2 sports): depth, gridDepth, scroll, contrast (§4g, including `--crimson-text` and `--down`), addresses. Record the numbers.
- [ ] T096 Final caveat diff: `caveats-after.md` has zero unmapped rows.
- [ ] T097 [P] Full suites (web tsc/test/build, backend test); compare with T001's pass/skip counts and explain the differences.
- [ ] T098 [P] `HANDOFF.md`, `README.md` and `claude/lessons.md`: what changed, verified vs assumed, open items (week 3 not final, co-commissioners, reversal-round gating, deploy order backend → frontend).
- [ ] T099 Proof screenshots (quickstart §5).
- [ ] T100 Bug-hunting review of the full diff (session model, not a style pass), plus a fix pass on confirmed findings.

---

## Dependencies & execution order

- Setup → Foundational (T005–T013, all sequential except T011/T012) → stories.
- **US1** tasks run sequentially. **US2** (T031–T036) can run beside US1 except T033/T034, which wait for T022 (same file).
- **US3** → **US4** (the home row and switcher fallback need the grouped table). **US5** after T020 and T043.
- **US7** → **US6**. US6's T063 after T026 (board) and T054.
- **US8** backend (T069–T077) is independent of the frontend; T079 after T016/T017; T080 after T026 and T077.
- **US9**: T084 after T033; T086 after T021; T088 after T024; T091 after T023 and T089; T092 after T089 and T006.
- Polish last.

Parallel-safe: backend tasks alongside frontend tasks; test-only tasks marked [P]; T060/T061 alongside US7.

## Implementation strategy

Phase A (US1, US2) → live check → Phase B (US3, US4, US5) → live check → Phase C (US7, US6, US8, US9) → live check → Polish. Commit at each phase end. Bring the branch up to date with `main` before each phase. Deploy the backend before the frontend.
