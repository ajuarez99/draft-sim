import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import AwardWinners from './AwardWinners'
import type { Superlative } from '../api'

vi.mock('react-router-dom', () => ({
  Link: ({ to, children, className }: { to: string; children: React.ReactNode; className?: string }) => (
    <a href={to} className={className}>{children}</a>
  ),
}))

/**
 * specs/047-hall-of-fame T030 (plan A9): the winner lines extracted from the Superlatives page. One test per state;
 * the page's own tests (Superlatives.test.tsx) still cover them in place, so a regression in the extraction shows in
 * both.
 */
function award(over: Partial<Superlative>): Superlative {
  return {
    kind: 'HIGHEST_WEEK',
    available: true,
    reason: null,
    early: false,
    value: 180.5,
    unit: 'POINTS',
    holders: [],
    emptyReason: null,
    detail: [],
    coverage: null,
    playerHolders: [],
    standings: [],
    playerStandings: [],
    playerContributions: [], trendingCoverage: null,
    ...over,
  }
}

// NBA, because only a sport with player pages (destinations.playerPagesFor) turns a player's name into a link.
const show = (s: Superlative) =>
  render(<AwardWinners s={s} hue={200} sport="nba" leagueId="L1" throughWeek={6} />)

describe('AwardWinners', () => {
  it('player holders: the Jabari Smith Jr. award names the player, his adds and the teams that added him', () => {
    show(award({
      kind: 'JABARI_SMITH_JR',
      unit: 'ADDS',
      value: null,
      playerHolders: [{ playerId: 'p1', playerName: 'Sleeper Pick', position: 'WR', team: 'DAL', adds: 5, distinctTeams: 3 }],
      detail: [
        { type: 'ADD', playerId: 'p1', week: 2, rosterId: 4, teamName: 'Team Four', avatarId: null, addType: 'WAIVER', faabBid: 12 },
        { type: 'ADD', playerId: 'p1', week: 3, rosterId: 5, teamName: 'Team Five', avatarId: null, addType: 'FREE_AGENT', faabBid: null },
      ],
    }))
    expect(screen.getByRole('link', { name: 'Sleeper Pick' })).toBeInTheDocument()
    expect(screen.getByText(/DAL · 5 adds by 3 teams/)).toBeInTheDocument()
    expect(screen.getByText('Team Four')).toBeInTheDocument()
    expect(screen.getByText('Waiver ($12)')).toBeInTheDocument()
    expect(screen.getByText('Free agent')).toBeInTheDocument()
  })

  it('empty: prints the award\'s own emptyReason, never a blank', () => {
    show(award({ kind: 'JABARI_SMITH_JR', holders: [], playerHolders: [], emptyReason: 'Nobody was added by two or more teams.' }))
    expect(screen.getByText('Nobody was added by two or more teams.')).toBeInTheDocument()
  })

  it('tied holders: every holder is listed with their own week line', () => {
    show(award({
      holders: [
        { rosterId: 1, managerId: 10, teamName: 'Team A', username: null, avatarId: null },
        { rosterId: 2, managerId: 20, teamName: 'Team B', username: null, avatarId: null },
      ],
      detail: [
        { type: 'WEEK_SCORE', week: 3, rosterId: 1, points: 180.5 },
        { type: 'WEEK_SCORE', week: 4, rosterId: 2, points: 180.5 },
      ],
    }))
    expect(screen.getByText('Team A')).toBeInTheDocument()
    expect(screen.getByText('Team B')).toBeInTheDocument()
    expect(screen.getByText('180.50 points · week 3')).toBeInTheDocument()
    expect(screen.getByText('180.50 points · week 4')).toBeInTheDocument()
  })

  it('kind-level unavailable: prints the award\'s reason', () => {
    show(award({ available: false, reason: 'not built yet', value: null, unit: null }))
    expect(screen.getByText('not built yet')).toBeInTheDocument()
  })

  it('a coverage note rides under the holders', () => {
    show(award({
      kind: 'MOST_BENCH_POINTS',
      holders: [{ rosterId: 1, managerId: 10, teamName: 'Team A', username: 'a_user', avatarId: null }],
      detail: [{ type: 'BENCH_TOTAL', rosterId: 1, pointsLeft: 90.25, weeksCounted: 5, fromWeek: 1, throughWeek: 6, biggestWeek: { week: 3, pointsLeft: 30 } }],
      coverage: { weeksCovered: 5, weeksExcluded: 1, reasons: ['week 2 has no per-player breakdown'] },
    }))
    expect(screen.getByText(/5 of 6 weeks — week 2 has no per-player breakdown/)).toBeInTheDocument()
    expect(screen.getByText(/90.25 points left on the bench/)).toBeInTheDocument()
  })

  // ---- spec 051: The Trending Tony ----------------------------------------------------------------

  const tonyHolder = { rosterId: 7, managerId: 70, teamName: 'Chasers', username: null, avatarId: null }
  const tonyPickup = {
    type: 'TRENDING_PICKUP' as const, playerId: 'p9', playerName: 'Hot Pickup', position: 'WR', rosterId: 7,
    week: 3, addType: 'WAIVER' as const, trendingRank: 2, addCount: 55000, snapshotAt: '2026-10-14T09:00:00Z',
  }
  const coverage = { judgingSinceAt: '2026-10-14T09:00:00Z', judged: 8, notJudged: 3, totalPickups: 11 }
  const tony = (over: Partial<Superlative> = {}) => award({
    kind: 'TRENDING_TONY', unit: 'PICKUPS', value: 4, holders: [tonyHolder],
    detail: [tonyPickup, { ...tonyPickup, playerId: 'p10', playerName: 'Free Guy', week: 5, addType: 'FREE_AGENT', trendingRank: 11 }],
    trendingCoverage: coverage, ...over,
  })

  it('trending pickups: one line per pickup, with its rank, week and how it was added, and the player linked', () => {
    show(tony())
    expect(screen.getByRole('link', { name: 'Hot Pickup' })).toBeInTheDocument()
    expect(screen.getByText(/· #2 trending · week 3 · Waiver$/)).toBeInTheDocument()
    expect(screen.getByText(/· #11 trending · week 5 · Free agent$/)).toBeInTheDocument()
  })

  it('trending coverage (NFL): names the start date, the judged and unjudged counts, the week and the defense exclusion', () => {
    render(<AwardWinners s={tony()} hue={200} sport="nfl" leagueId="L1" throughWeek={6} />)
    const since = new Date('2026-10-14T09:00:00Z').toLocaleDateString()
    expect(screen.getByText(
      `Judged since ${since} · 8 of 11 pickups judged, 3 not (before trending history began or no list within a day) · pickups through week 6, team defenses excluded`,
    )).toBeInTheDocument()
  })

  it('trending coverage (NBA): the same line without the team-defenses clause', () => {
    show(tony())
    const since = new Date('2026-10-14T09:00:00Z').toLocaleDateString()
    expect(screen.getByText(
      `Judged since ${since} · 8 of 11 pickups judged, 3 not (before trending history began or no list within a day) · pickups through week 6`,
    )).toBeInTheDocument()
    expect(screen.queryByText(/team defenses/)).not.toBeInTheDocument()
  })

  it('trending, nobody named: the emptyReason AND the coverage line both print', () => {
    show(tony({ holders: [], detail: [], value: 1, emptyReason: 'nobody has chased the trending list twice yet' }))
    expect(screen.getByText('nobody has chased the trending list twice yet')).toBeInTheDocument()
    expect(screen.getByText(/8 of 11 pickups judged/)).toBeInTheDocument()
  })

  it.each([
    'Trending data comes from Sleeper; this league is on ESPN.',
    "Transactions for this season haven't loaded yet.",
  ])('trending unavailable: prints the reason "%s" and no coverage line', (reason) => {
    show(tony({ available: false, reason, holders: [], detail: [], value: null, unit: null, trendingCoverage: null }))
    expect(screen.getByText(reason)).toBeInTheDocument()
    expect(screen.queryByText(/pickups judged/)).not.toBeInTheDocument()
  })
})
