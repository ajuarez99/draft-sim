/**
 * Shared by SeatPopover.tsx (a seat inside a draft board) and
 * ManagerTendencies.tsx (the standalone /managers page) -- both describe the
 * same underlying numbers (reachBias/unpredictability/positionalTilt), just
 * off two differently-shaped API types (Seat vs ManagerSummary). One text
 * function, so a threshold or ranking fix lands in both places at once.
 *
 * The one thing this file is careful about: reach and tilt are fitted from
 * different evidence and can be present or absent independently. Positional
 * tilt is fitted from every pick a manager made. Reach is only fitted from
 * picks that carry a contemporaneous board position (`adp_at_time`), which
 * only exists when a board snapshot was captured near the draft. A manager
 * with real drafts and no scoreable picks therefore has a genuine tilt and a
 * reach number that is nothing but the league average -- and saying "drafts
 * close to the board" about them states a measurement that was never taken.
 *
 * That is not hypothetical or rare. Every basketball manager is in exactly
 * this state, permanently: both completed NBA drafts are from 2024 and 2025
 * and the first NBA board was captured in 2026, so `picksScored` is 0 for all
 * of them and no re-ingest can change it (multi-sport-and-rebrand.md,
 * "Basketball has no reach signal"). Football can reach it too, for any draft
 * older than the ADP freshness window.
 */

export type BehaviourInputs = {
  /**
   * Picks earlier (+) or later (-) than the other managers in the same draft
   * room (claude/audit-2026-09-28/11-reach-bias-baseline.md). Not the absolute
   * distance from the market board: that figure is mostly a baseline offset
   * every room shares, so nearly every manager read as a reacher.
   */
  relativeReachBias: number | null
  /** Its standard error; null with fewer than 2 scoreable picks. */
  relativeReachStdErr: number | null
  unpredictability: number
  positionalTilt: Record<string, number>
  /**
   * Picks with a contemporaneous board position behind them. 0 means the
   * reach number is the league average wearing this manager's name, so the
   * sentence omits it rather than reporting it as a finding.
   */
  picksScored: number
}

export type ReachRead = {
  /** 'room' = within one standard error of the room; 'thin' = too few picks to say. */
  kind: 'early' | 'late' | 'room' | 'thin'
  text: string
}

/**
 * The one place the reach wording and the "drafts like the room" band live, so
 * the seat popover, /managers and the manager page cannot disagree. Null when
 * there is no figure (no scoreable picks): the caller says why, never a zero.
 *
 * The band is inclusive: |relative| <= one standard error reads as "drafts like
 * the room". With 15 picks a standard error is typically ~7 picks, so most
 * managers land in the band -- that is the honest reading of thin data.
 */
export function relativeReachRead(rel: number | null, se: number | null): ReachRead | null {
  if (rel == null) return null
  if (se == null) return { kind: 'thin', text: 'too few picks to compare with their room' }
  if (Math.abs(rel) <= se) return { kind: 'room', text: 'drafts like the room' }
  const n = Math.abs(rel).toFixed(1)
  return rel > 0
    ? { kind: 'early', text: `${n} picks earlier than their draft room` }
    : { kind: 'late', text: `${n} picks later than their draft room` }
}

export function behaviourText(m: BehaviourInputs): string {
  const bits: string[] = []
  if (m.picksScored > 0) {
    const read = relativeReachRead(m.relativeReachBias, m.relativeReachStdErr)
    if (read) bits.push(read.text)
  }

  if (m.unpredictability >= 1.25) bits.push('erratic')
  else if (m.unpredictability <= 0.8) bits.push('very predictable')

  // Ranked by how far a tilt sits from neutral (1.0), not by raw value --
  // otherwise two weak leans can bury a single strong fade (e.g. RB 1.06 and
  // WR 1.05 outranking TE 0.2, when TE is the only tilt actually worth saying).
  const tilts = Object.entries(m.positionalTilt)
    .filter(([, v]) => Math.abs(v - 1) > 0.05)
    .sort((a, b) => Math.abs(b[1] - 1) - Math.abs(a[1] - 1))
    .slice(0, 2)
    .map(([pos, v]) => `${v > 1 ? 'leans' : 'fades'} ${pos}`)

  const all = [...bits, ...tilts]
  // Only reachable with the reach clause suppressed: with it, `bits` is never
  // empty. "No clear lean" is itself a real reading of a real tilt fit, so it
  // is safe to say here in a way "drafts close to the board" would not be.
  if (all.length === 0) return 'No clear positional lean in what they have drafted'
  return all.join(' · ')
}

/**
 * Why there is no reach number for this manager, or null when there is one.
 *
 * `draftsObserved > 0 && picksScored === 0` is the state worth naming: the
 * history exists and was fitted for tilt, but nothing in it could be scored
 * against a board. A manager with no drafts at all is a different and already
 * well-described thing ("nothing entered, no history yet"), so it returns null
 * and leaves that copy alone.
 */
export function reachGapText(m: { draftsObserved: number; picksScored: number }): string | null {
  if (m.draftsObserved === 0 || m.picksScored > 0) return null
  const d = `${m.draftsObserved} draft${m.draftsObserved === 1 ? '' : 's'}`
  return `${d} of history, but no pick in ${m.draftsObserved === 1 ? 'it' : 'them'} can be scored for reach — `
    + 'no board snapshot exists from close enough to those drafts. The position leanings are '
    + 'real; there is no reach number, and the engine uses the league average in its place.'
}

/**
 * The smallest distance from neutral (1.0) at which a positional tilt becomes
 * the manager's label ("QB early", "TE late"). ARBITRARY: a hand-set number,
 * not fitted and not backtested. It is deliberately well above the 0.05 that
 * `behaviourText` uses to list a lean at all, because a label is a headline and
 * a weak lean is not one. If a manager has no tilt this far from neutral the
 * label says so ("Not enough history") rather than naming a weak lean.
 */
export const ARCHETYPE_TILT_CUTOFF = 0.25

export type Archetype = {
  /** The short fan-facing label: "Reacher", "Waits", "Drafts like the room", "QB early", "Not enough history". */
  label: string
  /** Why this label, in a sentence that names its evidence. Safe to show beside or under the label. */
  basis: string
}

/**
 * One label per manager, built on the SAME reach read the seat popover,
 * /managers and the manager page use (`relativeReachRead`), so an archetype can
 * never disagree with the reach caption beside it, and the "room" band is not
 * re-derived here.
 *
 * Order of evidence:
 *   1. Reach, but only with scoreable picks behind it (`picksScored > 0`) and a
 *      read that is not `thin`: early -> "Reacher", late -> "Waits", room ->
 *      "Drafts like the room". With no scoreable picks the reach number is the
 *      league mean wearing this manager's name (every NBA manager, always), so
 *      it never produces a reach label.
 *   2. Otherwise the strongest positional tilt, if it is at least
 *      ARCHETYPE_TILT_CUTOFF from neutral.
 *   3. Otherwise "Not enough history".
 */
export function archetype(m: BehaviourInputs): Archetype {
  if (m.picksScored > 0) {
    const read = relativeReachRead(m.relativeReachBias, m.relativeReachStdErr)
    if (read && read.kind !== 'thin') {
      const label = read.kind === 'early' ? 'Reacher' : read.kind === 'late' ? 'Waits' : 'Drafts like the room'
      return { label, basis: read.text }
    }
  }

  const strongest = Object.entries(m.positionalTilt)
    .sort((a, b) => Math.abs(b[1] - 1) - Math.abs(a[1] - 1))[0]
  if (strongest && Math.abs(strongest[1] - 1) >= ARCHETYPE_TILT_CUTOFF) {
    const [pos, v] = strongest
    return {
      label: `${pos} ${v > 1 ? 'early' : 'late'}`,
      basis: `${v > 1 ? 'Leans' : 'Fades'} ${pos} (${v.toFixed(2)}x neutral); no usable reach read to label them on.`,
    }
  }

  return {
    label: 'Not enough history',
    basis: m.picksScored > 0
      ? 'Too few scoreable picks to compare with their room, and no strong positional lean.'
      : 'No pick of theirs can be scored for reach, and no strong positional lean.',
  }
}
