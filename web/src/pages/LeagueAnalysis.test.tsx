import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LeagueAnalysis from './LeagueAnalysis'
import type { AnalysisLineupPlayer, LeagueAnalysis as LeagueAnalysisData } from '../api'

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

function player(slot: string, position: string, name: string, points: number,
                injuryStatus: string | null = null): AnalysisLineupPlayer {
  return { sleeperPlayerId: `${name}-${slot}`, name, position, team: 'CIN', slot, points, injuryStatus }
}

/**
 * The wire order the backend promises: the league's own slot order, which is
 * deliberately NOT points order -- the quarterback below is worth less than
 * the running back and still comes first.
 */
const LINEUP: AnalysisLineupPlayer[] = [
  player('QB', 'QB', 'Jayden Daniels', 18.1),
  player('RB', 'RB', 'Bijan Robinson', 19.6),
  player('WR', 'WR', "Ja'Marr Chase", 21.4),
  player('TE', 'TE', 'Brock Bowers', 13.2),
  player('FLEX', 'RB', 'Kyren Williams', 12.8),
]

const BENCH: AnalysisLineupPlayer[] = [
  player('BN', 'WR', 'Rome Odunze', 9.1),
  player('BN', 'WR', 'A.J. Brown', 0, 'IR'),
]

function roster(rosterId: number, manager: string, rank: number, total: number,
                byPosition: Record<string, number>, rankByPosition: Record<string, number>,
                missing = 0, over: { isMe?: boolean; starters?: AnalysisLineupPlayer[] } = {}) {
  return {
    rosterId, managerId: rosterId, manager, avatarId: null, rank, isMe: over.isMe ?? false, total,
    byPosition, rankByPosition, starters: over.starters ?? LINEUP, bench: BENCH,
    byWeek: [{ week: 2, points: 120 }, { week: 3, points: 96 }, { week: 4, points: 130 }], missing,
  }
}

function side(rosterId: number, manager: string, projected: number, isMe = false) {
  return {
    rosterId, managerId: rosterId, manager, avatarId: null, isMe, projected,
    byPosition: { RB: projected }, starters: LINEUP,
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
          { QB: 5, RB: 8, WR: 4, TE: 7, K: 9, DEF: 12 }, 0,
          { isMe: true, starters: [player('QB', 'QB', 'Josh Allen', 22.5), ...LINEUP.slice(1)] }),
      ],
    },
    scores: {
      available: true,
      reason: null,
      weeks: [1, 2],
      rosters: [
        { rosterId: 1, managerId: 1, manager: 'kieriskash', avatarId: null, isMe: false,
          weeks: [{ week: 1, points: 164.96, rank: 1 }, { week: 2, points: 120.5, rank: 2 }],
          total: 285.46, avg: 142.73, high: 164.96, low: 120.5 },
        { rosterId: 2, managerId: 2, manager: 'jstrobe', avatarId: null, isMe: true,
          weeks: [{ week: 1, points: 140.1, rank: 2 }, { week: 2, points: 131.0, rank: 1 }],
          total: 271.1, avg: 135.55, high: 140.1, low: 131.0 },
      ],
    },
    matchups: {
      available: true,
      reason: null,
      week: 2,
      matchups: [
        { matchupId: 1, sides: [side(1, 'kieriskash', 118.4), side(2, 'jstrobe', 104.1, true)] },
        { matchupId: 2, sides: [side(3, 'BamAddABio', 96.2)] },
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

  // ---- the lineup drilldown (claude/league-analysis-lineups-and-matchups.md) ----

  /**
   * The complaint the whole second pass answers: the page said 1,683.9 and
   * named nobody. A collapsed lineup is fine; an unreachable one is not.
   */
  it('opens the lineup behind a bar and names the players in it', async () => {
    const user = userEvent.setup()
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    const toggles = await screen.findAllByRole('button', { name: /Lineup/ })
    // Scoped to the bars: Head to head renders lineups of its own further down
    // the page, and an unscoped query would pass on those instead.
    const bars = within(document.querySelector('.analysis-bars') as HTMLElement)
    expect(bars.queryByText('Bijan Robinson')).not.toBeInTheDocument()

    await user.click(toggles[0])
    expect(bars.getByText('Bijan Robinson')).toBeInTheDocument()
    expect(toggles[0]).toHaveAttribute('aria-expanded', 'true')
  })

  /**
   * Slot order, not points order -- the backend sorts by the league's own
   * roster_positions for this reason, and a card that reordered it into a
   * ladder would be undoing that on the way out.
   */
  it('lists the lineup in slot order even where that is not points order', async () => {
    const user = userEvent.setup()
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    await user.click((await screen.findAllByRole('button', { name: /Lineup/ }))[0])
    const names = Array.from(document.querySelectorAll('.analysis-slotlist .analysis-slot-name'))
      .map((el) => el.textContent?.replace(/CIN.*$/, '').trim())

    // The 18.1 quarterback above the 21.4 receiver: the league's card, not a ladder.
    expect(names.slice(0, 3)).toEqual(['Jayden Daniels', 'Bijan Robinson', "Ja'Marr Chase"])
  })

  /**
   * "1 unprojected" is a count the reader cannot act on. The bench names the
   * zero and tags it, which is the same fact in a form that explains itself.
   */
  it('names the injured zero on the bench rather than only counting it', async () => {
    const user = userEvent.setup()
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    await user.click((await screen.findAllByRole('button', { name: /Lineup/ }))[0])
    const bench = screen.getByText('A.J. Brown').closest('.analysis-slot') as HTMLElement
    expect(within(bench).getByText('IR')).toBeInTheDocument()
    expect(within(bench).getByText('0.0')).toBeInTheDocument()
  })

  // ---- week N matchups ----

  /** Both sides, the week, and a margin that is called a margin. */
  it('shows next week\u2019s pairing with both projections and the margin', async () => {
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    expect(await screen.findByText('Week 2 matchups')).toBeInTheDocument()
    const game = document.querySelector('.analysis-game') as HTMLElement
    const scope = within(game)
    expect(scope.getByText('118.4')).toBeInTheDocument()
    expect(scope.getByText('104.1')).toBeInTheDocument()
    expect(scope.getByText('14.3')).toBeInTheDocument()
  })

  /**
   * A bye is carried on the wire deliberately. A manager with no game next
   * week needs telling; a missing card tells them nothing.
   */
  it('draws a bye as a bye instead of dropping the manager', async () => {
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)
    expect(await screen.findByText(/No opponent this week/)).toBeInTheDocument()
  })

  /** The same available/reason discipline as the two blocks that came first. */
  it('explains an unavailable matchup block instead of drawing nothing', async () => {
    getLeagueAnalysis.mockResolvedValue(
      data({
        matchups: {
          available: false,
          reason: 'no pairings stored for week 2',
          week: 2,
          matchups: [],
        },
      }),
    )
    render(<LeagueAnalysis />)
    expect(await screen.findByText(/no pairings stored for week 2/)).toBeInTheDocument()
  })

  /**
   * A finished season refuses this block because its regular season ended at
   * week 14, while `week` is lastScored + 1 = 18. Heading that refusal "Week 18
   * matchups" would assert a week the reason underneath denies.
   */
  it('does not name a week in the heading when it is refusing to show one', async () => {
    getLeagueAnalysis.mockResolvedValue(
      data({
        matchups: {
          available: false,
          reason: 'the regular season is over (weeks run to 14, and 17 are scored)',
          week: 18,
          matchups: [],
        },
      }),
    )
    render(<LeagueAnalysis />)

    expect(await screen.findByText('Upcoming matchups')).toBeInTheDocument()
    expect(screen.queryByText(/Week 18 matchups/)).not.toBeInTheDocument()
  })

  /**
   * "QUESTIONABLE" beside a name swamps the name it is annotating, and this
   * page draws ten of them at a time. The word survives in the title so the
   * short code never costs the reader the fact.
   */
  it('shortens the long injury words but keeps the word itself reachable', async () => {
    const user = userEvent.setup()
    getLeagueAnalysis.mockResolvedValue(
      data({
        projections: {
          available: true,
          reason: null,
          positionGroups: GROUPS,
          rosters: [
            roster(1, 'kieriskash', 1, 200, { QB: 200 }, { QB: 1 }, 0,
              { starters: [player('QB', 'QB', 'Brock Bowers', 17.1, 'Questionable')] }),
            roster(2, 'jstrobe', 2, 100, { QB: 100 }, { QB: 2 }),
          ],
        },
      }),
    )
    render(<LeagueAnalysis />)

    await user.click((await screen.findAllByRole('button', { name: /Lineup/ }))[0])
    const bars = within(document.querySelector('.analysis-bars') as HTMLElement)
    const tag = bars.getByTitle('Questionable')
    expect(tag).toHaveTextContent('Q')
  })

  // ---- head to head ----

  /**
   * It opens on the reader's own roster, which is the comparison they came
   * for. `isMe` is decided on the backend from Sleeper's owner_id, so the page
   * does not re-derive it.
   */
  it('defaults the comparison to your own roster', async () => {
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    await screen.findByText('Head to head')
    const picks = screen.getAllByRole('combobox')
    expect(within(picks[0] as HTMLElement).getByRole('option', { name: /jstrobe \(you\)/ }))
      .toBeInTheDocument()
    expect((picks[0] as HTMLSelectElement).value).toBe('2')
    expect((picks[1] as HTMLSelectElement).value).toBe('1')
  })

  // ---- week by week (claude/league-analysis-week-by-week.md) ----

  /**
   * The strip decomposes the bar above it, so the weeks it draws have to be
   * the weeks the wire sent -- and the worst one is named in words, because a
   * shorter bar is not a number anyone can read off.
   */
  it('draws the weekly strip and names the best and worst week', async () => {
    const user = userEvent.setup()
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    await user.click((await screen.findAllByRole('button', { name: /Lineup/ }))[0])
    const strip = document.querySelector('.analysis-weekstrip') as HTMLElement
    expect(within(strip).getAllByTitle(/^Week \d+: /)).toHaveLength(3)
    expect(strip).toHaveTextContent('best')
    expect(strip).toHaveTextContent('wk 4 · 130.0')
    expect(strip).toHaveTextContent('worst')
    expect(strip).toHaveTextContent('wk 3 · 96.0')
  })

  /** The week stepper asks the backend for that week rather than filtering locally. */
  it('refetches the matchup block for the week you pick', async () => {
    const user = userEvent.setup()
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    await screen.findByText('Week 2 matchups')
    getLeagueAnalysis.mockResolvedValue(
      data({ matchups: { available: true, reason: null, week: 6, matchups: [] } }),
    )
    await user.click(screen.getByRole('button', { name: '6' }))

    expect(getLeagueAnalysis).toHaveBeenLastCalledWith('L1', 6)
    await waitFor(() => expect(screen.getByText('Week 6 matchups')).toBeInTheDocument())
  })

  /**
   * Only the matchup block moves with the week. Replacing the whole response
   * would reset every open lineup drawer to answer a question that did not
   * touch them.
   */
  it('keeps an open lineup open when the week changes', async () => {
    const user = userEvent.setup()
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    await user.click((await screen.findAllByRole('button', { name: /Lineup/ }))[0])
    const bars = within(document.querySelector('.analysis-bars') as HTMLElement)
    expect(bars.getByText('Bijan Robinson')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '6' }))
    await waitFor(() => expect(bars.getByText('Bijan Robinson')).toBeInTheDocument())
  })

  /** The grid is the scored weeks, and the week's top score is marked. */
  it('shows every scored week with the best score of each marked', async () => {
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    await screen.findByText(/What every roster actually scored/)
    const grid = document.querySelector('.analysis-scores-grid') as HTMLElement
    expect(within(grid).getByTitle(/kieriskash — week 1: 165.0, 1 of 2/)).toHaveClass('best')
    expect(within(grid).getByTitle(/kieriskash — week 2: 120.5, 2 of 2/)).not.toHaveClass('best')
  })

  /**
   * With one scored week, total == avg == high == low == that week, and the row
   * reads as five copies of one number pretending to be five facts.
   */
  it('withholds the summary columns until a second week is scored', async () => {
    getLeagueAnalysis.mockResolvedValue(
      data({
        scores: {
          available: true,
          reason: null,
          weeks: [1],
          rosters: [
            { rosterId: 1, managerId: 1, manager: 'kieriskash', avatarId: null, isMe: false,
              weeks: [{ week: 1, points: 165, rank: 1 }],
              total: 165, avg: 165, high: 165, low: 165 },
          ],
        },
      }),
    )
    render(<LeagueAnalysis />)

    await screen.findByText(/What every roster actually scored/)
    const grid = document.querySelector('.analysis-scores-grid') as HTMLElement
    expect(within(grid).queryByRole('columnheader', { name: 'Avg' })).not.toBeInTheDocument()
    expect(screen.getByText(/appear from week two/)).toBeInTheDocument()
    expect(document.querySelector('.bump-chart')).toBeNull()
  })

  /** Two weeks is a line, so the chart this page borrows from Power rankings appears. */
  it('draws the bump chart once there are two weeks to move between', async () => {
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    await screen.findByText(/What every roster actually scored/)
    expect(document.querySelector('.bump-chart')).not.toBeNull()
    // One line per roster, and the week axis is the scored weeks.
    expect(document.querySelectorAll('.bump-series')).toHaveLength(2)
  })

  /** Slot against slot, with the heavier side marked as such. */
  it('compares the two lineups slot by slot', async () => {
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    await screen.findByText('Head to head')
    const row = document.querySelector('.analysis-vs-row') as HTMLElement
    const scope = within(row)
    // jstrobe's Josh Allen (22.5) against kieriskash's Jayden Daniels (18.1).
    expect(scope.getByText('Josh Allen')).toBeInTheDocument()
    expect(scope.getByText('Jayden Daniels')).toBeInTheDocument()
    expect(scope.getByText('Josh Allen').closest('.analysis-vs-player')).toHaveClass('won')
  })

  /** Changing a side changes the comparison, not just the dropdown. */
  it('swaps the compared roster when the picker changes', async () => {
    const user = userEvent.setup()
    getLeagueAnalysis.mockResolvedValue(data())
    render(<LeagueAnalysis />)

    await screen.findByText('Head to head')
    const picks = screen.getAllByRole('combobox')
    await user.selectOptions(picks[0], '1')

    const row = document.querySelector('.analysis-vs-row') as HTMLElement
    // Both sides are kieriskash's lineup now, so Josh Allen is gone from it.
    expect(within(row).queryByText('Josh Allen')).not.toBeInTheDocument()
  })
})
