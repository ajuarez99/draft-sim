---
description: "Task list for spec 024: Draft room UX, borrowed from Sleeper and FantasyAlarm"
---

# Tasks: Draft room UX, borrowed from Sleeper and FantasyAlarm

**Input**: Design documents from `specs/024-draft-room-ux/`: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md) (**read §Amendments A1–A13 first; they supersede R2, R3, R5 and R7 where they conflict**), [data-model.md](data-model.md), [contracts/api.md](contracts/api.md), [quickstart.md](quickstart.md), [plan-review.md](plan-review.md).

**Amended after plan review (2026-10-09).** The first version of this file was regenerated after T002. The tasks that changed and why:
- **T005/T006** now store the timer in a DB column instead of sending it on the live frame (A2), and use a `format()` query instead of widening `DraftRow` (A9).
- **T011** gains the `notices`, `prompt` and `compactRow` regions and drops "below" (review #8, FR-001c).
- **T019a** adds compact cells (FR-001b).
- **T020** adds a Claim path that needs no `Seat` (A5) and takes the column mark from `mySlot` only (A6). Landed vs projected styling is now per room (A7).
- **T023** splits tests between `MockDraftServiceTest` and `AccessControlMvcIT` (A13).
- **T026/T030** handle concurrent saves (A8).
- **T027** returns off-board targets in a `missing` list (A13).
- **T041** uses `isDraftable` + `> benchFloor` instead of `> 0` (A1), and fetches the board and fit once (A10).
- **T043/T044** count AUTO picks as the user's (A10).

**Tests**: included, per the repo bar. Preference-ordering tests apply to every choice rule (bug class 1), and SQL and binds run against real Postgres (classes 2 and 3).

**Working tree**: `C:\Users\allan\source\draft-sim-024`, branch `024-draft-room-ux`; all paths are relative to it. **Coding tasks run on Sonnet subagents; the parent session reads every diff.** Ask before committing. Preview servers: `draft-sim-024-api-8094` and `draft-sim-024-web-5195`.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

- [X] T001 Confirm the environment (Postgres 5433, JDK, free ports, launch config). Recorded in `specs/024-draft-room-ux/verification.md`. The 024 launch entries were added to the main checkout's `.claude/launch.json` on ports 8094 and 5195, and `web/node_modules` was installed in the worktree.
- [X] T002 Adversarial plan review → `specs/024-draft-room-ux/plan-review.md`: 4 blockers, 11 should-fixes, 9 notes. Amendments were applied visibly to spec, research, data-model, contracts, quickstart and plan. Heights were measured in the browser, and the user decided on compact cells and a compact row.

---

## Phase 2: Foundational

- [X] T003 Create `backend/src/main/resources/db/migration/V30__draft_target.sql`, per data-model.md:
  - **(a) The `draft_target` table:**
    - `id bigserial primary key`
    - `owner_sleeper_user_id text not null`
    - `sleeper_draft_id text` (nullable, **no FK to `draft`**; comment why: a re-ingest must never cascade-delete someone's stated choices, the same reasoning as V9's `ranking_ballot`)
    - `mock_session_id bigint references mock_draft_session (id) on delete cascade` (nullable)
    - `player_id bigint not null references player (id)`
    - `rank int not null`
    - `created_at timestamptz not null default now()`
    - `check ((sleeper_draft_id is null) <> (mock_session_id is null))`
    - two partial unique indexes: `(owner_sleeper_user_id, sleeper_draft_id, player_id) where sleeper_draft_id is not null` and `(owner_sleeper_user_id, mock_session_id, player_id) where mock_session_id is not null`
    - two partial lookup indexes: `(owner_sleeper_user_id, sleeper_draft_id)` and `(owner_sleeper_user_id, mock_session_id)`
  - **(b)** `alter table draft add column pick_timer_seconds int;` with a comment: nullable, from Sleeper `settings.pick_timer`, null = not sent.
  - **(c)** `alter table mock_draft_pick drop constraint mock_draft_pick_source_check; alter table mock_draft_pick add constraint mock_draft_pick_source_check check (source in ('USER','BOT','LIVE','AUTO'));`
  - **Header comment:** cite spec 024 and review A2.
- [X] T004 Apply V30 locally by restarting `draft-sim-024-api-8094`. Then run SQL by hand against 5433 and paste the commands and results into `verification.md`:
  - insert one `draft_target` row per scope;
  - confirm that a row with both scopes and a row with neither are both rejected;
  - insert a duplicate player in one scope and confirm it is rejected;
  - insert an `AUTO` `mock_draft_pick` row inside a transaction you roll back;
  - confirm `draft.pick_timer_seconds` exists.
- [X] T005 [P] Store and refresh the pick timer (A2):
  - In `backend/src/main/java/com/ballknowers/draftsim/ingest/LeagueIngestService.java` `ingestDraft`, read `settings.pick_timer` as a nullable Integer (absent → null; **never default it to a number**).
  - Pass it to `DraftRepository.upsert` in `backend/src/main/java/com/ballknowers/draftsim/store/DraftRepository.java`. Add `pick_timer_seconds` to the insert, and add `pick_timer_seconds = excluded.pick_timer_seconds, draft_type = excluded.draft_type` to the `on conflict do update` list. Match the existing upsert signature style; fix every caller the compiler flags.
  - In `LiveDraftPoller.pollOnce` (`backend/src/main/java/com/ballknowers/draftsim/ingest/LiveDraftPoller.java`), next to `drafts.updateStatus(...)`, write the timer from `raw.settings.pick_timer` with a new `drafts.updatePickTimer(id, Integer)`.
  - **Do not** change `LiveSnapshot` or the SSE frame.
- [X] T006 [P] Return the draft format from `seats()` (A9):
  - Add `record DraftFormat(String draftType, Integer pickTimerSeconds)` and `Optional<DraftFormat> format(long draftId)` to `DraftRepository`. **Do not widen `DraftRow`.**
  - In `LeagueController.seats()` (`backend/src/main/java/com/ballknowers/draftsim/api/LeagueController.java`, the existing `LinkedHashMap response`), put `draftType` and `pickTimerSeconds`. Both may be null; `LinkedHashMap` is null-safe, so don't use `Map.of`.
  - Mirror them in `web/src/api.ts` `SeatsResponse` as `draftType?: string | null` and `pickTimerSeconds?: number | null`. They are optional because the frontend and backend deploy separately.
  - Add `$.draftType` / `$.pickTimerSeconds` assertions to `backend/src/test/java/com/ballknowers/draftsim/api/LeagueControllerSeatsOwnerConfiguredIT.java`.
- [X] T007 [P] Add AUTO to the pick source:
  - In `web/src/api.ts`, `MockPick.source` (~line 611) becomes `'USER' | 'BOT' | 'LIVE' | 'AUTO'`.
  - Update the Javadoc on `MockSessionState.PickView.source` in `backend/src/main/java/com/ballknowers/draftsim/mock/MockSessionState.java`: AUTO = the user's own seat, decided by auto-pick.

**Checkpoint**: V30 applied and the SQL run by hand; the format returned on seats (curl the 2025 NBA draft); `./gradlew test` green **with the skip count checked**.

---

## Phase 3: User Story 1 — Board and player list together (P1) 🎯 MVP

**Goal**: one `DraftRoomLayout` for all three rooms. Board over list with a resizable divider, compact cells in the split, a compact row above the board, and a Board | Players toggle on phones.

**Independent test**: quickstart live checks 1, 2 and 8.

### Tests for US1

- [X] T008 [P] [US1] Write `web/src/components/SplitDivider.test.tsx`:
  - **Keyboard:** ↑/↓ move one row, Home/End go to the minimums, Enter resets.
  - **Minimums:** at least 3 board rounds and at least 3 list rows, computed from row heights passed as props.
  - **Persistence:** saved to and restored from `localStorage['draftRoom.split']`. A throwing `localStorage` falls back to the default.
  - **Reset:** double-click resets.
  - **`onDensityChange`:** reports `'compact'` when the board region is shorter than `rounds × fullRowHeight + headerHeight`, and `'full'` otherwise (FR-001b).
- [X] T009 [P] [US1] Write `web/src/components/DraftRoomLayout.test.tsx`:
  - The regions render in a fixed order: `status`, `notices`, `controls`, `prompt`, `compactRow`, `board`, divider, `targets`, `list`.
  - A region given an absence message renders it, never nothing.
  - With `useNarrow` stubbed true, one Board | Players control shows one of board or list at a time, and the other regions stay visible.

### Implementation for US1

- [X] T010 [P] [US1] Create `web/src/components/SplitDivider.tsx`:
  - A `role="separator"` element with `aria-orientation="horizontal"`, `aria-valuenow`/`min`/`max` and `tabIndex=0`.
  - Pointer drag plus the keys from T008.
  - The fraction is stored in `localStorage['draftRoom.split']`, with every read and write in try/catch.
  - The default fraction is a named constant commented `// ARBITRARY — set by measurement in T017 against SC-001, not derived`.
  - It exposes `onDensityChange` per T008.
- [X] T011 [US1] Create `web/src/components/DraftRoomLayout.tsx` per the contracts/api.md UI contract (amended):
  - Region props: `status`, `notices?`, `controls`, `prompt?`, `compactRow?`, `board`, `targets?`, `list`.
  - **At ≥1280 px** (constant labelled arbitrary): the room fills the viewport height below the app chrome, and is a CSS grid of `status / notices / controls+prompt / compactRow / board / SplitDivider / targets / list`. Board and list each scroll inside themselves.
  - **The board region is a non-scrolling `position: relative` wrapper** around a scrolling inner element, so `PickInsightCard` anchors to it (A12).
  - **Below 1280 px:** a segmented Board | Players control (reuse `web/src/useNarrow.ts`, adding a width parameter if its 860 threshold doesn't fit; don't add a second hook). The page never scrolls sideways.
  - Pass the density from `SplitDivider` to the board through a `boardDensity` render prop or context.
- [X] T012 [US1] Add layout styles to `web/src/styles.css`: `.room-grid`, `.room-divider`, `.room-toggle`, region overflow, and `.compact-row`. Keep the dark card/avatar look.
  - **Delete** the `.avail-sheet`, `.avail-sheet.collapsed` and `--avail-sheet-reserve` rules once T013 has removed their users.
  - **Keep** `.start-overlay`: DraftView's start CTA uses it (review #8).
  - Delete `.live-waiting` only in T038.
- [X] T013 [US1] In `web/src/components/AvailabilityPanel.tsx`, turn the floating sheet into a normal region:
  - Remove the `collapsed` state, the `sheet-toggle` chip and the `--avail-sheet-reserve` ResizeObserver effect (~lines 221-265).
  - Keep every data behaviour: tiers, survival columns, the spec-023 stats view, `noAvailabilityReason`, and "Likely there at my next pick".
- [X] T013a [US1] Create `web/src/components/CompactRow.tsx` (FR-001c), one row of about 80 px:
  - **(a) A feed ticker:** the latest pick from `PickFeed`'s same `FeedPick[]` input. Click to expand the last 5, reusing `PickFeed`'s row rendering by passing a `limit`/`compact` prop to `PickFeed` rather than copying it.
  - **(b)** "Your team" `TeamStrip` (`web/src/components/TeamStrip.tsx`) with its "N of M starters" label.
  - **(c)** the `ScarcityMeter` chips.
  - **(d)** a "Room read" toggle opening `OnBrandPanel` as a popover.
  - Each part is optional. A room passes only what it has.
  - Add a test, `web/src/components/CompactRow.test.tsx`: the ticker shows only the latest pick until expanded; missing parts don't render empty boxes.
- [X] T014 [US1] Move `web/src/pages/LiveDraftView.tsx` onto `DraftRoomLayout`:
  - **status:** `LiveStatusBar`.
  - **notices:** `error`, `liveError`, `forkError` and the resim progress bar.
  - **controls:** the existing four chips (US4 groups them).
  - **compactRow:** feed ticker + Your team (only when `slotKnown`) + scarcity + Room read.
  - **board:** the memoized `board` plus `PickInsightCard`, inside the board wrapper.
  - **list:** `AvailabilityPanel`.
  - The pre-projection waiting overlay becomes the board region's absence content.
  - `useAnnouncer`, pick cards, `SeatPopover` and `PlayerCard` stay as they are.
  - Keep all five `LiveDraftView.*.test.tsx` suites green; update their selectors only where the DOM moved, and say so in the diff.
- [X] T015 [US1] Move `web/src/pages/DraftView.tsx` onto `DraftRoomLayout`:
  - **status:** `OnTheClock`.
  - **notices:** the `seatsDirty` banner (~:488-495), the re-run progress bar (~:478-482) and errors.
  - **prompt:** `PickPrompt` and the pause banner (~:535-552).
  - **controls:** `skip`. The settings gear stays portalled into `pageActionSlot` (re-run lives there).
  - **compactRow:** feed ticker + Your team.
  - **board:** `DraftBoard`. The "Start the mock draft" CTA (~:603-627) is the board region's absence content, and keeps `.start-overlay`.
  - **list:** `AvailabilityPanel`.
  - The `PlayerPicker` modal and `useRevealedBoard` are unchanged.
- [X] T016 [US1] Move `web/src/pages/MockDraftView.tsx` onto `DraftRoomLayout`:
  - **status:** `TurnIndicator` and the forked-from note.
  - **notices:** errors.
  - **prompt:** "Pick from full list".
  - **compactRow:** feed ticker + Your team (from the `USER|AUTO` picks; see T043).
  - **board:** `DraftBoard`.
  - **list:** `AvailabilityPanel`, **always mounted**. When COMPLETE, it shows "Draft complete" as its absence content.
- [X] T017 [US1] Live measurement (no code beyond setting the T010 default), at a 1440×900 viewport, on `/drafts/1229352720230514688/live` (filled, 12 teams), `/drafts/1229352720230514688` and a 12-team NBA mock. Measure with `javascript_tool`:
  - the overlap between the board and list rectangles;
  - the number of compact rounds fully in view;
  - the number of list rows fully in view.

  Set the default fraction so that at least 7 compact rounds and at least 8 rows fit. If they can't both fit, compress the list head first (A4), and **record that in verification.md**; never lower the bar quietly. Screenshot all three rooms for the FR-003 parity check, and record the numbers.

**Checkpoint**: SC-001 measured, FR-003 parity screenshots, existing tests green.

---

## Phase 4: User Story 2 — A board you can read at a glance (P1)

**Independent test**: spec US2 scenarios 1–6 on `1414306786223153152` (pre-draft, no order; ingest first) and `1229352720230514688`.

### Tests for US2

- [X] T018 [P] [US2] Write `web/src/components/DraftBoard.test.tsx` (new):
  - **round.pick:** pick 15 of a 12-team board is "2.03".
  - **Snake arrows:** right for plain snake, and for `reversalRound: 3`, where round 3 runs the same direction as round 2.
  - **Claim, real draft:** a header for a slot with **no `Seat`** shows "Claim", calling `onClaim(slot)`.
  - **Claim, mock:** BOT seats show no Claim.
  - **Known seat:** `mySlot` set → column-level marker.
  - **Assumed seat:** `mySlotAssumed` → "assumed" text and the assumed class.
  - **No seat (A6):** `mySlot` undefined with `myPicks` non-empty → **no** cell or column carries a mine class.
  - **On the clock:** `onTheClockPickNo` marks exactly one cell, and none when it is undefined.
  - **Live kinds (FR-008, A7):** cells > `revealedThrough` are empty-kind and cells ≤ it are landed.
  - **Density:** `density='compact'` renders one line per filled cell, with no face or meta line.

### Implementation for US2

- [X] T019 [US2] Cell changes in `web/src/components/DraftBoard.tsx`:
  - **Label:** replace the bare `pickNo` label with `roundPickLabel(pickNo, teams)` (`web/src/roundPickLabel.ts`, already "2.03").
  - **Snake arrow:** from `isForward(round, reversalRound)` in `web/src/snake.ts`.
  - **Make the `reversalRound` prop required** (review #16). All four callers already pass it.
- [X] T019a [US2] Add a `density: 'compact' | 'full'` prop to `DraftBoard` (FR-001b). Compact renders a filled cell as one line, position badge + short name, about 36 px. Full is today's cell. Pass it from `DraftRoomLayout` in all three pages. `CompletedDraftBoard` passes `'full'`. Add the CSS in `web/src/styles.css`.
- [X] T020 [US2] Header and column changes in `DraftBoard.tsx`:
  - **Claim (A5):** a new `onClaim?: (slot: number) => void`. A header for a slot with no `Seat`, on a real draft, renders an "Unclaimed · Claim" pill that calls it. A mock never passes `onClaim`.
  - **Own column (A6):** remove the per-cell `mine` outline that comes from `myPicks`. Mark the column only from `mySlot` (tint + header badge), plus a new `mySlotAssumed?: boolean` (dashed, low-alpha, "assumed" in the header).
  - **On the clock:** a new `onTheClockPickNo?: number` that marks one cell "On the clock".
  - **Cell kinds per room (FR-008, A7):**
    - live: landed vs empty, via `revealedThrough`;
    - projection room: the existing `chosen` cells vs projected;
    - mock: all real.
  - Add the CSS.
- [X] T021 [US2] Wire the new props:
  - **`LiveDraftView.tsx`:**
    - `onClaim={(slot) => setSearchParams(... set 'slot' ...)}`, the same as the existing `onMakeMine`;
    - `mySlotAssumed={!slotKnown}`;
    - `onTheClockPickNo` only when `live?.status === 'drafting'`, as `live.picksMade + 1`.
    - Remove "(assumed — click your seat)" from the panel head.
  - **`DraftView.tsx`:** `onClaim` likewise, if it supports `?slot`; `onTheClockPickNo` = `pausedAt` while paused.
  - **`MockDraftView.tsx`:** `onTheClockPickNo={complete ? undefined : state.currentPickNo}`, and no `onClaim`.
- [X] T022 [US2] Run `web/src/pages/CompletedDraftBoard.test.tsx` and check `CompletedDraftBoard.tsx` still shows value and grade tints, with no on-the-clock cell and full density.

**Checkpoint**: SC-003 from a screenshot.

---

## Phase 5: User Story 3 — Search and targets (P2)

**Independent test**: spec US3 scenarios 1–7 and quickstart step 6.

### Tests for US3

- [X] T023 [P] [US3] Backend tests:
  - **`backend/src/test/java/com/ballknowers/draftsim/api/TargetControllerIT.java`** (real Postgres, set up like `LeagueControllerSeatsOwnerConfiguredIT`):
    - **Round trip:** PUT, then GET, returns `players` in rank order. An off-board player id goes in `missing`. PUT `[]` clears.
    - **400:** a duplicate, an unknown sleeper id, more than 50 (`// ARBITRARY cap`), and neither or both scopes.
    - **Fork:** forking copies the caller's targets only.
    - **Concurrency:** two concurrent PUTs for the same owner and draft both return 200, and the final list is one of the two lists (A8).
  - **`backend/src/test/java/com/ballknowers/draftsim/api/AccessControlMvcIT.java`:**
    - anonymous PUT `/api/targets` → 401, **including with the admin token**;
    - anonymous GET → empty;
    - a non-member → 404;
    - another owner's mock → 404.
- [X] T024 [P] [US3] Write `web/src/targets.test.ts`:
  - `fold("Nikola Jokić") === "nikola jokic"`.
  - `matchesSearch("jokic", "Nikola Jokić")` is true. Fewer than 2 characters → no filtering.
  - `markTaken` keeps taken targets in place, and `topAvailable` returns the first not taken.
  - `survivalFor` returns `undefined` when the seat is unknown, the player is absent, or there's no next pick. **Never 0** (FR-012).
- [X] T025 [P] [US3] Write `web/src/components/TargetStrip.test.tsx`:
  - the chips stay on one row, with "+N" when they overflow;
  - a taken chip is labelled "taken";
  - a `missing` target shows no %;
  - the empty state shows the how-to line;
  - the popover reorders with ↑/↓ and removes;
  - a save error shows "Couldn't save targets — retry" and keeps the edited order on screen.
- [X] T025a [P] [US3] Write `web/src/useTargets.test.ts` (A8):
  - two rapid edits produce at most one PUT in flight, and the last list wins;
  - a slow first response doesn't overwrite a newer local edit;
  - there is no focus refetch while dirty, in flight or errored;
  - a 404 from an older backend sets `unavailable` without throwing.

### Implementation for US3

- [X] T026 [P] [US3] Create `backend/src/main/java/com/ballknowers/draftsim/store/DraftTargetRepository.java`:
  - `list(owner, scope)`;
  - `replace(owner, scope, List<Long> playerIds)`, which in one `@Transactional` call runs `select pg_advisory_xact_lock(hashtext(?))` keyed `owner + ':' + scope`, then deletes, then inserts with rank 0..n-1;
  - `copyDraftToMock(owner, sleeperDraftId, mockSessionId)`.
  - The scope is a sealed interface `TargetScope` with the records `DraftScope(String sleeperDraftId)` and `MockScope(long mockSessionId)`. Bind types explicitly (lessons class 3).
- [X] T027 [US3] Create `backend/src/main/java/com/ballknowers/draftsim/api/TargetController.java`, implementing `GET` and `PUT /api/targets` per contracts/api.md (amended):
  - **Draft scope:** use `membership.visibleDraft(user, sleeperDraftId)` (404 when it's empty), and get the sport from the draft's league as `seats()` does.
  - **Mock scope:** use `MockDraftService.get` ownership (404).
  - **Players:** map sleeper ids → player ids with `players.idsBySleeperId(sport)`. Build `PlayerRef`s from `boards.currentBoard(sport)`. Targets not on the board go in `missing` as `{sleeperId, name}`.
  - **Wire types:** records only.
  - **`api.ts`:** add `DraftTargets = { players: PlayerRef[]; missing: { sleeperId: string; name: string }[] }`, `getTargets(scope)` and `putTargets(scope, ids)`.
- [X] T028 [US3] In `MockDraftService.createSessionFromDraft`, call `DraftTargetRepository.copyDraftToMock` in the same transaction, for the caller only.
- [X] T029 [P] [US3] Create `web/src/targets.ts` with `fold` (NFD, strip `\p{M}`, lowercase; **new**, since nothing in `web/src` normalizes accents today), `matchesSearch`, `markTaken`, `topAvailable` and `survivalFor`.
- [X] T030 [US3] Create `web/src/useTargets.ts` per T025a: serialized PUTs, edits shown before the save, kept on failure, a guarded focus refetch, and `unavailable` on a 404.
- [X] T031 [US3] Create `web/src/components/TargetStrip.tsx` per T025:
  - **Empty state:** "Star a player in the list to target them."
  - **Live-room copy (FR-015):** "Picks are made on Sleeper — this list only watches."
  - **Unavailable:** "Targets aren't available on this server yet."
  - Add the CSS.
- [X] T032 [US3] In `AvailabilityPanel.tsx`:
  - a search input in the list head (`matchesSearch`, at least 2 characters);
  - a "Hide drafted" toggle (default on), with drafted players marked "taken" when it is off;
  - a star per row to add or remove a target.
  - Both the tiers and stats views respect search and the toggle. Targets come in through props.
  - Keep the head on one row where possible (the A4 height budget).
- [X] T033 [US3] Wire `useTargets` + `TargetStrip` into the `targets` region of all three pages:
  - **Live and projection:** keyed by `draftId`. Survival comes from `result.availability` for the next own pick, only when `slotKnown`.
  - **Mock:** keyed by `mockSessionId`, survival always absent, with the note "Mocks don't run a simulation, so there's no survival number."
  - **Taken:** from `takenPlayerIds` in live, the revealed picks in projection, and `state.picks` in a mock.

---

## Phase 6: User Story 4 — A calm pre-draft room (P2)

**Independent test**: spec US4 scenarios 1–4 on `1414306786223153152`.

### Tests for US4

- [X] T034 [P] [US4] In `web/src/components/ScarcityMeter.test.tsx`:
  - `poolSize === 0` rows aren't chips;
  - one note names them: "SG, SF: no starter-pool players list these first" (the exact copy, since counting is by *first-listed* position, A11);
  - no note when none are hidden.
  - Fixture: the measured distribution, PG 17 · C 11 · PF 8 · SG 0 · SF 0.
- [X] T035 [P] [US4] Write `web/src/components/FormatSummary.test.tsx`:
  - "4 teams · 14 rounds · 2 min · snake";
  - the timer is omitted when null or undefined, and the type likewise;
  - `90` → "1 min 30 s";
  - `28800` → "8 h".

### Implementation for US4

- [X] T036 [P] [US4] Create `web/src/components/FormatSummary.tsx` and put it in the `status` region of all three pages:
  - **Live and projection:** from `SeatsResponse` (`teams`, `rounds`, `draftType`, `pickTimerSeconds`).
  - **Mock:** teams, rounds, "snake", plus the reversal note when `reversalRound > 0`. No timer.
- [X] T037 [P] [US4] In `ScarcityMeter.tsx`, hide `poolSize === 0` rows and add the T034 note. **Counting is unchanged.**
- [X] T038 [US4] Make `LiveStatusBar` the only statement of draft status:
  - move the `waitingDetail` copy variants into it;
  - **delete** `.live-waiting` (`LiveDraftView.tsx` ~:936-941) and its CSS.
  - Update `LiveStatusBar.test.tsx` and assert the status text appears once on the page.
- [X] T039 [US4] Create `web/src/components/RoomControls.tsx`, one compact group with one `primary` action and quiet toggles. Use it in all three `controls` regions.
  - **Live:** "Continue as a mock →" is primary. It is **disabled with a title reason** unless `drafting`, not hidden.

---

## Phase 7: User Story 5 — Mock auto-pick and auto-finish (P3)

**Independent test**: spec US5 scenarios 1–5 and quickstart step 7.

### Tests for US5

- [X] T040 [P] [US5] Preference-ordering cases in `backend/src/test/java/com/ballknowers/draftsim/mock/MockDraftServiceTest.java` (Mockito, real `FootballRules`/`BasketballRules`, as that test already does). **Assert which player was chosen:**
  - **(a)** targets `[X, Y]` with X drafted → Y, even when a non-target has better ADP;
  - **(b)** a non-draftable target is skipped;
  - **(c)** no targets → the top draftable player by ADP with `rosterNeed > benchFloor`, **including an NBA tenth-pick case with nine starters filled, where the top-ADP player is bench-only and must be skipped.** This case must fail under `> 0`;
  - **(d)** NFL: an early K or DEF is never taken (`isDraftable`);
  - **(e)** nobody would start → the top draftable player by ADP;
  - **(f)** FINISH: COMPLETE, only USER-seat picks are AUTO, and earlier manual picks stay USER;
  - **(g)** PICK when it isn't your turn → `IllegalStateException`; COMPLETE → `IllegalStateException`.
  - Also add `/api/mocks/{id}/auto` for another owner → 404 in `AccessControlMvcIT`.

### Implementation for US5

- [X] T041 [US5] Add `auto(long id, Scope scope, String sleeperUserId)` to `backend/src/main/java/com/ballknowers/draftsim/mock/MockDraftService.java`:
  - Check `mayUse`, take `lockForUpdate` once, and fetch `boards.currentBoard(sport)` and `profiles.fit(sport)` **once**.
  - Loop:
    - `contexts.build(...)` with the growing `completed`;
    - choose per research **A1**, considering only players in `ctx.byId` and not drafted;
    - insert with `source "AUTO"`, `seat_type "USER"`;
    - advance the bots using the same context data.
  - Stop when the user is next (`PICK`) or the draft is complete (`FINISH`).
  - Factor the insert-and-advance step out of `submitPick` so the two share it. Read `benchFloor` from `ScoringProperties` for the sport.
- [X] T042 [US5] Add `@PostMapping("/{id}/auto")` to `backend/src/main/java/com/ballknowers/draftsim/api/MockDraftController.java`:
  - The body is `record AutoRequest(Scope scope)`; a null scope → 400.
  - The 409s come from the existing `ErrorHandler`.
  - Add `autoMock(id, scope)` to `web/src/api.ts`, and hide the controls on a 404 from an older backend.
- [X] T043 [US5] In `web/src/pages/MockDraftView.tsx`:
  - `userPicks` and `draftedByUser` count `source === 'USER' || source === 'AUTO'` (`:118`, the only site).
  - Add `RoomControls` actions "Auto-pick" (your turn only) and "Auto-finish the draft" (primary; disabled when complete; busy while the request runs; then `setState(response)`).
- [X] T044 [P] [US5] Add `auto?: boolean` to `FeedPick` in `web/src/components/PickFeed.tsx`, and render a small "auto" tag. `MockDraftView` sets it from `source === 'AUTO'`. Add a case to `web/src/components/PickFeed.test.tsx`.

---

## Phase 8: Polish, review and verification

- [X] T045 Live verification of every quickstart.md step (amended), on a **restarted** `draft-sim-024-api-8094` and a hard-refreshed tab:
  - a 1440×900 viewport, 375×812, and a pick card at 1024;
  - ingest league `1414306784801239040` first;
  - a timed auto-finish.

  Write `verification.md` with verified kept apart from assumed or not run, and measured numbers only.
- [X] T046 Add an "**Amended by spec 024 (2026-10-09)**" note under §E of `claude/board-first-layout-and-pick-latency.md`: the floating sheet was replaced by a stacked, resizable split with compact cells, because the list grew to a 14-column stats table (spec 023) and covered rounds 7-14. Do **not** rewrite §E.
- [X] T047 Run a bug-hunting code review of the full diff. Write `specs/024-draft-room-ux/code-review.md`, fix the confirmed findings on Sonnet, and re-run the tests.
- [X] T048 [P] Update `HANDOFF.md`: spec 024 state, plus what is owed:
  - the Sleeper reset edge case (not met);
  - multi-position NBA (A11, the follow-up task).

  Add durable lessons to `claude/lessons.md`. Candidate: "`rosterNeed` is floored at `benchFloor`, so `> 0` is never a filter."
- [X] T049 Run the full suites: `cd backend && ./gradlew test` (report the **skipped** count) and `cd web && npx tsc -b && npm test && npm run build`. Then ask Allan before committing.

---

## Dependencies & order

- **Phase 2:** T003 → T004. T005, T006 and T007 are parallel; each has its own `api.ts` hunk, so merge carefully.
- **US1 first:** T008/T009/T010 ∥, then T011 → T012/T013/T013a, then T014 ∥ T015 ∥ T016, then T017.
- **US2:** after US1. T019a needs T010/T011's density plumbing.
- **US3:** needs T003/T004 and US1.
- **US4:** needs T006 and US1.
- **US5:** needs T003/T004, T007 and T039. Targets-first needs T026.
- **Polish:** last.

## Implementation strategy

- **MVP = US1 + US2.** Live-verify that slice (T017 + SC-003) before US3.
- **Then US4, US3, US5,** each ending at its checkpoint with a live check.
