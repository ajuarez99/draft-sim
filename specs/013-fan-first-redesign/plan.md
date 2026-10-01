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
existing responses (amended: eight fields after review) and one config block. There's no migration and no new endpoint.

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

## Amended after adversarial review (2026-09-30)

A cold-read review checked these docs against the code before any code was
written. It found 6 blockers and 18 should-fixes. Every decision below
supersedes the text it contradicts elsewhere in this folder. Spec, contracts,
quickstart and tasks were updated to match.

| # | Finding (evidence) | Decision |
|---|---|---|
| B1 | Steals/reaches would compare old picks to *today's* ADP (`RealPick` has no draft-time ADP; `LeagueController.java:313-341`) | Add `adpAtDraft: number \| null` to `RealPick` from `draft_pick.adp_at_time`. Tint **only** from it; null shows "no ADP at draft time" and stays untinted |
| B2 | The league home can't get record, rank or matchup for NBA: `/analysis` is NFL-only (`LeagueAnalysisService.java:433`), and standings have no `isMe` | Add `isMe` to history `StandingRow` (server-side: caller → manager id). Record and **standings position** come from that, for both sports. "Latest matchup" comes from the weekly report (B3), for both sports. "Next opponent" only where `/analysis` has it (NFL). SC-005 amended |
| B3 | `WeeklySide` identifies nobody (`api.ts:1837`) | Add `isMe` to `WeeklySide`, computed from the roster owner like `LeagueAnalysisService.java:687`. Never match on username |
| B4 | The depth harness counts the leaf itself, `border-top` only, hidden content, and the board can't pass | New harness definition: a surface is a visible element with background, border (any side) or inset shadow, **padding > 0, and element children**. Data grids (`.board`, tables) and their cells are exempt and measured by their own rule (a cell is one surface inside the grid frame). SC-001 amended |
| B5 | "Odds land after week N" is false: odds are written only by a commissioner recompute | One honest line per refusal reason: `NOT_COMPUTED` → "Odds appear when the commissioner updates them." Other reasons keep their existing text. StaleNotice is kept |
| B6 | The simulator doesn't auto-run; a "Simulating…" message before Start would be false | T068 becomes: a skeleton board while seats load; the existing progress overlay while running. SC-007 amended |
| S1 | History Compute needs no key | US2 AS2 and quickstart §4c corrected. A non-commissioner sees "Final ranks appear once the commissioner computes them." |
| S2 | Other league-wide writes exist (`setReversalRound`; refresh/ingest/track) | Refresh, ingest and track re-read Sleeper and are idempotent, so they stay visible. `setReversalRound` is a real league-wide setting any member can change today. It is **out of scope** and recorded as a follow-up, not silently left |
| S3 | The History nav group would be empty | Four groups: This week / The season / Draft / History, where History = Standings (the all-seasons page). The season group: Team strength, Luck, Bench points, Playoff odds, Awards |
| S4 | Labels already exist on the collapsed rail (`LeagueRailSection.tsx:322-342`); the switcher is already on top | T041 dropped. T042 retargeted to fix the remaining gaps: the phone lane works with groups, and groups are expanded by default on phone so SC-004's two-tap limit holds |
| S5 | Field is `rankingScores.entries`; Analysis uses records | Contract corrected. `ScoreEntry` gains a component |
| S6 | Positional record constructors in tests will break; config class must be registered | Tasks name `LeagueAnalyticsContractTest`, `LeagueAnalysisServiceTest` and `DraftSimApplication.java` `@EnableConfigurationProperties` |
| S7 | A strict `draftsim.grades` binding could stop startup (weights.yml is an optional import) | A missing block → `grade: null` everywhere, and `/api/health` reports `gradesLoaded`. Only a block that is *present* is validated |
| S8 | The all-play invariant was wrong with byes or odd team counts | Per team: Σ over weeks played of (n_w − 1). With odd n, median ties are by construction; documented |
| S9 | Expected-wins may fall back to another season, and covers the regular season only | Standings columns are filled only where `response.season` equals the row's season, and are labelled "regular season" |
| S10 | A new YourPickPanel would duplicate `AvailabilityPanel` (survival + verdict) | **Extend `AvailabilityPanel`** (tiers, faces, an optional no-availability reason for mocks) instead of a second panel. Mock drafts render it without curves |
| S11 | `positionRun` defaults sport to `'nfl'` | Callers in this spec must pass sport. The default is removed if its other callers allow (decided at build, recorded) |
| S12 | Archetypes would re-derive the reach band; `/managers` is already filtered | Archetype is built on `relativeReachRead(...).kind` (`managerBehaviour.ts:60`), including `thin`. "Your league first" means the **rail's currently selected league** |
| S13 | Moving `(±n)` into a tooltip hides a caveat on touch | `(±n)` stays visible |
| S14 | ~70 section titles use `.cond` directly; line refs stale; styles.css is 4,872 lines | New `.section-title` class. Section headings switch from `.cond` to it, and `.cond` stays for page titles and heroes. Line refs corrected |
| S15 | `--down` (hue 25) collides with `--crimson` (you); crimson *text* fails (3.92 on panel) | `--down` moves to hue 350 (measured at build); `.rank-move.down` remaps to `--down`. A new `--crimson-text` tuned to ≥ 4.5 for crimson used as text. Errors keep their current color (out of scope, noted) |
| S16 | Parallel page tasks would collide in `styles.css` | US1 page tasks run **sequentially**, each confined to its page's own CSS section |
| S17 | Site-home rows can't show record/rank without N requests | FR-017 amended: rows show sport, season, draft status and one action. No record/rank |
| S18 | Team strength is NFL-only | Grades on Team strength are NFL-only. Bench points has grades for both sports. Both "too early" rules (rankings hidden under 3 weeks, grade badged early under 4) are documented in How this works |
| N1–N5 | Names (`fetchSuperlatives`, `entries`), test lists, honesty wording, rollout order, CSS side effects | Fixed in tasks. Page H1s also take the fan names. The sign-in pitch says "likely gone". The Luck headline is suppressed while early. **Deploy the backend before the frontend.** The switcher fallback → League home. Re-measure position-tint contrast on the new `--panel` |

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

**Scale/Scope**: 18 existing routes plus one new (league home); 2 sports; 3 sizes; `styles.css` is 4,872 lines with ~125 `.mono` and ~182 `panel` usages

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
