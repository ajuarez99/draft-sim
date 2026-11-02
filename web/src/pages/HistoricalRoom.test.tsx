import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoricalGameState, HistoricalPlayerCard, HistoricalResult } from '../api'
import HistoricalRoom from './HistoricalRoom'

const getHistoricalGame = vi.fn()
const historicalPick = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  getHistoricalGame: (...a: unknown[]) => getHistoricalGame(...a),
  historicalPick: (...a: unknown[]) => historicalPick(...a),
}))

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
})
beforeEach(() => {
  getHistoricalGame.mockReset()
  historicalPick.mockReset()
})

export function card(id: number, name: string, over: Partial<HistoricalPlayerCard> = {}): HistoricalPlayerCard {
  return {
    id,
    name,
    positions: ['SF'],
    team: 'CHI',
    age: 28,
    rank: id,
    positionalRank: id,
    prior: {
      seasonLabel: '1994-95',
      games: 79,
      perGame: { pts: 29.3, reb: 8.1, ast: 4.2 },
      fantasyPoints: 3010.5,
      bestOfTwo: '1993-94',
    },
    ...over,
  }
}

export function drafting(): HistoricalGameState {
  return {
    id: 5,
    status: 'DRAFTING',
    season: {
      label: '1995-96',
      countedStats: ['pts', 'reb', 'ast'],
      excludedStats: { stl: 'steals were not fully recorded', blk: 'blocks were not fully recorded' },
      statCoverage: { pts: 1, reb: 0.99, ast: 0.946 },
      rankingSource: 'PRIOR_SEASON_POINTS',
      rankingLabel: "ranked by last season's points",
      settingsSource: 'Settings: the Legacy Ball Knowers league.',
    },
    teams: 2,
    rounds: 2,
    rosterPositions: ['PG', 'SG', 'BN'],
    userSlot: 1,
    myPicks: [1, 4],
    currentPickNo: 1,
    onTheClockSlot: 1,
    isUsersTurn: true,
    picks: [],
    available: [card(1, 'Top Guy'), card(2, 'Rookie Rick', { prior: null, team: null, age: null })],
    result: null,
  }
}

function result(userBeatIt: boolean, over: Partial<HistoricalResult> = {}): HistoricalResult {
  const a = card(11, 'Mine One')
  const b = card(12, 'Other Two')
  return {
    weeks: [
      { week: 1, startDate: '1995-11-06', matchups: [{ homeSlot: 1, awaySlot: 2, homePoints: 401.5, awayPoints: 388 }] },
      { week: 2, startDate: '1995-11-13', matchups: [{ homeSlot: 2, awaySlot: 1, homePoints: 300, awayPoints: 310 }] },
    ],
    standings: [
      { slot: 2, wins: 1, losses: 1, ties: 0, pointsFor: 688, place: 2 },
      { slot: 1, wins: 2, losses: 0, ties: 0, pointsFor: 711.5, place: 1 },
    ],
    userFinish: 1,
    lineups: { '1': [{ week: 1, starters: [{ playerId: 11, slot: 'PG', points: 52.5, gameDate: '1995-11-07' }] }] },
    hindsight: {
      label: 'greedy hindsight, bots unchanged',
      pointsFor: userBeatIt ? 650 : 900,
      userPointsFor: 711.5,
      userBeatIt,
      picks: [],
    },
    biggestBust: { pickNo: 3, player: b, seasonValueRank: 150, seasonValue: 400 },
    bestStealPassedOn: { pickNo: 4, taken: a, takenValue: 600, passedOn: b, passedOnValue: 1100 },
    ...over,
  }
}

export function complete(userBeatIt: boolean, over: Partial<HistoricalResult> = {}): HistoricalGameState {
  const g = drafting()
  return {
    ...g,
    status: 'COMPLETE',
    onTheClockSlot: null,
    isUsersTurn: false,
    available: [],
    picks: [
      { pickNo: 1, round: 1, draftSlot: 1, seatType: 'USER', player: card(11, 'Mine One') },
      { pickNo: 2, round: 1, draftSlot: 2, seatType: 'BOT', player: card(12, 'Other Two') },
    ],
    result: result(userBeatIt, over),
  }
}

function renderRoom() {
  return render(
    <MemoryRouter initialEntries={['/history/5']}>
      <Routes>
        <Route path="/history/:gameId" element={<HistoricalRoom />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('HistoricalRoom while drafting', () => {
  it('shows the season, counted stats with coverage, exclusions and the ranking label, and never says ADP', async () => {
    getHistoricalGame.mockResolvedValue(drafting())
    const { container } = renderRoom()
    expect(await screen.findByText("You're drafting 1995-96")).toBeInTheDocument()
    expect(screen.getByText('assists 94.6% of box scores')).toBeInTheDocument()
    expect(screen.getByText('rebounds 99.0% of box scores')).toBeInTheDocument()
        expect(screen.getByText(/steals were not fully recorded/)).toBeInTheDocument()
    expect(screen.getAllByText(/ranked by last season's points/).length).toBeGreaterThan(0)
    expect(screen.getByText(/1994-95 · 79 G · 29.3 pts 8.1 reb 4.2 ast/)).toBeInTheDocument()
    expect(screen.getAllByText(/excludes bonuses/).length).toBeGreaterThan(0)
    expect(screen.getByText('no NBA stats last season')).toBeInTheDocument()
    expect(container.textContent ?? '').not.toMatch(/ADP/i)
  })

  it('offers Draft buttons only on your turn', async () => {
    const g = drafting()
    getHistoricalGame.mockResolvedValue({ ...g, isUsersTurn: false, onTheClockSlot: 2 })
    renderRoom()
    await screen.findByText("You're drafting 1995-96")
    expect(screen.queryByRole('button', { name: 'Draft' })).not.toBeInTheDocument()
  })
})

describe('HistoricalRoom review fixes', () => {
  it('never requests a Sleeper headshot for a historical player (the ids are not Sleeper ids)', async () => {
    const g = drafting()
    getHistoricalGame.mockResolvedValue({
      ...g,
      currentPickNo: 2,
      onTheClockSlot: 2,
      isUsersTurn: false,
      picks: [{ pickNo: 1, round: 1, draftSlot: 1, seatType: 'USER', player: card(2000, 'Greg Ballard') }],
    })
    const { container } = renderRoom()
    await screen.findByText("You're drafting 1995-96")
    expect(container.querySelectorAll('.cell').length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Ballard/).length).toBeGreaterThan(0)
    const srcs = Array.from(document.querySelectorAll('img')).map((i) => i.getAttribute('src') ?? '')
    expect(srcs.filter((x) => x.includes('sleepercdn'))).toEqual([])
  })

  it('labels "no NBA stats last season" from prior == null, never from a null team', async () => {
    const g = drafting()
    getHistoricalGame.mockResolvedValue({
      ...g,
      available: [card(7, 'Veteran Vic', { team: null, age: null }), card(8, 'Newcomer Ned', { prior: null, team: null })],
    })
    const { container } = renderRoom()
    await screen.findByText("You're drafting 1995-96")
    expect(container.textContent ?? '').not.toMatch(/rookie/i)
    // exactly one player lacks a prior line, so exactly one says so; the veteran shows his line and a dash
    expect(screen.getAllByText('no NBA stats last season')).toHaveLength(1)
    expect(screen.getByText(/1994-95 · 79 G/)).toBeInTheDocument()
  })

  it('sends one pick for a fast double click on Draft', async () => {
    getHistoricalGame.mockResolvedValue(drafting())
    let release: (v: HistoricalGameState) => void = () => {}
    historicalPick.mockReturnValue(new Promise<HistoricalGameState>((r) => (release = r)))
    renderRoom()
    await screen.findByText("You're drafting 1995-96")
    const draft = screen.getAllByRole('button', { name: 'Draft' })[0]
    fireEvent.click(draft)
    fireEvent.click(draft)   // lands before React has re-rendered with busy set
    expect(historicalPick).toHaveBeenCalledTimes(1)
    release(drafting())
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Draft' })[0]).not.toBeDisabled())
  })
})

describe('HistoricalRoom copy polish', () => {
  it('labels every scoring key, never printing a raw key', async () => {
    const g = drafting()
    getHistoricalGame.mockResolvedValue({
      ...g,
      season: {
        ...g.season,
        countedStats: ['pts', 'bonus_pt_40p', 'bonus_reb_20p'],
        excludedStats: { bonus_pt_50p: 'not recorded', bonus_ast_15p: 'not recorded', tf: 'not recorded', ff: 'not recorded' },
        statCoverage: { pts: 1, bonus_pt_40p: 1, bonus_reb_20p: 1 },
      },
    })
    const { container } = renderRoom()
    await screen.findByText("You're drafting 1995-96")
    const text = container.textContent ?? ''
    expect(text).not.toMatch(/bonus_/)
    expect(text).not.toMatch(/\btf\b/)
    expect(text).not.toMatch(/\bff\b/)
    for (const l of ['40-point bonus', '50-point bonus', '20-rebound bonus', '15-assist bonus', 'technical fouls', 'flagrant fouls']) {
      expect(text).toContain(l)
    }
  })

  it('reads "You drafted" when complete and "(abandoned)" when abandoned', async () => {
    getHistoricalGame.mockResolvedValue(complete(false))
    renderRoom()
    expect(await screen.findByText('You drafted 1995-96')).toBeInTheDocument()
    expect(screen.queryByText("You're drafting 1995-96")).not.toBeInTheDocument()
  })

  it('marks an abandoned game in the header', async () => {
    getHistoricalGame.mockResolvedValue({ ...drafting(), status: 'ABANDONED' })
    renderRoom()
    expect(await screen.findByText('1995-96 (abandoned)')).toBeInTheDocument()
  })
})

describe('HistoricalRoom when complete', () => {
  it('renders standings with your row highlighted and your finish called out', async () => {
    getHistoricalGame.mockResolvedValue(complete(false))
    const { container } = renderRoom()
    expect(await screen.findByText('You finished 1st of 2')).toBeInTheDocument()
    const standings = screen.getByRole('region', { name: 'Standings' })
    const rows = within(standings).getAllByRole('row')
    const mine = rows.find((r) => r.textContent?.includes('You'))!
    expect(mine).toHaveClass('hist-me')
    expect(rows.filter((r) => r.classList.contains('hist-me'))).toHaveLength(1)
    expect(container.textContent ?? '').not.toMatch(/ADP/i)
  })

  it('shows the hindsight gap and its label when you did not beat it', async () => {
    getHistoricalGame.mockResolvedValue(complete(false))
    renderRoom()
    expect(await screen.findByText('greedy hindsight, bots unchanged')).toBeInTheDocument()
    expect(screen.getByText(/188\.5 points ahead/)).toBeInTheDocument()
    expect(screen.queryByText('You beat the greedy hindsight draft.')).not.toBeInTheDocument()
    expect(screen.getByText(/finished 150th of 4 drafted players/)).toBeInTheDocument()
    expect(screen.getByText(/500\.0 more/)).toBeInTheDocument()
  })

  it('says you beat hindsight, with no negative gap, when userBeatIt', async () => {
    getHistoricalGame.mockResolvedValue(complete(true))
    renderRoom()
    expect(await screen.findByText('You beat the greedy hindsight draft.')).toBeInTheDocument()
    expect(screen.queryByText(/points ahead/)).not.toBeInTheDocument()
    expect(screen.queryByText(/-\d+\.\d/)).not.toBeInTheDocument()
  })

  it('is null-safe when there is no bust and no steal, and shows the locked game date in the lineup', async () => {
    getHistoricalGame.mockResolvedValue(complete(false, { biggestBust: null, bestStealPassedOn: null }))
    renderRoom()
    expect(await screen.findByText(/every player you took finished at or above/)).toBeInTheDocument()
    expect(screen.getByText(/Nobody left on the board outscored/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('1995-11-07')).toBeInTheDocument())
    expect(screen.getByText('Mine One', { selector: 'td' })).toBeInTheDocument()
  })
})
