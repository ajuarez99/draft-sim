# Tasks: Basketball roster bars filled by position

**Input**: [spec.md](spec.md), [plan.md](plan.md)

## Phase 1: Guard first

- [x] T001 [US1][US2] Write `web/src/positionColours.test.ts`: every position in `POSITIONS_BY_SPORT` has an `.analysis-seg.pos-X` and an `.analysis-cell.pos-X` rule using `var(--x)`. Run it and confirm it FAILS on the current stylesheet for the five basketball positions (proves the test sees the bug).

## Phase 2: User Story 1 — bars (P1)

- [x] T002 [US1] Add `.analysis-seg.pos-PG/SG/SF/PF/C` rules in `web/src/styles.css` after `.analysis-seg.pos-DEF`.
- [x] T003 [US1] Give the base `.analysis-seg` a hatched fallback that matches no position colour (FR-003).

## Phase 3: User Story 2 — matrix (P2)

- [x] T004 [US2] Add `.analysis-cell.pos-PG/SG/SF/PF/C` rules after `.analysis-cell.pos-DEF`.

## Phase 4: Verify

- [x] T005 `npx tsc -b`, `npx vitest run`, `npm run build` green. T001 now passes.
- [x] T006 SC-004: delete one basketball rule, confirm T001 fails naming it, restore.
- [x] T007 Live: NBA league Team strength — computed segment backgrounds non-transparent and equal to pill colours; matrix tinted; screenshot. NFL league — computed colours unchanged.

## Verification record (2026-10-16, local, worktree code on :8139/:5239)

Measured, not assumed:

- T001: before the fix, 10 failed (5 NBA positions x 2 blocks), 12 football passed.
- T005: `npx tsc -b` clean; `npx vitest run` 130 files / 2209 tests passed; `npm run build` ok.
- T006: deleting `.analysis-seg.pos-SF` failed exactly that one case; restored, 22/22.
- T007 NBA "Ball Knowers" 2026 (1339351318115946496): 12 bars, 55 segments, 0 transparent; each segment's computed colour equals its pill's (PG oklch(0.67 0.145 215) ... C 359); hatch fallback computed to `none` on all of them (plan R3 confirmed). 60 matrix cells tinted at 7-24%.
- T007 NFL "(Foot) Ball Knowers" 2026 (1346366555759341568): 72 segments, every one equal to its position token, no hatch. Unchanged.
- FR-003: an injected `analysis-seg pos-UTIL` rendered the hatch at 13.8px wide, not transparent.

Not verified: prod. The local DB's numbers differ from the screenshot's; the change is CSS only, so prod renders the same rules once deployed.

Review stages here were inline passes by the same session, not separate agents, and the build was done directly rather than by a Sonnet subagent — stated plainly because AGENTS.md's convention asks for separate passes. Change size (17 CSS lines + one test) was the reason.
