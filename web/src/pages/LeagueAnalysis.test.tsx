import { render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LeagueAnalysis from './LeagueAnalysis'
import type { LeagueAnalysis as LeagueAnalysisData } from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
  Link: ({ children }: { children: React.ReactNode }) => <a href="#">{children}</a>,
}))

const getLeagueAnalysis = vi.fn()
const ingestLeagueHistory = vi.fn()
vi.mock('../api', () => ({
  getLeagueAnalysis: (...args: unknown[]) => getLeagueAnalysis(...args),
  ingestLeagueHistory: (...args: unknown[]) => ingestLeagueHistory(...args),
}))

function roster(rosterId: number, manager: string, rank: number, total: number,
                byPosition: Record<string, number>, rankByPosition: Record<string, number>,
                missing = 0) {
  return {
    rosterId, managerId: rosterId, manager, avatarId: null, rank, total,
    byPosition, rankByPosition, starters: [], missing,
  }
}

const GROUPS = ['QB', 'RB', 'WR', 'TE', 'K', 'DEF']

function data(over: Partial<LeagueAnalysisData> = {}): LeagueAnalysisData {
  return {
    season: 2026,
    scoringKey: 'PPR',
    window: { fromWeek: 2, toWeek: 14, weeks: 13, scoredWeeks: 1 },
    rankingScores: {
      available: false,
      reason: '1 week scored. Three weeks are needed.',
      formula: '((avgWeeklyScore * 6) + ((highScore + lowScore) * 2) + (winPct * 400)) / 10',
      weeksScored: 1,
      weeksRequired: 3,
      entries: [],
    },
    projections: {
      available: true,
      reason: null,
      positionGroups: GROUPS,
      rosters: [
        roster(1, 'kieriskash', 1, 1683.9,
          { QB: 234.7, RB: 757.2, WR: 323.4, TE: 189.6, K: 79.7, DEF: 99.3 },
          { QB: 4, RB: 1, WR: 11, TE: 3, K: 8, DEF: 1 }, 1),
        roster(2, 'jstrobe', 2, 1572.9,
          { QB: 229.9, RB: 368.2, WR: 685.3, TE: 138.8, K: 79.3, DEF: 71.4 },
          { QB: 5, RB: 8, WR: 4, TE: 7, K: 9, DEF: 12 }),
      ],
    },
    ...over,
  }
}

beforeEach(() => {
  getLeagueAnalysis.mockReset()
  ingestLeagueHistory.mockReset()
})

describe('LeagueAnalysis', () => {
  /**
   * The gate is the whole point of piece 1 -- an early season must say why it
   * is holding back, not render a ladder of numbers built on one game. A test
   * that only checked "no table" would pass on a page that rendered nothing at
   * all, so this asserts the reason reaches the reader.
   */
  it('explains why the ranking score is withheld instead of showing an empty ladder', async () => {
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    expect(await screen.findByText(/Three weeks are needed/)).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Score' })).not.toBeInTheDocument()
  })

  /** Withheld or not, the formula is shown -- it is the reader's way of judging it. */
  it('shows the formula even when the score is withheld', async () => {
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)
    expect(await screen.findByText(/avgWeeklyScore \* 6/)).toBeInTheDocument()
  })

  /**
   * claude/league-analysis.md, and feedback_label_the_axis_spell_out_the_number:
   * every segment of a bar has its value printed beside it. A stacked bar whose
   * only encoding is width and hue is unreadable, and six hues are not
   * memorizable.
   */
  it('prints each position group value next to the bar, not just the colour', async () => {
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    // The manager appears in both the bars and the matrix, so scope to the bar.
    await screen.findAllByText('kieriskash')
    const row = screen
      .getAllByText('kieriskash')
      .map((el) => el.closest('.analysis-bar-row'))
      .find((el) => el != null)
    expect(row).toBeTruthy()
    const scope = within(row as HTMLElement)
    // Rounded to whole points in the legend; the exact value is in the title.
    expect(scope.getByText('757')).toBeInTheDocument()
    expect(scope.getByText('235')).toBeInTheDocument()
    expect(scope.getByText('1683.9')).toBeInTheDocument()
    GROUPS.forEach((g) => expect(scope.getAllByText(g).length).toBeGreaterThan(0))
  })

  /**
   * A thin bar has two explanations -- a bad roster, or players Sleeper does
   * not project -- and only the page can tell the reader which.
   */
  it('reports rostered players with no projection rather than hiding them', async () => {
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)
    expect(await screen.findByText('1 unprojected')).toBeInTheDocument()
  })

  /** Piece 3: rank AND the points it came from, in every cell. */
  it('shows both the rank and its points in the position matrix', async () => {
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    await screen.findByText(/where each roster stands/)
    const matrix = document.querySelector('.analysis-matrix') as HTMLElement
    const cells = within(matrix).getAllByTitle(/kieriskash — RB/)
    expect(cells[0]).toHaveTextContent('1')
    expect(cells[0]).toHaveTextContent('757')
  })

  /**
   * The projections half can be unavailable on its own while the page is
   * otherwise fine -- a cold projection cache, or a league past its regular
   * season. It must say so rather than rendering twelve empty bars.
   */
  it('explains unavailable projections without breaking the page', async () => {
    getLeagueAnalysis.mockResolvedValue(
      data({
        projections: {
          available: false,
          reason: 'no projections stored for nfl 2026 weeks 2-14',
          positionGroups: GROUPS,
          rosters: [],
        },
      }),
    )
    render(<LeagueAnalysis />)

    await waitFor(() =>
      expect(screen.getAllByText(/no projections stored/).length).toBeGreaterThan(0),
    )
    // Rendered lowercase; the uppercase is `.panel h2`'s text-transform.
    expect(screen.getByText('Roster projections')).toBeInTheDocument()
  })

  /** The window the numbers cover is stated, along with the scoring they use. */
  it('names the weeks projected and the scoring they were read under', async () => {
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)
    expect(await screen.findByText(/weeks 2–14 · full PPR/)).toBeInTheDocument()
  })
})
