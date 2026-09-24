import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Superlatives from './Superlatives'
import type { ConductList, Superlative, SuperlativesResponse } from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ sleeperLeagueId: 'L1' }),
}))

const fetchSuperlatives = vi.fn()
const fetchConductList = vi.fn()
const saveConductEntry = vi.fn()
const deleteConductEntry = vi.fn()
vi.mock('../api', () => ({
  fetchSuperlatives: (...args: unknown[]) => fetchSuperlatives(...args),
  fetchConductList: (...args: unknown[]) => fetchConductList(...args),
  saveConductEntry: (...args: unknown[]) => saveConductEntry(...args),
  deleteConductEntry: (...args: unknown[]) => deleteConductEntry(...args),
}))

function conductList(over: Partial<ConductList> = {}): ConductList {
  return { canEdit: false, commissionerKnown: true, entries: [], ...over }
}

const KINDS = [
  'HIGHEST_WEEK', 'LOWEST_WEEK', 'BIGGEST_BLOWOUT', 'CLOSEST_GAME', 'CLOSE_WINS', 'CLOSE_LOSSES',
  'LUCKIEST', 'UNLUCKIEST', 'MOST_BENCH_POINTS', 'WAIVER_WIRE_WARRIOR', 'JOEL_EMBIID', 'UNETHICAL',
] as const

function notBuiltYet(kind: (typeof KINDS)[number]): Superlative {
  return {
    kind,
    available: false,
    reason: 'not built yet',
    early: false,
    value: null,
    unit: null,
    holders: [],
    emptyReason: null,
    detail: [],
    coverage: null,
  }
}

function baseline(): SuperlativesResponse {
  return {
    available: true,
    season: 2026,
    requestedSeason: null,
    sport: 'nfl',
    throughWeek: 6,
    weeksScored: 6,
    regularSeasonEnd: 14,
    early: false,
    earlyThresholdWeeks: 4,
    closeGameMargin: 10,
    suspensionWeeksObserved: [],
    commissionerListAvailable: false,
    superlatives: KINDS.map(notBuiltYet),
    leagueSleeperId: 'L1',
  }
}

function withKind(data: SuperlativesResponse, superlative: Superlative): SuperlativesResponse {
  return {
    ...data,
    superlatives: data.superlatives.map((s) => (s.kind === superlative.kind ? superlative : s)),
  }
}

describe('Superlatives', () => {
  beforeEach(() => {
    fetchSuperlatives.mockReset()
    fetchConductList.mockReset().mockResolvedValue(conductList())
    saveConductEntry.mockReset()
    deleteConductEntry.mockReset()
  })

  it('shows the payload reason when the whole page is unavailable', async () => {
    fetchSuperlatives.mockResolvedValue({
      available: false,
      reason: 'no week of this season has been scored yet',
      season: 2026,
      requestedSeason: null,
      sport: 'nfl',
      throughWeek: null,
      weeksScored: 0,
      regularSeasonEnd: null,
      early: false,
      earlyThresholdWeeks: 4,
      closeGameMargin: 10,
      suspensionWeeksObserved: [],
      commissionerListAvailable: false,
      superlatives: [],
      leagueSleeperId: 'L1',
    })
    render(<Superlatives />)

    expect(await screen.findByText(/no week of this season has been scored yet/)).toBeInTheDocument()
  })

  it('renders every kind with its own reason, never blank, when nothing is built yet', async () => {
    fetchSuperlatives.mockResolvedValue(baseline())
    render(<Superlatives />)

    expect(await screen.findByText('Highest week')).toBeInTheDocument()
    const reasons = await screen.findAllByText('not built yet')
    expect(reasons).toHaveLength(KINDS.length)
  })

  it('renders both names when two teams tie for a superlative', async () => {
    const tied: Superlative = {
      kind: 'HIGHEST_WEEK',
      available: true,
      reason: null,
      early: false,
      value: 180.5,
      unit: 'POINTS',
      holders: [
        { rosterId: 1, managerId: 10, teamName: 'Team A', avatarId: null },
        { rosterId: 2, managerId: 20, teamName: 'Team B', avatarId: null },
      ],
      emptyReason: null,
      detail: [
        { type: 'WEEK_SCORE', week: 3, rosterId: 1, points: 180.5 },
        { type: 'WEEK_SCORE', week: 4, rosterId: 2, points: 180.5 },
      ],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), tied))
    render(<Superlatives />)

    expect(await screen.findByText('Team A')).toBeInTheDocument()
    expect(screen.getByText('Team B')).toBeInTheDocument()
  })

  it('prints the payload margin on the close-wins card, not a hardcoded number', async () => {
    const closeWins: Superlative = {
      kind: 'CLOSE_WINS',
      available: true,
      reason: null,
      early: false,
      value: 3,
      unit: 'WINS',
      holders: [{ rosterId: 4, managerId: 17, teamName: 'Escape Artists', avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'GAME', week: 2, rosterId: 4, opponentRosterId: 9, opponentTeamName: 'Others',
          points: 118.4, opponentPoints: 112.1, margin: 6.3,
        },
      ],
      coverage: null,
    }
    const data = withKind(baseline(), closeWins)
    data.closeGameMargin = 15
    fetchSuperlatives.mockResolvedValue(data)
    render(<Superlatives />)

    expect(await screen.findByText(/by under 15 points/)).toBeInTheDocument()
  })

  it('renders a coverage note as "N of M weeks"', async () => {
    const blowout: Superlative = {
      kind: 'BIGGEST_BLOWOUT',
      available: true,
      reason: null,
      early: false,
      value: 55.2,
      unit: 'POINTS',
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Team A', avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'GAME', week: 2, rosterId: 1, opponentRosterId: 2, opponentTeamName: 'Team B',
          points: 150, opponentPoints: 94.8, margin: 55.2,
        },
      ],
      coverage: { weeksCovered: 5, weeksExcluded: 1, reasons: ['week 3: no pairings stored'] },
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), blowout))
    render(<Superlatives />)

    expect(await screen.findByText(/5 of 6 weeks/)).toBeInTheDocument()
  })

  it('shows the early caveat beside the card title when a kind is early', async () => {
    const luckiest: Superlative = {
      kind: 'LUCKIEST',
      available: true,
      reason: null,
      early: true,
      value: 2.4,
      unit: 'WINS',
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Team A', avatarId: null }],
      emptyReason: null,
      detail: [],
      coverage: null,
    }
    const data = baseline()
    data.early = true
    fetchSuperlatives.mockResolvedValue(withKind(data, luckiest))
    render(<Superlatives />)

    expect(await screen.findByText(/early — this is mostly noise/)).toBeInTheDocument()
  })

  it('renders an empty reason instead of naming a team with zero close games', async () => {
    const closeWins: Superlative = {
      kind: 'CLOSE_WINS',
      available: true,
      reason: null,
      early: false,
      value: null,
      unit: 'WINS',
      holders: [],
      emptyReason: 'no close games yet',
      detail: [],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), closeWins))
    render(<Superlatives />)

    expect(await screen.findByText('no close games yet')).toBeInTheDocument()
  })

  it('states the regular season window in the header', async () => {
    fetchSuperlatives.mockResolvedValue(baseline())
    render(<Superlatives />)

    expect(await screen.findByText(/through week 6/)).toBeInTheDocument()
    expect(screen.getByText(/Regular season/)).toBeInTheDocument()
  })

  it('says every scored week counts when there is no playoff start set', async () => {
    const data = baseline()
    data.regularSeasonEnd = null
    fetchSuperlatives.mockResolvedValue(data)
    render(<Superlatives />)

    expect(await screen.findByText(/every scored week counts/)).toBeInTheDocument()
  })

  // FR-002 (2026-09-23 live-check finding): the week must be visible on the
  // card itself, not only after expanding "Games".
  it('shows the week on the card itself, without expanding "Games"', async () => {
    const highest: Superlative = {
      kind: 'HIGHEST_WEEK',
      available: true,
      reason: null,
      early: false,
      value: 164.96,
      unit: 'POINTS',
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Master Bates', avatarId: null }],
      emptyReason: null,
      detail: [{ type: 'WEEK_SCORE', week: 1, rosterId: 1, points: 164.96 }],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), highest))
    render(<Superlatives />)

    const line = await screen.findByText('164.96 points · week 1')
    expect(line.closest('details')).toBeNull()
  })

  it('names the opponent inline for a game kind, alongside the figure and week', async () => {
    const blowout: Superlative = {
      kind: 'BIGGEST_BLOWOUT',
      available: true,
      reason: null,
      early: false,
      value: 99.68,
      unit: 'POINTS',
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Team A', avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'GAME', week: 2, rosterId: 1, opponentRosterId: 2,
          opponentTeamName: 'Dart has hit anotha Bower', points: 150, opponentPoints: 50.32, margin: 99.68,
        },
      ],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), blowout))
    render(<Superlatives />)

    const line = await screen.findByText('99.68 points over Dart has hit anotha Bower · week 2')
    expect(line.closest('details')).toBeNull()
  })

  it('gives each tied holder its own week/opponent rather than one shared figure', async () => {
    const closest: Superlative = {
      kind: 'CLOSEST_GAME',
      available: true,
      reason: null,
      early: false,
      value: 0.5,
      unit: 'POINTS',
      holders: [
        { rosterId: 1, managerId: 10, teamName: 'Team A', avatarId: null },
        { rosterId: 2, managerId: 20, teamName: 'Team B', avatarId: null },
        { rosterId: 3, managerId: 30, teamName: 'Team C', avatarId: null },
      ],
      emptyReason: null,
      detail: [
        { type: 'GAME', week: 1, rosterId: 1, opponentRosterId: 9, opponentTeamName: 'X', points: 100, opponentPoints: 99.5, margin: 0.5 },
        { type: 'GAME', week: 4, rosterId: 2, opponentRosterId: 8, opponentTeamName: 'Y', points: 88, opponentPoints: 87.5, margin: 0.5 },
        { type: 'GAME', week: 7, rosterId: 3, opponentRosterId: 7, opponentTeamName: 'Z', points: 77, opponentPoints: 76.5, margin: 0.5 },
      ],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), closest))
    render(<Superlatives />)

    expect(await screen.findByText(/week 1/)).toBeInTheDocument()
    expect(screen.getByText(/week 4/)).toBeInTheDocument()
    expect(screen.getByText(/week 7/)).toBeInTheDocument()
  })

  it('lists a close-game holder\'s weeks inline in short form', async () => {
    const closeWins: Superlative = {
      kind: 'CLOSE_WINS',
      available: true,
      reason: null,
      early: false,
      value: 3,
      unit: 'WINS',
      holders: [{ rosterId: 4, managerId: 17, teamName: 'Escape Artists', avatarId: null }],
      emptyReason: null,
      detail: [
        { type: 'GAME', week: 4, rosterId: 4, opponentRosterId: 1, opponentTeamName: 'X', points: 100, opponentPoints: 95, margin: 5 },
        { type: 'GAME', week: 9, rosterId: 4, opponentRosterId: 2, opponentTeamName: 'Y', points: 101, opponentPoints: 97, margin: 4 },
        { type: 'GAME', week: 13, rosterId: 4, opponentRosterId: 3, opponentTeamName: 'Z', points: 102, opponentPoints: 99, margin: 3 },
      ],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), closeWins))
    render(<Superlatives />)

    expect(await screen.findByText('weeks 4, 9, 13')).toBeInTheDocument()
  })

  // Text spans nodes (the payload's numeric margin is its own interpolated
  // text node), so these two assert on the value line's full textContent
  // directly rather than via a cross-node RTL text query.
  it('reads "loss" singular on the close-losses card', async () => {
    const closeLosses: Superlative = {
      kind: 'CLOSE_LOSSES',
      available: true,
      reason: null,
      early: false,
      value: 1,
      unit: 'GAMES',
      holders: [{ rosterId: 6, managerId: 60, teamName: 'Heartbreak Kid', avatarId: null }],
      emptyReason: null,
      detail: [
        { type: 'GAME', week: 2, rosterId: 6, opponentRosterId: 1, opponentTeamName: 'X', points: 95, opponentPoints: 100, margin: 5 },
      ],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), closeLosses))
    const { container } = render(<Superlatives />)

    const valueEl = await screen.findByText('1 loss')
    expect(valueEl.closest('.sl-value')?.textContent).toBe('1 loss by under 10 points')
    expect(container.querySelector('.sl-value')?.textContent).not.toContain('losses')
  })

  it('reads "losses" plural on the close-losses card for a count above one', async () => {
    const closeLosses: Superlative = {
      kind: 'CLOSE_LOSSES',
      available: true,
      reason: null,
      early: false,
      value: 3,
      unit: 'GAMES',
      holders: [{ rosterId: 6, managerId: 60, teamName: 'Heartbreak Kid', avatarId: null }],
      emptyReason: null,
      detail: [
        { type: 'GAME', week: 2, rosterId: 6, opponentRosterId: 1, opponentTeamName: 'X', points: 95, opponentPoints: 100, margin: 5 },
        { type: 'GAME', week: 5, rosterId: 6, opponentRosterId: 2, opponentTeamName: 'Y', points: 96, opponentPoints: 99, margin: 3 },
        { type: 'GAME', week: 8, rosterId: 6, opponentRosterId: 3, opponentTeamName: 'Z', points: 97, opponentPoints: 98, margin: 1 },
      ],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), closeLosses))
    render(<Superlatives />)

    const valueEl = await screen.findByText('3 losses')
    expect(valueEl.closest('.sl-value')?.textContent).toBe('3 losses by under 10 points')
  })

  // T035 (US2): the reading is the figure itself and belongs inline, next to
  // the span it covers -- not folded into the generic value line.
  it('renders the luck reading inline on the holder line', async () => {
    const luckiest: Superlative = {
      kind: 'LUCKIEST',
      available: true,
      reason: null,
      early: false,
      value: 2.4,
      unit: 'WINS',
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Team A', avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'LUCK', rosterId: 1, actualWins: 5, expectedWins: 2.6, winsAboveExpected: 2.4,
          swingWeeks: [{ week: 3, result: 'WON', points: 88.4, weeklyRank: 9, opponent: 'Team B' }],
          fromWeek: 1, throughWeek: 6, reading: '2.40 more wins than their scores earned',
        },
      ],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), luckiest))
    render(<Superlatives />)

    expect(
      await screen.findByText('2.40 more wins than their scores earned · weeks 1–6'),
    ).toBeInTheDocument()
  })

  it('puts swing weeks in the expandable detail, not on the card itself', async () => {
    const luckiest: Superlative = {
      kind: 'LUCKIEST',
      available: true,
      reason: null,
      early: false,
      value: 2.4,
      unit: 'WINS',
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Team A', avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'LUCK', rosterId: 1, actualWins: 5, expectedWins: 2.6, winsAboveExpected: 2.4,
          swingWeeks: [{ week: 3, result: 'WON', points: 88.4, weeklyRank: 9, opponent: 'Team B' }],
          fromWeek: 1, throughWeek: 6, reading: '2.40 more wins than their scores earned',
        },
      ],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), luckiest))
    render(<Superlatives />)

    const figure = await screen.findByText('2.40 more wins than their scores earned · weeks 1–6')
    expect(figure.closest('details')).toBeNull()

    const swingWeek = screen.getByText(/9th that week/)
    expect(swingWeek.closest('details')).not.toBeNull()
    expect(screen.getByText('Swing weeks').tagName.toLowerCase()).toBe('summary')
  })

  it('renders the bench span and worst week inline on the holder line', async () => {
    const bench: Superlative = {
      kind: 'MOST_BENCH_POINTS',
      available: true,
      reason: null,
      early: false,
      value: 210.55,
      unit: 'POINTS',
      holders: [{ rosterId: 2, managerId: 20, teamName: 'Bench Warmers', avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'BENCH_TOTAL', rosterId: 2, pointsLeft: 210.55, weeksCounted: 6,
          fromWeek: 1, throughWeek: 6, biggestWeek: { week: 4, pointsLeft: 55.2 },
        },
      ],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), bench))
    render(<Superlatives />)

    expect(
      await screen.findByText('210.55 points left on the bench · weeks 1–6 · worst: week 4 (55.20)'),
    ).toBeInTheDocument()
  })

  // T040 (US3): the total line and each top pickup render inline on the
  // holder's own line, tied by the pickup row's rosterId.
  it('renders the waiver pickup total and each top pickup inline', async () => {
    const waiver: Superlative = {
      kind: 'WAIVER_WIRE_WARRIOR',
      available: true,
      reason: null,
      early: false,
      value: 88.4,
      unit: 'POINTS',
      holders: [{ rosterId: 3, managerId: 30, teamName: 'Waiver Wire Warriors', avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'PICKUP', rosterId: 3, playerId: 'p1', playerName: 'Some Guy', position: 'RB',
          addedWeek: 4, addType: 'WAIVER', startedWeeks: [5, 6], points: 52.1,
        },
        {
          type: 'PICKUP', rosterId: 3, playerId: 'p2', playerName: 'Another Guy', position: 'WR',
          addedWeek: 2, addType: 'FREE_AGENT', startedWeeks: [3], points: 36.3,
        },
      ],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), waiver))
    render(<Superlatives />)

    expect(
      await screen.findByText('88.40 points from waiver pickups · weeks 1–6'),
    ).toBeInTheDocument()
    expect(
      screen.getByText('Some Guy (RB) — 52.10 pts, added week 4 off waivers, started weeks 5, 6'),
    ).toBeInTheDocument()
    expect(
      screen.getByText('Another Guy (WR) — 36.30 pts, added week 2 off free agency, started week 3'),
    ).toBeInTheDocument()
  })

  it('shows the unavailable reason for waiver wire warrior like any other kind', async () => {
    const waiver: Superlative = {
      kind: 'WAIVER_WIRE_WARRIOR',
      available: false,
      reason: 'no transactions stored for this season — run POST /api/ingest/transactions/{id}',
      early: false,
      value: null,
      unit: null,
      holders: [],
      emptyReason: null,
      detail: [],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), waiver))
    render(<Superlatives />)

    expect(
      await screen.findByText(/no transactions stored for this season/),
    ).toBeInTheDocument()
  })

  // T049 (US4): the "cause unknown" line is unconditional -- present even
  // before any holder is checked -- and "estimated" is said on the total
  // and on every player line, never left to be assumed once.
  it('shows "cause unknown" and marks the Embiid costs estimated', async () => {
    const embiid: Superlative = {
      kind: 'JOEL_EMBIID',
      available: true,
      reason: null,
      early: false,
      value: 62.4,
      unit: 'POINTS',
      holders: [{ rosterId: 5, managerId: 50, teamName: 'Process Trusters', avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'ABSENCE', rosterId: 5, playerId: 'e1', playerName: 'The Process', position: 'C',
          gamesMissed: 6, weeksAffected: 5, pointsPerGame: 10.4, estimatedPointsLost: 62.4, estimated: true,
        },
      ],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), embiid))
    render(<Superlatives />)

    expect(await screen.findByText('Games missed, cause unknown')).toBeInTheDocument()
    expect(screen.getByText('62.40 estimated points lost')).toBeInTheDocument()
    expect(
      screen.getByText('The Process (C) — 6 games missed (5 weeks), ~10.40 per game (estimated)'),
    ).toBeInTheDocument()
  })

  it('shows the "cause unknown" line even when nobody qualifies for the Embiid award', async () => {
    const embiid: Superlative = {
      kind: 'JOEL_EMBIID',
      available: true,
      reason: null,
      early: false,
      value: null,
      unit: 'POINTS',
      holders: [],
      emptyReason: "nobody's been bitten yet",
      detail: [],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), embiid))
    render(<Superlatives />)

    expect(await screen.findByText('Games missed, cause unknown')).toBeInTheDocument()
    expect(screen.getByText("nobody's been bitten yet")).toBeInTheDocument()
  })

  // T060 (US6): the tracking-began line, from the payload's own
  // suspensionWeeksObserved, not a hardcoded week.
  it('shows when suspension tracking began, from the payload', async () => {
    const data = baseline()
    data.suspensionWeeksObserved = [3, 4, 6]
    fetchSuperlatives.mockResolvedValue(data)
    render(<Superlatives />)

    expect(await screen.findByText('tracking began week 3')).toBeInTheDocument()
  })

  // The backend now lists only SCORED weeks in suspensionWeeksObserved, so
  // an empty list is the ordinary early-season case, not an error (2026-09-23).
  it('says tracking has not covered a scored week yet when suspensionWeeksObserved is empty', async () => {
    fetchSuperlatives.mockResolvedValue(baseline())
    render(<Superlatives />)

    expect(
      await screen.findByText("suspension tracking hasn't covered a scored week yet"),
    ).toBeInTheDocument()
  })

  // Live-check finding (2026-09-23): when nobody qualifies, the backend's own
  // emptyReason already says the tracking-empty sentence ("...and the
  // commissioner's list is empty"), so the separate fallback tracking line
  // must not repeat it -- the sentence should appear exactly once on the card.
  it('does not repeat the tracking sentence when the backend\'s emptyReason already says it', async () => {
    const unethical: Superlative = {
      kind: 'UNETHICAL',
      available: true,
      reason: null,
      early: false,
      value: null,
      unit: null,
      holders: [],
      emptyReason: "suspension tracking hasn't covered a scored week yet, and the commissioner's list is empty",
      detail: [],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), unethical))
    render(<Superlatives />)

    const matches = await screen.findAllByText(/suspension tracking hasn't covered a scored week yet/)
    expect(matches).toHaveLength(1)
    expect(matches[0]).toHaveTextContent(
      "suspension tracking hasn't covered a scored week yet, and the commissioner's list is empty",
    )
  })

  it('still shows "tracking began week N" beside an emptyReason, since it adds information', async () => {
    const unethical: Superlative = {
      kind: 'UNETHICAL',
      available: true,
      reason: null,
      early: false,
      value: null,
      unit: null,
      holders: [],
      emptyReason: 'no suspensions or commissioner entries yet',
      detail: [],
      coverage: null,
    }
    const data = baseline()
    data.suspensionWeeksObserved = [3, 4, 6]
    fetchSuperlatives.mockResolvedValue(withKind(data, unethical))
    render(<Superlatives />)

    expect(await screen.findByText('tracking began week 3')).toBeInTheDocument()
    expect(screen.getByText('no suspensions or commissioner entries yet')).toBeInTheDocument()
  })

  // FR-002 (2026-09-23 live-check finding): the weeks a CONDUCT row counted
  // for print on BOTH sources, not just SUSPENDED, and consecutive weeks
  // compress into a range rather than a raw comma list.
  it('renders Unethical Award rows with weeks on both sources, compressing a consecutive run', async () => {
    const unethical: Superlative = {
      kind: 'UNETHICAL',
      available: true,
      reason: null,
      early: false,
      value: 2,
      unit: 'GAMES',
      holders: [{ rosterId: 7, managerId: 70, teamName: 'Bad Actors', avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'CONDUCT', rosterId: 7, playerId: 'p9', playerName: 'Suspended Guy',
          source: 'SUSPENDED', weeks: [3, 4, 5], reason: null,
        },
        {
          type: 'CONDUCT', rosterId: 7, playerId: 'p10', playerName: 'Tyler Loop',
          source: 'COMMISSIONER', weeks: [2], reason: 'traded away his own starters, twice',
        },
        {
          type: 'CONDUCT', rosterId: 7, playerId: 'p11', playerName: 'Scattered Guy',
          source: 'SUSPENDED', weeks: [3, 7, 9], reason: null,
        },
      ],
      coverage: null,
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), unethical))
    render(<Superlatives />)

    expect(await screen.findByText('Suspended Guy — Suspended · weeks 3–5')).toBeInTheDocument()
    expect(
      screen.getByText("Tyler Loop — Commissioner's call: traded away his own starters, twice · week 2"),
    ).toBeInTheDocument()
    expect(screen.getByText('Scattered Guy — Suspended · weeks 3, 7, 9')).toBeInTheDocument()
  })

  it('hides add/edit/remove controls on the commissioner\'s list when canEdit is false', async () => {
    fetchConductList.mockResolvedValue(
      conductList({
        canEdit: false,
        entries: [
          {
            id: 1, playerId: '4034', playerName: 'A Player', reason: 'reasons',
            appliesFromWeek: 3, addedBy: 'Commish', createdAt: '2026-10-14T19:02:11Z',
          },
        ],
      }),
    )
    const data = baseline()
    data.commissionerListAvailable = true
    fetchSuperlatives.mockResolvedValue(data)
    render(<Superlatives />)

    await screen.findByText("Commissioner's list")
    expect(await screen.findByText('A Player')).toBeInTheDocument()
    expect(screen.queryByText('Add to the list')).not.toBeInTheDocument()
    expect(screen.queryByText('Remove')).not.toBeInTheDocument()
  })

  it('shows add/edit/remove controls on the commissioner\'s list when canEdit is true', async () => {
    fetchConductList.mockResolvedValue(
      conductList({
        canEdit: true,
        entries: [
          {
            id: 1, playerId: '4034', playerName: 'A Player', reason: 'reasons',
            appliesFromWeek: 3, addedBy: 'Commish', createdAt: '2026-10-14T19:02:11Z',
          },
        ],
      }),
    )
    const data = baseline()
    data.commissionerListAvailable = true
    fetchSuperlatives.mockResolvedValue(data)
    render(<Superlatives />)

    expect(await screen.findByText('Add to the list')).toBeInTheDocument()
    expect(screen.getByText('Remove')).toBeInTheDocument()
  })

  it("shows the no-commissioner note instead of controls when commissionerListAvailable is false", async () => {
    fetchConductList.mockResolvedValue(conductList({ canEdit: true }))
    const data = baseline()
    data.commissionerListAvailable = false
    fetchSuperlatives.mockResolvedValue(data)
    render(<Superlatives />)

    expect(
      await screen.findByText("No commissioner is known for this league, so the list can't be edited."),
    ).toBeInTheDocument()
    expect(screen.queryByText('Add to the list')).not.toBeInTheDocument()
  })

  it('renders a reason containing a tag as literal text, never as markup', async () => {
    fetchConductList.mockResolvedValue(
      conductList({
        canEdit: false,
        entries: [
          {
            id: 2, playerId: '9999', playerName: 'Sneaky Guy', reason: '<b>bold move</b>',
            appliesFromWeek: 2, addedBy: null, createdAt: '2026-10-14T19:02:11Z',
          },
        ],
      }),
    )
    fetchSuperlatives.mockResolvedValue(baseline())
    const { container } = render(<Superlatives />)

    const reasonEl = await screen.findByText('<b>bold move</b>')
    expect(reasonEl.tagName.toLowerCase()).not.toBe('b')
    expect(container.querySelector('b')).toBeNull()

    await waitFor(() => expect(fetchConductList).toHaveBeenCalled())
  })

  // Live-check finding (2026-09-23): saving used to refetch only the conduct
  // list, so the UNETHICAL card above kept showing its stale emptyReason
  // until a full page reload. Assert the superlatives payload is refetched,
  // and that the card actually picks up the new holder without a reload.
  it('refetches the superlatives payload after saving a commissioner entry', async () => {
    const emptyUnethical: Superlative = {
      kind: 'UNETHICAL', available: true, reason: null, early: false, value: null, unit: null,
      holders: [], emptyReason: 'nobody has been named yet', detail: [], coverage: null,
    }
    const filledUnethical: Superlative = {
      kind: 'UNETHICAL', available: true, reason: null, early: false, value: 1, unit: 'GAMES',
      holders: [{ rosterId: 9, managerId: 90, teamName: 'Team X', avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'CONDUCT', rosterId: 9, playerId: '4034', playerName: 'A Player',
          source: 'COMMISSIONER', weeks: [2], reason: 'reasons',
        },
      ],
      coverage: null,
    }
    fetchSuperlatives
      .mockResolvedValueOnce(withKind({ ...baseline(), commissionerListAvailable: true }, emptyUnethical))
      .mockResolvedValueOnce(withKind({ ...baseline(), commissionerListAvailable: true }, filledUnethical))
    fetchConductList.mockResolvedValue(conductList({ canEdit: true }))
    saveConductEntry.mockResolvedValue({
      id: 1, playerId: '4034', playerName: 'A Player', reason: 'reasons',
      appliesFromWeek: 2, addedBy: 'Commish', createdAt: '2026-10-14T19:02:11Z',
    })

    const user = userEvent.setup()
    render(<Superlatives />)

    expect(await screen.findByText('nobody has been named yet')).toBeInTheDocument()

    await user.click(await screen.findByRole('button', { name: 'Add to the list' }))
    await user.type(screen.getByLabelText('Sleeper player id'), '4034')
    await user.type(screen.getByLabelText('Reason'), 'reasons')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('A Player — Commissioner\'s call: reasons · week 2')).toBeInTheDocument()
    expect(fetchSuperlatives).toHaveBeenCalledTimes(2)
  })

  it('refetches the superlatives payload after removing a commissioner entry', async () => {
    const emptyUnethical: Superlative = {
      kind: 'UNETHICAL', available: true, reason: null, early: false, value: null, unit: null,
      holders: [], emptyReason: 'nobody has been named yet', detail: [], coverage: null,
    }
    fetchSuperlatives
      .mockResolvedValueOnce(
        withKind({ ...baseline(), commissionerListAvailable: true }, { ...emptyUnethical, emptyReason: 'still on the list' }),
      )
      .mockResolvedValueOnce(withKind({ ...baseline(), commissionerListAvailable: true }, emptyUnethical))
    fetchConductList.mockResolvedValue(
      conductList({
        canEdit: true,
        entries: [
          {
            id: 5, playerId: '4034', playerName: 'A Player', reason: 'reasons',
            appliesFromWeek: 2, addedBy: 'Commish', createdAt: '2026-10-14T19:02:11Z',
          },
        ],
      }),
    )
    deleteConductEntry.mockResolvedValue(undefined)

    const user = userEvent.setup()
    render(<Superlatives />)

    expect(await screen.findByText('still on the list')).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Remove' }))

    await waitFor(() => expect(screen.getByText('nobody has been named yet')).toBeInTheDocument())
    expect(fetchSuperlatives).toHaveBeenCalledTimes(2)
  })

  // Accessibility (2026-09-23 live-check finding): a browser's accessibility
  // tree listed these as unnamed textboxes because the <label>s weren't
  // associated with their inputs -- getByLabelText fails exactly the same
  // way an accessibility tree does when that association is missing.
  it('makes every form control findable by its label text', async () => {
    fetchConductList.mockResolvedValue(conductList({ canEdit: true }))
    const data = baseline()
    data.commissionerListAvailable = true
    fetchSuperlatives.mockResolvedValue(data)

    const user = userEvent.setup()
    render(<Superlatives />)

    await user.click(await screen.findByRole('button', { name: 'Add to the list' }))

    expect(screen.getByLabelText('Sleeper player id')).toBeInTheDocument()
    expect(screen.getByLabelText('Reason')).toBeInTheDocument()
    expect(screen.getByLabelText('Applies from week')).toBeInTheDocument()
  })

  // Code-review fix (2026-09-23): the cards resolve through
  // LeagueSeasonResolver and can land on an older PLAYED season than the
  // URL's own id ('L1' here, per the react-router-dom mock above); the
  // conduct list is addressed by exact league row, so it must fetch/save/
  // delete against the payload's leagueSleeperId, never that URL id.
  it('fetches, saves and deletes the conduct list against leagueSleeperId, not the URL id', async () => {
    const data = { ...baseline(), leagueSleeperId: 'L1_2025', commissionerListAvailable: true }
    fetchSuperlatives.mockResolvedValue(data)
    fetchConductList.mockResolvedValue(
      conductList({
        canEdit: true,
        entries: [
          {
            id: 3, playerId: '4034', playerName: 'A Player', reason: 'reasons',
            appliesFromWeek: 2, addedBy: 'Commish', createdAt: '2026-10-14T19:02:11Z',
          },
        ],
      }),
    )
    saveConductEntry.mockResolvedValue({
      id: 4, playerId: '4035', playerName: 'B Player', reason: 'more reasons',
      appliesFromWeek: 3, addedBy: 'Commish', createdAt: '2026-10-14T19:02:11Z',
    })
    deleteConductEntry.mockResolvedValue(undefined)

    const user = userEvent.setup()
    render(<Superlatives />)

    await waitFor(() => expect(fetchConductList).toHaveBeenCalledWith('L1_2025'))
    expect(fetchConductList).not.toHaveBeenCalledWith('L1')

    await user.click(await screen.findByRole('button', { name: 'Add to the list' }))
    await user.type(screen.getByLabelText('Sleeper player id'), '4035')
    await user.type(screen.getByLabelText('Reason'), 'more reasons')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(saveConductEntry).toHaveBeenCalledWith(
        'L1_2025',
        expect.objectContaining({ playerId: '4035' }),
      ),
    )

    await user.click(await screen.findByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(deleteConductEntry).toHaveBeenCalledWith('L1_2025', 3))
  })

  // The same fallback SeasonFallbackNote reads for the cards above: when the
  // resolver walked back, the list below needs its own note, since it is
  // addressed by exact league row and won't exist for the new season yet.
  it('notes which season the list belongs to when the resolver walked back', async () => {
    const data = { ...baseline(), requestedSeason: 2026, season: 2025 }
    fetchSuperlatives.mockResolvedValue(data)
    render(<Superlatives />)

    expect(
      await screen.findByText(
        'Showing the 2025 season — the list for the new season opens once it has a scored week.',
      ),
    ).toBeInTheDocument()
  })

  it('shows no season note when the list is for the season the URL asked for', async () => {
    fetchSuperlatives.mockResolvedValue(baseline())
    render(<Superlatives />)

    await screen.findByText("Commissioner's list")
    expect(screen.queryByText(/Showing the .* season/)).not.toBeInTheDocument()
  })
})
