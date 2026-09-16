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
export const FOCUS_SLOTS = ['#3987e5', '#199e70', '#c98500'] as const  // blue, aqua, yellow
export const FOCUS_CAP = FOCUS_SLOTS.length                            // 3 — measured, not chosen
```

**Validate with crimson in the set.** The reader's own line is `--crimson` and is
always drawn, so four colours are on screen at once, not three. Validating the
three alone passed at ΔE 9.4 and hid a real failure — see research.md R6a.

```bash
node scripts/validate_palette.js "#d33a3c,#3987e5,#199e70,#c98500" --mode dark --surface "#09121c" --pairs all
```

Result: CVD ΔE 7.1 · normal-vision ΔE 17.0 (floor 15) · contrast all ≥ 3:1 ·
**ALL CHECKS PASS**. The CVD figure is in the 6–8 band, legal only alongside
secondary encoding — the direct end labels are that encoding, so removing them
would make this palette illegal.

`#d33a3c` is `--crimson` (`oklch(58% 0.19 25)`) resolved to hex, since the
validator does not read CSS variables. If `--crimson` moves, re-run this.

`FOCUS_CAP` is derived from the array, never written as a literal `3` elsewhere.
Adding a fourth hex without re-running the validator is exactly how this
regresses, so the constant carries the command in a comment directly above it.
