import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PlayerSpotlight as PlayerSpotlightData, PlayerSpotlightApplicable } from '../api'
import HomeSpotlight, { type HomeSpotlightLeague } from './HomeSpotlight'
import { LeagueDataVersionProvider, useBumpLeagueDataVersion } from '../leagueDataVersion'

const getPlayerSpotlight = vi.fn()
const getWeeklyReport = vi.fn()
vi.mock('../api', () => ({
  getPlayerSpotlight: (...args: unknown[]) => getPlayerSpotlight(...args),
  getWeeklyReport: (...args: unknown[]) => getWeeklyReport(...args),
}))

const A: HomeSpotlightLeague = { leagueId: 'LA', name: 'Alpha League', sport: 'nfl' }
const B: HomeSpotlightLeague = { leagueId: 'LB', name: 'Beta League', sport: 'nba' }
const C: HomeSpotlightLeague = { leagueId: 'LC', name: 'Gamma League', sport: 'nfl' }

function applicable(over: Partial<PlayerSpotlightApplicable> = {}): PlayerSpotlightApplicable {
  return {
    applies: true,
    season: 2026,
    sport: 'nfl',
    playersPlayMultiplePerPeriod: false,
    period: { kind: 'WEEK', week: 3, weekFinal: true },
    periodUnavailable: null,
    laterNightInProgress: null,
    seasonStartDate: null,
    trending: { entries: [], lookbackHours: 24, fetchedAt: null, stale: false, omittedUnknownPlayers: 0, unavailable: 'NEVER_FETCHED' },
    rookieWatch: { entries: [], unavailable: 'NO_PERIOD' },
    ...over,
  }
}
const nbaSpotlight = (over: Partial<PlayerSpotlightApplicable> = {}) =>
  applicable({
    sport: 'nba',
    playersPlayMultiplePerPeriod: true,
    period: { kind: 'NIGHT', date: '2026-10-21', gamesCount: 11 },
    topOfNight: { entries: [], unavailable: 'NO_ROSTERED_PLAYED' },
    ...over,
  })

const wrap = (ui: ReactNode) => <MemoryRouter>{ui}</MemoryRouter>
const show = (leagues: HomeSpotlightLeague[]) => render(wrap(<HomeSpotlight leagues={leagues} />))

beforeEach(() => {
  getPlayerSpotlight.mockReset().mockResolvedValue(applicable())
  getWeeklyReport.mockReset().mockResolvedValue({ week: 3, topPerformers: [] })
  localStorage.clear()
})

describe('HomeSpotlight, first league (US1)', () => {
  it('U1: requests only the first league spotlight', async () => {
    show([A, B])
    await waitFor(() => expect(getPlayerSpotlight).toHaveBeenCalledTimes(1))
    expect(getPlayerSpotlight).toHaveBeenCalledWith('LA')
  })

  it('renders nothing for no leagues', () => {
    const { container } = show([])
    expect(container).toBeEmptyDOMElement()
  })

  it('U4: a spotlight with a period and no topOfNight fetches the weekly report once, week 0', async () => {
    show([A])
    await waitFor(() => expect(getWeeklyReport).toHaveBeenCalledTimes(1))
    expect(getWeeklyReport).toHaveBeenCalledWith('LA', 0)
  })

  describe('U5: no weekly report', () => {
    const cases: [string, PlayerSpotlightData][] = [
      ['topOfNight present', nbaSpotlight()],
      ['period null', applicable({ period: null, periodUnavailable: 'NO_WEEK_SCORED' })],
      ['applies false', { applies: false, reason: 'PAST_SEASON', season: 2025, sport: 'nfl' }],
    ]
    it.each(cases)('%s', async (_name, payload) => {
      getPlayerSpotlight.mockResolvedValue(payload)
      show([A])
      await waitFor(() => expect(screen.queryByRole('status', { name: 'Loading player spotlight' })).toBeNull())
      expect(getWeeklyReport).not.toHaveBeenCalled()
    })
  })

  it('U6: a failed spotlight names the league', async () => {
    getPlayerSpotlight.mockRejectedValue(new Error('boom'))
    show([A])
    expect(await screen.findByText("Couldn't load the player spotlight for Alpha League.")).toBeInTheDocument()
  })

  it('U7: a past season gets a sentence, never a blank panel', async () => {
    getPlayerSpotlight.mockResolvedValue({ applies: false, reason: 'PAST_SEASON', season: 2025, sport: 'nfl' })
    show([A])
    expect(await screen.findByText('No player spotlight for 2025: it covers the current season only.')).toBeInTheDocument()
  })

  it('shows a loading state while the spotlight is pending', () => {
    getPlayerSpotlight.mockReturnValue(new Promise(() => {}))
    show([A])
    expect(screen.getByRole('status', { name: 'Loading player spotlight' })).toBeInTheDocument()
  })
})

describe('HomeSpotlight, league tabs (US2)', () => {
  it('lists one tab per league, in order, each naming its league', () => {
    show([A, B, C])
    const strip = screen.getByRole('tablist', { name: 'Leagues' })
    const tabs = within(strip).getAllByRole('tab')
    expect(tabs).toHaveLength(3)
    expect(tabs[0].textContent).toContain('Alpha League')
    expect(tabs[1].textContent).toContain('Beta League')
    expect(tabs[2].textContent).toContain('Gamma League')
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true')
  })

  it('U2: opening a new tab requests its spotlight; the previous panel is hidden, not unmounted', async () => {
    show([A, B])
    await waitFor(() => expect(getPlayerSpotlight).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('tab', { name: /Beta League/ }))
    await waitFor(() => expect(getPlayerSpotlight).toHaveBeenCalledTimes(2))
    expect(getPlayerSpotlight).toHaveBeenLastCalledWith('LB')
    const panelA = document.getElementById('home-spot-panel-LA')
    expect(panelA).not.toBeNull()
    expect(panelA).toHaveAttribute('hidden')
    expect(document.getElementById('home-spot-panel-LB')).not.toHaveAttribute('hidden')
  })

  it('U3: returning to a visited tab makes no request and shows no skeleton', async () => {
    show([A, B])
    await waitFor(() => expect(screen.queryByRole('status', { name: 'Loading player spotlight' })).toBeNull())
    fireEvent.click(screen.getByRole('tab', { name: /Beta League/ }))
    await waitFor(() => expect(getPlayerSpotlight).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByRole('status', { name: 'Loading player spotlight' })).toBeNull())
    const calls = getPlayerSpotlight.mock.calls.length
    const weeklyCalls = getWeeklyReport.mock.calls.length
    fireEvent.click(screen.getByRole('tab', { name: /Alpha League/ }))
    expect(screen.queryByRole('status', { name: 'Loading player spotlight' })).toBeNull()
    expect(getPlayerSpotlight).toHaveBeenCalledTimes(calls)
    expect(getWeeklyReport).toHaveBeenCalledTimes(weeklyCalls)
  })

  it('U8: when the selected league leaves the list, the first league is selected', async () => {
    const { rerender } = show([A, B])
    fireEvent.click(screen.getByRole('tab', { name: /Beta League/ }))
    expect(screen.getByRole('tab', { name: /Beta League/ })).toHaveAttribute('aria-selected', 'true')
    rerender(wrap(<HomeSpotlight leagues={[A, C]} />))
    expect(screen.getByRole('tab', { name: /Alpha League/ })).toHaveAttribute('aria-selected', 'true')
    expect(document.getElementById('home-spot-panel-LB')).toBeNull()
  })

  it('U9: arrow keys move and wrap, Home/End jump to the ends', () => {
    show([A, B, C])
    const tab = (n: RegExp) => screen.getByRole('tab', { name: n })
    fireEvent.keyDown(tab(/Alpha/), { key: 'ArrowRight' })
    expect(tab(/Beta/)).toHaveAttribute('aria-selected', 'true')
    expect(tab(/Beta/)).toHaveFocus()
    fireEvent.keyDown(tab(/Beta/), { key: 'ArrowLeft' })
    fireEvent.keyDown(tab(/Alpha/), { key: 'ArrowLeft' })
    expect(tab(/Gamma/)).toHaveAttribute('aria-selected', 'true')
    fireEvent.keyDown(tab(/Gamma/), { key: 'Home' })
    expect(tab(/Alpha/)).toHaveAttribute('aria-selected', 'true')
    fireEvent.keyDown(tab(/Alpha/), { key: 'End' })
    expect(tab(/Gamma/)).toHaveAttribute('aria-selected', 'true')
  })

  it('U6 isolation: one league failing leaves the other panel intact', async () => {
    getPlayerSpotlight.mockImplementation((id: string) =>
      id === 'LB' ? Promise.reject(new Error('boom')) : Promise.resolve(applicable()),
    )
    show([A, B])
    const panelA = () => document.getElementById('home-spot-panel-LA') as HTMLElement
    await waitFor(() => expect(within(panelA()).getByRole('heading', { name: /Trending/ })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('tab', { name: /Beta League/ }))
    expect(await screen.findByText("Couldn't load the player spotlight for Beta League.")).toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: /Alpha League/ }))
    expect(within(panelA()).getByRole('heading', { name: /Trending/ })).toBeInTheDocument()
  })

  it('keeps every element id unique across mounted tabs', async () => {
    show([A, B])
    await waitFor(() => expect(screen.queryByRole('status', { name: 'Loading player spotlight' })).toBeNull())
    fireEvent.click(screen.getByRole('tab', { name: /Beta League/ }))
    await act(async () => {})
    const ids = Array.from(document.querySelectorAll('[id]')).map((el) => el.id)
    expect(new Set(ids).size).toBe(ids.length)
  })
})

describe('HomeSpotlight, Open league link (US3)', () => {
  it('points at the selected league and follows the tab', () => {
    show([A, B])
    expect(screen.getByRole('link', { name: 'Open league' })).toHaveAttribute('href', '/leagues/LA')
    fireEvent.click(screen.getByRole('tab', { name: /Beta League/ }))
    expect(screen.getByRole('link', { name: 'Open league' })).toHaveAttribute('href', '/leagues/LB')
  })
})

describe('HomeSpotlight, data refresh (T023 review)', () => {
  it('a finished refresh refetches the weekly report once, not a cancelled extra', async () => {
    let bump: (id: string) => void = () => {}
    function Bumper() {
      bump = useBumpLeagueDataVersion()
      return null
    }
    render(
      <LeagueDataVersionProvider>
        <Bumper />
        {wrap(<HomeSpotlight leagues={[A]} />)}
      </LeagueDataVersionProvider>,
    )
    await waitFor(() => expect(getWeeklyReport).toHaveBeenCalledTimes(1))
    await act(async () => bump('LA'))
    await waitFor(() => expect(getPlayerSpotlight).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(getWeeklyReport).toHaveBeenCalledTimes(2))
    // Let any stray effect run before asserting nothing more was requested.
    await act(async () => {})
    expect(getWeeklyReport).toHaveBeenCalledTimes(2)
  })
})
