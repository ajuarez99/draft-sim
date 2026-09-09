/**
 * Snake order, mirrored from backend/.../domain/DraftSlot.java.
 *
 * The frontend needs its own copy because DraftBoard draws the grid from
 * (round, slot) and has to know which pick number lands in each cell -- it is
 * not reading a pick list the backend already ordered. That copy was a literal
 * `round % 2 === 1` for as long as football was the only sport, which is
 * correct for every football draft and wrong from round 3 on for the 2026 Ball
 * Knowers NBA draft, which Sleeper reports as `reversal_round: 3`.
 *
 * Keep this in step with DraftSlot.isForward. The whole point of Phase 6b's
 * reversal-round control is that the engine and the board agree about who
 * picks where; two different snake implementations is how they stop agreeing.
 */

/**
 * Plain snake alternates: odd rounds run left to right, even rounds right to
 * left. A `reversalRound` of R flips that parity from round R onward, so at
 * R = 3 rounds 1-2 snake normally and round 3 repeats round 2's direction
 * instead of switching back. 0 (or less) means it never flips.
 */
export function isForward(round: number, reversalRound: number): boolean {
  const plainSnakeForward = round % 2 === 1
  if (reversalRound <= 0 || round < reversalRound) return plainSnakeForward
  return !plainSnakeForward
}

/** The overall pick number at this (round, slot). */
export function pickNoAt(round: number, slot: number, teams: number, reversalRound: number): number {
  const indexInRound = isForward(round, reversalRound) ? slot : teams - slot + 1
  return (round - 1) * teams + indexInRound
}
