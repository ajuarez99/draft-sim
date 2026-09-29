import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WeeklyReport from './WeeklyReport'
import type { WeeklyReport as Data } from '../api'
// Source text, the way destinations.test.ts reads App.tsx: the assertion is
// about what the file says, so it has to read the file.
import pageSource from './WeeklyReport.tsx?raw'

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
vi.mock('../api', () => ({
  getWeeklyReport: (...args: unknown[]) => getWeeklyReport(...args),
}))

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
      { playerId: '1', playerName: 'Caleb Williams', position: 'QB', teamName: 'Prayer Circle', points: 37.26 },
    ],
    awards: [
      {
        kind: 'SELF_INFLICTED_WOUND',
        teamName: 'Justice for Wags',
        detail: 'Deebo Samuel outscored Carnell Tate by 10.20. Justice for Wags lost by 9.50.',
      },
    ],
    awardsOmitted: [],
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
    const heading = await screen.findByRole('heading', { name: /matchups/i })
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
 */
describe('Weekly report week selection', () => {
  beforeEach(() => {
    getWeeklyReport.mockReset()
    lastSearch = ''
  })

  const weekInput = () => screen.findByLabelText('Week') as Promise<HTMLInputElement>

  it('opens on the latest scored week when the URL names none', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 2, latestScoredWeek: 2, latestFinalWeek: 2 }))
    renderPage()

    expect((await weekInput()).value).toBe('2')
    // 0 is the "latest" request; the page never asks for week 1 by default.
    expect(getWeeklyReport).toHaveBeenCalledWith('L1', 0)
    expect(await screen.findByText('Week 2 matchups')).toBeInTheDocument()
  })

  it('reads the week from ?week= and asks for exactly that week', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 1, latestScoredWeek: 3 }))
    renderPage('/leagues/L1/weekly-report?week=1')

    expect((await weekInput()).value).toBe('1')
    expect(getWeeklyReport).toHaveBeenCalledWith('L1', 1)
  })

  it('writes the chosen week back to ?week=', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 3, latestScoredWeek: 3 }))
    renderPage()

    fireEvent.change(await weekInput(), { target: { value: '2' } })

    await waitFor(() => expect(lastSearch).toBe('?week=2'))
    await waitFor(() => expect(getWeeklyReport).toHaveBeenLastCalledWith('L1', 2))
  })

  it('caps the input at the latest scored week, with no hardcoded 18', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 3, latestScoredWeek: 3 }))
    renderPage()

    const input = await weekInput()
    expect(input.max).toBe('3')
    expect(input.min).toBe('1')
  })

  it('clamps a typed week into [1, latest]', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 3, latestScoredWeek: 3 }))
    renderPage()
    const input = await weekInput()

    fireEvent.change(input, { target: { value: '40' } })
    await waitFor(() => expect(lastSearch).toBe('?week=3'))

    fireEvent.change(input, { target: { value: '-5' } })
    await waitFor(() => expect(lastSearch).toBe('?week=1'))
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

    expect((await weekInput()).value).toBe('2')
    expect(getWeeklyReport).toHaveBeenCalledWith('L1', 0)
  })

  it('defaults to the latest FINAL week, with no in-progress label, while a later week is in progress', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 2, latestScoredWeek: 3, latestFinalWeek: 2, weekFinal: true }))
    renderPage()

    const input = await weekInput()
    expect(input.value).toBe('2')
    expect(input.max).toBe('3') // the in-progress week can still be opened on purpose
    expect(screen.queryByText(/scores can still change/)).not.toBeInTheDocument()
  })

  it('labels an in-progress week, whether chosen on purpose or the fallback when nothing is final', async () => {
    getWeeklyReport.mockResolvedValue(data({ week: 1, latestScoredWeek: 1, latestFinalWeek: 0, weekFinal: false }))
    renderPage()

    expect(await screen.findByText(/scores can still change/)).toBeInTheDocument()
    expect(screen.getByText('Week 1 matchups')).toBeInTheDocument()
    expect((await weekInput()).max).toBe('1')
  })

  it('shows a plain "nothing scored" panel, and no week picker, before any week is scored', async () => {
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
    expect(screen.queryByLabelText('Week')).not.toBeInTheDocument()
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
