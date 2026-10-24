import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdpCompare from './AdpCompare'
import { ApiError } from '../apiError'
import type { AdpCompareResponse, AdpPlayer, AdpSource } from '../api'

const getAdpCompare = vi.fn()
vi.mock('../api', () => ({
  getAdpCompare: (...args: unknown[]) => getAdpCompare(...args),
}))

function player(over: Partial<AdpPlayer>): AdpPlayer {
  return {
    playerId: 1, sleeperId: 's1', name: 'Player', team: 'BOS', positions: ['PG'], yearsExp: 3,
    dynasty: null, redraft: null, yahooAdp: null, yahooCost: null, yahooPctDrafted: null, sale: null,
    ...over,
  }
}

const sources: AdpSource[] = [
  { key: 'sleeper_dynasty', label: 'Sleeper dynasty ADP', capturedOn: '2026-10-23', ranked: 835, scoring: 'dynasty', unmatched: null, deepestAdp: null, minPercentDrafted: null },
  { key: 'sleeper_redraft', label: 'Sleeper redraft ADP', capturedOn: '2026-10-23', ranked: 790, scoring: 'redraft', unmatched: null, deepestAdp: null, minPercentDrafted: null },
  { key: 'yahoo', label: 'Yahoo ADP', capturedOn: '2026-10-23', ranked: 186, scoring: 'redraft', unmatched: 2, deepestAdp: 123.8, minPercentDrafted: 0.5 },
]

// The shapes the spec names: a veteran Yahoo takes early, a rookie dynasty takes early, a thin-sample Yahoo value,
// a Yahoo-only row with no dynasty ADP, and a Yahoo player no local player matched.
const durant = player({ playerId: 1, sleeperId: 'kd', name: 'Kevin Durant', team: 'HOU', positions: ['SF', 'PF'], yearsExp: 18, dynasty: 80, redraft: 30, yahooAdp: 20, yahooCost: 30.5, yahooPctDrafted: 1 })
const dybantsa = player({ playerId: 2, sleeperId: 'ad', name: 'AJ Dybantsa', team: 'WAS', positions: ['SF'], yearsExp: 0, dynasty: 5, redraft: 40, yahooAdp: 90, yahooCost: 2, yahooPctDrafted: 0.8 })
const thin = player({ playerId: 3, sleeperId: 'th', name: 'Thin Sample', team: 'MIA', positions: ['C'], yearsExp: 5, dynasty: 200, redraft: 150, yahooAdp: 100, yahooCost: 1, yahooPctDrafted: 0.3 })
const noDynasty = player({ playerId: 4, sleeperId: 'nd', name: 'No Dynasty', team: 'LAL', positions: ['PG'], yearsExp: 2, dynasty: null, redraft: 60, yahooAdp: 70, yahooCost: 3, yahooPctDrafted: 0.9 })
const unmatched = player({ playerId: null, sleeperId: null, name: 'Mystery Man', team: 'DEN', positions: ['PG', 'SG'], yearsExp: null, yahooAdp: 50, yahooCost: 5, yahooPctDrafted: 0.7 })

function data(over: Partial<AdpCompareResponse> = {}): AdpCompareResponse {
  return {
    sport: 'nba',
    sources,
    players: [durant, dybantsa, thin, noDynasty, unmatched],
    costCurve: [80.9, 77.0],
    draft: null,
    ...over,
  }
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/leagues/L1/adp']}>
      <Routes>
        <Route path="/leagues/:sleeperLeagueId/adp" element={<AdpCompare />} />
      </Routes>
    </MemoryRouter>,
  )
}

const rowFor = (name: string) => screen.getByRole('rowheader', { name: new RegExp(name) }).closest('tr') as HTMLElement
const gapPill = (name: string) => within(rowFor(name)).getByText(/picks earlier|picks later|not compared|no dynasty ADP|not ranked by Yahoo|level/)
const order = () =>
  screen.getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('rowheader')[0].textContent ?? '')

describe('ADP & prices', () => {
  beforeEach(() => { getAdpCompare.mockReset() })

  it('asks for the league in the URL', async () => {
    getAdpCompare.mockResolvedValue(data())
    renderPage()
    await screen.findByRole('rowheader', { name: /Kevin Durant/ })
    expect(getAdpCompare).toHaveBeenCalledWith('L1')
  })

  it('shows where each source came from: fetched-on date, ranked count, and the unmatched count', async () => {
    getAdpCompare.mockResolvedValue(data())
    renderPage()
    await screen.findByRole('rowheader', { name: /Kevin Durant/ })

    const strip = screen.getByRole('list', { name: 'Where each ADP comes from' })
    const items = within(strip).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(within(items[0]).getByText('Sleeper dynasty ADP')).toBeInTheDocument()
    expect(within(items[0]).getByText('fetched on Oct 23, 2026')).toBeInTheDocument()
    expect(within(items[0]).getByText('835 players ranked')).toBeInTheDocument()
    expect(within(items[2]).getByText('186 players ranked')).toBeInTheDocument()
    expect(within(items[2]).getByText('2 not matched to a player')).toBeInTheDocument()
  })

  it('says "unavailable" for a source with no capture and still lists it', async () => {
    const off = sources.map((s) => (s.key === 'yahoo' ? { ...s, capturedOn: null, ranked: 0, unmatched: 0 } : s))
    getAdpCompare.mockResolvedValue(data({ sources: off }))
    renderPage()
    await screen.findByRole('rowheader', { name: /Kevin Durant/ })

    const yahooItem = within(screen.getByRole('list', { name: 'Where each ADP comes from' })).getAllByRole('listitem')[2]
    expect(within(yahooItem).getByText('Yahoo ADP')).toBeInTheDocument()
    expect(within(yahooItem).getByText('unavailable')).toBeInTheDocument()
    expect(within(yahooItem).queryByText(/fetched on/)).toBeNull()
  })

  it('tints a Durant-like row green and a Dybantsa-like row red, and writes the gap in words', async () => {
    getAdpCompare.mockResolvedValue(data())
    renderPage()
    await screen.findByRole('rowheader', { name: /Kevin Durant/ })

    expect(gapPill('Kevin Durant')).toHaveTextContent('Yahoo 60.0 picks earlier')
    expect(gapPill('Kevin Durant')).toHaveClass('yahoo')
    expect(gapPill('AJ Dybantsa')).toHaveTextContent('Yahoo 85.0 picks later')
    expect(gapPill('AJ Dybantsa')).toHaveClass('sleeper')
  })

  it('greys a Yahoo value drafted in under 50% of leagues, says why, never tints it', async () => {
    getAdpCompare.mockResolvedValue(data())
    renderPage()
    await screen.findByRole('rowheader', { name: /Thin Sample/ })

    const r = rowFor('Thin Sample')
    expect(within(r).getByText('drafted in 30% of Yahoo leagues')).toBeInTheDocument()
    expect(within(r).getByText('100.0').closest('td')).toHaveClass('adp-grey')
    expect(gapPill('Thin Sample')).toHaveTextContent('not compared')
    expect(gapPill('Thin Sample')).not.toHaveClass('yahoo')
    expect(gapPill('Thin Sample')).not.toHaveClass('sleeper')
  })

  it('raising the gap threshold removes a tint (US1 AS3); the default is 20', async () => {
    const mid = player({ playerId: 9, sleeperId: 'mid', name: 'Middle Gap', dynasty: 70, yahooAdp: 40, yahooPctDrafted: 0.9 })   // -30
    getAdpCompare.mockResolvedValue(data({ players: [mid] }))
    renderPage()
    await screen.findByRole('rowheader', { name: /Middle Gap/ })
    expect(screen.getByRole('slider', { name: 'Gap threshold in picks' })).toHaveValue('20')
    expect(gapPill('Middle Gap')).toHaveClass('yahoo')

    fireEvent.change(screen.getByRole('slider', { name: 'Gap threshold in picks' }), { target: { value: '35' } })

    expect(gapPill('Middle Gap')).not.toHaveClass('yahoo')
    // The words stay: only the tint depends on the threshold.
    expect(gapPill('Middle Gap')).toHaveTextContent('Yahoo 30.0 picks earlier')
  })

  it('marks a Yahoo player no local player matched, with no link', async () => {
    getAdpCompare.mockResolvedValue(data())
    renderPage()
    await screen.findByRole('rowheader', { name: /Mystery Man/ })

    const r = rowFor('Mystery Man')
    expect(within(r).getByText('not matched to a player')).toBeInTheDocument()
    expect(within(r).queryByRole('link')).toBeNull()
    expect(gapPill('Mystery Man')).toHaveTextContent('no dynasty ADP')
    // A matched player links to his page.
    expect(within(rowFor('Kevin Durant')).getByRole('link', { name: 'Kevin Durant' })).toHaveAttribute('href', '/leagues/L1/players/kd')
  })

  it('labels a rookie and shows a dash, not zero, for a missing value', async () => {
    getAdpCompare.mockResolvedValue(data())
    renderPage()
    await screen.findByRole('rowheader', { name: /AJ Dybantsa/ })

    expect(within(rowFor('AJ Dybantsa')).getByText('Rookie')).toBeInTheDocument()
    expect(within(rowFor('Kevin Durant')).getByText('18 yr')).toBeInTheDocument()
    const cells = within(rowFor('No Dynasty')).getAllByRole('cell')
    expect(cells[1]).toHaveTextContent('—')   // dynasty missing
  })

  it('searches by name or team, filters by position, and shows rookies only', async () => {
    const user = userEvent.setup()
    getAdpCompare.mockResolvedValue(data())
    renderPage()
    await screen.findByRole('rowheader', { name: /Kevin Durant/ })

    await user.type(screen.getByRole('searchbox'), 'durant')
    expect(order()).toHaveLength(1)
    await user.clear(screen.getByRole('searchbox'))

    await user.type(screen.getByRole('searchbox'), 'mia')   // team
    expect(order()[0]).toMatch(/Thin Sample/)
    await user.clear(screen.getByRole('searchbox'))

    await user.click(screen.getByRole('button', { name: 'C' }))
    expect(order()).toHaveLength(1)
    expect(order()[0]).toMatch(/Thin Sample/)
    await user.click(screen.getByRole('button', { name: 'All' }))
    expect(order()).toHaveLength(5)

    await user.click(screen.getByRole('checkbox', { name: 'Rookies only' }))
    expect(order()).toHaveLength(1)
    expect(order()[0]).toMatch(/AJ Dybantsa/)
  })

  it('sorts the gap either way from the header, rows with no trusted gap last both ways', async () => {
    const user = userEvent.setup()
    getAdpCompare.mockResolvedValue(data())
    renderPage()
    await screen.findByRole('rowheader', { name: /Kevin Durant/ })

    await user.click(screen.getByRole('button', { name: /^Gap/ }))   // ascending: Yahoo earliest first (-60, then +85)
    expect(order()[0]).toMatch(/^Kevin Durant/)
    expect(order()[1]).toMatch(/^AJ Dybantsa/)
    expect(screen.getByRole('columnheader', { name: /Gap/ })).toHaveAttribute('aria-sort', 'ascending')
    const lastThreeAsc = order().slice(2)

    await user.click(screen.getByRole('button', { name: /^Gap/ }))   // descending: dynasty-favoured first
    expect(order()[0]).toMatch(/^AJ Dybantsa/)
    expect(order()[1]).toMatch(/^Kevin Durant/)
    expect(screen.getByRole('columnheader', { name: /Gap/ })).toHaveAttribute('aria-sort', 'descending')
    // Thin Sample (unreliable), No Dynasty and Mystery Man (no dynasty) have no trusted gap: the same three, last, in the same order.
    expect(order().slice(2)).toEqual(lastThreeAsc)
    for (const n of ['Thin Sample', 'No Dynasty', 'Mystery Man']) expect(lastThreeAsc.some((t) => t.startsWith(n))).toBe(true)
  })

  it('states the honesty notes: Yahoo is redraft, its leagues are assumed to be category leagues, the cutoffs are hand-set', async () => {
    getAdpCompare.mockResolvedValue(data())
    renderPage()
    await screen.findByRole('rowheader', { name: /Kevin Durant/ })

    const notes = screen.getByRole('list', { name: 'Notes on these numbers' })
    expect(notes).toHaveTextContent(/Yahoo.s numbers are redraft/)
    expect(notes).toHaveTextContent(/assumed to be category leagues/)
    expect(notes).toHaveTextContent(/assumption, not something this page has checked/)
    expect(notes).toHaveTextContent(/hand-set and arbitrary/)
  })

  it('says there is nothing to compare when no ADP has been captured', async () => {
    getAdpCompare.mockResolvedValue(data({ players: [], costCurve: [] }))
    renderPage()
    expect(await screen.findByText(/No ADP has been captured yet/)).toBeInTheDocument()
  })

  it('renders the not-found state for a league that is not visible', async () => {
    getAdpCompare.mockRejectedValue(new ApiError(404))
    renderPage()
    expect(await screen.findByText('Nothing here')).toBeInTheDocument()
    expect(screen.queryByRole('table')).toBeNull()
  })
})
