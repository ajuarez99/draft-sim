import { render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PowerRankings, { ballotsCountedIn } from './PowerRankings'

/**
 * One week at a time.
 *
 * Observed on ballknowers.co 2026-09-17, league 1346366555759341568: the hero
 * said "0 of 12 ballots in for week 1" and the ladder header said
 * "League vote · week 1 · 0 ballots", directly above twelve rows of real
 * week-1 averages (4.50 ... 10.00) computed from four ballots -- while the
 * footer panel collected for week 2. The live `/ballot` tally (week 2, zero)
 * had been printed under the week-1 heading.
 *
 * The payloads below are the shapes those two endpoints actually answered
 * that day.
 */

vi.mock('react-router-dom', () => ({
  Link: ({ to, children, ...rest }: { to: string; children: ReactNode }) => (
    <a href={to} {...rest}>
      {children}
    </a>
  ),
  useParams: () => ({ sleeperLeagueId: '1346366555759341568' }),
}))

vi.mock('../user', () => ({
  useUser: () => ({ sleeperUserId: '1', username: 'popsharky', displayName: 'popsharky', avatar: null }),
}))

const MANAGERS = [
  'theadambomb98', 'popsharky', 'kieriskash', 'jstrobe', 'njerickson', 'xonicboom',
  'ImReallyHarry', 'BamAddABio', 'GraftonCarlson', 'GrandmasBeefRagu', 'jpelwell', 'ChedddaBob',
]
const SCORES = [4.5, 5.0, 5.25, 5.5, 6.0, 6.25, 6.25, 6.5, 7.0, 7.25, 8.5, 10.0]

/** The twelve MEMBER rows the live league has for week 1: four ballots, and
 *  every entry carries that same per-week tally. */
const memberEntries = (week: number) =>
  MANAGERS.map((manager, i) => ({
    season: 2026,
    week,
    kind: 'MEMBER' as const,
    rosterId: i + 1,
    managerId: i + 1,
    manager,
    avatarId: null,
    rank: i + 1,
    score: SCORES[i],
    note: null,
    bestRank: 1,
    worstRank: 7 + (i % 5),
    stdev: 1.5,
    ballotCount: 4,
    selfRankBias: i === 0 ? -3 : 1,
  }))

let currentWeek = 2
let memberWeek = 1
let liveBallotCount = 0

const getPowerRankings = vi.fn(() =>
  Promise.resolve({
    sleeperLeagueId: '1346366555759341568',
    sportState: { week: currentWeek, season: '2026', seasonStartDate: '2026-09-09', started: true },
    entries: memberEntries(memberWeek),
    playoffOdds: null,
  }),
)
const getBallot = vi.fn(() =>
  Promise.resolve({
    season: 2026,
    week: currentWeek,
    canSubmit: true,
    canCommission: false,
    commissionerKnown: true,
    memberCount: 12,
    ballotCount: liveBallotCount,
    members: MANAGERS.map((manager, i) => ({
      rosterId: i + 1,
      managerId: i + 1,
      manager,
      avatarId: null,
      teamName: manager,
      isMe: i === 1,
    })),
    mine: null,
  }),
)

vi.mock('../api', () => ({
  ALL_POWER_RANKING_KINDS: ['COMPUTED_REALIZED', 'COMMISSIONER', 'MEMBER'],
  getPowerRankings: (...args: unknown[]) => getPowerRankings(...(args as [])),
  getBallot: (...args: unknown[]) => getBallot(...(args as [])),
  getLeagueHistory: () => Promise.resolve({ seasons: [] }),
  computePowerRankings: vi.fn(),
  saveCommissionerRanking: vi.fn(),
  submitBallot: vi.fn(),
}))

beforeEach(() => {
  currentWeek = 2
  memberWeek = 1
  liveBallotCount = 0
})

describe('a shown week never borrows the live week\'s ballot count', () => {
  it('reads the tally off the week it belongs to', () => {
    const entries = memberEntries(1)
    expect(ballotsCountedIn(entries, 2026, 1)).toBe(4)
    // No entries for a week means no ballots for it -- MEMBER rows are derived
    // from the ballots on every read, so they cannot lag behind them.
    expect(ballotsCountedIn(entries, 2026, 2)).toBe(0)
    // Wrong season is not a fallback either.
    expect(ballotsCountedIn(entries, 2025, 1)).toBe(0)
  })

  it('states week 1 with week 1 ballots while collecting for week 2', async () => {
    render(<PowerRankings />)

    // The deck: the bug printed "0 of 12" here.
    await waitFor(() => expect(screen.getByText(/4 of 12 ballots in for week 1\./)).toBeTruthy())

    // The ladder header carries week 1's own tally, not the live zero.
    expect(screen.getByText(/League vote · week 1 · 4 ballots/)).toBeTruthy()
    expect(screen.queryByText(/week 1 · 0 ballots/)).toBeNull()

    // The page says which week it is showing and which it is missing, so the
    // footer panel's "week 2" is no longer a contradiction.
    expect(screen.getByText(/Showing week 1 .* Nothing for week 2 yet\./)).toBeTruthy()
    expect(screen.getByText(/Nothing submitted yet for week 2\./)).toBeTruthy()

    // Both weeks are on screen, each labelled with its own number.
    expect(screen.getByText(/4 of 12 ballots in · week 1/)).toBeTruthy()
    expect(screen.getByText(/0 of 12 in · week 2 open/)).toBeTruthy()
  })

  it('drops the lag line and shows one week once that week has ballots', async () => {
    memberWeek = 2
    liveBallotCount = 4
    render(<PowerRankings />)

    await waitFor(() => expect(screen.getByText(/4 of 12 ballots in for week 2\./)).toBeTruthy())
    expect(screen.getByText(/League vote · week 2 · 4 ballots/)).toBeTruthy()
    expect(screen.queryByText(/Showing week/)).toBeNull()
    expect(screen.queryByText(/open$/)).toBeNull()
    expect(screen.getByText(/Nothing submitted yet for week 2\./)).toBeTruthy()
  })

  it('never renders week 0 as week 1, and never as a number', async () => {
    // An NBA league: /state/nba answers week 0 for the whole offseason, so
    // week 0 is both the preseason baseline AND the week being voted in. A
    // `|| 1` anywhere in the week plumbing shows up here.
    currentWeek = 0
    memberWeek = 0
    liveBallotCount = 4
    render(<PowerRankings />)

    await waitFor(() => expect(screen.getByText(/4 of 12 ballots in for Preseason\./)).toBeTruthy())
    expect(screen.getByText(/League vote · Preseason · 4 ballots/)).toBeTruthy()
    expect(screen.getByText(/Nothing submitted yet for Preseason\./)).toBeTruthy()
    // Nothing anywhere claims week 1, and no lag line: one week, agreed on.
    expect(screen.queryByText(/week 1/i)).toBeNull()
    expect(screen.queryByText(/Showing /)).toBeNull()
  })
})
