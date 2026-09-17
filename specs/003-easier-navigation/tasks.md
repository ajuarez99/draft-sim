---

description: "Task list for 003-easier-navigation"
---

# Tasks: Navigation that doesn't strand you

**Input**: Design documents from `/specs/003-easier-navigation/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/)

**Tests**: Included. The spec gives each story an "Independent Test", the plan gives each phase a
"Test" section, and [quickstart.md](./quickstart.md) supplies the commands — tests are requested by
this feature, not optional.

**Organization**: Grouped by user story so each ships independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story the task serves (US1–US4)
- Every task names its exact file path

## Path Conventions

Web application, existing split: `web/src/` (React SPA) and `backend/src/main/` (Spring Boot). No new
top-level directories.

---

## Phase 1: Setup

**Purpose**: Establish the before-state and the one shared test utility every later phase uses.

- [X] T001 Reproduce the baseline defects listed in the table in `specs/003-easier-navigation/quickstart.md` ("Baseline: reproduce the defects first") and record the six `true`/`false` results in the PR description — `/leagues/:id/analysis`, `/managers/:id/history` and `/mock/:id` must report `false` before any change lands
- [X] T002 [P] Add a test helper that renders an arbitrary pathname inside `AppShell` with a stubbed user and stubbed `getDrafts`, in `web/src/testRailHelpers.tsx`, exporting `renderAtPath(pathname)` returning the rendered `nav.app-rail` — every rail assertion in T007, T008, T032 uses it

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The single declaration of what pages a league has. Per [contracts/destinations.md](./contracts/destinations.md), four components read it; today the same knowledge lives in three places that already disagree, which is the defect this feature fixes.

**⚠️ CRITICAL**: US1, US2 and US3 all consume this module. No story work starts until T003–T006 are done.

- [X] T003 Create `web/src/destinations.ts` exporting the `DestinationKey` union (`'board' | 'live' | 'history' | 'power' | 'analysis' | 'mock'`), the `LeagueDestination` type and the `LEAGUE_DESTINATIONS` table, with one row per destination carrying `key`, `glyph`, `sports`, `label`, `href`, `match`, `requiresStatus` and `isAction` as specified in `specs/003-easier-navigation/data-model.md` §1. Constraint, quoted from data-model.md: "`sports` may not be empty, and may not be defaulted. A defaulted sport list asserts a rule rather than a value" — type the field as required, never optional. Set `analysis` to `sports: ['nfl']` and move the existing football-only justification comment from `web/src/components/LeagueRailSection.tsx` beside it rather than copying it
- [X] T004 Implement `destinationsFor(ctx: LeagueContext): LeagueDestination[]` in `web/src/destinations.ts`, applying the sport gate and the status gate. Constraints from data-model.md §1: `live` has `requiresStatus` of `pre_draft` and `drafting` only; `board`'s `label` is a function of season status ("Draft room" while pre-draft or drafting, "Draft board" when complete, "Mock draft" otherwise), matching today's `draftLabel` in `web/src/components/LeagueRailSection.tsx`
- [X] T005 Implement `destinationFromPath(pathname: string): DestinationKey | null` in `web/src/destinations.ts`, testing the `match` patterns in table order
- [X] T006 [P] Write `web/src/destinations.test.ts` asserting the five invariants in `specs/003-easier-navigation/contracts/destinations.md`: (1) every non-action destination's `href` output is matched by its own `match`; (2) `destinationFromPath` is total over every league-scoped path in `web/src/App.tsx`; (3) no `sports` array is empty; (4) `destinationsFor` on an NBA league never returns `analysis`; (5) `live` appears only for `pre_draft`/`drafting`. Invariant (2) is the test that would have caught today's Analysis bug — confirm it fails when a league-scoped `<Route>` exists with no table row

**Checkpoint**: One declaration exists and is self-consistent. Stories can begin.

---

## Phase 3: User Story 1 — The league you are in stays with you (Priority: P1) 🎯 MVP

**Goal**: All six league-scoped routes show league context in the rail.

**Independent Test**: Visit each of the six routes and assert `nav.app-rail .app-rail-league` is present and the current destination is marked. Three of six fail today.

**Note**: T007–T013 are frontend-only and ship on their own. T014–T021 add the mock case and need a migration — see [contracts/mock-session.md](./contracts/mock-session.md). Ship the backend before the frontend half of the mock work; the services deploy independently.

### Tests for User Story 1

- [X] T007 [P] [US1] Extend `web/src/railLeague.test.ts` with a case per route shape asserting `leagueRefFromPath` resolves a league for `/leagues/:id/history`, `/leagues/:id/power`, `/leagues/:id/analysis`, `/drafts/:id`, `/drafts/:id/board` and `/drafts/:id/live`, and `null` for `/`, `/managers` and an unknown path
- [X] T008 [P] [US1] Create `web/src/components/LeagueRailSection.test.tsx` using `renderAtPath` from T002, asserting the League section renders on each of the six route shapes and marks exactly the current destination as `.on`

### Implementation for User Story 1 — frontend

- [X] T009 [US1] Rewrite `leagueRefFromPath` in `web/src/railLeague.ts` to derive its patterns from `LEAGUE_DESTINATIONS` instead of the literal `/^\/leagues\/([^/]+)\/(?:history|power)(?:\/verify)?\/?$/` alternation on line 27, keeping it a hand-written matcher — `useParams` returns `{}` outside `<Routes>` and `AppShell` must stay there (see the load-bearing comment at `web/src/components/AppShell.tsx:72`)
- [X] T010 [US1] Render the rail's destination rows in `web/src/components/LeagueRailSection.tsx` by mapping `destinationsFor(league)`, deleting the inline `d.sport === 'nfl'` gate and the hand-written `historyHref`/`powerHref`/`analysisHref` constants so the rail's links and the matcher can no longer disagree
- [X] T011 [US1] Extend `RailLeagueRef` in `web/src/railLeague.ts` with a `{ kind: 'manager-history' }` case that reads the referring league from `location.state`, and return `null` when there is none. Constraint from data-model.md §2: manager-history context is "best-effort" — "a direct visit to `/managers/:id/history` with no referring league legitimately has no league, and the rail must render no League section rather than guess one"
- [X] T012 [P] [US1] Pass the current league on the `Link` state for each manager row in `web/src/pages/LeagueHistory.tsx` (both sites — the 2026 table at line ~92 and the per-season tables at line ~233)
- [X] T013 [P] [US1] Pass the same state on the manager `Link` in `web/src/pages/LeagueAnalysis.tsx` (line ~100)

### Implementation for User Story 1 — mock sessions (backend first)

- [X] T014 [US1] Create `backend/src/main/resources/db/migration/V16__mock_source_league_id.sql` containing `alter table mock_draft_session add column source_sleeper_league_id text;`. Constraints from data-model.md §4, verbatim: type `text`, "Null: yes", "Default: none", "Never backfilled", "Nullable forever". Confirm the current head is `V15__player_projection.sql` before numbering
- [X] T015 [US1] Add the column to `backend/src/main/java/com/ballknowers/draftsim/store/MockDraftRepository.java`: a `sourceSleeperLeagueId` parameter on `createSession` (today 13 positional parameters — add it adjacent to `sourceLeagueName` and extend the Javadoc, or extract a parameter object if review prefers), the column in the `insert` statement with a `Types.VARCHAR` null branch matching the `sourceLeagueName` treatment on lines 100–101, the field on the `SessionSummary` record, `mapSummary`, and the `select` lists in **both** branches of `allSessionsFor`
- [X] T016 [US1] In `backend/src/main/java/com/ballknowers/draftsim/mock/MockDraftService.java`, pass the already-validated `sourceSleeperLeagueId` through to `createSession` at the call on line ~198 — the value is in scope from line 146 and is currently discarded after `league.name()` is read on line 163. Do not relax the existing membership or sport checks that guard it
- [X] T017 [US1] Surface `sourceSleeperLeagueId` on the mock session response in `backend/src/main/java/com/ballknowers/draftsim/api/MockDraftController.java` for all four endpoints listed in `specs/003-easier-navigation/contracts/mock-session.md` (`POST /api/mocks`, `POST /api/mocks/from-draft/{sleeperDraftId}`, `GET /api/mocks/{id}`, `GET /api/mocks`)
- [X] T018 [P] [US1] Write an integration test — **landed in the existing `backend/src/test/java/com/ballknowers/draftsim/store/MockDraftRepositoryIT.java` rather than a new `MockSourceLeagueIT.java`**, so it reuses that file's Postgres gate instead of duplicating it. Two tests added; the file now runs 11 with 0 skipped — asserting a mock created with `sourceSleeperLeagueId` round-trips it, one created without reads back `null`, and a pre-V16 row (name only) reads back `null` rather than being matched by name. Requires Postgres — the suite reports `BUILD SUCCESSFUL` with ~52 tests silently skipped when it is down, so verify the skip count, not just the exit code
- [X] T019 [US1] Add `sourceSleeperLeagueId?: string | null` to `MockSessionState` in `web/src/api.ts`. Constraint from contracts/mock-session.md: **optional**, not merely nullable — "A missing field should degrade one badge, never the page" (the rule from the 2026-09-14 white-page incident recorded at `web/src/api.ts:579`). Do not add it to `withSportDefaults`; absent must stay absent
- [X] T020 [US1] Add a `{ kind: 'mock' }` case to `leagueRefFromPath`/`useRailLeague` in `web/src/railLeague.ts` that resolves the league from the session's `sourceSleeperLeagueId`, supplied by `web/src/pages/MockDraftView.tsx`, and renders no League section when the field is absent or null — never falling back to matching `sourceLeagueName`
- [X] T021 [P] [US1] Add a case to `web/src/api.mockSport.test.ts` (or a sibling spec) asserting a `MockSessionState` payload with no `sourceSleeperLeagueId` key parses and renders without throwing — the split-deploy case from contracts/mock-session.md

**Checkpoint**: All six routes keep league context. The quickstart baseline table now reads `true` across the board, except pre-V16 mocks, which correctly show none.

---

## Phase 4: User Story 2 — Jump to anything from anywhere (Priority: P2)

**Goal**: Any destination reachable from any route in two actions.

**Independent Test**: Press the shortcut on any route, type three characters of a league, manager or page name, land on it.

### Tests for User Story 2

- [X] T022 [P] [US2] Write `web/src/searchIndex.test.ts` covering the derivation rules in data-model.md §3: a manager present in both sports appears **once** (constraint, verbatim: "Manager rows merge on **manager id**, not display name" — ten of twelve Ball Knowers managers are the same Sleeper user in both sports per `web/src/api.ts:426`); each season of a multi-season league yields a distinct `season-board` row labelled by year; no NBA league emits an `analysis` row; `context` is never empty
- [X] T023 [P] [US2] Write `web/src/components/JumpTo.test.tsx` covering keyboard-only operation (NFR-003): opens focused, arrow moves selection, Enter navigates, Escape closes without navigating and restores focus to the previously focused element

### Implementation for User Story 2

- [X] T024 [P] [US2] Create `web/src/searchIndex.ts` building `SearchDestination[]` per data-model.md §3 — `leagueLineages(getDrafts())` crossed with `destinationsFor()` for league pages and season boards, plus `getManagers()` for **both** sports merged on manager id, each row carrying `id`, `kind`, `label`, `context`, `href`, `sport` and a lowercased `terms` haystack
- [X] T025 [US2] Create `web/src/components/JumpTo.tsx`: overlay with a text input, results grouped by `kind` (Leagues / Pages / Managers), each row labelled with its `context` so two leagues' "History" are distinguishable (FR-005), plus a focus trap and Escape handling
- [X] T026 [US2] Host the overlay and own its open state in `web/src/components/AppShell.tsx`, bound to `Ctrl/Cmd+K`. The handler must not fire on a bare `k` while a text input has focus — draft rooms have their own player search, which is why `/` was rejected in research.md R3
- [X] T027 [US2] Add a visible entry point to `web/src/components/Rail.tsx` so the palette is discoverable without the shortcut (FR-004 requires both a keyboard and a visible route in)
- [X] T028 [US2] Style the overlay in `web/src/styles.css`, reusing the existing tokens. It is an overlay, so it takes no layout space — NFR-004 forbids anything new in a draft room's horizontal budget (a 14-team board already overflows 1440px by 99px with no rail at all)
- [X] T029 [US2] Verify NFR-001 with the `performance.getEntriesByType('resource')` check in quickstart.md "Phase 3": opening the palette on a warm cache must not increase the `/api/` request count

**Checkpoint**: Every destination is two actions away from every other.

---

## Phase 5: User Story 3 — Switch league without going home (Priority: P3)

**Goal**: Move between leagues, and between seasons, without returning to Home — including from a collapsed rail.

**Independent Test**: From `/leagues/A/history`, switch to league B and land on `/leagues/B/history`; from an NFL league's Analysis, switch to an NBA league and land on its History.

### Tests for User Story 3

- [X] T030 [P] [US3] Extend `web/src/components/LeagueRailSection.test.tsx` with switcher cases: same-destination preservation across two NFL leagues, and the History fallback when switching from `/analysis` to an NBA league (FR-008). The fallback must read `destinationsFor()` rather than restating the sport gate

### Implementation for User Story 3

- [X] T031 [US3] Turn the league crest in `web/src/components/LeagueRailSection.tsx` into a switcher listing the user's other leagues from the cached draft list, preserving the current `DestinationKey` when the target offers it and falling back to that league's `history` when it does not
- [X] T032 [US3] Make season links reachable while the rail is collapsed in `web/src/components/LeagueRailSection.tsx` by replacing the `!collapsed && lineage.seasons.length > 1` gate with a flyout from the crest (FR-009). It must not widen the rail — draft rooms collapse it for a measured reason and NFR-004 is non-negotiable there
- [X] T033 [P] [US3] Link each manager row to `/managers/:managerId/history` in `web/src/pages/ManagerTendencies.tsx` (FR-007). Measured 2026-09-17: the page renders 53 rows with 53 Edit controls and **zero** links to a profile
- [X] T034 [US3] Style the switcher and the season flyout in `web/src/styles.css`, then confirm with the board-width check in quickstart.md "Phase 4" that `.board-scroll` `scrollWidth` is unchanged in a 14-team room at 1440px

**Checkpoint**: League and season movement no longer routes through Home.

---

## Phase 6: User Story 4 — Navigation you can get back to on a phone (Priority: P4)

**Goal**: Reach navigation from a scrolled position at phone width, without spending a permanent band.

**Independent Test**: At 375×812, scroll to the bottom of a long standings page and reach another page without scrolling up.

**Context**: Do not pin the bar. research.md R6 quotes the existing measurement in `web/src/styles.css` — the pinned version cost "252px of 812 on an iPhone-sized screen" and was deliberately rejected. This phase adds a button, not a bar.

- [X] T035 [US4] Add a fixed bottom-corner control that opens the Phase 4 overlay, shown only below the existing `max-width: 860px` breakpoint, in `web/src/components/AppShell.tsx` and `web/src/styles.css`
- [X] T036 [US4] Verify with the quickstart.md "Phase 5" check that page content still begins where it did before this phase — the control may occupy no permanent band when closed (FR-010)

**Checkpoint**: All four stories are independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T037 Run the full `specs/003-easier-navigation/quickstart.md` end to end and record the six-route result table against the T001 baseline in the PR description
- [X] T038 [P] **Nothing to delete** — verified: the `← League history` chip was already removed in the earlier shell work (`PowerRankings.tsx:664` documents its removal), and `grep` finds no other hand-rolled back link. Originally: delete the now-dead per-page navigation left behind by the consolidation — check `web/src/pages/PowerRankings.tsx` for the hand-rolled "← League history" link that `LeagueRailSection`'s doc comment says "come out as this goes in", and any sibling one-offs
- [X] T039 [P] **Done as part of T009/T010** — both files' comments now describe the destination table as the source of truth and no "six routes" claim remains. Originally: update `web/src/components/LeagueRailSection.tsx` and `web/src/railLeague.ts` doc comments so they describe the destination table as the source of truth, replacing the "Six routes share these two shapes" comment that documented four
- [X] T040 Run the full-suite commands from the "Full suite before opening a PR" section of `specs/003-easier-navigation/quickstart.md` (`npm --prefix web test && ./gradlew -p backend test`) and confirm the backend skip count is near zero rather than ~52

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies
- **Foundational (Phase 2)**: depends on Setup — **blocks US1, US2 and US3**
- **US1 (Phase 3)**: depends on Phase 2
- **US2 (Phase 4)**: depends on Phase 2; should follow US1 so arriving somewhere leaves you navigable
- **US3 (Phase 5)**: depends on Phase 2 and on US1's `destinationsFor` consumption in the rail
- **US4 (Phase 6)**: depends on **US2** — it is a second entry point to that overlay and is pointless before it
- **Polish (Phase 7)**: depends on all shipped stories

### Notable within-story ordering

- T009 before T010 — the matcher must be table-driven before the rail renders from the table, or the two change under each other
- T014 → T015 → T016 → T017 is strictly sequential: migration, then repository, then service, then response
- T019/T020 (frontend) **after** T017 is deployed. The services deploy independently; ship backend first so a new frontend never meets an old backend
- T024 before T025 — the index is a pure function the component consumes

### Parallel Opportunities

- T012 and T013 touch different pages and can run together
- T018 and T021 are the backend and frontend halves of the same split-deploy guarantee, in different trees
- T022, T023 and T024 can run together once Phase 2 lands
- T033 is independent of the rest of US3 and can be done any time after Phase 2
- T038 and T039 are separate files

---

## Parallel Example: User Story 1

```bash
# Tests first, different files:
Task: "Extend web/src/railLeague.test.ts with a case per route shape"          # T007
Task: "Create web/src/components/LeagueRailSection.test.tsx"                   # T008

# Then the two standings-page state passes, different files:
Task: "Pass league state on manager links in web/src/pages/LeagueHistory.tsx"  # T012
Task: "Pass league state on manager links in web/src/pages/LeagueAnalysis.tsx" # T013
```

---

## Implementation Strategy

### MVP (User Story 1 only)

1. Phase 1 Setup — confirm the defects are real (T001)
2. Phase 2 Foundational — the destination table (T003–T006)
3. Phase 3 US1 — frontend half (T007–T013) is already a shippable fix for the Analysis dead end
4. **STOP and VALIDATE**: re-run the quickstart baseline table
5. Then the mock half (T014–T021), backend deployed first

The frontend half of US1 alone closes the sharpest complaint — the rail linking you to Analysis and
then deleting the block that linked you — with no migration and no new UI.

### Incremental Delivery

1. Phase 2 → foundation ready
2. US1 → the app stops stranding people → deploy
3. US2 → everything is two actions away → deploy
4. US3 → pointing works as well as typing → deploy
5. US4 → the phone gets its return trip → deploy

### Notes

- `[P]` = different files, no dependency on an incomplete task
- Commit per task or per logical group
- Each checkpoint is a valid stopping point
- The backend suite's silent-skip behaviour makes a green build meaningless without Postgres — check the skip count at T018 and T040


---

## Amendments after implementation (2026-09-17)

Recorded rather than silently absorbed, per this repo's convention.

1. **T014–T021 were scoped against a wrong reading of the API.** The plan and
   `contracts/mock-session.md` said `MockSessionState` already carried `sourceLeagueName`. It does
   not — that field is on `MockSessionSummary`, the list row. `MockSessionState` and
   `MockDraftRepository.SessionRow` had no source-league field at all, so the change touched both
   records, both single-session `select`s and both list `select`s rather than one column beside an
   existing one. The contract carries an "amended after implementation" note with the detail.
2. **`./gradlew -p backend test` does not work** — the wrapper lives in `backend/`, so it is
   `cd backend && ./gradlew test`. `quickstart.md` corrected.
3. **A clipping bug the unit tests could not catch.** The switcher flyout was clipped to a sliver by
   the collapsed rail's `overflow-y: auto`. Found by measuring rects in a real browser, fixed with
   viewport-fixed positioning. Covered now by the live measurements recorded in `quickstart.md`.
4. **`useAllLeagues` had to be gated.** Fetching the league list on every route broke `App.test.tsx`
   (a partial module mock) and, more importantly, fetched a list for a control that cannot appear
   outside a league. It now takes `enabled` and resolves inside an async function so a synchronous
   throw cannot escape the effect.
