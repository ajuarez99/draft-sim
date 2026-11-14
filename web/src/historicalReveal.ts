import type { HistoricalGameState, HistoricalResult } from './api'
import { isOldFormat, ordinal, outcomeHeadline } from './historicalCopy'

/**
 * Spec 071: the season played back one step at a time, as a pure function of the frozen result. Nothing here is
 * computed that the results page does not also print: the week scores come from `weeks`, the playoff rounds from
 * `playoffs`, and the record this counts up to must equal the fan's standings row (the tests pin that).
 */

export type WeekStep = {
  kind: 'week'
  week: number
  opponentSlot: number
  you: number
  them: number
  /** From the fan's side. More points is a win. */
  outcome: 'W' | 'L' | 'T'
  /** The running record after this week. */
  wins: number
  losses: number
  ties: number
}
export type SeedStep = {
  kind: 'seed'
  /** Regular-season place, 1 = first. */
  place: number
  /** The fan's playoff seed, or null when they missed out or there were no playoffs. */
  seed: number | null
  /** False when the season was too short for playoffs, or the result is old-format. */
  playoffsHeld: boolean
}
export type PlayoffStep = {
  kind: 'playoff'
  name: 'ROUND_ONE' | 'SEMIFINAL' | 'FINAL'
  week: number
  /** A bye has no opponent and no scores. */
  bye: boolean
  opponentSlot: number | null
  you: number | null
  them: number | null
  won: boolean
  /** Level on points, so the higher seed went through. */
  decidedBySeed: boolean
}
export type FinalStep = { kind: 'final'; headline: string; champion: boolean }
export type RevealStep = WeekStep | SeedStep | PlayoffStep | FinalStep

export function seasonSteps(game: Pick<HistoricalGameState, 'userSlot' | 'teams'>, result: HistoricalResult): RevealStep[] {
  const me = game.userSlot
  const old = isOldFormat(result)
  const steps: RevealStep[] = []

  const regularWeeks = result.format?.regularSeasonWeeks ?? Infinity
  let wins = 0
  let losses = 0
  let ties = 0
  for (const w of [...result.weeks].sort((a, b) => a.week - b.week)) {
    if (w.phase === 'PLAYOFF' || w.week > regularWeeks) continue
    const m = w.matchups.find((x) => x.homeSlot === me || x.awaySlot === me)
    if (!m) continue
    const home = m.homeSlot === me
    const you = home ? m.homePoints : m.awayPoints
    const them = home ? m.awayPoints : m.homePoints
    const outcome = you > them ? 'W' : you < them ? 'L' : 'T'
    if (outcome === 'W') wins++
    else if (outcome === 'L') losses++
    else ties++
    steps.push({ kind: 'week', week: w.week, opponentSlot: home ? m.awaySlot : m.homeSlot, you, them, outcome, wins, losses, ties })
  }

  const row = result.standings.find((s) => s.slot === me)
  const playoffs = old ? null : (result.playoffs ?? null)
  steps.push({
    kind: 'seed',
    place: row?.place ?? result.userFinish,
    seed: playoffs?.seeds.find((s) => s.slot === me)?.seed ?? null,
    playoffsHeld: playoffs != null,
  })

  for (const r of [...(playoffs?.rounds ?? [])].sort((a, b) => a.round - b.round)) {
    if (r.byeSlots.includes(me)) {
      steps.push({ kind: 'playoff', name: r.name, week: r.week, bye: true, opponentSlot: null, you: null, them: null, won: true, decidedBySeed: false })
      continue
    }
    const m = r.matchups.find((x) => x.highSlot === me || x.lowSlot === me)
    if (!m) break // not in this round: missed the playoffs, or out already
    const high = m.highSlot === me
    steps.push({
      kind: 'playoff',
      name: r.name,
      week: r.week,
      bye: false,
      opponentSlot: high ? m.lowSlot : m.highSlot,
      you: high ? m.highPoints : m.lowPoints,
      them: high ? m.lowPoints : m.highPoints,
      won: m.winnerSlot === me,
      decidedBySeed: m.decidedBySeed,
    })
    if (m.winnerSlot !== me) break
  }

  steps.push({
    kind: 'final',
    headline: old ? `You finished ${ordinal(result.userFinish)} of ${game.teams}` : outcomeHeadline(result.userOutcome, result.userFinish, game.teams),
    champion: !old && result.userOutcome === 'CHAMPION',
  })
  return steps
}

/** How long a step stays on screen before the next, in milliseconds. A whole regular season takes about 8 seconds. */
export function stepDelay(step: RevealStep, weekCount: number): number {
  if (step.kind === 'week') return Math.max(220, Math.min(600, Math.round(8000 / Math.max(1, weekCount))))
  if (step.kind === 'seed') return 1500
  if (step.kind === 'playoff') return 1700
  return 0
}

export const recordText = (wins: number, losses: number, ties: number) => `${wins}-${losses}${ties > 0 ? `-${ties}` : ''}`
