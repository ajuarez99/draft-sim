import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WeeklyPot from './WeeklyPot'
import { dollarsToCents } from '../components/CommissionerPotPanel'
import type { WeeklyPotBalance, WeeklyPotOn, WeeklyPotOff } from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
}))

const getWeeklyPot = vi.fn()
const saveWeeklyPotSettings = vi.fn()
vi.mock('../api', () => ({
  getWeeklyPot: (...a: unknown[]) => getWeeklyPot(...a),
  saveWeeklyPotSettings: (...a: unknown[]) => saveWeeklyPotSettings(...a),
}))

const bal = (rosterId: number, name: string, netCents: number, over: Partial<WeeklyPotBalance> = {}): WeeklyPotBalance => ({
  rosterId,
  managerId: rosterId + 10,
  name,
  avatarId: null,
  isMe: false,
  noManager: false,
  weeksWon: 0,
  collectedCents: 0,
  paidCents: 0,
  netCents,
  ...over,
})

function on(over: Partial<WeeklyPotOn> = {}): WeeklyPotOn {
  return {
    season: 2026,
    sport: 'nfl',
    enabled: true,
    stakeCents: 100,
    canEdit: false,
    commissionerKnown: true,
    regularSeasonKnown: true,
    regularSeasonThroughWeek: 14,
    throughWeek: 2,
    latestDecidedWeek: 2,
    seasonFinal: false,
    tieCentRule: 'TIE-CENT-RULE-TEXT',
    weeks: [
      { week: 1, status: 'DECIDED', winners: [1], winningPoints: 150.5, runnerUpPoints: 140.25, payers: 2, potCents: 200 },
      { week: 2, status: 'DECIDED', winners: [2, 3], winningPoints: 130, runnerUpPoints: 120, payers: 1, potCents: 100 },
      { week: 3, status: 'NOT_FINAL', winners: [], winningPoints: null, runnerUpPoints: null, payers: 0, potCents: 0 },
      { week: 4, status: 'MISSING', winners: [], winningPoints: null, runnerUpPoints: null, payers: 0, potCents: 0 },
      { week: 5, status: 'NO_SCORES', winners: [], winningPoints: null, runnerUpPoints: null, payers: 0, potCents: 0 },
    ],
    balances: [
      bal(1, 'Alpha', 1250, { weeksWon: 1, collectedCents: 1250 }),
      bal(2, 'Bravo', 50, { isMe: true }),
      bal(3, 'Charlie', -1300, { noManager: true, managerId: null }),
    ],
    pairwise: [
      { fromRosterId: 3, toRosterId: 1, cents: 1000 },
      { fromRosterId: 3, toRosterId: 2, cents: 300 },
    ],
    shortcut: [{ fromRosterId: 3, toRosterId: 1, cents: 1300 }],
    liveLeader: null,
    liveUnavailable: false,
    ...over,
  }
}

beforeEach(() => {
  getWeeklyPot.mockReset()
  saveWeeklyPotSettings.mockReset()
})

describe('WeeklyPot page', () => {
  it('renders standings in payload order with formatted money', async () => {
    getWeeklyPot.mockResolvedValue(on())
    render(<WeeklyPot />)
    await screen.findByText('Standings')
    const rows = within(screen.getAllByRole('table')[0]).getAllByRole('row').slice(1)
    expect(rows.map((r) => within(r).getAllByRole('cell')[1].textContent)).toEqual([
      expect.stringContaining('Alpha'),
      expect.stringContaining('Bravo'),
      expect.stringContaining('Charlie'),
    ])
    expect(within(rows[0]).getByText('+$12.50')).toBeTruthy()
    expect(within(rows[2]).getByText('−$13.00')).toBeTruthy()
    expect(within(rows[2]).getByText(/had no manager/)).toBeTruthy()
  })

  it('highlights the viewer row from isMe only', async () => {
    // Bravo is isMe; Alpha is named like a username would be. Only isMe decides.
    getWeeklyPot.mockResolvedValue(on())
    render(<WeeklyPot />)
    await screen.findByText('Standings')
    const rows = within(screen.getAllByRole('table')[0]).getAllByRole('row').slice(1)
    expect(rows[0].getAttribute('data-me')).toBeNull()
    expect(rows[1].getAttribute('data-me')).toBe('true')
    expect(rows[2].getAttribute('data-me')).toBeNull()
  })

  it('shows a Tie badge and labels non-decided weeks in words', async () => {
    getWeeklyPot.mockResolvedValue(on())
    render(<WeeklyPot />)
    await screen.findByText('Week by week')
    expect(screen.getAllByText('Tie')).toHaveLength(1)
    expect(screen.getByText('Bravo & Charlie')).toBeTruthy()
    expect(screen.getByText('Not final yet')).toBeTruthy()
    expect(screen.getByText('Not loaded yet')).toBeTruthy()
    expect(screen.getByText('No scores recorded')).toBeTruthy()
    expect(screen.getByText('+10.25 over 2nd')).toBeTruthy()
    expect(screen.getByText('tied')).toBeTruthy()
    expect(screen.getByText('TIE-CENT-RULE-TEXT')).toBeTruthy()
  })

  it('labels the weeks the money includes when a gap sits below the latest decided week', async () => {
    const w = (week: number, status: WeeklyPotOn['weeks'][number]['status']) =>
      status === 'DECIDED'
        ? { week, status, winners: [1], winningPoints: 100, runnerUpPoints: 90, payers: 2, potCents: 200 }
        : { week, status, winners: [], winningPoints: null, runnerUpPoints: null, payers: 0, potCents: 0 }
    getWeeklyPot.mockResolvedValue(
      on({
        throughWeek: 1,
        latestDecidedWeek: 5,
        weeks: [w(1, 'DECIDED'), w(2, 'NOT_FINAL'), w(3, 'DECIDED'), w(4, 'MISSING'), w(5, 'DECIDED')],
      }),
    )
    render(<WeeklyPot />)
    await screen.findByText(/Settle up/)
    expect(screen.getByText(/Includes weeks through 5; weeks 2, 4 not settled yet\./)).toBeTruthy()
    expect(screen.getByText('Settle up (as of week 5)')).toBeTruthy()
  })

  it('switches between the shortcut and every-pair settle-up', async () => {
    getWeeklyPot.mockResolvedValue(on())
    render(<WeeklyPot />)
    await screen.findByText(/Settle up/)
    expect(screen.getByText('Charlie pays Alpha $13.00')).toBeTruthy()
    expect(screen.queryByText('Charlie pays Bravo $3.00')).toBeNull()
    fireEvent.click(screen.getByRole('tab', { name: 'Every pair' }))
    expect(screen.getByText('Charlie pays Alpha $10.00')).toBeTruthy()
    expect(screen.getByText('Charlie pays Bravo $3.00')).toBeTruthy()
    expect(screen.queryByText('Charlie pays Alpha $13.00')).toBeNull()
  })

  it('off state shows no pot amount and no commissioner panel to a member', async () => {
    const off: WeeklyPotOff = { season: 2026, sport: 'nfl', enabled: false, stakeCents: 500, canEdit: false, commissionerKnown: true }
    getWeeklyPot.mockResolvedValue(off)
    const { container } = render(<WeeklyPot />)
    await screen.findByText(/side bet some leagues run/)
    expect(container.textContent).not.toMatch(/\$/)
    expect(screen.queryByText('Commissioner settings')).toBeNull()
  })

  it('off state says so when no commissioner is recorded', async () => {
    const off: WeeklyPotOff = { season: 2026, sport: 'nfl', enabled: false, stakeCents: 100, canEdit: false, commissionerKnown: false }
    getWeeklyPot.mockResolvedValue(off)
    render(<WeeklyPot />)
    await screen.findByText('No commissioner is recorded for this league yet.')
  })

  it('shows the live leader as not final, and the unavailable note', async () => {
    getWeeklyPot.mockResolvedValue(on({ liveLeader: { week: 8, rosterIds: [1], points: 88.24 } }))
    const { unmount } = render(<WeeklyPot />)
    await screen.findByText(/Leading week 8/)
    expect(screen.getByText(/not final/)).toBeTruthy()
    expect(screen.getByText('88.2', { exact: false })).toBeTruthy()
    unmount()
    getWeeklyPot.mockResolvedValue(on({ liveUnavailable: true }))
    render(<WeeklyPot />)
    await screen.findByText(/check the live week/)
  })
})

describe('commissioner panel', () => {
  it('appears only when canEdit', async () => {
    getWeeklyPot.mockResolvedValue(on({ canEdit: false }))
    const { unmount } = render(<WeeklyPot />)
    await screen.findByText('Standings')
    expect(screen.queryByText('Commissioner settings')).toBeNull()
    unmount()
    getWeeklyPot.mockResolvedValue(on({ canEdit: true }))
    render(<WeeklyPot />)
    await screen.findByText('Commissioner settings')
  })

  it('converts dollars to cents and saves; rejects 3 decimals client-side', async () => {
    getWeeklyPot.mockResolvedValue(on({ canEdit: true }))
    saveWeeklyPotSettings.mockResolvedValue(on({ canEdit: true, stakeCents: 150 }))
    render(<WeeklyPot />)
    const input = (await screen.findByLabelText('Stake per week in dollars')) as HTMLInputElement
    fireEvent.change(input, { target: { value: '1.234' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByRole('alert')
    expect(saveWeeklyPotSettings).not.toHaveBeenCalled()
    fireEvent.change(input, { target: { value: '1.5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(saveWeeklyPotSettings).toHaveBeenCalledWith('L1', { enabled: true, stakeCents: 150 }))
  })

  it('shows the server message on a refused save', async () => {
    getWeeklyPot.mockResolvedValue(on({ canEdit: true }))
    saveWeeklyPotSettings.mockRejectedValue(new Error('only this league’s Sleeper commissioner may change the weekly pot'))
    render(<WeeklyPot />)
    await screen.findByText('Commissioner settings')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByText(/only this league/)
  })
})

describe('dollarsToCents', () => {
  it('converts and bounds', () => {
    expect(dollarsToCents('0.29')).toBe(29)
    expect(dollarsToCents('1.5')).toBe(150)
    expect(dollarsToCents('1000')).toBe(100000)
    expect(dollarsToCents('1.234')).toBeNull()
    expect(dollarsToCents('0')).toBeNull()
    expect(dollarsToCents('1000.01')).toBeNull()
    expect(dollarsToCents('')).toBeNull()
    expect(dollarsToCents('abc')).toBeNull()
    expect(dollarsToCents('-1')).toBeNull()
  })
})
