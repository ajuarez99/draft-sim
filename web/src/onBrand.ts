/**
 * "On brand" reads for the live room (spec 012, US5): is each manager drafting
 * like their profile says? Verdicts exist only where there is something honest
 * to compare; otherwise a reason string says why not.
 */

import type { Provenance, RealPick, Seat } from './api'
import { INSIGHT } from './insightConstants'
import { adpDelta } from './pickInsight'

export type Verdict = 'on' | 'off' | null

export type OnBrandRead = {
  slot: number
  manager: string
  avatarId: string | null
  provenance: Provenance
  picks: number
  reachSoFar: number | null
  profileReach: number
  reachVerdict: Verdict
  reachReason: string | null
  /** "vs board ADP" for fitted/blended profiles, "vs what you entered" for stated. */
  reachLabel: string
  lean: { position: string; tilt: number } | null
  leanShare: number
  roomShare: number
  leanVerdict: Verdict
  leanReason: string | null
  mix: Record<string, number>
}

function leanFor(seat: Seat): { position: string; tilt: number } | null {
  // Tilt is only ever fitted; a stated or neutral seat never has a lean.
  if (seat.provenance === 'STATED' || seat.provenance === 'NEUTRAL') return null
  let best: { position: string; tilt: number } | null = null
  for (const [position, tilt] of Object.entries(seat.positionalTilt)) {
    if (tilt >= INSIGHT.LEAN_TILT && (best == null || tilt > best.tilt)) best = { position, tilt }
  }
  return best
}

const sign = (n: number) => (n > 0 ? 1 : n < 0 ? -1 : 0)

/**
 * Deliberately counts by `player.position` -- the first listed position, which for NBA is
 * Sleeper's own primary position (spec 026 moves it to the front at ingest) -- and NOT by eligibility like
 * the rest of the draft room (spec 025). The lean it is compared against is the manager's
 * fitted positional tilt, which ProfileService fits from that same first position, so
 * switching only this side to eligibility would compare two different definitions.
 */
export function onBrandReads(seats: Seat[], landed: RealPick[]): OnBrandRead[] {
  const roomCounts = new Map<string, number>()
  for (const p of landed) roomCounts.set(p.player.position, (roomCounts.get(p.player.position) ?? 0) + 1)

  return seats.map((seat) => {
    const mine = landed.filter((p) => p.slot === seat.slot)
    const picks = mine.length
    const mix: Record<string, number> = {}
    for (const p of mine) mix[p.player.position] = (mix[p.player.position] ?? 0) + 1

    const deltas = mine.map((p) => adpDelta(p.player, p.pickNo)).filter((d): d is number => d != null)
    const reachSoFar = deltas.length ? deltas.reduce((a, b) => a + b, 0) / deltas.length : null
    const profileReach = seat.reachBias
    const enough = picks >= INSIGHT.MIN_PICKS_FOR_VERDICT

    let reachVerdict: Verdict = null
    let reachReason: string | null = null
    if (seat.provenance === 'NEUTRAL') reachReason = 'no history'
    else if (!enough) reachReason = 'too early'
    else if (reachSoFar == null) reachReason = 'no ADP on these picks'
    else {
      const tol = INSIGHT.REACH_TOLERANCE
      const bothNear = Math.abs(profileReach) <= tol && Math.abs(reachSoFar) <= tol
      // A shared sign only counts as agreement when both numbers are outside the
      // noise band: +0.7 against a +10 reacher is "not reaching", not on brand
      // (found live, T042; amended from "same sign" in data-model.md).
      const bothBeyond = Math.abs(profileReach) > tol && Math.abs(reachSoFar) > tol
      reachVerdict = bothNear || (bothBeyond && sign(profileReach) === sign(reachSoFar)) ? 'on' : 'off'
    }

    const lean = leanFor(seat)
    const leanCount = lean ? (mix[lean.position] ?? 0) : 0
    const leanShare = lean && picks > 0 ? leanCount / picks : 0
    const roomShare = lean && landed.length > 0 ? (roomCounts.get(lean.position) ?? 0) / landed.length : 0
    let leanVerdict: Verdict = null
    let leanReason: string | null = null
    if (!lean) leanReason = seat.provenance === 'FITTED' || seat.provenance === 'BLENDED' ? 'no lean' : 'lean not fitted'
    else if (!enough) leanReason = 'too early'
    else leanVerdict = leanShare > roomShare ? 'on' : 'off'

    return {
      slot: seat.slot,
      manager: seat.manager,
      avatarId: seat.avatarId,
      provenance: seat.provenance,
      picks,
      reachSoFar,
      profileReach,
      reachVerdict,
      reachReason,
      reachLabel: seat.provenance === 'STATED' ? 'vs what you entered' : 'vs board ADP',
      lean,
      leanShare,
      roomShare,
      leanVerdict,
      leanReason,
      mix,
    }
  })
}
