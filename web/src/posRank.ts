import type { PlayerRef, Sport } from './api'
import { positionLabel } from './positions'

// 999 is Sleeper's own "no rank" sentinel (BoardService's default, never null
// -- see api.ts). This module is the only place that knows that, so a new
// caller can't quietly render "RB999".
const NO_RANK = 999

type Ranked = Pick<PlayerRef, 'position' | 'positions' | 'positionalRank'>

/**
 * The compact badge: "RB4", falling back to the bare position when the rank is
 * unknown. For the board cell and the availability table, whose `.pos` pill is
 * ~30px wide and cannot hold anything longer.
 *
 * `sport` is REQUIRED (spec 025 A3): it encodes a rule, not a value. Basketball
 * shows the position label with NO rank number for every player ("C", "PG/SG"),
 * because the stored rank counts only each player's first-listed (primary) position
 * and is not a rank among position-eligible players.
 */
export function posRank(p: Ranked, sport: Sport): string {
  if (sport === 'nba' || p.positionalRank === NO_RANK) return positionLabel(p, sport)
  return `${p.position}${p.positionalRank}`
}

/**
 * Same label, but with an ADP number as the fallback instead of nothing extra.
 * The picker has the width for it, and there the ADP is genuinely more useful
 * than a bare position. Basketball has no rank to show (A3) and the pill beside
 * the name already carries the position label, so a bare label here would just
 * repeat it: its "Rank" cell is always the ADP.
 */
export function posRankOrAdp(p: Ranked & Pick<PlayerRef, 'adp'>, sport: Sport): string {
  if (sport === 'nba') return `ADP ${Math.round(p.adp)}`
  return p.positionalRank === NO_RANK ? `ADP ${Math.round(p.adp)}` : `${p.position}${p.positionalRank}`
}
