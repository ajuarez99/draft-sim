# Phase 0 — Research

Everything below was **measured**, not reasoned about. Commands are reproducible;
the validator lives in the bundled `dataviz` skill.

---

## R1. Why the colors are identical today

**Decision**: `hueFor()` is the defect, not the chart.

**Measured**. `web/src/hue.ts` is `h = (h * 31 + charCode) % 360`. For short
sequential seeds the last character dominates and the hue advances 1° per id:

```
ids  1– 9 -> hues  49, 50, 51, 52, 53, 54, 55, 56, 57      (all orange)
ids 10–19 -> hues 127,128,129,130,131,132,133,134,135,136   (all green)
```

A league's manager ids *are* sequential, so this is the worst case, always. Two
clumps, ~78° apart, is exactly the orange/green legend in the screenshot.
Sleeper-style ids (`101`, `102`, …) degrade identically: hues 26–34.

**Alternatives considered**
- *Better hash.* Rejected — **measured failure**. FNV-1a with an xorshift
  finalizer and a golden-angle spread still produced a **2° minimum gap** across
  fourteen ids. A stateless hash distributes uniformly at random, and uniform
  random over 14 draws from 360 collides by the birthday problem. Spread cannot
  be guaranteed without knowing the set.
- *Hand-assigned colors per manager.* Rejected — not stable as leagues change,
  and no answer for mock/NBA leagues.

**Chosen**: assign by **index within the known league roster set**, evenly
spaced: `hue = round(360 * i / n)`, with `i` from a deterministic ordering
(ascending `managerId`, falling back to `rosterId`). Fourteen rosters → 25.7°
apart, guaranteed, versus 1° today.

---

## R2. How many colors this chart can actually carry

**Decision**: **three** simultaneous colors. This is a hard, measured ceiling.

Bump-chart lines cross, so any two series can be adjacent somewhere on the
plot. That makes the strict `--pairs all` test the correct one, not the default
adjacent test used for bars and stacks.

All runs against this app's real panel surface, `--panel: oklch(18% 0.025 250)`
= `#09121c`, dark mode:

| Candidate | CVD ΔE (≥8) | Normal-vision ΔE (≥15) | Contrast | Result |
|---|---|---|---|---|
| 14 evenly-spaced hues @ `oklch(70% 0.14 h)` | **0.5** | **5.5** | pass | **FAIL** |
| 8 documented palette slots | **1.6** | **7.1** | pass | **FAIL** |
| First 3 slots | 9.4 | 20.9 | pass | **PASS** |

```bash
node scripts/validate_palette.js "#3987e5,#d95926,#199e70" --mode dark --surface "#09121c" --pairs all
```

The middle row is the important one: even a professionally tuned eight-color
categorical palette cannot carry eight crossing lines. This is not a palette
problem to be solved by picking nicer colors — it is a property of human color
vision and it caps the design.

**Consequence for the request.** "Different colors per manager" is worth doing —
it is a genuine bug (R1) and it fixes manager color in the rail, the draft board
and avatar fallbacks. But it cannot make *this chart* readable on its own. The
chart needs identity from a channel other than hue.

---

## R3. Where identity comes from instead

**Decision**: a three-layer read — **context / identity / focus**.

1. **Context** — all rosters drawn thin in recessive neutral by default. Fourteen
   lines become a legible texture instead of fourteen competing signals.
2. **Identity** — a direct text label at each line's right end, plus hover.
   Fourteen unambiguous identities, zero color required. Satisfies the
   never-color-alone rule outright.
3. **Focus** — up to three rosters selected at once, taking validated slots
   1/2/3 in fixed selection order. The viewer's own roster is distinguished by
   default in crimson, reusing the app's existing "crimson when it's you" rule
   (`Avatar.isMe`, board headers, on-clock strip).

**Alternatives considered**
- *Keep all fourteen colored, just spread the hues.* Rejected — R2 measured it at
  CVD ΔE 0.5. It would look more colorful and read no better.
- *Small multiples (fourteen mini-charts).* Genuinely valid, and the dataviz
  guidance names it. Rejected for now: it loses the crossing-lines story, which
  is the entire point of a bump chart, and it is a much larger rewrite of a
  shared component. Worth revisiting if focus mode does not land well.
- *Fold to "Other".* Rejected — every roster in your league matters to someone;
  none of them is "Other".

---

## R4. Blast radius

**Decision**: fix the shared component; do not write a second chart.

`BumpChart` has three callers — `ProjectedBumpBlock` and `ScoresBlock` in
`LeagueAnalysis.tsx`, and `PowerRankings.tsx`. All three improve from one change.
`claude/league-analysis*.md` explicitly lists "a second bump chart" as a non-goal,
and `claude/lessons.md` §16 records a second implementation of one rule shipping
three separate times. That is the failure mode to avoid here.

`hueFor` has six call sites (`Avatar`, `LeagueRailSection`, `DraftPicker` ×2,
`MockSetup`, `PowerRankings`, `LeagueAnalysis` ×2) and **no test file at all** —
a shared rule with no test. Some call sites seed on a *league name*, not a
manager id; those keep hash-based assignment, because there is no known set to
index within. The two rules must be named differently so a future caller cannot
pick the wrong one by accident.

---

## R5. Open question (does not block)

Roster order for index assignment is stable *within* a league. It is **not**
stable across leagues — the same manager in two leagues gets two colors. Judged
acceptable: the chart is always scoped to one league, and cross-league color
identity is not something the UI claims today. Flagged rather than solved.
