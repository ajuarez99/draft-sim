---

description: "Task list for 002-league-history-record-book"
---

# Tasks: A league history worth scrolling

**Input**: Design documents from `/specs/002-league-history-record-book/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/league-history-api.md](./contracts/league-history-api.md),
[quickstart.md](./quickstart.md)

**Tests**: INCLUDED. Not a default — the spec's success criteria (SC-001…SC-005) are written as
queries and assertions, `quickstart.md` names specific test classes (A4, B4, C5), and two of the
three slices turn on bugs that are silent wrong answers rather than crashes (week 0 read as a final
rank; a matchup emitted twice mirrored). Nothing but a test catches those.

**Organization**: Grouped by user story. Each of US1/US2/US3 is independently shippable — see
`plan.md` § Implementation Sequence.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1, US2, US3 — maps to the user stories in `spec.md`

## Path Conventions

Web app: `backend/src/main/java/com/ballknowers/draftsim/`, `backend/src/test/java/…`, `web/src/`.
Integration tests that need Postgres are named `*IT.java` per the existing `store/` convention.

---

## Phase 1: Setup

**Purpose**: Freeze the "before" numbers so SC-002 and SC-003 are verifiable after the change rather
than argued about.

- [X] T001 Create `specs/002-league-history-record-book/baseline.md` (matching the convention in `specs/001-readable-bump-chart/baseline.md`) recording, from the live DB, the four queries in `quickstart.md`: per-league-season row counts, top-5 and bottom-5 `starters_points` for the football chain, paired-`league_matchup` weeks per season, and `power_ranking` snapshots by `(season, week, kind)`. Include the date and the command used.
- [X] T002 [P] Verify Postgres is up (`docker compose up -d postgres`) and that `(Foot) Ball Knowers` 2025 holds 204 `roster_week_points` rows over 17 weeks; if 0, run `POST /api/ingest/league-history/1346366555759341568` first. Record the outcome in `baseline.md`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The response container, the types and the page scaffolding that all three panels hang
off. Deliberately thin — everything story-specific stays in its own phase so the stories remain
independently shippable.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T003 Create `backend/src/main/java/com/ballknowers/draftsim/engine/LeagueRecordService.java` as a Spring `@Service` taking `RosterWeekPointsRepository`, `LeagueMatchupRepository` and `RosterSeasonRepository`. Define the DTO records `WeeklyScoreRecord(int season, int week, int rosterId, Long managerId, String manager, String avatarId, BigDecimal points)` and `MarginRecord(int season, int week, BigDecimal margin, Side winner, Side loser)` with `Side(int rosterId, Long managerId, String manager, String avatarId, BigDecimal points)`, per `data-model.md`. Methods return empty lists for now.
- [X] T004 [P] Extend `web/src/api.ts`: add `LeagueRecords`, `WeeklyScoreRecord`, `MarginRecord`, `MarginSide` and the `RankStatus` union `'RANKED' | 'IN_PROGRESS' | 'NOT_COMPUTED' | 'UNAVAILABLE'`. Add `records: LeagueRecords` to `LeagueHistory`, and add `finalRank?: number | null`, `finalRankWeek?: number | null`, `rankStatus?: RankStatus` to `StandingRow` as **optional** fields — `StandingRow` is shared with `getManagerHistory`, which will not carry rank data, so required fields would break its typecheck (contract § Backward compatibility).
- [X] T005 Wire `LeagueRecordService` into `LeagueHistoryController.history()` in `backend/src/main/java/com/ballknowers/draftsim/api/LeagueHistoryController.java`. Build the chain's league-id list from the existing `leagues.chainBySleeperId(sleeperId)` walk, and always emit a `records` object with `limit`, `highestWeeks`, `lowestWeeks`, `closestMatchups`, `biggestBlowouts` and `marginsUnavailableReason` — present on every 200, never omitted to signal emptiness (contract § Guarantees). Depends on T003.
- [X] T006 [P] Add record-list and margin-card styles to `web/src/styles.css`. Two distinct shapes deliberately: ranked lists for records, paired cards for margins — do not reuse one uniform card for both (plan § Constitution Check).
- [X] T007 Add the two new `<section>` scaffolds to `web/src/pages/LeagueHistory.tsx` below the existing per-season standings, each rendering only its stated empty reason for now. Depends on T004, T006.

**Checkpoint**: The endpoint returns an always-present empty `records` object and the page renders
two explained-empty sections. User story work can begin.

---

## Phase 3: User Story 1 — Settle an argument about the biggest week ever (Priority: P1) 🎯 MVP

**Goal**: Highest and lowest weekly scores across every ingested season, each attributed to a
manager, season and week.

**Independent Test**: Load the page for (Foot) Ball Knowers with only US1 built; the top-scores list
shows real names and scores drawn from all 17 stored weeks of 2025 — not just the current season.

**Data note**: no ingest, no migration, no Sleeper call. Scores are already complete for every
played season (research D1).

### Tests for User Story 1

- [X] T008 [P] [US1] Create `backend/src/test/java/com/ballknowers/draftsim/engine/LeagueRecordServiceTest.java` covering data-model R1–R4: **R1** a roster-season with `manager_id` null still produces a record with `rosterId` populated and `manager`/`managerId`/`avatarId` null rather than being dropped; **R2** ordering is deterministic across repeat calls via the `(season, week, rosterId)` tiebreak, and an exact tie inside the returned set yields both rows; **R3** a season with zero stored weeks contributes nothing and never a `0.00`; **R4** `highestWeeks` and `lowestWeeks` return the same length.
- [X] T009 [P] [US1] Create `backend/src/test/java/com/ballknowers/draftsim/store/LeagueRecordIT.java` asserting the extremes query against real Postgres returns `205.04` at season 2025 week 8 as the football chain's maximum and spans more than one season. Must tolerate a week missing some rosters rather than assuming a full slate (research § Open risk).
- [X] T010 [P] [US1] Create `web/src/pages/LeagueHistory.test.tsx` asserting the record lists render manager name, score and season/week; that an unowned roster row still renders; and that a chain with no stored weeks renders one stated sentence rather than two empty lists.

### Implementation for User Story 1

- [X] T011 [US1] Add an extremes query to `backend/src/main/java/com/ballknowers/draftsim/store/RosterWeekPointsRepository.java`: given a **collection** of league ids (the chain — `league.id` is a league-*season*, not a franchise, per `data-model.md`), return the top/bottom N `starters_points` rows joined to `roster_season` on `(league_id, roster_id)` for `manager_id`, and to `manager` for `display_name`/`avatar_id`, ordered by points then `(season, week, roster_id)` as a deterministic tiebreak.
- [X] T012 [US1] Implement `highestWeeks`/`lowestWeeks` in `backend/src/main/java/com/ballknowers/draftsim/engine/LeagueRecordService.java` over T011, applying a single shared `limit` to both lists (FR-003). Depends on T011.
- [X] T013 [US1] Populate `records.highestWeeks` / `records.lowestWeeks` / `records.limit` in `history()` in `backend/src/main/java/com/ballknowers/draftsim/api/LeagueHistoryController.java`. Emit `points` as a JSON number with the stored `numeric` precision — not a pre-formatted string (contract § Guarantees). Depends on T012.
- [X] T014 [US1] Render the two ranked lists in `web/src/pages/LeagueHistory.tsx` — avatar, name, score, season/week per row — reusing the existing `Avatar` component and linking a named manager to `/managers/{managerId}/history` as the standings table already does. Depends on T013.
- [X] T015 [US1] Verify SC-005: reload `http://localhost:5173/leagues/1346366555759341568/history` and confirm the `backend` log shows no outbound Sleeper request for `GET /api/leagues/{id}/history` (FR-002). Per `quickstart.md` A3.

**Checkpoint**: US1 is fully functional and shippable on its own. This is the MVP.

---

## Phase 4: User Story 2 — See where each season actually finished (Priority: P2)

**Goal**: Each standings row carries that season's end-of-season power rank, with absence explained
rather than blank.

**Independent Test**: Backfill one NBA season, reload, and confirm its standings rows show a final
rank while an un-backfilled season shows a stated reason rather than a blank.

**Data note**: three of four played seasons have zero snapshots, so the backfill is what makes this
story visible (research D3).

### Tests for User Story 2

- [X] T016 [P] [US2] Extend `backend/src/test/java/com/ballknowers/draftsim/engine/PowerRankingServiceTest.java` with the two traps from research D3, which exist live in this database: a season whose only `COMPUTED_REALIZED` snapshot is **week 0** must NOT yield a final rank (R9), and a season whose greatest week is a **`COMMISSIONER`** snapshot must NOT yield one (R10). Both are silent wrong answers, not exceptions.
- [X] T017 [P] [US2] Create `backend/src/test/java/com/ballknowers/draftsim/store/PowerRankingBackfillIT.java` asserting the backfill is idempotent — running it twice leaves exactly one `COMPUTED_REALIZED` row per `(league_id, season, week)` via the existing `ON CONFLICT` — and that a season with no stored week points is reported as skipped with a reason rather than written as an empty snapshot.
- [X] T018 [P] [US2] Extend `web/src/pages/LeagueHistory.test.tsx` asserting each `rankStatus` renders its own distinct output: `RANKED` a rank; `IN_PROGRESS` a "season not over" reading that is not an error; `NOT_COMPUTED` a reason **plus a button**; `UNAVAILABLE` a reason with no button. Assert no case renders a bare `—`.

### Implementation for User Story 2

- [X] T019 [P] [US2] Add a final-snapshot lookup to `backend/src/main/java/com/ballknowers/draftsim/store/PowerRankingRepository.java`: for a `league_id`, the `power_ranking` row with `kind = 'COMPUTED_REALIZED'` and the greatest `week` **strictly greater than 0**, plus its entries by `roster_id`. Both filters are mandatory — `week > 0` per R9 and the kind filter per R10.
- [X] T020 [US2] Add a "season is over" determination in `backend/src/main/java/com/ballknowers/draftsim/engine/PowerRankingService.java` that derives from the league's own settings and the sport's state via the existing `sportState(sport)`. It MUST NOT compare against a hardcoded week number — this DB holds a 17-week NFL season and a 24-week NBA season (FR-011, R12).
- [X] T021 [US2] Add `finalRankForSeason` to `backend/src/main/java/com/ballknowers/draftsim/engine/PowerRankingService.java` returning the rank map plus a `rankStatus` resolved as: `RANKED` if a qualifying snapshot exists; `IN_PROGRESS` if the season is the chain's newest and not over; `NOT_COMPUTED` if complete with no snapshot but week points present; `UNAVAILABLE` if complete with no week points. Depends on T019, T020.
- [X] T022 [US2] Attach `finalRank`, `finalRankWeek` and `rankStatus` to each row in `standingRow()` in `backend/src/main/java/com/ballknowers/draftsim/api/LeagueHistoryController.java`. `rankStatus` is present on every row and `finalRank` is non-null **iff** `RANKED` (contract § Guarantees). Depends on T021.
- [X] T023 [US2] Add a `backfill` method to `backend/src/main/java/com/ballknowers/draftsim/engine/PowerRankingService.java` that, for each completed season in the chain lacking a qualifying snapshot, calls the existing `computeRealized(leagueId, season, week)` with that season's greatest week present in `roster_week_points`. It makes **no Sleeper call** and must refuse a season with no stored week points rather than writing an empty snapshot. Depends on T020.
- [X] T024 [US2] Add `POST /api/leagues/{sleeperId}/power/backfill` to `backend/src/main/java/com/ballknowers/draftsim/api/LeagueHistoryController.java` with optional `?season=`, guarded by the existing `visibleLeague()` check, returning `{ backfilled: [...], skipped: [...] }`. A zero-length `backfilled` MUST be accompanied by a populated `skipped` explaining why — a bare zero reads as a broken feature, a lesson the sibling `/power/compute` endpoint already encodes. Depends on T023.
- [X] T025 [US2] Add the rank column to the standings table in `web/src/pages/LeagueHistory.tsx`, rendering per `rankStatus`. For `NOT_COMPUTED`, render the reason **and a button that fires the backfill** — never the endpoint string for the reader to run. `LeagueHistory.tsx` already carries this exact lesson in a comment about the ingest button; do not reintroduce the pattern (FR-006). Depends on T022, T024, T004.
- [X] T026 [US2] Verify per `quickstart.md` B4: `grep` the files changed in this phase for a literal `17`, `18` or `14` used as a week boundary and confirm none exist.

**Checkpoint**: US1 and US2 both work independently.

---

## Phase 5: User Story 3 — Relive the closest game and the worst beating (Priority: P3)

**Goal**: Closest matchups and biggest blowouts, each naming both sides and both scores.

**Independent Test**: Repair the ingest gate, re-run the league-history ingest, and confirm
`league_matchup` holds 12 paired rosters for weeks 1–17 of 2025 rather than 8 rows in week 17; then
confirm the margins panel populates.

**⚠️ Ordering is not optional here.** The ingest repair (T027–T030) must land and be **verified by
query** before any margins UI is written. Before the repair the pairing set is one week per season,
so a panel built against it will look correct on an effectively empty set (research D2).

### Tests for User Story 3

- [X] T027 [P] [US3] Extend `backend/src/test/java/com/ballknowers/draftsim/ingest/LeagueHistoryIngestServiceTest.java` for the gate repair: given cached week points and **absent** pairings, the ingest fetches those weeks and stores pairings; given both present, it does not re-fetch beyond `lastScoredLeg`; and a week Sleeper answers with all-null `matchup_id`s is still not counted as cached (preserving the existing guard against poisoning the cache with an unscheduled week).
- [X] T028 [P] [US3] Extend `backend/src/test/java/com/ballknowers/draftsim/engine/LeagueRecordServiceTest.java` for margin rules: **R5** a null `matchup_id` contributes no margin; **R6** each pair is emitted exactly once, not twice mirrored; **R7** a pairing with no stored score on one side is excluded rather than treated as zero — guaranteed to occur, since 2026 holds scheduled-but-unplayed fixtures for weeks 1–14 with scores for week 1 only; **R8** a `matchup_id` group holding more than two rosters is skipped rather than arbitrarily paired.

### Implementation for User Story 3

- [X] T029 [US3] Repair the gate in `LeagueHistoryIngestService.ingestWeeklyPoints()` (`backend/src/main/java/com/ballknowers/draftsim/ingest/LeagueHistoryIngestService.java:144`). Currently `if (stored.contains(week) && week != lastScoredLeg) continue;` skips a week whose points are cached and takes the `league_matchup` upsert down with it. Make the two conditions independent: fetch a week when its points are missing **OR** its pairings are missing, testing pairings with the existing `LeagueMatchupRepository.scheduledWeeks(leagueId, season)`, which already declines to count an all-null week as cached. Do **not** drop the gate entirely — that re-fetches every week of every season on every ingest, which is the cost the gate exists to avoid (research D2, alternatives).
- [X] T030 [US3] Run `POST /api/ingest/league-history/1346366555759341568`, then re-run the pairing-coverage query from `quickstart.md` C1. **Gate**: 2025 must now list weeks 1–17 with 12 paired rosters each, against a baseline of 8 rows in week 17 (SC-003). Record before/after in `baseline.md`. Do not proceed to T031 until this passes. Depends on T029.
- [X] T031 [US3] Add a paired-with-scores query to `backend/src/main/java/com/ballknowers/draftsim/store/LeagueMatchupRepository.java`: self-join `league_matchup` on `(league_id, season, week, matchup_id)` constrained by `a.roster_id < b.roster_id` (R6 — without this every matchup returns twice, mirrored), joining each side to `roster_week_points` on `(league_id, week, roster_id)` and excluding rows where either side has no stored score. Depends on T030.
- [X] T032 [US3] Implement `closestMatchups`/`biggestBlowouts` in `backend/src/main/java/com/ballknowers/draftsim/engine/LeagueRecordService.java` over T031, ordering by margin ascending and descending, each bounded by the same `limit` as the score lists. Assign `winner`/`loser` by points, not by `roster_season.wins`. Depends on T031.
- [X] T033 [US3] Populate `records.closestMatchups`, `records.biggestBlowouts` and `records.marginsUnavailableReason` in `history()` in `backend/src/main/java/com/ballknowers/draftsim/api/LeagueHistoryController.java`. The reason is a non-null sentence exactly when the margin lists are empty, and null when they are populated (contract § Guarantees). Depends on T032.
- [X] T034 [US3] Render the margin cards in `web/src/pages/LeagueHistory.tsx` — margin, season/week, both sides with scores — and render `marginsUnavailableReason` in place of the cards when it is non-null, never empty cards. Depends on T033, T006.
- [X] T035 [US3] Verify per `quickstart.md` C3 against the independent values in the reference screenshot: closest margin `0.16` at 2025 week 17, biggest blowout `118.86` at 2025 week 8 with `winner.points == 205.04` — the same number US1 reports as the all-time high. Also confirm no `(season, week, margin)` triple appears twice.

**Checkpoint**: All three user stories are independently functional.

---

## Phase 6: Slice D — Per-season team names (OPTIONAL)

**Purpose**: Close the labelling gap against the reference UI. Records currently read
"GraftonCarlson — 205.04" where ffwrapped reads "Justice for Wags — 205.04", because this database
stores no team name anywhere (research D4).

**Not required by any user story.**

**WITHDRAWN 2026-09-16 — not implemented, because its acceptance criterion is unreachable.** Two
measurements taken during implementation (see [research.md](./research.md) D4):

1. **No migration was needed.** `league_member` already carries a per-season `team_name`, already
   populated by the ingest through `LeagueMapper.teamName`, already with the fall-back rule. T036
   would have added a column that exists.
2. **The stored names are the CURRENT ones and Sleeper has no others.** Queried live against the
   2025 league's own Sleeper id: roster 7 is `i Chase Brown kids` today, where the reference page
   shows **Justice for Wags** for 2025. `metadata.team_name` is mutable and returns the present
   value even for a past league. The 2025 names are gone.

So T039 ("roster 7 in 2025 renders as `Justice for Wags`") cannot pass, and shipping the current
name as a historical label would attribute the league's biggest-ever week to a team that never held
that name in 2025 — the same quiet wrongness as the pre-repair margins panel. Records therefore
attribute to `manager.display_name`.

A forward-only fix exists (snapshot `team_name` onto `roster_season` at ingest so future seasons
freeze their labels); it cannot recover 2024 or 2025, and is out of scope here.

- [~] T036 [US-D] Create `backend/src/main/resources/db/migration/V16__roster_season_team_name.sql` adding a **nullable** `team_name text` column to `roster_season`. Current migration head is `V15__player_projection.sql`. Nullable is required — existing rows have no value and the column is per-season by construction.
- [~] T037 [US-D] Populate `team_name` from Sleeper's league-users `metadata.team_name` during ingest in `backend/src/main/java/com/ballknowers/draftsim/ingest/LeagueHistoryIngestService.java`. Depends on T036.
- [~] T038 [US-D] Extend `StandingRow` in `backend/src/main/java/com/ballknowers/draftsim/store/RosterSeasonRepository.java`, the DTOs in `LeagueRecordService.java`, and the types in `web/src/api.ts` with `teamName`, and apply the display rule "team name, falling back to `manager.display_name`" — a roster with no stored team name must fall back rather than render blank. Depends on T037.
- [~] T039 [US-D] Verify per `quickstart.md` Slice D: roster 7 in 2025 renders as `Justice for Wags`, roster 8 as `Puka-Boo`, roster 12 as `Torta Pounder with Cheese`.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T040 [P] Run the full backend suite: `cd backend && ./gradlew test`. **Check the skip count, not just the exit code** — this suite reports `BUILD SUCCESSFUL` with integration tests silently skipped when Postgres is unreachable, so a green build alone proves nothing.
- [X] T041 [P] Run `cd web && npm run test` and `cd web && npm run build`.
- [X] T042 Walk the empty-and-degraded state table in `quickstart.md` against `West Coast Fantasy Football` 2025 (216 week points, **zero** pairings) and NBA 2026 (0 weeks). Confirm SC-004: no panel on the page is simultaneously empty and unexplained.
- [X] T043 [P] Update the file-header comment in `web/src/pages/LeagueHistory.tsx`, which currently describes the page as `claude/league-suite.md Phase A: standings across every ingested season` — no longer the whole truth once it carries a record book.
- [X] T044 Re-read the diff for the conventions in `plan.md` § Constitution Check: no endpoint printed for the reader to run, no unexplained empty panel, no optional parameter silently encoding a sport's rule, no uniform card reused across two different content shapes.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Setup. **Blocks all user stories.**
- **US1 (Phase 3)**: depends on Phase 2 only.
- **US2 (Phase 4)**: depends on Phase 2 only. Independent of US1.
- **US3 (Phase 5)**: depends on Phase 2 only. Independent of US1 and US2.
- **Slice D (Phase 6)**: optional; touches display code US1/US3 also touch, so run it after whichever of those have shipped.
- **Polish (Phase 7)**: after the desired stories are complete.

### Critical path inside US3

T029 → T030 (**verification gate**) → T031 → T032 → T033 → T034. T030 is a hard gate, not a
formality: the margins code is unverifiable before the ingest repair lands.

### Parallel Opportunities

- T002 alongside T001.
- T004 and T006 in parallel within Phase 2 (different files, no shared dependency).
- All three test tasks in US1 (T008, T009, T010) in parallel.
- All three test tasks in US2 (T016, T017, T018) in parallel.
- Both test tasks in US3 (T027, T028) in parallel.
- T019 is [P] within US2 — a repository query with no dependency on T020.
- **US1, US2 and US3 can be worked in parallel by different people once Phase 2 is done**, with one
  caveat: T014, T025 and T034 all edit `web/src/pages/LeagueHistory.tsx`, and T013/T022/T033 all edit
  `LeagueHistoryController.java`. Those are the merge points.
- **`web/src/styles.css` (T006) is a shared surface beyond this feature.** Branch `001` changed it
  too, and several sessions work in this tree at once. T006's additions are purely new rules, so
  they merge cleanly, but rebase onto `main` before writing them rather than after.
- T040, T041, T043 in parallel in Polish.

---

## Parallel Example: User Story 1

```bash
# Launch all three US1 test tasks together:
Task: "LeagueRecordServiceTest covering R1-R4 in backend/src/test/java/com/ballknowers/draftsim/engine/LeagueRecordServiceTest.java"
Task: "LeagueRecordIT extremes assertion in backend/src/test/java/com/ballknowers/draftsim/store/LeagueRecordIT.java"
Task: "LeagueHistory.test.tsx record list rendering in web/src/pages/LeagueHistory.test.tsx"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1: Setup — freeze the baselines.
2. Phase 2: Foundational — the `records` container and page scaffolding.
3. Phase 3: US1 — score extremes.
4. **STOP and VALIDATE**: `quickstart.md` A1–A4. The page's top entry must equal `205.04` at 2025
   week 8, matched against the query rather than by eye.
5. Ship. US1 needs no migration, no ingest change and no Sleeper call — it is the lowest-risk slice
   in the feature.

### Incremental Delivery

1. Setup + Foundational → foundation ready.
2. US1 → validate → ship (**MVP**).
3. US2 → backfill one NBA season → validate the four `rankStatus` renderings → ship.
4. US3 → repair ingest → **verify pairings by query (T030)** → build margins → ship.
5. Slice D only if the reference labelling matters.

### Notes

- `[P]` = different files, no dependencies on incomplete tasks.
- Commit after each task or logical group.
- Every verification in `quickstart.md` is a query or a page interaction. Where it says to click a
  button rather than curl an endpoint (B3), a curl-only verification is a **failure** of FR-006, not
  a pass.
