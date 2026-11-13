import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoricalBadge, HistoricalGameAward, HistoricalProfile } from '../api'
import HistoricalAwardPanel from './HistoricalAwardPanel'

const getHistoricalGameAward = vi.fn()
const getHistoricalProfile = vi.fn()
const markHistoricalSeen = vi.fn()
vi.mock('../api', async (orig) => ({
  ...(await orig<typeof import('../api')>()),
  getHistoricalGameAward: (...a: unknown[]) => getHistoricalGameAward(...a),
  getHistoricalProfile: (...a: unknown[]) => getHistoricalProfile(...a),
  markHistoricalSeen: (...a: unknown[]) => markHistoricalSeen(...a),
}))

beforeEach(() => {
  getHistoricalGameAward.mockReset()
  getHistoricalProfile.mockReset()
  markHistoricalSeen.mockReset()
  markHistoricalSeen.mockResolvedValue(undefined)
})

const badge = (code: string, over: Partial<HistoricalBadge> = {}): HistoricalBadge => ({
  code,
  name: `Badge ${code}`,
  rule: `Rule ${code}`,
  xp: 50,
  source: 'STORED',
  unlocked: true,
  unlockedAt: '2026-11-13T12:00:00Z',
  gameId: 7,
  detail: null,
  seen: false,
  ...over,
})

const profile = (over: Partial<HistoricalProfile> = {}): HistoricalProfile => ({
  xp: 340, level: 3, levelName: 'Rookie', levelStartXp: 300, nextLevelXp: 600, seenLevel: 3,
  totals: { games: 4, gamesFromSavedResults: 0, weeklyWins: 30, playoffWins: 2, titles: 1 },
  badges: [], settleFailures: 0, ...over,
})

const award = (over: Partial<HistoricalGameAward> = {}): HistoricalGameAward => ({
  gameId: 7, settled: true, fromSavedResult: false, xp: 120,
  lines: [{ label: '9 wins', xp: 90 }, { label: 'Finished the draft', xp: 30 }],
  badges: [badge('TITLE', { xp: 80 })],
  moments: [{ code: 'BIG_BUST', name: 'Big bust', text: 'Pick 3 finished far down the list.', pickNo: 3, playerName: 'Some Guy' }],
  momentsSeen: false,
  total: 200,
  ...over,
})

describe('HistoricalAwardPanel', () => {
  it('shows the lines, the badge, the total and the moments', async () => {
    getHistoricalGameAward.mockResolvedValue(award())
    getHistoricalProfile.mockResolvedValue(profile())
    render(<HistoricalAwardPanel gameId={7} />)
    const panel = await screen.findByRole('region', { name: 'What this game earned' })
    expect(within(panel).getByText('9 wins')).toBeInTheDocument()
    expect(within(panel).getByText('+90 XP')).toBeInTheDocument()
    expect(within(panel).getByText('Total').nextSibling).toHaveTextContent('+200 XP')
    expect(within(panel).getAllByText('Pick 3 finished far down the list.').length).toBeGreaterThan(0)
    expect(getHistoricalGameAward).toHaveBeenCalledWith(7)
  })

  it('toasts the unseen badge, the moment and a level-up, and posts seen for each', async () => {
    getHistoricalGameAward.mockResolvedValue(award())
    getHistoricalProfile.mockResolvedValue(profile({ level: 4, levelName: 'Starter', seenLevel: 3 }))
    render(<HistoricalAwardPanel gameId={7} />)
    await screen.findByText('Level 4: Starter')
    await waitFor(() => expect(markHistoricalSeen).toHaveBeenCalledTimes(3))
    const bodies = markHistoricalSeen.mock.calls.map((c) => c[0])
    expect(bodies).toContainEqual({ codes: ['TITLE'], level: null, momentsGameId: null })
    expect(bodies).toContainEqual({ codes: [], level: 4, momentsGameId: null })
    expect(bodies).toContainEqual({ codes: [], level: null, momentsGameId: 7 })
  })

  it('raises no toast for a seen badge, seen moments or an unchanged level', async () => {
    getHistoricalGameAward.mockResolvedValue(award({ badges: [badge('TITLE', { seen: true })], momentsSeen: true }))
    getHistoricalProfile.mockResolvedValue(profile())
    render(<HistoricalAwardPanel gameId={7} />)
    await screen.findByText('What this game earned')
    expect(screen.queryByRole('button', { name: /^Dismiss/ })).not.toBeInTheDocument()
    expect(markHistoricalSeen).not.toHaveBeenCalled()
  })

  it('says so when the awards could not be worked out', async () => {
    getHistoricalGameAward.mockResolvedValue(award({ settled: false, xp: 0, lines: [], badges: [], moments: [], total: 0 }))
    getHistoricalProfile.mockResolvedValue(profile())
    render(<HistoricalAwardPanel gameId={7} />)
    expect(await screen.findByText("This game's awards could not be worked out.")).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Dismiss/ })).not.toBeInTheDocument()
  })

  it('shows one muted line when the request fails', async () => {
    getHistoricalGameAward.mockRejectedValue(new Error('boom'))
    getHistoricalProfile.mockRejectedValue(new Error('boom'))
    const { container } = render(<HistoricalAwardPanel gameId={7} />)
    expect(await screen.findByText("This game's awards could not be loaded.")).toBeInTheDocument()
    expect(container.querySelectorAll('li')).toHaveLength(0)
    expect(markHistoricalSeen).not.toHaveBeenCalled()
  })

  it('still shows the award when only the profile request fails', async () => {
    getHistoricalGameAward.mockResolvedValue(award({ momentsSeen: true, badges: [], total: 120 }))
    getHistoricalProfile.mockRejectedValue(new Error('boom'))
    render(<HistoricalAwardPanel gameId={7} />)
    expect(await screen.findByText('9 wins')).toBeInTheDocument()
  })

  it('notes a game counted from its saved result', async () => {
    getHistoricalGameAward.mockResolvedValue(award({ fromSavedResult: true }))
    getHistoricalProfile.mockResolvedValue(profile())
    render(<HistoricalAwardPanel gameId={7} />)
    expect(
      await screen.findByText(
        "Counted from this game's saved result. Pick-by-pick badges need a game finished after achievements were added.",
      ),
    ).toBeInTheDocument()
  })

  it('lets a toast be dismissed with its button', async () => {
    getHistoricalGameAward.mockResolvedValue(award({ moments: [], momentsSeen: true }))
    getHistoricalProfile.mockResolvedValue(profile())
    render(<HistoricalAwardPanel gameId={7} />)
    const btn = await screen.findByRole('button', { name: 'Dismiss: Badge TITLE' })
    fireEvent.click(btn)
    expect(screen.queryByRole('button', { name: 'Dismiss: Badge TITLE' })).not.toBeInTheDocument()
  })

  it('raises one toast, not two, for a first bust and its matching moment, and still marks moments seen', async () => {
    const text = 'Synthetic 112, pick 24, finished 107th of 168 drafted players by season value.'
    getHistoricalGameAward.mockResolvedValue(
      award({
        badges: [badge('BIG_BUST', { name: 'Swing and a miss', detail: { text, pickNo: 24, playerName: 'Synthetic 112' } })],
        moments: [
          { code: 'BIG_BUST', name: 'Swing and a miss', text, pickNo: 24, playerName: 'Synthetic 112' },
          { code: 'BIG_BUST', name: 'Swing and a miss', text: 'The other one.', pickNo: 13, playerName: 'Other' },
        ],
      }),
    )
    getHistoricalProfile.mockResolvedValue(profile())
    render(<HistoricalAwardPanel gameId={7} />)
    await screen.findAllByRole('button', { name: 'Dismiss: Swing and a miss' })
    // the badge toast, plus the moment on a different pick, which is a different event
    expect(screen.getAllByRole('button', { name: 'Dismiss: Swing and a miss' })).toHaveLength(2)
    expect(screen.getByText('Badge unlocked')).toBeInTheDocument()
    expect(screen.getAllByText('Moment')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveTextContent('The other one.')
  })

  it('posts the moments mark when every moment was folded into a badge toast', async () => {
    const text = 'Synthetic 112, pick 24, finished 107th.'
    getHistoricalGameAward.mockResolvedValue(
      award({
        badges: [badge('BIG_BUST', { detail: { text, pickNo: 24, playerName: 'Synthetic 112' } })],
        moments: [{ code: 'BIG_BUST', name: 'Swing and a miss', text, pickNo: 24, playerName: 'Synthetic 112' }],
      }),
    )
    getHistoricalProfile.mockResolvedValue(profile())
    render(<HistoricalAwardPanel gameId={7} />)
    await waitFor(() =>
      expect(markHistoricalSeen.mock.calls.map((c) => c[0])).toContainEqual({
        codes: [],
        level: null,
        momentsGameId: 7,
      }),
    )
    expect(screen.queryByText('Moment')).not.toBeInTheDocument()
  })

  it('has its live region mounted and empty before the award request resolves, and keeps it through every branch', async () => {
    let resolve: (a: HistoricalGameAward) => void = () => {}
    getHistoricalGameAward.mockReturnValue(new Promise<HistoricalGameAward>((r) => (resolve = r)))
    getHistoricalProfile.mockResolvedValue(profile())
    render(<HistoricalAwardPanel gameId={7} />)
    const region = screen.getByRole('status')
    expect(region).toBeEmptyDOMElement()
    resolve(award())
    await screen.findByText('Level 3: Rookie').catch(() => {})
    await screen.findByRole('button', { name: 'Dismiss: Badge TITLE' })
    expect(screen.getByRole('status')).toBe(region)   // the same node: content arrived into an existing region
  })

  it('keeps an empty live region on the failed and the not-settled branches too', async () => {
    getHistoricalGameAward.mockRejectedValue(new Error('boom'))
    getHistoricalProfile.mockRejectedValue(new Error('boom'))
    const failed = render(<HistoricalAwardPanel gameId={7} />)
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
    await screen.findByText("This game's awards could not be loaded.")
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
    failed.unmount()

    getHistoricalGameAward.mockResolvedValue(award({ settled: false, xp: 0, lines: [], badges: [], moments: [], total: 0 }))
    getHistoricalProfile.mockResolvedValue(profile())
    render(<HistoricalAwardPanel gameId={7} />)
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
    await screen.findByText("This game's awards could not be worked out.")
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
  })
})
