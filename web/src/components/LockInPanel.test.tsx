import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LiveCall, LockBaselineFigure, LockInReport, LockInThresholds, LockPayoffCell, LockSummary } from '../api'
import { ApiError } from '../apiError'

const fetchLockIns = vi.fn()
vi.mock('../api', () => ({ fetchLockIns: (...a: unknown[]) => fetchLockIns(...a) }))

import LockInPanel, { LockInPanelView, orderedPlayers, signedPct } from './LockInPanel'

const fig = (n: number, median: number | null = null, q1: number | null = null, q3: number | null = null): LockBaselineFigure => ({
  n, median, q1, q3,
})

function summary(key: string, name: string, standIn: LockBaselineFigure, projection: LockBaselineFigure,
                 rodeOut = 70): LockSummary {
  return { key, name, avatarId: null, standIn, projection, rodeOut, tieNonLast: 1 }
}

function report(over: Partial<LockInReport> = {}): LockInReport {
  return {
    sport: 'nba', season: 2025, status: 'MEASURED', reason: null, minLocks: 10, minCell: 15,
    bands: [0, 0.1, 0.25, 0.5], gamesLeftCap: 3, minPriorGames: 5, weeksCovered: 21,
    totals: {
      multiGame: 2168, lock: 1188, rodeOut: 894, rodeOutMissed: 14, tieNonLast: 10, tieWithLast: 62, unmatched: 0,
      singleGame: 92, noGames: 1, noBaselineStandIn: 127, noSnapshot: 1188, nonPositiveProjection: 0,
    },
    managers: [
      summary('7', 'popsharky', fig(87, 0.468, 0.25, 0.71), fig(12, 0.31, 0.12, 0.5)),
      summary('9', 'nuggetchef', fig(4), fig(2), 55),
      summary('11', 'xonicboom', fig(76, 0.329, 0.16, 0.52), fig(0), 61),
    ],
    players: [], payoff: { STAND_IN: [], PROJECTION: [] }, locks: [], excludedCategories: [],
    caveats: [
      'Locks are inferred: Sleeper does not publish which game a manager locked.',
      "Describes this league's past weeks; not a prediction.",
    ],
    thresholds: null, live: null,
    ...over,
  }
}

const row = (name: string) => screen.getByRole('listitem', { name })

describe('LockInPanelView', () => {
  it('shows each manager with the baseline it is measured against and the lock count (SC-004)', () => {
    render(<LockInPanelView r={report()} />)
    // Opens on the stand-in, and says so on the axis caption and the selected tab.
    expect(screen.getByRole('tab', { name: 'Season-average stand-in' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText(/season average before it/)).toBeInTheDocument()
    const r = row('popsharky')
    expect(r).toHaveTextContent('+47%')
    expect(r).toHaveTextContent('87 locks')
    expect(r).toHaveTextContent('70 rode out')
    // The exact quartiles are printed beside the bar, not left to the bar's length.
    expect(r).toHaveTextContent('middle half +25% to +71%')
  })

  it('says "n locks — too few" below the minimum instead of printing a median', () => {
    render(<LockInPanelView r={report()} />)
    const r = row('nuggetchef')
    expect(r).toHaveTextContent('4 locks — too few')
    expect(r).not.toHaveTextContent('%')
    expect(r).not.toHaveTextContent('middle half')
  })

  it('lists the enough-locks managers first, highest median on top, and the too-few ones last', () => {
    render(<LockInPanelView r={report()} />)
    const names = screen.getAllByRole('listitem').map((li) => li.getAttribute('aria-label')).filter(Boolean)
    expect(names).toEqual(['popsharky', 'xonicboom', 'nuggetchef'])
  })

  it('switches every figure to the projection when the toggle is clicked, and back', async () => {
    render(<LockInPanelView r={report()} />)
    await userEvent.click(screen.getByRole('tab', { name: 'Projection' }))
    expect(screen.getByRole('tab', { name: 'Projection' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText(/frozen pre-game projection/)).toBeInTheDocument()
    expect(row('popsharky')).toHaveTextContent('+31%')
    expect(row('popsharky')).toHaveTextContent('12 locks')
    expect(row('popsharky')).not.toHaveTextContent('+47%')
    expect(row('xonicboom')).toHaveTextContent('0 locks — too few')
    // What this baseline left out changes with it.
    expect(screen.getByText(/1188 locks have no frozen projection/)).toBeInTheDocument()
    expect(screen.queryByText(/have no stand-in/)).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('tab', { name: 'Season-average stand-in' }))
    expect(row('popsharky')).toHaveTextContent('+47%')
    expect(screen.getByText(/127 locks have no stand-in \(fewer than 5 earlier games/)).toBeInTheDocument()
  })

  it('renders the caveats and the tie exclusion from the payload', () => {
    render(<LockInPanelView r={report()} />)
    expect(screen.getByText(/Locks are inferred/)).toBeInTheDocument()
    expect(screen.getByText(/not a prediction/)).toBeInTheDocument()
    expect(screen.getByText(/10 locks tied several games/)).toBeInTheDocument()
    // provable locks = lock + tieNonLast
    expect(within(document.querySelector('.lockin-summary') as HTMLElement).getByText('1198')).toBeInTheDocument()
  })

  it('formats a margin as a signed whole percent with a real minus', () => {
    expect(signedPct(0.468)).toBe('+47%')
    expect(signedPct(-0.081)).toBe('−8%')
    expect(signedPct(0.001)).toBe('0%')
  })
})

describe('LockInPanel', () => {
  beforeEach(() => fetchLockIns.mockReset())

  it.each(['NOT_NBA', 'NOT_SLEEPER', 'GAME_MODE'])('draws nothing for %s', async (status) => {
    fetchLockIns.mockResolvedValueOnce(report({ status: status as LockInReport['status'], reason: 'nope', managers: [] }))
    const { container } = render(<LockInPanel sleeperLeagueId="L1" />)
    await waitFor(() => expect(fetchLockIns).toHaveBeenCalledWith('L1', { live: true }))
    await waitFor(() => expect(container).toBeEmptyDOMElement())
  })

  it('draws nothing when the server refuses the platform (422)', async () => {
    fetchLockIns.mockRejectedValueOnce(new ApiError(422, 'PLATFORM_NOT_SUPPORTED'))
    const { container } = render(<LockInPanel sleeperLeagueId="espn-1" />)
    await waitFor(() => expect(container).toBeEmptyDOMElement())
  })

  it('says why in one line for the other refusals', async () => {
    fetchLockIns.mockResolvedValueOnce(report({
      status: 'NO_SCORED_WEEKS', reason: 'No final scored weeks are stored for this league yet.', managers: [],
    }))
    render(<LockInPanel sleeperLeagueId="L1" />)
    expect(await screen.findByText('No final scored weeks are stored for this league yet.')).toBeInTheDocument()
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
  })

  it("clears the previous league's report while the next league loads", async () => {
    fetchLockIns.mockResolvedValueOnce(report())
    const { rerender } = render(<LockInPanel sleeperLeagueId="L1" />)
    expect(await screen.findByRole('listitem', { name: 'popsharky' })).toBeInTheDocument()
    fetchLockIns.mockReturnValueOnce(new Promise(() => {}))          // L2 never answers in this test
    rerender(<LockInPanel sleeperLeagueId="L2" />)
    await waitFor(() => expect(screen.queryByRole('listitem', { name: 'popsharky' })).not.toBeInTheDocument())
    expect(screen.getByText('Loading…')).toBeInTheDocument()
  })

  it('shows the measured panel once loaded, and a plain message on a failed load', async () => {
    fetchLockIns.mockResolvedValueOnce(report())
    const { unmount } = render(<LockInPanel sleeperLeagueId="L1" />)
    expect(await screen.findByRole('heading', { name: 'Lock-ins' })).toBeInTheDocument()
    expect(await screen.findByRole('listitem', { name: 'popsharky' })).toBeInTheDocument()
    unmount()

    fetchLockIns.mockRejectedValueOnce(new ApiError(500))
    render(<LockInPanel sleeperLeagueId="L1" />)
    expect(await screen.findByText(/couldn't be loaded just now/)).toBeInTheDocument()
  })
})

// --- US2: players ----------------------------------------------------------------------------------

const playerRow = (key: string, name: string, standIn: LockBaselineFigure) => summary(key, name, standIn, fig(0), 5)

describe('players block (US2)', () => {
  const players = [
    playerRow('a', 'Few Locks', fig(4, null)),
    playerRow('b', 'Steady', fig(30, 0.2, 0.1, 0.3)),
    playerRow('c', 'Streaky', fig(12, 0.6, 0.2, 0.9)),
    playerRow('d', 'Also Few', fig(9, null)),
  ]

  it('sorts by median or by lock count, and a row below the minimum is always after the rows above it', () => {
    expect(orderedPlayers(players, 'STAND_IN', 'median').map((p) => p.key)).toEqual(['c', 'b', 'd', 'a'])
    expect(orderedPlayers(players, 'STAND_IN', 'n').map((p) => p.key)).toEqual(['b', 'c', 'd', 'a'])
  })

  it('renders them with a count, "too few" under the minimum, and re-sorts when asked', async () => {
    render(<LockInPanelView r={report({ players })} />)
    const block = within(screen.getByRole('region', { name: 'Players' }))
    expect(block.getAllByRole('listitem').map((li) => li.getAttribute('aria-label'))).toEqual(['Streaky', 'Steady', 'Also Few', 'Few Locks'])
    expect(block.getByRole('listitem', { name: 'Streaky' })).toHaveTextContent('+60%')
    expect(block.getByRole('listitem', { name: 'Streaky' })).toHaveTextContent('12 locks')
    expect(block.getByRole('listitem', { name: 'Few Locks' })).toHaveTextContent('4 locks — too few')
    expect(block.getByRole('listitem', { name: 'Few Locks' })).not.toHaveTextContent('%')
    await userEvent.click(block.getByRole('button', { name: 'Lock count' }))
    expect(block.getAllByRole('listitem').map((li) => li.getAttribute('aria-label'))).toEqual(['Steady', 'Streaky', 'Also Few', 'Few Locks'])
  })

  it('follows the baseline toggle: the projection has none of these players enough', async () => {
    render(<LockInPanelView r={report({ players })} />)
    await userEvent.click(screen.getByRole('tab', { name: 'Projection' }))
    const block = within(screen.getByRole('region', { name: 'Players' }))
    expect(block.getByRole('listitem', { name: 'Streaky' })).toHaveTextContent('0 locks — too few')
  })
})

// --- US3: the payoff grid --------------------------------------------------------------------------

const cell = (band: string, gamesLeft: number, n: number, rateLast: number | null, rateAny: number | null): LockPayoffCell => ({
  band, gamesLeft, n, rateLast, rateAny,
})

describe('payoff grid (US3)', () => {
  const payoff = {
    STAND_IN: [
      cell('<0', 1, 40, 0.55, 0.55),
      cell('<0', 2, 3, null, null),
      cell('50+', 1, 210, 0.062, 0.062),
      cell('50+', 2, 120, 0.2, 0.41),
    ],
    PROJECTION: [cell('<0', 1, 5, null, null), cell('50+', 1, 2, null, null)],
  }

  it('headlines "riding it out beat it" with the count, and puts the in-hindsight rate smaller beside it', () => {
    render(<LockInPanelView r={report({ payoff })} />)
    const c = screen.getByTestId('cell-50+-2')
    expect(c).toHaveTextContent('riding it out beat it 20% (120)')
    expect(c).toHaveTextContent('in hindsight 41%')
    expect(screen.getByTestId('cell-<0-1')).toHaveTextContent('riding it out beat it 55% (40)')
  })

  it('shows the count but no rate below the minimum sample', () => {
    render(<LockInPanelView r={report({ payoff })} />)
    const c = screen.getByTestId('cell-<0-2')
    expect(c).toHaveTextContent('3 locks — too few')
    expect(c).not.toHaveTextContent('%')
  })

  it('says it describes past weeks, not a prediction, and what a later game means', () => {
    render(<LockInPanelView r={report({ payoff })} />)
    const g = screen.getByRole('region', { name: 'When locking paid off' })
    expect(g).toHaveTextContent("describes this league's past weeks; it is not a prediction")
    expect(g).toHaveTextContent('a game the player actually played')
  })

  it('switches to the projection grid with the baseline toggle', async () => {
    render(<LockInPanelView r={report({ payoff })} />)
    await userEvent.click(screen.getByRole('tab', { name: 'Projection' }))
    expect(screen.getByRole('region', { name: 'When locking paid off' })).toHaveTextContent('above the projection')
    expect(screen.getByTestId('cell-50+-1')).toHaveTextContent('2 locks — too few')
    expect(screen.queryByTestId('cell-50+-2')).not.toBeInTheDocument()
  })

  it('labels the bottom row by the selected baseline, never "below average" under the projection', async () => {
    render(<LockInPanelView r={report({ payoff })} />)
    expect(screen.getByRole('rowheader', { name: 'below average' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: 'Projection' }))
    expect(screen.getByRole('rowheader', { name: 'below projection' })).toBeInTheDocument()
    expect(screen.queryByRole('rowheader', { name: 'below average' })).not.toBeInTheDocument()
  })

  it('draws no grid when the payload has no cells', () => {
    render(<LockInPanelView r={report()} />)
    expect(screen.queryByRole('region', { name: 'When locking paid off' })).not.toBeInTheDocument()
  })
})

// --- US4: thresholds and this week's calls -----------------------------------------------------------

const thresholds: LockInThresholds = {
  status: 'OK', from: [2024, 2025], pMissed: 0.031,
  byGamesLeft: [
    { gamesLeft: 1, threshold: 0.068, n: 13210 }, { gamesLeft: 2, threshold: 0.248, n: 13210 },
    { gamesLeft: 3, threshold: 0.358, n: 13210 }, { gamesLeft: 4, threshold: 0.438, n: 13210 },
  ],
}

function call(over: Partial<LiveCall>): LiveCall {
  return {
    rosterId: 1, managerId: 7, manager: 'popsharky', playerId: 'p1', player: 'Paul George', team: 'BOS',
    latestGame: { gameId: 'g1', gameDate: '2026-10-20', points: 37.5 }, baseline: 21.5, baselineKind: 'LAST_SEASON',
    margin: 0.746, gamesLeft: 1, threshold: 0.068, call: 'LOCK', ...over,
  }
}

describe('thresholds and this week\'s calls (US4)', () => {
  const live = (calls: LiveCall[], status = 'OK') => ({ week: 1, asOf: '2026-10-22', status, calls })

  it('prints the bar for each games-left count with the sample and the seasons it was learned from', () => {
    render(<LockInPanelView r={report({ thresholds })} />)
    const strip = screen.getByLabelText('Lock thresholds')
    expect(strip).toHaveTextContent('1 left')
    expect(strip).toHaveTextContent('+7%')
    expect(strip).toHaveTextContent('2 left')
    expect(strip).toHaveTextContent('+25%')
    expect(strip).toHaveTextContent('4+ left')
    expect(strip).toHaveTextContent('+44%')
    expect(strip).toHaveTextContent('13,210 starter games in 2024 + 2025')
    expect(strip).toHaveTextContent('3.1%')
  })

  it('shows each call with its baseline label, margin, games left, bar and a Lock or Wait pill', () => {
    const calls = [
      call({}),
      call({ playerId: 'p2', player: 'Ausar Thompson', manager: 'slapdicks', margin: 0.247, baseline: 18, baselineKind: 'STAND_IN', gamesLeft: 2, threshold: 0.248, call: 'WAIT' }),
      call({ playerId: 'p3', player: 'Rookie', baseline: null, baselineKind: null, margin: null, threshold: null, gamesLeft: 3, call: 'TOO_EARLY' }),
    ]
    render(<LockInPanelView r={report({ live: live(calls), thresholds })} />)
    const block = within(screen.getByRole('region', { name: "This week's calls" }))
    const pg = block.getByRole('listitem', { name: 'Paul George' })
    expect(pg).toHaveTextContent('37.5 pts')
    expect(pg).toHaveTextContent("+75% vs last season's avg 21.5")
    expect(pg).toHaveTextContent('1 left · bar +7%')
    expect(pg).toHaveTextContent('Lock')
    const at = block.getByRole('listitem', { name: 'Ausar Thompson' })
    expect(at).toHaveTextContent('vs season avg 18.0')
    expect(at).toHaveTextContent('Wait')
    expect(block.getByRole('listitem', { name: 'Rookie' })).toHaveTextContent('too early to call')
    expect(block.getByRole('listitem', { name: 'Rookie' })).toHaveTextContent('Too early')
  })

  it('states the rule is learned from past weeks and is not a guarantee', () => {
    render(<LockInPanelView r={report({ live: live([call({})]), thresholds })} />)
    const block = screen.getByRole('region', { name: "This week's calls" })
    expect(block).toHaveTextContent('learned from past weeks')
    expect(block).toHaveTextContent("doesn't know about injuries or rest days")
    expect(block).toHaveTextContent("isn't a guarantee")
  })

  it('says why there are no calls: no history, no rosters, nobody yet', () => {
    const { rerender } = render(<LockInPanelView r={report({ live: live([], 'NO_HISTORY') })} />)
    expect(screen.getByText(/No completed lock-in season in this league's history to learn from/)).toBeInTheDocument()
    rerender(<LockInPanelView r={report({ live: live([], 'ROSTERS_FAILED') })} />)
    expect(screen.getByText(/lineups couldn't be read from Sleeper/)).toBeInTheDocument()
    rerender(<LockInPanelView r={report({ live: live([]) })} />)
    expect(screen.getByText(/No starter has played this week with games still to come/)).toBeInTheDocument()
  })

  it('says thresholds cannot be learned when the chain has no completed season', () => {
    render(<LockInPanelView r={report({ thresholds: { status: 'NO_HISTORY', from: [], pMissed: null, byGamesLeft: [] } })} />)
    expect(screen.getByText(/to learn thresholds from, so no Lock\/Wait calls can be made/)).toBeInTheDocument()
  })
})

describe('LockInPanel in a first week (US4)', () => {
  beforeEach(() => fetchLockIns.mockReset())

  it('shows the live calls and thresholds even though no week has been scored yet', async () => {
    fetchLockIns.mockResolvedValueOnce(report({
      status: 'NO_SCORED_WEEKS', reason: 'No final scored weeks are stored for this league yet.', managers: [],
      thresholds, live: { week: 1, asOf: '2026-10-22', status: 'OK', calls: [call({})] },
    }))
    render(<LockInPanel sleeperLeagueId="L1" />)
    expect(await screen.findByText('No final scored weeks are stored for this league yet.')).toBeInTheDocument()
    expect(screen.getByRole('listitem', { name: 'Paul George' })).toHaveTextContent('Lock')
    expect(screen.getByLabelText('Lock thresholds')).toBeInTheDocument()
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
  })
})

describe('live-verification polish (2026-10-22)', () => {
  const zeroProj = () => report({
    managers: [
      summary('7', 'popsharky', fig(87, 0.468, 0.25, 0.71), fig(0)),
      summary('9', 'nuggetchef', fig(40, 0.1, 0.0, 0.2), fig(0)),
    ],
    players: [{ key: 'p1', name: 'Paul George', avatarId: null, standIn: fig(30, 0.2, 0.1, 0.3), projection: fig(0), rodeOut: 0, tieNonLast: 0 }],
    payoff: {
      STAND_IN: [],
      PROJECTION: [{ band: '50+', gamesLeft: 1, n: 0, rateLast: null, rateAny: null }],
    },
  })

  it('P1: a season with no projection-backed locks shows one empty-state line instead of 0-lock rows', async () => {
    render(<LockInPanelView r={zeroProj()} />)
    await userEvent.click(screen.getByRole('tab', { name: 'Projection' }))
    expect(screen.getByText(/No frozen pre-game projections cover this season's locks/)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Managers' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Players' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'When locking paid off' })).not.toBeInTheDocument()
    expect(screen.queryByText('0 locks — too few')).not.toBeInTheDocument()
    // The stand-in view is untouched.
    await userEvent.click(screen.getByRole('tab', { name: 'Season-average stand-in' }))
    expect(screen.getByRole('region', { name: 'Managers' })).toBeInTheDocument()
  })

  it('P2: the top band reads 50%+ and a middle band 0–10%', () => {
    const payoff = {
      STAND_IN: [
        { band: '0-10', gamesLeft: 1, n: 30, rateLast: 0.5, rateAny: 0.6 },
        { band: '50+', gamesLeft: 1, n: 30, rateLast: 0.5, rateAny: 0.6 },
      ],
      PROJECTION: [],
    }
    render(<LockInPanelView r={report({ payoff })} />)
    expect(screen.getByRole('rowheader', { name: '50%+' })).toBeInTheDocument()
    expect(screen.getByRole('rowheader', { name: '0–10%' })).toBeInTheDocument()
    expect(screen.queryByText('50+%')).not.toBeInTheDocument()
  })

  it('P3: with the Projection toggle on, the threshold strip says it still uses the stand-in', async () => {
    render(<LockInPanelView r={report({ thresholds })} />)
    expect(screen.queryByText(/Thresholds use the season-average stand-in/)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: 'Projection' }))
    expect(within(screen.getByLabelText('Lock thresholds')).getByText(/Thresholds use the season-average stand-in/)).toBeInTheDocument()
  })
})
