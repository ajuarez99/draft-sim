import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { HistoricalGameState, HistoricalPlayerCard, HistoricalResult } from '../api'
import HistoricalResults from './HistoricalResults'
import old062 from '../__fixtures__/historical-result-062.json'

function card(id: number, name: string): HistoricalPlayerCard {
  return { id, name, positions: ['SF'], team: 'CHI', age: 28, rank: id, positionalRank: id, prior: null, rookiePick: null }
}

function game(userSlot: number): HistoricalGameState {
  return {
    id: 9,
    sport: 'NBA',
    status: 'COMPLETE',
    season: {
      label: '1995-96', countedStats: ['pts'], excludedStats: {}, statCoverage: { pts: 1 },
      rankingSource: 'PRIOR_SEASON_POINTS', rankingLabel: "ranked by last season's points", settingsSource: 'x',
    },
    teams: 12,
    rounds: 14,
    rosterPositions: ['PG', 'BN'],
    userSlot,
    myPicks: [],
    currentPickNo: 169,
    onTheClockSlot: null,
    isUsersTurn: false,
    picks: [],
    available: [],
    result: null,
  } as unknown as HistoricalGameState
}

/** Seed n is slot n. The user (slot 3, seed 3) loses to seed 6 in round one; seed 1 beats seed 2 in the final. */
function playoffResult(over: Partial<HistoricalResult> = {}): HistoricalResult {
  const standings = Array.from({ length: 12 }, (_, i) => ({
    slot: i + 1, wins: 12 - i, losses: i, ties: 0, pointsFor: 5000 - i * 100, place: i + 1,
  }))
  const m = (hs: number, ls: number, hp: number, lp: number, winner: number, tie = false) => ({
    highSeed: hs, lowSeed: ls, highSlot: hs, lowSlot: ls, highPoints: hp, lowPoints: lp, winnerSlot: winner, decidedBySeed: tie,
  })
  const r1 = [m(3, 6, 300, 310, 6), m(4, 5, 280, 280, 4, true)]
  const r2 = [m(1, 6, 330, 320, 1), m(2, 4, 305, 290, 2)]
  const fin = [m(1, 2, 340, 335, 1)]
  const pm = (x: ReturnType<typeof m>) => ({ homeSlot: x.highSlot, awaySlot: x.lowSlot, homePoints: x.highPoints, awayPoints: x.lowPoints })
  return {
    weeks: [
      { week: 1, startDate: '1995-11-06', phase: 'REGULAR', matchups: [{ homeSlot: 3, awaySlot: 4, homePoints: 250, awayPoints: 240 }] },
      { week: 20, startDate: '1996-03-18', phase: 'PLAYOFF', matchups: r1.map(pm) },
      { week: 21, startDate: '1996-03-25', phase: 'PLAYOFF', matchups: r2.map(pm) },
      { week: 22, startDate: '1996-04-01', phase: 'PLAYOFF', matchups: fin.map(pm) },
    ],
    standings,
    userFinish: 5,
    lineups: {
      '3': [
        { week: 1, starters: [{ playerId: 11, slot: 'PG', points: 52.5, gameDate: '1995-11-07' }] },
        { week: 20, starters: [{ playerId: 11, slot: 'PG', points: 61, gameDate: '1996-03-19' }] },
      ],
    },
    hindsight: { label: 'greedy hindsight, bots unchanged', pointsFor: 900, userPointsFor: 700, userBeatIt: false, picks: [] },
    biggestBust: { pickNo: 3, player: card(12, 'Bust Bobby'), seasonValueRank: 150, seasonValue: 400 },
    bestStealPassedOn: { pickNo: 4, taken: card(11, 'Mine One'), takenValue: 600, passedOn: card(13, 'Steal Stan'), passedOnValue: 1100 },
    format: { version: 2, seasonWeeks: 25, configuredRegularSeasonWeeks: 19, regularSeasonWeeks: 19, playoffWeeks: [20, 21, 22], unusedWeeks: 3, shortened: false },
    playoffs: {
      seeds: standings.slice(0, 6).map((s, i) => ({ seed: i + 1, slot: s.slot })),
      rounds: [
        { round: 1, week: 20, name: 'ROUND_ONE', byeSlots: [1, 2], matchups: r1 },
        { round: 2, week: 21, name: 'SEMIFINAL', byeSlots: [], matchups: r2 },
        { round: 3, week: 22, name: 'FINAL', byeSlots: [], matchups: fin },
      ],
      championSlot: 1,
    },
    finalPlaces: standings.map((s, i) => ({
      slot: s.slot, place: i + 1,
      outcome: i === 0 ? 'CHAMPION' : i === 1 ? 'LOST_FINAL' : i < 4 ? 'LOST_SEMIFINAL' : i < 6 ? 'LOST_ROUND_ONE' : 'MISSED_PLAYOFFS',
    })),
    userOutcome: 'LOST_ROUND_ONE',
    ...over,
  } as HistoricalResult
}

const renderIt = (g: HistoricalGameState, r: HistoricalResult) => render(<HistoricalResults game={g} result={r} />)

describe('HistoricalResults, drafted as and finished as (spec 065)', () => {
  const fin = (pickNo: number, name: string, drafted: number, finished: number, weeks: number) => ({
    pickNo, player: { ...card(pickNo, name), positions: ['RB'] }, position: 'RB',
    draftedPositionRank: drafted, finishPositionRank: finished, positionPlayers: 140, seasonValue: 391.3, weeksPlayed: weeks,
  })

  it('says a back taken second who finished first got better, and one who fell got worse', () => {
    renderIt(game(3), playoffResult({ pickFinishes: [fin(2, 'Star Back', 2, 1, 16), fin(23, 'Hurt Back', 9, 61, 3), fin(26, 'Even Back', 12, 12, 17)] }))
    const table = screen.getByRole('region', { name: 'Your picks, drafted and finished' })
    const rows = Array.from(table.querySelectorAll('tbody tr')).map((r) => Array.from(r.querySelectorAll('td')).map((c) => c.textContent))
    expect(rows[0]).toEqual(['2 · round 1', 'Star Back', 'RB2', 'RB1 of 140', '▲ 1 better', '391.3', '16 of 25'])
    expect(rows[1].slice(2, 5)).toEqual(['RB9', 'RB61 of 140', '▼ 52 worse'])
    expect(rows[1][0]).toBe('23 · round 2')
    expect(rows[2][4]).toBe('Same spot')
    expect(table.querySelector('.hist-change.up')?.textContent).toMatch(/better/)
    expect(table.querySelector('.hist-change.down')?.textContent).toMatch(/worse/)
  })

  it('says so, and draws no table, for a result saved before pick finishes existed', () => {
    renderIt(game(3), playoffResult())
    expect(screen.queryByRole('region', { name: 'Your picks, drafted and finished' })).toBeNull()
    expect(screen.getByText(/finished before pick-by-pick finishes were saved/)).toBeInTheDocument()
  })
})

describe('HistoricalResults, playoff format', () => {
  it('leads with the outcome in words and the bust and steal callouts, above the tables', () => {
    const { container } = renderIt(game(3), playoffResult())
    expect(screen.getByText('You lost in round one')).toBeInTheDocument()
    const bust = screen.getByText('Biggest bust')
    const steal = screen.getByText('Best steal you passed on')
    const standings = screen.getByRole('region', { name: 'Standings' })
    // DOM order: callouts come before the standings table
    expect(bust.compareDocumentPosition(standings) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(steal.compareDocumentPosition(standings) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByText('Bust Bobby')).toBeInTheDocument()
    expect(container.textContent).not.toMatch(/old format/)
  })

  it('draws the bracket with byes, scores, the seed tie-break and a champion, and highlights your team', () => {
    renderIt(game(3), playoffResult())
    const bracket = screen.getByRole('region', { name: 'Playoff bracket' })
    expect(within(bracket).getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['Round one Week 20', 'Semifinals Week 21', 'Final Week 22'])
    expect(within(bracket).getByText('Team 1 won it all')).toBeInTheDocument()
    // byes
    expect(within(bracket).getAllByText('bye')).toHaveLength(2)
    // both scores of a matchup
    expect(within(bracket).getByText('300.0')).toBeInTheDocument()
    expect(within(bracket).getByText('310.0')).toBeInTheDocument()
    // the level matchup says so
    expect(within(bracket).getByText(/better seed advanced on a tie/)).toBeInTheDocument()
    // a winner is marked with a word, not only a colour: one per non-final matchup
    expect(within(bracket).getAllByText(/Advances/)).toHaveLength(4)
    expect(within(bracket).getAllByText(/Champion/).length).toBeGreaterThan(0)
    // your team is marked
    expect(bracket.querySelectorAll('.hist-bk-side.me').length).toBeGreaterThan(0)
  })

  it('shows the champion as you when you won it', () => {
    const r = playoffResult()
    renderIt(game(1), { ...r, userOutcome: 'CHAMPION', userFinish: 1 })
    expect(screen.getByText('Champion', { selector: 'h2' })).toBeInTheDocument()
    expect(screen.getByText('You won it all')).toBeInTheDocument()
  })

  it('titles the table Regular season, draws the line after 6th, and counts weeks from the format', () => {
    const { container } = renderIt(game(3), playoffResult())
    const table = screen.getByRole('region', { name: 'Standings' })
    expect(within(table).getByRole('heading', { name: 'Regular season' })).toBeInTheDocument()
    expect(within(table).getByText(/19 weeks\./)).toBeInTheDocument()
    expect(within(table).getByText('19 weeks over an 11-week cycle: you play eight teams twice and three once.', { exact: false })).toBeInTheDocument()
    const rows = Array.from(table.querySelectorAll('tbody tr'))
    expect(rows[6].className).toBe('hist-cutline')
    expect(rows[6].textContent).toMatch(/top 6 advance/)
    expect(rows).toHaveLength(13)
    expect(container.querySelectorAll('tr.hist-me')).toHaveLength(1)
  })

  it('says which weeks were not played and that comparisons cover the whole real season', () => {
    renderIt(game(3), playoffResult())
    expect(screen.getByText(/Weeks 23 to 25 of the real season were not played\. The draft comparisons below cover the whole real season\./)).toBeInTheDocument()
    expect(screen.getByText('You, whole real season')).toBeInTheDocument()
  })

  it('says when the regular season was cut short, and says nothing about unplayed weeks when there are none', () => {
    const r = playoffResult()
    renderIt(game(3), {
      ...r,
      format: { ...r.format!, seasonWeeks: 21, regularSeasonWeeks: 18, configuredRegularSeasonWeeks: 19, playoffWeeks: [19, 20, 21], unusedWeeks: 0, shortened: true },
    })
    expect(screen.getByText(/18 weeks\./)).toBeInTheDocument()
    expect(screen.getByText(/only 21 weeks long/)).toBeInTheDocument()
    expect(screen.queryByText(/were not played/)).not.toBeInTheDocument()
    expect(screen.getByText(/The draft comparisons below cover the whole real season/)).toBeInTheDocument()
  })

  it('labels playoff weeks in the week picker and explains a week you did not play', () => {
    renderIt(game(3), playoffResult())
    const select = screen.getByLabelText('Week') as HTMLSelectElement
    // the visible label already says "Week", so the option does not repeat it (CR-05)
    expect(within(select).getByText('20 · round one (1996-03-18)')).toBeInTheDocument()
    expect(screen.queryByText(/Week Week/)).not.toBeInTheDocument()
    fireEvent.change(select, { target: { value: '20' } })
    expect(screen.getByText('Your lineup, week 20')).toBeInTheDocument()
    fireEvent.change(select, { target: { value: '21' } })
    expect(screen.queryByText(/Your lineup, week/)).not.toBeInTheDocument()
    expect(screen.getByText('You were eliminated before this week.')).toBeInTheDocument()
  })

  it('a playoff game decided by seed shows who advanced and why, in the week list (CR-06)', () => {
    renderIt(game(3), playoffResult())
    fireEvent.change(screen.getByLabelText('Week'), { target: { value: '20' } })
    const list = document.querySelector('.hist-matchups')!
    const rows = Array.from(list.querySelectorAll('li'))
    const level = rows.find((li) => li.textContent?.includes('Team 4'))!
    expect(level.textContent).toMatch(/Level on points\. Team 4 advanced as the better seed\./)
    // exactly one side is marked the winner, and it is the better seed
    expect(level.querySelectorAll('b.win')).toHaveLength(1)
    expect(level.querySelector('b.win')!.previousElementSibling!.textContent).toBe('Team 4')
    // a normal playoff game takes its winner from the bracket and has no note
    const normal = rows.find((li) => li.textContent?.includes('Team 6'))!
    expect(normal.textContent).not.toMatch(/Level on points/)
    expect(normal.querySelector('b.win')!.textContent).toBe('310.0')
  })

  it('prints two decimals for a matchup whose one-decimal scores are equal but not the same, everywhere it appears (CR-06)', () => {
    const r = playoffResult()
    const rounds = r.playoffs!.rounds.map((rd) =>
      rd.round === 1
        ? { ...rd, matchups: rd.matchups.map((m) => (m.highSeed === 3 ? { ...m, highPoints: 150.04, lowPoints: 150.01, winnerSlot: 3 } : m)) }
        : rd,
    )
    const weeks = r.weeks.map((w) =>
      w.week === 20
        ? { ...w, matchups: w.matchups.map((m) => (m.homeSlot === 3 ? { ...m, homePoints: 150.04, awayPoints: 150.01 } : m)) }
        : w,
    )
    renderIt(game(3), { ...r, playoffs: { ...r.playoffs!, rounds }, weeks })
    const bracket = screen.getByRole('region', { name: 'Playoff bracket' })
    expect(within(bracket).getByText('150.04')).toBeInTheDocument()
    expect(within(bracket).getByText('150.01')).toBeInTheDocument()
    expect(within(bracket).queryByText('150.0')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Week'), { target: { value: '20' } })
    const list = document.querySelector('.hist-matchups')!
    expect(list.textContent).toContain('150.04')
    expect(list.textContent).toContain('150.01')
    // a level game keeps one decimal: the two scores are the same number
    expect(within(bracket).getAllByText('280.0', { selector: 'b' })).toHaveLength(2)
  })

  it('says you had a bye in the first playoff week', () => {
    renderIt(game(1), { ...playoffResult(), userOutcome: 'CHAMPION', userFinish: 1 })
    fireEvent.change(screen.getByLabelText('Week'), { target: { value: '20' } })
    expect(screen.getByText('You had a bye this week.')).toBeInTheDocument()
  })

  it('says a missed-playoffs team has no lineup in a playoff week', () => {
    renderIt(game(9), { ...playoffResult(), userOutcome: 'MISSED_PLAYOFFS', userFinish: 9 })
    expect(screen.getByText('You missed the playoffs (9th of 12)')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Week'), { target: { value: '21' } })
    expect(screen.getByText(/did not make the playoffs/)).toBeInTheDocument()
  })

  it('a season too short for playoffs has no bracket and says so', () => {
    const r = playoffResult()
    const { container } = renderIt(game(3), {
      ...r,
      playoffs: null,
      finalPlaces: null,
      userOutcome: 'NO_PLAYOFFS',
      userFinish: 3,
      weeks: r.weeks.slice(0, 1),
      format: { ...r.format!, seasonWeeks: 8, regularSeasonWeeks: 8, playoffWeeks: [], unusedWeeks: 0, shortened: false },
    })
    expect(screen.getByText('You finished 3rd of 12')).toBeInTheDocument()
    expect(screen.getByText('This season was too short for playoffs.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Playoff bracket' })).not.toBeInTheDocument()
    expect(container.querySelector('.hist-cutline')).toBeNull()
  })
})

describe('HistoricalResults, a result stored before playoffs (the 062 fixture)', () => {
  const result = old062 as unknown as HistoricalResult
  const slot = Number(Object.keys(result.lineups)[0])

  it('renders as it always did, plus one line', () => {
    const { container } = renderIt(game(slot), result)
    expect(screen.getByText(`You finished ${result.userFinish}th of 12`)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Final standings' })).toBeInTheDocument()
    expect(screen.getByText('Played under the old format: no playoffs, finish is the standings rank.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Playoff bracket' })).not.toBeInTheDocument()
    expect(container.querySelector('.hist-cutline')).toBeNull()
    expect(screen.queryByText(/not played/)).not.toBeInTheDocument()
    expect(screen.queryByText(/whole real season/)).not.toBeInTheDocument()
    expect(screen.queryByText('Your draft in two moments')).not.toBeInTheDocument()
    // every week is still in the picker, labelled the old way
    expect(screen.getByLabelText('Week').querySelectorAll('option')).toHaveLength(result.weeks.length)
    expect(screen.getByText('Biggest bust')).toBeInTheDocument()
  })
})
