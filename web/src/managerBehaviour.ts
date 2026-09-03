/**
 * Shared by SeatPopover.tsx (a seat inside a draft board) and
 * ManagerTendencies.tsx (the standalone /managers page) -- both describe the
 * same underlying numbers (reachBias/unpredictability/positionalTilt), just
 * off two differently-shaped API types (Seat vs ManagerSummary). One text
 * function, so a threshold or ranking fix lands in both places at once.
 */
export type BehaviourInputs = {
  reachBias: number
  unpredictability: number
  positionalTilt: Record<string, number>
}

export function behaviourText(m: BehaviourInputs): string {
  const bits: string[] = []
  if (m.reachBias > 0.5) bits.push(`reaches ~${m.reachBias.toFixed(1)} picks early`)
  else if (m.reachBias < -0.5) bits.push(`waits ~${Math.abs(m.reachBias).toFixed(1)} picks past board`)
  else bits.push('drafts close to the board')

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

  return [...bits, ...tilts].join(' · ')
}
