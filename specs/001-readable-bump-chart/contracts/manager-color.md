# Contract: manager color assignment

`web/src/hue.ts` + `web/src/managerColor.ts`

## Two rules, deliberately named apart

There is no known set to index within when the seed is a *league name*, so the
hash rule has to survive. It is renamed so no caller can reach for the wrong one
by accident — `claude/lessons.md` §16 is a second implementation of one rule
shipping three times, and this is the cheap guard against a fourth.

### `hueForIndex(index: number, count: number): number`

Evenly-spaced identity hue for a member of a **known set**.

```
hueForIndex(i, n) = Math.round((360 * i) / n)
```

- **Guarantees** adjacent hues are `360/n` apart. n=14 → 25.7°.
- `count <= 0` or `index < 0` throws. There is no sensible hue for an empty set,
  and returning `0` would silently paint everyone red.
- Pure, no state, same input → same output.

### `hueForName(seed: string): number`

The existing multiply-31 hash, renamed, unchanged in behaviour. **Only** for
seeds with no known set — league names in `LeagueRailSection` and `DraftPicker`.

- Documented with its known weakness: sequential-ish seeds cluster. Acceptable
  for league names, which are not sequential, and where two leagues sharing a hue
  is cosmetic rather than a failure to read a chart.
- **Not** to be used for manager identity. The doc comment says so explicitly.

### `managerHues(rosters): Map<rosterId, ManagerHue>`

- Sorts by ascending `managerId`, falling back to `rosterId` when `managerId` is
  null (FR-002).
- Stable across reloads and across standings changes — the ordering key is
  identity, never rank (FR-003).
- Adding a roster re-spaces the wheel. Accepted: rosters are added between
  seasons, not mid-chart.

## Focus palette

```ts
export const FOCUS_SLOTS = ['#3987e5', '#d95926', '#199e70'] as const  // blue, orange, aqua
export const FOCUS_CAP = FOCUS_SLOTS.length                            // 3 — measured, not chosen
```

Validated all-pairs, dark, against surface `#09121c`:

```bash
node scripts/validate_palette.js "#3987e5,#d95926,#199e70" --mode dark --surface "#09121c" --pairs all
```

Result: CVD ΔE 9.4 (floor 8) · normal-vision ΔE 20.9 (floor 15) · contrast all
≥ 3:1 · **ALL CHECKS PASS**.

`FOCUS_CAP` is derived from the array, never written as a literal `3` elsewhere.
Adding a fourth hex without re-running the validator is exactly how this
regresses, so the constant carries the command in a comment directly above it.
