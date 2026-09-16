# Contract: BumpChart

`web/src/components/BumpChart.tsx` — one component, three callers
(`ProjectedBumpBlock`, `ScoresBlock`, `PowerRankings`). Changing it here is how
all three get fixed without a second implementation.

## Props

```ts
type BumpChartProps = {
  series: Series[]
  weeks: number[]
  teamCount: number

  /** Selected rosterIds in selection order. Capped at FOCUS_CAP by the caller. */
  selection: number[]
  /** The viewer's roster, or null. REQUIRED — see below. */
  meRosterId: number | null
  /** Toggle a roster in or out of the selection. */
  onToggle: (rosterId: number) => void

  /** What one point means, for the hover readout. */
  scoreLabel?: string
}
```

### Why `selection` and `meRosterId` are required, not optional

An optional `meRosterId` defaulting to `null` would assert "nobody is the viewer"
as a *rule* while looking like a *value*. That exact shape — a defaulted optional
param silently encoding a rule — has shipped as a bug in this repo three times
(`reversalRound`, `sport`, and the pick-order default in `lessons.md` §16). Both
props are required so every caller has to answer the question.

`scoreLabel` stays optional: it is genuinely a cosmetic label, not a rule.

## Rendering contract

1. Every series in `series` is drawn. Selection changes **appearance only** —
   never which series exist. Fourteen lines in, fourteen lines out.
2. A series' stroke is determined solely by its `ManagerColorRole`:
   - in `selection` → `FOCUS_SLOTS[slot]`, width 3, opacity 1
   - else `rosterId === meRosterId` → `var(--crimson)`, width 2, opacity 1
   - else → `var(--muted)`, width 1.25, opacity 0.35
3. **Color never depends on rank, on standings, or on array position in
   `series`.** Only on `selection` order and `meRosterId`.
4. Deselecting a roster must not change any other roster's colour. **This is why
   `selection` is slots and not a list.** With a list, releasing the first of
   three shifted the survivors up and repainted them; the unit test asserted that
   as correct and it took clicking it in a browser to catch (research.md R6b).
   Releasing now empties one slot and leaves the others exactly where they are.
5. Every series carries a direct text label at its right end. Identity is never
   colour-alone (NFR-002).
6. `segmentsOf()` is unchanged — bye-week gaps and thin-coverage dashes behave
   exactly as they do today. `PowerRankings.chart.test.ts` covers it and must
   keep passing untouched.
7. The component still knows nothing about projections or power rankings. What a
   rank *means* stays the caller's business.

## A third mode

`colorBy` is a required discriminated union, added during implementation. Power
rankings' team-view chart plots its three ranking MODES, not rosters, each with a
deliberately fixed distinct hue. Greying those out would have destroyed a working
chart, so `colorBy: 'series'` keeps hue on the series there, and `colorBy: 'focus'`
is the roster-identity behaviour above. It is required, and a union rather than an
optional flag, so no call site can get the wrong one by default.

## Caller contract

- The caller owns selection state and enforces `FOCUS_CAP`.
- On a fourth toggle the caller either disables the control with a visible reason
  or releases the oldest selection. It never asks for a fourth colour (FR-007).
