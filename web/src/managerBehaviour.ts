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
  reachBias: number
  unpredictability: number
  positionalTilt: Record<string, number>
  /**
   * Picks with a contemporaneous board position behind them. 0 means the
   * reach number is the league average wearing this manager's name, so the
   * sentence omits it rather than reporting it as a finding.
   */
  picksScored: number
}

export function behaviourText(m: BehaviourInputs): string {
  const bits: string[] = []
  if (m.picksScored > 0) {
    if (m.reachBias > 0.5) bits.push(`reaches ~${m.reachBias.toFixed(1)} picks early`)
    else if (m.reachBias < -0.5) bits.push(`waits ~${Math.abs(m.reachBias).toFixed(1)} picks past board`)
    else bits.push('drafts close to the board')
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
