# Implementation Plan: Stop saying basketball has no projection source

**Branch**: `016-honest-nba-projection-message` | **Date**: 2026-10-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/016-honest-nba-projection-message/spec.md`.
That's Phase 0 of `claude/competitor-gap-roadmap.md`.

## Summary

Two runtime strings say Sleeper has no basketball projection source. That's false:
measured 2026-10-05, it serves per-game RotoWire projections (research R0).
Replace them with "not built yet" wording that makes no claim about whether a
source exists (R1, R2). Correct two comments that repeat the premise, and add
dated "amended" notes to three docs (R5). Behavior is unchanged: the NBA
Analysis gate and the ingest refusal both stay. This is text plus two new tests
that fail on today's code (R3).

It also corrects the roadmap. Phase 0 called this "a false statement on a live
page", but Analysis isn't linked for basketball leagues, so it's reachable only by
direct URL or API (spec, Background).

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5) for the two strings and two
tests. TypeScript for one comment only, with no runtime change.

**Primary Dependencies**: existing only. `LeagueAnalysisService`,
`IngestController`, `ErrorHandler`, `NoIngestHintsInMessagesTest`.

**Storage**: N/A. No migration (V26 stays highest), no table or column.

**Testing**: JUnit 5 + Mockito. `LeagueAnalysisServiceTest` (pure functions,
no DB) and `IngestControllerTest` (Mockito, no DB). Neither can be silently
skipped when Postgres is down. Live verification per [quickstart.md](quickstart.md).

**Target Platform**: Railway (backend + web services). Local verification on
the Windows dev machine, with Postgres on 5433.

**Project Type**: web application (Spring Boot backend + React frontend).

**Performance Goals**: N/A.

**Constraints**: user-facing text must not contain `/api/ingest` or `POST /api/`
(`NoIngestHintsInMessagesTest`). The analysis reason must not assert anything
about an unmeasured sport (spec edge case). No sport gate changes (FR-006).

**Scale/Scope**: about 10 changed lines of production Java, 1 extracted static
method, 2 tests, 2 comments, 3 doc notes.

No NEEDS CLARIFICATION remain. Wording was the only open choice, and it's
proposed in [contracts/messages.md](contracts/messages.md) for review.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so the gates are
this repo's working rules from `AGENTS.md`, as in plans 009–015.

| Gate | Status |
|---|---|
| Honesty over apparent confidence | ✅ The feature exists to remove a false claim. The new text claims less ("not built yet"), not more. The roadmap's own overstatement is corrected in the open. |
| Verified vs assumed kept apart | ✅ R0 measured. R6's "reaches the sport check" is labelled reasoned-from-code, with V3 to observe it. The V2 grep was run on `main`, and its output is recorded. |
| Migrations append-only | ✅ N/A: none. |
| `api.ts` mirrors Java records | ✅ N/A: no record or type change, only string values. |
| `Map.of` with nullable values | ✅ Unaffected. `ErrorHandler`'s `Map.of("error", message)` gets a non-null literal. |
| Two implementations of one rule | ✅ The reason text gets one builder (`projectionsNotBuiltReason`) shared by both blocks via the existing `unavailable()`. The NFL gate isn't restated anywhere new. |
| Don't retune constants to match guesses | ✅ N/A. |
| Corrections shown, not hidden | ✅ Amended notes (R5), with no silent rewrites of docs. |
| Planning doc ≠ verified spec | ✅ Reading the code found the roadmap overstated reach. It also found a second runtime string (the ingest 400) and the `NoIngestHintsInMessagesTest` constraint, none of which the roadmap mentioned. |
| Live verification is the bar | ✅ quickstart V3–V6 drive the real server and browser. |
| Ask before committing; concurrent sessions | ⚠️ This work is in the main checkout, not a worktree. It's on its own branch off `origin/main` @ `bee3645`. Diff before every commit, and don't commit without asking. |
| Coding subagents run on Sonnet | Applies at `/speckit-implement`. |

**Post-design re-check**: unchanged. The design added no surface: one static
method, private to the package.

## Project Structure

### Documentation (this feature)

```text
specs/016-honest-nba-projection-message/
├── spec.md
├── plan.md              # this file
├── research.md          # R0–R7
├── data-model.md        # no model change; two string values
├── quickstart.md        # V1–V7
├── contracts/
│   └── messages.md      # M1 (analysis reason), M2 (ingest 400): before/after text + invariants
└── tasks.md             # /speckit-tasks, not yet
```

### Source Code (repository root)

```text
backend/src/main/java/com/ballknowers/draftsim/
├── engine/LeagueAnalysisService.java      # :445-451 call projectionsNotBuiltReason(league.sport()); new static, package-private
└── api/IngestController.java              # :142-147 M2 text; refusal unchanged

backend/src/test/java/com/ballknowers/draftsim/
├── engine/LeagueAnalysisServiceTest.java  # + reason for NBA: "aren't built", "nba", no "equivalent"
├── api/IngestControllerTest.java          # + nba projections → IAE, no "equivalent", projectionIngest never called
└── sport/FootballRulesTest.java           # :269 comment only

web/src/destinations.ts                    # :177-181 comment only; sports: ['nfl'] stays

claude/league-analysis.md                  # amended note at the :252 non-goal
claude/competitor-gap-roadmap.md           # amended note on Phase 0 (reach overstated)
specs/004-ffwrapped-feature-parity/spec.md # amended note at the :266-270 out-of-scope entry
```

**Structure Decision**: the existing web-application layout. The only
production changes are two backend string sites.

## Build order

1. Write both tests first and run them on the current strings. They must fail;
   record the failure (SC-001).
2. Extract `projectionsNotBuiltReason`, change M1. Change M2. Run V1.
3. Comments (`destinations.ts`, `FootballRulesTest`), then run the V2 grep.
4. Doc amendments (R5).
5. Bug-hunting review, then live verification V3–V7, recording each as run or
   not run.

## Complexity Tracking

No gate violations. The one ⚠️ (main checkout, not a worktree) is a process
caution, not a design violation. Nothing to justify.
