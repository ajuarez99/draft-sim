import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ScheduleGrid from './ScheduleGrid'
import { LeagueDataVersionProvider, useBumpLeagueDataVersion } from '../leagueDataVersion'
import type { LeagueSchedule } from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
}))

const getLeagueSchedule = vi.fn()
vi.mock('../api', () => ({
  getLeagueSchedule: (...args: unknown[]) => getLeagueSchedule(...args),
}))

/*
 * Four stored weeks that deliberately do NOT start at 1 (3..6), three-plus
 * teams sent out of code order, and a current week (4) that is neither the
 * first stored week nor index 4. An index-offset lookup reads the wrong column
 * on this data; a by-week-number lookup doesn't (N5).
 *
 *            wk3 wk4 wk5 wk6
 *   ATL       1   2   2   2
 *   BOS       1   4   1   1
 *   CHI       1   3   2   3
 *   DEN       1   3   4   0
 */
function data(over: Partial<LeagueSchedule> = {}): LeagueSchedule {
  return {
    sport: 'nba',
    season: 2026,
    available: true,
    reason: null,
    fetchedAt: new Date(Date.now() - 2 * 3600 * 1000).toISOString(),
    currentWeek: 4,
    lastLeagueWeek: 6,
    seasonOver: false,
    weeks: [
      { week: 3, firstDate: '2026-11-09', lastDate: '2026-11-15' },
      { week: 4, firstDate: '2026-11-16', lastDate: '2026-11-22' },
      { week: 5, firstDate: '2026-11-23', lastDate: '2026-11-29' },
      { week: 6, firstDate: '2026-11-30', lastDate: '2026-12-06' },
    ],
    playoff: { startWeek: 5, endWeek: 6, reason: null },
    teams: [
      { team: 'DEN', games: [1, 3, 4, 0], seasonTotal: 8 },
      { team: 'BOS', games: [1, 4, 1, 1], seasonTotal: 7 },
      { team: 'ATL', games: [1, 2, 2, 2], seasonTotal: 7 },
      { team: 'CHI', games: [1, 3, 2, 3], seasonTotal: 9 },
    ],
    excluded: { postponed: 0, canceled: 0 },
    ...over,
  }
}

const teamOrder = () =>
  screen
    .getAllByRole('row')
    .slice(1)
    .map((r) => within(r).getAllByRole('rowheader')[0].textContent)

const rowFor = (team: string) => screen.getByRole('rowheader', { name: team }).closest('tr') as HTMLElement

describe('Schedule grid', () => {
  beforeEach(() => getLeagueSchedule.mockReset())

  it('prints each count in its cell and tints the cell by that same count', async () => {
    getLeagueSchedule.mockResolvedValue(data())
    render(<ScheduleGrid />)
    await screen.findByRole('rowheader', { name: 'BOS' })

    // Week 4 is BOS's 4-game week; its cell prints 4 and carries the 4-game tint.
    const cells = within(rowFor('BOS')).getAllByRole('cell')
    // cells: [next-N total, wk4, wk5, wk6]
    expect(cells.map((c) => c.textContent)).toEqual(['4', '4', '1', '1'])
    expect(cells[1]).toHaveClass('sg-c4')
    expect(cells[2]).toHaveClass('sg-c1')
    expect(within(rowFor('DEN')).getAllByRole('cell')[3]).toHaveClass('sg-c0')
  })

  // Bug class 1: a sort that is well-formed and pointed the wrong way passes every structural test.
  it('ranks most games first and breaks ties by team code', async () => {
    getLeagueSchedule.mockResolvedValue(data())
    const user = userEvent.setup()
    render(<ScheduleGrid />)
    await screen.findByRole('rowheader', { name: 'BOS' })

    // Next 1 week = week 4: BOS 4, then CHI and DEN tied at 3 (CHI first), then ATL 2.
    expect(teamOrder()).toEqual(['BOS', 'CHI', 'DEN', 'ATL'])

    // Next 2 weeks = weeks 4+5: DEN 7, then BOS and CHI tied at 5, then ATL 4.
    await user.click(screen.getByRole('button', { name: 'Next 2 weeks' }))
    expect(teamOrder()).toEqual(['DEN', 'BOS', 'CHI', 'ATL'])
    expect(within(rowFor('DEN')).getAllByRole('cell')[0]).toHaveTextContent('7')
  })

  it('never sums past the last league week', async () => {
    getLeagueSchedule.mockResolvedValue(data({ lastLeagueWeek: 5 }))
    const user = userEvent.setup()
    render(<ScheduleGrid />)
    await screen.findByRole('rowheader', { name: 'BOS' })
    await user.click(screen.getByRole('button', { name: 'Next 2 weeks' }))
    // Weeks 4..5 only: week 6 isn't a column and isn't in the sum.
    expect(screen.queryByText('Week 6')).toBeNull()
    expect(within(rowFor('ATL')).getAllByRole('cell')[0]).toHaveTextContent('4')
    expect(within(rowFor('CHI')).getAllByRole('cell')[0]).toHaveTextContent('5')
  })

  it('disables Next-N buttons beyond the weeks left and never labels more weeks than it sums', async () => {
    getLeagueSchedule.mockResolvedValue(data({ currentWeek: 4, lastLeagueWeek: 5 }))
    const user = userEvent.setup()
    render(<ScheduleGrid />)
    await screen.findByRole('rowheader', { name: 'BOS' })
    expect(screen.getByRole('button', { name: 'Next 3 weeks' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next 4 weeks' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next 2 weeks' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Next 2 weeks' }))
    const heads = screen.getAllByRole('columnheader').map((h) => h.textContent ?? '')
    expect(heads).toContain('Next 2 weeks')
    expect(heads.some((h) => /Next [34] weeks/.test(h))).toBe(false)
  })

  it('shows columns to the last stored week when the current week is past an estimated last league week', async () => {
    // Mid-playoffs, playoff end unknown: lastLeagueWeek (start - 1) is behind currentWeek, not over.
    getLeagueSchedule.mockResolvedValue(
      data({ currentWeek: 5, lastLeagueWeek: 4, seasonOver: false, playoff: { startWeek: 5, endWeek: null, reason: 'x' } }),
    )
    render(<ScheduleGrid />)
    await screen.findByRole('rowheader', { name: 'BOS' })
    const heads = screen.getAllByRole('columnheader').map((h) => h.textContent ?? '')
    expect(heads.filter((h) => /^Week \d/.test(h)).map((h) => h.match(/^Week (\d+)/)![1])).toEqual(['5', '6'])
    expect(screen.queryByText('No weeks left to show.')).toBeNull()
    // Next 1 week = week 5: CHI 2 (and week 6 is a real column, CHI 3).
    expect(within(rowFor('CHI')).getAllByRole('cell').map((c) => c.textContent)).toEqual(['2', '2', '3'])
  })

  it('runs its columns from the current week to the last league week, by week number', async () => {
    getLeagueSchedule.mockResolvedValue(data())
    render(<ScheduleGrid />)
    await screen.findByRole('rowheader', { name: 'BOS' })
    const heads = screen.getAllByRole('columnheader').map((h) => h.textContent ?? '')
    expect(heads.some((h) => h.startsWith('Week 3'))).toBe(false)
    expect(heads.filter((h) => /^Week \d/.test(h)).map((h) => h.match(/^Week (\d+)/)![1])).toEqual(['4', '5', '6'])
    // Week 4 reads the second stored entry, not games[4] (which doesn't exist).
    expect(within(rowFor('CHI')).getAllByRole('cell')[1]).toHaveTextContent('3')
  })

  it("labels the current week as including games already played", async () => {
    getLeagueSchedule.mockResolvedValue(data())
    render(<ScheduleGrid />)
    await screen.findByRole('rowheader', { name: 'BOS' })
    expect(screen.getByText('this week (incl. played)')).toBeInTheDocument()
  })

  it('marks weeks inside the playoff window in the header', async () => {
    getLeagueSchedule.mockResolvedValue(data())
    render(<ScheduleGrid />)
    await screen.findByRole('rowheader', { name: 'BOS' })
    expect(screen.getAllByText('playoffs')).toHaveLength(2)
  })

  it('says the season is over and shows no upcoming columns', async () => {
    getLeagueSchedule.mockResolvedValue(data({ seasonOver: true, currentWeek: 21 }))
    render(<ScheduleGrid />)
    expect(await screen.findByText("This league's season is over.")).toBeInTheDocument()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('shows only the reason when the schedule is unavailable', async () => {
    getLeagueSchedule.mockResolvedValue(
      data({ available: false, reason: "The 2026 NBA schedule hasn't been loaded yet.", weeks: [], teams: [] }),
    )
    render(<ScheduleGrid />)
    expect(await screen.findByText(/hasn't been loaded yet/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Next 1 week' })).toBeNull()
  })

  it('states the freshness without explaining the NBA Cup', async () => {
    getLeagueSchedule.mockResolvedValue(data())
    render(<ScheduleGrid />)
    expect(await screen.findByText(/Schedule from Sleeper, fetched 2 hours ago/)).toBeInTheDocument()
    expect(screen.queryByText(/Cup/)).toBeNull()
  })

  it('says how many games were left out, only when some were', async () => {
    getLeagueSchedule.mockResolvedValue(data({ excluded: { postponed: 3, canceled: 1 } }))
    const { unmount } = render(<ScheduleGrid />)
    expect(await screen.findByText(/3 postponed and 1 canceled games aren't counted/)).toBeInTheDocument()
    unmount()
    getLeagueSchedule.mockResolvedValue(data())
    render(<ScheduleGrid />)
    await screen.findByRole('rowheader', { name: 'BOS' })
    expect(screen.queryByText(/aren't counted/)).toBeNull()
  })

  // F8: the visit's own refresh must fill an empty grid without a reload.
  it('refetches when the league data version is bumped', async () => {
    getLeagueSchedule.mockResolvedValue(data())
    let bump: (id: string) => void = () => {}
    function Grab() {
      bump = useBumpLeagueDataVersion()
      return null
    }
    render(
      <LeagueDataVersionProvider>
        <Grab />
        <ScheduleGrid />
      </LeagueDataVersionProvider>,
    )
    await waitFor(() => expect(getLeagueSchedule).toHaveBeenCalledTimes(1))
    act(() => bump('L1'))
    await waitFor(() => expect(getLeagueSchedule).toHaveBeenCalledTimes(2))
  })

  describe('playoff weeks view (US2)', () => {
    it('shows only the playoff columns and a per-team total, sorted by it', async () => {
      getLeagueSchedule.mockResolvedValue(data())
      const user = userEvent.setup()
      render(<ScheduleGrid />)
      await screen.findByRole('rowheader', { name: 'BOS' })
      await user.click(screen.getByRole('button', { name: 'Playoff weeks' }))

      const heads = screen.getAllByRole('columnheader').map((h) => h.textContent ?? '')
      expect(heads.filter((h) => /^Week \d/.test(h)).map((h) => h.match(/^Week (\d+)/)![1])).toEqual(['5', '6'])
      expect(screen.getByRole('columnheader', { name: 'Playoff total' })).toBeInTheDocument()
      // Weeks 5+6: CHI 5, then ATL and DEN tied at 4 (ATL first), then BOS 2.
      expect(teamOrder()).toEqual(['CHI', 'ATL', 'DEN', 'BOS'])
      expect(within(rowFor('CHI')).getAllByRole('cell')[0]).toHaveTextContent('5')
    })

    it('shows the reason and no table when the window could not be worked out', async () => {
      getLeagueSchedule.mockResolvedValue(
        data({ playoff: { startWeek: 20, endWeek: null, reason: "This league's playoff rounds aren't one week each." } }),
      )
      const user = userEvent.setup()
      render(<ScheduleGrid />)
      await screen.findByRole('rowheader', { name: 'BOS' })
      await user.click(screen.getByRole('button', { name: 'Playoff weeks' }))
      expect(screen.getByText(/playoff rounds aren't one week each/)).toBeInTheDocument()
      expect(screen.queryByRole('table')).toBeNull()
    })

    // N6: a week Sleeper hasn't published is missing, which is not the same as zero games.
    it("renders a playoff week beyond the stored schedule as not in Sleeper's schedule, not 0", async () => {
      getLeagueSchedule.mockResolvedValue(data({ playoff: { startWeek: 5, endWeek: 7, reason: null } }))
      const user = userEvent.setup()
      render(<ScheduleGrid />)
      await screen.findByRole('rowheader', { name: 'BOS' })
      await user.click(screen.getByRole('button', { name: 'Playoff weeks' }))
      const cells = within(rowFor('BOS')).getAllByRole('cell')
      // [total, wk5, wk6, wk7]
      expect(cells[3]).toHaveTextContent("not in Sleeper's schedule")
      expect(cells[3]).not.toHaveTextContent(/^0$/)
      // The total counts the two weeks that exist.
      expect(cells[0]).toHaveTextContent('2')
    })
  })
})
