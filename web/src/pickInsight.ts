/**
 * Pure derivations behind the live room's pick card (spec 012).
 *
 * Everything in this file that is exported today is a FACT about picks that
 * have already landed: no simulation is read, so none of it can be stale or
 * wait on one. The projection-dependent fields (model share, likely next) exist
 * on `PickInsight` with their "nothing yet" values and are filled in by later
 * tasks.
 */

import type { Candidate, PlayerRef, PredictedPick, RealPick, Seat, SimulationResult, Sport } from './api'
import { INSIGHT } from './insightConstants'
import { RUNNABLE_BY_SPORT } from './pickRun'
import { eligiblePositions } from './positions'
import { pickNoAt } from './snake'
import { computeTeamNeeds, fitSlot } from './teamNeeds'

/**
 * The open starting slot this player would fill for a roster that already
 * holds `priorRoster`, or null for "Depth". The ONE place that asks the
 * question: the feed's fit clause and the card both call it, so they cannot
 * disagree about whether a pick filled anything.
 */
export function fillsFor(
  sport: Sport,
  rosterPositions: string[],
  priorRoster: PlayerRef[],
  player: PlayerRef,
): string | null {
  return fitSlot(sport, player, computeTeamNeeds(sport, rosterPositions, priorRoster))
}

/** Starting slots still empty once `player` joins `priorRoster`, in roster order. */
export function openAfter(
  sport: Sport,
  rosterPositions: string[],
  priorRoster: PlayerRef[],
  player: PlayerRef,
): string[] {
  return computeTeamNeeds(sport, rosterPositions, [...priorRoster, player])
    .filter((n) => n.player == null)
    .map((n) => n.slot)
}

/**
 * One or two plain sentences. Built only from `fills`, `openAfter` and which of
 * the sport's runnable positions this roster has no player at yet (DM-5): every
 * clause maps to a field, and there is no free text to be wrong. `rosterSoFar`
 * includes the player just taken.
 */
export function summarize(
  sport: Sport,
  fills: string | null,
  open: string[],
  rosterSoFar: PlayerRef[],
): string {
  const parts: string[] = []
  parts.push(fills ? `Fills ${fills}.` : 'Depth pick, no open starting slot.')
  if (open.length === 0) {
    parts.push('Roster complete.')
    return parts.join(' ')
  }
  parts.push(`Still open: ${open.join(', ')}.`)
  // Positions already named above (an open QB slot) would be said twice.
  // "Has a PG" means someone on the roster is ELIGIBLE at PG (spec 025 T020): a
  // PG/SG counts for both, so "No SG yet" is never said while he is on the roster.
  const have = new Set<string>(rosterSoFar.flatMap((p) => eligiblePositions(p, sport)))
  const missing = [...RUNNABLE_BY_SPORT[sport]].filter((pos) => !have.has(pos) && !open.includes(pos))
  if (missing.length > 0) parts.push(`No ${missing.join(', ')} yet.`)
  return parts.join(' ')
}

export type { Candidate } from './api'

export type LikelyNext =
  | { state: 'ready'; top: Candidate; rest: Candidate[]; wideOpen: boolean }
  | { state: 'updating' }
  | { state: 'busy' }
  | { state: 'none'; reason: string }

export type ModelShare = { share: number; bound: 'exact' | 'under'; asOfPick: number }

export type PickInsight = {
  pick: RealPick
  seat: Seat | undefined
  /**
   * False when the fit facts cannot be trusted: the league's roster template is
   * missing, or a pick belonging to this seat is absent from the landed list
   * (review F2). The card then leaves the fit rows out rather than state a
   * roster it assembled from a partial history.
   */
  fitKnown: boolean
  fills: string | null
  openAfter: string[]
  rosterComplete: boolean
  summary: string
  /** Positive = taken before ADP (a reach). Null when the player has no ADP. */
  adpDelta: number | null
  modelShare: ModelShare | null
  surprise: boolean
  nextPickNo: number | null
  likelyNext: LikelyNext
  /** Null when the seat is unknown -- there is nothing to say about a seat we cannot find. */
  provenanceLabel: string | null
  scarcityLine: string | null
}

function provenanceLabelFor(seat: Seat | undefined): string | null {
  if (!seat) return null
  switch (seat.provenance) {
    case 'STATED':
      return 'stated by you'
    case 'NEUTRAL':
      return 'no history — neutral seat'
    // BLENDED is fitted history with a stated nudge on top; it is still fitted.
    default:
      return `fitted from ${seat.draftsObserved} ${seat.draftsObserved === 1 ? 'draft' : 'drafts'}`
  }
}

/**
 * Everything about a landed pick that needs only landed picks. `opts.seatComplete`
 * is the per-seat completeness of the landed list (LiveDraftView's seatComplete);
 * it defaults true for callers that have already established it.
 */
export function buildFactInsight(
  pick: RealPick,
  landed: RealPick[],
  seats: Seat[],
  sport: Sport,
  rosterPositions: string[],
  opts: { seatComplete?: boolean } = {},
): PickInsight {
  const priorRoster = landed
    .filter((p) => p.slot === pick.slot && p.pickNo < pick.pickNo)
    .map((p) => p.player)
  const fitKnown = rosterPositions.length > 0 && (opts.seatComplete ?? true)
  const fills = fitKnown ? fillsFor(sport, rosterPositions, priorRoster, pick.player) : null
  const open = fitKnown ? openAfter(sport, rosterPositions, priorRoster, pick.player) : []
  const seat = seats.find((s) => s.slot === pick.slot)
  return {
    pick,
    seat,
    fitKnown,
    fills,
    openAfter: open,
    rosterComplete: fitKnown && open.length === 0,
    summary: fitKnown ? summarize(sport, fills, open, [...priorRoster, pick.player]) : '',
    adpDelta: adpDelta(pick.player, pick.pickNo),
    modelShare: null,
    surprise: false,
    nextPickNo: null,
    likelyNext: { state: 'none', reason: 'no projection yet' },
    provenanceLabel: provenanceLabelFor(seat),
    scarcityLine: null,
  }
}

/**
 * round(adp) - pickNo. POSITIVE = taken before ADP (a reach), the same sign as
 * `reachBias`; negative = he lasted past where the board had him. Null when the
 * player has no ADP: 999 is Sleeper's "no rank" sentinel (and what
 * fallbackPlayerRef gives an off-board player), and a missing/NaN value must not
 * turn into a confident "reach" of 900 picks.
 */
export function adpDelta(player: Pick<PlayerRef, 'adp'>, pickNo: number): number | null {
  const adp = player.adp
  if (adp == null || !Number.isFinite(adp) || adp >= 999) return null
  return Math.round(adp) - pickNo
}

/** Pick numbers in 1..picksMade with no entry in `landed` -- holes in what we know. */
export function missingPickNos(landed: { pickNo: number }[], picksMade: number): number[] {
  const have = new Set(landed.map((p) => p.pickNo))
  const out: number[] = []
  for (let n = 1; n <= picksMade; n++) if (!have.has(n)) out.push(n)
  return out
}

/**
 * The explicit startState for a projection run, and the pick it is "as of"
 * (spec 012 R2, DM-1). All-or-nothing (review F2): only when no pick up to the
 * highest landed one is missing AND every landed pick carries a sleeperId do we
 * send a prefix, stamping asOfPick with the highest pickNo sent. Otherwise no
 * startState at all (never an empty object -- the backend then replays its own
 * DB picks) and asOfPick is null: we cannot say what the run was conditioned on.
 */
export function buildStartState(
  landed: { pickNo: number; player: { sleeperId?: string | null } }[],
  missing: number[],
): { startState?: Record<number, string>; asOfPick: number | null } {
  if (landed.length === 0 || missing.length > 0) return { asOfPick: null }
  const startState: Record<number, string> = {}
  let max = 0
  for (const p of landed) {
    if (!p.player.sleeperId) return { asOfPick: null }
    startState[p.pickNo] = p.player.sleeperId
    max = Math.max(max, p.pickNo)
  }
  return { startState, asOfPick: max }
}

/**
 * True when none of the missing picks belonged to `slot`, so that seat's roster
 * is whole even though the list as a whole has a hole (review F2). Ownership is
 * snake.ts's pickNoAt for the pick's own round, so a reversal round is honoured.
 */
export function seatComplete(
  slot: number,
  missing: number[],
  teams: number,
  reversalRound: number,
): boolean {
  if (teams <= 0) return false
  return !missing.some((n) => pickNoAt(Math.ceil(n / teams), slot, teams, reversalRound) === n)
}

/** "14 before ADP" / "9 past ADP" / "On ADP" (|delta| <= 1); null for no ADP. */
export function adpLabel(delta: number | null): string | null {
  if (delta == null) return null
  if (Math.abs(delta) <= 1) return 'On ADP'
  return delta > 0 ? `${delta} before ADP` : `${-delta} past ADP`
}

// ---- Projection-dependent figures (spec 012 T024/T025) ---------------------

/** The reason a card carries when the seat has no pick left. The card keys off it. */
export const NO_PICKS_LEFT = 'no picks left'

/** What a projection run left behind; structurally LiveDraftView's StampedProjection. */
export type StampLike = { result?: SimulationResult; asOfPick: number | null; busy: boolean }

/**
 * That seat's next pick after `afterPickNo`, in snake order (reversal round
 * honoured via snake.ts), or null when the draft holds none for it.
 */
export function nextPickFor(
  slot: number,
  afterPickNo: number,
  teams: number,
  rounds: number,
  reversalRound: number,
): number | null {
  if (teams <= 0) return null
  for (let round = Math.ceil(afterPickNo / teams); round <= rounds; round++) {
    const n = pickNoAt(round, slot, teams, reversalRound)
    if (n > afterPickNo) return n
  }
  return null
}

/**
 * The cell's per-cell marginal top four, most-voted first. `cell.player` is the
 * globally ASSIGNED player -- when `isModal` is false he is NOT the most-voted
 * here, so the order comes from the shares, never from which field held whom.
 */
export function cellCandidates(cell: PredictedPick): Candidate[] {
  return [{ player: cell.player, probability: cell.probability }, ...cell.alternatives].sort(
    (a, b) => b.probability - a.probability,
  )
}

/**
 * Who this manager probably takes at `nextPickNo`, read from the newest
 * projection conditioned on at least `pick` (DM-2). Anyone already on the
 * landed list is dropped from the candidates -- a projection is never allowed to
 * name a player who is gone, however recent the stamp looks.
 */
export function likelyNext(
  stamps: readonly StampLike[],
  pick: Pick<RealPick, 'pickNo'>,
  nextPickNo: number | null,
  inFlightAsOf: number | null,
  landedPlayerIds: ReadonlySet<number>,
): LikelyNext {
  if (nextPickNo == null) return { state: 'none', reason: NO_PICKS_LEFT }
  const covers = (s: StampLike) => s.asOfPick != null && s.asOfPick >= pick.pickNo
  for (let i = stamps.length - 1; i >= 0; i--) {
    const s = stamps[i]
    if (s.busy || !s.result || !covers(s)) continue
    const cell = s.result.board.find((c) => c.pickNo === nextPickNo)
    const cands = cell ? cellCandidates(cell).filter((c) => !landedPlayerIds.has(c.player.id)) : []
    if (cands.length === 0) continue
    const [top, ...others] = cands
    return { state: 'ready', top, rest: others.slice(0, 2), wideOpen: top.probability < INSIGHT.WIDE_OPEN_SHARE }
  }
  if (inFlightAsOf != null && inFlightAsOf >= pick.pickNo) return { state: 'updating' }
  const newest = stamps[stamps.length - 1]
  // A refused run from before this pick says nothing about it.
  if (newest?.busy && (newest.asOfPick == null || newest.asOfPick >= pick.pickNo)) return { state: 'busy' }
  return { state: 'none', reason: 'projection not back yet' }
}

/**
 * What the model gave this player at this pick, from the newest projection made
 * BEFORE it (DM-3) -- a projection that already knew the pick would be grading
 * itself. Not among the cell's shown candidates means "under" the smallest
 * share shown, not zero.
 */
/**
 * The assigned player plus MonteCarloRunner.ALTERNATIVES (3) runners-up: the
 * most a PredictedPick ever carries. Mirrors the backend constant.
 */
const CELL_CANDIDATES = 4

export function modelShare(stamps: readonly StampLike[], pick: RealPick): ModelShare | null {
  for (let i = stamps.length - 1; i >= 0; i--) {
    const s = stamps[i]
    if (s.busy || !s.result || s.asOfPick == null || s.asOfPick >= pick.pickNo) continue
    const cell = s.result.board.find((c) => c.pickNo === pick.pickNo)
    if (!cell) continue
    const cands = cellCandidates(cell)
    const hit = cands.find((c) => c.player.id === pick.player.id)
    if (hit) return { share: hit.probability, bound: 'exact', asOfPick: s.asOfPick }
    // A cell carries every player any run took there, up to CELL_CANDIDATES.
    // Fewer than that means the list is the whole tally, so an absent player
    // went there in no run at all: exactly 0, not "under" the smallest shown.
    if (cands.length < CELL_CANDIDATES) return { share: 0, bound: 'exact', asOfPick: s.asOfPick }
    return { share: Math.min(...cands.map((c) => c.probability)), bound: 'under', asOfPick: s.asOfPick }
  }
  return null
}

/** A pick the model thought unlikely: under the threshold, or at most it for an "under" bound. */
export function isSurprise(ms: ModelShare | null): boolean {
  if (ms == null) return false
  return ms.bound === 'exact' ? ms.share < INSIGHT.SURPRISE_SHARE : ms.share <= INSIGHT.SURPRISE_SHARE
}

/** "as of pick N" only when the projection is more than one pick stale; else null. */
export function asOfLabel(ms: ModelShare | null, pickNo: number): string | null {
  if (ms == null || ms.asOfPick >= pickNo - 1) return null
  return `as of pick ${ms.asOfPick}`
}
