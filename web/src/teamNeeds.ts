// Shared client-side team-needs helper, used by both PlayerPicker (the "your
// team so far" strip + "fills a need" row tags) and DraftView (deriving the
// roster to pass in). Kept in one place so this logic can't exist twice and
// drift -- see claude/plan-review-B.md's "prop-wiring" and "shared helper"
// amendments.

import type { PlayerRef, PredictedPick, Sport } from './api'
import { POSITIONS_BY_SPORT } from './positions'

/**
 * Which roster slots each sport recognizes, and which positions each slot
 * accepts -- mirrors FootballRules.isEligible / BasketballRules.isEligible on
 * the backend (domain/sport/*.java) exactly, rather than inventing a second
 * model that can drift out from under it:
 *
 *   football: QB/RB/WR/TE/K/DEF are dedicated (one position each); FLEX
 *   accepts RB/WR/TE (Position.FLEX / isFlexEligible()).
 *   basketball: PG/SG/SF/PF/C are dedicated; G accepts PG/SG, F accepts
 *   SF/PF, UTIL accepts any of the five.
 *
 * A slot's own dedicated position is included in its own set for basketball's
 * PG/SG/SF/PF/C (so `SLOT_ELIGIBILITY[sport][pos].has(pos)` is always true for
 * a dedicated slot) -- keeps the "is this slot open to that position" check
 * below (needLabel/openPositions) uniform across dedicated and pooled slots.
 *
 * BN/IR aren't here: computeTeamNeeds filters those out of rosterPositions
 * before any of this runs (they accept anyone and are never "a need").
 */
const SLOT_ELIGIBILITY: Record<Sport, Record<string, Set<string>>> = {
  nfl: {
    QB: new Set(['QB']),
    RB: new Set(['RB']),
    WR: new Set(['WR']),
    TE: new Set(['TE']),
    K: new Set(['K']),
    DEF: new Set(['DEF']),
    FLEX: new Set(['RB', 'WR', 'TE']),
  },
  nba: {
    PG: new Set(['PG']),
    SG: new Set(['SG']),
    SF: new Set(['SF']),
    PF: new Set(['PF']),
    C: new Set(['C']),
    G: new Set(['PG', 'SG']),
    F: new Set(['SF', 'PF']),
    UTIL: new Set(['PG', 'SG', 'SF', 'PF', 'C']),
  },
}

/**
 * Pooled slots, per sport, in "most specific first" order -- the order
 * needLabel walks when a position matches more than one open pooled slot
 * (a basketball PG matches both G and UTIL; report the more specific one).
 * Everything not listed here for a sport is a dedicated slot (one of that
 * sport's own Position codes, from positions.ts).
 */
const POOLED_SLOTS: Record<Sport, readonly string[]> = {
  nfl: ['FLEX'],
  nba: ['G', 'F', 'UTIL'],
}

export type SlotStatus = { slot: string; player: PlayerRef | null }

/**
 * Assigns the user's drafted players to the league's starting roster_positions
 * template (BN/IR excluded -- this is starting-lineup need, matching
 * LeagueSettings.dedicatedStarters()/.flexSlots() on the backend, not
 * full-roster depth).
 *
 * FLEX tie-break, decided explicitly (see plan-review-B.md's FLEX-overflow
 * gap): dedicated slots at a position are filled by that position's
 * best-ADP-first players (RosterState.at() sorts the same way before
 * FootballRules.startingLineupValue() reads it); whatever's left over at a
 * flex-eligible position spills into a FLEX pool, again best-ADP-first, which
 * is exactly the greedy-by-value order startingLineupValue() fills FLEX with
 * on the backend. This matches the engine rather than inventing a different
 * (e.g. draft-order) tie-break.
 *
 * An unrecognized slot string (SUPER_FLEX etc.) always renders open/null --
 * informational only, never fillable, per the same gap's resolution.
 */
export function computeTeamNeeds(sport: Sport, rosterPositions: string[], drafted: PlayerRef[]): SlotStatus[] {
  const eligibility = SLOT_ELIGIBILITY[sport]
  const dedicatedPositions = new Set<string>(POSITIONS_BY_SPORT[sport])
  const starterSlots = rosterPositions.filter((s) => s !== 'BN' && s !== 'IR')

  const byPosition = new Map<string, PlayerRef[]>()
  for (const p of drafted) {
    const list = byPosition.get(p.position)
    if (list) list.push(p)
    else byPosition.set(p.position, [p])
  }
  for (const list of byPosition.values()) list.sort((a, b) => a.adp - b.adp)
  const nextIndex = new Map<string, number>()

  // Pass 1: dedicated slots (one of this sport's own positions), in template
  // order. Pooled slots (FLEX; G/F/UTIL) and unrecognized slot strings are
  // left null here and, for pooled ones, resolved in pass 2 below.
  const results: SlotStatus[] = starterSlots.map((slot) => {
    if (!dedicatedPositions.has(slot)) return { slot, player: null }
    const have = byPosition.get(slot) ?? []
    const i = nextIndex.get(slot) ?? 0
    nextIndex.set(slot, i + 1)
    return { slot, player: have[i] ?? null }
  })

  // Pass 2: pooled slots, in template order -- each draws the best
  // (lowest-ADP) remaining eligible player not already claimed by an earlier
  // dedicated or pooled slot. `usedIds` generalizes the old FLEX-only "past
  // its dedicated slots" cutoff (nextIndex) to any number of pooled slot
  // kinds with overlapping eligibility (basketball's G/F/UTIL all draw from
  // overlapping position sets, unlike football's single FLEX pool) -- a
  // player already seated by a dedicated slot, or by an earlier pooled slot
  // in template order, can't be drawn again by a later one.
  const usedIds = new Set<number>()
  for (const r of results) if (r.player) usedIds.add(r.player.id)

  for (const r of results) {
    if (dedicatedPositions.has(r.slot)) continue
    const rule = eligibility[r.slot]
    if (!rule) continue // genuinely unrecognized slot string -- stays open/null
    let best: PlayerRef | null = null
    for (const [pos, list] of byPosition) {
      if (!rule.has(pos)) continue
      for (const p of list) {
        if (usedIds.has(p.id)) continue
        if (best == null || p.adp < best.adp) best = p
      }
    }
    if (best) {
      r.player = best
      usedIds.add(best.id)
    }
  }

  return results
}

/** Starting slots still open -- what a "fills a need" row tag checks against.
 * Unrecognized slot strings never appear here (never fillable). */
export function openPositions(sport: Sport, needs: SlotStatus[]): Set<string> {
  const recognized = new Set(Object.keys(SLOT_ELIGIBILITY[sport]))
  const open = new Set<string>()
  for (const n of needs) {
    if (n.player == null && recognized.has(n.slot)) open.add(n.slot)
  }
  return open
}

/**
 * "Fills {slot}" tag text for a player of `position`, or null when drafting
 * them wouldn't fill any open starting slot. Checks the dedicated slot first,
 * then this sport's pooled slots in "most specific" order (POOLED_SLOTS) --
 * a basketball PG matching both an open G and an open UTIL reports "Fills G",
 * the more informative of the two. Slot-named throughout, so basketball says
 * "Fills G"/"Fills UTIL" rather than football's "Fills FLEX" leaking in.
 */
export function needLabel(sport: Sport, position: string, open: Set<string>): string | null {
  if (open.has(position)) return `Fills ${position}`
  const eligibility = SLOT_ELIGIBILITY[sport]
  for (const slot of POOLED_SLOTS[sport]) {
    if (open.has(slot) && eligibility[slot]?.has(position)) return `Fills ${slot}`
  }
  return null
}

/**
 * The user's own roster so far, in pick order: SimulationResult.myPicks
 * filtered to picks before pausedAt, preferring userPicks[pickNo] (a
 * confirmed choice from reactive-resimulation) over the board's own resolved
 * player -- the same precedence choosePick() already uses to build
 * startState. No new data; everything here already ships in SimulationResult.
 */
export function draftedSoFar(
  myPicks: number[],
  pausedAt: number,
  board: PredictedPick[],
  userPicks: Record<number, PlayerRef>,
): PlayerRef[] {
  const byPickNo = new Map(board.map((p) => [p.pickNo, p]))
  const out: PlayerRef[] = []
  for (const pickNo of myPicks) {
    if (pickNo >= pausedAt) continue
    const chosen = userPicks[pickNo] ?? byPickNo.get(pickNo)?.player
    if (chosen) out.push(chosen)
  }
  return out
}
