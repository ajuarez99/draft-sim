// Shared client-side team-needs helper, used by both PlayerPicker (the "your
// team so far" strip + "fills a need" row tags) and DraftView (deriving the
// roster to pass in). Kept in one place so this logic can't exist twice and
// drift -- see claude/plan-review-B.md's "prop-wiring" and "shared helper"
// amendments.

import type { PlayerRef, PredictedPick } from './api'

/** Positions a standard FLEX slot accepts -- mirrors Position.isFlexEligible()
 * / FootballRules's FLEX-eligibility rule (RB/WR/TE) on the backend. Keep in
 * sync with backend/src/main/java/.../domain/Position.java if that ever
 * changes. */
const FLEX_ELIGIBLE = new Set(['RB', 'WR', 'TE'])

/** Slot strings this helper knows how to fill/badge. LeagueSettings on the
 * backend only recognizes literal "FLEX" today (SUPER_FLEX/REC_FLEX slot
 * strings exist in other league formats but aren't handled there either) --
 * anything else renders as a plain, always-open badge rather than being
 * silently treated as fillable. */
const RECOGNIZED_SLOTS = new Set(['QB', 'RB', 'WR', 'TE', 'K', 'DEF', 'FLEX'])

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
export function computeTeamNeeds(rosterPositions: string[], drafted: PlayerRef[]): SlotStatus[] {
  const starterSlots = rosterPositions.filter((s) => s !== 'BN' && s !== 'IR')

  const byPosition = new Map<string, PlayerRef[]>()
  for (const p of drafted) {
    const list = byPosition.get(p.position)
    if (list) list.push(p)
    else byPosition.set(p.position, [p])
  }
  for (const list of byPosition.values()) list.sort((a, b) => a.adp - b.adp)
  const nextIndex = new Map<string, number>()

  // Pass 1: dedicated (non-FLEX) slots, in template order.
  const results: SlotStatus[] = starterSlots.map((slot) => {
    if (slot === 'FLEX' || !RECOGNIZED_SLOTS.has(slot)) return { slot, player: null }
    const have = byPosition.get(slot) ?? []
    const i = nextIndex.get(slot) ?? 0
    nextIndex.set(slot, i + 1)
    return { slot, player: have[i] ?? null }
  })

  // Pass 2: whatever's left at flex-eligible positions, past their dedicated
  // slots, forms the FLEX pool -- best ADP first.
  const flexPool: PlayerRef[] = []
  for (const pos of FLEX_ELIGIBLE) {
    const have = byPosition.get(pos) ?? []
    const i = nextIndex.get(pos) ?? 0
    flexPool.push(...have.slice(i))
  }
  flexPool.sort((a, b) => a.adp - b.adp)

  let flexIdx = 0
  for (const r of results) {
    if (r.slot === 'FLEX') {
      r.player = flexPool[flexIdx] ?? null
      flexIdx++
    }
  }

  return results
}

/** Starting slots still open -- what a "fills a need" row tag checks against.
 * Unrecognized slot strings never appear here (never fillable). */
export function openPositions(needs: SlotStatus[]): Set<string> {
  const open = new Set<string>()
  for (const n of needs) {
    if (n.player == null && RECOGNIZED_SLOTS.has(n.slot)) open.add(n.slot)
  }
  return open
}

/** "Fills {slot}" tag text for a player of `position`, or null when drafting
 * them wouldn't fill any open starting slot -- a dedicated match, or a
 * FLEX-eligible position when only FLEX remains open. */
export function needLabel(position: string, open: Set<string>): string | null {
  if (open.has(position)) return `Fills ${position}`
  if (FLEX_ELIGIBLE.has(position) && open.has('FLEX')) return 'Fills FLEX'
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
