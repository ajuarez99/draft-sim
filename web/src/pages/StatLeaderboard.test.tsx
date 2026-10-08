import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import StatLeaderboard from './StatLeaderboard'
import { LeagueDataVersionProvider } from '../leagueDataVersion'
import type { StatLeaderboard as Board } from '../api'
import { board, owner, row, win } from '../testStatBoard'

const getStatLeaderboard = vi.fn()
vi.mock('../api', () => ({
  getStatLeaderboard: (...args: unknown[]) => getStatLeaderboard(...args),
}))

const rows = () => [
  row('1', { name: 'Ann Alpha', fpPerGame: 40, stats: win({ pg: { pts: 20 } }), leagueRank: 1, draft: { pickNo: 3, round: 1, managerName: 'Dunk Tank' } }),
  row('2', { name: 'Bob Beta', fpPerGame: 30, stats: win({ pg: { pts: 30 } }), leagueRank: 2, positions: ['C'], team: 'DEN' }),
  row('3', {
    name: 'Cy Free', fpPerGame: 20, stats: win({ pg: { pts: 10 } }), leagueRank: 3, positions: ['SF'],
    ownership: owner({ state: 'FREE_AGENT', rosterId: null, ownerName: null }), draft: null, draftValue: null, adp: null,
  }),
  row('4', {
    name: 'Dee Stale', fpPerGame: 50, qualified: false, reason: 'NOT_QUALIFIED_STALE', leagueRank: null, positionRank: null,
    pointsRank: null, rankMove: null, valueOverReplacement: null, vorPosition: null,
    stats: win({ lastGameDate: '2026-01-05', pg: { pts: 40 } }),
  }),
]

function mount(url = '/leagues/L25/stats') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <LeagueDataVersionProvider>
        <Routes>
          <Route path="/leagues/:sleeperLeagueId/stats" element={<StatLeaderboard />} />
        </Routes>
      </LeagueDataVersionProvider>
    </MemoryRouter>,
  )
}

async function show(b: Board = board(rows())) {
  getStatLeaderboard.mockResolvedValue(b)
  mount()
  return screen.findByRole('region', { name: 'Player stats' })
}

/** The player names in the table, top to bottom. */
const order = () =>
  within(screen.getByRole('region', { name: 'Player stats' }))
    .getAllByRole('rowheader')
    .map((th) => th.querySelector('a')?.textContent)

const group = (name: string) => fireEvent.click(within(screen.getByRole('group', { name: 'Column group' })).getByRole('button', { name }))

beforeEach(() => {
  getStatLeaderboard.mockReset()
})

describe('StatLeaderboard', () => {
  it('asks for the season window of the league in the URL, then refetches on a window change', async () => {
    await show()
    expect(getStatLeaderboard).toHaveBeenCalledWith('L25', 'SEASON')
    fireEvent.click(within(screen.getByRole('group', { name: 'Window' })).getByRole('button', { name: 'Last 10' }))
    await waitFor(() => expect(getStatLeaderboard).toHaveBeenCalledWith('L25', 'LAST_10'))
    await screen.findByRole('region', { name: 'Player stats' })
  })

  it('lists qualified players by fantasy points per game, hides the unqualified, and sorts on a header click', async () => {
    await show()
    expect(order()).toEqual(['Ann Alpha', 'Bob Beta', 'Cy Free'])
    group('Basic')
    // Basic has no FP/G column, so the sort falls back to the group's default: points, best first.
    // PTS is a counting column, so the qualification switch does not apply and the stale scorer is listed.
    expect(order()).toEqual(['Dee Stale', 'Bob Beta', 'Ann Alpha', 'Cy Free'])
    fireEvent.click(screen.getByRole('button', { name: 'PTS' }))
    expect(order()).toEqual(['Cy Free', 'Ann Alpha', 'Bob Beta', 'Dee Stale'])
    expect(screen.getByRole('columnheader', { name: /PTS/ })).toHaveAttribute('aria-sort', 'ascending')
  })

  it('keeps the window, filters and a still-valid sort when the group changes', async () => {
    await show()
    fireEvent.change(screen.getByLabelText('Position'), { target: { value: 'C' } })
    expect(order()).toEqual(['Bob Beta'])
    fireEvent.click(screen.getByRole('button', { name: 'Rank' }))
    group('Draft value')
    expect(screen.getByLabelText('Position')).toHaveValue('C')
    expect(order()).toEqual(['Bob Beta'])
    // The league-rank sort is in the draft group too, so it survives.
    expect(screen.getByRole('columnheader', { name: /Rank/ })).toHaveAttribute('aria-sort', 'ascending')
    expect(within(screen.getByRole('group', { name: 'Window' })).getByRole('button', { name: 'Season' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('pins the player column and scrolls the table inside its own container', async () => {
    const region = await show()
    const wrap = region.querySelector('.sl-wrap')!
    expect(wrap).toBeInTheDocument()
    expect(wrap.querySelector('thead th.sl-pin')).toBeInTheDocument()
    for (const th of wrap.querySelectorAll('tbody th')) expect(th).toHaveClass('sl-pin')
  })

  it('filters by NBA team, availability and one manager roster', async () => {
    await show()
    fireEvent.change(screen.getByLabelText('NBA team'), { target: { value: 'DEN' } })
    expect(order()).toEqual(['Bob Beta'])
    fireEvent.change(screen.getByLabelText('NBA team'), { target: { value: '' } })
    fireEvent.change(screen.getByLabelText('Availability'), { target: { value: 'free' } })
    expect(order()).toEqual(['Cy Free'])
    fireEvent.change(screen.getByLabelText('Availability'), { target: { value: 'roster:1' } })
    expect(order()).toEqual(['Ann Alpha', 'Bob Beta'])
  })

  it('states the ownership date', async () => {
    await show()
    expect(screen.getByText(/Ownership: End of 2025–26 regular season \(week 18\)/)).toBeInTheDocument()
  })

  it('draws the qualification rule from the payload and lists the unqualified when switched off', async () => {
    await show(board(rows(), { qualification: { minGamesShare: 0.4, minGames: 33, maxTeamGames: 82, minMinutesPerGame: 12, recencyDays: 10 } }))
    expect(screen.getByText(/at least 33 games/)).toBeInTheDocument()
    expect(screen.getByText(/12\+ minutes per game/)).toBeInTheDocument()
    expect(screen.queryByText('Dee Stale')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Qualified players only' }))
    expect(screen.getByText('Dee Stale')).toBeInTheDocument()
    expect(screen.getByText(/Hasn’t played since Jan 5, 2026/)).toBeInTheDocument()
    // Unqualified, so no rank and no value over replacement, said in the cell's tooltip rather than a 0.
    expect(order()).toContain('Dee Stale')
  })

  it('shows replacement levels per position and calls the rule a simplification', async () => {
    await show()
    expect(screen.getByText(/PG 16\.5/)).toBeInTheDocument()
    expect(screen.getByText(/labelled a simplification/)).toBeInTheDocument()
  })

  describe('draft columns', () => {
    it('says undrafted when the draft is complete and the player has no pick, and links Draft Grades', async () => {
      await show()
      group('Draft value')
      const table = screen.getByRole('region', { name: 'Player stats' })
      expect(within(table).getByText('undrafted')).toBeInTheDocument()
      expect(within(table).getAllByText('+3.2').length).toBeGreaterThan(0)
      const link = screen.getByRole('link', { name: 'Draft Grades' })
      expect(link).toHaveAttribute('href', '/drafts/D1/board')
      expect(screen.getByText(/summed over the league’s counted weeks/)).toBeInTheDocument()
      expect(screen.getByText(/\(18 weeks counted\)/)).toBeInTheDocument()
      expect(screen.getByText(/Pick, round, drafted-by and ADP are from the 2025–26 draft/)).toBeInTheDocument()
    })

    it('says the draft has not happened, with no zero pick', async () => {
      await show(board(rows().map((r) => ({ ...r, draft: null, draftValue: null })), {
        draft: { state: 'NOT_HAPPENED', draftId: 'D9', draftSeason: 2026 },
        draftGrades: { available: false, reason: 'DRAFT_NOT_COMPLETE', gradesEarly: false, weeksCounted: 0 },
      }))
      group('Draft value')
      expect(screen.getAllByText(/The draft hasn’t happened yet/).length).toBeGreaterThan(0)
      expect(screen.getByText(/Draft value appears once the draft is complete and games are scored/)).toBeInTheDocument()
      expect(screen.getByText(/are from the 2026–27 draft/)).toBeInTheDocument()
      expect(screen.queryByText('undrafted')).not.toBeInTheDocument()
    })

    it('says no ADP is stored, never a blank or a zero', async () => {
      await show()
      group('Draft value')
      expect(screen.getByText(/no ADP stored for this season/)).toBeInTheDocument()
    })

    it('names the ADP source and capture date, and prints the value', async () => {
      await show(board(rows(), { adp: { source: 'blend', capturedOn: '2026-09-28', reason: null } }))
      group('Draft value')
      expect(screen.getByText(/blend of Sleeper search rank and observed mock drafts, captured Sep 28, 2026/)).toBeInTheDocument()
      expect(screen.getAllByText('20.5').length).toBeGreaterThan(0)
    })

    it('flags early grades with the weeks counted', async () => {
      await show(board(rows(), { draftGrades: { available: true, reason: null, gradesEarly: true, weeksCounted: 2 } }))
      group('Draft value')
      expect(screen.getByText(/only 2 weeks are counted so far/)).toBeInTheDocument()
    })

    it('says there is no draft when the league has none', async () => {
      await show(board(rows().map((r) => ({ ...r, draft: null })), { draft: { state: 'NONE', draftId: null, draftSeason: 2025 } }))
      group('Draft value')
      expect(screen.getByText('This league has no draft.')).toBeInTheDocument()
    })
  })

  it('shows stat leaders with exact values and player links to the shown season', async () => {
    await show()
    fireEvent.click(within(screen.getByRole('group', { name: 'View' })).getByRole('button', { name: 'Stat leaders' }))
    const pts = screen.getByRole('group', { name: 'Points' })
    const names = within(pts).getAllByRole('link').map((a) => a.textContent)
    expect(names).toEqual(['Bob Beta', 'Ann Alpha', 'Cy Free'])
    expect(within(pts).getByText('30.0')).toBeInTheDocument()
    expect(within(pts).getAllByRole('link')[0]).toHaveAttribute('href', '/leagues/L25/players/2')
    expect(within(pts).queryByText('Dee Stale')).not.toBeInTheDocument()
    expect(screen.getAllByRole('group').filter((g) => g.className.includes('sl-leader')).length).toBe(9)
  })

  it('hides the qualification toggle in the stat leaders view and says leaders use qualified players only', async () => {
    await show()
    expect(screen.getByRole('checkbox', { name: 'Qualified players only' })).toBeInTheDocument()
    fireEvent.click(within(screen.getByRole('group', { name: 'View' })).getByRole('button', { name: 'Stat leaders' }))
    expect(screen.queryByRole('checkbox', { name: 'Qualified players only' })).not.toBeInTheDocument()
    expect(screen.getByText(/Leaders use qualified players only \(Ranked among players with at least 42 games/)).toBeInTheDocument()
  })

  it('applies qualified-only to rate and rank columns, not to a counting column, and says so', async () => {
    await show()
    expect(screen.getByText(/Applies to rate and rank columns/)).toBeInTheDocument()
    expect(order()).not.toContain('Dee Stale')   // default sort is FP/G, a rate column
    group('Basic')                                // PTS: a counting column, so everyone is listed
    expect(order()).toContain('Dee Stale')
    expect(screen.getByText(/every player is listed/)).toBeInTheDocument()
  })

  it('names the window in the rank headers when it is not the season, and points at season ranks', async () => {
    getStatLeaderboard.mockResolvedValue(board(rows(), { window: 'LAST_10' }))
    mount()
    await screen.findByRole('region', { name: 'Player stats' })
    fireEvent.click(within(screen.getByRole('group', { name: 'Window' })).getByRole('button', { name: 'Last 10' }))
    expect(await screen.findByRole('button', { name: /Rank \(last 10\)/ })).toBeInTheDocument()
    expect(screen.getByText(/the player page shows season ranks/)).toBeInTheDocument()
  })

  it('shows the rank move as whole places', async () => {
    await show(board(rows().map((r) => ({ ...r, rankMove: r.qualified ? 7 : null }))))
    const table = screen.getByRole('region', { name: 'Player stats' })
    expect(within(table).getAllByText('+7').length).toBeGreaterThan(0)
    expect(within(table).queryByText('+7.0')).not.toBeInTheDocument()
  })

  it('disables the availability filter, with a reason, when no row has known ownership', async () => {
    const notDrafted = owner({ state: 'NOT_DRAFTED', rosterId: null, ownerName: null, asOf: null })
    await show(board(rows().map((r) => ({ ...r, ownership: notDrafted }))))
    expect(screen.getByLabelText('Availability')).toBeDisabled()
    expect(screen.getByText(/Ownership isn’t known for this season/)).toBeInTheDocument()
  })

  it('on a fallback season, owners, the free-agent filter and the roster list come from currentOwnership, labelled now', async () => {
    const now = (id: string, o: Parameters<typeof owner>[0]) => ({
      ...rows().find((r) => r.sleeperPlayerId === id)!,
      currentOwnership: owner({ asOf: { kind: 'CURRENT', fetchedAt: '2026-10-12T10:00:00Z', week: null }, ...o }),
    })
    const fell = [
      now('1', { state: 'FREE_AGENT', rosterId: null, ownerName: null }),      // March: rostered; now: free agent
      now('2', { rosterId: 9, ownerName: 'New Owner' }),
      now('3', { rosterId: 9, ownerName: 'New Owner' }),
      now('4', { state: 'FREE_AGENT', rosterId: null, ownerName: null }),
    ]
    getStatLeaderboard.mockResolvedValue(board(fell, { season: 2025, requestedSeason: 2026 }))
    mount('/leagues/L26/stats')
    await screen.findByRole('region', { name: 'Player stats' })
    expect(screen.getByRole('columnheader', { name: 'Owner (now 2026–27)' })).toBeInTheDocument()
    expect(screen.getByText(/Owners are now 2026–27/)).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'New Owner’s roster' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Dunk Tank’s roster' })).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Availability'), { target: { value: 'free' } })
    expect(order()).toEqual(['Ann Alpha'])
  })

  it('on a fallback season before the new draft, nobody is rostered, so the ownership filters are off with a reason', async () => {
    const nobody = owner({ state: 'NOT_DRAFTED', rosterId: null, ownerName: null, asOf: null })
    getStatLeaderboard.mockResolvedValue(
      board(rows().map((r) => ({ ...r, currentOwnership: nobody })), { season: 2025, requestedSeason: 2026 }),
    )
    mount('/leagues/L26/stats')
    await screen.findByRole('region', { name: 'Player stats' })
    expect(screen.getByLabelText('Availability')).toBeDisabled()
    expect(screen.getByText(/Nobody is on a roster yet \(now 2026–27\)/)).toBeInTheDocument()
  })

  it('on a fallback season, says so and links players with the league id of the season shown', async () => {
    getStatLeaderboard.mockResolvedValue(board(rows(), { season: 2025, requestedSeason: 2026 }))
    mount('/leagues/L26/stats')
    expect(await screen.findByText(/2026–27 has no games yet; showing 2025–26/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ann Alpha' })).toHaveAttribute('href', '/leagues/L25/players/1')
  })

  it.each([
    ['NOT_BASKETBALL', /for basketball leagues/],
    ['NOT_CONFIGURED', /aren’t set up/],
    ['NO_GAMES', /No games have been played/],
  ] as const)('explains %s instead of an empty table', async (reason, text) => {
    getStatLeaderboard.mockResolvedValue(board([], { available: reason === 'NO_GAMES', reason, qualification: null }))
    mount()
    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Player stats' })).not.toBeInTheDocument()
  })
})
