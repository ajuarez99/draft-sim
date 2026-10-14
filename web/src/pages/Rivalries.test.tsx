import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Rivalries from './Rivalries'
import { indexPairs, viewFrom, kindSplit } from '../rivalries'
import type { Rivalries as RivalriesData, RivalryPair, RivalryRow } from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
  Link: ({ children, to, title, className }: { children: React.ReactNode; to: string; title?: string; className?: string }) => (
    <a href={to} title={title} className={className}>{children}</a>
  ),
}))

const getRivalries = vi.fn()
vi.mock('../api', () => ({
  getRivalries: (...args: unknown[]) => getRivalries(...args),
}))

const row = (managerId: number, teamName: string, over: Partial<RivalryRow> = {}): RivalryRow => ({
  managerId,
  managerName: `user${managerId}`,
  teamName,
  avatarId: null,
  isMe: false,
  allOpponents: { wins: 0, losses: 0, ties: 0, meetings: 0 },
  formerOpponentMeetings: 0,
  ...over,
})

const noKinds = { aWins: 0, bWins: 0, ties: 0 }
const pair = (a: number, b: number, over: Partial<RivalryPair> = {}): RivalryPair => ({
  aManagerId: a,
  bManagerId: b,
  meetings: 0,
  aWins: 0,
  bWins: 0,
  ties: 0,
  byKind: { REGULAR: noKinds, PLAYOFF: noKinds, PLACEMENT: noKinds, CONSOLATION: noKinds, POSTSEASON_UNSPLIT: noKinds },
  status: 'NEVER_MET',
  finals: [],
  games: [],
  ...over,
})

function data(over: Partial<RivalriesData> = {}): RivalriesData {
  return {
    sport: 'nba',
    season: 2026,
    seasonsCovered: [2025, 2026],
    matrix: {
      rows: [
        row(1, 'Alpha', { isMe: true, allOpponents: { wins: 5, losses: 2, ties: 0, meetings: 7 }, formerOpponentMeetings: 2 }),
        row(2, 'Bravo'),
        row(3, 'Charlie'),
      ],
      pairs: [
        pair(1, 2, {
          meetings: 7, aWins: 5, bWins: 2, status: 'MET',
          byKind: { ...pair(1, 2).byKind, REGULAR: { aWins: 4, bWins: 2, ties: 0 }, PLAYOFF: { aWins: 1, bWins: 0, ties: 0 } },
        }),
        pair(1, 3, { status: 'NOT_YET_MET' }),
        pair(2, 3, { status: 'NEVER_MET' }),
      ],
      splitNotes: [{ season: 2025, reason: 'NO_BRACKET', text: 'The 2025 playoff weeks cannot be split.' }],
    },
    seasonGrid: {
      scheduleStatus: 'OK',
      currentWeek: 2,
      weeks: [{ week: 1, isPlayoffWeek: false }, { week: 2, isPlayoffWeek: false }, { week: 3, isPlayoffWeek: true }],
      playoffWindowNote: null,
      tbdNote: 'Playoff pairings appear once the week has been scored.',
      medianNote: null,
      rosterNote: null,
      cells: [
        { managerId: 1, week: 1, state: 'OPPONENT', source: 'SCHEDULE', opponentManagerId: 2,
          priorRecord: { wins: 3, losses: 1, ties: 0, meetings: 4 },
          result: { points: 120.5, opponentPoints: 99, outcome: 'W', kind: 'REGULAR', bracketDisagrees: false } },
        { managerId: 1, week: 2, state: 'BYE', source: null, opponentManagerId: null, priorRecord: null, result: null },
        { managerId: 1, week: 3, state: 'TBD', source: null, opponentManagerId: null, priorRecord: null, result: null },
      ],
    },
    recommendation: {
      status: 'OK',
      minMeetings: 3,
      minMeetingsLabel: 'hand-set',
      pairings: [
        { aManagerId: 1, bManagerId: 2, meetings: 7, score: 2, thin: false,
          reason: { code: 'LOPSIDED', text: 'One-sided so far.', numbers: {} } },
        { aManagerId: 3, bManagerId: 4, meetings: 1, score: 1, thin: true,
          reason: { code: 'THIN', text: 'Met once.', numbers: {} } },
      ],
      unpaired: { managerId: 2, why: 'Leaving Bravo out gives the highest total.' },
      thinCount: 1,
      note: "This is a suggestion for a rivalry week; it doesn't change your Sleeper schedule.",
    },
    ...over,
  }
}

const matrixRow = (team: string) => screen.getAllByRole('rowheader', { name: team })[0].closest('tr') as HTMLElement

describe('mirror lookup', () => {
  const index = indexPairs([pair(1, 2, { meetings: 8, aWins: 5, bWins: 2, ties: 1, status: 'MET' })])

  it('reads the pair from either side', () => {
    expect(viewFrom(index, 1, 2)).toMatchObject({ wins: 5, losses: 2, ties: 1 })
    expect(viewFrom(index, 2, 1)).toMatchObject({ wins: 2, losses: 5, ties: 1 })
    expect(viewFrom(index, 1, 3)).toBeNull()
  })

  it('splits game types from the row side too', () => {
    const p = pair(1, 2, { meetings: 3, aWins: 2, bWins: 1, status: 'MET', byKind: { ...pair(1, 2).byKind, REGULAR: { aWins: 2, bWins: 1, ties: 0 } } })
    const i = indexPairs([p])
    expect(kindSplit(viewFrom(i, 1, 2)!)).toBe('Regular season 2-1')
    expect(kindSplit(viewFrom(i, 2, 1)!)).toBe('Regular season 1-2')
  })
})

describe('Rivalries page', () => {
  beforeEach(() => getRivalries.mockReset())

  it('prints the exact record from each side, mirrored, with the meeting count', async () => {
    getRivalries.mockResolvedValue(data())
    render(<Rivalries />)
    await screen.findByText('All-time head-to-head')
    // Alpha (id 1) vs Bravo (id 2): 5-2 for Alpha, 2-5 for Bravo, both "7 met".
    const alpha = within(matrixRow('Alpha')).getByRole('link', { name: /5-2/ })
    expect(alpha).toHaveTextContent('7 met')
    expect(alpha).toHaveAttribute('href', '/managers/1/versus/2')
    const bravo = within(matrixRow('Bravo')).getByRole('link', { name: /2-5/ })
    expect(bravo).toHaveAttribute('href', '/managers/2/versus/1')
    expect(alpha).toHaveAttribute('title', 'Regular season 4-2, Playoff 1-0')
    expect(bravo).toHaveAttribute('title', 'Regular season 2-4, Playoff 0-1')
  })

  it('words the unmet statuses, shows the vs-everyone total and the split note', async () => {
    getRivalries.mockResolvedValue(data())
    render(<Rivalries />)
    await screen.findByText('All-time head-to-head')
    expect(within(matrixRow('Alpha')).getByText('not yet — scheduled')).toBeInTheDocument()
    expect(within(matrixRow('Bravo')).getByText('never met')).toBeInTheDocument()
    expect(within(matrixRow('Alpha')).getByText(/2 of those against managers no longer in the league/)).toBeInTheDocument()
    expect(screen.getByText('The 2025 playoff weeks cannot be split.')).toBeInTheDocument()
  })

  it('shows the season grid: prior record, result, bye, TBD and the notes', async () => {
    getRivalries.mockResolvedValue(data())
    render(<Rivalries />)
    await screen.findByText('2026 schedule')
    expect(screen.getByText('3-1 before')).toBeInTheDocument()
    expect(screen.getByText('W 120.50–99.00')).toBeInTheDocument()
    expect(screen.getByText('BYE')).toBeInTheDocument()
    expect(screen.getByText('TBD')).toBeInTheDocument()
    expect(screen.getByText('Playoff pairings appear once the week has been scored.')).toBeInTheDocument()
    expect(screen.getByText('this week')).toBeInTheDocument()
    expect(screen.getByText('playoffs')).toBeInTheDocument()
  })

  it('prints a sentence instead of an empty grid when the schedule is not published', async () => {
    const d = data()
    getRivalries.mockResolvedValue({ ...d, seasonGrid: { ...d.seasonGrid, scheduleStatus: 'NOT_PUBLISHED', weeks: [], cells: [] } })
    render(<Rivalries />)
    await screen.findByText(/hasn't published this season's schedule/)
    expect(screen.queryByText('Week 1')).toBeNull()
  })

  it('labels thin pairings, prints N as hand-set, the unpaired why and the Sleeper note', async () => {
    getRivalries.mockResolvedValue(data())
    render(<Rivalries />)
    await screen.findByText('Rivalry week')
    expect(screen.getByText('thin history')).toBeInTheDocument()
    expect(screen.getByText(/at least 3 meetings \(hand-set\)/)).toBeInTheDocument()
    expect(screen.getByText(/Leaving Bravo out gives the highest total/)).toBeInTheDocument()
    expect(screen.getByText(/doesn't change your Sleeper schedule/)).toBeInTheDocument()
    // 1 thin of 2 is not more than half.
    expect(screen.queryByText(/rest on thin history/)).toBeNull()
  })

  it('adds a headline when more than half the pairings are thin', async () => {
    const d = data()
    getRivalries.mockResolvedValue({ ...d, recommendation: { ...d.recommendation, thinCount: 2 } })
    render(<Rivalries />)
    expect(await screen.findByText(/2 of 2\) rest on thin history/)).toBeInTheDocument()
  })

  it('prints a sentence for a refused recommendation and still shows the note', async () => {
    const d = data()
    getRivalries.mockResolvedValue({
      ...d,
      recommendation: { ...d.recommendation, status: 'NO_HISTORY', pairings: [], unpaired: null, minMeetings: 3 },
    })
    render(<Rivalries />)
    await screen.findByText(/no earlier season/)
    expect(screen.queryByText('thin history')).toBeNull()
    expect(screen.getByText(/doesn't change your Sleeper schedule/)).toBeInTheDocument()
  })
})

describe('review fixes', () => {
  beforeEach(() => getRivalries.mockReset())

  function withCells(cells: RivalriesData['seasonGrid']['cells'], extra: Partial<RivalriesData['seasonGrid']> = {}) {
    const d = data()
    return { ...d, seasonGrid: { ...d.seasonGrid, cells, ...extra } }
  }

  it('B5: prints two decimals so a narrow win never reads as a tie', async () => {
    getRivalries.mockResolvedValue(withCells([
      { managerId: 1, week: 1, state: 'OPPONENT', source: 'SCHEDULE', opponentManagerId: 2,
        priorRecord: { wins: 0, losses: 0, ties: 0, meetings: 0 },
        result: { points: 120.24, opponentPoints: 120.16, outcome: 'W', kind: 'REGULAR', bracketDisagrees: false } },
    ]))
    render(<Rivalries />)
    expect(await screen.findByText('W 120.24–120.16')).toBeInTheDocument()
  })

  it('B6: an opponent roster with no manager is an unowned roster, not a former manager', async () => {
    getRivalries.mockResolvedValue(withCells([
      { managerId: 1, week: 1, state: 'OPPONENT', source: 'SCHEDULE', opponentManagerId: null,
        priorRecord: null, result: null },
    ]))
    render(<Rivalries />)
    expect(await screen.findByText('an unowned roster')).toBeInTheDocument()
    expect(screen.queryByText('a former manager')).toBeNull()
  })

  it('B8: shows the extra-roster note', async () => {
    getRivalries.mockResolvedValue(withCells([], { rosterNote: 'One manager owns more than one team this season.' }))
    render(<Rivalries />)
    expect(await screen.findByText('One manager owns more than one team this season.')).toBeInTheDocument()
  })

  it('B2: flags a final whose stored scores disagree with the bracket, in the grid and the matrix', async () => {
    const d = withCells([
      { managerId: 1, week: 3, state: 'OPPONENT', source: 'SCHEDULE', opponentManagerId: 2,
        priorRecord: { wins: 1, losses: 0, ties: 0, meetings: 1 },
        result: { points: 234, opponentPoints: 199, outcome: 'W', kind: 'PLAYOFF', bracketDisagrees: true } },
    ])
    d.matrix.pairs[0] = {
      ...d.matrix.pairs[0],
      finals: [{ season: 2025, aPoints: 234, bPoints: 199, winner: 'B', twoWeek: false, scoresAgreeWithWinner: false }],
    }
    getRivalries.mockResolvedValue(d)
    render(<Rivalries />)
    await screen.findByText('2026 schedule')
    // grid: Alpha won on the stored scores, so the bracket gave it to Bravo
    expect(screen.getByText("Sleeper's bracket gives this final to Bravo; the stored scores say otherwise.")).toBeInTheDocument()
    // matrix: Alpha's cell title names the winner from Alpha's side
    const alpha = within(matrixRow('Alpha')).getByRole('link', { name: /5-2/ })
    expect(alpha.getAttribute('title')).toContain("2025: Sleeper's bracket gives the final to Bravo; the stored scores say otherwise.")
  })
})
