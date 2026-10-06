---
description: "Task list for spec 018: NFL scoring check and draft grades"
---

# Tasks: NFL scoring check and draft grades

**Input**: `specs/018-draft-grades/`: plan.md (including "Amended after review
(2026-10-06)"), spec.md, research.md (R1–R11), data-model.md, contracts/api.md, quickstart.md
(V0–V8), plan-review.md (F1–F11, N1–N17). Generated **after** the adversarial review, so it
builds the amended design: positional baseline, counted-for-you on production's basis,
centred team value, and exclusive board views.

**Tests**: requested. The plan's build order is failing tests first (plan.md steps 1 and 3),
and this repo's bar adds live verification on top.

**Repo rules that apply to every task** (AGENTS.md):
- Code tasks run on Sonnet subagents. The parent session reads each diff before ticking it
  off.
- No migration in this spec. If a task seems to need one, stop and amend the plan.
- `web/src/api.ts` mirrors the Java records in the same change.
- No `Map.of` with nullable values.
- Never commit without asking. Work only in `.claude/worktrees/018-draft-grades`.

Paths: backend `backend/src/main/java/com/ballknowers/draftsim/` (abbreviated `B/`), backend
tests `backend/src/test/java/com/ballknowers/draftsim/` (`T/`), web `web/src/`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1 NFL scoring check, US2 how each pick played out, US3 counted for the
  drafting team, US4 steals and busts

---

## Phase 1: Setup

**Purpose**: a working DB, and the measurements the review couldn't re-run (N16).

- [X] T001 Get Postgres 5433 reachable again: on 2026-10-06 Docker Desktop failed with "Insufficient system resources", and `pgdata/` is a PG 17 cluster. Check with `export PGPASSWORD=draftsim PGCONNECT_TIMEOUT=5; timeout 20 psql -h 127.0.0.1 -p 5433 -U draftsim -d draftsim -w -c "select count(*) from league"`. Never touch the unrelated cluster on 5432. Record how it was brought up in `specs/018-draft-grades/verification.md`. Every DB-backed task below waits on this
- [X] T002 Run quickstart V0 and record each number in `specs/018-draft-grades/verification.md`:
  - `ScoredWeeks` final weeks for NBA 2025 (expect 21) and NFL 2026 (expect ≥ 3);
  - `adp_at_time` coverage per draft (R10), and the D/ST row count in `player_game`;
  - R1 parity for leagues 9466, 9465 and 3;
  - R6's 168/168 roster mapping for draft 231.

  Where a number differs from research.md, add a dated amended note there. Don't edit the measured value
- [X] T003 Check that `sport_week_stats` has a row for every final week of NBA 2024/2025 and NFL 2025/2026 (`select sport, season, array_agg(week order by week) from sport_week_stats group by 1,2`). Compare against each league's `ScoredWeeks` final weeks. Record any gap in verification.md. A gap means F7's intersection will drop those weeks, which is a data finding, not a grades bug
- [X] T004 Re-check that nothing in this branch needs a migration (`ls backend/src/main/resources/db/migration | sort -V | tail -1` should still be V27), and that `origin/main` hasn't moved files this plan edits (`git fetch && git diff HEAD origin/main --stat`)

---

## Phase 2: Foundational (blocking)

**Purpose**: config and the health flag that US2–US4 read. US1 doesn't depend on this phase.

- [X] T005 [P] Write `T/config/DraftGradePropertiesTest.java`:
  - a null `neighborsPerSide` binds (block absent), and `loaded()` is false;
  - 0 and 11 throw ("1 ≤ n ≤ 10");
  - 3 loads.

  Watch it fail (no class yet)
- [X] T006 Create `B/config/DraftGradeProperties.java` as a record bound to prefix `draftsim.draft-grades` with one field `Integer neighborsPerSide`. An absent block gives a **null field**, not a default (data-model "Config", N3). A present value is validated `1 ≤ n ≤ 10`, else `IllegalArgumentException`. Add `loaded()`. Javadoc says ARBITRARY, matching `B/config/GradeProperties.java`. Make T005 pass
- [X] T007 Register `DraftGradeProperties` in `@EnableConfigurationProperties` in `B/DraftSimApplication.java` (N3)
- [X] T008 Add the block to `config/weights.yml` under `draftsim:`, after `grades:`. Use `draft-grades: { neighbors-per-side: 3 }` with the comment from data-model "Config" (ARBITRARY, not fitted; same-position picks before and after, shifted inward at the ends)
- [X] T009 Add `"draftGradesLoaded", draftGrades.loaded()` to `B/api/HealthController.java:39` (boolean, so `Map.of` is safe). Update any health test that asserts the key set

**Checkpoint**: `./gradlew test --tests '*DraftGradeProperties*' --tests '*Health*'` is green, and startup succeeds with and without the block.

---

## Phase 3: User Story 1 - Prove NFL scoring before anything reads it (P1) 🎯 gate

**Goal**: `GameScoringService` itself reproduces Sleeper's credited NFL points (FR-001, SC-001).

**Independent test**: quickstart V1. The pure test passes on real rows and fails when one
multiplier changes. The IT reports 0 mismatches per league.

**⚠️ If any mismatch appears, stop. US2–US4 wait until it's explained and fixed.**

- [X] T010 [US1] Build the fixture `backend/src/test/resources/sleeper/nfl-scoring-parity-2025.json` from real DB rows of "(Foot) Ball Knowers" 2025 (league id 5):
  - the league's full `scoring_json`;
  - ~40 starter-weeks, each `{week, sleeperPlayerId, position, stats, credited}`, where `credited` is `players_points[pid]`. Cover QB, RB, WR, TE, K and DEF (team-code id), at least one 0-point inactive starter with no `player_game` row (`stats: null`, `credited: 0.0`), and a negative-scoring game if one exists.

  Keep the raw stat keys as stored. Note the export SQL in the test's javadoc
- [X] T011 [US1] Write `T/engine/NflScoringParityTest.java` (pure, can't skip): load the fixture. For each entry with stats, assert `new GameScoringService().score(scoring, stats) == credited` to 0.005. For `stats: null`, assert credited is 0.0. Add a second test that copies the scoring, halves `rec` (or the first receiving key present), and asserts at least one entry now mismatches (the mutation check from quickstart V1)
- [X] T012 [US1] Write `T/engine/NflScoringParityIT.java` (DB-backed, same IT base and skip convention as the other ITs). For every NFL league in the DB, read every starter-week (starters ≠ `"0"`, present in `players_points`) with its `player_game` rows for the same sport/season/week, score through `GameScoringService`, and count matched / mismatched / noGameRow. Assert mismatched = 0, and that every noGameRow starter was credited 0.0. Log the three counts per league. Use plain `JdbcClient` queries in the test. No production repository method is added for this
- [X] T013 [US1] Run quickstart V1 and record the counts per league and the **skipped count** (must be 0 for both classes) in `specs/018-draft-grades/verification.md`

**Checkpoint**: US1 done. Roadmap 2.1 is closed by Java, not SQL.

---

## Phase 4: User Story 2 - How each pick actually played out (P1) 🎯 MVP

**Goal**: `GET /api/drafts/{id}/grades` with production, positional value over slot, and
centred team grades, shown on the completed board (FR-002–FR-012).

**Independent test**: quickstart V3, V4, V5 and V7. NBA 2025 gives 168 picks with production.
NFL 2025's top 5 steals are no longer all QBs.

### Tests first (must fail before T017)

- [X] T014 [P] [US2] Write `T/engine/DraftGradesServiceTest.java` against the pure core's planned signature: `DraftGradesService.grade(Input)` returns `Result`, where `Input` holds picks (pickNo, round, slot, managerId, sleeperId, name, positions), games by player and week (already scored to points), counted weeks, the roster map, starter-weeks, matchup flags, `neighborsPerSide`, the teams and the sport basis. US2 cases (each one a test):
  - **SC-003 ordering**: 12 picks, same position. Pick 10 outscores picks 1–9 and becomes the top steal;
  - **F1 positional ordering**: a late QB scoring at QB-typical levels vs. a WR who outscored every WR drafted before him. The WR's `valueOverSlot` is greater than the QB's, and the QB isn't the top steal;
  - the pick itself is excluded from its own window;
  - **F9**: the window shifts inward at both ends and keeps 2n picks when the position has them; with fewer than 2n same-position picks it uses all the others; with fewer than 2 it gives a null baseline and a null value;
  - **N7**: even median = mean of the middle two (a 2-pick window);
  - **basis**: NBA averages a 3-game week ((a+b+c)/3), and NFL takes the one game;
  - weeks not in counted weeks are ignored;
  - **N11**: a player with empty positions gets `position` null, a null baseline, null positional ranks, and is counted in `unpositionedPicks`;
  - `positionDrafted`/`positionFinish` ordering, with ties → lower pickNo;
  - **F4**: team values are centred and Σ non-null `draftValue` ≈ 0;
  - **N9**: a team with no graded picks gets null `draftValue`, rank and grade;
  - tied teams share a rank and grade (`LetterGrades.ranksDescending(items, value, ranked)`, 3 args, N2);
  - `weeksPlayed ≤ weeksCounted` and `production ≥ 0`.
- [X] T015 [P] [US2] Write `T/api/DraftGradesControllerTest.java` with mocked collaborators, checking the data-model check order (N13):
  - absent config → `NOT_CONFIGURED`, `weeksCounted` 0, every list empty, `neighborsPerSide` null;
  - a `pre_draft` draft → `DRAFT_NOT_COMPLETE`;
  - complete but no counted weeks → `NO_SCORED_WEEKS`, with `weeksMissingGameData` filled when final weeks exist without `sport_week_stats` rows (**F7**);
  - a draft that `visibleDraft` hides → 404.
- [X] T016 [P] [US2] Add `/api/drafts/it-acl-draft/grades` to `T/api/AccessControlMvcIT.java` `draftRoutesAre404WithNoIdentity` (`:152`): no header, `X-Sleeper-User: "  "` and STRANGER → 404, MEMBER → 200 (the fixture draft is `pre_draft`, so the body is `DRAFT_NOT_COMPLETE`) (**F11**)

### Implementation

- [X] T017 [US2] Create `B/engine/DraftGradesService.java` with the **pure** static/instance core `grade(Input)` → `Result` per data-model "Counted weeks", "Production", "Position", "Value over slot", "Team", "Steals and busts":
  - production = Σ over counted weeks of the mean scored game;
  - position = `positions[0]` or **null, never `Player.primary()`**;
  - the window is "the n same-position picks drafted just before i and the n just after, shifted inward at an end to keep 2n";
  - the baseline is null when |window| < 2;
  - `draftValue` = raw − mean raw over teams with non-null raw;
  - the grade comes from the injected `LetterGrades` bean (instance `grade(rank, rankedTeamCount)`);
  - steals = top 5, and busts = bottom 5 excluding steals (N8).

  Records: `PickGrade`, `TeamGrade`, `DraftGrades` with exactly the contract C1 fields and nullability. Make T014 pass
- [X] T018 [US2] Add `read(DraftRepository.DraftRow draft)` to `DraftGradesService`, following the check order in data-model "States":
  - config;
  - `draft.status`;
  - league by `draft.leagueId()` via `LeagueRepository.byId`, **never `LeagueSeasonResolver`**;
  - `scoringOf(leagueId)`;
  - counted weeks = `ScoredWeeks.of(leagueId).finalWeeks()` ∩ weeks in `SportWeekStatsRepository.forSeason(sport, season)`, with the rest in `weeksMissingGameData`;
  - picks via `DraftRepository.picks`, and players via `PlayerRepository.findAll(sport)` keyed by `id()` (as `LeagueController.pickNaming` does at `:399-400`). A pick with a null player id or an unknown player is counted in `excludedPicks`;
  - games via the **existing** `PlayerGameRepository.forPlayers(sport, season, sleeperIds)` (`:171`), filtered to counted weeks in Java (**F6**, as `SeasonSuperlativesService.boundedPlayerGames:1104` does), scored by `GameScoringService.score(scoring, stats)`;
  - basis from `SportRulesRegistry`'s `playsMultipleGamesPerScoringPeriod()`;
  - `gradesEarly = SeasonWindow.isEarly(weeksCounted)`, and `earlyThresholdWeeks = SeasonWindow.EARLY_THRESHOLD_WEEKS`.

  Leave the US3 inputs (rosters, starters, matchups) empty or null-producing until T024
- [X] T019 [US2] Create `B/api/DraftGradesController.java`: `GET /api/drafts/{sleeperDraftId}/grades` with `@RequestHeader(value = "X-Sleeper-User", required = false)`, and `membership.visibleDraft(sleeperUserId, sleeperDraftId)` → 404 when empty, exactly as `LeagueController.realBoard:299-302`. It returns the `DraftGrades` record. Make T015 and T016 pass
- [X] T020 [US2] Write `T/engine/DraftGradesReadIT.java` (DB-backed). Against NBA 2025 draft `1229352720230514688` when present (skip with a message otherwise), assert contract invariants 1–7 and 9, `weeksCounted` 21 and `productionBasis` `WEEKLY_AVERAGE_GAME`. Against NFL 2025 `1254190894563729408`, assert `WEEKLY_GAME`, and that fewer than 3 of the top 5 steals are QBs (F1). Read values back. Counting them isn't enough
- [X] T021 [P] [US2] Add the types `ProductionBasis`, `DraftGradesReason`, `PickGrade`, `TeamGrade`, `DraftGrades` and `getDraftGrades(draftId)` to `web/src/api.ts`, exactly as contracts/api.md "TypeScript mirror" (field-for-field with T017's records; `sport: Sport` from `api.ts:53`). Use the same fetch helper and headers as `getRealDraftBoard`
- [X] T022 [P] [US2] Create `web/src/draftGrades.ts` + `web/src/draftGrades.test.ts`:
  - `reasonSentence(reason)`: "This draft isn't finished", "No weeks have been scored yet", and a neutral NOT_CONFIGURED line. The test asserts no sentence contains `ingest` or `/api/` (N4);
  - `productionLabel(basis)`: NBA "season points, counting each week's average game", NFL "season points" (F5);
  - `legendText(grades)`: the basis sentence. For NBA, "In the basketball leagues measured so far, Sleeper credited one game per starter per week." Then `weeksCounted`, or the explicit week list when `countedWeeks` isn't contiguous (N6), then any `weeksMissingGameData`;
  - `valueTint(value, allValues)`: its own scale, |value| / the 90th percentile of |values|, capped. **Not** `tintPercent` (F8). Test that not every cell saturates on the NBA-like spread.
- [X] T023 [US2] Update `web/src/pages/CompletedDraftBoard.tsx` + `.test.tsx`:
  - replace the single "Steals & reaches" chip with an exclusive control. When the draft has ADP it's a segmented "Off / Steals & reaches / How it played out". Without ADP it's a single "How it played out" chip. It's shown only when the board's `status` is `complete` (F8);
  - the first selection of "How it played out" calls `getDraftGrades` once and keeps the result;
  - unavailable shows `reasonSentence`;
  - pass the grades to `DraftBoard` and the PlayerCard;
  - render the legend, plus `GradesEarlyBadge` with `gradesEarlySentence(earlyThresholdWeeks)` when `gradesEarly`;
  - Vitest: no grades control for an incomplete draft; switching views never shows both deltas; the NBA legend wording; the unavailable sentence.

  Plus `web/src/components/DraftBoard.tsx`: when a grades map is passed, the cell shows signed `valueOverSlot` with `valueTint`, in place of (never beside) the ADP delta
- [X] T024 [US2] Create `web/src/components/DraftGradeStrip.tsx` with its own `.draft-grade-strip` CSS (**not** `TeamStrip`/`.team-strip`, which already exist, N10). It has one item per slot: avatar + manager, `draftValue` signed with "vs. the average team in this draft", `GradeChip` wrapping the number, and best/worst pick names. At 375px it scrolls horizontally **inside itself**, with no page-level scroll (FR-012). A null `draftValue` shows "—" and no grade. Mount it in `CompletedDraftBoard` above the board when grades are on. Add a Vitest
- [X] T025 [US2] Add a labelled "How he played out" section to `web/src/components/PlayerCard.tsx` for a completed pick when grades are present. Each line has its label:
  - production (`productionLabel`), weeks played of `weeksCounted`;
  - baseline as "vs. the {2n} {position}s drafted around him";
  - value over slot;
  - positional ranks as "{positionDrafted}th {position} drafted, finished {positionFinish}th".

  Null values print "unknown", never 0. Add a Vitest
- [X] T026 [US2] Run quickstart V3, V4, V5 and V7 (1–3, 5, 6) against **this worktree's** backend: check `preview_logs` that the bootRun classpath is the worktree (memory: a worktree preview can serve main). Record the NBA 2025 and NFL 2025 top-5 steals with positions, the picks with `weeksPlayed: 0` by name (N17), the picks-1–6 mean vs. the middle (F9), and the request time (SC-005) in verification.md. Take screenshots for V7.2 and V7.5

**Checkpoint**: US2 shippable alone (MVP).

---

### Rework after build measurement (2026-10-06, Allan: per-position log fit)

- [X] T026a [US2] Replace the positional-neighbours baseline with the per-position log fit (data-model "Value over slot: per-position log fit") in `B/engine/DraftGradesService.java`. Rename the config `neighbors-per-side` → `min-picks-per-position: 8` (validated 3 ≤ m ≤ 30) in `B/config/DraftGradeProperties.java`, `config/weights.yml` and the tests, and `neighborsPerSide` → `minPicksPerPosition` in the records. Replace the window tests in `T/engine/DraftGradesServiceTest.java` with fit tests: (1) production exactly `a + b·ln(pick)` gives every value 0; (2) values sum to 0 within a fitted position; (3) a position below the minimum gets null baselines; (4) SC-003 and the F1 positional ordering still pass. Re-run `DraftGradesReadIT` and report NBA/NFL 2025 steals, busts and the edge means (picks 1–6, 1–12, last 12)
- [X] T026b [US2] Frontend: `minPicksPerPosition` in `web/src/api.ts` and the fixtures. The PlayerCard baseline line becomes "vs. what a {position} taken at pick {pickNo} scored in this draft (fitted)"

## Phase 5: User Story 3 - What a pick did for the team that drafted him (P2)

**Goal**: `countedForYou`, `creditedForYou`, `weeksStartedForYou`, `weeksUnknownForYou`
and `unmappedPicks` (FR-005, SC-004).

**Independent test**: quickstart V6. `countedForYou ≤ production` everywhere, a bye week
doesn't count, and a dropped player is far lower.

- [X] T027 [P] [US3] Extend `T/engine/DraftGradesServiceTest.java` with US3 cases:
  - **F2**: `countedForYou ≤ production` for every pick in a fixture where credited > average;
  - a loyal starter's countedForYou ≈ production, and a dropped player's is far lower;
  - `creditedForYou` sums `players_points`;
  - **F3**: a playoff week with `matchupId` null doesn't count;
  - **F10**: null starters, `"{}"` points or a missing matchup row → `weeksUnknownForYou` += 1, never 0 points;
  - **N1**: a manager with zero or two roster rows → all four fields null and `unmappedPicks` += 1;
  - `weeksStartedForYou + weeksUnknownForYou ≤ weeksCounted`.
- [X] T028 [US3] Implement the US3 part of the core in `B/engine/DraftGradesService.java` per data-model "Counted for the drafting team". In `read`, build the roster map from `RosterSeasonRepository.forLeague(leagueId)`, mapping a manager with ≠ 1 row to null. Read starters and points from the existing `RosterWeekPointsRepository.breakdownsFor(leagueId, season)` and parse its JSON (respect its javadoc at `:113-117`: null or `{}` = no answer). Read matchups from `LeagueMatchupRepository.between(leagueId, season, min(counted), max(counted))`. Make T027 pass, and re-run T020 with invariants 4 and 8 added
- [X] T029 [US3] Add the counted-for-you lines to the PlayerCard section (T025):
  - "Counted for {team}: {countedForYou}, {weeksStartedForYou} weeks started";
  - for NBA only, "Credited by Sleeper: {creditedForYou}", marked "a different scale: Sleeper counts one game a week";
  - "{n} weeks unknown" when `weeksUnknownForYou > 0`.

  Null → "unknown". Add a Vitest
- [X] T030 [US3] Run quickstart V6 and record it in verification.md: the invariant over every pick of NBA 2025 and NFL 2026, one hand-checked dropped player (SQL), and one bye-week starter (NBA 2025 weeks 19/21)

---

## Phase 6: User Story 4 - Steals and busts of the draft (P3)

**Goal**: league-wide top 5 / bottom 5 by value over slot, shown with round and team.

**Independent test**: the NBA 2025 lists match a hand query of the same positional rule.

- [X] T031 [P] [US4] Extend `T/engine/DraftGradesServiceTest.java`: steals and busts hold ≤ 5 each and are disjoint in a 6-pick draft (N8), ties → lower pickNo, and null values are excluded
- [X] T032 [US4] Render a "Steals & busts" panel in `web/src/pages/CompletedDraftBoard.tsx` when grades are on: player, round, pick, drafting team and signed value, using the board's existing pick data for names. Add a Vitest
- [X] T033 [US4] Check the NBA 2025 steals/busts against a hand SQL implementation of the positional rule (n = 3, shifted inward). Record both lists in verification.md. Explain any difference before calling it a pass

---

## Phase 7: Polish & cross-cutting

- [X] T034 Run the full regression (quickstart V8): `cd backend && ./gradlew test` (report total **and skipped**), and `cd web && npx tsc -b && npm run build && npx vitest run`. `LeagueControllerRealBoardTest` must be unchanged
- [X] T035 Update `HANDOFF.md` and the roadmap status line in `claude/competitor-gap-roadmap.md` (2.1 closed by Java, 2.2 built, local or deployed). If the build overturned anything in research.md or `claude/draft-grades.md`, add a dated amended note there
- [X] T036 Bug-hunting code review as a **separate pass** (not the build agent), written to `specs/018-draft-grades/code-review.md`. Fix confirmed findings via Sonnet subagents
### Fixes from code review (2026-10-06)

- [X] T036a Backend: B1 `SportRules.draftGradeGroup(Player)` (no default; football `positions[0]` or null, basketball `"ALL"`) used for the fit group and positional ranks in `B/engine/DraftGradesService.java`; basketball `position` = eligibility joined with "/". B3 counted weeks require `sport_week_stats.fin`. B4 positional-finish ties on 2-dp rounded values then lower pick. Tests for each in `T/engine/DraftGradesServiceTest.java` and the sport rules tests
- [X] T036b Web: B2 `NO_SCORED_WEEKS` with `weeksMissingGameData` non-empty says the weeks are scored but their game data isn't loaded yet, naming them. Basketball positional-rank wording "drafted Nth, finished Mth among this draft's picks". Pluralise "1 week started". Cell hover uses the fitted wording. Football rank reads "…finished Mth among drafted {pos}s". Retry is a button. Remove unused `.grades-controls`

- [X] T037 Write `specs/018-draft-grades/verification.md` in full: each of V0–V8 marked **run** (with output) or **not run** (why)
- [ ] T038 Ask Allan before committing. Then PR and deploy **both** Railway services, and repeat V3 and V7.2 against production

---

## Dependencies & execution order

### Phases

- Phase 1 (T001–T004) first. T001 blocks every DB task (T002, T003, T010, T012, T013, T020, T026, T030, T033).
- US1 (T010–T013) depends only on T001. **It gates US2–US4**: no grades work merges while a mismatch is unexplained.
- Phase 2 (T005–T009) can run beside US1.
- US2 (T014–T026) needs Phase 2 and US1.
- US3 (T027–T030) needs T017–T019 (the core and the read path) and T025 (the PlayerCard section).
- US4 (T031–T033) needs T017.
- Polish last.

### Within stories

Tests before implementation (T014–T016 before T017–T019; T027 before T028; T031 before
T032). T021 (types) before T022–T025. T017 before T018 before T019.

### Story independence

US2 ships alone (MVP). US3 and US4 add fields and panels without changing US2's numbers.
Production, the baseline and team value don't read roster data.

### Parallel examples

```text
# After T001:
T002, T003, T004 (setup checks)   ‖   T005 (properties test)   ‖   T010 (fixture)

# US2 tests together:
T014 (service test)   ‖   T015 (controller test)   ‖   T016 (access IT)

# US2 web, once T019 and T021 land:
T022 (draftGrades.ts)   ‖   T024 (DraftGradeStrip)   ‖   T025 (PlayerCard section)

# US3/US4 tests:
T027   ‖   T031
```

## Implementation strategy

1. **Gate first**: T001, then US1 (T010–T013). If it fails, stop and report.
2. **MVP**: Phase 2 + US2. That means positional value over slot and centred team grades on
   the board. Verify live (T026).
3. **Then** US3 (counted for you), then US4 (steals & busts), each verified on its own.
4. Polish: full regression, a separate bug-hunt review, verification.md, then ask before
   committing and deploying.
