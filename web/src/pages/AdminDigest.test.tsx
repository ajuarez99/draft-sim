import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminDigest from './AdminDigest'
import { AdminTokenRequiredError } from '../apiError'
import type { AdminDigest as Digest, DigestEntry, SportDigest } from '../api'

const getAdminDigest = vi.fn()
const refreshDigestStats = vi.fn()
vi.mock('../api', () => ({
  getAdminDigest: (...args: unknown[]) => getAdminDigest(...args),
  refreshDigestStats: (...args: unknown[]) => refreshDigestStats(...args),
}))

function entry(over: Partial<DigestEntry> = {}): DigestEntry {
  return {
    playerId: '1',
    name: 'Nikola Jokić',
    position: 'C',
    team: 'DEN',
    opponent: 'GSW',
    isAway: false,
    points: 62.5,
    line: { pts: 34, reb: 12, ast: 8, stl: 3 },
    ...over,
  }
}

function sport(over: Partial<SportDigest> = {}): SportDigest {
  return {
    sport: 'nba',
    season: 2026,
    pointsBasis: 'Sleeper default points',
    status: 'READY',
    statsFetchedAt: '2026-10-21T11:00:00Z',
    date: '2026-10-20',
    week: 1,
    gamesCount: 2,
    lastSlateDate: '2026-10-20',
    inProgressDate: null,
    nextGameDate: null,
    trending: {
      status: 'OK',
      lookbackHours: 24,
      fetchedAt: '2026-10-20T23:48:00Z',
      stale: false,
      omittedUnknownPlayers: 0,
      entries: [{ rank: 1, addCount: 4210, outcome: 'PLAYED', entry: entry() }],
    },
    topPerformers: [entry()],
    keyPlayers: [{ rank: 1, entry: entry(), played: true, expectation: 50, expectationBasis: 'THIS_SEASON', verdict: 'ABOVE' }],
    positionLeaders: null,
    ...over,
  }
}

function digest(sports: SportDigest[]): Digest {
  return { generatedAt: '2026-10-21T14:00:00Z', yesterday: '2026-10-20', sports }
}

const NFL_IDLE = sport({
  sport: 'nfl',
  pointsBasis: 'Sleeper PPR',
  status: 'NO_GAMES_LAST_NIGHT',
  date: null,
  week: null,
  gamesCount: null,
  trending: null,
  topPerformers: [],
  keyPlayers: [],
  positionLeaders: [],
})

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
})

describe('AdminDigest', () => {
  it('asks for the token when there is none, and does not call the API', () => {
    render(<AdminDigest />)
    expect(screen.getByLabelText('Admin token')).toBeInTheDocument()
    expect(getAdminDigest).not.toHaveBeenCalled()
  })

  it('stores the token for the tab session and loads the cards', async () => {
    getAdminDigest.mockResolvedValue(digest([sport(), NFL_IDLE]))
    const user = userEvent.setup()
    render(<AdminDigest />)
    await user.type(screen.getByLabelText('Admin token'), 'secret')
    await user.click(screen.getByRole('button', { name: 'Continue' }))

    expect(await screen.findByLabelText('Most added, and how they did text')).toHaveValue(
      '📈 Most added on Sleeper, and how they did last night:\n1. Nikola Jokić (+4,210) — 62.5 FP (34 PTS/12 REB/8 AST/3 STL)',
    )
    expect(getAdminDigest).toHaveBeenCalledWith('secret', undefined)
    expect(sessionStorage.getItem('bk.adminToken')).toBe('secret')
    expect(screen.getByLabelText('Top fantasy scores text')).toBeInTheDocument()
    expect(screen.getByLabelText('How the stars did text')).toHaveValue('⭐ How the stars did:\nNikola Jokić — 62.5 FP ✅')
    expect(screen.getByText(/Sleeper default points/)).toBeInTheDocument()
    expect(screen.getByText(/No games last night/)).toBeInTheDocument()
  })

  it('updates the counter on edit and copies the edited text', async () => {
    sessionStorage.setItem('bk.adminToken', 'secret')
    getAdminDigest.mockResolvedValue(digest([sport(), NFL_IDLE]))
    const user = userEvent.setup()
    const writeText = vi.spyOn(navigator.clipboard, 'writeText')
    render(<AdminDigest />)

    const box = await screen.findByLabelText('Best single line text')
    await user.clear(box)
    await user.type(box, 'hello')
    const card = box.closest('.dg-card') as HTMLElement
    expect(card.querySelector('.dg-count')).toHaveTextContent('5 / 280')

    await user.click(card.querySelector('button:last-of-type') as HTMLElement)
    expect(writeText).toHaveBeenCalledWith('hello')
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument()
  })

  it('turns the counter red past 280', async () => {
    sessionStorage.setItem('bk.adminToken', 'secret')
    getAdminDigest.mockResolvedValue(digest([sport(), NFL_IDLE]))
    const user = userEvent.setup()
    render(<AdminDigest />)
    const box = await screen.findByLabelText('Best single line text')
    await user.clear(box)
    await user.click(box)
    await user.paste('x'.repeat(281))
    const count = (box.closest('.dg-card') as HTMLElement).querySelector('.dg-count')!
    expect(count).toHaveTextContent('281 / 280')
    expect(count).toHaveClass('over')
  })

  it('clears the stored token and asks again when the server refuses it', async () => {
    sessionStorage.setItem('bk.adminToken', 'stale')
    getAdminDigest.mockRejectedValue(new AdminTokenRequiredError())
    render(<AdminDigest />)
    expect(await screen.findByText(/That token was refused/)).toBeInTheDocument()
    expect(sessionStorage.getItem('bk.adminToken')).toBeNull()
    expect(screen.getByLabelText('Admin token')).toBeInTheDocument()
  })

  it('shows the fetch button and no cards when the night has not been fetched', async () => {
    sessionStorage.setItem('bk.adminToken', 'secret')
    getAdminDigest.mockResolvedValue(
      digest([
        sport({ status: 'NOT_FETCHED', date: null, week: null, gamesCount: null, trending: null, topPerformers: [], keyPlayers: [] }),
        NFL_IDLE,
      ]),
    )
    render(<AdminDigest />)
    expect(await screen.findByText(/their stats haven't been fetched yet/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Fetch latest stats' })).toBeInTheDocument()
    expect(screen.queryAllByRole('textbox')).toHaveLength(0)
  })

  it('ignores a response that was superseded by a newer load, and hides the old cards while loading (S2)', async () => {
    sessionStorage.setItem('bk.adminToken', 'secret')
    let resolveSlow: (d: Digest) => void = () => {}
    const slow = new Promise<Digest>((r) => {
      resolveSlow = r
    })
    getAdminDigest.mockResolvedValueOnce(digest([sport(), NFL_IDLE])) // the default load
    getAdminDigest.mockReturnValueOnce(slow) // a chosen date, slow
    getAdminDigest.mockResolvedValueOnce(
      digest([sport({ topPerformers: [entry({ name: 'Newest Win' })] }), NFL_IDLE]),
    ) // "Last night" again
    const user = userEvent.setup()
    render(<AdminDigest />)
    await screen.findByLabelText('Top fantasy scores text')

    fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-04-12' } })
    // While the chosen date loads, the previous cards (and their Copy buttons) are gone.
    await waitFor(() => expect(screen.queryByLabelText('Top fantasy scores text')).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Copy' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Last night' }))
    expect(((await screen.findByLabelText('Top fantasy scores text')) as HTMLTextAreaElement).value).toContain('Newest Win')

    // The slow past-date response lands last and must not overwrite the newer data.
    resolveSlow(digest([sport({ topPerformers: [entry({ name: 'Stale Apr' })] }), NFL_IDLE]))
    await new Promise((r) => setTimeout(r, 20))
    expect((screen.getByLabelText('Top fantasy scores text') as HTMLTextAreaElement).value).toContain('Newest Win')
    expect(screen.queryByDisplayValue(/Stale Apr/)).not.toBeInTheDocument()
  })

  it('never offers a header-only card; it says there is nothing to post (L5)', async () => {
    sessionStorage.setItem('bk.adminToken', 'secret')
    getAdminDigest.mockResolvedValue(
      digest([sport({ topPerformers: [entry({ name: 'X'.repeat(400) })] }), NFL_IDLE]),
    )
    render(<AdminDigest />)
    expect(await screen.findByText(/Nothing to post for “Top fantasy scores”/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Top fantasy scores text')).not.toBeInTheDocument()
  })

  it('says a future date has not happened yet (L1)', async () => {
    sessionStorage.setItem('bk.adminToken', 'secret')
    getAdminDigest.mockResolvedValue(
      digest([
        sport({ status: 'DATE_IN_FUTURE', date: null, week: null, gamesCount: null, trending: null, topPerformers: [], keyPlayers: [] }),
        NFL_IDLE,
      ]),
    )
    render(<AdminDigest />)
    expect((await screen.findAllByText(/hasn't happened yet/)).length).toBeGreaterThan(0)
    expect(screen.queryAllByRole('textbox')).toHaveLength(0)
  })

  it('shows failed weeks and a failed schedule save in the fetch note (S4)', async () => {
    sessionStorage.setItem('bk.adminToken', 'secret')
    getAdminDigest.mockResolvedValue(digest([sport(), NFL_IDLE]))
    refreshDigestStats.mockResolvedValue({
      results: [{ sport: 'nba', season: 2026, weeksFetched: 0, gamesStored: 0, weeksFailed: 2, scheduleStoreFailed: true, error: null }],
    })
    const user = userEvent.setup()
    render(<AdminDigest />)
    await user.click(await screen.findByRole('button', { name: 'Fetch latest stats' }))
    expect(await screen.findByText(/NBA: 0 weeks fetched, 0 games, 2 weeks FAILED, schedule not saved/)).toBeInTheDocument()
  })

  it('keeps the copy-blocked hint until the next action (L7)', async () => {
    sessionStorage.setItem('bk.adminToken', 'secret')
    getAdminDigest.mockResolvedValue(digest([sport(), NFL_IDLE]))
    const user = userEvent.setup()
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('blocked'))
    document.execCommand = (() => false) as unknown as typeof document.execCommand
    render(<AdminDigest />)
    const box = await screen.findByLabelText('Best single line text')
    const card = box.closest('.dg-card') as HTMLElement
    await user.click(card.querySelector('button:last-of-type') as HTMLElement)
    expect(await screen.findByText(/Copy was blocked/)).toBeInTheDocument()
    await new Promise((r) => setTimeout(r, 2200))
    expect(screen.getByText(/Copy was blocked/)).toBeInTheDocument()
  })

  it('fetches stats then reloads the digest', async () => {
    sessionStorage.setItem('bk.adminToken', 'secret')
    getAdminDigest.mockResolvedValue(digest([sport(), NFL_IDLE]))
    refreshDigestStats.mockResolvedValue({
      results: [{ sport: 'nba', season: 2026, weeksFetched: 1, gamesStored: 61, weeksFailed: 0, scheduleStoreFailed: false, error: null }],
    })
    const user = userEvent.setup()
    render(<AdminDigest />)
    await user.click(await screen.findByRole('button', { name: 'Fetch latest stats' }))
    await waitFor(() => expect(getAdminDigest).toHaveBeenCalledTimes(2))
    expect(refreshDigestStats).toHaveBeenCalledWith('secret')
    expect(await screen.findByText(/NBA: 1 week fetched, 61 games/)).toBeInTheDocument()
  })
})
