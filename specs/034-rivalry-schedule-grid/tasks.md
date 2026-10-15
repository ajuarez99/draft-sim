---
description: "Task list for spec 034: rivalry schedule grid and rivalry week recommendation"
---

# Tasks: Rivalry schedule grid and rivalry week recommendation

**Input**: `specs/034-rivalry-schedule-grid/`: plan.md (incl. "Amended after review", F1–F19,
which **overrides** research/data-model/contracts where they conflict), spec.md, research.md
(R1–R11), data-model.md, contracts/api.md, quickstart.md, plan-review.md.

**Tests**: required. Write the failing tests before the code they cover, especially the
preference-ordering tests (AGENTS.md bug class 1).

**Repo rules that apply to every task** (AGENTS.md):
- Code tasks run on Sonnet subagents. The parent reads the diff.
- Migrations are append-only. This spec's migration is **V33** (not V32, see F1).
- `web/src/api.ts` mirrors the Java records in the same change.
- No `Map.of` with nullable values.
- Never commit without asking.
- Work only in `.claude/worktrees/034-rivalry-schedule-grid`.

Paths: `B/` = `backend/src/main/java/com/ballknowers/draftsim/`,
`T/` = `backend/src/test/java/com/ballknowers/draftsim/`, `W/` = `web/src/`.

## Format: `[ID] [P?] [Story] Description`

**[Story]**: Found = foundation, US1 matrix, US2 season grid, US3 recommendation.

---

## Phase 1: Foundation: bracket storage and ingest (blocks everything)

- [x] T001 [Found] `backend/src/main/resources/db/migration/V33__league_bracket_match.sql`.
  - Table `league_bracket_match` per data-model.
  - FK `league(id) on delete cascade`.
  - PK `(league_id, match_id)`.
- [x] T002 [Found] `B/store/LeagueBracketRepository.java`.
  - `replaceForLeague(leagueId, rows)`: a no-op when rows is empty (F5), otherwise
    delete+insert in one transaction.
  - `forLeagues(ids)`.
  - `isComplete(leagueId)`: has rows and every row has a winner.
  - `count(leagueId)`.
- [x] T003 [Found] `T/store/LeagueBracketRepositoryIT.java`.
  - Replace semantics.
  - Empty input keeps the existing rows.
  - Nullable slots round-trip.
  - Cascade on league delete.
- [x] T004 [Found] `B/ingest/BracketIngestService.java`.
  - `ingestSeason(leagueId, sleeperLeagueId, force)`: calls `SleeperClient.winnersBracket`.
  - A null or empty body is "no data".
  - Skips when `isComplete` and not `force`.
  - Maps `m, r, t1, t2, w, l, p`. `t1`/`t2` may be absent while `*_from` is set.
- [x] T005 [Found] `B/ingest/LeagueHistoryIngestService.ingestChain`.
  - Call `BracketIngestService` **after** `ingestWeeklyPoints` for non-skipped seasons (F2).
  - For a **skipped** season with `count == 0`, call it once (F6). Nothing else for that
    season.
  - A bracket exception propagates (F6).
  - Unit test with mocked SleeperClient: a skipped season makes no `matchups` call.
- [x] T006 [Found] `B/api/IngestController.java`: `POST /api/ingest/brackets/{sleeperLeagueId}`
  (C2, optional `force`). It walks `LeagueRepository.chainBySleeperId` locally and returns
  `{seasons:[{season, sleeperLeagueId, matches, skipped}]}`.
- [x] T007 [Found] Extract the H2H outcome rule. Make a package-visible static in
  `B/engine/HeadToHeadService.java`, e.g. `static String outcome(double a, double b)`, and
  use it there. `HeadToHeadServiceTest` stays unchanged and green.

## Phase 2: Pure engine (no Spring, no DB)

- [x] T008 [US3] `B/config/RivalryProperties.java`.
  - Binds `draftsim.rivalry`: `minMeetings`, `playoffBonus`, `championshipBonus`,
    `maxManagers`.
  - A null field means not loaded. `loaded()` needs **all** fields (F17).
  - Range checks per data-model.
  - Register it like `DraftGradeProperties`.
  - Add the block to `config/weights.yml` with values 3 / 1.5 / 2.5 / 16 and an ARBITRARY
    comment citing research R5/R6.
- [x] T009 [US3] `T/engine/RivalryMatchingTest.java`, written first:
  - 5-5 beats 1-0.
  - 4-4 beats 7-1.
  - A final beats an otherwise equal pair.
  - 0 meetings is the minimum.
  - A single 1-0 final (4.5) beats 2-2 (4.0) (F14).
  - The matching is optimal (A–C + B–D beats A–B + C–D when it scores higher).
  - Odd n leaves exactly one manager out.
  - Determinism: same output twice, and the all-zero tie-break is pinned (F12).
  - n > max refuses.
- [x] T010 [US3] `B/engine/RivalryMatching.java`.
  - `score(PairStats, props)`.
  - `match(List<Long> managerIds, ScoreFn)` per the F12 DP spec.
  - Returns the pairs plus the unpaired id, with that manager's best available score for the
    honest reason.
- [x] T011 [US1] `B/engine/RivalryGameKind.java` (or nested in the service).
  - Classifies REGULAR / PLAYOFF / PLACEMENT / CONSOLATION / POSTSEASON_UNSPLIT using
    round↔week mapping per round type (F2, F4). Other round types → UNSPLIT.
  - A null slot in the week's round → UNSPLIT (`BRACKET_PENDING`).
  - Unit tests:
    - Foot 2025 shape: 7 PLAYOFF/PLACEMENT and 7 CONSOLATION.
    - A stale bracket → UNSPLIT.
    - West Coast round type 1: both final legs are PLAYOFF.
    - Unknown round type → UNSPLIT.

## Phase 3: Service and API

- [x] T012 [US1] `B/engine/RivalryService.java` matrix.
  - Reads the URL's league row (never `LeagueSeasonResolver`, F9).
  - Chain via `chainBySleeperId`.
  - `pairedWithScores(List.of(id), ALL_WEEKS)` **per league** (F15), with the league attached.
  - Drops null managers and self-pairs.
  - Builds `PairRecord`s for current-season managers. `byKind` sums to the totals.
  - Status MET / NEVER_MET / NOT_YET_MET (F11) / NO_SHARED_HISTORY.
  - `finals[]` per bracket match, with summed legs and the bracket winner (F3).
  - `MatrixRow.allOpponents` / `formerOpponentMeetings`.
  - Team names from this season, falling back to the username (same source as
    `LeagueHistoryController.teamNamesByManager`; reuse it, don't copy it).
  - `isMe` via `managers.idsBySleeperUserId`.
- [x] T013 [US2] Same service, season grid.
  - `scheduledPairs` + scored results.
  - `priorRecord` comes from a single chronological pass, counting only meetings strictly
    before `(season, week)`.
  - BYE / NO_GAME / TBD / OPPONENT, with `source: SCHEDULE|BRACKET` (F7, F8).
  - Weeks run 1 through the max of the stored weeks and `lastPlayoffWeek()` when present.
    Otherwise use the stored weeks only, plus `playoffWindowNote`.
  - `currentWeek` (F9). `scheduleStatus NOT_PUBLISHED` when there are no pairs.
  - The median-game note (F17).
- [x] T014 [US3] Same service, recommendation.
  - Status OK / NO_HISTORY (no season before the requested one, F10) / TOO_MANY_MANAGERS /
    TOO_FEW_MANAGERS / CONFIG_MISSING.
  - A reason code by the R7 priority, with F16 tweaks (MOST_MEETINGS only when unique,
    "even", a streak breaks on a tie). Text and numbers come from the same values.
  - Placement games are worded as such (F4).
  - `thin`, `thinCount`, `note` (FR-012).
- [x] T015 [US1-3] `T/engine/RivalryServiceTest.java` (mocked repositories):
  - mirror symmetry
  - prior-record cut
  - NOT_YET_MET
  - NO_HISTORY
  - the West Coast two-week final (legs 1-1, winner 6, 269.04–254.52)
  - eliminated teams NO_GAME, and a round-1 bye BYE
  - a newest season with no scores
  - null manager / self-pair dropped
  - reason numbers equal the matrix
- [x] T016 [US1] `T/engine/RivalryParityIT.java` (Postgres).
  - Synthetic two-NFL-chain fixture plus one NBA chain.
  - Every cell equals `HeadToHeadService.compute(a,b)` meetings filtered to the chain's
    `sleeperLeagueId`s and recounted.
  - `byKind` sums to the total (SC-001, SC-002).
- [x] T017 [US1-3] `B/api/RivalryController.java`: `GET /api/leagues/{sleeperId}/rivalries`,
  404 unless `membership.visibleLeague`. Records serialize as-is. Contract test for the 404
  and the shape.

## Phase 4: Frontend

- [x] T018 [US1-3] `W/api.ts`: types mirroring every record field for field, plus
  `fetchRivalries(sleeperLeagueId)` following the existing fetch helpers (X-Sleeper-User
  header).
- [x] T019 [US1-3] `W/destinations.ts` (+ test).
  - Add `'rivalries'` to `DestinationKey`.
  - The row: group `history`, sports both, label `Rivalries`, a one-season href with a
    comment on why it differs from History (F19), match regex.
  - `W/App.tsx`: the route.
- [x] T020 [US1-3] `W/pages/Rivalries.tsx` (+ `Rivalries.test.tsx`).
  - Three panels: matrix, season grid, rivalry week.
  - Each table is in its own horizontal scroll container with a sticky first column (F19).
  - Cells print the exact record and the meeting count. One encoding per mark (memory: label
    the axis).
  - Matrix cells link to `/managers/:a/versus/:b`, with an "all leagues" hint when needed
    (F13).
  - Highlight `currentWeek` and your own row.
  - Thin pairs are labelled, N is printed "(hand-set)", the Sleeper note shows, and a
    headline appears when `thinCount` > half.
  - Empty and refusal states are printed in words.
  - Match the existing card/avatar dark style (`Avatar`, `PageHeader`, panel classes).
  - Tests: thin label, NOT_YET_MET text, NOT_PUBLISHED text, and the mirror lookup.

## Phase 5: Verification

- [x] T021 Backend suite: `./gradlew test` on the **scratch DB** (`DB_URL=…/draftsim034`).
  Report the skip count.
- [x] T022 Web: `npx tsc -b && npm run build && npx vitest run`.
- [x] T023 Bug-hunting code review → `code-review.md`. Fix the findings.
- [x] T024 Live verification per quickstart §1–§5, on the scratch DB with a worktree-built
  server on a spare port. Write it up in `verification.md`.
