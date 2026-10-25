import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TrophyCase, { LAST_PLACE_TEXT, TrophyCaseTable } from './TrophyCase'
import { finalDisagreeSentence } from '../rivalryText'
import type {
  HofManagerRef,
  Superlative,
  SuperlativesResponse,
  TrophyCase as TrophyCaseData,
  TrophyLastPlaceReason,
  TrophySeason,
} from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
  Link: ({ children, to, className }: { children: React.ReactNode; to: string; className?: string }) => (
    <a href={to} className={className}>{children}</a>
  ),
}))

const getTrophyCase = vi.fn()
const fetchSuperlatives = vi.fn()
vi.mock('../api', () => ({
  getTrophyCase: (...args: unknown[]) => getTrophyCase(...args),
  fetchSuperlatives: (...args: unknown[]) => fetchSuperlatives(...args),
}))

const ref = (rosterId: number, name: string, over: Partial<HofManagerRef> = {}): HofManagerRef => ({
  managerId: rosterId * 10,
  name,
  teamName: null,
  avatarId: null,
  rosterId,
  ...over,
})

function season(over: Partial<TrophySeason> = {}): TrophySeason {
  return {
    season: 2025,
    sleeperLeagueId: 'S2025',
    champion: ref(1, 'Alpha'),
    runnerUp: ref(2, 'Bravo'),
    runnerUpReason: null,
    finalDisputed: false,
    pointsLeaders: [{ ...ref(3, 'Charlie'), pointsFor: 1234.5 }],
    lastPlace: ref(4, 'Delta'),
    lastPlaceReason: null,
    lastPlaceBracket: 'TOILET_BOWL',
    ...over,
  }
}

const tc = (...seasons: TrophySeason[]): TrophyCaseData => ({ sport: 'nfl', status: 'OK', seasons })

beforeEach(() => {
  getTrophyCase.mockReset()
  fetchSuperlatives.mockReset()
})

describe('trophy case table', () => {
  it('names the champion, runner-up, points leader and last place of a season, with the points printed', () => {
    render(<TrophyCaseTable data={tc(season())} />)
    const row = screen.getByRole('row', { name: /2025/ })
    expect(within(row).getByText('Alpha')).toBeInTheDocument()
    expect(within(row).getByText('Bravo')).toBeInTheDocument()
    expect(within(row).getByText('Charlie')).toBeInTheDocument()
    expect(within(row).getByText('1234.50')).toBeInTheDocument()
    expect(within(row).getByText('Delta')).toBeInTheDocument()
    expect(within(row).getByRole('img', { name: 'Champion' })).toBeInTheDocument()
  })

  it('links each name to the manager page, and prefers the team name', () => {
    render(<TrophyCaseTable data={tc(season({ champion: ref(1, 'Alpha', { teamName: 'The Alphas' }) }))} />)
    const link = screen.getByRole('link', { name: /The Alphas/ })
    expect(link).toHaveAttribute('href', '/managers/10/history')
    expect(screen.queryByText('Alpha')).not.toBeInTheDocument()
  })

  it('renders a manager with a null managerId as plain text, never a link', () => {
    render(<TrophyCaseTable data={tc(season({ lastPlace: ref(4, 'Orphan roster', { managerId: null }) }))} />)
    expect(screen.getByText('Orphan roster')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Orphan roster/ })).not.toBeInTheDocument()
    // the other names are still links
    expect(screen.getByRole('link', { name: /Alpha/ })).toBeInTheDocument()
  })

  it('lists every points leader on a tie and says they tied', () => {
    render(
      <TrophyCaseTable
        data={tc(season({ pointsLeaders: [{ ...ref(3, 'Charlie'), pointsFor: 900 }, { ...ref(5, 'Echo'), pointsFor: 900 }] }))}
      />,
    )
    expect(screen.getByText('Charlie')).toBeInTheDocument()
    expect(screen.getByText('Echo')).toBeInTheDocument()
    expect(screen.getAllByText('900.00')).toHaveLength(2)
    expect(screen.getByText('Tied for the most points.')).toBeInTheDocument()
  })

  describe('runner-up reasons', () => {
    it('NO_BRACKET and NO_LOSER say "not available" and name nobody', () => {
      for (const reason of ['NO_BRACKET', 'NO_LOSER'] as const) {
        const { unmount } = render(<TrophyCaseTable data={tc(season({ runnerUp: null, runnerUpReason: reason }))} />)
        expect(screen.getByText(/^Not available — /)).toBeInTheDocument()
        expect(screen.queryByText('Bravo')).not.toBeInTheDocument()
        unmount()
      }
    })

    it('SOURCES_DISAGREE says the two sources name different champions and keeps the season result', () => {
      render(<TrophyCaseTable data={tc(season({ runnerUp: null, runnerUpReason: 'SOURCES_DISAGREE' }))} />)
      expect(
        screen.getByText('Sleeper’s bracket and its season result name different champions; the season result is shown.'),
      ).toBeInTheDocument()
      expect(screen.getByText('Alpha')).toBeInTheDocument()
    })

    it('no champion means "No champion reported." and no runner-up cell content', () => {
      render(<TrophyCaseTable data={tc(season({ champion: null, runnerUp: null, runnerUpReason: null }))} />)
      expect(screen.getByText('No champion reported.')).toBeInTheDocument()
      expect(screen.queryByText(/^Not available — the playoff/)).not.toBeInTheDocument()
    })
  })

  describe('last place reasons', () => {
    it('every reason is shown as "not reported" and names nobody', () => {
      const reasons = Object.keys(LAST_PLACE_TEXT) as TrophyLastPlaceReason[]
      expect(reasons).toHaveLength(7)
      for (const reason of reasons) {
        const { unmount } = render(
          <TrophyCaseTable data={tc(season({ lastPlace: null, lastPlaceReason: reason, lastPlaceBracket: null }))} />,
        )
        expect(screen.getByText(LAST_PLACE_TEXT[reason])).toBeInTheDocument()
        expect(LAST_PLACE_TEXT[reason]).toMatch(/^Not reported/)
        expect(screen.queryByText('Delta')).not.toBeInTheDocument()
        unmount()
      }
    })
  })

  it('a disputed final prints the Rivalries page’s sentence, naming the champion', () => {
    render(<TrophyCaseTable data={tc(season({ finalDisputed: true, season: 2024 }))} />)
    expect(screen.getByText(finalDisagreeSentence(2024, 'Alpha'))).toBeInTheDocument()
    expect(finalDisagreeSentence(2024, 'Alpha')).toBe(
      '2024: Sleeper\'s bracket gives the final to Alpha; the stored scores say otherwise.',
    )
  })

  it('no disputed sentence when the final is not disputed', () => {
    render(<TrophyCaseTable data={tc(season())} />)
    expect(screen.queryByText(/the stored scores say otherwise/)).not.toBeInTheDocument()
  })

  it('shows seasons in the order given (newest first from the server)', () => {
    render(
      <TrophyCaseTable
        data={tc(season({ season: 2025, sleeperLeagueId: 'a' }), season({ season: 2024, sleeperLeagueId: 'b' }))}
      />,
    )
    const heads = screen.getAllByRole('rowheader').map((h) => h.querySelector('.hof-year')?.textContent)
    expect(heads).toEqual(['2025', '2024'])
  })

  it('NO_COMPLETED_SEASON says so instead of drawing an empty table', () => {
    render(<TrophyCaseTable data={{ sport: 'nfl', status: 'NO_COMPLETED_SEASON', seasons: [] }} />)
    expect(screen.getByText('No season has finished yet.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })
})

describe('the Hall of fame section', () => {
  it('is hidden when the fetch returns null (a 404)', async () => {
    getTrophyCase.mockResolvedValue(null)
    const { container } = render(<TrophyCase sleeperLeagueId="L1" />)
    await waitFor(() => expect(getTrophyCase).toHaveBeenCalledWith('L1'))
    // let the resolved promise settle, then confirm nothing was drawn
    await new Promise((r) => setTimeout(r, 0))
    expect(screen.queryByText('Hall of fame')).not.toBeInTheDocument()
    expect(container).toBeEmptyDOMElement()
  })

  it('renders the section heading and the table when the fetch resolves', async () => {
    getTrophyCase.mockResolvedValue(tc(season()))
    render(<TrophyCase sleeperLeagueId="L1" />)
    expect(await screen.findByRole('heading', { name: 'Hall of fame' })).toBeInTheDocument()
    expect(screen.getByText('Alpha')).toBeInTheDocument()
  })

  it('a non-404 failure says so in one line rather than vanishing', async () => {
    getTrophyCase.mockRejectedValue(new Error('boom'))
    render(<TrophyCase sleeperLeagueId="L1" />)
    expect(await screen.findByText('Couldn’t load the hall of fame.')).toBeInTheDocument()
  })
})

describe('past award winners (US4)', () => {
  const award = (over: Partial<Superlative> = {}): Superlative => ({
    kind: 'HIGHEST_WEEK', available: true, reason: null, early: false, value: 180.5, unit: 'POINTS',
    holders: [{ rosterId: 1, managerId: 10, teamName: 'Award Winners FC', username: null, avatarId: null }],
    emptyReason: null,
    detail: [{ type: 'WEEK_SCORE', week: 3, rosterId: 1, points: 180.5 }],
    coverage: null, playerHolders: [], standings: [], playerStandings: [], playerContributions: [],
    ...over,
  })
  const response = (over: Partial<SuperlativesResponse> = {}): SuperlativesResponse => ({
    available: true, season: 2025, requestedSeason: null, sport: 'nfl', throughWeek: 17, weeksScored: 17,
    regularSeasonEnd: 14, early: false, earlyThresholdWeeks: 4, closeGameMargin: 10, suspensionWeeksObserved: [],
    commissionerListAvailable: false, superlatives: [award(), award({ kind: 'LOWEST_WEEK', available: false, reason: 'not built yet', holders: [] })],
    leagueSleeperId: 'S2025',
    ...over,
  })
  const open = async () => {
    render(<TrophyCaseTable data={tc(season())} />)
    await userEvent.click(screen.getByRole('button', { name: 'Awards' }))
  }

  it('fetches nothing until a row is opened, then asks for that row’s own league id', async () => {
    fetchSuperlatives.mockResolvedValue(response())
    render(<TrophyCaseTable data={tc(season())} />)
    expect(fetchSuperlatives).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Awards' }))
    expect(fetchSuperlatives).toHaveBeenCalledWith('S2025')
  })

  it('renders each kind’s title and winners with the Superlatives page’s own rendering, and links to that page', async () => {
    fetchSuperlatives.mockResolvedValue(response())
    await open()
    expect(await screen.findByText('Highest week')).toBeInTheDocument()
    expect(screen.getByText('Award Winners FC')).toBeInTheDocument()
    expect(screen.getByText('180.50 points · week 3')).toBeInTheDocument()
    // a kind that is unavailable still says why
    expect(screen.getByText('not built yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /2025’s awards page/ })).toHaveAttribute('href', '/leagues/S2025/superlatives')
  })

  it('caches the response per row: closing and reopening does not ask again', async () => {
    fetchSuperlatives.mockResolvedValue(response())
    await open()
    await screen.findByText('Highest week')
    await userEvent.click(screen.getByRole('button', { name: 'Awards' }))
    expect(screen.queryByText('Highest week')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Awards' }))
    expect(await screen.findByText('Highest week')).toBeInTheDocument()
    expect(fetchSuperlatives).toHaveBeenCalledTimes(1)
  })

  it('a response for another season (requestedSeason set) shows "not available" and none of its awards', async () => {
    fetchSuperlatives.mockResolvedValue(response({ requestedSeason: 2026, season: 2025, leagueSleeperId: 'S2025' }))
    await open()
    expect(await screen.findByText('Awards aren’t available for this season.')).toBeInTheDocument()
    expect(screen.queryByText('Highest week')).not.toBeInTheDocument()
    expect(screen.queryByText('Award Winners FC')).not.toBeInTheDocument()
  })

  it('a response whose league id is not the row’s shows "not available" and none of its awards', async () => {
    fetchSuperlatives.mockResolvedValue(response({ leagueSleeperId: 'S2024' }))
    await open()
    expect(await screen.findByText('Awards aren’t available for this season.')).toBeInTheDocument()
    expect(screen.queryByText('Award Winners FC')).not.toBeInTheDocument()
  })

  it('a response-level unavailable prints the response’s reason', async () => {
    fetchSuperlatives.mockResolvedValue(response({ available: false, reason: 'no week of this season has been scored', superlatives: [] }))
    await open()
    expect(await screen.findByText('no week of this season has been scored')).toBeInTheDocument()
  })

  it('a failed fetch says so', async () => {
    fetchSuperlatives.mockRejectedValue(new Error('boom'))
    await open()
    expect(await screen.findByText('Couldn’t load this season’s awards.')).toBeInTheDocument()
  })

  it('does not cache a failure: reopening the row asks again (code review N3)', async () => {
    fetchSuperlatives.mockRejectedValueOnce(new Error('boom')).mockResolvedValue(response())
    await open()
    expect(await screen.findByText('Couldn’t load this season’s awards.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Awards' }))
    await userEvent.click(screen.getByRole('button', { name: 'Awards' }))
    expect(await screen.findByText('Highest week')).toBeInTheDocument()
    expect(fetchSuperlatives).toHaveBeenCalledTimes(2)
  })
})
