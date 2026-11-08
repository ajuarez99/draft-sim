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
      totals: {},
    },
    rookiePick: null,
    ...over,
  }
}

export function drafting(): HistoricalGameState {
  return {
    id: 5,
    sport: 'NBA',
    status: 'DRAFTING',
    season: {
      label: '1995-96',
      countedStats: ['pts', 'reb', 'ast'],
      excludedStats: { stl: 'steals were not fully recorded', blk: 'blocks were not fully recorded' },
      statCoverage: { pts: 1, reb: 0.99, ast: 0.946 },
      rankingSource: 'PRIOR_SEASON_POINTS',
      rankingLabel: "ranked by last season's points",
      settingsSource: 'Settings: the Legacy Ball Knowers league.',
      dataNotes: [],
      attribution: null,
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

// ---- Spec 056: football ---------------------------------------------------------------------------------

const NFL_KEYS = ['pass_yd', 'pass_td', 'pass_int', 'rush_yd', 'rush_td', 'rec', 'rec_yd', 'rec_td', 'fum_lost', 'st_td', 'pass_2pt', 'rush_2pt', 'rec_2pt']

function nflCard(id: number, name: string, pos: string, over: Partial<HistoricalPlayerCard> = {}): HistoricalPlayerCard {
  return card(id, name, {
    positions: [pos],
    team: 'SD',
    age: null,
    prior: {
      seasonLabel: '2006',
      games: 16,
      perGame: { pass_yd: 0, pass_td: 0, pass_int: 0, rush_yd: 115.8, rush_td: 1.1, rec: 3.5, rec_yd: 20.1, rec_td: 0 },
      fantasyPoints: 476.9,
      bestOfTwo: '2006',
      totals: { st_td: 2, fum_lost: 3 },
    },
    ...over,
  })
}

function nflDrafting(): HistoricalGameState {
  const g = drafting()
  return {
    ...g,
    sport: 'NFL',
    season: {
      label: '2007',
      countedStats: NFL_KEYS,
      excludedStats: {},
      statCoverage: {},
      rankingSource: 'PRIOR_SEASON_VORP',
      rankingLabel: "ranked by last season's points over replacement",
      settingsSource: 'Hand-set 12-team, 15-round roster: QB, 2 RB, 2 WR, TE, FLEX + 8 bench · full PPR',
      dataNotes: ['Week 17 of 2007 is missing from the data'],
      attribution: 'Stats: nflverse (nflverse-data), CC BY 4.0',
    },
    rosterPositions: ['QB', 'RB', 'RB', 'WR', 'WR', 'TE', 'FLEX', 'BN'],
    available: [
      nflCard(1, 'Top Back', 'RB'),
      nflCard(2, 'Gunslinger Gus', 'QB'),
      nflCard(3, 'Rookie Ray', 'WR', { prior: null, team: null, rookiePick: 5 }),
      nflCard(4, 'Walk-on Wes', 'TE', { prior: null, team: null, rookiePick: null }),
    ],
  }
}

function nflComplete(): HistoricalGameState {
  const g = nflDrafting()
  const done = complete(false)
  return {
    ...g,
    status: 'COMPLETE',
    onTheClockSlot: null,
    isUsersTurn: false,
    available: [],
    picks: [
      { pickNo: 1, round: 1, draftSlot: 1, seatType: 'USER', player: nflCard(11, 'Mine One', 'RB') },
      { pickNo: 2, round: 1, draftSlot: 2, seatType: 'BOT', player: nflCard(12, 'Other Two', 'QB') },
    ],
    result: {
      ...done.result!,
      weeks: [
        { week: 1, startDate: null, matchups: [{ homeSlot: 1, awaySlot: 2, homePoints: 101.5, awayPoints: 88 }] },
        { week: 2, startDate: null, matchups: [{ homeSlot: 2, awaySlot: 1, homePoints: 100, awayPoints: 110 }] },
      ],
      lineups: { '1': [{ week: 1, starters: [{ playerId: 11, slot: 'RB', points: 22.5, gameDate: null }] }] },
    },
  }
}

describe('HistoricalRoom, football', () => {
  it('shows scoring keys, the data note, the attribution and the settings, with no NBA copy and no ADP', async () => {
    getHistoricalGame.mockResolvedValue(nflDrafting())
    const { container } = renderRoom()
    expect(await screen.findByText("You're drafting 2007")).toBeInTheDocument()
    expect(screen.getByText('Scoring in 2007')).toBeInTheDocument()
    expect(screen.getByText('passing yards')).toBeInTheDocument()
    expect(screen.getByText('return touchdowns')).toBeInTheDocument()
    expect(screen.getByText('Week 17 of 2007 is missing from the data')).toBeInTheDocument()
    expect(screen.getByText('Stats: nflverse (nflverse-data), CC BY 4.0')).toBeInTheDocument()
    expect(screen.getByText(/Hand-set 12-team, 15-round roster/)).toBeInTheDocument()
    expect(screen.getAllByText(/ranked by last season's points over replacement/).length).toBeGreaterThan(0)
    const text = container.textContent ?? ''
    expect(text).not.toMatch(/ADP/i)
    expect(text).not.toMatch(/box scores/)
    expect(text).not.toMatch(/bonus/i)
    expect(text).not.toMatch(/NBA/)
    expect(text).not.toMatch(/_/)
  })

  it('filters by QB, RB, WR, TE only, and each filter narrows the list', async () => {
    getHistoricalGame.mockResolvedValue(nflDrafting())
    renderRoom()
    await screen.findByText("You're drafting 2007")
    const group = screen.getByRole('group', { name: 'Position filter' })
    expect(within(group).getAllByRole('button').map((b) => b.textContent)).toEqual(['All', 'QB', 'RB', 'WR', 'TE'])
    fireEvent.click(within(group).getByRole('button', { name: 'QB' }))
    const list = screen.getByRole('region', { name: 'Available players' })
    expect(within(list).getByText('Gunslinger Gus')).toBeInTheDocument()
    expect(within(list).queryByText('Top Back')).not.toBeInTheDocument()
    fireEvent.click(within(group).getByRole('button', { name: 'TE' }))
    expect(within(list).getByText('Walk-on Wes')).toBeInTheDocument()
    expect(within(list).queryByText('Gunslinger Gus')).not.toBeInTheDocument()
  })

  it('renders card lines: zero keys hidden, totals shown, rookie pick or no-stats line, no bonuses wording', async () => {
    getHistoricalGame.mockResolvedValue(nflDrafting())
    renderRoom()
    await screen.findByText("You're drafting 2007")
    const list = screen.getByRole('region', { name: 'Available players' })
    const line = within(list).getAllByText(/^2006 · 16 G/)[0]
    expect(line.textContent).toContain('115.8 rush yd 1.1 rush TD 3.5 rec 20.1 rec yd')
    expect(line.textContent).not.toMatch(/pass yd|pass TD|INT|rec TD/)
    expect(line.textContent).toContain('3 fum lost · 2 ret TD')
    expect(line.textContent).toContain('476.9 fantasy pts')
    expect(line.textContent).not.toMatch(/bonus/i)
    expect(within(list).getByText('rookie · NFL pick #5')).toBeInTheDocument()
    expect(within(list).getByText('no NFL stats last season')).toBeInTheDocument()
    expect(within(list).queryByText(/no NBA stats/)).not.toBeInTheDocument()
  })

  it('results show Week N for a starter with no game date, never "did not play", and keep the attribution', async () => {
    getHistoricalGame.mockResolvedValue(nflComplete())
    const { container } = renderRoom()
    expect(await screen.findByText('You finished 1st of 2')).toBeInTheDocument()
    expect(screen.getByText('Week 1', { selector: 'td' })).toBeInTheDocument()
    const text = container.textContent ?? ''
    expect(text).not.toMatch(/did not play/)
    expect(text).not.toMatch(/ADP/i)
    expect(text).not.toMatch(/locked/i)
    expect(screen.getByText('Stats: nflverse (nflverse-data), CC BY 4.0')).toBeInTheDocument()
  })

  it('basketball still says "did not play" for a starter with no game date', async () => {
    const c = complete(false)
    getHistoricalGame.mockResolvedValue({
      ...c,
      result: { ...c.result!, lineups: { '1': [{ week: 1, starters: [{ playerId: 11, slot: 'PG', points: 0, gameDate: null }] }] } },
    })
    renderRoom()
    expect(await screen.findByText('did not play')).toBeInTheDocument()
  })
})
