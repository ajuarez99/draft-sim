import type { AvailabilityRow, PlayerRef } from './api'
import { mkPlayer } from './testLiveRoom'

/**
 * The measured top-108 NBA eligibility shapes (spec 025 SC-002), count per
 * shape. The stored primary `position` is the alphabetically-first listing, as
 * the backend sends it, which is exactly why a `.position === filter` comparison
 * loses most multi-position players.
 */
export const TOP_108_SHAPES: ReadonlyArray<readonly [string[], number]> = [
  [['PG', 'SG'], 20],
  [['PG'], 17],
  [['C'], 16],
  [['C', 'PF'], 14],
  [['PF', 'SF'], 14],
  [['PF', 'SF', 'SG'], 11],
  [['SF', 'SG'], 10],
  [['PF'], 4],
  [['PF', 'PG', 'SF'], 1],
  [['PG', 'SF', 'SG'], 1],
]

export function top108Players(): PlayerRef[] {
  const out: PlayerRef[] = []
  let n = 0
  for (const [positions, count] of TOP_108_SHAPES) {
    for (let i = 0; i < count; i++) {
      const primary = [...positions].sort()[0]
      out.push({ ...mkPlayer(primary, `P${++n} ${positions.join('/')}`, n), positions: positions as PlayerRef['positions'] })
    }
  }
  return out
}

export const top108Rows = (pickNos: number[] = [20]): AvailabilityRow[] =>
  top108Players().map((player) => ({
    player,
    survivalByPick: Object.fromEntries(pickNos.map((k) => [String(k), 0.9])),
  }))

export const sgEligible = (ps: PlayerRef[]) => ps.filter((p) => p.positions?.includes('SG'))
