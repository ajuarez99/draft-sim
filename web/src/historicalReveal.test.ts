import { describe, expect, it } from 'vitest'
import type { HistoricalPlayoffMatchup, HistoricalResult } from './api'
import { recordText, seasonSteps, stepDelay, type PlayoffStep, type WeekStep } from './historicalReveal'

const ME = 3
const game = { userSlot: ME, teams: 12 }

/** Week scores from the fan's side: [you, them]. The fan is home on odd weeks and away on even ones. */
function weeks(scores: [number, number][], phase: 'REGULAR' | 'PLAYOFF' | null = 'REGULAR') {
  return scores.map(([you, them], i) => ({
    week: i + 1,
    startDate: null,
    phase,
    matchups: [
      { homeSlot: 1, awaySlot: 2, homePoints: 50, awayPoints: 60 },
      i % 2 === 0
        ? { homeSlot: ME, awaySlot: 7, homePoints: you, awayPoints: them }
        : { homeSlot: 7, awaySlot: ME, homePoints: them, awayPoints: you },
    ],
  }))
}

function result(over: Partial<HistoricalResult>): HistoricalResult {
  return {
    weeks: [],
    standings: [{ slot: ME, place: 2, wins: 0, losses: 0, ties: 0, pointsFor: 0 }],
    userFinish: 2,
    lineups: {},
    hindsight: { userPointsFor: 1, pointsFor: 2, userBeatIt: false, label: '', picks: [] },
    biggestBust: null,
    bestStealPassedOn: null,
    format: { version: 2, seasonWeeks: 6, configuredRegularSeasonWeeks: 4, regularSeasonWeeks: 4, playoffWeeks: [5, 6], unusedWeeks: 0, shortened: false },
    playoffs: null,
    userOutcome: 'MISSED_PLAYOFFS',
    ...over,
  } as HistoricalResult
}

const weekSteps = (r: HistoricalResult) => seasonSteps(game, r).filter((s): s is WeekStep => s.kind === 'week')

describe('seasonSteps: the regular season', () => {
  it('scores each week from the fan side whether they are home or away, and more points is the win', () => {
    // A sign flip or a home/away mix-up here would turn every win into a loss, so the direction is pinned.
    const steps = weekSteps(result({ weeks: weeks([[120, 100], [120, 100], [90, 100], [100, 100]]) }))
    expect(steps.map((s) => s.outcome)).toEqual(['W', 'W', 'L', 'T'])
    expect(steps.map((s) => [s.you, s.them])).toEqual([[120, 100], [120, 100], [90, 100], [100, 100]])
    expect(steps.every((s) => s.opponentSlot === 7)).toBe(true)
  })

  it('counts the record up week by week and ends on the standings row', () => {
    const r = result({
      weeks: weeks([[120, 100], [120, 100], [90, 100], [100, 100]]),
      standings: [{ slot: ME, place: 2, wins: 2, losses: 1, ties: 1, pointsFor: 430 }],
    })
    const steps = weekSteps(r)
    expect(steps.map((s) => recordText(s.wins, s.losses, s.ties))).toEqual(['1-0', '2-0', '2-1', '2-1-1'])
    const last = steps[steps.length - 1]
    const row = r.standings[0]
    expect([last.wins, last.losses, last.ties]).toEqual([row.wins, row.losses, row.ties])
  })

  it('leaves playoff weeks and weeks past the regular season out of the record', () => {
    const regular = weeks([[120, 100], [90, 100]])
    const playoff = weeks([[1, 2], [1, 2], [300, 1]], 'PLAYOFF').slice(2) // week 3, marked PLAYOFF
    const unmarkedLate = { ...weeks([[1, 2], [1, 2], [1, 2], [1, 2], [300, 1]], null)[4] } // week 5, past week 4
    expect(weekSteps(result({ weeks: [...regular, ...playoff, unmarkedLate] })).map((s) => s.week)).toEqual([1, 2])
  })

  it('plays the weeks in week order even when the result lists them out of order', () => {
    const w = weeks([[120, 100], [90, 100], [120, 100]])
    expect(weekSteps(result({ weeks: [w[2], w[0], w[1]] })).map((s) => s.week)).toEqual([1, 2, 3])
  })
})

describe('seasonSteps: seed, playoffs and the finish', () => {
  const round = (name: PlayoffStep['name'], n: number, week: number, m: HistoricalPlayoffMatchup[], byeSlots: number[] = []) => ({ round: n, week, name, byeSlots, matchups: m })
  const tie = (highSlot: number, lowSlot: number, highPoints: number, lowPoints: number, winnerSlot: number, decidedBySeed = false): HistoricalPlayoffMatchup => ({
    highSeed: 1, lowSeed: 2, highSlot, lowSlot, highPoints, lowPoints, winnerSlot, decidedBySeed,
  })

  it('a team that missed the playoffs goes from the seed line straight to the finish', () => {
    const r = result({
      weeks: weeks([[90, 100]]),
      playoffs: { seeds: [{ seed: 1, slot: 1 }], rounds: [round('FINAL', 1, 5, [tie(1, 2, 10, 5, 1)])], championSlot: 1 },
      standings: [{ slot: ME, place: 9, wins: 0, losses: 1, ties: 0, pointsFor: 90 }],
      userFinish: 9,
    })
    const kinds = seasonSteps(game, r).map((s) => s.kind)
    expect(kinds).toEqual(['week', 'seed', 'final'])
    expect(seasonSteps(game, r)[1]).toEqual({ kind: 'seed', place: 9, seed: null, playoffsHeld: true })
    expect(seasonSteps(game, r)[2]).toEqual({ kind: 'final', headline: 'You missed the playoffs (9th of 12)', champion: false })
  })

  it('a bye, a win and a lost final are told in round order and stop at the loss', () => {
    const r = result({
      userOutcome: 'LOST_FINAL',
      playoffs: {
        seeds: [{ seed: 2, slot: ME }],
        championSlot: 8,
        rounds: [
          // Listed out of order on purpose.
          round('FINAL', 3, 7, [tie(8, ME, 140, 120, 8)]),
          round('ROUND_ONE', 1, 5, [tie(4, 5, 10, 5, 4)], [ME, 8]),
          round('SEMIFINAL', 2, 6, [tie(ME, 4, 130, 110, ME)]),
        ],
      },
    })
    const p = seasonSteps(game, r).filter((s): s is PlayoffStep => s.kind === 'playoff')
    expect(p.map((s) => s.name)).toEqual(['ROUND_ONE', 'SEMIFINAL', 'FINAL'])
    expect(p[0]).toMatchObject({ bye: true, won: true, you: null })
    expect(p[1]).toMatchObject({ bye: false, won: true, you: 130, them: 110, opponentSlot: 4 })
    // The fan is the LOW seed in the final: their points are lowPoints, not highPoints.
    expect(p[2]).toMatchObject({ won: false, you: 120, them: 140, opponentSlot: 8 })
    expect(seasonSteps(game, r).find((s) => s.kind === 'seed')).toMatchObject({ seed: 2, playoffsHeld: true })
  })

  it('a round-one loss ends the playoffs there, and a champion is flagged', () => {
    const lost = result({
      userOutcome: 'LOST_ROUND_ONE',
      playoffs: {
        seeds: [{ seed: 5, slot: ME }],
        championSlot: 1,
        rounds: [round('ROUND_ONE', 1, 5, [tie(4, ME, 100, 99, 4)]), round('SEMIFINAL', 2, 6, [tie(1, 4, 9, 8, 1)])],
      },
    })
    expect(seasonSteps(game, lost).filter((s) => s.kind === 'playoff')).toHaveLength(1)

    const champ = result({
      userOutcome: 'CHAMPION',
      userFinish: 1,
      playoffs: { seeds: [{ seed: 1, slot: ME }], championSlot: ME, rounds: [round('FINAL', 1, 5, [tie(ME, 2, 100, 100, ME, true)])] },
    })
    const steps = seasonSteps(game, champ)
    expect(steps[steps.length - 1]).toMatchObject({ kind: 'final', champion: true })
    expect(steps.find((s) => s.kind === 'playoff')).toMatchObject({ won: true, decidedBySeed: true })
  })

  it('an old-format result has no playoffs and finishes on the standings rank', () => {
    const r = result({ format: null, playoffs: null, userOutcome: null, userFinish: 4, weeks: weeks([[120, 100]], null) })
    const steps = seasonSteps(game, r)
    expect(steps.map((s) => s.kind)).toEqual(['week', 'seed', 'final'])
    expect(steps[1]).toMatchObject({ playoffsHeld: false, seed: null })
    expect(steps[2]).toEqual({ kind: 'final', headline: 'You finished 4th of 12', champion: false })
  })
})

describe('stepDelay', () => {
  const week = { kind: 'week' } as WeekStep
  it('keeps a whole regular season near eight seconds, within a floor and a ceiling per week', () => {
    expect(stepDelay(week, 14)).toBe(571)
    expect(stepDelay(week, 3)).toBe(600)
    expect(stepDelay(week, 80)).toBe(220)
  })
})
