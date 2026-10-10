import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AvailabilityRow } from '../api'
import { board, row as statRow, win } from '../testStatBoard'
import { mkPlayer } from '../testLiveRoom'
import AvailabilityPanel from './AvailabilityPanel'
import { clearDraftStatsCache } from '../draftStatsCache'
import { sgEligible, top108Rows } from '../testPositionShapes'

const cellRenders = vi.fn()
vi.mock('../statCells', async (orig) => {
  const actual = await orig<typeof import('../statCells')>()
  return {
    ...actual,
    Cell: (props: Parameters<typeof actual.Cell>[0]) => {
      cellRenders()
      return actual.Cell(props)
    },
  }
})

const getStatLeaderboard = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  getStatLeaderboard: (...args: unknown[]) => getStatLeaderboard(...args),
}))

// Braces: a returned function would be run by vitest as the test's teardown.
beforeEach(() => {
  getStatLeaderboard.mockReset()
  clearDraftStatsCache()
  localStorage.clear()
  sessionStorage.clear()
  cellRenders.mockClear()
})

const row = (name: string, adp: number, survival: number, position = 'RB'): AvailabilityRow => ({
  player: mkPlayer(position, name, adp),
  survivalByPick: { '20': survival, '33': survival },
})

beforeAll(() => {
  // jsdom has no ResizeObserver; the panel uses one to publish its height.
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver
})

const base = { myPicks: [20, 33], teams: 12, started: true, sport: 'nfl' as const }

describe('AvailabilityPanel', () => {
  it('groups rows under tier headings, with a face per player', () => {
    const availability = [row('Alpha', 10, 0.5), row('Bravo', 12, 0.5), row('Charlie', 40, 0.5)]
    const { container } = render(<AvailabilityPanel {...base} availability={availability} />)
    expect(screen.getByText('Tier 1')).toBeInTheDocument()
    expect(screen.getByText('Tier 2')).toBeInTheDocument()
    // One face per player: an image or an initials fallback, both named for the player.
    expect(container.querySelectorAll('.pface')).toHaveLength(3)
  })

  it('keeps the verdict thresholds for known survival values', () => {
    const availability = [row('Low', 10, 0.2), row('Mid', 11, 0.5), row('High', 12, 0.8)]
    render(<AvailabilityPanel {...base} availability={availability} />)
    const verdictOf = (name: string) => {
      const tr = screen.getByText(name).closest('tr') as HTMLElement
      return within(tr).getByText(/Act now|Coin flip|Safe/).textContent
    }
    expect(verdictOf('Low')).toBe('Act now')
    expect(verdictOf('Mid')).toBe('Coin flip')
    expect(verdictOf('High')).toBe('Safe')
  })

  it('shows the reason and no survival or verdict when there is no availability', () => {
    render(
      <AvailabilityPanel
        {...base}
        players={[mkPlayer('QB', 'Solo', 5)]}
        noAvailabilityReason="Availability needs a simulation, which mock drafts don't run."
      />,
    )
    const mark = screen.getByLabelText("Availability needs a simulation, which mock drafts don't run.")
    expect(mark).toHaveAttribute('title', "Availability needs a simulation, which mock drafts don't run.")
    expect(screen.getByText('Solo')).toBeInTheDocument()
    expect(screen.queryByText('Verdict')).toBeNull()
    expect(screen.queryByText(/Act now|Coin flip|Safe/)).toBeNull()
  })

  it('holds survival back when a reason is given even if availability is present', () => {
    render(
      <AvailabilityPanel
        {...base}
        availability={[row('Held', 10, 0.1)]}
        noAvailabilityReason="Availability appears once your seat is known."
      />,
    )
    expect(screen.getByLabelText('Availability appears once your seat is known.')).toBeInTheDocument()
    expect(screen.getByText('Held')).toBeInTheDocument()
    expect(screen.queryByText('Act now')).toBeNull()
  })

  it('calls out a position run using the given sport', () => {
    const wr = (n: number) => ({ position: n < 4 ? 'WR' : 'RB' })
    const recent = Array.from({ length: 6 }, (_, i) => wr(i))
    // 4 WR + 2 RB in the last 6 picks
    render(<AvailabilityPanel {...base} availability={[row('A', 10, 0.5)]} recentPicks={[...recent.slice(0, 4), { position: 'RB' }, { position: 'RB' }]} />)
    expect(screen.getByText(/WR run:/)).toBeInTheDocument()
    expect(screen.getByText(/4 of the last 6 picks/)).toBeInTheDocument()
  })

  it('does not read a football run into a basketball room', () => {
    const recent = Array.from({ length: 6 }, () => ({ position: 'WR' }))
    render(<AvailabilityPanel {...base} sport="nba" availability={[row('A', 10, 0.5, 'PG')]} recentPicks={recent} />)
    expect(screen.queryByText(/run:/)).toBeNull()
  })
})

describe('AvailabilityPanel stats view (spec 023 US1)', () => {
  const nba = { ...base, sport: 'nba' as const, sleeperLeagueId: 'L1' }
  const ann = row('Ann', 5, 0.9, 'PG')
  const bob = row('Bob', 20, 0.5, 'C')
  const rookie = row('Rookie', 40, 0.5, 'SF')
  const pool = [ann, bob, rookie]
  const lb = () => [
    statRow(ann.player.sleeperId, { name: 'Ann', fpPerGame: 30, stats: win({ pg: { pts: 25 } }) }),
    statRow(bob.player.sleeperId, { name: 'Bob', fpPerGame: 40, stats: win({ pg: { pts: 10 } }) }),
  ]
  const openStats = async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await screen.findByText(/stats, regular season/)
  }
  const bodyRows = (container: HTMLElement) => container.querySelectorAll('.ds-table tbody tr')
  const names = (container: HTMLElement) => [...container.querySelectorAll('.ds-table tbody th a')].map((a) => a.textContent)

  it('has no Tiers/Stats switch for football', () => {
    render(<AvailabilityPanel {...base} sleeperLeagueId="L1" availability={[row('A', 10, 0.5)]} />)
    expect(screen.queryByRole('button', { name: 'Stats' })).toBeNull()
  })

  it('disables Stats with its reason when the server did not send a league id', () => {
    render(<AvailabilityPanel {...nba} sleeperLeagueId={undefined} availability={pool} />)
    expect(screen.getByRole('button', { name: 'Stats' })).toBeDisabled()
    expect(screen.getByLabelText('Stats aren’t available on this server yet.')).toBeInTheDocument()
    expect(screen.getByText('Ann')).toBeInTheDocument() // tiers still work
  })

  it('renders one row per undrafted player, ordered by the default sort', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const { container } = render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    expect(getStatLeaderboard).toHaveBeenCalledWith('L1', 'SEASON')
    expect(bodyRows(container)).toHaveLength(3)
    // fp desc: Bob (40) before Ann (30); the no-games rookie last.
    expect(names(container)).toEqual(['Bob', 'Ann', 'Rookie'])
  })

  it('links a name to the player page in a new tab', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    const a = screen.getByRole('link', { name: 'Ann' })
    expect(a).toHaveAttribute('href', `/leagues/L1/players/${ann.player.sleeperId}`)
    expect(a).toHaveAttribute('target', '_blank')
    expect(a).toHaveAttribute('rel', 'noopener')
  })

  it('drops a picked player without refetching and keeps the chosen sort', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const { container, rerender } = render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    // Flip to ascending fp so the sort is observably non-default.
    fireEvent.click(screen.getByRole('button', { name: /^FP/ }))
    expect(names(container).slice(0, 2)).toEqual(['Ann', 'Bob'])
    rerender(<AvailabilityPanel {...nba} availability={pool} pickedPlayerIds={new Set([ann.player.id])} />)
    expect(names(container)).toEqual(['Bob', 'Rookie'])
    expect(screen.getByRole('button', { name: /^FP/ }).closest('th')).toHaveAttribute('aria-sort', 'ascending')
    expect(getStatLeaderboard).toHaveBeenCalledTimes(1)
    // Going to Tiers and back is not a new request either.
    fireEvent.click(screen.getByRole('button', { name: 'Tiers' }))
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    expect(getStatLeaderboard).toHaveBeenCalledTimes(1)
  })

  it('shows one spanning sentence for a player with no games, never zeros', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const { container } = render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    const cell = screen.getByText('No NBA games in 2025–26')
    expect(cell.tagName).toBe('TD')
    expect(Number(cell.getAttribute('colspan'))).toBeGreaterThan(1)
    expect(cell.closest('tr')!.querySelectorAll('td')).toHaveLength(1)
    expect(bodyRows(container)).toHaveLength(3)
  })

  it('says the stats are last season’s when the requested season has no games', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb(), { requestedSeason: 2026 }))
    render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    expect(screen.getByText(/not a projection/)).toBeInTheDocument()
  })

  it('warns when the league scoring changed since the shown season', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb(), { requestedSeason: 2026, scoringSeason: 2025, scoringMatchesRequested: false }))
    render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    expect(screen.getByText('This league’s scoring has changed since then.')).toBeInTheDocument()
  })

  it('says why the likely-there filter is off when the seat is unknown, and still lists everyone', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const { container } = render(
      <AvailabilityPanel {...nba} availability={pool} noAvailabilityReason="Availability appears once your seat is known." />,
    )
    await openStats()
    expect(screen.getByRole('checkbox', { name: /Likely there/ })).toBeDisabled()
    expect(bodyRows(container)).toHaveLength(3)
  })

  it('lists the whole statsPool, including players the projection does not track', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const untracked = mkPlayer('PF', 'Untracked', 90)
    const statsPool = [ann.player, bob.player, rookie.player, untracked]
    const { container } = render(<AvailabilityPanel {...nba} availability={[ann]} statsPool={statsPool} />)
    await openStats()
    expect(bodyRows(container)).toHaveLength(4)
    expect(names(container)).toContain('Untracked')
    const untrackedRow = screen.getByRole('link', { name: 'Untracked' }).closest('tr')!
    expect(untrackedRow.textContent).not.toMatch(/there at your next pick/)
    expect(screen.getByRole('link', { name: 'Ann' }).closest('tr')!.textContent).toMatch(/90% there at your next pick/)
    expect(getStatLeaderboard).toHaveBeenCalledTimes(1)
  })

  it('a taken row in the Stats view shows no survival number', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    render(<AvailabilityPanel {...nba} availability={[ann, bob]} statsPool={[ann.player, bob.player]} pickedPlayerIds={new Set([ann.player.id])} />)
    await openStats()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Hide drafted' }))
    expect(screen.getByRole('link', { name: 'Ann' }).closest('tr')!.textContent).not.toMatch(/there at your next pick/)
    expect(screen.getByRole('link', { name: 'Bob' }).closest('tr')!.textContent).toMatch(/there at your next pick/)
  })

  it('removes picked statsPool players and applies the position filter, with no survival filter or cap', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const many = Array.from({ length: 80 }, (_, i) => mkPlayer('PG', `Guard ${i}`, 100 + i))
    const statsPool = [ann.player, bob.player, ...many]
    const { container, rerender } = render(<AvailabilityPanel {...nba} availability={[ann]} statsPool={statsPool} />)
    await openStats()
    expect(bodyRows(container)).toHaveLength(82)
    rerender(<AvailabilityPanel {...nba} availability={[ann]} statsPool={statsPool} pickedPlayerIds={new Set([ann.player.id, many[0].id])} />)
    expect(bodyRows(container)).toHaveLength(80)
    fireEvent.click(screen.getByRole('button', { name: 'C' }))
    expect(names(container)).toEqual(['Bob'])
    expect(getStatLeaderboard).toHaveBeenCalledTimes(1)
  })

  it('likely-only keeps tracked players at or above the bar and drops untracked ones', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const untracked = mkPlayer('PF', 'Untracked', 90)
    const { container } = render(
      <AvailabilityPanel {...nba} availability={[ann, row('Low', 30, 0.1, 'SG')]} statsPool={[ann.player, untracked]} />,
    )
    await openStats()
    fireEvent.click(screen.getByRole('checkbox', { name: /Likely there/ }))
    expect(names(container)).toEqual(['Ann'])
  })

  it('shows the pool error instead of the table', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    render(<AvailabilityPanel {...nba} availability={pool} statsPoolError="Couldn’t load the player list." />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    expect(screen.getByText('Couldn’t load the player list.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Tiers' }))
    expect(screen.getByText('Ann')).toBeInTheDocument()
  })

  it('shows an error line when the fetch rejects, and Tiers still works', async () => {
    getStatLeaderboard.mockRejectedValue(new Error('boom'))
    render(<AvailabilityPanel {...nba} availability={pool} />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await waitFor(() => expect(screen.getByText('Couldn’t load stats.')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Tiers' }))
    expect(screen.getByText('Tier 1')).toBeInTheDocument()
  })

  it('uses the server’s sentence when the stats are unavailable, and Tiers still works', async () => {
    getStatLeaderboard.mockResolvedValue(board([], { available: false, reason: 'NOT_CONFIGURED' }))
    render(<AvailabilityPanel {...nba} availability={pool} />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await waitFor(() => expect(screen.getByText(/set up|configured/i)).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Tiers' }))
    expect(screen.getByText('Ann')).toBeInTheDocument()
  })
})

describe('AvailabilityPanel stat choice, window and mode (spec 023 US2)', () => {
  const nba = { ...base, sport: 'nba' as const, sleeperLeagueId: 'L1' }
  const ann = row('Ann', 5, 0.9, 'PG')
  const bob = row('Bob', 20, 0.5, 'C')
  const pool = [ann, bob]
  const lb = () => [
    statRow(ann.player.sleeperId, { name: 'Ann', fpPerGame: 30, stats: win({ pg: { pts: 25 } }) }),
    statRow(bob.player.sleeperId, { name: 'Bob', fpPerGame: 40, stats: win({ pg: { pts: 10 } }) }),
  ]
  const openStats = async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await screen.findByText(/stats, regular season/)
  }
  const heads = (c: HTMLElement) => [...c.querySelectorAll('.ds-table thead th')].map((t) => t.textContent?.replace(/[▲▼]/g, ''))
  const openPicker = () => fireEvent.click(screen.getByRole('button', { name: 'Choose stats' }))

  it('adds a USG column at once when it is toggled on', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const { container } = render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    expect(heads(container)).not.toContain('USG')
    openPicker()
    fireEvent.click(screen.getByRole('checkbox', { name: /USG/ }))
    expect(heads(container).at(-1)).toBe('USG')
  })

  it('reorders the table columns when a stat moves up', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const { container } = render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    expect(heads(container).slice(0, 4)).toEqual(['Player', 'GP', 'MIN', 'FP/G'])
    openPicker()
    fireEvent.click(screen.getByRole('button', { name: 'Move FP/G up' }))
    expect(heads(container).slice(0, 4)).toEqual(['Player', 'GP', 'FP/G', 'MIN'])
  })

  it('remembers the choice across an unmount and remount', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const first = render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    openPicker()
    fireEvent.click(screen.getByRole('checkbox', { name: /USG/ }))
    first.unmount()
    const { container } = render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    expect(heads(container).at(-1)).toBe('USG')
  })

  it('closes the picker on Escape', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    openPicker()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('keeps the picker open with its toggles when a pick lands behind it', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const { container, rerender } = render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    openPicker()
    fireEvent.click(screen.getByRole('checkbox', { name: /USG/ }))
    rerender(<AvailabilityPanel {...nba} availability={pool} pickedPlayerIds={new Set([ann.player.id])} />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /USG/ })).toBeChecked()
    expect(container.querySelectorAll('.ds-table tbody tr')).toHaveLength(1)
    expect(getStatLeaderboard).toHaveBeenCalledTimes(1)
  })

  it('falls back to sorting by FP/G when the sorted column is removed, then by name without FP/G', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const { container } = render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    fireEvent.click(screen.getByRole('button', { name: /^PTS/ }))
    expect(screen.getByRole('button', { name: /^PTS/ }).closest('th')).toHaveAttribute('aria-sort', 'descending')
    openPicker()
    fireEvent.click(screen.getByRole('checkbox', { name: /PTS\s*Points/ }))
    expect(screen.getByRole('button', { name: /^FP/ }).closest('th')).toHaveAttribute('aria-sort', 'descending')
    fireEvent.click(screen.getByRole('checkbox', { name: /FP\/G/ }))
    expect(screen.getByRole('button', { name: /^Player/ }).closest('th')).toHaveAttribute('aria-sort', 'ascending')
    expect(heads(container)).not.toContain('FP/G')
  })

  it('with every stat removed shows the names, "No stats chosen" and a reset', async () => {
    localStorage.setItem('bk.draftStats.v1', JSON.stringify({ v: 1, columns: [] }))
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const { container } = render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    expect(screen.getByText('No stats chosen')).toBeInTheDocument()
    expect(container.querySelectorAll('.ds-table tbody tr')).toHaveLength(2)
    fireEvent.click(screen.getByRole('button', { name: 'Reset to default' }))
    expect(heads(container)).toContain('FP/G')
  })

  it('refetches once on a window change, and not at all on a mode change', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    render(<AvailabilityPanel {...nba} availability={pool} />)
    await openStats()
    expect(getStatLeaderboard).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Totals' }))
    fireEvent.click(screen.getByRole('button', { name: 'Per 36' }))
    expect(getStatLeaderboard).toHaveBeenCalledTimes(1)
    getStatLeaderboard.mockResolvedValue(board(lb(), { window: 'LAST_10' }))
    fireEvent.click(screen.getByRole('button', { name: 'Last 10' }))
    await screen.findByText(/Last 10 window/)
    expect(getStatLeaderboard).toHaveBeenCalledTimes(2)
    expect(getStatLeaderboard).toHaveBeenLastCalledWith('L1', 'LAST_10')
  })

  // One stable instance, as the rooms' useMemo'd makeFitFor provides.
  const fitPg = (p: { position: string }) => (p.position === 'PG' ? 'Fills PG' : null)

  it('does not re-render the table when the parent re-renders with equal props (SC-006)', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const props = () => ({ ...nba, availability: pool, myPicks: [20, 33], pickedPlayerIds: new Set<number>(), fitFor: fitPg })
    const same = props()
    const { rerender } = render(<AvailabilityPanel {...same} />)
    await openStats()
    const before = cellRenders.mock.calls.length
    expect(before).toBeGreaterThan(0)
    // The live room's clock re-renders the panel with fresh-but-equal myPicks / Set instances.
    // pickedPlayerIds and fitFor are memoized upstream, so those two stay the same instance.
    rerender(<AvailabilityPanel {...same} />)
    rerender(<AvailabilityPanel {...same} myPicks={[20, 33]} />)
    expect(cellRenders.mock.calls.length).toBe(before)
    // A landed pick still updates the rows.
    rerender(<AvailabilityPanel {...same} pickedPlayerIds={new Set([ann.player.id])} />)
    expect(document.querySelectorAll('.ds-table tbody tr')).toHaveLength(1)
  })

  it('a pick that removes one player re-renders no cell of the surviving rows (code review S8)', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const rookie = row('Rookie', 40, 0.5, 'SF')
    const statsPool = [...pool.map((r) => r.player), rookie.player]
    const same = { ...nba, availability: pool, statsPool, fitFor: fitPg }
    const { rerender } = render(<AvailabilityPanel {...same} pickedPlayerIds={new Set()} />)
    await openStats()
    expect(document.querySelectorAll('.ds-table tbody tr')).toHaveLength(3)
    const before = cellRenders.mock.calls.length
    // Rookie has no stats row; Ann and Bob keep theirs. Picking Rookie must not touch their cells.
    rerender(<AvailabilityPanel {...same} pickedPlayerIds={new Set([rookie.player.id])} />)
    expect(document.querySelectorAll('.ds-table tbody tr')).toHaveLength(2)
    expect(cellRenders.mock.calls.length - before).toBe(0)
  })
})

describe('AvailabilityPanel stats in a mock room (spec 023 US3)', () => {
  const players = [mkPlayer('PG', 'Ann', 5), mkPlayer('C', 'Bob', 20)]
  const mock = {
    ...base,
    sport: 'nba' as const,
    players,
    noAvailabilityReason: "Availability needs a simulation, which mock drafts don't run.",
  }
  const lb = () => [statRow(players[0].sleeperId, { name: 'Ann', fpPerGame: 30, stats: win({ pg: { pts: 25 } }) })]

  it('offers Stats for a mock with a source league, and explains why the next-pick filter is off', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const { container } = render(<AvailabilityPanel {...mock} sleeperLeagueId="L1" />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await screen.findByText(/stats, regular season/)
    expect(container.querySelectorAll('.ds-table tbody tr')).toHaveLength(2)
    expect(screen.getByRole('checkbox', { name: /Likely there/ })).toBeDisabled()
    expect(screen.getByText("Availability needs a simulation, which mock drafts don't run.")).toBeInTheDocument()
  })

  it('disables Stats with the league reason for a mock started with no league', () => {
    render(<AvailabilityPanel {...mock} sleeperLeagueId={null} />)
    expect(screen.getByRole('button', { name: 'Stats' })).toBeDisabled()
    expect(screen.getByLabelText('Stats need a league: start the mock from a league to see them.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Stats aren’t available on this server yet.')).toBeNull()
  })

  it('reopens on Stats after the sheet unmounts between turns, with the same stored columns', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const first = render(<AvailabilityPanel {...mock} sleeperLeagueId="L1" />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await screen.findByText(/stats, regular season/)
    const heads = () => [...document.querySelectorAll('.ds-table thead th')].map((th) => th.textContent)
    const before = heads()
    first.unmount()
    // The mock room mounts the sheet only on your turn: a fresh instance is the next turn.
    render(<AvailabilityPanel {...mock} sleeperLeagueId="L1" />)
    await screen.findByText(/stats, regular season/)
    expect(screen.getByRole('button', { name: 'Stats' })).toHaveAttribute('aria-pressed', 'true')
    expect(heads()).toEqual(before)
  })
})


describe('AvailabilityPanel stats fetch cache and persisted state (code review S1, S5)', () => {
  const nba = { ...base, sport: 'nba' as const, sleeperLeagueId: 'L1' }
  const ann = row('Ann', 5, 0.9, 'PG')
  const bob = row('Bob', 20, 0.5, 'C')
  const pool = [ann, bob]
  const lb = (fp: number) => [
    statRow(ann.player.sleeperId, { name: 'Ann', fpPerGame: fp, stats: win({ pg: { pts: 25 } }) }),
    statRow(bob.player.sleeperId, { name: 'Bob', fpPerGame: 40, stats: win({ pg: { pts: 10 } }) }),
  ]
  const deferred = () => {
    let resolve!: (v: unknown) => void
    let reject!: (e: unknown) => void
    const promise = new Promise((res, rej) => {
      resolve = res
      reject = rej
    })
    return { promise, resolve, reject }
  }

  it('shows the second window when the first resolves after it', async () => {
    const season = deferred()
    const last10 = deferred()
    getStatLeaderboard.mockImplementation((_l: string, w: string) => (w === 'SEASON' ? season.promise : last10.promise))
    render(<AvailabilityPanel {...nba} availability={pool} />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    fireEvent.click(screen.getByRole('button', { name: 'Last 10' }))
    last10.resolve(board(lb(30), { window: 'LAST_10' }))
    await screen.findByText(/Last 10 window/)
    season.resolve(board(lb(30)))
    await new Promise((r) => setTimeout(r, 0))
    expect(screen.getByText(/Last 10 window/)).toBeInTheDocument()
    expect(screen.queryByText('Loading stats…')).toBeNull()
    expect(document.querySelectorAll('.ds-table tbody tr')).toHaveLength(2)
  })

  it('reuses the cached board across a remount, with no second request', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb(30)))
    const first = render(<AvailabilityPanel {...nba} availability={pool} />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await screen.findByText(/stats, regular season/)
    first.unmount()
    render(<AvailabilityPanel {...nba} availability={pool} />)
    await screen.findByText(/stats, regular season/)
    expect(getStatLeaderboard).toHaveBeenCalledTimes(1)
  })

  it('retries on a later open after a failed fetch', async () => {
    getStatLeaderboard.mockRejectedValueOnce(new Error('boom'))
    const first = render(<AvailabilityPanel {...nba} availability={pool} />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await screen.findByText('Couldn’t load stats.')
    first.unmount()
    getStatLeaderboard.mockResolvedValue(board(lb(30)))
    render(<AvailabilityPanel {...nba} availability={pool} />)
    await screen.findByText(/stats, regular season/)
    expect(getStatLeaderboard).toHaveBeenCalledTimes(2)
  })

  it('keeps sort, window, mode, position filter and likely-only across an unmount and remount', async () => {
    getStatLeaderboard.mockImplementation((_l: string, w: string) => Promise.resolve(board(lb(30), { window: w as 'LAST_10' })))
    const first = render(<AvailabilityPanel {...nba} availability={pool} />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await screen.findByText(/stats, regular season/)
    fireEvent.click(screen.getByRole('button', { name: 'Last 10' }))
    await screen.findByText(/Last 10 window/)
    fireEvent.click(screen.getByRole('button', { name: 'Totals' }))
    fireEvent.click(screen.getByRole('button', { name: 'C' }))
    fireEvent.click(screen.getByRole('checkbox', { name: /Likely there/ }))
    fireEvent.click(screen.getByRole('button', { name: /^Player/ }))
    const sortedBy = () => document.querySelector('.ds-table thead th[aria-sort="ascending"], .ds-table thead th[aria-sort="descending"]')?.textContent
    const sortBefore = sortedBy()
    first.unmount()
    render(<AvailabilityPanel {...nba} availability={pool} />)
    await screen.findByText(/Last 10 window/)
    expect(screen.getByRole('button', { name: 'Last 10' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Totals' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'C' }).className).toMatch(/\bon\b/)
    expect(screen.getByRole('checkbox', { name: /Likely there/ })).toBeChecked()
    expect(sortedBy()).toBe(sortBefore)
  })

  it('falls back to defaults when the stored state is garbage', async () => {
    sessionStorage.setItem('bk.availStatsState.v1', '{"window":"NOPE","sort":7}')
    getStatLeaderboard.mockResolvedValue(board(lb(30)))
    render(<AvailabilityPanel {...nba} availability={pool} />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await screen.findByText(/stats, regular season/)
    expect(screen.getByRole('button', { name: 'Season' })).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('AvailabilityPanel empty states and loading (code review S2, S3, S4)', () => {
  const nba = { ...base, sport: 'nba' as const, sleeperLeagueId: 'L1' }
  const ann = row('Ann', 5, 0.9, 'PG')
  const lb = () => [statRow(ann.player.sleeperId, { name: 'Ann', fpPerGame: 30, stats: win() })]

  it('does not print the tier list empty-state under the Stats table', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    render(<AvailabilityPanel {...nba} availability={[]} statsPool={[ann.player]} />)
    expect(screen.getByText('No players survive to these picks in any run.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await screen.findByText(/stats, regular season/)
    expect(screen.queryByText('No players survive to these picks in any run.')).toBeNull()
  })

  it('does not claim no picks are left before the projection exists', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    render(<AvailabilityPanel {...nba} started={false} myPicks={[]} availability={[]} statsPool={[ann.player]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await screen.findByText(/stats, regular season/)
    expect(screen.queryByText('You have no picks left.')).toBeNull()
    expect(screen.getByText('Survival appears once the projection is ready.')).toBeInTheDocument()
  })

  it('still says no picks are left when they genuinely are', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    render(<AvailabilityPanel {...nba} myPicks={[]} availability={[ann]} statsPool={[ann.player]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await screen.findByText(/stats, regular season/)
    expect(screen.getByText('You have no picks left.')).toBeInTheDocument()
  })

  it('says the player list is loading instead of using the simulation subset', async () => {
    getStatLeaderboard.mockResolvedValue(board(lb()))
    const { rerender } = render(<AvailabilityPanel {...nba} availability={[ann]} statsPoolLoading />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await screen.findByText('Loading the player list…')
    expect(document.querySelector('.ds-table')).toBeNull()
    rerender(<AvailabilityPanel {...nba} availability={[ann]} statsPool={[ann.player]} />)
    await screen.findByText(/stats, regular season/)
    expect(document.querySelectorAll('.ds-table tbody tr')).toHaveLength(1)
  })
})

describe('AvailabilityPanel search, hide-drafted and targets (spec 024 US3)', () => {
  const dropped = mkPlayer('RB', 'Dropped Dan', 5)
  const two = [row('Nikola Jokić', 10, 0.5), row('Plain Pete', 12, 0.5)]

  it('narrows by name ignoring case and accents, only from 2 characters', () => {
    render(<AvailabilityPanel {...base} availability={two} />)
    const box = screen.getByRole('searchbox', { name: 'Search players' })
    fireEvent.change(box, { target: { value: 'j' } })
    expect(screen.getByText('Plain Pete')).toBeInTheDocument()
    fireEvent.change(box, { target: { value: 'JOKIC' } })
    expect(screen.getByText('Nikola Jokić')).toBeInTheDocument()
    expect(screen.queryByText('Plain Pete')).toBeNull()
  })

  it('hides drafted players by default and shows them marked taken when the toggle is off', () => {
    const picked = new Set([two[1].player.id])
    render(<AvailabilityPanel {...base} availability={two} pickedPlayerIds={picked} />)
    expect(screen.queryByText('Plain Pete')).toBeNull()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Hide drafted' }))
    const rowEl = screen.getByText('Plain Pete').closest('tr')!
    expect(rowEl.querySelector('.taken-tag')!.textContent).toBe('taken')
    expect(screen.getByText('Nikola Jokić').closest('tr')!.querySelector('.taken-tag')).toBeNull()
  })

  it('a taken row shows only the taken tag: no survival tiles and no verdict', () => {
    // 'Plain Pete' still has a 50% curve in the (stale) availability; he is taken, so none of it shows.
    const picked = new Set([two[1].player.id])
    const { container } = render(
      <AvailabilityPanel {...base} availability={two} pickedPlayerIds={picked} draftedPlayers={[dropped]} />,
    )
    fireEvent.click(screen.getByRole('checkbox', { name: 'Hide drafted' }))
    for (const name of ['Plain Pete', 'Dropped Dan']) {
      const tr = screen.getByText(name).closest('tr')!
      expect(tr.querySelector('.taken-tag')).not.toBeNull()
      expect(tr.querySelector('.survival-block')).toBeNull()
      expect(tr.textContent).not.toMatch(/Act now|Coin flip|Safe/)
    }
    // The live row keeps both.
    const live = screen.getByText('Nikola Jokić').closest('tr')!
    expect(live.querySelector('.survival-block')).not.toBeNull()
    expect(container.querySelectorAll('td.verdict:not(:empty)')).toHaveLength(1)
  })

  it('adds drafted players the room supplies separately when the toggle is off', () => {
    render(<AvailabilityPanel {...base} players={[mkPlayer('RB', 'Open Ollie', 30)]} draftedPlayers={[dropped]} pickedPlayerIds={new Set([dropped.id])} />)
    expect(screen.queryByText('Dropped Dan')).toBeNull()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Hide drafted' }))
    expect(screen.getByText('Dropped Dan').closest('tr')!.querySelector('.taken-tag')).not.toBeNull()
  })

  it('stars and unstars a row through the handlers, and draws no star without them', () => {
    const onAdd = vi.fn()
    const onRemove = vi.fn()
    const { rerender } = render(<AvailabilityPanel {...base} availability={two} />)
    expect(screen.queryByRole('button', { name: /to targets/ })).toBeNull()
    rerender(<AvailabilityPanel {...base} availability={two} targetIds={new Set([two[0].player.sleeperId])} onAddTarget={onAdd} onRemoveTarget={onRemove} />)
    fireEvent.click(screen.getByRole('button', { name: 'Add Plain Pete to targets' }))
    expect(onAdd).toHaveBeenCalledWith(two[1].player)
    fireEvent.click(screen.getByRole('button', { name: 'Remove Nikola Jokić from targets' }))
    expect(onRemove).toHaveBeenCalledWith(two[0].player.sleeperId)
  })

  it('a taken row has no star', () => {
    const picked = new Set([two[1].player.id])
    render(<AvailabilityPanel {...base} availability={two} pickedPlayerIds={picked} targetIds={new Set()} onAddTarget={vi.fn()} onRemoveTarget={vi.fn()} />)
    fireEvent.click(screen.getByRole('checkbox', { name: 'Hide drafted' }))
    expect(screen.queryByRole('button', { name: 'Add Plain Pete to targets' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Add Nikola Jokić to targets' })).toBeInTheDocument()
  })
})

describe('AvailabilityPanel multi-position eligibility (spec 025 US2)', () => {
  const nba = { ...base, sport: 'nba' as const, sleeperLeagueId: 'L1', myPicks: [20] }
  const shapes = top108Rows([20])
  const chipBtn = (label: string) =>
    screen.getAllByRole('button').find((b) => b.textContent === label && b.classList.contains('chip')) as HTMLElement

  it('tiers: ALL lists each player once and the SG filter has 100% recall', () => {
    const { container } = render(<AvailabilityPanel {...nba} availability={shapes} />)
    const count = () => container.querySelectorAll('.avail .pc').length
    // The tiers list caps at the top 60 live players; the cap applies after the filter.
    expect(count()).toBe(60)
    const listed = () => [...container.querySelectorAll('.avail .pc-name')].map((e) => e.textContent)
    expect(new Set(listed()).size).toBe(60)
    fireEvent.click(chipBtn('SG'))
    expect(count()).toBe(sgEligible(shapes.map((r) => r.player)).length) // 42, under the cap
  })

  it('stats view: the SG filter has 100% recall too', async () => {
    getStatLeaderboard.mockResolvedValue(board([]))
    const { container } = render(<AvailabilityPanel {...nba} availability={shapes} />)
    fireEvent.click(screen.getByRole('button', { name: 'Stats' }))
    await screen.findByText(/stats, regular season/)
    expect(container.querySelectorAll('.ds-table tbody tr')).toHaveLength(108)
    fireEvent.click(chipBtn('SG'))
    expect(container.querySelectorAll('.ds-table tbody tr')).toHaveLength(42)
  })

  it('a multi-position row shows the label with no rank number', () => {
    const { container } = render(<AvailabilityPanel {...nba} availability={shapes} />)
    const pill = container.querySelector('.avail .pos.multi') as HTMLElement
    expect(pill.textContent).toMatch(/^[A-Z]{1,2}(\/[A-Z]{1,2})+$/)
    const single = [...container.querySelectorAll('.avail .pos')].find((e) => e.textContent === 'C')
    expect(single).toBeDefined()
  })
})
