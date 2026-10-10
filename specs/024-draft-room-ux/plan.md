# Implementation Plan: Draft room UX, borrowed from Sleeper and FantasyAlarm

**Branch**: `024-draft-room-ux` | **Date**: 2026-10-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/024-draft-room-ux/spec.md`, with five clarifications (four from `/speckit-clarify`, plus "this should also look the same for actual draft", raised during planning).

## Summary

All three draft rooms move onto one shared layout: the live room, the projection room for a real draft, and the mock room. In that layout:
- **Board and list:** the board sits above the player list, with a draggable divider that is remembered per device. This replaces the floating "Best available" sheet, which covered the board.
- **Board legibility:** cells say "2.03", show a snake arrow, and mark the cell on the clock. Seat headers show either the manager or a Claim pill, and your column is marked once, at the column level.
- **Player list:** a name search that ignores accents, a "Hide drafted" toggle, and a pinned strip of your targets, saved to your account per draft.
- **Pre-draft state:** one waiting message, a format summary ("4 teams · 14 rounds · 2 min · snake"), no "0/0" scarcity chips, and one grouped set of controls.
- **Mock pacing:** mocks gain an auto-pick and an atomic auto-finish, done on the server.

**Backend work:**
- One migration, V30: a new `draft_target` table, plus `'AUTO'` added to `mock_draft_pick.source`.
- One targets controller and one mock endpoint.
- Two new wire fields: `draftType` and `pickTimerSeconds`.

Nothing about the simulation or its numbers changes (FR-023).

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5, virtual threads); TypeScript (strict) + React (Vite)

**Primary Dependencies**: Spring Web/JDBC and Flyway on the backend, React and react-router on the web. **No new dependencies.** The split pane is our own code (research R2).

**Storage**: PostgreSQL. One new table `draft_target`, plus a widened check constraint (V30).

**Testing**: Gradle/JUnit with real-Postgres ITs, as the existing ITs do; Vitest + Testing Library on the web; live browser verification via the Browser pane.

**Target Platform**: Desktop browsers from 1280 px up for the split layout. Phones at 375 px get the Board | Players toggle.

**Project Type**: Web application (`backend/` + `web/`)

**Performance Goals**:
- Auto-finish adds no per-pick reveal time (SC-006). The measured server time is recorded at verification.
- Divider dragging stays smooth: only grid-row sizes change.

**Constraints**:
- **The live room never writes to Sleeper.**
- An assumed seat is never shown as known.
- `api.ts` mirrors every Java record change in the same change.
- Migrations are append-only. V30 is next: checked on main and on every unmerged branch, all of which top out at V29.

**Scale/Scope**: Drafts of 4–16 teams and up to 20 rounds. Target lists of 50 or fewer per owner per draft (cap labelled arbitrary). A handful of concurrent users per draft.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Rule (AGENTS.md "Hard rules") | Status |
|---|---|
| Migrations append-only | ✅ A new `V30__draft_target.sql`, nothing edited. The source check is dropped and re-added under V4's measured name `mock_draft_pick_source_check`. |
| `api.ts` mirrors Java records field-for-field | ✅ Planned in the same change: `SeatsResponse.draftType`, `LiveState.pickTimerSeconds`, `PickView.source` gaining `'AUTO'`, new `DraftTargets`. Tasks must pair each backend record edit with its `api.ts` edit. |
| Never retune a hand-set constant to match a guess | ✅ No `weights.yml` change. The new hand-set values (the 50-target cap, the 1280 px breakpoint, the default split) are labelled arbitrary, and the default split is *measured* in the browser rather than asserted (R2). |
| `Map.of` throws on a null in a JSON path | ⚠️ Watch: `pickTimerSeconds` and `draftType` are nullable. Carry them in records, never `Map.of`. |
| Ask before committing | ✅ Nothing is committed without asking. Work stays in the `draft-sim-024` worktree. |
| A planning doc's difficulty is not a spec | ✅ Checked against the code. The seat-claim mechanism already exists (`SeatPopover` "make mine"), as do the backend need rule (`SportRules.rosterNeed`) and the pick `source` column. The plan reuses all three instead of building them. |

**Gate: PASS.** Re-check after design: PASS, with no new violations. The ⚠️ is a review item, not a violation.

## Project Structure

### Documentation (this feature)

```text
specs/024-draft-room-ux/
├── spec.md
├── research/            # competitor notes (Sleeper, FantasyAlarm)
├── research.md          # Phase 0: R1–R9
├── data-model.md        # Phase 1
├── contracts/api.md     # Phase 1: REST + DraftRoomLayout UI contract
├── quickstart.md        # Phase 1: verification guide
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks (not yet)
```

### Source code

```text
backend/src/main/resources/db/migration/
└── V30__draft_target.sql                         # new table + widen mock_draft_pick_source_check

backend/src/main/java/com/ballknowers/draftsim/
├── api/TargetController.java                     # new: GET/PUT /api/targets
├── api/MockDraftController.java                  # + POST /{id}/auto
├── store/DraftTargetRepository.java              # new
├── mock/MockDraftService.java                    # + auto(scope); fork copies targets
├── mock/MockSessionState.java                    # PickView.source doc: AUTO
├── (seats endpoint) SeatsResponse                # + draftType
└── ingest/LiveDraftPoller.java + LiveState       # + pickTimerSeconds from settings.pick_timer

backend/src/test/java/…                           # TargetControllerIT, MockAutoPickIT, V30 migration IT

web/src/
├── api.ts                                        # mirrors + getTargets/putTargets/autoMock
├── components/DraftRoomLayout.tsx                # new: regions + divider + phone toggle
├── components/SplitDivider.tsx                   # new: role=separator, keyboard, localStorage
├── components/TargetStrip.tsx                    # new: chips, +N overflow, popover
├── components/RoomControls.tsx                   # new: grouped chips, one primary
├── components/FormatSummary.tsx                  # new: teams · rounds · timer · type
├── components/DraftBoard.tsx                     # round.pick, snake cue, on-clock, column mark, claim header
├── components/AvailabilityPanel.tsx              # loses floating-sheet code; + search, hide drafted, add-target
├── components/ScarcityMeter.tsx                  # hide 0-pool positions with a note
├── components/LiveStatusBar.tsx                  # sole owner of draft status
├── components/PickFeed.tsx                       # "auto" tag
├── useTargets.ts                                 # new: load / optimistic save / error
├── pages/LiveDraftView.tsx                       # renders through DraftRoomLayout; .live-waiting deleted
├── pages/DraftView.tsx                           # renders through DraftRoomLayout
├── pages/MockDraftView.tsx                       # renders through DraftRoomLayout; auto controls
└── styles.css                                    # .avail-sheet rules removed; layout/strip/divider styles

claude/board-first-layout-and-pick-latency.md     # "Amended by spec 024" note against §E (not a rewrite)
```

**Structure Decision**: The existing web application layout (`backend/` + `web/`). There is one new shared layout component; the three pages become thin.

## Build order (for /speckit-tasks)

1. **V30 + repository + `TargetController`**, with ITs against real Postgres.
2. **`DraftRoomLayout` + `SplitDivider`**, moving the three pages onto it with no behaviour change yet. Live-check FR-003 parity at this point, because it's the riskiest refactor.
3. **Board legibility** (US2).
4. **Pre-draft tidy**: summary fields, single status, scarcity, controls (US4).
5. **Search, hide drafted, target strip** (US3).
6. **Mock auto endpoint and controls** (US5).
7. **Amend §E** of the earlier layout doc, then full live verification per `quickstart.md`.

The repo's pipeline applies: an adversarial plan review **before** any code, a Sonnet build, a bug-hunting review, then live verification.

## Amended after plan review (2026-10-09)

The review ([plan-review.md](plan-review.md)) found 4 blockers. All four are resolved in [research.md](research.md) §Amendments A1–A13. Two of the fixes were the user's decisions; the rest are mechanical or Claude's conservative calls, marked as such:

- **Auto-pick's need rule was wrong.** `rosterNeed > 0` is always true, so the rule is now `isDraftable` + `> benchFloor` (A1).
- **The timer moves from the live frame to a stored `draft.pick_timer_seconds` column** in V30, so it shows pre-draft (A2).
- **The Sleeper URL in the request can't be ingested.** Verification uses the league's real draft (A3).
- **SC-001 was infeasible at today's 70 px cells.** It was **measured**, and the user chose compact one-line cells plus a compact row above the board (A4, FR-001b/c).

The Source Code tree below changes as follows. There is no "below" region. New regions are `notices`, `prompt` and `compactRow`. `DraftRepository.format()` is new, and `LiveDraftPoller` writes the timer column instead of carrying it on `LiveSnapshot`.

## Risks the adversarial review should attack (answered; see plan-review.md)

- **Moving three different pages onto one layout.** `DraftView` has the reveal, `PickPrompt` and a modal picker. `MockDraftView` mounts the list only on your turn. Is "always mounted, read-only between turns" correct for a mock, where the bots advance instantly anyway?
- **The default split meeting SC-001 at 1440×900 is arithmetic, not a measurement** (R2).
- **Auto-pick's "fits an open slot"** uses `rosterNeed > 0`. Is `rosterNeed` ever positive for a bench-only need, or zero for a real starter need in NBA's G/F/UTIL pooled slots?
- **The single-position NBA data (R6) means the SG and SF filters are thin.** Leaving that as a follow-up is a scope call the review may disagree with.
- **Targets keyed by `sleeper_draft_id` with no FK**: orphans if a draft is deleted for good. That's acceptable, but should be named.

## Complexity Tracking

No constitution violations to justify.
