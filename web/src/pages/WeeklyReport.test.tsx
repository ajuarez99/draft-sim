import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WeeklyReport from './WeeklyReport'
import type { WeeklyReport as Data } from '../api'
// Source text, the way destinations.test.ts reads App.tsx: the assertion is
// about what the file says, so it has to read the file.
import pageSource from './WeeklyReport.tsx?raw'
import { invalidateRailLeagues } from '../railLeague'

/** Real router, so the `?week=` round-trip is the real thing rather than a mock of it. */
let lastSearch = ''
function LocationProbe() {
  lastSearch = useLocation().search
  return null
}
function renderPage(url = '/leagues/L1/weekly-report') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <LocationProbe />
      <Routes>
        <Route path="/leagues/:sleeperLeagueId/weekly-report" element={<WeeklyReport />} />
      </Routes>
    </MemoryRouter>,
  )
}

const getWeeklyReport = vi.fn()
const fetchRecap = vi.fn()
const getDrafts = vi.fn()
const fetchLockIns = vi.fn()
vi.mock('../api', () => ({
  getDrafts: (...args: unknown[]) => getDrafts(...args),
  getWeeklyReport: (...args: unknown[]) => getWeeklyReport(...args),
  fetchRecap: (...args: unknown[]) => fetchRecap(...args),
  fetchLockIns: (...args: unknown[]) => fetchLockIns(...args),
}))

const recapOff = {
  state: 'FEATURE_OFF', season: null, week: 1, model: null, generatedAt: null, revision: null,
  revisionReason: null, stale: false, headline: null, sections: null, failureReason: null,
}

// Every test starts with the recap switched off, which is what main's page effectively had.
beforeEach(() => {
  invalidateRailLeagues()
  getDrafts.mockReset()
  fetchRecap.mockReset()
  fetchLockIns.mockReset()
  fetchLockIns.mockResolvedValue({ status: 'NOT_NBA', locks: [], live: null })
  fetchRecap.mockResolvedValue(recapOff)
})

function data(over: Partial<Data> = {}): Data {
  return {
    available: true,
    season: 2026,
    week: 1,
    latestScoredWeek: 1,
    latestFinalWeek: 1,
    weekFinal: true,
    sport: 'nfl',
    playersPlayMultiplePerPeriod: false,
    matchups: [
      {
        home: { rosterId: 1, teamName: 'Dart has hit anotha Bower', username: null, avatarId: null, record: '1-0', points: 157.4 },
        away: { rosterId: 2, teamName: 'Justice for Wags', username: null, avatarId: null, record: '0-1', points: 147.9 },
      },
    ],
    topPerformers: [
      { playerId: '1', playerName: 'Caleb Williams', position: 'QB', teamName: 'Prayer Circle', points: 37.26, team: 'CHI', opponent: null, isAway: null, avatarId: null },
    ],
    awards: [
      {
        kind: 'SELF_INFLICTED_WOUND',
        teamName: 'Justice for Wags',
        detail: 'Deebo Samuel outscored Carnell Tate by 10.20. Justice for Wags lost by 9.50.',
      },
    ],
    awardsOmitted: [],
    live: false,
    liveWeek: null,
    sleeperLeagueId: 'L1',
    ...over,
  }
}

describe('Weekly report', () => {
  beforeEach(() => getWeeklyReport.mockReset())

  /** US5.1: both teams, both records, both final scores. */
  it('shows every matchup with records and final scores', async () => {
    getWeeklyReport.mockResolvedValue(data())
    renderPage()

    // Scoped to the matchups panel: a team that also won an award appears
    // twice on this page, which is realistic rather than a bug.
    const heading = await screen.findByRole('heading', { name: /week [0-9]+ matchups/i })
    const panel = heading.closest('section') as HTMLElement

    expect(within(panel).getByText('Dart has hit anotha Bower')).toBeInTheDocument()
    expect(within(panel).getByText('Justice for Wags')).toBeInTheDocument()
    expect(within(panel).getByText('(1-0)')).toBeInTheDocument()
    expect(within(panel).getByText('(0-1)')).toBeInTheDocument()
    expect(within(panel).getByText('157.40')).toBeInTheDocument()
    expect(within(panel).getByText('147.90')).toBeInTheDocument()
  })

  /** US5.2: ranked by points actually scored, with the owning team named. */
  it('lists top performers with their team', async () => {
    getWeeklyReport.mockResolvedValue(data())
    renderPage()

    expect(await screen.findByText('Caleb Williams')).toBeInTheDocument()
    expect(screen.getByText('Prayer Circle')).toBeInTheDocument()
    expect(screen.getByText('37.26')).toBeInTheDocument()
  })

  /** US5.3: the award names the specific players involved. */
  it('renders an award with a readable title and its detail', async () => {
    getWeeklyReport.mockResolvedValue(data())
    renderPage()

    expect(await screen.findByText('Self-inflicted wound')).toBeInTheDocument()
    expect(screen.getByText(/Deebo Samuel outscored Carnell Tate by 10.20/)).toBeInTheDocument()
  })

  /**
   * US5.4. The honesty case: an award that could not be computed says so. A
   * missing award is otherwise indistinguishable from nobody qualifying.
   */
  it('explains an award it could not work out', async () => {
    getWeeklyReport.mockResolvedValue(
      data({
        awards: [],
        awardsOmitted: [{ kind: 'SELF_INFLICTED_WOUND', reason: 'STARTERS_NOT_STORED' }],
      }),
    )
    renderPage()

    expect(await screen.findByText(/could not be worked out for this week/)).toBeInTheDocument()
    expect(screen.getByText(/naming the swap would be a guess/)).toBeInTheDocument()
  })

  it('says a week has not been scored rather than showing an empty board', async () => {
    getWeeklyReport.mockResolvedValue(
      data({
        available: false,
        reason: 'week 9 has not been scored for this league',
        week: 9,
        latestScoredWeek: 2,
        latestFinalWeek: 2,
        matchups: [],
        topPerformers: [],
        awards: [],
      }),
    )
    renderPage()

    expect(await screen.findByText(/Week 9 has not been scored/)).toBeInTheDocument()
  })

  /** US5.5: basketball renders the same shapes. */
  it('renders a basketball week', async () => {
    getWeeklyReport.mockResolvedValue(
      data({
        sport: 'nba',
        matchups: [
          {
            home: { rosterId: 1, teamName: 'Hoop Dreams', username: null, avatarId: null, record: '1-0', points: 228 },
            away: { rosterId: 2, teamName: 'Rim Reapers', username: null, avatarId: null, record: '0-1', points: 211 },
          },
        ],
      }),
    )
    renderPage()

    expect(await screen.findByText('Hoop Dreams')).toBeInTheDocument()
    expect(screen.getByText('228.00')).toBeInTheDocument()
  })
})

/*
 * claude/audit-2026-09-28/10: the page opens on the latest scored week, keeps
 * the pick in the URL, and cannot be pushed past what has been scored.
 * 013 US5: the numeric input became a ‹ Week N › stepper.
 */
describe('Weekly report week selection', () => {
  beforeEach(() => {
    getWeeklyReport.mockReset()
    lastSearch = ''
  })

  const weekLabel = async () => (await screen.findByText(/^Week \d+$/, { selector: '.wr-stepper-label' })).textContent
  const prev = () => screen.findByRole('button', { name: 'Previous week' }) as Promise<HTMLButtonElement>
  const next = () => screen.findByRole('button', { name: 'Next week' }) as Promise<HTMLButtonElement>

  it('opens on the latest scored week when the URL names none', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 2, latestScoredWeek: 2, latestFinalWeek: 2 }))
    renderPage()

    expect(await weekLabel()).toBe('Week 2')
    // 0 is the "latest" request; the page never asks for week 1 by default.
    expect(getWeeklyReport).toHaveBeenCalledWith('L1', 0)
    expect(await screen.findByText('Week 2 matchups')).toBeInTheDocument()
  })

  it('reads the week from ?week= and asks for exactly that week', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 1, latestScoredWeek: 3 }))
    renderPage('/leagues/L1/weekly-report?week=1')

    expect(await weekLabel()).toBe('Week 1')
    expect(getWeeklyReport).toHaveBeenCalledWith('L1', 1)
  })

  it('writes the chosen week back to ?week= when stepping', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 3, latestScoredWeek: 3 }))
    renderPage()

    fireEvent.click(await prev())

    await waitFor(() => expect(lastSearch).toBe('?week=2'))
    await waitFor(() => expect(getWeeklyReport).toHaveBeenLastCalledWith('L1', 2))
  })

  it('disables the stepper at week 1 and at the latest scored week, with no hardcoded 18', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 3, latestScoredWeek: 3 }))
    renderPage()
    expect((await next()).disabled).toBe(true)
    expect((await prev()).disabled).toBe(false)
  })

  it('disables Previous at week 1', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 1, latestScoredWeek: 3 }))
    renderPage('/leagues/L1/weekly-report?week=1')
    expect((await prev()).disabled).toBe(true)
    expect((await next()).disabled).toBe(false)
  })

  it('steps forward to the next week, which can be the in-progress one', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 2, latestScoredWeek: 3, latestFinalWeek: 2 }))
    renderPage()

    fireEvent.click(await next())
    await waitFor(() => expect(lastSearch).toBe('?week=3'))
  })

  it('pulls a link past the last scored week back to it', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 99, latestScoredWeek: 2, available: false, matchups: [] }))
    renderPage('/leagues/L1/weekly-report?week=99')

    await waitFor(() => expect(lastSearch).toBe('?week=2'))
    await waitFor(() => expect(getWeeklyReport).toHaveBeenLastCalledWith('L1', 2))
  })

  it('ignores a ?week= that is not a whole week and falls back to the latest', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 2, latestScoredWeek: 2, latestFinalWeek: 2 }))
    renderPage('/leagues/L1/weekly-report?week=abc')

    expect(await weekLabel()).toBe('Week 2')
    expect(getWeeklyReport).toHaveBeenCalledWith('L1', 0)
  })

  /** T052: the default is the newest FINAL week, and a later scored week is offered, not hidden. */
  it('labels the default week "latest final week" and links to the in-progress one', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 2, latestScoredWeek: 3, latestFinalWeek: 2, weekFinal: true }))
    renderPage()

    expect(await screen.findByText('Week 2 · latest final week')).toBeInTheDocument()
    const link = screen.getByRole('link', { name: /Week 3 in progress/ })
    expect(link.getAttribute('href')).toMatch(/[?]week=3$/)
    // The week shown is final, so the in-progress caveat is not on it.
    expect(screen.queryByText(/scores can still change/)).not.toBeInTheDocument()
    expect((await next()).disabled).toBe(false)
  })

  it('does not call a chosen week the "latest final week"', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 2, latestScoredWeek: 3, latestFinalWeek: 2, weekFinal: true }))
    renderPage('/leagues/L1/weekly-report?week=2')

    await weekLabel()
    expect(screen.queryByText(/latest final week/)).not.toBeInTheDocument()
  })

  it('labels an in-progress week, whether chosen on purpose or the fallback when nothing is final', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 1, latestScoredWeek: 1, latestFinalWeek: 0, weekFinal: false }))
    renderPage()

    expect(await screen.findByText(/scores can still change/)).toBeInTheDocument()
    expect(screen.getByText('Week 1 matchups')).toBeInTheDocument()
    expect(screen.queryByText(/latest final week/)).not.toBeInTheDocument()
    expect((await next()).disabled).toBe(true)
  })

  it('shows a plain "nothing scored" panel, and no stepper, before any week is scored', async () => {
    getWeeklyReport.mockResolvedValue(
      data({
        available: false,
        reason: 'no week has been scored for this league yet',
        week: 0,
        latestScoredWeek: 0,
        latestFinalWeek: 0,
        weekFinal: false,
        matchups: [],
        topPerformers: [],
        awards: [],
      }),
    )
    renderPage()

    expect(await screen.findByText('No week has been scored yet')).toBeInTheDocument()
    expect(screen.queryByText(/Week 0/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next week' })).not.toBeInTheDocument()
  })
})

/*
 * 013 US5 (T050): the reader's own matchup leads, full width, with the result
 * in words. Without an isMe side the page keeps its original order.
 */
describe('Weekly report: your matchup first', () => {
  beforeEach(() => getWeeklyReport.mockReset())

  const side = (rosterId: number, teamName: string, points: number, isMe?: boolean) => ({
    rosterId,
    teamName,
    username: null,
    avatarId: null,
    record: '1-0',
    points,
    ...(isMe === undefined ? {} : { isMe }),
  })
  const twoGames = (meIsAway: boolean, mePoints = 147.9, themPoints = 157.4) => [
    { home: side(3, 'Other Home', 100), away: side(4, 'Other Away', 90) },
    meIsAway
      ? { home: side(1, 'Rival', themPoints), away: side(2, 'My Team', mePoints, true) }
      : { home: side(2, 'My Team', mePoints, true), away: side(1, 'Rival', themPoints) },
  ]

  it('puts your matchup first as a scoreboard, with the word Lost', async () => {
    getWeeklyReport.mockResolvedValue(data({ matchups: twoGames(true) }))
    renderPage()

    const board = await screen.findByLabelText('Your matchup')
    expect(within(board).getByText('My Team')).toBeInTheDocument()
    expect(within(board).getByText('Rival')).toBeInTheDocument()
    expect(within(board).getByText('147.90')).toBeInTheDocument()
    expect(within(board).getByText('157.40')).toBeInTheDocument()
    const result = within(board).getByText('Lost')
    expect(result).toHaveClass('lost')
    // The scoreboard comes before the other matchup in document order.
    const other = screen.getByText('Other Home')
    expect(board.compareDocumentPosition(other) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('says Won in words when you outscored them, even when you are the home side', async () => {
    getWeeklyReport.mockResolvedValue(data({ matchups: twoGames(false, 160, 150) }))
    renderPage()

    const board = await screen.findByLabelText('Your matchup')
    expect(within(board).getByText('Won')).toHaveClass('won')
    expect(within(board).getByText(/by 10\.00/)).toBeInTheDocument()
  })

  it('says Tied, with no margin, on equal scores', async () => {
    getWeeklyReport.mockResolvedValue(data({ matchups: twoGames(false, 150, 150) }))
    renderPage()

    const board = await screen.findByLabelText('Your matchup')
    expect(within(board).getByText('Tied')).toBeInTheDocument()
    expect(within(board).queryByText(/ by /)).not.toBeInTheDocument()
  })

  it('merges awards and top performers into one "Week in review" list', async () => {
    getWeeklyReport.mockResolvedValue(data({ matchups: twoGames(true) }))
    renderPage()

    const heading = await screen.findByRole('heading', { name: 'Week in review' })
    const section = heading.closest('section') as HTMLElement
    expect(within(section).getByText('Self-inflicted wound')).toBeInTheDocument()
    expect(within(section).getByText('Caleb Williams')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Weekly awards' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Top performers' })).not.toBeInTheDocument()
  })

  it('keeps today\'s order and sections when no side is yours', async () => {
    getWeeklyReport.mockResolvedValue(data())
    renderPage()

    const matchups = await screen.findByRole('heading', { name: /week 1 matchups/i })
    const awards = screen.getByRole('heading', { name: 'Weekly awards' })
    const performers = screen.getByRole('heading', { name: 'Top performers' })
    expect(screen.queryByLabelText('Your matchup')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Week in review' })).not.toBeInTheDocument()
    const follows = (a: Element, b: Element) => !!(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)
    expect(follows(matchups, awards)).toBe(true)
    expect(follows(awards, performers)).toBe(true)
  })
})

/*
 * specs/005-daily-weekly-top-players: the pair, and football's right to be
 * left alone.
 */
describe('Best nights and Best week', () => {
  beforeEach(() => getWeeklyReport.mockReset())

  const nba = (over: Partial<Data> = {}): Data =>
    data({
      sport: 'nba',
      playersPlayMultiplePerPeriod: true,
      topPerformers: undefined,
      basis: 'ALL_GAMES_PLAYED',
      sectionsUnavailable: [],
      bestNights: [
        {
          playerId: '1658',
          playerName: 'Nikola Jokić',
          position: 'C',
          teamName: 'FentMachines5:SoFkingOver',
          points: 58.5,
          date: '2025-11-17',
          opponent: 'CHI',
          isAway: false,
        },
      ],
      bestWeek: [
        {
          playerId: '1658',
          playerName: 'Nikola Jokić',
          position: 'C',
          teamName: 'FentMachines5:SoFkingOver',
          totalPoints: 182.0,
          gamesPlayed: 4,
        },
      ],
      ...over,
    })

  /** US1: the night and the opponent are the whole point of this section. */
  it('names the night and the opponent for a best-night performance', async () => {
    getWeeklyReport.mockResolvedValue(nba())
    renderPage()

    const section = (await screen.findByRole('heading', { name: /best nights/i })).closest('section')!
    expect(within(section).getByText('58.50')).toBeInTheDocument()
    expect(within(section).getByText(/Nov 17 vs CHI/)).toBeInTheDocument()
  })

  /** An away game reads "@", not "vs". */
  it('distinguishes an away game from a home one', async () => {
    getWeeklyReport.mockResolvedValue(
      nba({
        bestNights: [
          { ...nba().bestNights![0], isAway: true },
        ],
      }),
    )
    renderPage()
    expect(await screen.findByText(/Nov 17 @ CHI/)).toBeInTheDocument()
  })

  /** FR-006: an unknown opponent shows the night alone rather than a guess. */
  it('shows the night alone when the opponent is unknown', async () => {
    getWeeklyReport.mockResolvedValue(
      nba({ bestNights: [{ ...nba().bestNights![0], opponent: null, isAway: null }] }),
    )
    renderPage()

    expect(await screen.findByText('Nov 17')).toBeInTheDocument()
    expect(screen.queryByText(/vs null|@ null|undefined/)).not.toBeInTheDocument()
  })

  /** US2: the total never appears without the number of games it covers. */
  it('prints the games a week total covers beside the total', async () => {
    getWeeklyReport.mockResolvedValue(nba())
    renderPage()

    const section = (await screen.findByRole('heading', { name: /best week/i })).closest('section')!
    expect(within(section).getByText('182.00')).toBeInTheDocument()
    expect(within(section).getByText('4 games')).toBeInTheDocument()
  })

  /** FR-005: the big number must say what it is not. */
  it('states that week totals are not the points that decided a matchup', async () => {
    getWeeklyReport.mockResolvedValue(nba())
    renderPage()
    expect(await screen.findByText(/not the points that decided a matchup/i)).toBeInTheDocument()
  })

  /** FR-006: a missing ranking says why rather than rendering empty. */
  it('explains a ranking it could not build', async () => {
    getWeeklyReport.mockResolvedValue(
      nba({
        bestNights: [],
        bestWeek: [],
        sectionsUnavailable: [
          { section: 'BEST_NIGHTS', reason: 'PER_GAME_DETAIL_MISSING' },
          { section: 'BEST_WEEK', reason: 'PER_GAME_DETAIL_MISSING' },
        ],
      }),
    )
    renderPage()

    // Both rankings say it, so this is findAll: the count IS the assertion.
    const notes = await screen.findAllByText(/Game-by-game detail has not been stored/i)
    expect(notes).toHaveLength(2)
    expect(notes[0]).toHaveTextContent(/It is not that nobody played/i)
  })

  /** SC-004: football keeps the list it has always had, and gains nothing. */
  it('leaves football showing Top performers and neither new section', async () => {
    getWeeklyReport.mockResolvedValue(data())
    renderPage()

    expect(await screen.findByRole('heading', { name: /top performers/i })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /best nights/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /best week/i })).not.toBeInTheDocument()
  })

  // specs/022 T033: the player's name opens his page on basketball, in both rankings.
  it('links basketball player names in both rankings to the player page', async () => {
    getWeeklyReport.mockResolvedValue(nba())
    renderPage()

    const links = await screen.findAllByRole('link', { name: 'Nikola Jokić' })
    expect(links).toHaveLength(2)
    for (const l of links) expect(l).toHaveAttribute('href', '/leagues/L1/players/1658')
  })

  it('leaves football top performers unlinked', async () => {
    getWeeklyReport.mockResolvedValue(data())
    renderPage()

    expect(await screen.findByText('Caleb Williams')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Caleb Williams' })).not.toBeInTheDocument()
  })

  /** And the converse: basketball loses the single list rather than gaining a third ranking. */
  it('replaces Top performers with the pair for basketball', async () => {
    getWeeklyReport.mockResolvedValue(nba())
    renderPage()

    expect(await screen.findByRole('heading', { name: /best nights/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /best week/i })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /top performers/i })).not.toBeInTheDocument()
  })
})

/*
 * FR-004, the frontend half of T050. The page must branch on the flag the
 * server sends, not on the sport it happens to be looking at.
 *
 * Grepping source from a test is ugly; three shipped instances of "a rule
 * expressed as a sport check" in this repo are uglier, and a one-line hotfix
 * that adds `sport === 'nba'` here would otherwise pass every other test.
 */
describe('the weekly report page decides by rule, not by sport name', () => {
  it('contains no sport literal outside comments', () => {
    const offending = pageSource
      .split(/\r?\n/)
      .map((line, i) => [i + 1, line] as const)
      .filter(([, line]) => {
        const t = line.trim()
        if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) return false
        return /'(nba|nfl)'|"(nba|nfl)"/i.test(line)
      })
      .map(([n, line]) => `${n}: ${line.trim()}`)

    expect(
      offending,
      `WeeklyReport.tsx must branch on playersPlayMultiplePerPeriod, not a sport name: ${offending.join(' | ')}`,
    ).toEqual([])
  })

  it('branches on the cadence flag', () => {
    expect(pageSource).toContain('playersPlayMultiplePerPeriod')
  })
})

describe('Weekly report eyebrow names the season shown', () => {
  it('reads League · <season> from the payload', async () => {
    getWeeklyReport.mockResolvedValue(data({ season: 2025 }))
    renderPage()
    expect(await screen.findByText('League · 2025')).toBeInTheDocument()
  })

  it('uses the resolved season, not the requested one, when the server fell back', async () => {
    getWeeklyReport.mockResolvedValue(data({ season: 2025, requestedSeason: 2026 }))
    renderPage()
    expect(await screen.findByText('League · 2025')).toBeInTheDocument()
    expect(screen.queryByText('League · 2026')).not.toBeInTheDocument()
  })

  it('is plain League while there is no payload yet', () => {
    getWeeklyReport.mockReturnValue(new Promise(() => {}))
    renderPage()
    expect(screen.getByText('League')).toBeInTheDocument()
  })
})

/**
 * specs/020 SC-002: with the recap feature off the page is exactly what it was before the card
 * existed. The card asks the server once the week has been on screen for a second, gets
 * FEATURE_OFF, and must add nothing to the DOM, before or after.
 */
describe('Weekly report with the recap feature off', () => {
  it('renders no recap markup, before or after the card asks', async () => {
    getWeeklyReport.mockResolvedValue(data())
    const { container } = renderPage()
    await screen.findByRole('heading', { name: /week [0-9]+ matchups/i })
    const before = container.innerHTML

    // Real timers: the card's 1 s on-screen delay was scheduled when the page rendered.
    await waitFor(() => expect(fetchRecap).toHaveBeenCalledTimes(1), { timeout: 3000 })
    await act(async () => { await Promise.resolve() })

    expect(container.innerHTML).toBe(before)
    expect(container.querySelector('.recap-card')).toBeNull()
    expect(container.innerHTML.toLowerCase()).not.toContain('recap')
  })
})

/** Two seasons of one league: L0 is 2025, L1 (the route's id) is 2026. */
const lineageDrafts = [
  { sleeperLeagueId: 'L1', sleeperDraftId: 'd1', season: 2026, previousLeagueId: 'L0', leagueName: 'BK', sport: 'nba' },
  { sleeperLeagueId: 'L0', sleeperDraftId: 'd0', season: 2025, previousLeagueId: null, leagueName: 'BK', sport: 'nba' },
]

describe('Weekly report player links follow the season the data came from', () => {
  beforeEach(() => getWeeklyReport.mockReset())
  const nbaData = (over: Partial<Data>) =>
    data({
      sport: 'nba',
      playersPlayMultiplePerPeriod: true,
      topPerformers: undefined,
      basis: 'ALL_GAMES_PLAYED',
      sectionsUnavailable: [],
      bestNights: [
        { playerId: '1658', playerName: 'Nikola Jokić', position: 'C', teamName: 'T', points: 58.5, date: '2025-11-17', opponent: 'CHI', isAway: false },
      ],
      bestWeek: [
        { playerId: '1658', playerName: 'Nikola Jokić', position: 'C', teamName: 'T', totalPoints: 182.0, gamesPlayed: 4 },
      ],
      ...over,
    })

  it('links a fallback-season row to the league id of that season, not the route one', async () => {
    getDrafts.mockResolvedValue(lineageDrafts)
    getWeeklyReport.mockResolvedValue(nbaData({ season: 2025, requestedSeason: 2026 }))
    renderPage()

    const links = await screen.findAllByRole('link', { name: 'Nikola Jokić' })
    await waitFor(() => {
      for (const l of links) expect(l).toHaveAttribute('href', '/leagues/L0/players/1658')
    })
  })

  it('keeps the route league id when the data is the route own season', async () => {
    getDrafts.mockResolvedValue(lineageDrafts)
    getWeeklyReport.mockResolvedValue(nbaData({ season: 2026 }))
    renderPage()

    const links = await screen.findAllByRole('link', { name: 'Nikola Jokić' })
    for (const l of links) expect(l).toHaveAttribute('href', '/leagues/L1/players/1658')
  })
})

/*
 * Spec 045 (T020): a week that is being played and not stored yet, read live from Sleeper. Sleeper does not let
 * the current week be stored until it ends, so on opening week the server answers 2026 live instead of
 * walking back to 2025.
 */
describe('Weekly report: the live week', () => {
  beforeEach(() => {
    getWeeklyReport.mockReset()
    lastSearch = ''
  })

  const nbaSides = {
    home: { rosterId: 1, teamName: 'Hoop Dreams', username: null, avatarId: null, record: '0-0', points: 201.5, isMe: false },
    away: { rosterId: 7, teamName: 'Rim Reapers', username: null, avatarId: null, record: '0-0', points: 192.5, isMe: false },
  }
  const liveOpening = (over: Partial<Data> = {}) =>
    data({
      sport: 'nba',
      playersPlayMultiplePerPeriod: true,
      week: 1,
      latestScoredWeek: 0,
      latestFinalWeek: 0,
      weekFinal: false,
      live: true,
      liveWeek: 1,
      sleeperLeagueId: 'L1',
      matchups: [nbaSides],
      topPerformers: undefined,
      bestNights: [],
      bestWeek: [],
      basis: 'ALL_GAMES_PLAYED',
      sectionsUnavailable: [],
      awards: [],
      awardsOmitted: [],
      ...over,
    })

  it('opening week: labelled live, scores shown, no rankings, awards or fallback note', async () => {
    getWeeklyReport.mockResolvedValue(liveOpening())
    renderPage()

    expect(await screen.findByText(/In progress, live from Sleeper/)).toBeInTheDocument()
    expect(screen.getByText('Hoop Dreams')).toBeInTheDocument()
    expect(screen.getByText('201.50')).toBeInTheDocument()
    expect(screen.getByText('Rankings and awards come once week 1 is final.')).toBeInTheDocument()
    // not the empty ranking sections, and not a "nobody qualified" claim
    expect(screen.queryByText('Best nights')).not.toBeInTheDocument()
    expect(screen.queryByText('Best week')).not.toBeInTheDocument()
    expect(screen.queryByText(/Nobody qualified/)).not.toBeInTheDocument()
    expect(screen.queryByText('Weekly awards')).not.toBeInTheDocument()
    expect(screen.queryByText(/showing|Showing/)).not.toBeInTheDocument()
    // the stepper reaches the live week even though nothing is stored
    expect(await screen.findByText('Week 1', { selector: '.wr-stepper-label' })).toBeInTheDocument()
    expect(fetchRecap).not.toHaveBeenCalled()
  })

  it('mid-season: the in-progress link goes to the live week and is not clamped back to the stored one', async () => {
    const finalWeek3 = data({ week: 3, latestScoredWeek: 3, latestFinalWeek: 3, weekFinal: true, liveWeek: 4 })
    getWeeklyReport.mockImplementation((_id: string, w: number) =>
      Promise.resolve(
        w === 4
          ? liveOpening({ week: 4, latestScoredWeek: 3, latestFinalWeek: 3, liveWeek: 4 })
          : finalWeek3,
      ),
    )
    renderPage()

    const link = await screen.findByRole('link', { name: /Week 4 in progress/ })
    expect(link.getAttribute('href')).toMatch(/[?]week=4$/)
    fireEvent.click(link)

    await waitFor(() => expect(getWeeklyReport).toHaveBeenLastCalledWith('L1', 4))
    expect(await screen.findByText(/In progress, live from Sleeper/)).toBeInTheDocument()
    // give the clamp effect every chance to fire; it must leave week 4 alone
    await waitFor(() => expect(lastSearch).toBe('?week=4'))
    expect((await screen.findByRole('button', { name: 'Next week' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('a bookmark to the live week is not pulled back to the last stored week', async () => {
    getWeeklyReport.mockResolvedValue(liveOpening({ week: 4, latestScoredWeek: 3, latestFinalWeek: 3, liveWeek: 4 }))
    renderPage('/leagues/L1/weekly-report?week=4')

    expect(await screen.findByText(/In progress, live from Sleeper/)).toBeInTheDocument()
    expect(lastSearch).toBe('?week=4')
  })

  it('a failed live read shows its reason, not last season', async () => {
    getWeeklyReport.mockResolvedValue(
      data({
        available: false,
        reason: 'live scores unavailable from Sleeper right now',
        week: 1,
        latestScoredWeek: 0,
        latestFinalWeek: 0,
        weekFinal: false,
        matchups: [],
        topPerformers: [],
        awards: [],
      }),
    )
    renderPage()

    expect(await screen.findByText('live scores unavailable from Sleeper right now')).toBeInTheDocument()
    expect(screen.queryByText(/In progress, live/)).not.toBeInTheDocument()
  })

  // ---- code review B1: a week that is not final never declares a winner ------------------------------

  it('B1: a live scoreboard says Leading, with no "Won by" and no winner highlight', async () => {
    getWeeklyReport.mockResolvedValue(
      liveOpening({ matchups: [{ home: { ...nbaSides.home, isMe: true, points: 120.5 }, away: { ...nbaSides.away, points: 99 } }] }),
    )
    const { container } = renderPage()

    const board = await screen.findByLabelText('Your matchup')
    expect(within(board).getByText('Leading')).toBeInTheDocument()
    expect(screen.queryByText(/Won/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Lost/)).not.toBeInTheDocument()
    expect(container.querySelector('.wr-won')).toBeNull()
  })

  it('B1: a live game card (not yours) has no winner highlight', async () => {
    getWeeklyReport.mockResolvedValue(liveOpening())
    const { container } = renderPage()

    expect(await screen.findByText('Hoop Dreams')).toBeInTheDocument()
    expect(container.querySelector('.wr-won')).toBeNull()
  })

  it('B1: Trailing and Level on a live scoreboard', async () => {
    getWeeklyReport.mockResolvedValue(
      liveOpening({ matchups: [{ home: { ...nbaSides.home, isMe: true, points: 90 }, away: { ...nbaSides.away, points: 99 } }] }),
    )
    const first = renderPage()
    expect(await within(await screen.findByLabelText('Your matchup')).findByText('Trailing')).toBeInTheDocument()
    first.unmount()

    getWeeklyReport.mockResolvedValue(
      liveOpening({ matchups: [{ home: { ...nbaSides.home, isMe: true, points: 99 }, away: { ...nbaSides.away, points: 99 } }] }),
    )
    renderPage()
    const board = await screen.findByLabelText('Your matchup')
    expect(within(board).getByText('Level')).toBeInTheDocument()
    expect(within(board).queryByText(/ by /)).not.toBeInTheDocument()
  })

  it('B1: a final week still highlights the winner', async () => {
    getWeeklyReport.mockResolvedValue(data())
    const { container } = renderPage()
    expect(await screen.findByText('Dart has hit anotha Bower')).toBeInTheDocument()
    expect(container.querySelectorAll('.wr-won')).toHaveLength(1)
  })

  // ---- code review B2: a refusal for an explicit live week is shown, not clamped away -----------------

  it('B2: mid-season, ?week=4 answered with a refusal stays on week 4 and shows the reason', async () => {
    getWeeklyReport.mockResolvedValue(
      data({
        available: false,
        reason: 'live scores unavailable from Sleeper right now',
        week: 4,
        latestScoredWeek: 3,
        latestFinalWeek: 3,
        liveWeek: 4,
        weekFinal: false,
        matchups: [],
        topPerformers: [],
        awards: [],
      }),
    )
    renderPage('/leagues/L1/weekly-report?week=4')

    expect(await screen.findByText('live scores unavailable from Sleeper right now')).toBeInTheDocument()
    await waitFor(() => expect(lastSearch).toBe('?week=4'))
    expect(getWeeklyReport.mock.calls.every((c) => c[1] === 4)).toBe(true)
  })

  // ---- code review N5: no "()" for a roster without a record ------------------------------------------

  it('N5: a side with no record renders no empty parentheses', async () => {
    getWeeklyReport.mockResolvedValue(
      liveOpening({ matchups: [{ home: { ...nbaSides.home, record: null as unknown as string }, away: { ...nbaSides.away, record: '' } }] }),
    )
    const { container } = renderPage()
    expect(await screen.findByText('Hoop Dreams')).toBeInTheDocument()
    expect(container.textContent).not.toContain('()')
  })
})

/**
 * Spec 045 US3/US4 (T023, T029): lock-ins under each side of an NBA card. Football has none; every state says what
 * it is in words; margins always carry the name of their baseline.
 */
describe('Weekly report: lock-ins on the cards', () => {
  const nba = (over: Partial<Data> = {}) =>
    data({ sport: 'nba', playersPlayMultiplePerPeriod: true, topPerformers: undefined, awards: [], ...over })

  const call = (rosterId: number, playerId: string, player: string, over: Record<string, unknown> = {}) => ({
    rosterId, managerId: null, manager: 'Someone', playerId, player, team: 'BOS',
    latestGame: { gameId: 'g', gameDate: '2026-10-22', points: 41.5 },
    baseline: 30.2, baselineKind: 'STAND_IN', margin: 0.37, gamesLeft: 2, threshold: 0.25, call: 'LOCK', ...over,
  })
  const liveReport = (week: number | null, calls: unknown[], status = 'OK') => ({
    sport: 'nba', season: 2026, status: 'NO_SCORED_WEEKS', reason: 'No final scored weeks are stored for this league yet.',
    locks: [], caveats: [], thresholds: null, live: { week, asOf: '2026-10-22', status, calls },
  })
  const lock = (rosterId: number, playerId: string, player: string, over: Record<string, unknown> = {}) => ({
    week: 5, playoff: false, managerId: null, manager: null, rosterId, playerId, player, gameDate: '2025-11-20', gameId: 'g',
    points: 50, standIn: { baseline: 32, margin: 0.54 }, projection: null, laterGames: 2, lastGame: 10, bestLater: 12,
    beatenByLast: false, beatenLater: false, ...over,
  })
  const pastReport = (locks: unknown[], over: Record<string, unknown> = {}) => ({
    sport: 'nba', season: 2025, status: 'MEASURED', reason: null, locks, caveats: [], thresholds: null, live: null,
    weekCovered: true, ...over,
  })
  const sidesOf = () => Array.from(document.querySelectorAll('.wr-gside, .wr-score-col')) as HTMLElement[]

  it('live week: each call lands under its own side, with the baseline named and one caption', async () => {
    getWeeklyReport.mockResolvedValue(nba({ live: true, weekFinal: false, liveWeek: 1 }))
    fetchLockIns.mockResolvedValue(liveReport(1, [call(1, 'p1', 'Jayson Tatum'), call(2, 'p2', 'Nikola Jokic', { call: 'WAIT', margin: 0.1 })]))
    renderPage()

    expect(await screen.findByText('Jayson Tatum')).toBeInTheDocument()
    const sides = sidesOf()
    expect(sides.length).toBe(2)
    expect(within(sides[0]).getByText('Jayson Tatum')).toBeInTheDocument()
    expect(within(sides[0]).queryByText('Nikola Jokic')).not.toBeInTheDocument()
    expect(within(sides[1]).getByText('Nikola Jokic')).toBeInTheDocument()
    expect(within(sides[0]).getByText('Lock')).toBeInTheDocument()
    expect(within(sides[1]).getByText('Wait')).toBeInTheDocument()
    expect(screen.getAllByText(/season avg/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Locks are inferred from credited points; bars are learned from past weeks\./)).toHaveLength(1)
    expect(fetchLockIns).toHaveBeenCalledWith('L1', undefined)
  })

  it('football renders no lock-in content and never asks', async () => {
    getWeeklyReport.mockResolvedValue(data({ live: true, weekFinal: false, liveWeek: 1 }))
    renderPage()
    await screen.findByText(/In progress, live from Sleeper/)
    expect(fetchLockIns).not.toHaveBeenCalled()
    expect(screen.queryByText(/Locks are inferred/)).not.toBeInTheDocument()
  })

  it('GAME_MODE hides the whole area', async () => {
    getWeeklyReport.mockResolvedValue(nba({ live: true, weekFinal: false, liveWeek: 1 }))
    fetchLockIns.mockResolvedValue({ ...liveReport(1, []), status: 'GAME_MODE', live: null })
    renderPage()
    await screen.findByText(/In progress, live from Sleeper/)
    await waitFor(() => expect(fetchLockIns).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByText(/Loading lock-ins/)).not.toBeInTheDocument())
    expect(screen.queryByText(/Locks are inferred/)).not.toBeInTheDocument()
  })

  it('ROSTERS_FAILED says why while the scores stay visible', async () => {
    getWeeklyReport.mockResolvedValue(nba({ live: true, weekFinal: false, liveWeek: 1 }))
    fetchLockIns.mockResolvedValue(liveReport(1, [], 'ROSTERS_FAILED'))
    renderPage()
    expect(await screen.findByText(/lineups couldn't be read from Sleeper/)).toBeInTheDocument()
    expect(screen.getByText('157.40')).toBeInTheDocument()
  })

  it('shows no calls when the lock-in week is not the card week', async () => {
    getWeeklyReport.mockResolvedValue(nba({ live: true, weekFinal: false, liveWeek: 1 }))
    fetchLockIns.mockResolvedValue(liveReport(2, [call(1, 'p1', 'Jayson Tatum')]))
    renderPage()
    expect(await screen.findByText(/Lock-in calls are for week 2, not this week \(1\)/)).toBeInTheDocument()
    expect(screen.queryByText('Jayson Tatum')).not.toBeInTheDocument()
  })

  it('a failed lock-ins read never takes the scores away', async () => {
    getWeeklyReport.mockResolvedValue(nba({ live: true, weekFinal: false, liveWeek: 1 }))
    fetchLockIns.mockRejectedValue(new Error('boom'))
    renderPage()
    expect(await screen.findByText(/Lock-ins couldn't be loaded/)).toBeInTheDocument()
    expect(screen.getByText('157.40')).toBeInTheDocument()
  })

  it('final week: locks grouped per side, labelled by baseline, with later games played', async () => {
    getWeeklyReport.mockResolvedValue(nba({ week: 5, latestScoredWeek: 5, latestFinalWeek: 5 }))
    fetchLockIns.mockResolvedValue(
      pastReport([
        lock(1, 'p1', 'Donovan Mitchell'),
        lock(2, 'p2', 'Luka Doncic', { standIn: { baseline: 40, margin: 0.2 }, projection: { projectedPoints: 35, actualRestricted: 50, margin: 0.43 }, laterGames: 1 }),
      ]),
    )
    renderPage('/leagues/L1/weekly-report?week=5')

    expect(await screen.findByText('Donovan Mitchell')).toBeInTheDocument()
    expect(fetchLockIns).toHaveBeenCalledWith('L1', { week: 5 })
    const [a, b] = sidesOf()
    expect(within(a).getByText(/\+54%/)).toBeInTheDocument()
    expect(within(a).getByText('stand-in')).toBeInTheDocument()
    expect(within(a).getByText('(2 later games)')).toBeInTheDocument()
    expect(within(b).getByText('Luka Doncic')).toBeInTheDocument()
    expect(within(b).getByText(/\+43%/)).toBeInTheDocument()
    expect(within(b).getByText('projection')).toBeInTheDocument()
    expect(within(b).getByText('(1 later game)')).toBeInTheDocument()
    expect(screen.getAllByText(/Locks are inferred from credited points/)).toHaveLength(1)
  })

  it('a side with no locks says so', async () => {
    getWeeklyReport.mockResolvedValue(nba({ week: 5, latestScoredWeek: 5, latestFinalWeek: 5 }))
    fetchLockIns.mockResolvedValue(pastReport([lock(1, 'p1', 'Donovan Mitchell')]))
    renderPage('/leagues/L1/weekly-report?week=5')
    await screen.findByText('Donovan Mitchell')
    expect(within(sidesOf()[1]).getByText('no locks')).toBeInTheDocument()
  })

  it('weekCovered false says it cannot tell, not no locks', async () => {
    getWeeklyReport.mockResolvedValue(nba({ week: 5, latestScoredWeek: 5, latestFinalWeek: 5 }))
    fetchLockIns.mockResolvedValue(pastReport([], { weekCovered: false }))
    renderPage('/leagues/L1/weekly-report?week=5')
    expect((await screen.findAllByText(/can't tell for this week/)).length).toBeGreaterThanOrEqual(2)
    expect(screen.queryByText('no locks')).not.toBeInTheDocument()
  })
})
