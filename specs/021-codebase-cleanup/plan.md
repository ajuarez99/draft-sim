# Implementation Plan: Codebase Cleanup — Refactor & Removal

**Branch**: `021-codebase-cleanup` | **Date**: 2026-10-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/021-codebase-cleanup/spec.md`

## Summary

The work is three independently mergeable phases, and none of them changes anything
a user can see.

- **P1, removal.** Delete the dev-only Power Rankings verify page and the reference
  PNGs it uses. The PNGs are currently served publicly. Also fill in or delete the
  placeholder constitution, and prune merged worktrees and branches, but only after
  the owner confirms the list.
- **P2, consolidation.** Collapse the duplicated rounding and ordinal helpers into
  one implementation per language. Then convert the hand-built `Map<String,Object>`
  responses to response records, one controller per change. Each conversion is
  guarded by a characterization test written against the *old* code first.
- **P3, splits.** Split the six files over 1,000 lines along seams they already
  have, one file per change.

The central technical risk is in P2. The spec requires "identical JSON", and Jackson
serializes a record's null component as `"k": null`. A map that *skipped* a null key
would therefore gain one. Research §R3 decides how that case is caught and handled.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5) for the backend; TypeScript with React
and Vite for the frontend.

**Primary Dependencies**: Jackson for JSON, which uses its defaults here: no global
inclusion config is set in `application.yml`, so nulls are included. JUnit 5 and
Vitest for tests.

**Storage**: PostgreSQL through Flyway. **No schema change in this feature** (FR-009).

**Testing**: `./gradlew test`, which has known silent IT skips when Postgres is down,
so the skip count must be compared every time. `npx vitest run`, `npx tsc -b` and
`npm run build`.

**Target Platform**: Railway (backend at api.ballknowers.co, static frontend at
www.ballknowers.co).

**Project Type**: Web application (backend/ + web/).

**Performance Goals**: N/A. A refactor must not regress performance, and nothing
here touches the simulation hot path.

**Constraints**: Response bodies must be byte-equivalent after normalizing key order
(FR-006). Concurrent sessions share the machine, so all work happens in the
`../draft-sim-021` worktree as small, separate commits.

**Scale/Scope**: About 141 map-built responses across 24 controllers, 17 backend
rounding copies, ~~4~~ 5 frontend ordinal copies, and 6 files over 1,000 lines.

## Constitution Check

`.specify/memory/constitution.md` is the **unfilled template**, so there is no ratified
constitution to check against. This gate uses the AGENTS.md hard rules instead, which
are this repo's actual governance. Filling the constitution in is itself task FR-011.

| Rule (AGENTS.md) | This plan | Status |
|---|---|---|
| Migrations are append-only | No migration touched; FR-009 | ✅ |
| `api.ts` mirrors Java records in the same change | P2 introduces the records; each conversion updates `api.ts` in the same commit, or proves it needs no change | ✅ (gated by contract C2) |
| `Map.of` throws on null | P2 *removes* map-building; any map that survives uses mutable maps | ✅ |
| Don't retune constants to match guesses | No constants touched | ✅ |
| Ask before committing | Each phase's commits are proposed, not auto-made; branch deletion needs explicit confirmation (FR-008) | ✅ |
| Verified vs. assumed kept apart | research.md tags every claim **measured** or **assumed** | ✅ |
| Multi-stage pipeline (plan → adversarial review → build → bug-hunt → live verify) | This plan is stage 1. An adversarial review of it is the next step, before `/speckit-tasks` | ⚠️ pending, not violated |
| Coding subagents run on Sonnet | Applies at build time | n/a here |

**Post-design re-check**: still passes. The design adds no runtime dependencies, no
schema change and no new endpoints.

## Project Structure

### Documentation (this feature)

```text
specs/021-codebase-cleanup/
├── spec.md
├── plan.md              # this file
├── research.md          # Phase 0: decisions R1–R8
├── data-model.md        # removal-candidate / refactor-target records + response-record conventions
├── quickstart.md        # how to prove each phase changed nothing
├── contracts/
│   ├── C1-removal-evidence.md
│   ├── C2-response-equivalence.md
│   └── C3-split-equivalence.md
└── tasks.md             # /speckit-tasks, not created here
```

### Source Code (touched areas only)

```text
backend/src/main/java/com/ballknowers/draftsim/
├── util/Rounding.java               # NEW (P2): the one rounding implementation
├── api/                             # P2: map-built bodies → records; P3: LeagueHistoryController ÷ 3
│   └── dto/                         # NEW (P2): response records, one file per controller
└── engine/
    ├── SeasonSuperlativesService.java   # P3: delegates to …
    └── superlatives/                    # NEW (P3): one unit per kind
backend/src/test/java/com/ballknowers/draftsim/api/
└── *CharacterizationTest.java       # NEW (P2): golden JSON written BEFORE each conversion

web/src/
├── format.ts                        # NEW (P2): ordinal() and future shared formatters
├── api.ts                           # P3: becomes a barrel re-exporting api/*.ts
├── api/                             # NEW (P3): per-domain types and fetchers
├── styles.css                       # P3: becomes an @import index
├── styles/                          # NEW (P3): tokens, shell, per-page sheets
├── App.tsx                          # P1: verify route removed
└── pages/PowerRankings.verify.tsx   # P1: DELETED
web/public/pr-reference/             # P1: DELETED
```

**Structure decision**: Use the existing web-application layout. The new directories
exist only to receive code that is being split out, and every new module is
re-exported from the old path, so import sites do not churn (research R6).

## Phase order and gating

| Phase | Merge unit | Gate before merge |
|---|---|---|
| P1 | 1 PR | C1 evidence recorded; tests for the 4 verify-only builders added first; suites green, skip count ≤ baseline; prod PNG URL no longer returns `image/png` (*amended: it was "404s"; production's SPA fallback returns 200 HTML*) |
| P2a, helpers | 1 PR | Helper tests over the full input range (R2); suites green |
| P2b, responses | **1 PR per controller**, worst first, merged in **batches** | C2 characterization (MockMvc for LHC/LC/SC) committed *before* the conversion; **0 IT skips** |
| P3, splits | **1 PR per file**; LHC only *after* its P2b conversion | C3; byte-identical built CSS; service-level golden test before the superlatives split |

**Merge freeze**: nothing merges to main from 2026-10-09 through the end of the
2026-10-10 NBA draft, because both Railway services auto-deploy.

## Amended after review (2026-10-07)

[plan-review.md](plan-review.md) returned **proceed after amendments** (4 blockers, 12
should-fix, 4 nits). All 20 are folded in. The full resolutions are in
[research.md § Amended after review](research.md#amended-after-review-2026-10-07); this
section records what changed at plan level.

- **Blockers 1–2:** the R3 rules are replaced. Keys are classified by final emitted
  state, there is one record per emitted shape, and dynamic-key maps stay maps.
- **Blockers 3–4:** the three worst controllers are characterized through MockMvc with
  stubbed services, not through an extracted seam. Map-casting tests move to JSON
  assertions in the characterization commit. P2b needs 0 IT skips.
- **Scope cuts:** error bodies, SSE payloads, controllers outside `api/`, and
  `PlayoffOddsService:422`.
- **Ordering:** LHC is converted before it is split. Dto files are named by response
  family. The superlatives units stay in `engine`, behind a new service-level golden
  test.
- **Deploy:** merge freeze over the NBA draft; backend PRs are batched; both services
  are checked after each merge.
- **Project Structure correction:** `engine/superlatives/` becomes new files *in*
  `engine/`, and `api/dto/<Controller>…` becomes `api/dto/<ResponseFamily>Responses.java`.

## Complexity Tracking

| Choice | Why | Simpler alternative rejected because |
|---|---|---|
| Characterization tests per controller (P2b) | Only a test written against the old code proves the new code identical | Live before/after hashing: the parity script's own history (stale baselines, 791/850 ADP moves between rebuilds) shows that input drift makes live hashes report false MOVED results |
| Barrel re-exports for `api.ts` and the CSS index | 108 files import `./api`; re-exporting keeps that diff at 0 | Rewriting 108 import sites maximizes conflicts with concurrent sessions for no behavioral gain |
