import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LeagueHistory from './LeagueHistory'
import type {
  LeagueHistory as LeagueHistoryData,
  LeagueRecords,
  RankStatus,
  StandingRow,
  WeeklyScoreRecord,
} from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
  Link: ({ children }: { children: React.ReactNode }) => <a href="#">{children}</a>,
}))

const getLeagueHistory = vi.fn()
const ingestLeagueHistory = vi.fn()
const backfillFinalRanks = vi.fn()
vi.mock('../api', () => ({
  getLeagueHistory: (...args: unknown[]) => getLeagueHistory(...args),
  ingestLeagueHistory: (...args: unknown[]) => ingestLeagueHistory(...args),
  backfillFinalRanks: (...args: unknown[]) => backfillFinalRanks(...args),
}))

function score(season: number, week: number, rosterId: number, points: number,
               manager: string | null = `mgr${rosterId}`): WeeklyScoreRecord {
  return {
    season, week, rosterId, points, manager,
    managerId: manager == null ? null : rosterId,
    avatarId: null,
  }
}

const EMPTY_RECORDS: LeagueRecords = {
  limit: 10,
  highestWeeks: [],
  lowestWeeks: [],
  closestMatchups: [],
  biggestBlowouts: [],
  marginsUnavailableReason: 'Pairings have not been ingested for seasons before 2026.',
}

function history(records: Partial<LeagueRecords> = {}): LeagueHistoryData {
  return {
    sleeperLeagueId: 'L1',
    seasons: [
      {
        season: 2025, leagueId: 5, sleeperLeagueId: 'L1', name: '(Foot) Ball Knowers',
        standings: [],
      },
    ],
    records: { ...EMPTY_RECORDS, ...records },
  }
}

function standing(rosterId: number, rankStatus: RankStatus, finalRank: number | null = null,
                  finalRankWeek: number | null = null): StandingRow {
  return {
    rosterId, managerId: rosterId, manager: `mgr${rosterId}`, avatarId: null,
    wins: 9, losses: 5, ties: 0, pointsFor: 1800, pointsAgainst: 1700,
    champion: false, season: null, sleeperLeagueId: null,
    rankStatus, finalRank, finalRankWeek,
  }
}

function withStandings(rows: StandingRow[], season = 2025): LeagueHistoryData {
  const h = history()
  h.seasons = [{ season, leagueId: 5, sleeperLeagueId: 'L1', name: 'BK', standings: rows }]
  return h
}

beforeEach(() => {
  getLeagueHistory.mockReset()
  ingestLeagueHistory.mockReset()
  backfillFinalRanks.mockReset()
})

describe('record book', () => {
  it('renders manager, score and season/week for each record', async () => {
    getLeagueHistory.mockResolvedValue(
      history({ highestWeeks: [score(2025, 8, 7, 205.04, 'GraftonCarlson')] }),
    )
    render(<LeagueHistory />)

    const highest = await screen.findByRole('heading', { name: /highest weeks/i })
    const list = highest.parentElement as HTMLElement
    expect(within(list).getByText('GraftonCarlson')).toBeTruthy()
    expect(within(list).getByText('205.04')).toBeTruthy()
    expect(within(list).getByText(/2025.*Wk 8/)).toBeTruthy()
  })

  /**
   * Records span the chain. A page scoped to seasons[0] would answer the
   * question it could already answer (FR-001) -- 2024 here is not the head.
   */
  it('renders records from a season that is not the head of the chain', async () => {
    getLeagueHistory.mockResolvedValue(
      history({ highestWeeks: [score(2024, 24, 3, 188.2), score(2025, 8, 7, 205.04)] }),
    )
    render(<LeagueHistory />)

    expect(await screen.findByText(/2024.*Wk 24/)).toBeTruthy()
  })

  /**
   * data-model R1: an unowned roster-season still renders. Dropping it would
   * silently edit the record book rather than report it.
   */
  it('still renders a record whose roster has no manager', async () => {
    getLeagueHistory.mockResolvedValue(
      history({ highestWeeks: [score(2025, 8, 7, 205.04, null)] }),
    )
    render(<LeagueHistory />)

    expect(await screen.findByText('roster 7')).toBeTruthy()
    expect(screen.getByText('205.04')).toBeTruthy()
  })

  /**
   * FR-010 / SC-004: a panel with nothing to show says why. Two silent empty
   * lists are indistinguishable from a broken page.
   */
  it('states a reason instead of rendering two empty lists', async () => {
    getLeagueHistory.mockResolvedValue(history())
    render(<LeagueHistory />)

    expect(await screen.findByText(/no weekly scores have been loaded/i)).toBeTruthy()
    expect(screen.queryByRole('heading', { name: /highest weeks/i })).toBeNull()
  })

  /** Ties both appear rather than one silently winning (R2). */
  it('renders both rows of a tied score', async () => {
    getLeagueHistory.mockResolvedValue(
      history({ highestWeeks: [score(2025, 8, 7, 150.5, 'a'), score(2025, 9, 3, 150.5, 'b')] }),
    )
    render(<LeagueHistory />)

    await screen.findByText('a')
    expect(screen.getAllByText('150.50')).toHaveLength(2)
  })
})

describe('matchup margins', () => {
  it('renders the stated reason when pairings are unavailable', async () => {
    getLeagueHistory.mockResolvedValue(history())
    render(<LeagueHistory />)

    expect(
      await screen.findByText(/pairings have not been ingested for seasons before 2026/i),
    ).toBeTruthy()
  })

  it('renders both sides, both scores and the labelled margin', async () => {
    getLeagueHistory.mockResolvedValue(
      history({
        closestMatchups: [
          {
            season: 2025, week: 17, margin: 0.16,
            winner: { rosterId: 3, managerId: 3, manager: 'Dart', avatarId: null, points: 132.58 },
            loser: { rosterId: 9, managerId: 9, manager: 'AJ', avatarId: null, points: 132.42 },
          },
        ],
      }),
    )
    render(<LeagueHistory />)

    const card = (await screen.findByText('0.16')).closest('.margin-card') as HTMLElement
    expect(within(card).getByText('Dart')).toBeTruthy()
    expect(within(card).getByText('132.58')).toBeTruthy()
    expect(within(card).getByText('AJ')).toBeTruthy()
    expect(within(card).getByText('132.42')).toBeTruthy()
    // The axis is spelled out beside the number -- "0.16" alone does not say
    // what it measures.
    expect(within(card).getByText(/margin/i)).toBeTruthy()
  })
})

describe('loading and error states', () => {
  it('offers a button rather than printing an endpoint when seasons are missing', async () => {
    getLeagueHistory.mockRejectedValue(new Error('404 not found'))
    render(<LeagueHistory />)

    expect(await screen.findByRole('button', { name: /load past seasons/i })).toBeTruthy()
    await waitFor(() => {
      expect(document.body.textContent).not.toMatch(/POST \/api/)
    })
  })
})

describe('season final rank', () => {
  it('shows the rank for a season that has a realized snapshot', async () => {
    getLeagueHistory.mockResolvedValue(withStandings([standing(7, 'RANKED', 1, 17)]))
    render(<LeagueHistory />)

    const cell = await screen.findByTitle('Through week 17')
    expect(cell.textContent).toBe('1')
  })

  /**
   * A season still being played has no final rank and is not supposed to. It
   * must not read as an error, and it must not offer a button that cannot help.
   */
  it('reads as in progress, with no button, for the current season', async () => {
    getLeagueHistory.mockResolvedValue(withStandings([standing(7, 'IN_PROGRESS')], 2026))
    render(<LeagueHistory />)

    expect(await screen.findByText(/season in progress/i)).toBeTruthy()
    expect(screen.queryByRole('button', { name: /compute/i })).toBeNull()
  })

  /** FR-006: a finished, computable season gets the reason AND the control. */
  it('offers a compute button for a finished season that was never computed', async () => {
    getLeagueHistory.mockResolvedValue(withStandings([standing(7, 'NOT_COMPUTED')], 2024))
    render(<LeagueHistory />)

    expect(await screen.findByText(/not computed yet/i)).toBeTruthy()
    expect(screen.getByRole('button', { name: /compute/i })).toBeTruthy()
  })

  /** Nothing to compute from -- the button would lie, so there isn't one. */
  it('states the reason and offers no button when there are no stored scores', async () => {
    getLeagueHistory.mockResolvedValue(withStandings([standing(7, 'UNAVAILABLE')], 2023))
    render(<LeagueHistory />)

    expect(await screen.findByText(/no weekly scores stored/i)).toBeTruthy()
    expect(screen.queryByRole('button', { name: /compute/i })).toBeNull()
  })

  /** SC-004 / FR-005: no rank cell is ever a bare dash with no explanation. */
  it('never renders an unexplained dash', async () => {
    getLeagueHistory.mockResolvedValue(
      withStandings([standing(1, 'IN_PROGRESS'), standing(2, 'NOT_COMPUTED'), standing(3, 'UNAVAILABLE')]),
    )
    render(<LeagueHistory />)

    await screen.findByText(/season in progress/i)
    const cells = document.querySelectorAll('.rank-cell')
    expect(cells.length).toBe(3)
    cells.forEach((c) => expect(c.textContent?.trim()).not.toBe('—'))
  })

  it('fires the backfill from the button and reloads, rather than printing an endpoint', async () => {
    getLeagueHistory.mockResolvedValue(withStandings([standing(7, 'NOT_COMPUTED')], 2024))
    backfillFinalRanks.mockResolvedValue({ backfilled: [{ season: 2024, week: 24, entries: 12 }], skipped: [] })
    render(<LeagueHistory />)

    const button = await screen.findByRole('button', { name: /compute/i })
    getLeagueHistory.mockResolvedValue(withStandings([standing(7, 'RANKED', 1, 24)], 2024))
    await userEvent.click(button)

    await waitFor(() => expect(backfillFinalRanks).toHaveBeenCalledWith('L1'))
    expect(document.body.textContent).not.toMatch(/POST \/api/)
  })
})
