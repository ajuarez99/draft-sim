import type { PlayerRef } from './api'

// 999 is Sleeper's own "no rank" sentinel (BoardService's default, never null
// -- see api.ts). This module is the only place that knows that, so a new
// caller can't quietly render "RB999".
const NO_RANK = 999

type Ranked = Pick<PlayerRef, 'position' | 'positionalRank'>

/**
 * The compact badge: "RB4", falling back to the bare position when the rank is
 * unknown. For the board cell and the availability table, whose `.pos` pill is
 * ~30px wide and cannot hold anything longer.
 */
export function posRank(p: Ranked): string {
  return p.positionalRank === NO_RANK ? p.position : `${p.position}${p.positionalRank}`
}

/**
 * Same label, but with an ADP number as the fallback instead of nothing extra.
 * The picker has the width for it, and there the ADP is genuinely more useful
 * than a bare position.
 */
export function posRankOrAdp(p: Ranked & Pick<PlayerRef, 'adp'>): string {
  return p.positionalRank === NO_RANK ? `ADP ${Math.round(p.adp)}` : `${p.position}${p.positionalRank}`
}
