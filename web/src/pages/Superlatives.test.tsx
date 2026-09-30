import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Superlatives, { standingFigure } from './Superlatives'
import { LeagueDataVersionProvider, useBumpLeagueDataVersion } from '../leagueDataVersion'
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
  'LUCKIEST', 'UNLUCKIEST', 'MOST_BENCH_POINTS', 'WAIVER_WIRE_WARRIOR', 'JABARI_SMITH_JR',
  'JOEL_EMBIID', 'UNETHICAL',
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
    coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
        { rosterId: 1, managerId: 10, teamName: 'Team A', username: null, avatarId: null },
        { rosterId: 2, managerId: 20, teamName: 'Team B', username: null, avatarId: null },
      ],
      emptyReason: null,
      detail: [
        { type: 'WEEK_SCORE', week: 3, rosterId: 1, points: 180.5 },
        { type: 'WEEK_SCORE', week: 4, rosterId: 2, points: 180.5 },
      ],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      holders: [{ rosterId: 4, managerId: 17, teamName: 'Escape Artists', username: null, avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'GAME', week: 2, rosterId: 4, opponentRosterId: 9, opponentTeamName: 'Others',
          points: 118.4, opponentPoints: 112.1, margin: 6.3,
        },
      ],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Team A', username: null, avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'GAME', week: 2, rosterId: 1, opponentRosterId: 2, opponentTeamName: 'Team B',
          points: 150, opponentPoints: 94.8, margin: 55.2,
        },
      ],
      coverage: { weeksCovered: 5, weeksExcluded: 1, reasons: ['week 3: no pairings stored'] },
      playerHolders: [], standings: [], playerStandings: [],
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
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Team A', username: null, avatarId: null }],
      emptyReason: null,
      detail: [],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Master Bates', username: null, avatarId: null }],
      emptyReason: null,
      detail: [{ type: 'WEEK_SCORE', week: 1, rosterId: 1, points: 164.96 }],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Team A', username: null, avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'GAME', week: 2, rosterId: 1, opponentRosterId: 2,
          opponentTeamName: 'Dart has hit anotha Bower', points: 150, opponentPoints: 50.32, margin: 99.68,
        },
      ],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
        { rosterId: 1, managerId: 10, teamName: 'Team A', username: null, avatarId: null },
        { rosterId: 2, managerId: 20, teamName: 'Team B', username: null, avatarId: null },
        { rosterId: 3, managerId: 30, teamName: 'Team C', username: null, avatarId: null },
      ],
      emptyReason: null,
      detail: [
        { type: 'GAME', week: 1, rosterId: 1, opponentRosterId: 9, opponentTeamName: 'X', points: 100, opponentPoints: 99.5, margin: 0.5 },
        { type: 'GAME', week: 4, rosterId: 2, opponentRosterId: 8, opponentTeamName: 'Y', points: 88, opponentPoints: 87.5, margin: 0.5 },
        { type: 'GAME', week: 7, rosterId: 3, opponentRosterId: 7, opponentTeamName: 'Z', points: 77, opponentPoints: 76.5, margin: 0.5 },
      ],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      holders: [{ rosterId: 4, managerId: 17, teamName: 'Escape Artists', username: null, avatarId: null }],
      emptyReason: null,
      detail: [
        { type: 'GAME', week: 4, rosterId: 4, opponentRosterId: 1, opponentTeamName: 'X', points: 100, opponentPoints: 95, margin: 5 },
        { type: 'GAME', week: 9, rosterId: 4, opponentRosterId: 2, opponentTeamName: 'Y', points: 101, opponentPoints: 97, margin: 4 },
        { type: 'GAME', week: 13, rosterId: 4, opponentRosterId: 3, opponentTeamName: 'Z', points: 102, opponentPoints: 99, margin: 3 },
      ],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      holders: [{ rosterId: 6, managerId: 60, teamName: 'Heartbreak Kid', username: null, avatarId: null }],
      emptyReason: null,
      detail: [
        { type: 'GAME', week: 2, rosterId: 6, opponentRosterId: 1, opponentTeamName: 'X', points: 95, opponentPoints: 100, margin: 5 },
      ],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      holders: [{ rosterId: 6, managerId: 60, teamName: 'Heartbreak Kid', username: null, avatarId: null }],
      emptyReason: null,
      detail: [
        { type: 'GAME', week: 2, rosterId: 6, opponentRosterId: 1, opponentTeamName: 'X', points: 95, opponentPoints: 100, margin: 5 },
        { type: 'GAME', week: 5, rosterId: 6, opponentRosterId: 2, opponentTeamName: 'Y', points: 96, opponentPoints: 99, margin: 3 },
        { type: 'GAME', week: 8, rosterId: 6, opponentRosterId: 3, opponentTeamName: 'Z', points: 97, opponentPoints: 98, margin: 1 },
      ],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Team A', username: null, avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'LUCK', rosterId: 1, actualWins: 5, expectedWins: 2.6, winsAboveExpected: 2.4,
          swingWeeks: [{ week: 3, result: 'WON', points: 88.4, weeklyRank: 9, opponent: 'Team B' }],
          fromWeek: 1, throughWeek: 6, reading: '2.40 more wins than their scores earned',
        },
      ],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), luckiest))
    render(<Superlatives />)

    expect(
      await screen.findByText('2.40 more wins than their scores earned · weeks 1–6'),
    ).toBeInTheDocument()
  })

  it('reads "week 3", not "weeks 3–3", when the span is a single week', async () => {
    const luckiest: Superlative = {
      kind: 'LUCKIEST',
      available: true,
      reason: null,
      early: true,
      value: 0.8,
      unit: 'WINS',
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Team A', username: null, avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'LUCK', rosterId: 1, actualWins: 1, expectedWins: 0.2, winsAboveExpected: 0.8,
          swingWeeks: [], fromWeek: 3, throughWeek: 3, reading: '0.80 more wins than their scores earned',
        },
      ],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), luckiest))
    render(<Superlatives />)

    expect(
      await screen.findByText('0.80 more wins than their scores earned · week 3'),
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
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Team A', username: null, avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'LUCK', rosterId: 1, actualWins: 5, expectedWins: 2.6, winsAboveExpected: 2.4,
          swingWeeks: [{ week: 3, result: 'WON', points: 88.4, weeklyRank: 9, opponent: 'Team B' }],
          fromWeek: 1, throughWeek: 6, reading: '2.40 more wins than their scores earned',
        },
      ],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      holders: [{ rosterId: 2, managerId: 20, teamName: 'Bench Warmers', username: null, avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'BENCH_TOTAL', rosterId: 2, pointsLeft: 210.55, weeksCounted: 6,
          fromWeek: 1, throughWeek: 6, biggestWeek: { week: 4, pointsLeft: 55.2 },
        },
      ],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      holders: [{ rosterId: 3, managerId: 30, teamName: 'Waiver Wire Warriors', username: null, avatarId: null }],
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
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      reason: "Transactions for this season haven't loaded yet.",
      early: false,
      value: null,
      unit: null,
      holders: [],
      emptyReason: null,
      detail: [],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), waiver))
    render(<Superlatives />)

    expect(
      await screen.findByText(/Transactions for this season haven't loaded yet/),
    ).toBeInTheDocument()
  })

  // T080 (US7): JABARI_SMITH_JR is player-headed -- `holders` stays `[]` and
  // `playerHolders` carries the tied winners, each with its own add list.
  it('renders two tied players for the Jabari Smith Jr. Award, each with its own adds', async () => {
    const jabari: Superlative = {
      kind: 'JABARI_SMITH_JR',
      available: true,
      reason: null,
      early: false,
      value: 14,
      unit: 'ADDS',
      holders: [],
      emptyReason: null,
      detail: [
        {
          type: 'ADD', playerId: 'p1', week: 1, rosterId: 2, teamName: 'Team A', avatarId: null,
          addType: 'WAIVER', faabBid: 12,
        },
        {
          type: 'ADD', playerId: 'p1', week: 3, rosterId: 5, teamName: 'Team B', avatarId: null,
          addType: 'FREE_AGENT', faabBid: null,
        },
        {
          type: 'ADD', playerId: 'p2', week: 2, rosterId: 4, teamName: 'Team C', avatarId: null,
          addType: 'WAIVER', faabBid: 0,
        },
      ],
      coverage: null,
      playerHolders: [
        { playerId: 'p1', playerName: 'Jake LaRavia', position: 'PF', team: 'MEM', adds: 14, distinctTeams: 8 },
        { playerId: 'p2', playerName: 'Brice Sensabaugh', position: 'SF', team: 'UTA', adds: 14, distinctTeams: 8 },
      ],
      standings: [], playerStandings: [],
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), jabari))
    render(<Superlatives />)

    expect(await screen.findByText('The Jabari Smith Jr. Award')).toBeInTheDocument()
    expect(screen.getByText('Jake LaRavia (PF)')).toBeInTheDocument()
    expect(screen.getByText('Brice Sensabaugh (SF)')).toBeInTheDocument()
    expect(screen.getByText('MEM · 14 adds by 8 teams')).toBeInTheDocument()
    expect(screen.getByText('UTA · 14 adds by 8 teams')).toBeInTheDocument()

    // Each player's own adds render, not interleaved with the other's.
    expect(screen.getByText('Team A')).toBeInTheDocument()
    expect(screen.getByText('Team B')).toBeInTheDocument()
    expect(screen.getByText('Team C')).toBeInTheDocument()
  })

  it('renders "Waiver ($N)" for a bid, "Waiver" for a null bid, and "Free agent" for FA -- $0 is a real bid', async () => {
    const jabari: Superlative = {
      kind: 'JABARI_SMITH_JR',
      available: true,
      reason: null,
      early: false,
      value: 3,
      unit: 'ADDS',
      holders: [],
      emptyReason: null,
      detail: [
        {
          type: 'ADD', playerId: 'p1', week: 1, rosterId: 2, teamName: 'Team A', avatarId: null,
          addType: 'WAIVER', faabBid: 12,
        },
        {
          type: 'ADD', playerId: 'p1', week: 2, rosterId: 3, teamName: 'Team B', avatarId: null,
          addType: 'WAIVER', faabBid: null,
        },
        {
          type: 'ADD', playerId: 'p1', week: 3, rosterId: 4, teamName: 'Team C', avatarId: null,
          addType: 'WAIVER', faabBid: 0,
        },
        {
          type: 'ADD', playerId: 'p1', week: 4, rosterId: 5, teamName: 'Team D', avatarId: null,
          addType: 'FREE_AGENT', faabBid: null,
        },
      ],
      coverage: null,
      playerHolders: [
        { playerId: 'p1', playerName: 'Some Guy', position: 'C', team: null, adds: 4, distinctTeams: 4 },
      ],
      standings: [], playerStandings: [],
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), jabari))
    render(<Superlatives />)

    expect(await screen.findByText('Some Guy (C)')).toBeInTheDocument()
    // No real-life team: the team is left out, not shown as "Free agent",
    // which the rows below already use for the FA add type.
    expect(screen.getByText('4 adds by 4 teams')).toBeInTheDocument()
    expect(screen.getByText('Waiver ($12)')).toBeInTheDocument()
    expect(screen.getByText('Waiver')).toBeInTheDocument()
    expect(screen.getByText('Waiver ($0)')).toBeInTheDocument()
    expect(screen.getByText('Free agent')).toBeInTheDocument()
  })

  it('shows the emptyReason for the Jabari Smith Jr. Award when nobody has been added', async () => {
    const jabari: Superlative = {
      kind: 'JABARI_SMITH_JR',
      available: true,
      reason: null,
      early: false,
      value: null,
      unit: 'ADDS',
      holders: [],
      emptyReason: "nobody's been picked up yet",
      detail: [],
      coverage: null,
      playerHolders: [], standings: [], playerStandings: [],
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), jabari))
    render(<Superlatives />)

    expect(await screen.findByText("nobody's been picked up yet")).toBeInTheDocument()
  })

  // T079 fix: `holders` alone used to decide "empty". A kind with playerHolders
  // non-empty but holders == [] (true for every JABARI_SMITH_JR result) must
  // never render the empty state.
  it('does not render the empty state when playerHolders is non-empty but holders is empty', async () => {
    const jabari: Superlative = {
      kind: 'JABARI_SMITH_JR',
      available: true,
      reason: null,
      early: false,
      value: 1,
      unit: 'ADDS',
      holders: [],
      emptyReason: "nobody's been picked up yet",
      detail: [
        {
          type: 'ADD', playerId: 'p1', week: 1, rosterId: 2, teamName: 'Team A', avatarId: null,
          addType: 'WAIVER', faabBid: null,
        },
      ],
      coverage: null,
      playerHolders: [
        { playerId: 'p1', playerName: 'Some Guy', position: 'C', team: 'BOS', adds: 1, distinctTeams: 1 },
      ],
      standings: [], playerStandings: [],
    }
    fetchSuperlatives.mockResolvedValue(withKind(baseline(), jabari))
    render(<Superlatives />)

    expect(await screen.findByText('Some Guy (C)')).toBeInTheDocument()
    expect(screen.queryByText("nobody's been picked up yet")).not.toBeInTheDocument()
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
      holders: [{ rosterId: 5, managerId: 50, teamName: 'Process Trusters', username: null, avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'ABSENCE', rosterId: 5, playerId: 'e1', playerName: 'The Process', position: 'C',
          gamesMissed: 6, weeksAffected: 5, pointsPerGame: 10.4, estimatedPointsLost: 62.4, estimated: true,
        },
      ],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      holders: [{ rosterId: 7, managerId: 70, teamName: 'Bad Actors', username: null, avatarId: null }],
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
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      holders: [], emptyReason: 'nobody has been named yet', detail: [], coverage: null, playerHolders: [], standings: [], playerStandings: [],
    }
    const filledUnethical: Superlative = {
      kind: 'UNETHICAL', available: true, reason: null, early: false, value: 1, unit: 'GAMES',
      holders: [{ rosterId: 9, managerId: 90, teamName: 'Team X', username: null, avatarId: null }],
      emptyReason: null,
      detail: [
        {
          type: 'CONDUCT', rosterId: 9, playerId: '4034', playerName: 'A Player',
          source: 'COMMISSIONER', weeks: [2], reason: 'reasons',
        },
      ],
      coverage: null, playerHolders: [], standings: [], playerStandings: [],
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
      holders: [], emptyReason: 'nobody has been named yet', detail: [], coverage: null, playerHolders: [], standings: [], playerStandings: [],
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

  // specs/009-auto-data-refresh T027/T028: the rail bumps a league's data
  // version when its background refresh finishes, and the page refetches.
  it('refetches when the rail bumps its league data version, and not for another league', async () => {
    function Bumper() {
      const bump = useBumpLeagueDataVersion()
      return (
        <>
          <button onClick={() => bump('L1')}>bump L1</button>
          <button onClick={() => bump('OTHER')}>bump other</button>
        </>
      )
    }
    fetchSuperlatives.mockResolvedValue(baseline())
    render(
      <LeagueDataVersionProvider>
        <Bumper />
        <Superlatives />
      </LeagueDataVersionProvider>,
    )
    await screen.findByText('Highest week')
    expect(fetchSuperlatives).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByText('bump other'))
    expect(fetchSuperlatives).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByText('bump L1'))
    await waitFor(() => expect(fetchSuperlatives).toHaveBeenCalledTimes(2))
    expect(fetchSuperlatives).toHaveBeenLastCalledWith('L1')
  })

  // specs/010-superlatives-full-standings T021/T026: the "See all" standings modal.
  describe('full standings modal', () => {
    const holder = (id: number, name: string) => ({
      rosterId: id, managerId: id * 10, teamName: name, username: null, avatarId: null,
    })
    const row = (
      rank: number | null, id: number, name: string, value: number | null, note: string | null = null,
      missingReason: string | null = null,
    ) => ({ rank, team: holder(id, name), value, note, hasValue: value != null, missingReason })

    function highestWeek(over: Partial<Superlative> = {}): Superlative {
      return {
        kind: 'HIGHEST_WEEK',
        available: true,
        reason: null,
        early: false,
        value: 180.5,
        unit: 'POINTS',
        holders: [holder(1, 'Team A')],
        emptyReason: null,
        detail: [{ type: 'WEEK_SCORE', week: 3, rosterId: 1, points: 180.5 }],
        coverage: null,
        playerHolders: [],
        standings: [
          row(1, 1, 'Team A', 180.5, 'week 3'),
          row(2, 2, 'Team B', 150.25, 'week 5'),
          row(3, 3, 'Team C', 120, 'week 1'),
        ],
        playerStandings: [],
        ...over,
      }
    }

    it('opens from the See all button with every row in payload order', async () => {
      fetchSuperlatives.mockResolvedValue(withKind(baseline(), highestWeek()))
      render(<Superlatives />)

      await userEvent.click(await screen.findByRole('button', { name: 'See all' }))
      const dialog = screen.getByRole('dialog')
      const items = within(dialog).getAllByRole('listitem')
      expect(items).toHaveLength(3)
      expect(items[0]).toHaveTextContent('Team A')
      expect(items[0]).toHaveTextContent('180.50 points')
      expect(items[1]).toHaveTextContent('Team B')
      expect(items[2]).toHaveTextContent('Team C')
      expect(items[0]).toHaveTextContent('week 3')
    })

    it('closes on Escape and on the backdrop, but not on a click inside the dialog', async () => {
      fetchSuperlatives.mockResolvedValue(withKind(baseline(), highestWeek()))
      render(<Superlatives />)

      await userEvent.click(await screen.findByRole('button', { name: 'See all' }))
      await userEvent.click(screen.getByRole('dialog'))
      expect(screen.getByRole('dialog')).toBeInTheDocument()

      await userEvent.keyboard('{Escape}')
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

      // The backdrop click must close it and not bubble up to the card, which
      // would reopen it.
      await userEvent.click(screen.getByRole('button', { name: 'See all' }))
      await userEvent.click(screen.getByRole('dialog').parentElement as HTMLElement)
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('does not open from the Games summary', async () => {
      fetchSuperlatives.mockResolvedValue(withKind(baseline(), highestWeek()))
      render(<Superlatives />)

      await userEvent.click(await screen.findByText('Games'))
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('opens from a click on the card body (mouse path)', async () => {
      fetchSuperlatives.mockResolvedValue(withKind(baseline(), highestWeek()))
      render(<Superlatives />)

      await userEvent.click(await screen.findByText('Highest week'))
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    it('does not open from a card click while text is selected (B3)', async () => {
      fetchSuperlatives.mockResolvedValue(withKind(baseline(), highestWeek()))
      render(<Superlatives />)

      const title = await screen.findByText('Highest week')
      const spy = vi.spyOn(window, 'getSelection').mockReturnValue({ toString: () => 'Team A' } as unknown as Selection)
      try {
        await userEvent.click(title)
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      } finally {
        spy.mockRestore()
      }
    })

    it('stays open when a press starts inside the card and is released on the backdrop (B3)', async () => {
      fetchSuperlatives.mockResolvedValue(withKind(baseline(), highestWeek()))
      render(<Superlatives />)

      await userEvent.click(await screen.findByRole('button', { name: 'See all' }))
      const dialog = screen.getByRole('dialog')
      const backdrop = dialog.parentElement as HTMLElement
      fireEvent.mouseDown(dialog)
      fireEvent.mouseUp(backdrop)
      fireEvent.click(backdrop)
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    it('labels UNETHICAL as player-weeks and JOEL_EMBIID as estimated (B1, B4)', () => {
      expect(standingFigure('UNETHICAL', 'GAMES', 1)).toBe('1 player-week')
      expect(standingFigure('UNETHICAL', 'GAMES', 3)).toBe('3 player-weeks')
      expect(standingFigure('JOEL_EMBIID', 'POINTS', 12.4)).toBe('12.40 estimated points lost')
    })

    it('has no See all on unavailable or empty cards', async () => {
      const empty = highestWeek({ holders: [], value: null, emptyReason: 'nothing yet', detail: [], standings: [] })
      fetchSuperlatives.mockResolvedValue(withKind(baseline(), empty))
      render(<Superlatives />)

      await screen.findByText('nothing yet')
      // Every other kind is "not built yet" (unavailable), the one above is empty.
      expect(screen.queryByRole('button', { name: 'See all' })).not.toBeInTheDocument()
    })

    it('shows a dash and the reason for a row with no value', async () => {
      const s = highestWeek({
        standings: [
          row(1, 1, 'Team A', 180.5),
          row(null, 4, 'Roster 4', null, null, 'no scored weeks'),
        ],
      })
      fetchSuperlatives.mockResolvedValue(withKind(baseline(), s))
      render(<Superlatives />)

      await userEvent.click(await screen.findByRole('button', { name: 'See all' }))
      const items = within(screen.getByRole('dialog')).getAllByRole('listitem')
      expect(items[1]).toHaveTextContent('—')
      expect(items[1]).toHaveTextContent('Roster 4')
      expect(items[1]).toHaveTextContent('no scored weeks')
    })

    it('renders a low-to-high kind in the order given, never re-sorted', async () => {
      const lowest = highestWeek({
        kind: 'LOWEST_WEEK',
        standings: [row(1, 3, 'Team C', 60), row(2, 2, 'Team B', 90), row(3, 1, 'Team A', 120)],
      })
      fetchSuperlatives.mockResolvedValue(withKind(baseline(), lowest))
      render(<Superlatives />)

      await userEvent.click(await screen.findByRole('button', { name: 'See all' }))
      const items = within(screen.getByRole('dialog')).getAllByRole('listitem')
      expect(items.map((li) => li.textContent)).toEqual([
        expect.stringContaining('Team C'),
        expect.stringContaining('Team B'),
        expect.stringContaining('Team A'),
      ])
    })

    it('signs luck figures and formats close-game counts like the card', async () => {
      const unluckiest = highestWeek({
        kind: 'UNLUCKIEST',
        unit: 'WINS',
        standings: [row(1, 1, 'Team A', -1.3, '3 actual vs 4.30 expected'), row(2, 2, 'Team B', 0.5)],
      })
      fetchSuperlatives.mockResolvedValue(withKind(baseline(), unluckiest))
      render(<Superlatives />)

      await userEvent.click(await screen.findByRole('button', { name: 'See all' }))
      const items = within(screen.getByRole('dialog')).getAllByRole('listitem')
      expect(items[0]).toHaveTextContent('−1.30 wins vs expected')
      expect(items[1]).toHaveTextContent('+0.50 wins vs expected')
    })

    it('formats CLOSE_WINS figures with formatCloseGameCount', async () => {
      const closeWins = highestWeek({
        kind: 'CLOSE_WINS',
        unit: 'WINS',
        standings: [row(1, 1, 'Team A', 2), row(2, 2, 'Team B', 1)],
      })
      fetchSuperlatives.mockResolvedValue(withKind(baseline(), closeWins))
      render(<Superlatives />)

      await userEvent.click(await screen.findByRole('button', { name: 'See all' }))
      const items = within(screen.getByRole('dialog')).getAllByRole('listitem')
      expect(items[0]).toHaveTextContent('2 wins')
      expect(items[1]).toHaveTextContent('1 win')
      expect(standingFigure('CLOSE_LOSSES', 'GAMES', 1)).toBe('1 loss')
    })

    it('opens the Jabari card on its player standings, in order, with the players subtitle', async () => {
      const jabari: Superlative = {
        kind: 'JABARI_SMITH_JR',
        available: true,
        reason: null,
        early: false,
        value: 14,
        unit: 'ADDS',
        holders: [],
        emptyReason: null,
        detail: [],
        coverage: null,
        playerHolders: [
          { playerId: 'p1', playerName: 'Jake LaRavia', position: 'PF', team: 'MEM', adds: 14, distinctTeams: 8 },
        ],
        standings: [],
        playerStandings: [
          { rank: 1, playerId: 'p1', playerName: 'Jake LaRavia', position: 'PF', team: 'MEM', adds: 14, distinctTeams: 8 },
          { rank: 2, playerId: 'p2', playerName: 'Some Guy', position: null, team: null, adds: 1, distinctTeams: 1 },
        ],
      }
      fetchSuperlatives.mockResolvedValue(withKind(baseline(), jabari))
      render(<Superlatives />)

      await userEvent.click(await screen.findByRole('button', { name: 'See all' }))
      const dialog = screen.getByRole('dialog')
      expect(within(dialog).getByText('This award ranks players, not teams.')).toBeInTheDocument()
      const items = within(dialog).getAllByRole('listitem')
      expect(items).toHaveLength(2)
      expect(items[0]).toHaveTextContent('Jake LaRavia (PF)')
      expect(items[0]).toHaveTextContent('14 adds by 8 teams')
      expect(items[1]).toHaveTextContent('Some Guy')
      expect(items[1]).toHaveTextContent('1 add by 1 team')
    })
  })
})
