# Tasks: Player stats in the draft room, with stats you choose

**Input**: design documents in `specs/023-draft-room-player-stats/`: plan.md, spec.md, research.md,
data-model.md, contracts/api.md, quickstart.md

**Worktree**: `C:\Users\allan\source\draft-sim-023`, branch `023-draft-room-player-stats` (from
`022-player-stat-analysis` at 555592e). All paths below are relative to that worktree.

**Tests**: included. The plan puts every rule (join, stored choice, catalog, sort) in a plain `.ts`
module *so that* it can be unit-tested, and AGENTS.md requires an ordering test for any sort
change. Live verification (quickstart.md) is a separate, required phase. A green suite is not this
project's bar for "verified".

**Deadline**: the NBA draft starts 2026-10-10 19:15 UTC. A story merges only if it has passed its
live checks by then (spec Clarifications 2026-10-08).

**Coding agents run on Sonnet** (AGENTS.md). The parent session reads every diff before marking a
task done.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1, US2 or US3, from spec.md

---

## Phase 1: Setup

- [X] T001 Confirm the baseline in worktree `draft-sim-023`. Run `cd backend && ./gradlew test` and `cd web && npx tsc -b && npx vitest run`, with local Postgres on **5433** up (not 5432). Record the pass, fail and **skip** counts in `specs/023-draft-room-player-stats/verification.md` (new file, heading "Baseline"). A non-zero IT skip count means Postgres wasn't reachable, and the run doesn't count.

---

## Phase 2: Foundational (blocks every story)

**Purpose**: the two wire additions and the shared cell renderer. US1 can't show league stats without the league id, and FR-002 needs one renderer.

- [X] T002 [P] Add `sleeperLeagueId` to the `seats` response in `backend/src/main/java/com/ballknowers/draftsim/api/LeagueController.java` `seats()`: `response.put("sleeperLeagueId", league.map(LeagueRepository.LeagueRow::sleeperId).orElse(null));`, in the existing `LinkedHashMap` (never `Map.of`: the value is nullable when the league row is missing). Contract C1.
- [X] T003 [P] Add `Integer scoringSeason` and `Boolean scoringMatchesRequested` to the `StatLeaderboard` record, immediately after `requestedSeason`, in `backend/src/main/java/com/ballknowers/draftsim/engine/PlayerStatsService.java`. In `readLeaderboard` (the `new StatLeaderboard(` at ~line 451), pass `league.season()` as `scoringSeason`. For `scoringMatchesRequested`, pass `null` when `resolved.requestedSeason() == null`. Otherwise pass `leagues.scoringOf(league.id()).equals(leagues.scoringOf(requested.id()))`, computed only in that branch. In `unavailableBoard` (~line 467), pass `null, null`. Update the record's Javadoc to match contract C2 ("the season whose league scoring scored every fantasy figure"; "null when there was no fallback"). Fix every other constructor call the compiler flags, including tests.
- [X] T004 Mirror T002 and T003 in `web/src/api.ts`, in the same change as the Java (constitution rule 2). Add `sleeperLeagueId?: string | null` to `SeatsResponse`, and `scoringSeason?: number | null` and `scoringMatchesRequested?: boolean | null` to `StatLeaderboard`. Each gets a doc comment saying it's optional because the frontend can deploy ahead of the backend (the 2026-09-14 white-page incident), and that `undefined` means unknown.
- [X] T005 [P] Backend tests. In `backend/src/test/java/com/ballknowers/draftsim/api/LeagueControllerSeatsOwnerConfiguredIT.java`, assert that `$.sleeperLeagueId` equals the fixture league's Sleeper id. In `backend/src/test/java/com/ballknowers/draftsim/engine/PlayerStatsLeaderboardReadIT.java`, add three cases:
  - (a) no fallback: `scoringSeason == season` and `scoringMatchesRequested == null`;
  - (b) fallback with identical scoring: `true`;
  - (c) fallback where the requested league's `scoring_json` differs in one key: `false`.
  Run `./gradlew test` and check that the skip count is unchanged from T001.
- [X] T006 [P] Move `Cell` and its private helpers (formatting and reason text for one leaderboard cell) out of `web/src/pages/StatLeaderboard.tsx` (~line 119) into a new `web/src/statCells.tsx`, exported. `pages/StatLeaderboard.tsx` then imports it. This is a pure move: no behaviour change, and `web/src/pages/StatLeaderboard.test.tsx` must pass **unchanged** (research R7, one renderer for FR-002).
- [X] T007 In `web/src/components/AvailabilityPanel.tsx`, export the existing `RISK_MAX` (0.35), so the "likely there" filter reads the same boundary as the verdict (research R5). Do not change its value.

**Checkpoint**: `curl` the seats and stats endpoints per quickstart V1. Expect `sleeperLeagueId` `1339351318115946496`, `scoringSeason` 2025 and `scoringMatchesRequested` true. Both suites green, with skip counts noted.

---

## Phase 3: User Story 1 - See real stats for the players still on the board (P1) 🎯 MVP

**Goal**: the available-players sheet in the live room gets a **Stats** view: one row per undrafted player, using 022's leaderboard values, with the season named. Picks remove rows live.

**Independent test**: quickstart V2 and V3. 10 of 10 values match the leaderboard, the row count equals the tier list's, and 20 of 20 picks update the table in the same render as the board.

### Tests for US1

- [X] T008 [P] [US1] Write `web/src/draftRoomStats.test.ts` for `joinPoolStats` (T010). It must assert:
  - `rows.length === pool.length`, with every pool player exactly once, in pool order;
  - the join is on `PlayerRef.sleeperId === LeaderboardRow.sleeperPlayerId`, and not on `PlayerRef.id`;
  - a pool player with no leaderboard row gets `stats: null` and `reason: 'NO_SEASON_GAMES'`;
  - a leaderboard row whose player isn't in the pool (already drafted) is dropped;
  - `survivalNext` and `fillsSlot` are passed through unchanged.
- [X] T009 [P] [US1] Ordering tests (AGENTS.md bug class 1, preference order and not just structure) in `web/src/draftRoomStats.test.ts` for `sortDraftRows` (T010):
  - sorting by `pts` desc puts the higher scorer first;
  - by `tov` (lower is better), the natural first click puts fewer turnovers first;
  - rows with `stats === null` are last in **both** directions;
  - a row with a null value in the sort column is last in both directions;
  - ties break as `statLeaderboard.ts`'s `compareRows` does (games, then name, then id);
  - `likelyOnly` keeps exactly the rows with `survivalNext >= RISK_MAX`, and keeps all rows when `survivalNext` is null for every row (seat unknown), because the filter is unavailable then and not empty.

### Implementation for US1

- [X] T010 [US1] Create `web/src/draftRoomStats.ts` with these pure functions, and make T008 and T009 pass:
  - `type DraftStatRow = { player: PlayerRef; stats: LeaderboardRow | null; reason: 'NO_SEASON_GAMES' | null; survivalNext: number | null; fillsSlot: string | null }`;
  - `joinPoolStats(pool, rows, survivalNext: (p) => number | null, fillsSlot: (p) => string | null): DraftStatRow[]`;
  - `sortDraftRows(rows, sort: SortState, mode: StatMode): DraftStatRow[]`. It delegates the comparison of two rows that both have stats to `compareRows` from `web/src/statLeaderboard.ts`, so the order can't drift from the leaderboard's;
  - `filterLikely(rows, on: boolean)`;
  - `shownSeason(board: StatLeaderboard)`, which returns `{ seasonLabel: "2025–26", fellBack, scoringLabel | null, scoringChanged: boolean }` per data-model.md ShownSeason. `scoringLabel` is null when `scoringSeason` is undefined or null (an older backend), and `scoringChanged` is true only when `scoringMatchesRequested === false`.
- [X] T011 [US1] Create `web/src/components/DraftStatsTable.tsx`. Props: `rows: DraftStatRow[]`, `columns: StatColumn[]`, `board: StatLeaderboard`, `sleeperLeagueId`, `mode`, `sort`, `onSort`, `likelyOnly`, `onLikelyOnly`, `nextPickLabel: string | null`, `likelyUnavailableReason: string | null`. It renders:
  - a header line from `shownSeason`: "2025–26 stats, regular season · Season window"; when `fellBack`, "Last season's play, not a projection."; the scoring label when known; and when `scoringChanged`, "This league's scoring has changed since then.";
  - a table with a sticky first column (player face, name, position, ADP, the open-slot tag and survival to the next pick when known). The other columns come from `columns`, each rendered through `Cell` from `web/src/statCells.tsx` (no local number formatting);
  - for a `stats === null` row, one cell spanning every stat column, reading "No NBA games in {seasonLabel}" (never a 0 per column);
  - sortable headers, reusing the leaderboard's `SortHeader` behaviour via `toggleSort`;
  - names as `<a href="/leagues/{sleeperLeagueId}/players/{sleeperId}" target="_blank" rel="noopener">` (research R8);
  - a "Likely there at my next pick" toggle with the printed rule "35%+ chance he's there at {nextPickLabel}", or disabled with `likelyUnavailableReason`.

  The wrapper has `overflow-x: auto` so the page never scrolls sideways at 375 px (FR-016).
- [X] T012 [US1] Add styles for `DraftStatsTable` to `web/src/styles.css`: a sticky first column with its own background so cells don't show through when scrolling, the table-only horizontal scroll, and compact row height that fits the sheet. Reuse the leaderboard's existing table classes wherever they apply, and don't fork them.
- [X] T013 [US1] In `web/src/components/AvailabilityPanel.tsx`, add a "Tiers | Stats" segmented switch at the top of the sheet. It's shown only when `sport === 'nba'`, so football is unchanged (FR-017). New optional props: `sleeperLeagueId?: string | null` and `statsUnavailableReason?: string`. When `sleeperLeagueId` is missing or undefined, the Stats option is disabled with "Stats aren't available on this server yet" (split-deploy safe, contract C1). In Stats mode:
  - fetch `getStatLeaderboard(sleeperLeagueId, window)` **once per window** (a `useEffect` keyed on league and window, never on picks);
  - build the pool from the same undrafted list the tier view uses (after `pickedPlayerIds` is removed);
  - join with `joinPoolStats`, passing `survivalNext` from the first of `myPicks` when survival is shown, otherwise null;
  - render `DraftStatsTable` with `DEFAULT_COLUMNS` (T014). US2 replaces this with the stored choice;
  - on a fetch error or `available === false`, show the reason sentence via `statCopy.reasonSentence` (or "Couldn't load stats" for a network error), while the tier view stays usable (FR-015).

  View state (`view`, `sort`, `window`, `mode`, `likelyOnly`, scroll) lives in the panel and must survive re-renders caused by landed picks (FR-004).
- [X] T014 [US1] In `web/src/statLeaderboard.ts`, export `DEFAULT_COLUMNS = ['gp','min','fp','pts','reb','ast','stl','blk','tpm','tov','fgPctMA','ftPctMA']` (research R6, FR-009), with a comment saying it's a starting point for a points league and not a recommendation. Add a test in `web/src/statLeaderboard.test.ts` that every id is in `COLUMNS`.
- [X] T015 [US1] In `web/src/pages/LiveDraftView.tsx`, pass `sleeperLeagueId={seats.sleeperLeagueId}` to `<AvailabilityPanel>` (~line 890). Pass nothing else new: survival and `myPicks` already flow in.
- [X] T016 [US1] Component tests in `web/src/components/AvailabilityPanel.test.tsx` (mock `getStatLeaderboard`):
  - the Stats switch is absent for `sport='nfl'`, and disabled with its reason when `sleeperLeagueId` is undefined;
  - switching to Stats renders one row per undrafted player;
  - re-rendering with one more id in `pickedPlayerIds` removes that row **without** a second `getStatLeaderboard` call, and keeps the chosen sort;
  - a no-stats player shows "No NBA games in 2025–26";
  - with `requestedSeason` set, the header contains "not a projection";
  - with `scoringMatchesRequested: false`, the warning shows;
  - when the fetch rejects, an error line shows and the Tiers option still works.
- [X] T017 [US1] **Live verification of US1**, quickstart V1–V3, on a backend restarted from this worktree (check the bootRun classpath, per memory "Worktree preview serves main") and the vite dev server. Drive the real browser:
  - 10-player value comparison against `/leagues/1339351318115946496/stats`;
  - row count equals the tier count;
  - 375 px layout;
  - the player link opens in a new tab while the room stays connected;
  - a 20-pick replay with stats closed and then open: record the pick-to-board timings and the first leaderboard request time.

  Write the measured numbers (not estimates) to `specs/023-draft-room-player-stats/verification.md` under "US1", with screenshots described. Anything failing is fixed and re-checked before US2 starts.

**Checkpoint**: US1 is shippable on its own with the default columns. If time runs out here, this is what goes into the 10-10 draft.

---

## Phase 4: User Story 2 - Choose the stats that matter to me (P1)

**Goal**: a stat picker **modal** that turns stats on and off and reorders them (usage rate included), remembered per device and resettable.

**Independent test**: quickstart V4. Choose FP/G, MIN, USG and TS%, sort by USG, reload, open another room and see the same four. Reset brings back the defaults, and a bogus stored id is skipped.

### Tests for US2

- [X] T018 [P] [US2] Write `web/src/statChoice.test.ts` for `readStatChoice`, `writeStatChoice` and `resetStatChoice` (T020):
  - an absent key → `DEFAULT_COLUMNS`;
  - `{"v":2,...}` or invalid JSON → `DEFAULT_COLUMNS`;
  - `{"v":1,"columns":["gp","bogus","gp"]}` → `['gp']` (unknown ids skipped, duplicates dropped, data-model StatChoice);
  - `{"v":1,"columns":[]}` → `[]` ("Empty list: allowed… It is not treated as absent");
  - a `localStorage` that throws on get or set → defaults on read, and no throw on write;
  - reset writes `DEFAULT_COLUMNS` explicitly.
- [X] T019 [P] [US2] Add tests to `web/src/statLeaderboard.test.ts` for `PICKABLE` (T021):
  - it contains the Basic, Shooting, Advanced and Fantasy groups, and no Draft value group;
  - no stat appears twice (`fgPct`/`ftPct` are replaced by `fgPctMA`/`ftPctMA`; `leagueRank` appears once);
  - `usg` is present;
  - every `DEFAULT_COLUMNS` id is in `PICKABLE`.

### Implementation for US2

- [X] T020 [US2] Create `web/src/statChoice.ts`, modelled on `web/src/pickCardsPref.ts` (the try/catch discipline). The key is `bk.draftStats.v1` with the value `{ v: 1, columns: string[] }` (contract C3). Exports: `readStatChoice(): string[]`, `writeStatChoice(columns: string[]): void` and `resetStatChoice(): string[]`. Read rules, verbatim from data-model.md: "Any other value reads as 'never chose', so the default is used"; "Duplicates are dropped on read. Unknown ids are skipped on read, without error". Make T018 pass.
- [X] T021 [US2] In `web/src/statLeaderboard.ts`, export `PICKABLE: { id: ColumnGroup; label: string; columns: StatColumn[] }[]`, built from `GROUPS` minus `draft`, with Basic's `fgPct`/`ftPct` dropped in favour of Shooting's `fgPctMA`/`ftPctMA` (research R6). Make T019 pass.
- [X] T022 [US2] Create `web/src/components/StatPickerModal.tsx`, following `web/src/components/StartMockModal.tsx`'s dialog pattern (`role="dialog"`, `aria-modal="true"`, Escape closes it, focus returns to the opener). It shows:
  - a "Shown" list at the top, in the current order, each with up and down buttons (buttons, not drag: they work on a phone and with a keyboard) and a remove control;
  - below it, `PICKABLE` grouped, each stat as a checkbox with its `label` and `title` (the full name);
  - "Reset to default" and "Done".

  Every change calls `onChange(columns)` immediately (FR-007, "the table updates immediately"). The modal holds no copy of the room's pick state, so a landed pick behind it re-renders the table and leaves the modal open with its toggles intact (US2 scenario 8). It must fit 375 px with its own vertical scroll.
- [X] T023 [US2] Wire it in `web/src/components/AvailabilityPanel.tsx`:
  - replace `DEFAULT_COLUMNS` with state initialised from `readStatChoice()`, and write each change through `writeStatChoice`;
  - add a "Choose stats" button in the Stats view that opens `StatPickerModal`;
  - map ids to `COLUMNS[id]`, skipping any missing one;
  - with zero columns, `DraftStatsTable` shows the names plus "No stats chosen" and a reset button (US2 scenario 4);
  - if the current sort column is removed, fall back to sorting by `fp`, or by name if `fp` isn't shown.

  Also add the window (Season, Last 10, Last 5) and mode (Per game, Totals, Per 36) segmented controls from the leaderboard (FR-014, defaults Season and Per game). A window change refetches, and a mode change doesn't.
- [X] T024 [US2] Component tests in `web/src/components/StatPickerModal.test.tsx` and `web/src/components/AvailabilityPanel.test.tsx`:
  - toggling USG on adds a USG column to the table at once;
  - moving a stat up reorders the table columns;
  - the choice persists via `localStorage` across an unmount and remount;
  - Escape closes the modal;
  - re-rendering the panel with a newly picked player while the modal is open keeps the modal open and the toggles intact;
  - removing the sorted column falls back as specified.
- [X] T025 [US2] **Live verification of US2**, quickstart V4 (all six steps), in the real browser on desktop and at 375 px. Record the results in `specs/023-draft-room-player-stats/verification.md` under "US2".

**Checkpoint**: US1 and US2 together are the full P1 scope for 10-10.

---

## Phase 5: User Story 3 - The same view in a mock draft (P2)

**Goal**: basketball mock rooms get the same Stats view and stored choice, without the next-pick filter.

**Independent test**: quickstart V5.

- [X] T026 [US3] In `web/src/pages/MockDraftView.tsx`, pass `sleeperLeagueId={state.sourceSleeperLeagueId ?? null}` to `<AvailabilityPanel>` (~line 180). In `AvailabilityPanel`, when `availability` is absent (mock mode), the "likely there" toggle is disabled with "Mock drafts don't project who'll be there" (FR-011, FR-018). When `sleeperLeagueId` is null in a basketball mock, Stats is disabled with "Stats need a league: start the mock from a league to see them" (research R9). Note the different reason from the undefined, old-backend case in T013: null means no league, undefined means the server doesn't send it.
- [X] T027 [US3] Tests in `web/src/components/AvailabilityPanel.test.tsx`:
  - mock mode with a league shows Stats and has the likely toggle disabled with its reason;
  - mock mode with `sleeperLeagueId={null}` disables Stats with the league reason;
  - a choice written in one panel instance is read by a second (mock) instance.
- [X] T028 [US3] **Live verification of US3**, quickstart V5 (a mock from the "Ball Knowers" league, a mock with no league, and a football room). Record the results in `specs/023-draft-room-player-stats/verification.md` under "US3".

---

## Phase 6: Polish, review and ship

- [X] T029 Split-deploy check, quickstart V6. Run the dev frontend from this branch against a backend built from `022-player-stat-analysis`, which has no new fields. The room loads, Stats reads "not available on this server yet", and the console shows no errors. Record the result in verification.md.
- [X] T030 Bug-hunting code review of the whole diff (`git diff 022-player-stat-analysis...HEAD`), as a separate pass from the build. It isn't a style pass. Hunt specifically for:
  - join-key mistakes (`id` vs `sleeperId`);
  - refetches triggered by picks;
  - state reset on pick updates;
  - nullable fields read without a guard;
  - any number formatted outside `statCells.tsx`.

  Write the findings and their fixes to `specs/023-draft-room-player-stats/code-review.md`.
- [X] T031 Update docs: `HANDOFF.md` (023 state, what's verified and what's assumed, the 10-10 outcome to be filled in after the draft) and `README.md` (a mention under the live room). If a durable lesson came up, add it to `claude/lessons.md`. Record the FR-006 amendment's measured fact (2025 and 2026 scoring identical) in HANDOFF too, so a later season whose scoring changes isn't misread.
- [ ] T032 **Ask Allan before committing**, then commit per story. Before 2026-10-10 19:15 UTC, and **only with explicit confirmation**: merge `022-player-stat-analysis`, then `023-draft-room-player-stats`, into `main`, push, and deploy **both** Railway services (backend first, then frontend, since the two services deploy separately). Prod-check `/api/drafts/1339351318128517120/seats` for `sleeperLeagueId` and the room's Stats view. Ship only the stories whose live check passed. Anything unverified stays on the branch (spec Clarifications 2026-10-08).

---

## Dependencies and execution order

- **Phase 1 → Phase 2 → US1 → US2 → US3 → Phase 6.** The order is strict for this deadline, even
  where it could be parallel, because each story is the fallback ship point for the one after it.
- **Inside Phase 2**: T002, T003, T006 and T007 touch different files and can run in parallel. T004
  follows T002 and T003, which is the constitution's mirror rule. T005 follows T002 and T003.
- **US1**: T008 and T009 (tests) come before T010. T011 and T012 depend on T010. T013 depends on T010,
  T011 and T014. T015 depends on T013. T016 depends on T013. T017 comes last.
- **US2**: T018 → T020 and T019 → T021, as two independent pairs. T022 depends on T021. T023 depends
  on T020 and T022. T024, then T025.
- **US3**: T026 depends on US2's T023, because the stored choice must exist to share. Then T027 and
  T028.

## Parallel examples

```text
Phase 2:  T002 (LeagueController) ‖ T003 (PlayerStatsService) ‖ T006 (statCells move) ‖ T007 (export RISK_MAX)
US1:      T008 ‖ T009 (both in draftRoomStats.test.ts, so one agent writes both) ‖ T014 (DEFAULT_COLUMNS)
US2:      T018 + T020 (statChoice) ‖ T019 + T021 (PICKABLE)
```

## Implementation strategy

1. **The MVP is US1** (Phases 1–3) with the default columns. It's verified live and can ship alone
   if 10-10 arrives first.
2. **US2** next. It's the user's explicit ask ("pick and choose stats"), with usage rate in the
   modal.
3. **US3** only if US1 and US2 are verified with time to spare. Otherwise it ships after the draft.
4. **US4** (weighted personal ranking) is out of this round per the spec, and has no tasks.
