# Implementation Plan: Player spotlight on the home page, one tab per league

**Branch**: `015-home-roster-players` | **Date**: 2026-10-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/015-home-roster-players/spec.md`

## Summary

Put spec 014's Player spotlight on the root home page (`/`), between "From Sleeper" and "Mock
drafts", with one tab per league in "Your leagues" order. It is **frontend only**. Each tab calls the
existing `/api/leagues/{id}/player-spotlight` (and, for football's Top list, the existing weekly
report) exactly as `LeagueHome` does, so the two pages cannot disagree. `PlayerSpotlight.tsx` is
split so its lists can be reused under a league tab strip. Visited tabs stay mounted, so switching
back costs nothing, and only the open tab is fetched.

## Technical Context

**Language/Version**: TypeScript (strict, `tsc -b`), React 18, Vite. No Java changes.

**Primary Dependencies**: existing only: `api.ts` (`getPlayerSpotlight`, `getWeeklyReport`,
`cachedDrafts`), `leagueLineage.ts`, `leagueDataVersion.tsx`, `components/PlayerSpotlight.tsx`.

**Storage**: N/A. No migration, no new table, no new response type.

**Testing**: Vitest + Testing Library (`web/src/**/*.test.tsx`); the backend JUnit source-scan test
`NoSportNameInPlayerSpotlightTest` extended to the new component; live verification per
[quickstart.md](quickstart.md).

**Target Platform**: the web app (desktop and phone widths), deployed on Railway.

**Project Type**: web application (Spring Boot backend + React frontend); this feature touches `web/` plus one backend test file.

**Performance Goals**: time-to-"Your leagues" no worse than `main` (SC-005). Only one spotlight
request on load. **Not measured yet**: the extra NFL round trip (R2) and the slow-network check (V6).

**Constraints**: no sport-name branching in the new component (FR-014, source-scanned); no new
refresh trigger from `/` (R7); 375px with a 16px gutter and no horizontal scroll (FR-011).

**Scale/Scope**: one new component (about 150 lines), one split of an existing component, one hook
moved to its own module, one section inserted in `DraftPicker`, CSS for the league strip, and tests.
A typical viewer has 1 to 6 leagues.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so the gates are this repo's
working rules from `AGENTS.md`, as in plans 009–014:

| Gate | Status |
|---|---|
| Honesty over apparent confidence: nothing looks more certain than it is | ✅ Reuses 014's reasons verbatim. The past-season case gets a sentence, not a blank (R4). Stale trending shows its age. The no-refresh trade-off is stated (R7). |
| Verified vs assumed kept apart | ✅ research.md opens by saying nothing was measured. R2 and R6 latency are labelled assumed, with V5 and V6 to measure them. |
| Migrations append-only | ✅ N/A: no migration. |
| `api.ts` mirrors Java records | ✅ N/A: no record or type change. |
| `Map.of` with nullable values | ✅ N/A: no Java response code. |
| Two implementations of one rule (multi-sport landmines) | ✅ One spotlight component and one endpoint for both pages. No copy (R3), no batch endpoint (R1). |
| Sport rules from the payload, not a sport name | ✅ The weekly-report decision reads `topOfNight` and `period` (R2). The new file is source-scanned, unconditionally (contract). |
| Planning doc ≠ verified spec | ✅ Read the real component and endpoint first. Found the football weekly dependency and the `applies: false → null` behaviour, neither of which is visible from the spec. |
| Live verification is the bar | ✅ quickstart V1–V13 drive the real server and UI. |
| Ask before committing; concurrent sessions | ✅ Isolated worktree off `origin/main`. Nothing committed without asking. |
| Coding subagents run on Sonnet | Applies at `/speckit-implement`. |

**Post-design re-check**: unchanged. The design added no backend surface and no new rule.

## Project Structure

### Documentation (this feature)

```text
specs/015-home-roster-players/
├── spec.md
├── plan.md              # this file
├── research.md          # R1–R9
├── data-model.md        # client state only
├── quickstart.md        # V1–V13 live checks
├── contracts/
│   └── home-spotlight-ui.md
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks, not yet
```

### Source Code (repository root)

```text
web/src/
├── useBlock.ts                          # NEW: useBlock + Block<T>, moved verbatim from LeagueHome (R8)
├── pages/
│   ├── LeagueHome.tsx                   # imports useBlock from ../useBlock; markup unchanged
│   ├── DraftPicker.tsx                  # inserts <HomeSpotlight> after From Sleeper (R6)
│   └── DraftPicker.test.tsx             # placement + absent-when-no-leagues
├── components/
│   ├── PlayerSpotlight.tsx              # split: export SpotlightLists({spotlight, weekly, idPrefix}); default wrapper unchanged (R3)
│   ├── PlayerSpotlight.test.tsx         # still green unchanged; + idPrefix uniqueness
│   ├── HomeSpotlight.tsx                # NEW: league tab strip, visited/selected state, per-tab blocks (R4, R5)
│   └── HomeSpotlight.test.tsx           # NEW: U1–U9, U11 with mocked api
└── styles.css                           # .home-spot-leagues strip (scrolls within itself at 375px)

backend/src/test/java/com/ballknowers/draftsim/engine/
└── NoSportNameInPlayerSpotlightTest.java  # + unconditional scan of HomeSpotlight.tsx
```

**Structure Decision**: web application layout as it exists. All production changes are in `web/`.
The only backend change is a test.

## Build order

1. Move `useBlock` to `web/src/useBlock.ts`, as its own commit. A pure move, with the suite green before and after.
2. Split `PlayerSpotlight.tsx` into `SpotlightLists` and the wrapper. `LeagueHome` renders byte-identical markup (existing tests are the check).
3. `HomeSpotlight.tsx` plus its tests (contract U1–U9, U11).
4. Insert it in `DraftPicker`, add the CSS for the strip, and extend the source-scan test.
5. Bug-hunting review, then live verification (quickstart), recording measured vs not run.

## Complexity Tracking

No gate violations. Nothing to justify.
