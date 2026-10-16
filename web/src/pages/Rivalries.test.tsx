import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Rivalries from './Rivalries'
import { indexPairs, viewFrom, kindSplit } from '../rivalries'
import type {
  Rivalries as RivalriesData,
  RivalryPair,
  RivalryRow,
  RivalryWeekOption,
  RivalryWeekRecommendation,
} from '../api'

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
      week: null,
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

// ---- specs/037-rivalry-week-pick

const weekPairing = (a: number, b: number, text: string, over: { thin?: boolean; already?: boolean } = {}) => ({
  pairing: {
    aManagerId: a, bManagerId: b, meetings: 4, score: 3, thin: over.thin ?? false,
    reason: { code: 'EVEN', text, numbers: {} },
  },
  alreadyScheduled: over.already ?? false,
})

const weekOption = (week: number, over: Partial<RivalryWeekOption> = {}): RivalryWeekOption => ({
  week,
  total: 31.5,
  bestTotal: 34,
  reason: {
    code: 'BEST_UNDER_CAP',
    text: "1 of the best pairings would go over the 2-meeting limit in week 9, so it's swapped out. This set scores 31.5 of a possible 34.",
    numbers: {},
  },
  pairings: [
    weekPairing(1, 2, 'WEEKNINE pair one.', { already: true }),
    weekPairing(3, 4, 'WEEKNINE pair two.'),
  ],
  unpaired: null,
  heldBack: [{ aManagerId: 1, bManagerId: 3, meetsInWeeks: [3, 11], wouldMeet: 3 }],
  displaced: [
    { aManagerId: 1, bManagerId: 3, metInWeeks: [], stillMeetsInWeeks: [16] },
    { aManagerId: 2, bManagerId: 3, metInWeeks: [2], stillMeetsInWeeks: [] },
    { aManagerId: 1, bManagerId: 2, metInWeeks: [], stillMeetsInWeeks: [] },
  ],
  ...over,
})

function weekData(over: Partial<RivalryWeekRecommendation> = {}): RivalriesData {
  const d = data()
  const week: RivalryWeekRecommendation = {
    status: 'OK',
    cap: 2,
    capLabel: 'league rule',
    asOfWeek: 6,
    recommended: weekOption(9),
    others: [
      weekOption(12, {
        total: 30, heldBack: [], displaced: [],
        reason: { code: 'ALL_BEST_FIT', text: 'WEEKTWELVE reason.', numbers: {} },
        pairings: [weekPairing(1, 4, 'WEEKTWELVE pair.')],
      }),
    ],
    unfitWeeks: [],
    overCapNote: null,
    ...over,
  }
  return { ...d, recommendation: { ...d.recommendation, week } }
}

describe('rivalry week pick (spec 037)', () => {
  beforeEach(() => getRivalries.mockReset())

  it('names the week, its reason, the cap rule and the week pairings instead of the history-best set', async () => {
    getRivalries.mockResolvedValue(weekData())
    render(<Rivalries />)
    expect(await screen.findByText('Recommended: Week 9')).toBeInTheDocument()
    expect(screen.getByText(/1 of the best pairings would go over the 2-meeting limit in week 9/)).toBeInTheDocument()
    expect(screen.getByText('Nobody plays the same manager more than 2 times in the regular season (league rule).'))
      .toBeInTheDocument()
    expect(screen.getByText('as of week 6')).toBeInTheDocument()
    expect(screen.getByText('WEEKNINE pair one.')).toBeInTheDocument()
    expect(screen.queryByText('One-sided so far.')).toBeNull()
    expect(screen.getByText(/doesn't change your Sleeper schedule/)).toBeInTheDocument()
  })

  it('lists the other weeks with total against best and shows each week its own pairings', async () => {
    getRivalries.mockResolvedValue(weekData())
    render(<Rivalries />)
    await screen.findByText('Recommended: Week 9')
    const others = screen.getByText('Other weeks').closest('details') as HTMLElement
    expect(within(others).getByText(/Week 12/)).toBeInTheDocument()
    expect(within(others).getByText(/30 of 34/)).toBeInTheDocument()
    expect(within(others).getByText('WEEKTWELVE pair.')).toBeInTheDocument()
  })

  it('tags pairs that are already on the schedule', async () => {
    getRivalries.mockResolvedValue(weekData())
    render(<Rivalries />)
    await screen.findByText('Recommended: Week 9')
    expect(screen.getAllByText('already on the schedule')).toHaveLength(1)
  })

  it('prints the held-back pair own would-be count, not one derived from the cap', async () => {
    getRivalries.mockResolvedValue(weekData({
      cap: 3,
      recommended: weekOption(9, { heldBack: [{ aManagerId: 1, bManagerId: 3, meetsInWeeks: [1, 4, 5], wouldMeet: 4 }] }),
    }))
    render(<Rivalries />)
    await screen.findByText('Recommended: Week 9')
    expect(screen.getByText("Alpha and Charlie met in weeks 1, 4 and 5, so they'd play a fourth time."))
      .toBeInTheDocument()
  })

  it('NO_WEEK_FITS names the real cap, and stays generic without one', async () => {
    getRivalries.mockResolvedValue(weekData({ status: 'NO_WEEK_FITS', recommended: null, others: [], cap: 3 }))
    const { unmount } = render(<Rivalries />)
    expect(await screen.findByText(/more than 3 times, so no week is recommended/)).toBeInTheDocument()
    unmount()
    getRivalries.mockResolvedValue(weekData({ status: 'NO_WEEK_FITS', recommended: null, others: [], cap: null }))
    render(<Rivalries />)
    expect(await screen.findByText(/break the meeting limit, so no week is recommended/)).toBeInTheDocument()
  })

  it('says, per held-back pair, which weeks they met and which are still only scheduled', async () => {
    getRivalries.mockResolvedValue(weekData())
    render(<Rivalries />)
    await screen.findByText('Recommended: Week 9')
    // asOfWeek is 6: week 3 was played, week 11 has not happened yet.
    expect(screen.getByText(
      "Alpha and Charlie met in week 3 and are scheduled to meet in week 11, so they'd play a third time.",
    )).toBeInTheDocument()
  })

  it('says "more than once" for a cap of 1', async () => {
    getRivalries.mockResolvedValue(weekData({ cap: 1 }))
    render(<Rivalries />)
    await screen.findByText('Recommended: Week 9')
    expect(screen.getByText('Nobody plays the same manager more than once in the regular season (league rule).'))
      .toBeInTheDocument()
  })

  it('lists what the week replaces, past and future meetings in words', async () => {
    getRivalries.mockResolvedValue(weekData())
    render(<Rivalries />)
    await screen.findByText('Recommended: Week 9')
    const replaces = screen.getByText('What this replaces').closest('details') as HTMLElement
    expect(within(replaces).getByText(/Alpha vs Charlie/)).toBeInTheDocument()
    expect(within(replaces).getByText(/still meet in week 16/)).toBeInTheDocument()
    expect(within(replaces).getByText(/already met in week 2/)).toBeInTheDocument()
    expect(within(replaces).getByText(/they wouldn't meet otherwise this season/)).toBeInTheDocument()
  })

  it('names the weeks no full set fits in, and any over-cap note', async () => {
    getRivalries.mockResolvedValue(weekData({ unfitWeeks: [18, 19], overCapNote: 'Alpha and Bravo meet 3 times.' }))
    render(<Rivalries />)
    await screen.findByText('Recommended: Week 9')
    expect(screen.getByText('No full set fits in weeks 18 and 19.')).toBeInTheDocument()
    expect(screen.getByText('Alpha and Bravo meet 3 times.')).toBeInTheDocument()
  })

  it('counts thin history on the pairings it shows, not on the history-best set', async () => {
    // rec.thinCount is 1 of 2 (not more than half); the shown week has 2 thin of 2.
    getRivalries.mockResolvedValue(weekData({
      recommended: weekOption(9, {
        pairings: [weekPairing(1, 2, 'a', { thin: true }), weekPairing(3, 4, 'b', { thin: true })],
      }),
    }))
    render(<Rivalries />)
    expect(await screen.findByText(/2 of 2\) rest on thin history/)).toBeInTheDocument()
  })

  it('falls back to spec 034 when the backend predates the week field (undefined, not null)', async () => {
    const d = data()
    const old = { ...d, recommendation: { ...d.recommendation } } as unknown as { recommendation: Record<string, unknown> }
    delete old.recommendation.week
    getRivalries.mockResolvedValue(old)
    render(<Rivalries />)
    expect(await screen.findByText('One-sided so far.')).toBeInTheDocument()
    expect(screen.queryByText(/Recommended: Week/)).toBeNull()
    expect(screen.queryByText('Best pairings from history')).toBeNull()
  })

  it.each([
    ['NOT_PUBLISHED', "A week can't be recommended until the schedule is published."],
    ['NO_SEASON_FORMAT', "This league's settings don't say where the regular season ends, so no week is recommended."],
    ['NO_ELIGIBLE_WEEK', 'No regular-season week is left to recommend.'],
    ['NO_WEEK_FITS', 'Every remaining week would make someone play the same manager more than 2 times, so no week is recommended.'],
    ['UNSUPPORTED_ROSTERS', "A week can't be recommended because not every team has exactly one manager this season."],
    ['CONFIG_MISSING', "A week can't be recommended because this app's meeting-limit setting didn't load."],
  ] as const)('%s prints its sentence, then the history-best pairings', async (status, sentence) => {
    getRivalries.mockResolvedValue(weekData({
      status, recommended: null, others: [], cap: status === 'CONFIG_MISSING' ? null : 2,
    }))
    render(<Rivalries />)
    expect(await screen.findByText(sentence)).toBeInTheDocument()
    expect(screen.getByText('Best pairings from history')).toBeInTheDocument()
    expect(screen.getByText('One-sided so far.')).toBeInTheDocument()
    expect(screen.queryByText(/Recommended: Week/)).toBeNull()
    expect(screen.getByText(/doesn't change your Sleeper schedule/)).toBeInTheDocument()
  })
})
