import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdpCompare from './AdpCompare'
import type { AdpCompareResponse, AdpDraft, AdpPlayer, AdpSource, AdpTeamState, AdpSaleRow } from '../api'

// Spec 046 US2 + US3 on the page: the blend sliders, Blend $, budget cards, the sales feed and the live refetch.

const getAdpCompare = vi.fn()
vi.mock('../api', () => ({
  getAdpCompare: (...args: unknown[]) => getAdpCompare(...args),
}))

// The page must use the EXISTING stream; the mock records what it was asked to follow.
let liveState: { picksMade: number } | null = null
const useLiveDraft = vi.fn((draftId: string) => ({ live: draftId ? liveState : null, connected: true, secondsSinceContact: 0, error: null }))
vi.mock('../useLiveDraft', () => ({
  useLiveDraft: (draftId: string) => useLiveDraft(draftId),
}))

function player(over: Partial<AdpPlayer>): AdpPlayer {
  return {
    playerId: 1, sleeperId: 's1', name: 'Player', team: 'BOS', positions: ['PG'], yearsExp: 3,
    dynasty: null, redraft: null, yahooAdp: null, yahooCost: null, yahooPctDrafted: null, sale: null,
    ...over,
  }
}

const sources: AdpSource[] = [
  { key: 'sleeper_dynasty', label: 'Sleeper dynasty ADP', capturedOn: '2026-10-23', ranked: 3, scoring: 'dynasty', unmatched: null, deepestAdp: null, minPercentDrafted: null },
  { key: 'sleeper_redraft', label: 'Sleeper redraft ADP', capturedOn: '2026-10-23', ranked: 3, scoring: 'redraft', unmatched: null, deepestAdp: null, minPercentDrafted: null },
  { key: 'yahoo', label: 'Yahoo ADP', capturedOn: '2026-10-23', ranked: 3, scoring: 'redraft', unmatched: 0, deepestAdp: 50, minPercentDrafted: 0.5 },
]

// Three players, dynasty order A, B, C; Yahoo agrees (level) so the blend leaves the order alone.
const A = player({ playerId: 1, sleeperId: 'a', name: 'Alpha Ace', dynasty: 1, yahooAdp: 1, yahooCost: 60, yahooPctDrafted: 1 })
const B = player({ playerId: 2, sleeperId: 'b', name: 'Bravo Bee', dynasty: 2, yahooAdp: 2, yahooCost: 30, yahooPctDrafted: 1 })
const C = player({ playerId: 3, sleeperId: 'c', name: 'Charlie Cee', dynasty: 3, yahooAdp: 3, yahooCost: 10, yahooPctDrafted: 1 })

const teamsState: AdpTeamState[] = [
  { managerId: 11, name: 'Ann', avatar: null, slot: 1, spent: 30, filled: 1, spotsLeft: 1, left: 70, maxBid: 70, priceMissing: 0, mine: true },
  { managerId: null, name: null, avatar: null, slot: 2, spent: 0, filled: 1, spotsLeft: 1, left: 100, maxBid: 100, priceMissing: 1, mine: false },
]
const sales: AdpSaleRow[] = [
  { pickNo: 2, amount: null, managerId: null, playerId: null, name: 'Mystery Guest', priceMissing: true },
  { pickNo: 1, amount: 30, managerId: 11, playerId: 2, name: 'Bravo Bee', priceMissing: false },
]

function auctionDraft(over: Partial<AdpDraft> = {}): AdpDraft {
  return { sleeperDraftId: 'D1', type: 'auction', status: 'drafting', teams: 2, rounds: 2, budget: 100, teamsState, sales, ...over }
}

// N = 2 x 2 = 4 spots, 2 x $100 = $200. Curve [60, 30, 10, 5]: surplus 101, spare 196, k = 196/101.
// $ = 1 + k*(c-1): 115, 57, 18, 8 (whole dollars). Alpha is rank 1, Bravo 2, Charlie 3.
const COSTS = [60, 30, 10, 5]

function data(over: Partial<AdpCompareResponse> = {}): AdpCompareResponse {
  return {
    sport: 'nba',
    sources,
    players: [A, B, C],
    costCurve: COSTS,
    draft: auctionDraft(),
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

const order = () => screen.getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('rowheader')[0].textContent ?? '')

describe('ADP & prices: blend', () => {
  beforeEach(() => {
    getAdpCompare.mockReset()
    useLiveDraft.mockClear()
    liveState = null
  })

  it('has a trust slider at 75% and a full-trust-gap slider at 30, both labelled hand-set', async () => {
    getAdpCompare.mockResolvedValue(data())
    renderPage()
    const trust = await screen.findByRole('slider', { name: /Trust in Yahoo/ })
    const gap = screen.getByRole('slider', { name: /Gap at which Yahoo gets its full say/ })
    expect(trust).toHaveValue('75')
    expect(gap).toHaveValue('30')
    expect(screen.getAllByText('hand-set').length).toBeGreaterThanOrEqual(2)
  })

  it('shows Blend $ from the league budget, scaled so the room adds up, with the FR-013 note', async () => {
    getAdpCompare.mockResolvedValue(data())
    renderPage()
    await screen.findByRole('columnheader', { name: /Blend \$/ })
    const rowOf = (n: string) => screen.getByRole('rowheader', { name: new RegExp(n) }).closest('tr') as HTMLElement
    expect(within(rowOf('Alpha')).getByText('$115')).toBeInTheDocument()
    expect(within(rowOf('Bravo')).getByText('$57')).toBeInTheDocument()
    expect(within(rowOf('Charlie')).getByText('$18')).toBeInTheDocument()

    const note = screen.getByTestId('blend-cost-note').textContent ?? ''
    expect(note).toMatch(/scaled to add up to this league’s total budget/)
    expect(note).toMatch(/does not model how fewer teams and deeper rosters push money to the top/)
    expect(note).toMatch(/in the one auction measured, the top sold 25–50% above it/)
  })

  it('hides Blend $ with the reason for a snake draft with no budget, and still shows the note', async () => {
    getAdpCompare.mockResolvedValue(data({ draft: { sleeperDraftId: 'S1', type: 'snake', status: 'complete', teams: 10, rounds: 13, budget: null } }))
    renderPage()
    await screen.findByRole('rowheader', { name: /Alpha/ })
    expect(screen.queryByRole('columnheader', { name: /Blend \$/ })).not.toBeInTheDocument()
    const note = screen.getByTestId('blend-cost-note').textContent ?? ''
    expect(note).toMatch(/does not model how fewer teams/)
    expect(note).toMatch(/unavailable here: this league’s draft has no budget/)
  })

  it('hides Blend $ with its own reason when Yahoo has no price capture or the league has no draft', async () => {
    getAdpCompare.mockResolvedValue(data({ costCurve: [] }))
    const first = renderPage()
    await screen.findByRole('rowheader', { name: /Alpha/ })
    expect(screen.queryByRole('columnheader', { name: /Blend \$/ })).not.toBeInTheDocument()
    expect(screen.getByTestId('blend-cost-note').textContent).toMatch(/Yahoo has no price capture yet/)
    first.unmount()

    getAdpCompare.mockResolvedValue(data({ draft: null }))
    renderPage()
    await screen.findByRole('rowheader', { name: /Alpha/ })
    expect(screen.getByTestId('blend-cost-note').textContent).toMatch(/no Sleeper draft/)
  })

  it('sorts by blended ADP and by Blend $, either way', async () => {
    const user = userEvent.setup()
    // Show everything: sold players are hidden by default in an auction.
    getAdpCompare.mockResolvedValue(data({ draft: auctionDraft({ status: 'complete' }) }))
    renderPage()
    await screen.findByRole('rowheader', { name: /Alpha/ })
    await user.click(screen.getByRole('button', { name: /Blended ADP/ }))
    expect(order().map((t) => t.slice(0, 5))).toEqual(['Alpha', 'Bravo', 'Charl'])
    await user.click(screen.getByRole('button', { name: /Blended ADP/ }))
    expect(order().map((t) => t.slice(0, 5))).toEqual(['Charl', 'Bravo', 'Alpha'])
    await user.click(screen.getByRole('button', { name: /Blend \$/ }))
    expect(order().map((t) => t.slice(0, 5))).toEqual(['Alpha', 'Bravo', 'Charl'])
  })

  it('writes a blended sub-line only when Yahoo moves him by 5% or more of the way', async () => {
    // Durant-like: Yahoo 60 picks earlier -> full weight. Level player: no movement -> no sub-line.
    const moved = player({ playerId: 5, sleeperId: 'm', name: 'Moved Mo', dynasty: 80, yahooAdp: 20, yahooPctDrafted: 1 })
    const level = player({ playerId: 6, sleeperId: 'l', name: 'Level Lee', dynasty: 10, yahooAdp: 10, yahooPctDrafted: 1 })
    getAdpCompare.mockResolvedValue(data({ players: [moved, level], draft: null }))
    renderPage()
    const row = (await screen.findByRole('rowheader', { name: /Moved Mo/ })).closest('tr') as HTMLElement
    expect(within(row).getByText('35.0')).toBeInTheDocument()           // 80 + 0.75 * (20 - 80)
    expect(within(row).getByText('75% of the way to Yahoo')).toBeInTheDocument()
    const lvl = screen.getByRole('rowheader', { name: /Level Lee/ }).closest('tr') as HTMLElement
    expect(within(lvl).queryByText(/of the way to Yahoo/)).not.toBeInTheDocument()
  })
})

describe('ADP & prices: live auction', () => {
  beforeEach(() => {
    getAdpCompare.mockReset()
    useLiveDraft.mockClear()
    liveState = null
  })

  it('draws a budget card per slot from teamsState: money, spots, max bid, the viewer outlined', async () => {
    getAdpCompare.mockResolvedValue(data())
    renderPage()
    const region = await screen.findByRole('region', { name: 'Team budgets' })
    const items = within(region).getAllByRole('listitem')
    expect(items).toHaveLength(2)

    expect(within(items[0]).getByText('Ann')).toBeInTheDocument()
    expect(within(items[0]).getByText('$70')).toBeInTheDocument()
    expect(within(items[0]).getByText('1 spot left')).toBeInTheDocument()
    expect(within(items[0]).getByText('max bid $70')).toBeInTheDocument()
    expect(items[0]).toHaveClass('mine')
    expect(within(items[0]).getByText('You')).toBeInTheDocument()

    // A slot with no manager yet is still a card, named by its slot, and says its price is missing.
    expect(within(items[1]).getAllByText('Slot 2').length).toBeGreaterThan(0)
    expect(within(items[1]).getByText('$100')).toBeInTheDocument()
    expect(within(items[1]).getByText('1 price missing')).toBeInTheDocument()
    expect(items[1]).not.toHaveClass('mine')
  })

  it('lists the latest sales with the price against Blend $, and says "price missing" instead of $0', async () => {
    getAdpCompare.mockResolvedValue(data())
    renderPage()
    const feed = await screen.findByRole('region', { name: 'Latest sales' })
    const items = within(feed).getAllByRole('listitem')
    expect(within(items[0]).getByText('Mystery Guest')).toBeInTheDocument()
    expect(within(items[0]).getByText('price missing')).toBeInTheDocument()
    expect(within(items[0]).queryByText('$0')).not.toBeInTheDocument()
    // Bravo sold for $30; Blend $ for him is $57 -> paid $27 under.
    expect(within(items[1]).getByText('Bravo Bee')).toBeInTheDocument()
    expect(within(items[1]).getByText('$30')).toBeInTheDocument()
    expect(within(items[1]).getByText(/Blend \$ 57: paid \$27 under/)).toBeInTheDocument()
  })

  it('hides sold players by default and shows "Sold $X · buyer" when the toggle is off', async () => {
    const user = userEvent.setup()
    const sold = { ...B, sale: { amount: 30, managerId: 11, pickNo: 1 } }
    getAdpCompare.mockResolvedValue(data({ players: [A, sold, C] }))
    renderPage()
    await screen.findByRole('rowheader', { name: /Alpha/ })
    const hide = screen.getByRole('checkbox', { name: 'Hide sold' })
    expect(hide).toBeChecked()
    expect(screen.queryByRole('rowheader', { name: /Bravo Bee/ })).not.toBeInTheDocument()

    await user.click(hide)
    const row = screen.getByRole('rowheader', { name: /Bravo Bee/ })
    expect(within(row).getByText('Sold $30 · Ann')).toBeInTheDocument()
  })

  it('follows the existing live stream while drafting and refetches when picksMade changes', async () => {
    liveState = { picksMade: 2 }   // the page already holds 2 sales: nothing to fetch yet
    getAdpCompare.mockResolvedValue(data())
    const { rerender } = renderPage()
    await screen.findByRole('region', { name: 'Team budgets' })
    expect(useLiveDraft).toHaveBeenCalledWith('D1')
    expect(getAdpCompare).toHaveBeenCalledTimes(1)

    const more = data({ draft: auctionDraft({ sales: [{ pickNo: 3, amount: 5, managerId: 11, playerId: 3, name: 'Charlie Cee', priceMissing: false }, ...sales] }) })
    getAdpCompare.mockResolvedValue(more)
    liveState = { picksMade: 3 }
    await act(async () => {
      rerender(
        <MemoryRouter initialEntries={['/leagues/L1/adp']}>
          <Routes>
            <Route path="/leagues/:sleeperLeagueId/adp" element={<AdpCompare />} />
          </Routes>
        </MemoryRouter>,
      )
    })
    expect(getAdpCompare).toHaveBeenCalledTimes(2)
    const feed = await screen.findByRole('region', { name: 'Latest sales' })
    expect(await within(feed).findByText('Charlie Cee')).toBeInTheDocument()
  })

  it('does not subscribe to the stream once the auction is complete', async () => {
    getAdpCompare.mockResolvedValue(data({ draft: auctionDraft({ status: 'complete' }) }))
    renderPage()
    await screen.findByRole('region', { name: 'Team budgets' })
    expect(useLiveDraft).toHaveBeenCalledWith('')
    expect(useLiveDraft).not.toHaveBeenCalledWith('D1')
  })

  it('shows no budget cards, sales or sold toggle for a snake draft', async () => {
    getAdpCompare.mockResolvedValue(data({ draft: { sleeperDraftId: 'S1', type: 'snake', status: 'drafting', teams: 10, rounds: 13, budget: null } }))
    renderPage()
    await screen.findByRole('rowheader', { name: /Alpha/ })
    expect(screen.queryByRole('region', { name: 'Team budgets' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Latest sales' })).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'Hide sold' })).not.toBeInTheDocument()
    expect(useLiveDraft).not.toHaveBeenCalledWith('S1')
  })

  // ---- Spec 046 C1: the reason must match the draft type ----

  it('C1: an auction with a null budget says Sleeper has not reported the budget, not that it is not an auction', async () => {
    getAdpCompare.mockResolvedValue(data({ draft: auctionDraft({ status: 'complete', budget: null }) }))
    renderPage()
    await screen.findByRole('region', { name: 'Team budgets' })
    const note = screen.getByTestId('blend-cost-note').textContent ?? ''
    expect(note).toMatch(/Sleeper hasn’t reported this auction’s budget yet/)
    expect(note).toMatch(/re-ingest the league/)
    expect(note).not.toMatch(/isn’t an auction/)
  })

  // ---- Spec 046 C2: a finished auction nobody re-ingested has no prices ----

  it('C2: a finished auction whose every price is null gets one line, not a feed of "price missing" rows', async () => {
    const unpriced = [3, 2, 1].map((n): AdpSaleRow => ({ pickNo: n, amount: null, managerId: null, playerId: null, name: `Guest ${n}`, priceMissing: true }))
    getAdpCompare.mockResolvedValue(data({ draft: auctionDraft({ status: 'complete', budget: null, sales: unpriced }) }))
    renderPage()
    const feed = await screen.findByRole('region', { name: 'Latest sales' })
    expect(within(feed).getByTestId('prices-not-ingested').textContent).toMatch(/3 players are recorded as sold, but none has a price yet/)
    expect(within(feed).queryByText('price missing')).not.toBeInTheDocument()
    expect(within(feed).queryAllByRole('listitem')).toHaveLength(0)
  })

  it('C2: a live auction with ONE unpriced sale still lists it (that may be a real missing price)', async () => {
    const one: AdpSaleRow[] = [{ pickNo: 1, amount: null, managerId: null, playerId: null, name: 'Guest 1', priceMissing: true }]
    getAdpCompare.mockResolvedValue(data({ draft: auctionDraft({ sales: one }) }))
    renderPage()
    const feed = await screen.findByRole('region', { name: 'Latest sales' })
    expect(within(feed).queryByTestId('prices-not-ingested')).not.toBeInTheDocument()
    expect(within(feed).getByText('price missing')).toBeInTheDocument()
  })

  // ---- Spec 046 C6: paused, and a failed refetch is retried ----

  it('C6: follows the stream while paused too', async () => {
    getAdpCompare.mockResolvedValue(data({ draft: auctionDraft({ status: 'paused' }) }))
    renderPage()
    await screen.findByRole('region', { name: 'Team budgets' })
    expect(useLiveDraft).toHaveBeenCalledWith('D1')
  })

  it('C6: a failed refetch for a pick count is retried on the next stream event, not dropped for good', async () => {
    liveState = { picksMade: 2 }
    getAdpCompare.mockResolvedValue(data())
    const tree = () => (
      <MemoryRouter initialEntries={['/leagues/L1/adp']}>
        <Routes>
          <Route path="/leagues/:sleeperLeagueId/adp" element={<AdpCompare />} />
        </Routes>
      </MemoryRouter>
    )
    const { rerender } = render(tree())
    await screen.findByRole('region', { name: 'Team budgets' })

    // Pick 3 lands; the refetch fails.
    getAdpCompare.mockRejectedValueOnce(new Error('network'))
    liveState = { picksMade: 3 }
    await act(async () => { rerender(tree()) })
    expect(getAdpCompare).toHaveBeenCalledTimes(2)

    // The next frame still says 3 (a new object, as every SSE frame is): the page asks again and gets it.
    const more = data({ draft: auctionDraft({ sales: [{ pickNo: 3, amount: 5, managerId: 11, playerId: 3, name: 'Charlie Cee', priceMissing: false }, ...sales] }) })
    getAdpCompare.mockResolvedValue(more)
    liveState = { picksMade: 3 }
    await act(async () => { rerender(tree()) })
    expect(getAdpCompare).toHaveBeenCalledTimes(3)
    const feed = await screen.findByRole('region', { name: 'Latest sales' })
    expect(await within(feed).findByText('Charlie Cee')).toBeInTheDocument()

    // Once it succeeded, further frames at the same count do not refetch.
    liveState = { picksMade: 3 }
    await act(async () => { rerender(tree()) })
    expect(getAdpCompare).toHaveBeenCalledTimes(3)
  })

  // ---- Spec 046 C13: unmatched rows sharing a name ----

  it('C13: two unmatched Yahoo rows with the same name do not collide on a React key', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const u1 = player({ playerId: null, sleeperId: null, name: 'Chris Smith', team: 'LAL', yahooAdp: 140, yahooPctDrafted: 0.9 })
    const u2 = player({ playerId: null, sleeperId: null, name: 'Chris Smith', team: 'LAL', yahooAdp: 140, yahooPctDrafted: 0.9 })
    const u3 = player({ playerId: null, sleeperId: null, name: 'Chris Smith', team: 'BKN', yahooAdp: 150, yahooPctDrafted: 0.9 })
    getAdpCompare.mockResolvedValue(data({ players: [u1, u2, u3], draft: null }))
    renderPage()
    await screen.findAllByRole('rowheader', { name: /Chris Smith/ })
    expect(screen.getAllByRole('rowheader', { name: /Chris Smith/ })).toHaveLength(3)
    expect(spy.mock.calls.some((c) => String(c[0]).includes('same key'))).toBe(false)
    spy.mockRestore()
  })
})
