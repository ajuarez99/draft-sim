// Shared client-side team-needs helper, used by both PlayerPicker (the "your
// team so far" strip + "fills a need" row tags) and DraftView (deriving the
// roster to pass in). Kept in one place so this logic can't exist twice and
// drift -- see claude/plan-review-B.md's "prop-wiring" and "shared helper"
// amendments.

import type { PlayerRef, PredictedPick, Sport } from './api'
import { canJoin, lineupFromSeats, NBA_SLOT_ELIGIBILITY, seatLineup } from './lineup'
import { eligiblePositions, POSITIONS_BY_SPORT } from './positions'

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
  // The one shared copy lives in lineup.ts (spec 025 US3), next to the matcher that uses it.
  nba: NBA_SLOT_ELIGIBILITY,
}

/**
 * Football's pooled slots in "most specific first" order. Basketball no longer
 * walks a list: lineup.canJoin orders open slots by how few positions they
 * accept (spec 025 A1), which gives G/F before UTIL for the real template.
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
  // Basketball: the backend's matroid lineup (lineup.ts, pinned by the parity
  // fixture). The two-pass greedy below is football-only now -- its single
  // position per player is exactly right there, and it cannot seat a PG/SG at
  // SG so that a later pure PG can take PG.
  if (sport === 'nba') {
    const lineup = seatLineup(rosterPositions, drafted)
    return lineup.slots.map((slot, i) => ({ slot, player: lineup.seats[i] }))
  }
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

/** A player's fit: the open slot's name and its index in the needs list. */
type Fit = { slot: string; index: number }

/**
 * Builds the "which open starting slot would this player fill" function for one
 * roster (`needs`), so a list of hundreds of rows reuses one lineup.
 *
 * Football: the player's first position -- checks the dedicated slot, then the
 * pooled slots in POOLED_SLOTS order (today's behaviour, unchanged).
 * Basketball: lineup.canJoin -- he fills a need if he can be seated by shifting
 * seated players around, and the slot named is the one that goes from open to
 * filled when the lineup is re-seated with him (code-review B1), so it always
 * agrees with the team strip after the pick.
 */
function fitterFor(sport: Sport, needs: SlotStatus[]): (player: PlayerRef) => Fit | null {
  if (sport === 'nba') {
    const lineup = lineupFromSeats(needs.map((n) => n.slot), needs.map((n) => n.player))
    return (player) => {
      const r = canJoin(lineup, player)
      return r.ok && r.slot != null ? { slot: r.slot, index: r.index } : null
    }
  }
  const open = openPositions(sport, needs)
  const eligibility = SLOT_ELIGIBILITY[sport]
  const firstOpen = (slot: string) => needs.findIndex((n) => n.slot === slot && n.player == null)
  return (player) => {
    const position = eligiblePositions(player, sport)[0]
    if (position == null) return null
    if (open.has(position)) return { slot: position, index: firstOpen(position) }
    for (const slot of POOLED_SLOTS[sport]) {
      if (open.has(slot) && eligibility[slot]?.has(position)) return { slot, index: firstOpen(slot) }
    }
    return null
  }
}

/**
 * The open starting slot this player would fill, or null when drafting him
 * wouldn't fill one.
 *
 * The one definition of "does this player fill a need": `needLabel` below and
 * `fitSlot` further down are both wording over this, so the row tag in the
 * picker and the fit clause on a live pick announcement can never disagree
 * about whether a pick filled anything.
 */
export function openSlotFor(sport: Sport, player: PlayerRef, needs: SlotStatus[]): string | null {
  return fitterFor(sport, needs)(player)?.slot ?? null
}

/**
 * "Fills {slot}" tag text, or null. Slot-named throughout, so basketball says
 * "Fills G"/"Fills UTIL" rather than football's "Fills FLEX" leaking in.
 */
export function needLabel(sport: Sport, player: PlayerRef, needs: SlotStatus[]): string | null {
  const slot = openSlotFor(sport, player, needs)
  return slot == null ? null : `Fills ${slot}`
}

/**
 * `needLabel` for one roster as a function of the player alone -- what the
 * draft-room lists take (AvailabilityPanel's `fitFor`, PlayerPicker,
 * OnTheClockPickInput). One lineup per roster and one answer per player: the
 * live room re-renders every second, so callers memoize this on `needs`.
 */
export function makeFitFor(sport: Sport, needs: SlotStatus[]): (player: PlayerRef) => string | null {
  const fit = fitterFor(sport, needs)
  const cache = new Map<number, string | null>()
  return (player) => {
    if (cache.has(player.id)) return cache.get(player.id) ?? null
    const f = fit(player)
    const label = f == null ? null : `Fills ${f.slot}`
    cache.set(player.id, label)
    return label
  }
}

/**
 * The same answer as `openSlotFor`, numbered when the template has more than
 * one slot of that name: "RB2" for a team whose RB1 is already seated, plain
 * "TE" or "FLEX" where there is only one.
 *
 * The number is the position of the slot *within the template*, not a count of
 * what the team has -- a team holding one RB fills RB2 next, and a team holding
 * three fills FLEX, because the third RB is already seated there by
 * computeTeamNeeds. That is the whole reason this reads the needs list rather
 * than counting the roster: the seating rule already exists and this must not
 * become a second one.
 *
 * Returns null when nothing is open for that position, which the caller should
 * render as depth rather than as a need -- saying "fills nothing" is louder
 * than it deserves to be.
 */
export function fitSlot(sport: Sport, player: PlayerRef, needs: SlotStatus[]): string | null {
  const fit = fitterFor(sport, needs)(player)
  if (fit == null) return null
  const sameName = needs.map((n, i) => ({ n, i })).filter(({ n }) => n.slot === fit.slot)
  if (sameName.length <= 1) return fit.slot
  return `${fit.slot}${sameName.findIndex(({ i }) => i === fit.index) + 1}`
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
