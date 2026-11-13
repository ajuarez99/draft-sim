import { isForward } from './snake'

/**
 * Which team's slot owns overall pick `pickNo` (1-indexed), for a snake draft with an optional reversal round.
 * Mirrors DraftSlot.slot on the backend; the one copy of the round-direction rule is snake.ts's `isForward`, which
 * this reuses rather than restating. draftSlot.parity.test.ts replays a fixture the backend writes from
 * DraftSlot.slot itself (spec 063 T020).
 *
 * `reversalRound` is required, never defaulted: leaving it out reads as "plain snake", which is wrong for a game
 * that stored a reversal round.
 */
export function slotForPick(pickNo: number, teams: number, reversalRound: number): number {
  const round = Math.floor((pickNo - 1) / teams) + 1
  const indexInRound = ((pickNo - 1) % teams) + 1
  return isForward(round, reversalRound) ? indexInRound : teams - indexInRound + 1
}
