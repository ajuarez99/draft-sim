---

description: "Task list for 016: stop saying basketball has no projection source"
---

# Tasks: Stop saying basketball has no projection source

**Input**: Design documents from `specs/016-honest-nba-projection-message/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/messages.md](contracts/messages.md), [quickstart.md](quickstart.md)

**Tests**: REQUIRED. SC-001 asks for tests that fail on today's code. Write
them first (T002–T004) and watch them fail before changing any string.

**Organization**: the spec has one user story (US1, P1). Comment and doc
corrections (FR-004, FR-005) sit in US1, since they're part of the same claim
being withdrawn. Verification is in the Polish phase.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 for user-story tasks; none for Setup/Polish

## Path Conventions

Web app: `backend/src/main/java/com/ballknowers/draftsim/…`, `backend/src/test/java/com/ballknowers/draftsim/…`, `web/src/…`. Docs in `claude/` and `specs/`.

---

## Phase 1: Setup

- [X] T001 Confirm the starting state before touching anything. Run `git branch --show-current` (must be `016-honest-nba-projection-message`), `git status --short` (only `specs/016-honest-nba-projection-message/` untracked) and `git log --oneline -1 origin/main` (expect `bee3645` or later; if main moved, `git rebase origin/main` and re-check the line numbers below). Then from `backend/` run `./gradlew test --tests '*LeagueAnalysisServiceTest' --tests '*IngestControllerTest' --tests '*NoIngestHintsInMessagesTest'` and record that it's green, so later failures belong to this feature.

---

## Phase 2: Foundational

None. No shared infrastructure, migration or type change (data-model.md).

---

## Phase 3: User Story 1 - A basketball reader is told the truth about projections (Priority: P1) 🎯 MVP

**Goal**: both runtime messages (contracts M1, M2) stop claiming basketball has no projection source. Code comments and docs that state the premise are corrected or amended. No behavior changes.

**Independent Test**: `GET /api/leagues/1339351318115946496/analysis` returns `projections.reason` equal to M1's "After" text, and `POST /api/ingest/projections?sport=nba…` returns 400 with M2's "After" text (quickstart V3, V5).

### Tests for User Story 1 (write first, must FAIL before T005/T006)

- [X] T002 [P] [US1] In `backend/src/test/java/com/ballknowers/draftsim/engine/LeagueAnalysisServiceTest.java`, add `import com.ballknowers.draftsim.domain.Sport;` and a test `basketballIsToldProjectionsArentBuiltYetNotThatNoSourceExists()`. It calls `LeagueAnalysisService.projectionsNotBuiltReason(Sport.NBA)` and asserts the result equals exactly `"Roster projections aren't built for nba leagues yet: so far this app only has football projections."`, contains `"aren't built"` and `"nba"`, and does **not** contain `"equivalent"`, `"/api/ingest"` or `"POST /api/"`. Javadoc it: Sleeper serves per-game NBA projections (measured 2026-10-05, research R0), so the old "no nba equivalent" text was false. The method doesn't exist yet, so this fails to compile. That counts as the expected failure.
- [X] T003 [P] [US1] In `backend/src/test/java/com/ballknowers/draftsim/api/IngestControllerTest.java`, add `import static org.mockito.Mockito.verifyNoInteractions;` and a test `projectionsWithNbaAreStillRefusedButNotForWantOfASource()`. It does `IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> controller().projections("nba", 2026, 1, 1, false));`, then asserts `e.getMessage()` equals exactly M2's "After" text from `specs/016-honest-nba-projection-message/contracts/messages.md` and does not contain `"equivalent"`, `"/api/ingest"` or `"POST /api/"`. Finally `verifyNoInteractions(projectionIngest)` pins the refusal (FR-002, FR-006). Add it under a `// ---- POST /api/ingest/projections ----` divider, matching the file's existing section dividers.
- [X] T004 [US1] From `backend/`, run `./gradlew test --tests '*LeagueAnalysisServiceTest' --tests '*IngestControllerTest'`. Record that T002 fails (compile error: no `projectionsNotBuiltReason`) and T003 fails (message mismatch). That's SC-001's "fails on today's code". Don't continue until both failures are observed.

### Implementation for User Story 1

- [X] T005 [US1] In `backend/src/main/java/com/ballknowers/draftsim/engine/LeagueAnalysisService.java`, add a package-private static method `static String projectionsNotBuiltReason(Sport sport)` returning `"Roster projections aren't built for " + sport.code() + " leagues yet: so far this app only has football projections."`. Javadoc: why it doesn't claim a source does or doesn't exist (research R1: true for NBA, unmeasured for any other sport, and the claim would go stale when Phase 3 lands), plus a pointer to `claude/projection-tools.md`. In `projectionBlocks` (currently ~`:441-451`), replace the inline string in the `league.sport() != Sport.NFL` branch with `projectionsNotBuiltReason(league.sport())`. Keep the branch, its position (after the playoff-week and season-over guards, before the cache read) and the `unavailable(...)` call unchanged. Update the comment above it from "for a sport with no projection source the cache will ALWAYS be empty" to "for a sport whose projections aren't ingested the cache will ALWAYS be empty". Its reasoning (don't advise an ingest call that answers 400) stays.
- [X] T006 [US1] In `backend/src/main/java/com/ballknowers/draftsim/api/IngestController.java` (~`:142-147`), replace the `IllegalArgumentException` message with exactly: `"projections are football-only for now: the stored columns are Sleeper's pts_ppr / pts_half_ppr / pts_std, which are football scoring totals. Basketball projections need per-game stat lines scored with each league's settings, which isn't built yet (claude/projection-tools.md)."`. Keep the `if (s != Sport.NFL)` check and the throw type.
- [X] T007 [US1] From `backend/`, run `./gradlew test --tests '*LeagueAnalysisServiceTest' --tests '*IngestControllerTest' --tests '*NoIngestHintsInMessagesTest'`. All must pass (quickstart V1). If `NoIngestHintsInMessagesTest` fails, the new text contains an ingest path. Fix the text, not the scanner.
- [X] T008 [P] [US1] In `web/src/destinations.ts` (the `key: 'analysis'` entry, ~`:177-181`), rewrite only the comment. Keep its first clause ("Football-only on its own terms rather than by a shared sport gate: two of this page's three blocks are rest-of-season projections,"), then: "and basketball projections aren't wired up yet. Sleeper does serve them (claude/projection-tools.md), but only football's are ingested. A basketball league reaching it would get one working block and two explaining themselves. See claude/league-analysis.md." `sports: ['nfl']` and everything else must not change (FR-006).
- [X] T009 [P] [US1] In `backend/src/test/java/com/ballknowers/draftsim/sport/FootballRulesTest.java` (~`:268-270`), change "It was justified on the grounds that basketball has no projection source -- true, and still true -- but" to "It was justified on the grounds that basketball has no projection source -- believed true then, and wrong: Sleeper serves per-game NBA projections, measured 2026-10-05 (claude/projection-tools.md). Either way," and keep the rest of the comment intact. Comment-only, no code change.
- [X] T010 [US1] From the repo root, run quickstart V2: `grep -rniE "(basketball|nba) equivalent|\" equivalent|basketball has no projection source" backend/src web/src`. Expected output is exactly the two "kickers go last" lines (`BasketballRules.java:394`, `MockDraftServiceTest.java:56`). Any other hit is an unfixed claim, so fix it and re-run.
- [X] T011 [P] [US1] In `claude/league-analysis.md`, directly under the non-goal bullet "Basketball. `pts_ppr` is a football stat key…" (~`:252-253`), add an indented note: "> **Amended 2026-10-05 (spec 016):** still true of `pts_ppr`, but not a reason basketball *can't* have projections. Sleeper serves per-game NBA projections (RotoWire), measured in `claude/projection-tools.md`. Basketball is unbuilt here, not unsupported upstream." Don't edit the original bullet text.
- [X] T012 [P] [US1] In `specs/004-ffwrapped-feature-parity/spec.md`, directly under the out-of-scope bullet that says the projection-bound tools need "a projection source that does not exist" (~`:266-270`), add: "> **Amended 2026-10-05 (spec 016):** the basketball half of this reason was wrong. Sleeper's `/projections/nba/{season}/{week}` returns per-game RotoWire projections (measured, `claude/projection-tools.md`). The tools are still out of scope here, but for want of building, not of a source." Don't edit the original bullet text.
- [X] T013 [P] [US1] In `claude/competitor-gap-roadmap.md`, under the `## Phase 0` heading and above its table, add: "> **Amended 2026-10-05 (spec 016 planning):** \"a false statement on a live page\" overstated it. Analysis is `sports: ['nfl']` in `web/src/destinations.ts`, so no basketball league links to it. The message reaches only a direct URL or an API caller. Still worth fixing (it's false). Spec 016 also found a second copy in the projection-ingest 400." Leave the table row as written.

**Checkpoint**: US1 is complete. Both messages are honest and pinned by tests, the gates are unchanged, and the docs are amended in the open.

---

## Phase 4: Polish & Verification

- [X] T014 Bug-hunting review of the diff (`git diff origin/main -- backend web claude specs/004-ffwrapped-feature-parity`), separate from the build. It's not a style pass. Check: (a) no change to any sport gate, route, record or `api.ts` type; (b) the `!= NFL` branch still sits after the two earlier guards; (c) `matchups.reason` gets the same text via `unavailable()` (contracts M1); (d) no string literal in `api/` or `engine/` names an ingest path; (e) every doc edit is an added note, with no rewritten original text. Fix and re-run T007 for any finding.
- [X] T015 Live verification, quickstart V3–V6. Kill any process on 8080 (check `netstat -ano | findstr :8080`), start `bootRun` from this branch via the Browser tool's `preview_start` (add a `.claude/launch.json` entry if none exists), and confirm Postgres on 5433 holds league `1339351318115946496`. Run V3 (curl NBA analysis: both reasons equal M1), V4 (browser `/leagues/1339351318115946496/analysis` shows M1, and the NBA rail/`LeagueHome` still don't offer Analysis; take a screenshot), V5 (`curl -D -` ingest → 400 with M2) and V6 (an NFL 2026 league's analysis unchanged). If V3 shows a different reason, research R6 was wrong: record it and amend R6, don't bend the check.
- [X] T016 Full suites, quickstart V7. From `backend/` run `./gradlew test`, and from `web/` run `npx tsc -b && npm test`. Report the pass count and **the backend skipped-test count**. Skips mean the ITs didn't run (Postgres down), and it isn't a full run.
- [X] T017 Record the results in `specs/016-honest-nba-projection-message/quickstart.md`: append a "Results (date)" section listing V1–V7, each marked **run** (with the actual output: test counts, skip count, the curl bodies, the grep output) or **not run** (with why). Note T004's observed failures as SC-001's evidence.
- [X] T018 Show `git status` and `git diff --stat`, confirm only this feature's files changed (concurrent sessions share this checkout), and **ask before committing** (AGENTS.md).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (T001)** → before everything.
- **US1 tests (T002, T003)** → T004 (observe failure) → **implementation (T005, T006)** → T007.
- T008, T009, T011, T012 and T013 touch separate files with no code dependency, so they can run any time after T001. T010 (the grep) must run after T005, T006, T008 and T009.
- **Polish**: T014 after all of US1; T015 and T016 after T014; T017 after T015 and T016; T018 last.

### User Story Dependencies

There's one story. No cross-story dependencies.

### Within User Story 1

- Tests fail first (T004), then the strings change.
- T005 before T007 (T002 won't compile until the method exists).

### Parallel Opportunities

- T002 ∥ T003 (different test files).
- T008 ∥ T009 ∥ T011 ∥ T012 ∥ T013 (five different files, all comments or docs).
- T005 and T006 are different files and *could* run in parallel, but both feed T007. Keep them sequential if a single agent builds.

---

## Parallel Example: User Story 1

```bash
# Tests together:
Task: "T002 LeagueAnalysisServiceTest: basketballIsToldProjectionsArentBuiltYetNotThatNoSourceExists"
Task: "T003 IngestControllerTest: projectionsWithNbaAreStillRefusedButNotForWantOfASource"

# Comment and doc corrections together:
Task: "T008 destinations.ts comment"
Task: "T009 FootballRulesTest.java comment"
Task: "T011 claude/league-analysis.md amended note"
Task: "T012 specs/004 spec.md amended note"
Task: "T013 claude/competitor-gap-roadmap.md amended note"
```

---

## Implementation Strategy

### MVP (the whole feature is US1)

1. T001 baseline.
2. T002–T004: tests written and observed failing.
3. T005–T007: strings changed, tests green. **This alone fixes the false runtime claims.**
4. T008–T013: comments and docs amended.
5. T014–T018: review, live verification, results recorded, ask to commit.

Per AGENTS.md, any subagent that writes code here (T002–T009) runs on Sonnet
(`model: "sonnet"`). Review (T014) and verification (T015–T016) stay with the
session's model. The parent session reads the diff before reporting done.

---

## Notes

- 18 tasks; [P] marks independent files.
- "No behavior change" (FR-006) is the main risk to watch. T014 (a) and V6 exist to catch an accidental gate or route change.
- Line numbers are as of `origin/main` @ `bee3645`. Re-locate by searching for the quoted text if main has moved.
