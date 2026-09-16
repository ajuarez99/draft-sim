# Implementation Plan: A readable projected-week-by-week chart

**Branch**: `001-readable-bump-chart` | **Date**: 2026-09-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-readable-bump-chart/spec.md`

## Summary

The *Projected week by week* panel shows fourteen rosters in two perceptible
colors, because `hueFor()` moves the hue by one degree per sequential manager id
(ids 1–9 → hues 49–57; ids 10–19 → 127–136). Fixing that is real and worth doing,
but it does not make the chart readable: fourteen crossing lines fail the
colorblind gate at ΔE **0.5**, and even an eight-color professional palette fails
at **1.6**. Only **three** simultaneous colors pass on this app's surface.

So the plan does two separable things. It **fixes manager color** by assigning
hues by index within the league instead of hashing an id — which pays off in the
rail, the draft board and avatar fallbacks as well as the legend. And it **stops
spending hue as the chart's identity channel**: lines default to recessive
neutral, identity comes from direct end-labels and hover, the viewer's own line
is crimson by default, and up to three rosters can be selected for colored
comparison using a palette that passes every check.

## Technical Context

**Language/Version**: TypeScript 5.x, React 18.3
**Primary Dependencies**: react, react-dom, react-router-dom. No new dependency.
**Storage**: N/A — presentation only. Ranks continue to come from the backend `Ranker`.
**Testing**: Vitest + @testing-library/react (`npm test` in `web/`)
**Target Platform**: Web, dark mode only (the app ships dark)
**Project Type**: Web frontend within an existing full-stack repo
**Performance Goals**: No regression. The chart is inline SVG, ~14 series × ~14 points.
**Constraints**: Dark surface `#09121c`; palette must pass `--pairs all`; identity never color-alone.
**Scale/Scope**: 3 files changed, 2 files added. Affects 3 chart instances via one shared component.

## Constitution Check

*GATE: must pass before Phase 0. Re-checked after Phase 1.*

**`.specify/memory/constitution.md` is an unfilled template** — every principle is
still a `[PRINCIPLE_N_NAME]` placeholder. There are no ratified project gates to
check against, so this gate is **NOT APPLICABLE**, not "passed". Reported rather
than silently skipped. Run `/speckit-constitution` to make this a real gate.

In its absence the plan was checked against the repo's de-facto standing rules in
`claude/lessons.md` and `claude/README.md`:

| De-facto rule | Source | Status |
|---|---|---|
| Never build a second implementation of one rule | lessons §16 | **PASS** — one shared `BumpChart`, one new hue rule, existing hash rule renamed rather than overloaded |
| Ranks come from the backend `Ranker`, never recomputed on the frontend | `LeagueAnalysis.tsx:377` | **PASS** — untouched |
| Separate verified from assumed | lessons §"Standing rule" | **PASS** — research.md marks every number as measured and gives the command |
| Identity/state never carried by color alone | `styles.css` position-color comment; dataviz | **PASS** — FR-008 direct labels |
| A defaulted optional param that encodes a rule is a bug | memory / prior shipped bugs ×3 | **PASS** — see "Risks", the `hueFor` rename exists to prevent exactly this |

**Post-Phase-1 re-check**: still PASS. The Phase 1 design adds no second
implementation and no rule-encoding default.

## Project Structure

### Documentation (this feature)

```text
specs/001-readable-bump-chart/
├── plan.md              # This file
├── spec.md              # Feature spec
├── research.md          # Phase 0 — the measurements behind every decision
├── data-model.md        # Phase 1 — the view-model types
├── quickstart.md        # Phase 1 — how to verify it
└── contracts/
    ├── bump-chart.md    # BumpChart component contract
    └── manager-color.md # The color-assignment rule
```

### Source Code (repository root)

```text
web/src/
├── hue.ts                       # CHANGED — add index-based rule, rename the hash rule
├── hue.test.ts                  # ADDED — first test this shared rule has ever had
├── managerColor.ts              # ADDED — palette slots + selection→slot assignment
├── components/
│   └── BumpChart.tsx            # CHANGED — context/identity/focus layers, end labels, multi-select
└── pages/
    ├── LeagueAnalysis.tsx       # CHANGED — legend becomes multi-select; panel copy; both blocks
    ├── PowerRankings.tsx        # CHANGED — same multi-select state shape
    └── styles.css               # CHANGED — .bump-* neutral/focus/label styles
```

**Structure Decision**: existing `web/` React frontend. No backend, API or schema
change — `AnalysisProjections` already carries everything needed. The shared
`BumpChart` is changed once and all three chart instances benefit, per R4.

## Approach

### Layer 1 — Fix the color rule (`hue.ts`, `managerColor.ts`)

- Add `hueForIndex(index, count)` → `round(360 * index / count)`. Deterministic,
  guaranteed spread (25.7° at n=14 vs 1° today).
- Rename the existing hash to `hueForName(seed)` and keep it for the call sites
  that seed on a *league name*, where there is no known set to index within.
  Renaming is the point: a caller must not be able to reach for the wrong rule.
- `managerColor.ts` exports the three validated focus slots as named constants,
  the crimson "it's you" token, and the neutral context stroke.

### Layer 2 — Rebuild the chart's read (`BumpChart.tsx`)

- **Context**: unselected series stroke `var(--muted)` at low opacity, width 1.25.
- **Focus**: selected series take slots 1–3 in selection order, width 3, full opacity.
- **You**: the viewer's roster defaults to crimson, always distinguishable, and
  can still be selected.
- **Labels**: a text label at each series' right end. Collision handled by nudging
  labels vertically; if a league is too dense to label every line, label the
  focused ones and the viewer's, and rely on hover for the rest.
- **Hover**: nearest-line crosshair reporting manager · week · rank.
- Selection becomes `Set<rosterId>` capped at 3 (from `number | null`).

### Layer 3 — Callers

- `BumpLegend` becomes a multi-select pin list; the fourth pin is disabled with a
  visible reason (FR-007), never a generated fourth hue.
- Panel copy updated — "Click a line to follow it" is no longer the whole story.
- `PowerRankings.tsx` adopts the same `Set` state shape.

## Risks

| Risk | Mitigation |
|---|---|
| `hueFor` has 6 call sites and **no test** — changing it silently shifts avatar and rail colors | Rename forces every call site to be visited; `hue.test.ts` added first (SC-004) |
| Existing tests assert the old single-highlight shape | `PowerRankings.chart.test.ts`, `LeagueAnalysis.test.tsx`, `PowerRankings.stale.test.tsx` reviewed and updated with the state change, not after |
| Adding an optional `isMe`/`selection` prop with a default would re-encode a rule in a default — a bug this repo has shipped three times | Both are **required** props on the changed component; no defaults |
| End labels collide in a 14-team league | Explicit fallback in Layer 2; verified in the browser, not assumed |
| Light mode untouched | Declared out of scope in spec; the app ships dark |

## Complexity Tracking

No constitution violations to justify — the gate is not applicable (see above).
