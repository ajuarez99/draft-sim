---
description: "Task list for spec 040 — awards commissioner's list, every player, fairer Embiid cost"
---

# Tasks: Awards — commissioner's list, every player, and a fairer Embiid cost

**Input**: `specs/040-superlatives-commissioner-ux/` — spec.md (clarified + amended 2026-10-20), plan.md (with
"Amended after the adversarial plan review"), research.md, data-model.md, contracts/awards-api.md, quickstart.md,
plan-review.md.

**Tests**: included. This repo's bar (AGENTS.md, lessons class 1) needs preference-ordering tests for any scoring
change, and plan-review F2/F3/F5/F6/F9 each name a regression test.

Paths are relative to the worktree root `C:\Users\allan\source\draft-sim\.claude\worktrees\040-superlatives-commissioner-ux`.
`SSS` = `backend/src/main/java/com/ballknowers/draftsim/engine/SeasonSuperlativesService.java`.

## Phase 1: Setup

- [X] T001 Start Docker Postgres on 5433 (never 5432), run the backend from THIS worktree, and save `GET /api/leagues/{id}/superlatives` for NBA 2025 `1229352720222134272`, NBA 2024 `1141438340626231296`, NFL 2025 `1254190892974084096` and `1262506916429430784`, NFL 2026 `1346366555759341568` to `specs/040-superlatives-commissioner-ux/baseline/<sleeperId>.json` (before side of SC-002/SC-003). Stop the backend afterwards.

## Phase 2: Foundational (blocks US2 and US3)

- [X] T002 Create `backend/src/main/resources/db/migration/V38__conduct_entry_through_week.sql`: `alter table league_conduct_entry add column applies_through_week int null` plus constraint `check (applies_through_week is null or applies_through_week >= applies_from_week)`; header comment: null = rest of season, existing rows keep today's meaning.
- [X] T003 Add `playerContributions` (List<PlayerContribution>) as the last component of `Superlative` in `SSS`, with record `PlayerContribution(int rank, String playerId, String playerName, String position /*nullable*/, Holder team, double value, String note /*nullable*/)`. Keep every existing constructor overload compiling by defaulting it to `List.of()`. **Then update `withUsernames` (`SSS` ~line 1550) to copy `playerContributions` explicitly, mapping each `team` through the same username fill as holders** (plan-review F2 — a defaulting overload would silently drop it). Same for `withStandings` (~line 535) and any other helper that rebuilds a `Superlative` (grep `new Superlative(`).
- [X] T004 In `backend/src/main/java/com/ballknowers/draftsim/api/SuperlativesController.java` `superlativeRow`, emit `playerContributions` as a list of mutable maps (`rank, playerId, playerName, position, team` via the existing `holderRow`, `value, note`); nullable fields mean no `Map.of`.
- [X] T005 Mirror T003/T004 in `web/src/api.ts`: `SuperlativePlayerContribution` type and `playerContributions: SuperlativePlayerContribution[]` on `Superlative`; holder fields exactly as the existing holder type (`rosterId, managerId, teamName, username, avatarId`).

**Checkpoint**: backend compiles, existing tests pass, payload carries `playerContributions: []` everywhere.

## Phase 3: User Story 1 — Embiid cost per week (P1) 🎯 MVP

**Goal**: a counted week costs one game's worth (`pointsPerGame`), once; a basketball week with any game played costs 0; football identical.
**Independent test**: AbsenceCostTest ordering cases; NFL 2025 totals identical to T001 baseline; NBA 2025 before/after table.

- [X] T006 [US1] Write failing tests in `backend/src/test/java/com/ballknowers/draftsim/engine/AbsenceCostTest.java`: (a) zero-play week with 4 absence rows costs exactly 1 × ppg; (b) a week with ≥1 absence and ≥1 played game costs 0 and is not counted; (c) zero-play week with 1 absence costs the same as one with 4; (d) preference ordering — roster A (3 zero-play weeks of a 20-ppg player) ranks above roster B (1 zero-play week of a 40-ppg player + 3 partial weeks of the same player); (e) football-shaped input (one absence, no game, per week) gives the same totals as the old rule did; (f) `gamesMissed` counts only absence rows inside counted weeks. Update existing cases that asserted per-game totals or `weeksAffected` — change expectations deliberately, with a comment citing spec 040 FR-001.
- [X] T007 [US1] Change `backend/src/main/java/com/ballknowers/draftsim/engine/AbsenceCost.java`: `PlayerCost(playerId, weeksMissed, gamesMissed, pointsPerGame, estimatedPointsLost)`; counted weeks = distinct absence weeks ∩ `rosteredWeeks` minus `playedWeeksByPlayer[pid]`; `estimatedPointsLost = round2(weeksMissed × ppg)`; `weeksAffected` removed. Keep `isRegularContributor` and `MIN_STARTED_WEEKS` untouched. Add a dated note to the class comment: the 2026-09-23 per-game rule is **superseded 2026-10-20 by spec 040** at Allan's request (keep the old rationale quoted, marked superseded).
- [X] T008 [US1] Update `AbsenceDetail` in `SSS` to `(playerId, playerName, position, rosterId, weeksMissed, gamesMissed, pointsPerGame, estimatedPointsLost, estimated)` and `absenceWinners` to fill it; controller `detailRow` emits the same fields (mutable map); `web/src/api.ts` ABSENCE detail type mirrors it (drop `weeksAffected`, add `weeksMissed`). Fix the stale `weeksAffected` doc comment in api.ts (~line 2411).
- [X] T009 [P] [US1] Update `backend/src/test/java/com/ballknowers/draftsim/engine/SeasonSuperlativesAbsenceWiringTest.java`, `SeasonSuperlativesAbsenceCoverageTest.java`, and `backend/src/test/java/com/ballknowers/draftsim/api/SuperlativesStandingsIT.java`: re-derive the Embiid expectations from the fixture rows under the new rule (plan-review F11 — fix the "10 ppg" fixture comment if wrong, never just paste the new output).
- [X] T010 [US1] Frontend copy in `web/src/pages/Superlatives.tsx` (`holderDetailLines` / `detailLine` for ABSENCE): "Name — N weeks missed · X pts/game · Y pts lost (sat M games)", singular/plural correct; the Embiid "How this works"/note text states the rule in one sentence including that it's an estimate and that a basketball week he played any game in costs nothing. Update `web/src/pages/Superlatives.test.tsx` fixtures/assertions for the new fields.

**Checkpoint**: US1 complete and demonstrable alone.

## Phase 4: User Story 2 — Commissioner's list in the Unethical card (P1)

**Goal**: name picker, week range, in-place edit, per-entry charges, edit the URL's own season.
**Independent test**: quickstart scenarios 5–8.

### Backend

- [X] T011 [P] [US2] `backend/src/main/java/com/ballknowers/draftsim/store/LeagueConductRepository.java`: `Entry` gains `Integer appliesThroughWeek` (nullable); every select returns it; `upsert` takes it and its `do update set` also sets `applies_through_week = excluded.applies_through_week` (F10); new `Optional<Entry> update(long leagueId, long entryId, String reason, int fromWeek, Integer throughWeek)` pinned by `league_id` (never touches `added_by_manager_id`). Fix the ~8 test constructor sites of `Entry` (grep).
- [X] T012 [US2] Write failing tests in `backend/src/test/java/com/ballknowers/draftsim/engine/UnethicalAwardTest.java`: (a) an entry from 5 through 7 only qualifies rostered weeks 5–7; (b) null through-week = rest of window (today's behaviour); (c) one player with SUSPENDED and COMMISSIONER on the same weeks counts each (player, week) once in the roster total (R7/F9); (d) preference ordering — roster with 3 distinct flagged player-weeks outranks one with 2 even if the latter has 4 source rows. Point the test's `totalWeeks` helper at the new production method from T013 instead of re-implementing the sum.
- [X] T013 [US2] In `SSS`: `computeUnethical` honours `appliesThroughWeek`; extract `static Map<Integer,Integer> unethicalTotals(Map<Integer, List<ConductQualifyingWeeks>>)` counting distinct (player, week) per roster, and use it in `unethicalSuperlative` (replacing the sum at ~line 1242).
- [X] T014 [US2] In `SSS`, add `public ConductCharges conductCharges(LeagueRepository.LeagueRow league)` returning, per conduct entry id, the rosters charged with their weeks (scored regular-season window only, same membership rule as the award) and `playerWeeks`, plus per entry the weeks Sleeper also flagged (for the "also flagged by Sleeper in weeks …" note, F9). Reuse the existing parsed-weeks loader; no per-entry queries.
- [X] T015 [P] [US2] `backend/src/main/java/com/ballknowers/draftsim/store/RosterSeasonRepository.java`: method returning, for one league row, each rostered player id → current roster id from `roster_season.players` (null-safe when `players` is null), plus a fallback from the latest scored week's `roster_week_points.players_points` keys (F4b). Candidates = union of both sources plus every `players_points` key in the season.
- [X] T016 [US2] `SuperlativesController.java`: GET `/conduct-list` adds `season`, per-entry `appliesThroughWeek`, `position`, `proTeam`, `charges` (`team` via `holderRow`, `weeks`), `playerWeeks`, `alsoFlaggedBySleeperWeeks`, and `candidates` (`playerId, playerName, position, proTeam, fantasyTeam` holder or null) — all mutable maps (nullable fields). POST accepts optional `appliesThroughWeek`. New `PUT /leagues/{sleeperId}/conduct-list/{entryId}` body `{reason, appliesFromWeek, appliesThroughWeek}`: same `visibleLeague` + `canCommission` 403 rule as POST, reason "1-140 chars, trimmed", `appliesFromWeek >= 1`, and **400 when `appliesThroughWeek != null && appliesThroughWeek < appliesFromWeek`, checked before the DB** (F10); 404 when the id isn't in this league row; POST and PUT return the plain entry row without `charges`.
- [X] T017 [US2] Tests in `backend/src/test/java/com/ballknowers/draftsim/api/SuperlativesControllerIT.java` (Postgres): PUT happy path keeps `addedBy`; PUT through<from → 400 not 500; PUT by non-commissioner → 403; PUT with another league's entry id → 404; POST re-add of a listed player overwrites `appliesThroughWeek`; GET returns candidates for a league whose `roster_season.players` is null (fallback) and `charges`/`playerWeeks` for a ranged entry. Keep `SuperlativesControllerConductListBatchTest` green (no per-entry N+1).

### Frontend

- [X] T018 [US2] `web/src/api.ts`: extend `ConductEntry` (`appliesThroughWeek: number | null`, `position`, `proTeam`, `charges`, `playerWeeks`, `alsoFlaggedBySleeperWeeks`), `ConductList` (`season`, `candidates`), `saveConductEntry` body (`appliesThroughWeek`), new `updateConductEntry(sleeperLeagueId, entryId, body)` (PUT, same error handling as `conductListResult`).
- [X] T019 [US2] New `web/src/components/CommissionerListPanel.tsx` (move `ConductListSection` out of `Superlatives.tsx`): props `leagueSleeperId` (the **URL's** id, R5), `cardsSeason`, `sport`, `onChanged`. Panel root stops click **and** keydown propagation (F3). Header "Commissioner's list · {season}"; when `list.season !== cardsSeason`, a note that the awards above show {cardsSeason} and this list counts once {season} has a scored week. `canEdit`/`commissionerKnown` come only from the conduct-list response (F5). Rows: avatar + player link, position/pro team, "charged to" team chips with weeks, week range ("wk 2–3" / "from wk 2"), player-weeks, reason, adder; Edit (inline form) and Remove with inline "Remove? Yes / No" confirm (no `window.confirm`). Add form: name search over `candidates` (case- and diacritic-insensitive, keyboard navigable listbox with accessible names), each option shows avatar/name/position/pro team/fantasy team; an already-listed player shows "on the list — edit" and jumps to that entry; zero candidates → "Rosters haven't loaded yet — refresh the league" and no id box (F4b). From/through week inputs (through optional, "rest of season" placeholder); inline 400 message. Keep `CommissionerHonourNote` for editors.
- [X] T020 [US2] `web/src/pages/Superlatives.tsx`: delete the bottom `ConductListSection`; UNETHICAL card gets a "Commissioner's list · N" button rendered **regardless of** `available`/empty/`openable` (F3), its own `stopPropagation`, `aria-expanded`/`aria-controls`, expanding `CommissionerListPanel` in place under the card; also render the button+panel in the `!data.available` branch, addressed by the URL id (F4a). `onChanged` refetches superlatives only when the panel's season equals the cards' season. Replace the old component comment's "never the URL's own id" with a dated note explaining the 2026-10-20 reversal (F5). Style in the page's existing CSS file following the tinted-pill/card conventions (no flat uniform rows).
- [X] T021 [US2] Tests in `web/src/components/CommissionerListPanel.test.tsx` and `web/src/pages/Superlatives.test.tsx`: **rewrite** (don't delete) the tests at ~`:1063` and ~`:1108` to assert the panel addresses the URL id and names its own season when it differs (F5); a click/Enter inside the panel leaves "See all"'s `aria-expanded` unchanged (F3); the button shows on an empty UNETHICAL card and on an unavailable payload; picker filters by name and saves with `{playerId, reason, appliesFromWeek, appliesThroughWeek}`; edit calls PUT; remove needs confirm; non-commissioner sees no inputs/buttons; zero-candidates message.

**Checkpoint**: US2 demonstrable alone (quickstart 5–8).

## Phase 5: User Story 3 — Every player behind an award (P2)

**Goal**: players view for Embiid, Waiver Wire Warrior, Unethical; Jabari uncapped.
**Independent test**: Σ contributions per team == standings value on every real league; UI toggle.

- [X] T022 [US3] Write failing tests in `backend/src/test/java/com/ballknowers/draftsim/engine/SeasonSuperlativesStandingsTotalsTest.java` (or a new `PlayerContributionsTest.java`): for each of the three kinds, per-team Σ `value` == that team's `standings` value (±0.01); competition ranks (1,1,3); ties broken by player name; zero-value rows excluded; a player on two teams appears once per team; `[]` for every other kind; Jabari `playerStandings` not capped at 10.
- [X] T023 [US3] In `SSS`: build `playerContributions` from the full per-roster maps — Embiid from `AbsenceCost.RosterCost.players()` (value `estimatedPointsLost`, note "N weeks missed · X pts/game"), Waiver from `WaiverPickupAttribution.RosterTotal.contributions()` (value = points, note "added wk N · waiver/free agent"), Unethical from `computeUnethical` output deduped per (player, week) (value = distinct weeks, note = sources + week span). Remove the `10` cap on `MostAddedPlayers.top(...)` (~line 838) for `playerStandings` (its eligibility filter, D/ST excluded, stays as is) while keeping `playerHolders` as tied-top only.
- [X] T024 [US3] IT assertion in `backend/src/test/java/com/ballknowers/draftsim/api/SuperlativesStandingsIT.java`: `playerContributions` non-empty for JOEL_EMBIID, WAIVER_WIRE_WARRIOR and UNETHICAL on the fixture league, and team holders carry `username` (guards F2's silent drop end to end).
- [X] T025 [US3] `web/src/components/SuperlativeStandings.tsx`: segmented Teams/Players control when `s.playerContributions.length > 0`; **opens on Players** for JOEL_EMBIID and WAIVER_WIRE_WARRIOR, Teams for UNETHICAL (F12). Players rows: rank, player link, position, team chip (avatar + team name), figure (points via the existing `figure` fn; player-weeks for UNETHICAL), note. Render in payload order, never re-sort. Fix the Jabari fold summary to "N more players" (F6). Clicks stay inside (existing stopPropagation).
- [X] T026 [US3] Tests in `web/src/components/SuperlativeStandings.test.tsx` (create if absent) and `web/src/pages/Superlatives.test.tsx`: default view per kind; toggle switches; payload order preserved; no toggle for kinds with empty contributions; Jabari fold label with two collapsed rank groups says "N more players" (F6).

## Phase 6: Polish & verification

- [X] T027 Run `cd backend && .\gradlew.bat cleanTest test` with Postgres up — 0 failures, **check the skipped count is 0** (ITs skip silently when the DB is down); `cd web && npx tsc -b && npm test && npm run build`.
- [X] T028 Bug-hunting code review → `specs/040-superlatives-commissioner-ux/code-review.md`; fix findings.
- [X] T029 Live verification per quickstart.md (all 9 scenarios, real browser for PUT/POST/DELETE) → `specs/040-superlatives-commissioner-ux/verification.md`: NBA 2025 and NBA 2024 per-roster before/after table + old vs new holder; NFL 2025 ×2 identical to baseline; SC-004 0 mismatches on every real league; delete any conduct entries created during verification.
- [X] T030 Docs: HANDOFF.md status line for spec 040; correct the project memory that says "don't re-propose week-level costing" (Allan reversed it 2026-10-20); note the spec 038 coordination point (F7) for whoever merges second.

## Dependencies

- Phase 1 → Phase 2 → {US1, US2, US3}. US1 needs only T001 (baseline) — it can start before Phase 2.
- US3's Embiid contributions (T023) depend on US1's `PlayerCost` (T007); Unethical contributions depend on T013.
- US2 and US1 touch different code regions of `SSS` but the same file: run them sequentially, or US2 backend after US1 lands.
- Within stories: tests first (T006, T012, T022), then implementation.

## Parallel opportunities

- T011 and T015 (different repositories) in parallel; T009 alongside T010 once T008 lands.
- Frontend T018–T021 can proceed against the contract while T014–T017 are built.

## Implementation strategy

MVP = US1 (the wrong number is the most user-visible bug). Then US2 (the NBA season started today, so the list is
needed now), then US3. Each story checkpoint is demonstrable on its own.
