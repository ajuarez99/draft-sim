import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ManagerTendencies from './ManagerTendencies'
import type { ManagerSummary } from '../api'

const getManagers = vi.fn()
const getLeagueHistory = vi.fn()
const getDrafts = vi.fn()
vi.mock('../api', () => ({
  getManagers: (...args: unknown[]) => getManagers(...args),
  getLeagueHistory: (...args: unknown[]) => getLeagueHistory(...args),
  getDrafts: (...args: unknown[]) => getDrafts(...args),
}))

function m(over: Partial<ManagerSummary>): ManagerSummary {
  return {
    managerId: 1,
    manager: 'A',
    avatarId: null,
    provenance: 'FITTED',
    effectiveReachBias: 6,
    empiricalReachBias: 9,
    relativeReachBias: null,
    relativeReachStdErr: null,
    unpredictability: 1,
    positionalTilt: {},
    note: null,
    draftsObserved: 1,
    picksScored: 15,
    stated: { reachBias: null, unpredictability: null, note: null },
    draftHistory: [],
    careers: [],
    ...over,
  } as ManagerSummary
}

beforeEach(() => {
  localStorage.clear()
  getManagers.mockReset()
  getLeagueHistory.mockReset()
  getDrafts.mockReset()
})

describe('ManagerTendencies reach display (audit 11)', () => {
  it('states the room-relative frame and never falls back to the absolute figure', async () => {
    getManagers.mockImplementation((sport: string) =>
      Promise.resolve(
        sport === 'nfl'
          ? [
              m({ managerId: 1, manager: 'Early Eddie', relativeReachBias: 12, relativeReachStdErr: 4 }),
              m({ managerId: 2, manager: 'Late Larry', relativeReachBias: -9.5, relativeReachStdErr: 3 }),
              m({ managerId: 3, manager: 'Middle Mike', relativeReachBias: 2, relativeReachStdErr: 5 }),
              m({ managerId: 4, manager: 'No Score', picksScored: 0, relativeReachBias: null, effectiveReachBias: 9.9 }),
            ]
          : [],
      ),
    )
    render(
      <MemoryRouter>
        <ManagerTendencies />
      </MemoryRouter>,
    )
    // Said in the row and, since the archetype basis is visible text, once more beside the label.
    expect((await screen.findAllByText(/12\.0 picks earlier than their draft room/)).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/9\.5 picks later than their draft room/).length).toBeGreaterThanOrEqual(1)
    // Inside one standard error: no number claimed.
    expect(screen.getAllByText(/drafts like the room/).length).toBeGreaterThanOrEqual(1)
    expect(screen.queryByText(/(^|\D)2\.0 picks/)).not.toBeInTheDocument()
    // The manager with nothing scoreable gets the reason, not a bar built from effectiveReachBias (9.9).
    expect(screen.queryByText(/9\.9 picks/)).not.toBeInTheDocument()
    // The page says what the numbers are measured against.
    expect(screen.getByText(/same draft, not the market board/)).toBeInTheDocument()
    // Most extreme first: |12| before |-9.5| before |2|.
    const order = screen.getAllByRole('link').map((a) => a.textContent)
    expect(order.indexOf('Early Eddie')).toBeLessThan(order.indexOf('Late Larry'))
    expect(order.indexOf('Late Larry')).toBeLessThan(order.indexOf('Middle Mike'))
  })
})

// spec 013 US9 (T091): an archetype per manager, and the rail's selected league first.
describe('ManagerTendencies archetypes and league-first order', () => {
  const nfl = [
    m({ managerId: 1, manager: 'Early Eddie', relativeReachBias: 12, relativeReachStdErr: 4 }),
    m({ managerId: 2, manager: 'Late Larry', relativeReachBias: -9.5, relativeReachStdErr: 3 }),
    m({ managerId: 3, manager: 'Middle Mike', relativeReachBias: 2, relativeReachStdErr: 5 }),
    m({ managerId: 4, manager: 'Tilt Tom', picksScored: 0, relativeReachBias: null, positionalTilt: { QB: 1.5 } }),
    m({ managerId: 5, manager: 'No Info', picksScored: 0, relativeReachBias: null }),
  ]
  function withManagers() {
    getManagers.mockImplementation((sport: string) => Promise.resolve(sport === 'nfl' ? nfl : []))
  }

  it('labels each manager Reacher, Waits, Drafts like the room, a tilt, or Not enough history', async () => {
    withManagers()
    render(<MemoryRouter><ManagerTendencies /></MemoryRouter>)
    await screen.findByText('Early Eddie')
    expect(screen.getByText('Reacher')).toBeInTheDocument()
    expect(screen.getByText('Waits')).toBeInTheDocument()
    expect(screen.getByText('Drafts like the room')).toBeInTheDocument()
    expect(screen.getByText('QB early')).toBeInTheDocument()
    expect(screen.getByText('Not enough history')).toBeInTheDocument()
  })

  it('lists the managers of the rail league first when the rail passes its league along', async () => {
    withManagers()
    getDrafts.mockResolvedValue([
      { id: 1, sleeperDraftId: 'D1', leagueId: 1, leagueName: 'Our League', season: 2026, teams: 12, rounds: 15,
        status: 'complete', startTime: null, sleeperLeagueId: 'L1', previousLeagueId: null, sport: 'nfl' },
    ])
    // Only Mike and Tom are in Our League; the default order would put Eddie and Larry ahead of them.
    getLeagueHistory.mockResolvedValue({
      sleeperLeagueId: 'L1',
      seasons: [{ season: 2026, leagueId: 1, sleeperLeagueId: 'L1', name: 'Our League',
        standings: [{ managerId: 3 }, { managerId: 4 }] }],
      records: {},
    })
    render(
      <MemoryRouter initialEntries={[{ pathname: '/managers', state: { railLeagueId: 'L1' } }]}>
        <ManagerTendencies />
      </MemoryRouter>,
    )
    expect(await screen.findByText('In Our League')).toBeInTheDocument()
    expect(screen.getByText('Everyone else')).toBeInTheDocument()
    const names = screen.getAllByRole('link').map((a) => a.textContent)
    expect(names.indexOf('Middle Mike')).toBeLessThan(names.indexOf('Early Eddie'))
    expect(names.indexOf('Tilt Tom')).toBeLessThan(names.indexOf('Early Eddie'))
  })

  it("groups by the rail league's own season only, not every season in its chain", async () => {
    withManagers()
    getDrafts.mockResolvedValue([
      { id: 1, sleeperDraftId: 'D1', leagueId: 1, leagueName: 'Our League', season: 2026, teams: 12, rounds: 15,
        status: 'complete', startTime: null, sleeperLeagueId: 'L1', previousLeagueId: 'L0', sport: 'nfl' },
    ])
    // Eddie played in an earlier season of the chain only; he must not count as "in" the league.
    getLeagueHistory.mockResolvedValue({
      sleeperLeagueId: 'L1',
      seasons: [
        { season: 2026, leagueId: 1, sleeperLeagueId: 'L1', name: 'Our League', standings: [{ managerId: 3 }] },
        { season: 2025, leagueId: 0, sleeperLeagueId: 'L0', name: 'Our League', standings: [{ managerId: 1 }] },
      ],
      records: {},
    })
    render(
      <MemoryRouter initialEntries={[{ pathname: '/managers', state: { railLeagueId: 'L1' } }]}>
        <ManagerTendencies />
      </MemoryRouter>,
    )
    await screen.findByText('In Our League')
    const names = screen.getAllByRole('link').map((a) => a.textContent)
    expect(names.indexOf('Middle Mike')).toBeLessThan(names.indexOf('Early Eddie'))
    // Eddie only appears in the older season, so he is with everyone else.
    expect(screen.getByText('Everyone else')).toBeInTheDocument()
  })

  it('drops the grouping when the rail league cannot be resolved', async () => {
    withManagers()
    getDrafts.mockResolvedValue([])
    getLeagueHistory.mockResolvedValue({ sleeperLeagueId: 'L1', seasons: [], records: {} })
    render(
      <MemoryRouter initialEntries={[{ pathname: '/managers', state: { railLeagueId: 'L1' } }]}>
        <ManagerTendencies />
      </MemoryRouter>,
    )
    await screen.findByText('Early Eddie')
    expect(screen.queryByText('Everyone else')).not.toBeInTheDocument()
  })

  it('shows the archetype evidence as visible text, not only a tooltip', async () => {
    withManagers()
    render(<MemoryRouter><ManagerTendencies /></MemoryRouter>)
    await screen.findByText('Early Eddie')
    const chip = screen.getByText('Reacher')
    const basis = chip.getAttribute('title')
    expect(basis).toBeTruthy()
    expect(screen.getAllByText(basis as string).length).toBeGreaterThanOrEqual(1)
  })

  it('does not group at all when no league is selected', async () => {
    withManagers()
    render(<MemoryRouter><ManagerTendencies /></MemoryRouter>)
    await screen.findByText('Early Eddie')
    expect(screen.queryByText('Everyone else')).not.toBeInTheDocument()
    expect(getLeagueHistory).not.toHaveBeenCalled()
  })
})
