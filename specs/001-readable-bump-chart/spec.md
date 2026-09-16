# Feature Specification: A readable projected-week-by-week chart

**Feature Branch**: `001-readable-bump-chart`
**Created**: 2026-09-16
**Status**: Draft
**Input**: "clean this up with different colors per manager and make it clearer for a user" (with a screenshot of the Projected week by week panel on League analysis)

## Problem

The *Projected week by week* panel draws fourteen rosters as fourteen rank lines.
Today it is unreadable for two separate reasons, and only one of them is color.

**1. The colors are not actually different.** `hueFor()` in `web/src/hue.ts` is
`h = (h * 31 + charCode) % 360`. For short sequential seeds the final character
dominates, moving the hue by exactly one degree per increment. Manager ids 1–9
produce hues 49–57; ids 10–19 produce hues 127–136. That is two clumps — one
orange, one green — which is exactly what the screenshot's legend shows. The
chart has fourteen series and two perceptible colors.

**2. Fourteen lines cannot be told apart by color even when the colors are
correct.** Measured against this app's own panel surface (`#09121c`), fourteen
evenly-spaced hues at the current `oklch(70% 0.14 h)` fail hard: worst adjacent
CVD ΔE **0.5** (deuteranopia) and worst normal-vision ΔE **5.5**, against floors
of 8 and 15. Because bump-chart lines cross, any two series can end up adjacent,
so the strict all-pairs test applies; under it even a professionally-tuned
eight-color palette fails (CVD ΔE 1.6). Only **three** simultaneous colors pass
every check (CVD ΔE 9.4, normal-vision 20.9, contrast ≥ 3:1).

So "different colors per manager" fixes a real bug, but it cannot by itself make
this chart readable. Identity for fourteen rosters has to come from something
other than hue.

## User Scenarios

### US1 — Find my own roster (Priority: P1)

A logged-in manager opens League analysis and wants to see where their team is
projected to finish each week.

**Acceptance**: the viewer's own line is distinguishable from the other thirteen
within one second, without clicking anything, and without relying on color alone.

### US2 — Compare myself against a rival (Priority: P1)

A manager wants to compare their line against one or two specific rivals.

**Acceptance**: the reader can select up to three rosters; selected rosters are
drawn in colors that pass the CVD and normal-vision gates; unselected rosters
recede but stay on screen as context. Attempting a fourth selection is handled
explicitly rather than silently degrading the palette.

### US3 — Identify any line without selecting it (Priority: P2)

A reader points at, or looks at, a line and wants to know whose it is.

**Acceptance**: every line is identifiable without color — by a direct label at
its end, and by hover — including for a reader with deuteranopia and for a reader
viewing in grayscale.

### US4 — Consistent manager color across the app (Priority: P2)

The same manager should read as the same color in the rail, the draft board,
their avatar fallback, and the legend.

**Acceptance**: two managers in the same league never receive near-identical
hues. Within a league the assignment is stable across reloads and does not
change when a roster is added or when the standings change.

## Requirements

### Functional

- **FR-001**: Manager color MUST be assigned by position within the known league
  roster set, evenly spaced around the hue wheel — not by a stateless hash of the
  id. A hash cannot guarantee separation (a good avalanche hash still produced a
  2° collision across fourteen ids in testing).
- **FR-002**: Assignment MUST be stable: keyed to a deterministic roster ordering
  (ascending `managerId`, falling back to `rosterId`), so it survives reloads and
  standings changes.
- **FR-003**: Color MUST follow the manager, never their rank or their position
  in the chart. Selecting or deselecting a roster MUST NOT repaint the others.
- **FR-004**: The chart's default state MUST draw unselected rosters as recessive
  neutral context, not as fourteen competing colored lines.
- **FR-005**: The viewer's own roster MUST be distinguished by default, using the
  app's existing "crimson when it's you" convention.
- **FR-006**: The reader MUST be able to select up to three rosters at once for
  colored comparison. Selected rosters take validated palette slots in fixed
  selection order.
- **FR-007**: Selecting a fourth roster MUST be handled deliberately — the
  control for it is disabled with a stated reason, or the oldest selection is
  released — never by generating a fourth hue.
- **FR-008**: Every line MUST carry a direct text label at its right end, so
  identity never depends on color.
- **FR-009**: Hover MUST report manager, week and rank for the nearest line.
- **FR-010**: The panel's explanatory copy MUST describe the new interaction;
  the current text says "Click a line to follow it", which will no longer be the
  whole story.
- **FR-011**: The fix MUST apply to every `BumpChart` caller — both blocks on
  League analysis and the Power rankings chart — via the shared component, not
  by a second implementation.

### Non-functional

- **NFR-001**: The simultaneous-color palette MUST pass `validate_palette.js`
  all-pairs in dark mode against surface `#09121c`: CVD ΔE ≥ 8, normal-vision
  ΔE ≥ 15, contrast ≥ 3:1.
- **NFR-002**: Identity MUST NOT be color-alone anywhere in the panel (legend,
  chart, tooltip).
- **NFR-003**: No new runtime dependency. This is presentation-layer only — no
  API or backend change.

## Out of Scope

- Changing what the ranks mean or how they are computed. Ranks come from the
  backend `Ranker`; a second implementation of that rule on the frontend is a
  bug this repo has shipped before and will not ship again.
- The Position group rankings table below the chart.
- Light mode. The app ships dark; a light palette would need its own validation.
- Any backend or API change.

## Success Criteria

- **SC-001**: No two managers in a fourteen-team league sit within 15° of hue.
- **SC-002**: The palette used for simultaneous on-chart comparison passes all
  six checks all-pairs in dark mode.
- **SC-003**: A reader can name the owner of any line without using color.
- **SC-004**: `hue.ts` has unit tests. It is a shared rule with six call sites
  and currently no test at all.
