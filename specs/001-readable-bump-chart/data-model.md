# Phase 1 — Data model

View-model only. No persisted entity, no API field, no schema change: the
backend already sends everything this feature needs on `AnalysisProjections`.

## ManagerColorRole

What visual role a series is playing on this render. Derived per render — never
stored, never sent.

| Value | Meaning | Stroke |
|---|---|---|
| `me` | the signed-in viewer's roster | `var(--crimson)` |
| `focus-1` | first selected roster | `#3987e5` |
| `focus-2` | second selected roster | `#d95926` |
| `focus-3` | third selected roster | `#199e70` |
| `context` | everything else | `var(--muted)`, low opacity |

Roles are assigned by **selection order**, and selection order is keyed to
`rosterId` so a roster keeps its slot while it stays selected (FR-003 — releasing
one roster must not repaint the survivors).

## ChartSelection

```ts
type ChartSelection = {
  /** Selected rosterIds in selection order. Length capped at 3 (R2). */
  order: number[]
  /** The viewer's own rosterId, or null when signed out / not in this league. */
  meRosterId: number | null
}
```

**Invariants**

- `order.length <= 3` — the measured ceiling, not a preference.
- `order` holds no duplicates.
- Deselecting removes one entry and **does not reorder the rest**.
- A roster in `order` that is also `meRosterId` renders as its focus slot, not
  as `me` — an explicit selection is the stronger signal.

## ManagerHue

Identity hue for chips outside the chart — legend swatch fallback, avatar
fallback, rail, draft board.

```ts
type ManagerHue = {
  /** 0–359, evenly spaced within the league. */
  hue: number
  /** Index in the deterministic roster ordering. */
  index: number
  /** Roster count the spacing was computed against. */
  count: number
}
```

**Assignment rule** (see `contracts/manager-color.md`): sort rosters by ascending
`managerId`, falling back to `rosterId` when `managerId` is null; then
`hue = round(360 * index / count)`.

**Why index and not a hash**: a hash cannot guarantee spread. Measured — FNV-1a
with an xorshift finalizer still put two of fourteen ids 2° apart; the current
multiply-31 hash puts them 1° apart. Index assignment guarantees 360/n.

## Existing types — unchanged

`SeriesPoint` and `Series` in `BumpChart.tsx` keep their shape, except that
`Series.hue` is no longer the thing that draws the line. It stays for the legend
swatch and the avatar chip. The line's stroke now comes from its
`ManagerColorRole`.

`SeriesPoint.thin`, `note` and `ballotCount` stay exactly as they are — they
belong to Power rankings' ballot coverage and this feature does not touch them.
