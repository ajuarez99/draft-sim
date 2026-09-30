/**
 * Position scarcity for the live room (spec 012, US4).
 *
 * "Starter pool" = the board's top S players, S = teams x starters per team.
 * Everything here is a read over picks that have already landed plus, optionally,
 * one post-pick projection. Nothing waits on a simulation: leftNow and running
 * are facts; only expectedAtNext is model output, and it is gated (R7).
 */

import type { PlayerRef, RealPick, SimulationResult, Sport } from './api'
import { SNAPSHOT_DEPTH_MIRROR } from './insightConstants'
import { RUNNABLE_BY_SPORT, positionRun, type PositionRun } from './pickRun'

export type PositionScarcity = {
  position: string
  /** Players at this position among the first S of the pool. */
  poolSize: number
  /** Of those, how many have no landed pick. */
  leftNow: number
  /** Expected survivors at my next pick; null when the gate is closed. */
  expectedAtNext: number | null
  running: boolean
}

export type ScarcityResult = {
  S: number
  teams: number
  startersPerTeam: number
  /** "Starter pool: the board's top S (teams x starters)" with the real numbers in. */
  definition: string
  rows: PositionScarcity[]
  /** The feed's own run, so the meter can use its wording ("4 of the last 6 were RB"). */
  run: PositionRun | null
  /** True when the depth gate (not a missing seat/projection) is what kept expectedAtNext null. */
  gatedByDepth: boolean
  /** Pick number from which the gate can open; see projectedFromPick. */
  projectedFrom: number
}

export type ScarcityInput = {
  sport: Sport
  /** Board order, best first. */
  pool: PlayerRef[]
  teams: number
  startersPerTeam: number
  landed: RealPick[]
  postPickResult: SimulationResult | null
  myNextPick: number | null
  slotKnown: boolean
}

/**
 * The pick number from which the expected-count gate can open, ASSUMING one
 * starter-pool player leaves the pool per pick (an assumption, not a measurement:
 * early picks can take players from outside the pool head). The gate opens once
 * at most SNAPSHOT_DEPTH_MIRROR starter-pool players remain, i.e. after
 * S - SNAPSHOT_DEPTH_MIRROR picks, so the first pick with a projection is that
 * plus one. Minimum 1.
 */
export function projectedFromPick(S: number): number {
  return Math.max(1, S - SNAPSHOT_DEPTH_MIRROR + 1)
}

export function positionScarcity(input: ScarcityInput): ScarcityResult {
  const { sport, pool, teams, startersPerTeam, landed, postPickResult, myNextPick, slotKnown } = input
  const S = teams * startersPerTeam
  const head = pool.slice(0, S)
  const taken = new Set(landed.map((p) => p.player.id))
  const undrafted = head.filter((p) => !taken.has(p.id))

  const haveInputs = slotKnown && postPickResult != null && myNextPick != null
  const gateOpen = haveInputs && undrafted.length <= SNAPSHOT_DEPTH_MIRROR
  const gatedByDepth = haveInputs && undrafted.length > SNAPSHOT_DEPTH_MIRROR

  const survival = new Map<number, Record<string, number>>()
  if (gateOpen && postPickResult) for (const r of postPickResult.availability) survival.set(r.player.id, r.survivalByPick)

  const run = positionRun(landed.map((p) => p.player), 6, 4, sport)
  const rows: PositionScarcity[] = [...RUNNABLE_BY_SPORT[sport]].map((position) => {
    const atPos = head.filter((p) => p.position === position)
    const left = atPos.filter((p) => !taken.has(p.id))
    let expectedAtNext: number | null = null
    if (gateOpen) {
      expectedAtNext = left.reduce((sum, p) => sum + (survival.get(p.id)?.[String(myNextPick)] ?? 0), 0)
    }
    return { position, poolSize: atPos.length, leftNow: left.length, expectedAtNext, running: run?.position === position }
  })

  return {
    S,
    teams,
    startersPerTeam,
    definition: `Starter pool: the board's top ${S} (${teams} teams × ${startersPerTeam} starters)`,
    rows,
    run,
    gatedByDepth,
    projectedFrom: projectedFromPick(S),
  }
}
