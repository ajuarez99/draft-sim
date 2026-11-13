import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { act } from '@testing-library/react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoricalGameState, HistoricalPlayerCard, HistoricalResult } from '../api'
import HistoricalRoom from './HistoricalRoom'

const getHistoricalGame = vi.fn()
const historicalPick = vi.fn()
const historicalAuto = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  getHistoricalGame: (...a: unknown[]) => getHistoricalGame(...a),
  historicalPick: (...a: unknown[]) => historicalPick(...a),
  historicalAuto: (...a: unknown[]) => historicalAuto(...a),
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
  historicalAuto.mockReset()
  localStorage.clear()
})
afterEach(() => vi.useRealTimers())

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
    fireEvent.click(screen.getByRole('button', { name: 'Board' }))
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
      settingsSource: 'Hand-set 12-team, 15-round roster: QB, 2 RB, 2 WR, TE, 2 FLEX + 7 bench · full PPR',
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

// ---- Spec 063: pick pacing ------------------------------------------------------------------------------

/** Two teams, you are slot 2: the computer made pick 1 before you opened the game, and you have not picked. */
function botFirst(): HistoricalGameState {
  const g = drafting()
  const bot = card(21, 'Bot Pick')
  return {
    ...g,
    userSlot: 2,
    myPicks: [2, 3],
    currentPickNo: 2,
    onTheClockSlot: 2,
    isUsersTurn: true,
    picks: [{ pickNo: 1, round: 1, draftSlot: 1, seatType: 'BOT', player: bot }],
    available: [card(1, 'Top Guy')],
  }
}

async function renderPaced(speed: string) {
  localStorage.setItem('bk.tm.revealSpeed', speed)
  vi.useFakeTimers()
  getHistoricalGame.mockResolvedValue(botFirst())
  const utils = renderRoom()
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
  return utils
}

const cellsWith = (container: HTMLElement, text: string) =>
  // a filled board cell carries the player's name in its title
  Array.from(container.querySelectorAll('.cell')).filter((c) => (c.getAttribute('title') ?? c.textContent ?? '').includes(text)).length

describe('HistoricalRoom pick pacing', () => {
  it('fills the board one pick per tick and offers no Draft button until the reveal catches up', async () => {
    const { container } = await renderPaced('FAST')
    const rail = () => screen.getByRole('region', { name: 'Pick order' })
    expect(rail()).not.toHaveTextContent('Bot Pick')
    // the player is still in the list: his pick has not been revealed
    expect(screen.getByRole('region', { name: 'Available players' })).toHaveTextContent('Bot Pick')
    expect(screen.queryByRole('button', { name: 'Draft' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Auto-pick/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Skip' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Board' }))
    expect(cellsWith(container, 'Bot Pick')).toBe(0)
    fireEvent.click(screen.getByRole('button', { name: 'Players' }))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(400)
    })
    expect(rail()).toHaveTextContent('Bot Pick')
    fireEvent.click(screen.getByRole('button', { name: 'Board' }))
    expect(cellsWith(container, 'Bot Pick')).toBeGreaterThan(0)
    fireEvent.click(screen.getByRole('button', { name: 'Players' }))
    expect(screen.getByRole('region', { name: 'Available players' })).not.toHaveTextContent('Bot Pick')
    expect(screen.getAllByRole('button', { name: 'Draft' }).length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: 'Skip' })).toBeDisabled()
    // FAST announces only the end summary
    expect(screen.getByRole('status')).toHaveTextContent('1 pick made. You are on the clock.')
  })

  it('NORMAL announces each pick by name', async () => {
    await renderPaced('NORMAL')
    expect(screen.getByRole('status')).toHaveTextContent('')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(screen.getByRole('status')).toHaveTextContent('Pick 1: Team 1 took Bot Pick. You are on the clock.')
  })

  it('Skip lands on your turn at once', async () => {
    await renderPaced('SLOW')
    expect(screen.queryByRole('button', { name: 'Draft' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Skip' }))
    expect(screen.getAllByRole('button', { name: 'Draft' }).length).toBeGreaterThan(0)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('reads the saved speed, saves a new one, and Instant shows the true state with no wait', async () => {
    await renderPaced('FAST')
    expect(screen.getByRole('button', { name: 'Fast' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Slow' }))
    expect(localStorage.getItem('bk.tm.revealSpeed')).toBe('SLOW')
    fireEvent.click(screen.getByRole('button', { name: 'Instant' }))
    expect(localStorage.getItem('bk.tm.revealSpeed')).toBe('INSTANT')
    expect(screen.getAllByRole('button', { name: 'Draft' }).length).toBeGreaterThan(0)
  })
})

// ---- Spec 063: the room (banner, rail, queue, roster, layout) -------------------------------------------

/** Four teams, you are slot 4 (the pick-4 seat in round 1); nothing picked yet, so the reveal replays picks 1-3. */
function fourTeams(): HistoricalGameState {
  const g = drafting()
  const bots = [card(21, 'Bot One'), card(22, 'Bot Two'), card(23, 'Bot Three')]
  return {
    ...g,
    teams: 4,
    rounds: 3,
    userSlot: 4,
    myPicks: [4, 5, 12],
    currentPickNo: 4,
    onTheClockSlot: 4,
    isUsersTurn: true,
    rosterPositions: ['PG', 'SG', 'BN'],
    picks: bots.map((b, i) => ({ pickNo: i + 1, round: 1, draftSlot: i + 1, seatType: 'BOT' as const, player: b })),
    available: [card(1, 'Top Guy'), card(2, 'Second Guy'), card(3, 'Third Guy', { positions: ['PG'] })],
  }
}

async function renderFour(speed = 'SLOW') {
  localStorage.setItem('bk.tm.revealSpeed', speed)
  vi.useFakeTimers()
  getHistoricalGame.mockResolvedValue(fourTeams())
  const utils = renderRoom()
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
  return utils
}
const tick = (ms: number) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })

describe('HistoricalRoom: banner, rail, roster', () => {
  it('says who is on the clock and how many picks until you, from the reveal view', async () => {
    await renderFour()
    const banner = screen.getByRole('region', { name: 'Draft clock' })
    expect(banner).toHaveTextContent('Team 1 is on the clock')
    expect(banner).toHaveTextContent('Your next pick in 3 picks')
    await tick(2000)
    expect(banner).toHaveTextContent('Team 2 is on the clock')
    expect(banner).toHaveTextContent('Your next pick in 2 picks')
    fireEvent.click(screen.getByRole('button', { name: 'Skip' }))
    expect(banner).toHaveTextContent('Your turn')
  })

  it('marks the current pick in the rail and your own picks, and shows no computer-team needs', async () => {
    const { container } = await renderFour()
    const rail = screen.getByRole('region', { name: 'Pick order' })
    const current = rail.querySelector('[aria-current="true"]')!
    expect(current).toHaveTextContent('Team 1')
    expect(current).toHaveTextContent('on the clock')
    expect(rail.querySelectorAll('.hist-rail-pick.me').length).toBeGreaterThan(0)
    expect(within(rail).getAllByText('You').length).toBeGreaterThan(0)
    expect(container.textContent).not.toMatch(/needs?:/i)
  })

  it('shows your roster so far with empty slots, and fills a slot when your pick is shown', async () => {
    getHistoricalGame.mockResolvedValue(fourTeams())
    historicalPick.mockResolvedValue({
      ...fourTeams(),
      currentPickNo: 5,
      isUsersTurn: false,
      onTheClockSlot: 4,
      picks: [...fourTeams().picks, { pickNo: 4, round: 1, draftSlot: 4, seatType: 'USER', player: card(3, 'Third Guy', { positions: ['PG'] }) }],
      available: [card(1, 'Top Guy'), card(2, 'Second Guy')],
    })
    localStorage.setItem('bk.tm.revealSpeed', 'INSTANT')
    renderRoom()
    const roster = await screen.findByRole('region', { name: 'Your roster' })
    expect(within(roster).getByText('Your roster so far')).toBeInTheDocument()
    expect(within(roster).getAllByText('empty').length).toBe(2)
    fireEvent.click(screen.getAllByRole('button', { name: 'Draft' })[2])
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Your roster' })).getByText('Third Guy')).toBeInTheDocument())
  })

  it('keeps the season disclosure closed under the room, with the counts in its summary', async () => {
    getHistoricalGame.mockResolvedValue(drafting())
    const { container } = renderRoom()
    await screen.findByText("You're drafting 1995-96")
    const details = container.querySelector('details.hist-counts') as HTMLDetailsElement
    expect(details).not.toBeNull()
    expect(details.open).toBe(false)
    expect(details.querySelector('summary')).toHaveTextContent('What counts in 1995-96 · 2 stats not counted')
    expect(details.querySelector('summary')!.textContent).not.toMatch(/data note/)
    expect(details).toHaveTextContent('steals were not fully recorded')
  })

  it('offers the five views on a narrow screen, one pane at a time', async () => {
    const original = window.matchMedia
    window.matchMedia = ((q: string) => ({
      matches: q.includes('1279'),
      media: q,
      addEventListener() {},
      removeEventListener() {},
    })) as unknown as typeof window.matchMedia
    try {
      getHistoricalGame.mockResolvedValue(fourTeams())
      localStorage.setItem('bk.tm.revealSpeed', 'INSTANT')
      renderRoom()
      await screen.findByText("You're drafting 1995-96")
      const tabs = screen.getByRole('group', { name: 'Room view' })
      expect(within(tabs).getAllByRole('button').map((b) => b.textContent)).toEqual(['Players', 'Queue (0)', 'Board', 'Picks', 'Roster'])
      expect(screen.queryByRole('region', { name: 'Pick order' })).not.toBeInTheDocument()
      fireEvent.click(within(tabs).getByRole('button', { name: 'Picks' }))
      expect(screen.getByRole('region', { name: 'Pick order' })).toBeInTheDocument()
      expect(screen.queryByRole('region', { name: 'Available players' })).not.toBeInTheDocument()
      fireEvent.click(within(tabs).getByRole('button', { name: 'Roster' }))
      expect(screen.getByRole('region', { name: 'Your roster' })).toBeInTheDocument()
    } finally {
      window.matchMedia = original
    }
  })

  it('collapses the rail and the roster when the board is chosen on a wide screen', async () => {
    getHistoricalGame.mockResolvedValue(fourTeams())
    localStorage.setItem('bk.tm.revealSpeed', 'INSTANT')
    renderRoom()
    await screen.findByText("You're drafting 1995-96")
    expect(screen.getByRole('region', { name: 'Pick order' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Board' }))
    expect(screen.queryByRole('region', { name: 'Pick order' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Your roster' })).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Draft clock' })).toBeInTheDocument()
  })

  it('every control is a real, focusable button: speeds, Skip, view tabs, Draft, star', async () => {
    await renderFour('SLOW')
    const controls = [
      ...within(screen.getByRole('group', { name: 'Pick speed' })).getAllByRole('button'),
      screen.getByRole('button', { name: 'Skip' }),
      ...within(screen.getByRole('group', { name: 'Room view' })).getAllByRole('button'),
      screen.getByRole('button', { name: 'Queue Top Guy' }),
    ]
    for (const c of controls) {
      expect(c.tagName).toBe('BUTTON')
      expect(c.tabIndex).not.toBe(-1)
    }
    fireEvent.click(screen.getByRole('button', { name: 'Skip' }))
    const draft = screen.getAllByRole('button', { name: 'Draft' })[0]
    expect(draft.tagName).toBe('BUTTON')
    expect(draft.tabIndex).not.toBe(-1)
  })

  it('keeps Auto-finish available while picks are landing, and says it ignores the queue', async () => {
    await renderFour()
    const finish = screen.getByRole('button', { name: 'Auto-finish the draft' })
    expect(finish).toBeEnabled()
    expect(finish).toHaveAttribute('title', 'Makes every remaining pick, yours included. Ignores your queue.')
  })
})

describe('HistoricalRoom: queue', () => {
  const star = (name: string) => screen.getByRole('button', { name: `Queue ${name}` })

  it('lets you queue during a reveal, drops a player the moment his pick is shown, and cannot draft until your turn', async () => {
    await renderFour('SLOW')
    fireEvent.click(star('Bot One'))
    fireEvent.click(star('Top Guy'))
    expect(screen.getByRole('button', { name: 'Queue (2)' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Draft/ })).not.toBeInTheDocument()
    // Bot One is still in the list and the queue: pick 1 has not been revealed
    await tick(1999)
    expect(screen.getByRole('button', { name: 'Queue (2)' })).toBeInTheDocument()
    await tick(1)
    expect(screen.getByRole('button', { name: 'Queue (1)' })).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('bk.tm.queue.5')!)).toEqual([1])
  })

  it('shows the queue in order, reorders by buttons, and removes', async () => {
    await renderFour('INSTANT')
    fireEvent.click(star('Top Guy'))
    fireEvent.click(star('Second Guy'))
    fireEvent.click(screen.getByRole('button', { name: 'Queue (2)' }))
    const rows = () =>
      within(screen.getByRole('region', { name: 'Your queue' }))
        .getAllByRole('listitem')
        .map((li) => li.querySelector('strong')!.textContent)
    expect(rows()).toEqual(['Top Guy', 'Second Guy'])
    fireEvent.click(screen.getByRole('button', { name: 'Move Second Guy up' }))
    expect(rows()).toEqual(['Second Guy', 'Top Guy'])
    expect(screen.getByRole('button', { name: 'Move Second Guy up' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Remove Second Guy from your queue' }))
    expect(rows()).toEqual(['Top Guy'])
  })

  it('explains an empty queue', async () => {
    await renderFour('INSTANT')
    fireEvent.click(screen.getByRole('button', { name: 'Queue (0)' }))
    expect(screen.getByText(/Nothing queued yet/)).toBeInTheDocument()
  })

  it('Auto-pick names the top queued player and picks him; with an empty queue it uses the server auto-pick', async () => {
    await renderFour('INSTANT')
    historicalPick.mockResolvedValue(fourTeams())
    historicalAuto.mockResolvedValue(fourTeams())
    // empty queue: plain label, server auto-pick
    fireEvent.click(screen.getByRole('button', { name: 'Auto-pick' }))
    await tick(0)
    expect(historicalAuto).toHaveBeenCalledWith(5, 'PICK')
    expect(historicalPick).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Auto-pick' })).toBeEnabled()
    // queue two; the top one is named and chosen
    fireEvent.click(star('Second Guy'))
    fireEvent.click(star('Top Guy'))
    fireEvent.click(screen.getByRole('button', { name: 'Auto-pick: Second Guy' }))
    await tick(0)
    expect(historicalPick).toHaveBeenCalledWith(5, 2)
  })

  it('deletes the stored queue once the game is complete', async () => {
    localStorage.setItem('bk.tm.queue.5', JSON.stringify([1]))
    getHistoricalGame.mockResolvedValue(complete(false))
    renderRoom()
    await screen.findByText('You drafted 1995-96')
    expect(localStorage.getItem('bk.tm.queue.5')).toBeNull()
  })
})

// ---- Spec 063 review fixes: CR-01, CR-08, CR-09 ---------------------------------------------------------

describe('HistoricalRoom: Auto-finish during the final reveal (CR-01)', () => {
  /** Two teams, you are slot 2 (picks 2 and 3); the computer makes pick 4, which ends the draft. */
  function lastPickIsTheComputers(): { start: HistoricalGameState; end: HistoricalGameState } {
    const done = complete(false)
    const bot1 = card(21, 'Bot Pick')
    const mine2 = card(1, 'Top Guy')
    const mine3 = card(22, 'Mine Three')
    const bot4 = card(23, 'Bot Last')
    const start: HistoricalGameState = {
      ...drafting(),
      userSlot: 2,
      myPicks: [2, 3],
      currentPickNo: 2,
      onTheClockSlot: 2,
      isUsersTurn: true,
      picks: [{ pickNo: 1, round: 1, draftSlot: 1, seatType: 'BOT', player: bot1 }],
      available: [mine2, mine3, bot4],
    }
    const end: HistoricalGameState = {
      ...done,
      userSlot: 2,
      myPicks: [2, 3],
      currentPickNo: 5,
      picks: [
        { pickNo: 1, round: 1, draftSlot: 1, seatType: 'BOT', player: bot1 },
        { pickNo: 2, round: 1, draftSlot: 2, seatType: 'USER', player: mine2 },
        { pickNo: 3, round: 2, draftSlot: 2, seatType: 'USER', player: mine3 },
        { pickNo: 4, round: 2, draftSlot: 1, seatType: 'BOT', player: bot4 },
      ],
    }
    return { start, end }
  }

  it('makes no request, shows no error and lands on the results when the server has already finished', async () => {
    const { start, end } = lastPickIsTheComputers()
    localStorage.setItem('bk.tm.revealSpeed', 'SLOW')
    vi.useFakeTimers()
    getHistoricalGame.mockResolvedValue(start)
    historicalPick.mockResolvedValue(end)
    historicalAuto.mockRejectedValue(new Error('historical game 5 is COMPLETE, not DRAFTING'))
    const { container } = renderRoom()
    await tick(0)
    fireEvent.click(screen.getByRole('button', { name: 'Skip' }))
    fireEvent.click(screen.getAllByRole('button', { name: 'Draft' })[0])
    await tick(0)
    // pick 4 is still being revealed: the view is DRAFTING, the server is COMPLETE
    expect(screen.getByRole('button', { name: 'Skip' })).toBeEnabled()
    expect(screen.queryByText('You drafted 1995-96')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Auto-finish the draft' }))
    await tick(0)
    expect(historicalAuto).not.toHaveBeenCalled()
    expect(container.querySelector('.error')).toBeNull()
    expect(screen.getByText('You drafted 1995-96')).toBeInTheDocument()
  })

  it('still asks the server to finish while the server is DRAFTING', async () => {
    const { start, end } = lastPickIsTheComputers()
    localStorage.setItem('bk.tm.revealSpeed', 'SLOW')
    vi.useFakeTimers()
    getHistoricalGame.mockResolvedValue(start)
    historicalAuto.mockResolvedValue(end)
    renderRoom()
    await tick(0)
    fireEvent.click(screen.getByRole('button', { name: 'Auto-finish the draft' }))
    await tick(0)
    expect(historicalAuto).toHaveBeenCalledWith(5, 'FINISH')
    expect(screen.getByText('You drafted 1995-96')).toBeInTheDocument()
  })

  it('speaks the end of the draft: the live region is the same node before and after the results appear', async () => {
    const { start, end } = lastPickIsTheComputers()
    localStorage.setItem('bk.tm.revealSpeed', 'NORMAL')
    vi.useFakeTimers()
    getHistoricalGame.mockResolvedValue(start)
    historicalPick.mockResolvedValue(end)
    renderRoom()
    await tick(0)
    fireEvent.click(screen.getByRole('button', { name: 'Skip' }))
    fireEvent.click(screen.getAllByRole('button', { name: 'Draft' })[0])
    await tick(0)
    // The room's own sentence region; the results also mount the (empty) achievement toast region, spec 064.
    const room = () => screen.getAllByRole('status').find((el) => el.classList.contains('hist-sr-only'))!
    const region = room()
    await tick(1000)
    expect(screen.getByText('You drafted 1995-96')).toBeInTheDocument()
    expect(room()).toBe(region)
    expect(region).toHaveTextContent('The draft is complete.')
  })
})

describe('HistoricalRoom: the players list keeps its state (CR-09)', () => {
  it('keeps the search text and position filter across Players, Queue, Players, and hides the pane meanwhile', async () => {
    getHistoricalGame.mockResolvedValue(fourTeams())
    localStorage.setItem('bk.tm.revealSpeed', 'INSTANT')
    renderRoom()
    await screen.findByText("You're drafting 1995-96")
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search players' }), { target: { value: 'second' } })
    fireEvent.click(within(screen.getByRole('group', { name: 'Position filter' })).getByRole('button', { name: 'SF' }))
    fireEvent.click(screen.getByRole('button', { name: 'Queue (0)' }))
    expect(screen.queryByRole('region', { name: 'Available players' })).not.toBeInTheDocument()
    expect(screen.queryByRole('searchbox', { name: 'Search players' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Players' }))
    expect(screen.getByRole('searchbox', { name: 'Search players' })).toHaveValue('second')
    expect(within(screen.getByRole('group', { name: 'Position filter' })).getByRole('button', { name: 'SF' })).toHaveAttribute('aria-pressed', 'true')
    const list = screen.getByRole('region', { name: 'Available players' })
    expect(within(list).getByText('Second Guy')).toBeInTheDocument()
    expect(within(list).queryByText('Top Guy')).not.toBeInTheDocument()
  })

  it('the star has one fixed name and carries its state in aria-pressed (CR-08)', async () => {
    getHistoricalGame.mockResolvedValue(fourTeams())
    localStorage.setItem('bk.tm.revealSpeed', 'INSTANT')
    renderRoom()
    await screen.findByText("You're drafting 1995-96")
    const star = screen.getByRole('button', { name: 'Queue Top Guy' })
    expect(star).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(star)
    expect(screen.getByRole('button', { name: 'Queue Top Guy' })).toHaveAttribute('aria-pressed', 'true')
  })
})
