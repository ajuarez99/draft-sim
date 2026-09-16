/**
 * What colour a roster's line wears on a bump chart, and why it is allowed to.
 *
 * <p>The short version: a fourteen-team league cannot be told apart by hue, and
 * no palette fixes that. Measured against this app's own panel surface
 * (`--panel` = #09121c), fourteen evenly-spaced hues at the stroke the chart
 * used to draw collapse to a worst-pair colour-blind delta-E of **0.5** and a
 * worst-pair normal-vision delta-E of **5.5**, against floors of 8 and 15. Even
 * the eight slots of a professionally tuned categorical palette fail (1.6). Only
 * **three** simultaneous colours clear every check.
 *
 * <p>Bump-chart lines cross, so any two series can end up adjacent somewhere on
 * the plot. That is why the strict all-pairs test is the right one here and not
 * the cheaper adjacent-only test used for bars and stacks.
 *
 * <p>So hue stopped being this chart's identity channel. Identity comes from a
 * direct label at the end of each line; colour is spent on focus -- the reader's
 * own roster, plus up to three rosters they pinned. See
 * `specs/001-readable-bump-chart/research.md` R2.
 */

import type { AnalysisRosterProjection } from './api'

/**
 * The three slots a pinned roster can take, in pin order. Blue, aqua, yellow.
 *
 * <p><b>Validate with CRIMSON in the set, not just these three.</b> The reader's
 * own line is crimson and is on screen at the same time, so what a reader
 * actually sees at once is four colours, not three. Checking the three alone
 * passed at delta-E 9.4 and hid a real failure: the orange slot this list used
 * to hold sat at normal-vision delta-E 6.7 from crimson -- two of the four lines
 * were effectively the same colour. It took looking at the rendered chart to
 * notice. So the command below includes crimson, and it is the one that counts:
 *
 *   node scripts/validate_palette.js "#d33a3c,#3987e5,#199e70,#c98500" \
 *     --mode dark --surface "#09121c" --pairs all
 *
 * Current result: CVD delta-E 7.1 - normal-vision delta-E 17.0 (floor 15) -
 * contrast all >= 3:1 - ALL CHECKS PASS. The CVD figure sits in the 6-8 band,
 * which is legal only alongside secondary encoding; every line carries a direct
 * name label at its right end, which is that encoding. Remove the labels and
 * this palette stops being legal.
 *
 * <p>`#d33a3c` is `--crimson` (oklch(58% 0.19 25)) resolved to hex for the
 * validator, which does not read CSS variables. If `--crimson` moves in
 * styles.css, re-run this.
 */
export const FOCUS_SLOTS = ['#3987e5', '#199e70', '#c98500'] as const

/** Derived from the array on purpose -- a literal 3 elsewhere would drift. */
export const FOCUS_CAP = FOCUS_SLOTS.length

/** The visual job a series is doing on this render. Never stored, never sent. */
export type ManagerColorRole = 'me' | 'focus-1' | 'focus-2' | 'focus-3' | 'context'

/**
 * Which role a roster plays, given what is pinned and who the reader is.
 *
 * <p>Precedence is explicit: a roster that is BOTH pinned and the reader's own
 * renders as its pin slot, because pinning is something the reader just did and
 * "this is you" is something they already knew.
 *
 * <p>Depends only on identity and pin order -- never on rank, never on the
 * roster's position in the series array. A chart that repainted when the
 * standings moved would be lying about what colour means.
 */
export function roleFor(
  rosterId: number,
  selection: PinSlots,
  meRosterId: number | null,
): ManagerColorRole {
  const slot = selection.indexOf(rosterId)
  if (slot >= 0 && slot < FOCUS_CAP) return `focus-${slot + 1}` as ManagerColorRole
  if (meRosterId != null && rosterId === meRosterId) return 'me'
  return 'context'
}

/** The stroke a role wears. `me` and `context` are theme tokens; pins are hexes. */
export function strokeFor(role: ManagerColorRole): string {
  switch (role) {
    case 'me':
      return 'var(--crimson)'
    case 'context':
      return 'var(--muted)'
    default:
      return FOCUS_SLOTS[Number(role.slice('focus-'.length)) - 1]
  }
}

/** Line weight by role: pinned reads first, yours second, everyone else recedes. */
export function widthFor(role: ManagerColorRole): number {
  if (role === 'context') return 1.25
  if (role === 'me') return 2
  return 3
}

/** Context lines are present but quiet -- texture, not fourteen competing signals. */
export function opacityFor(role: ManagerColorRole): number {
  return role === 'context' ? 0.35 : 1
}

/**
 * The pinned rosters, BY SLOT. Fixed length `FOCUS_CAP`; `null` is a free slot.
 *
 * <p>Slots rather than a list, because the list version was wrong. With a plain
 * array, unpinning the first of three shifted the survivors up and repainted
 * them -- pin three, drop the first, and the other two both changed colour. The
 * unit test even asserted that as if it were fine. Watching it happen in the
 * browser is what made it obvious that it is not.
 *
 * <p>A slot is a promise: while a roster stays pinned it keeps its colour, no
 * matter what happens to the others.
 */
export type PinSlots = readonly (number | null)[]

/** No pins: every slot free. */
export const NO_PINS: PinSlots = FOCUS_SLOTS.map(() => null)

/** How many rosters are pinned right now. */
export const pinnedCount = (selection: PinSlots) => selection.filter((id) => id != null).length

/** Whether every slot is taken, so the next pin has nowhere to go. */
export const pinsFull = (selection: PinSlots) => pinnedCount(selection) >= FOCUS_CAP

/**
 * Pin `rosterId` into the first free slot, or release the slot it holds.
 *
 * <p>Releasing empties that one slot and leaves the others exactly where they
 * are, so no surviving pin ever changes colour (FR-003).
 *
 * <p>With every slot taken, a further pin is refused -- the caller is expected
 * to have disabled the control and said why. Refusing here too means a stray
 * call can never conjure a fourth colour.
 */
export function toggleSelection(selection: PinSlots, rosterId: number): (number | null)[] {
  const at = selection.indexOf(rosterId)
  if (at >= 0) return selection.map((id, i) => (i === at ? null : id))

  const free = selection.indexOf(null)
  if (free < 0) return [...selection]
  return selection.map((id, i) => (i === free ? rosterId : id))
}

/** An evenly-spaced identity hue, plus where it came from. */
export type ManagerHue = {
  /** 0-359. */
  hue: number
  /** Index in the deterministic roster ordering. */
  index: number
  /** Roster count the spacing was computed against. */
  count: number
}

type HueableRoster = Pick<AnalysisRosterProjection, 'rosterId' | 'managerId'>

/**
 * Evenly-spaced hues for a league's rosters, keyed by `rosterId`.
 *
 * <p>Ordered by ascending `managerId`, falling back to `rosterId` when a manager
 * id is missing, so the mapping survives a reload and does not move when the
 * standings do. Colour follows the manager, never their rank.
 *
 * <p>Index-based and not hashed, because a hash cannot guarantee separation:
 * the old `hueFor` put sequential ids one degree apart, and even FNV-1a with an
 * xorshift finaliser still collided at two degrees across fourteen ids. Fourteen
 * rosters here are 25.7 degrees apart, guaranteed.
 */
export function managerHues(rosters: readonly HueableRoster[]): Map<number, ManagerHue> {
  const ordered = [...rosters].sort(
    (a, b) => (a.managerId ?? a.rosterId) - (b.managerId ?? b.rosterId),
  )
  const count = ordered.length
  const out = new Map<number, ManagerHue>()
  ordered.forEach((r, index) => {
    out.set(r.rosterId, { hue: Math.round((360 * index) / count), index, count })
  })
  return out
}
