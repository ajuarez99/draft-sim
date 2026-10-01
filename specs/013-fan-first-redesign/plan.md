# Implementation Plan: Fan-first redesign

**Branch**: `013-fan-first-redesign` | **Date**: 2026-09-30 | **Spec**: [spec.md](spec.md)

**Base**: `origin/main` @ `f684189` (includes specs 010–012)

**Input**: Feature specification from `specs/013-fan-first-redesign/spec.md`. Design source: `claude/design-review-fan-first.md`.

## Summary

The site gets a fan-first redesign across every page, for both sports, at three
screen sizes: two surfaces instead of nested boxes, takeaway-first wording with
methods behind "How this works", one meaning per color, grouped navigation with fan
names, a league home, player photos, a "Your pick" panel, and rank-based grades.
This is overwhelmingly a **frontend** change. The backend adds four fields to
existing responses and one config block. There's no migration and no new endpoint.

Planning found four things the spec didn't know (spec amended, see its "Amended
after planning" note; details in [research.md](research.md)):

1. **Commissioner gating mostly exists already** (R1). `canCommission` (Sleeper flag
   *or* the configured app owner) already hides Power's and Forecast's recompute.
   The review saw them only because it was run as the commissioner. **History's
   Compute is the one real gap.**
2. **Mock drafts have no availability curve** (R5). They run no simulation, so the
   "Your pick" panel ships there with tiers but no availability bar, and says why.
3. **The weekly report opens on the latest *final* week on purpose** (R2, measured).
   The fix is the label. Week 3 still being non-final is out of scope.
4. **One existing color fails contrast** (R10, computed): white on the crimson "You"
   fill is 4.07:1. It gets its own fill token.

## Technical Context

**Language/Version**: TypeScript 5 (strict), React 18, Vite. Java 21 / Spring Boot 3.5 for the four response fields

**Primary Dependencies**: none new, npm or Gradle. Google Fonts already loads Oswald and Plus Jakarta Sans

**Storage**: none. No migration. One new `config/weights.yml` block (`draftsim.grades`)

**Testing**: vitest + Testing Library (web); JUnit/MockMvc ITs against Postgres (backend); live browser verification per [quickstart.md](quickstart.md)

**Target Platform**: browser at 1440×900, 768×1024, 375×812; backend and frontend on Railway, deployed independently

**Project Type**: web application (`backend/` + `web/`)

**Performance Goals**: simulator loading message ≤ 1s (SC-007). Player images must not cause layout shift (fixed-size, lazy)

**Constraints**:
- No page address changes.
- No caveat removed.
- No change to the commissioner key, backfill gating or `SimulationResult`.
- New frontend on an old backend degrades each new field to "absent" (contract table).
- Hand-set values are labelled arbitrary.

**Scale/Scope**: 18 existing routes plus one new (league home); 2 sports; 3 sizes; `styles.css` is 4,583 lines with ~125 `.mono` and ~182 `panel` usages

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template, so there are no ratified
principles to gate on. This plan is checked against `AGENTS.md`, which is the
project's binding rule set.

| Rule (AGENTS.md) | Status |
|---|---|
| Migrations append-only | ✅ No migration |
| `api.ts` mirrors Java records in the same change | ✅ Four fields; each lands with its TS type in the same commit ([contracts/api-additions.md](contracts/api-additions.md)) |
| Never retune a constant to match a guess | ✅ Grade/tier/archetype cutoffs are new, labelled arbitrary, and chosen once at build with the values recorded. `EARLY_THRESHOLD_WEEKS` is moved, not changed |
| Honesty: don't look more certain than you are | ✅ Caveat inventory before/after (quickstart §2); grades carry the early badge and always sit beside their number; archetypes refuse reach labels without measured reach; mock panel says why there's no availability |
| `Map.of` null trap | ✅ New nullable `grade` is put into the existing mutable maps, not `Map.of` |
| Class 1 (sign/ordering) | ✅ Grade ordering test; all-play invariants |
| Class 5 (right stat, wrong display) | ✅ The weekly label names *which* week and *why* (R2); standings "—" for seasons without all-play data rather than a blank that reads as zero |
| Class 6 ("no caller yet" ≠ works) | ✅ Every new field is exercised from a real browser in quickstart §4 |
| Two implementations of one rule | ✅ `canCommission` reused, not re-derived; early threshold moved to one constant; nav grouping lives on the existing `destinations.ts` table; archetypes live in the existing shared `managerBehaviour.ts` |
| Ask before committing; concurrent sessions | ✅ Branch isolated; commit per phase only when asked |
| Coding subagents on Sonnet | Applies at `/speckit-implement` |

**Post-design re-check:** unchanged. No violations, so Complexity Tracking is empty.

## Phases (build order = spec's delivery order; each live-verified before the next)

**Phase 1: Look, wording, commissioner (US1, US2)**
1. Tokens in `:root`: `--volt`, `--up`, `--down`, `--row-hover`, `--crimson-fill`;
   consolidate surfaces. Update the house-style header comment to [contracts/ui-rules.md](contracts/ui-rules.md).
2. Shared rules: `.mono` → Jakarta tabular; `.panel h2`/section titles → sentence case, 600.
3. `HowThisWorks` disclosure component. Caveat inventory (quickstart §2), then a
   copy pass page by page.
4. Flatten nested surfaces page by page, measured with quickstart §4a.
5. Backend: `canCommission` on the history response. Hide Compute. Forecast empty
   state gets fan wording.

**Phase 2: Navigation, league home, weekly report (US3–US5)**
6. `destinations.ts`: `group`, fan `label`, `formerLabel`, new `home` row;
   `LeagueRailSection` renders groups; collapsed rail gets labelled glyphs.
7. `LeagueHome` page at `/leagues/:id`, composed from existing endpoints (R8).
   Site home rows.
8. Weekly report: your matchup first, scoreboard strip, week stepper, "why this
   week" label (R2).

**Phase 3: Faces, draft room, grades, page reworks (US6–US9)**
9. `PlayerFace` (R6) wired into the board cell, picker, pick feed, weekly top
   performers and team strip.
10. "Your pick" panel: tiers (R5), availability bar where a curve exists, run
    callout, simulator loading state.
11. Backend: `LetterGrades`, `SeasonWindow`, `draftsim.grades`, and the grade fields on
    roster-management/analysis. `allPlay`/`median` on expected-wins. Frontend grade
    chips and early badge. Steals/reaches board toggle (uses existing ADP; no ADP →
    untinted and labelled).
12. Page reworks: Superlatives trophy list, manager profile header, scouting
    archetypes (R9), standings columns, mock setup chips, sign-in welcome.

## Project Structure

### Documentation (this feature)

```text
specs/013-fan-first-redesign/
├── spec.md
├── plan.md              # this file
├── research.md          # R1–R11
├── data-model.md        # response additions + frontend-derived values
├── quickstart.md        # live verification per phase
├── contracts/
│   ├── api-additions.md # four additive fields, rollout degradation
│   └── ui-rules.md      # surfaces, type, color, words, nav, faces, sizes
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks (not created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/ballknowers/draftsim/
├── api/LeagueHistoryController.java        # + canCommission on history
├── api/ExpectedWinsController.java         # + allPlay, median
├── api/RosterManagementController.java     # + grade, gradesEarly
├── api/LeagueAnalysisController.java       # + grade, gradesEarly
├── engine/ExpectedWinsService.java         # all-play + median from the same walk
├── engine/SeasonSuperlativesService.java   # reads SeasonWindow.EARLY_THRESHOLD_WEEKS
├── engine/SeasonWindow.java                # NEW: the one early threshold
├── engine/LetterGrades.java                # NEW: rank → grade
└── config/GradeProperties.java             # NEW: draftsim.grades
config/weights.yml                          # + grades block (labelled arbitrary)

web/src/
├── styles.css                              # tokens, shared rules, house-style header
├── api.ts                                  # mirrors the four fields
├── destinations.ts                         # group, fan label, formerLabel, home row
├── tiers.ts                                # NEW: tier grouping + TIER_ADP_GAP (arbitrary)
├── managerBehaviour.ts                     # archetype()
├── components/
│   ├── LeagueRailSection.tsx, Rail.tsx     # grouped nav, labelled collapsed rail
│   ├── HowThisWorks.tsx                    # NEW
│   ├── PlayerFace.tsx                      # NEW
│   ├── YourPickPanel.tsx                   # NEW (wraps/replaces the picker modal as primary)
│   ├── GradeChip.tsx                       # NEW
│   ├── PlayerPicker.tsx, AvailabilityPanel.tsx, PickFeed.tsx, TeamStrip.tsx, DraftBoard.tsx
│   └── PageHeader.tsx                      # one-sentence subtitle slot
└── pages/
    ├── LeagueHome.tsx                      # NEW
    ├── DraftPicker.tsx (site home), WeeklyReport.tsx, Superlatives.tsx, LeagueHistory.tsx,
    ├── PowerRankings.tsx, LeagueAnalysis.tsx, RosterManagement.tsx, ExpectedWins.tsx,
    ├── SeasonForecast.tsx, ManagerTendencies.tsx, ManagerHistory.tsx, ManagerComparison.tsx,
    ├── MockSetup.tsx, MockDraftView.tsx, DraftView.tsx, LiveDraftView.tsx,
    └── CompletedDraftBoard.tsx, SignIn.tsx
```

**Structure Decision**: the existing web application layout (`backend/` + `web/`).
There are no new modules. New components sit beside their peers, and the one new page
goes in `pages/`.

## Risks

| Risk | Mitigation |
|---|---|
| Flattening `.panel` globally breaks a page that relies on it | Shared-rule change first, then the depth script (quickstart §4a) on every route before moving on |
| A copy pass quietly drops a caveat | The before/after caveat inventory is a gate, not a nice-to-have |
| 12×15 board with photos is heavy | Lazy loading, thumbnail URLs, fixed size; measure image failures and layout shift on the full board |
| Concurrent sessions touching `styles.css` / `destinations.ts` | Commit per phase when asked; rebase via `sync_with_base_branch` before each phase |
| Grade cutoffs drift toward "whatever looks right" | Chosen once, recorded in the commit with the reason, never tuned to match an expectation (AGENTS.md) |

## Complexity Tracking

No violations.
