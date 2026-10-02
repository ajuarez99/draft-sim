---

description: "Task list for spec 015: Player spotlight on the home page, one tab per league"
---

# Tasks: Player spotlight on the home page, one tab per league

**Input**: Design documents from `specs/015-home-roster-players/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/home-spotlight-ui.md](contracts/home-spotlight-ui.md), [quickstart.md](quickstart.md)

**Tests**: included. The plan and the UI contract name the test files (U1–U11), and this repo does not
call a feature built without them. The tests sit before implementation within each story.

**Ground rules for whoever builds this** (from AGENTS.md and the plan):
- Frontend only. **No** Java production code, **no** migration, **no** change to `web/src/api.ts`. If
  a task seems to need one, stop and add an "amended after build" note to research.md instead.
- The new component must contain **no sport literal**: no quoted `nba`/`nfl`/`NBA`/`NFL`, and no
  `Sport.NBA`/`Sport.NFL`. What renders and what is fetched follows `playersPlayMultiplePerPeriod`,
  `topOfNight` and `period` from the payload.
- Coding subagents run on Sonnet. Ask before committing. Work only in this worktree
  (`.claude/worktrees/015-home-roster-players`).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependencies)
- **[Story]**: which user story the task belongs to (US1, US2, US3)

---

## Phase 1: Setup

**Purpose**: confirm the baseline is green before anything moves, so a later red is ours.

- [X] T001 Record the baseline: run `cd web && npx tsc -b && npm test -- --run` and `cd backend && ./gradlew test --tests '*NoSportNameInPlayerSpotlight*'`. Note the pass, fail and skip counts in a scratch note. Check that the source-scan test **ran** (not skipped); a suite that skips when Postgres is down is a known trap in this repo.

---

## Phase 2: Foundational (blocks every story)

**Purpose**: make the spotlight's lists and data hook reusable outside `LeagueHome`, with **zero
behaviour change** on the league home.

- [X] T002 Move `useBlock` and `export type Block<T>` verbatim from `web/src/pages/LeagueHome.tsx` (around lines 57–80) into a new `web/src/useBlock.ts`, exporting both. In `LeagueHome.tsx` import them from `../useBlock` and keep `export type { Block } from '../useBlock'` so existing imports keep compiling. Update `web/src/components/PlayerSpotlight.tsx` and `web/src/components/PlayerSpotlight.test.tsx` to import `Block` from `../useBlock`. Then `npx tsc -b && npm test -- --run` must match T001's counts. Research R8.
- [X] T003 Split `web/src/components/PlayerSpotlight.tsx` (research R3). Add a named export `SpotlightLists({ spotlight, weekly, idPrefix }: { spotlight: PlayerSpotlightApplicable; weekly: Block<WeeklyReport>; idPrefix: string })`. It renders the phone segmented control (`.lh-spot-tabs`) plus `.lh-spot-cols`, which today sit inside `PlayerSpotlight`'s `<section>`, with every element id built from `idPrefix`: `${idPrefix}-spot-tab-${key}` and `${idPrefix}-spot-panel-${key}`, including the matching `aria-controls`/`aria-labelledby` inside `Section`. Keep the default export `PlayerSpotlight({ spotlight, weekly })` with the same signature. It returns `null` when `!spotlight.applies` (unchanged) and otherwise renders the same `<section className="section lh-spotlight" aria-labelledby="lh-spotlight-h">` + `<h2>` around `<SpotlightLists idPrefix="lh" …/>`, so the league home's ids stay `lh-spot-tab-*` and `lh-spot-panel-*` exactly as today. The tab/localStorage state (`TAB_KEY = 'lh-spotlight-tab'`) moves into `SpotlightLists`, still shared across both pages.
- [X] T004 [P] In `web/src/components/PlayerSpotlight.test.tsx`, add a test that renders two `SpotlightLists` with `idPrefix="a"` and `idPrefix="b"` and asserts no duplicate `id` attributes in the container. Every existing test in that file must pass **unchanged**: that is the proof `LeagueHome`'s output didn't change.
- [X] T005 Run `npx tsc -b && npm test -- --run` in `web/`. The counts must equal T001's plus T004's new test. **Checkpoint**: the foundation is ready, and the league home is untouched.

---

## Phase 3: User Story 1 — A league's spotlight on the home page (Priority: P1) 🎯 MVP

**Goal**: `/` shows a Player spotlight section between From Sleeper and Mock drafts, showing the first
league's spotlight exactly as its league home does.

**Independent Test**: with one ingested NFL league, the home page's spotlight equals that league's
home spotlight entry for entry (quickstart V1, V3).

### Tests for User Story 1

- [X] T006 [P] [US1] Create `web/src/components/HomeSpotlight.test.tsx` using `vi.mock('../api', …)` for `getPlayerSpotlight` and `getWeeklyReport`, following the fixture style in `PlayerSpotlight.test.tsx`. Cover contract rows:
  - **U1**: given `leagues=[A,B]`, exactly one `getPlayerSpotlight` call, with A's id.
  - **U4**: spotlight `applies:true`, no `topOfNight`, `period:{kind:'WEEK',week:3,…}` → exactly one `getWeeklyReport(A, 0)`.
  - **U5**: three separate cases, each → zero `getWeeklyReport` calls: `topOfNight` present; `period:null`; `applies:false`.
  - **U6**: `getPlayerSpotlight` rejects → the panel text contains "Couldn't load the player spotlight for {A's name}".
  - **U7**: `applies:false, reason:'PAST_SEASON', season:2025` → the text "No player spotlight for 2025: it covers the current season only.", and the panel is not blank.
  - **Loading**: the panel shows "Loading player spotlight" while the spotlight promise is pending.
- [X] T007 [P] [US1] In `web/src/pages/DraftPicker.test.tsx`, add:
  - **Placement**: with ingested leagues, the "Player spotlight" heading comes after "From Sleeper" (when present) and before "Mock drafts" in document order.
  - **Absent**: with `drafts=[]` there is no "Player spotlight" heading at all (no empty state).
  - Mock `getPlayerSpotlight` so these tests make no network call.

### Implementation for User Story 1

- [X] T008 [US1] Create `web/src/components/HomeSpotlight.tsx` with the default export `HomeSpotlight({ leagues }: { leagues: { leagueId: string; name: string; sport: Sport }[] })`. Render `<section className="section picker-section home-spotlight" aria-labelledby="home-spotlight-h">` with a `panel-head` holding `<h2 id="home-spotlight-h" className="section-title">Player spotlight</h2>`. Render one inner `LeaguePanel` for the selected league, which in this story is always `leagues[0]`. Return `null` when `leagues` is empty. Contract: [home-spotlight-ui.md](contracts/home-spotlight-ui.md) "Markup shape".
- [X] T009 [US1] Write `LeaguePanel({ league, idPrefix })` in `web/src/components/HomeSpotlight.tsx`:
  - `const version = useLeagueDataVersion(league.leagueId)`
  - `const spotlight = useBlock(() => getPlayerSpotlight(league.leagueId), [league.leagueId, version])`
  - `const needsWeekly = spotlight.status === 'ok' && spotlight.data.applies && !spotlight.data.topOfNight && spotlight.data.period != null`
  - `const weekly = useBlock(needsWeekly ? () => getWeeklyReport(league.leagueId, 0) : null, [league.leagueId, version, needsWeekly])`

  Render per data-model.md "What a tab panel shows":
  - `loading` → `<SkeletonRows count={3} label="Loading player spotlight" />`
  - `error` (including notFound) → `<p className="muted small">Couldn't load the player spotlight for {league.name}.</p>`
  - `ok` and `!applies` → `<p className="muted small">No player spotlight for {spotlight.data.season}: it covers the current season only.</p>`
  - otherwise → `<SpotlightLists spotlight={spotlight.data} weekly={weekly} idPrefix={idPrefix} />`

  The weekly fetch must be decided by these payload fields only, never by `league.sport` (research R2, FR-014).
- [X] T010 [US1] In `web/src/pages/DraftPicker.tsx`, insert `<HomeSpotlight leagues={…} />` after both "From Sleeper" blocks (the `sleeperError` section and the `visibleSleeperLeagues` list section) and before the "Mock drafts" `<section>` (around line 505). Render it only when `drafts != null`. Build `leagues` from `visibleLineages.map(({ current: d }) => ({ leagueId: d.sleeperLeagueId, name: d.leagueName, sport: d.sport }))`. Make no other change to `DraftPicker`'s existing fetches; the section's own fetch starts only after `drafts` resolves (research R6, FR-007).
- [X] T011 [US1] Add `.home-spotlight` spacing in `web/src/styles.css` next to the `.lh-spotlight` rules (around line 5127). Reuse every `.lh-spot-*` rule as it is: no copies, no overrides of column layout.
- [X] T012 [US1] Run `npx tsc -b && npm test -- --run` in `web/`. T006 and T007 pass. **Checkpoint**: US1 is shippable alone, as a single-league spotlight on `/`.

---

## Phase 4: User Story 2 — Switch between leagues (Priority: P1)

**Goal**: one tab per visible league; switching swaps the spotlight without reloading the page, with
no refetch when returning to a visited tab, and the strip follows the sport filter.

**Independent Test**: with an NFL and an NBA league, each tab matches its own league home, the period
label changes shape, and returning to a tab makes no request (quickstart V4, V7, V8).

### Tests for User Story 2

- [X] T013 [P] [US2] Extend `web/src/components/HomeSpotlight.test.tsx`:
  - **Strip**: a `role="tablist"` named "Leagues" with one `role="tab"` per league, in prop order, each tab's text containing the league name.
  - **U2**: click B → `getPlayerSpotlight(B)` is called once, and A's panel is still in the DOM but `hidden`.
  - **U3**: click A again → no new call and no "Loading player spotlight" text.
  - **U8**: rerender with `leagues` no longer containing the selected id → the first league's tab is `aria-selected="true"`.
  - **U9**: with focus on tab 1, ArrowRight selects tab 2, ArrowLeft from tab 1 wraps to the last, and Home/End jump to the ends.
  - **U6 isolation**: B's spotlight rejects while A's resolves → A's panel still renders its lists after switching back.
- [X] T014 [P] [US2] Add `HomeSpotlight.tsx` to `backend/src/test/java/com/ballknowers/draftsim/engine/NoSportNameInPlayerSpotlightTest.java` as a new `@Test void theHomeSpotlightComponentNamesNoSport()` calling `assertNamesNoSport("../web/src/components/HomeSpotlight.tsx")`. Make it **unconditional**: no `if (!Files.exists(f)) return;` (contract "Source-scan rule").

### Implementation for User Story 2

- [X] T015 [US2] In `web/src/components/HomeSpotlight.tsx`, add the state from data-model.md "Selection":
  - `const [selectedId, setSelectedId] = useState<string | null>(null)`
  - Effective selection: `leagues.some(l => l.leagueId === selectedId) ? selectedId : leagues[0].leagueId`
  - `const [visited, setVisited] = useState<Set<string>>(…)`, which grows to include the effective selection on every render path (use an effect or derive it during selection). It "never shrinks during a visit".

  Render a `LeaguePanel` for **every visited id that is still in `leagues`**, each wrapped in `<div role="tabpanel" id={`home-spot-panel-${id}`} aria-labelledby={`home-spot-league-${id}`} hidden={id !== effectiveId}>`, with `idPrefix={`home-${id}`}`. Unvisited leagues mount nothing (research R5, FR-007, FR-008).
- [X] T016 [US2] In `web/src/components/HomeSpotlight.tsx`, render the league strip `<div role="tablist" aria-label="Leagues" className="home-spot-leagues">` with one `<button role="tab" id={`home-spot-league-${id}`} aria-selected aria-controls={`home-spot-panel-${id}`} tabIndex={selected ? 0 : -1}>` per league. The label is `{name}` plus `<span className={`sport-pill ${sport}`}>{sport.toUpperCase()}</span>`, the same pill markup "Your leagues" uses at `DraftPicker.tsx` ~line 374. Add an ArrowLeft/ArrowRight/Home/End handler with wrap and focus, mirroring `PlayerSpotlight.tsx`'s `onKey`. The sport appears **only** as that class and text, never in a comparison.
- [X] T017 [US2] In `web/src/styles.css`, add `.home-spot-leagues` as a single row with `overflow-x: auto`, `scrollbar-width: thin`, `gap` and `min-width: 0`, so at 375px the strip scrolls within itself and the page never scrolls horizontally (FR-011, U10). Style tab buttons on the existing segmented-control look (`.lh-spot-tab` tokens), with `aria-selected="true"` as the selected state, so it matches the site rather than introducing a new control style.
- [X] T018 [US2] Run `npx tsc -b && npm test -- --run` in `web/`, and `./gradlew test --tests '*NoSportNameInPlayerSpotlight*'` in `backend/`. All pass, and the new scan test shows as **run**, not skipped. **Checkpoint**: US1 and US2 work together.

---

## Phase 5: User Story 3 — Go to the league from its tab (Priority: P3)

**Goal**: an "Open league" link in the section head follows the selected tab.

**Independent Test**: select a tab and follow the link to that league's home (quickstart V12).

- [X] T019 [P] [US3] In `web/src/components/HomeSpotlight.test.tsx`, add: with A selected, the link named "Open league" has `href` `/leagues/{A}`; after clicking tab B it is `/leagues/{B}`. Wrap the render in a `MemoryRouter`.
- [X] T020 [US3] In `web/src/components/HomeSpotlight.tsx`, add `<Link to={`/leagues/${effectiveId}`} className="link-button">Open league</Link>` inside the section's `panel-head`, after the `<h2>` (FR-009).
- [X] T021 [US3] Run `npx tsc -b && npm test -- --run` in `web/`. All pass.

---

## Phase 6: Polish, review, and live verification

- [X] T022 Run `cd web && npm run build` (production build). It must succeed with no new warnings in the files touched above.
- [X] T023 Bug-hunting code review of the full diff (not a style pass), as a separate pass from the build. In particular:
  - the hidden panels' blocks don't refetch on every parent rerender (`useBlock` deps);
  - `visited` can't hold an id that is no longer in `leagues` and still render a panel;
  - `needsWeekly` flipping from false to true doesn't start a duplicate weekly request;
  - nothing in `LeagueHome`'s render changed;
  - no sport literal was added anywhere in `HomeSpotlight.tsx`.

  Fix confirmed findings, and record any that change the design as "amended after review" in research.md.
- [X] T024 Live verification per [quickstart.md](quickstart.md) V1–V13 against a backend serving **this worktree's** code. Check the running process's classpath; `preview_start` from a worktree runs main's launch.json. Postgres is on 5433. Use a viewer with an NFL and an NBA league ingested. Record each V-step in quickstart.md as **measured** (with the number or what was seen) or **not run**. In particular, record V5's two request durations and V6's time-to-"Your leagues" versus `main`. Those are the plan's two unmeasured assumptions (research R2, R6).
- [X] T025 Update `HANDOFF.md` with a short 015 entry: what shipped, what V1–V13 measured versus did not run, and the two stated trade-offs (no refresh started from `/`, research R7; NBA night cutoff still owed after 2026-10-21, inherited from 014). Do not commit; ask Allan first.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (T001)** → **Foundational (T002–T005)** → US1 → US2 → US3 → Polish.
- T002 before T003, since T003 imports `Block` from the new module. T004 needs T003.
- **US2 depends on US1**: it extends the same component (T015 and T016 edit `HomeSpotlight.tsx` from T008 and T009). Both are P1. US1 is still shippable alone as the MVP.
- **US3 depends on US1** for the section head. It is independent of US2 except that its test clicks a second tab; if US3 is built before US2, drop that half of T019.

### Within each story

- Tests first (T006/T007, T013/T014, T019); they fail until the implementation lands.
- `HomeSpotlight.tsx` tasks are sequential: one file.

### Parallel opportunities

- T004 runs alongside nothing else in Phase 2 (it needs T003), but T006 and T007 can be written in parallel with each other once Phase 2 is done.
- T013 (web test) and T014 (backend source-scan test) are different files and different toolchains: parallel.
- T011 and T017 (CSS) can be done alongside the component tasks of their story by a second pair of hands, since `styles.css` is a different file.

### Parallel example: User Story 2

```text
Task: "T013 [US2] Extend HomeSpotlight.test.tsx with strip/U2/U3/U8/U9/isolation tests"
Task: "T014 [US2] Add unconditional HomeSpotlight.tsx scan to NoSportNameInPlayerSpotlightTest.java"
```

---

## Implementation Strategy

### MVP first (User Story 1)

1. T001–T005: baseline, then the move and split, with the league home proven unchanged.
2. T006–T012: the first league's spotlight on `/`.
3. **Stop and validate**: quickstart V1–V3 live. That alone answers "like this but on the main page"
   for a one-league viewer.

### Incremental delivery

1. Add US2 (T013–T018): tabs per league, which is the "on each" half of the request.
2. Add US3 (T019–T021): the link.
3. Phase 6: build, review, live verification, hand-off. A passing suite is not this repo's bar for
   done; T024 is.

---

## Notes

- 25 tasks. Every one names its file. Each [P] touches a different file from the tasks it is parallel to.
- A wrong guess in the plan found during the build gets a visible "amended after build" note in
  research.md, not a quiet rewrite.
