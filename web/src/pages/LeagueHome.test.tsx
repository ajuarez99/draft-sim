import { act, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '../api'
import type {
  DraftSummary,
  LeagueAnalysis,
  LeagueHistory,
  PowerRankings,
  StandingRow,
  SuperlativesResponse,
  WeeklyReport,
} from '../api'
import { invalidateRailLeagues } from '../railLeague'
import { LeagueDataVersionProvider, useBumpLeagueDataVersion } from '../leagueDataVersion'
import LeagueHome from './LeagueHome'

/*
 * specs/013 US4. One block per endpoint, so the tests are mostly about what
 * happens when an endpoint says nothing, fails, or does not apply.
 */

function draft(over: Partial<DraftSummary> = {}): DraftSummary {
  return {
    id: 1,
    sleeperDraftId: 'D1',
    leagueId: 1,
    leagueName: 'Ball Knowers',
    season: 2026,
    teams: 12,
    rounds: 15,
    status: 'complete',
    startTime: null,
    sleeperLeagueId: 'L1',
    previousLeagueId: null,
    sport: 'nfl',
    ...over,
  }
}

function row(rosterId: number, wins: number, over: Partial<StandingRow> = {}): StandingRow {
  return {
    leagueId: 1,
    rosterId,
    managerId: rosterId,
    manager: `user${rosterId}`,
    teamName: `Team ${rosterId}`,
    avatarId: null,
    wins,
    losses: 7 - wins,
    ties: 0,
    pointsFor: 100,
    pointsAgainst: 90,
    champion: false,
    season: null,
    sleeperLeagueId: null,
    ...over,
  } as StandingRow
}

function history(standings: StandingRow[]): LeagueHistory {
  return {
    sleeperLeagueId: 'L1',
    seasons: [{ season: 2026, leagueId: 1, sleeperLeagueId: 'L1', name: 'Ball Knowers', standings }],
    records: {} as LeagueHistory['records'],
  }
}

// Eight teams; "me" is roster 6, so sixth in the order the server sends.
const STANDINGS = [1, 2, 3, 4, 5, 6, 7, 8].map((r) => row(r, 9 - r, { isMe: r === 6 }))

function weekly(over: Partial<WeeklyReport> = {}): WeeklyReport {
  return {
    available: true,
    season: 2026,
    week: 3,
    latestScoredWeek: 3,
    latestFinalWeek: 3,
    weekFinal: true,
    sport: 'nfl',
    playersPlayMultiplePerPeriod: false,
    matchups: [
      {
        home: { rosterId: 6, teamName: 'Team 6', username: 'user6', avatarId: null, record: '3-0', points: 120.5, isMe: true },
        away: { rosterId: 2, teamName: 'Team 2', username: 'user2', avatarId: null, record: '0-3', points: 99.1, isMe: false },
      },
    ],
    awards: [],
    awardsOmitted: [],
    ...over,
  }
}

function analysis(): LeagueAnalysis {
  return {
    season: 2026,
    matchups: {
      available: true,
      reason: null,
      week: 4,
      matchups: [
        {
          matchupId: 1,
          sides: [
            { rosterId: 6, managerId: 6, manager: 'user6', avatarId: null, isMe: true, projected: 110.2, byPosition: {}, starters: [] },
            { rosterId: 3, managerId: 3, manager: 'user3', avatarId: null, isMe: false, projected: 105.4, byPosition: {}, starters: [] },
          ],
        },
      ],
    },
    teams: [
      { rosterId: 6, teamName: 'Team 6', username: 'user6' },
      { rosterId: 3, teamName: 'Team 3', username: 'user3' },
    ],
  } as unknown as LeagueAnalysis
}

function power(): PowerRankings {
  const e = (rosterId: number, rank: number, week: number) => ({
    season: 2026,
    week,
    kind: 'MEMBER',
    rosterId,
    managerId: rosterId,
    manager: `user${rosterId}`,
    teamName: `Team ${rosterId}`,
    avatarId: null,
    rank,
    score: null,
    note: null,
  })
  return {
    sleeperLeagueId: 'L1',
    sportState: { week: 3, season: '2026', seasonStartDate: '2026-09-01', started: true },
    entries: [e(1, 1, 2), e(2, 2, 2), e(2, 1, 3), e(1, 2, 3)],
  } as unknown as PowerRankings
}

function awards(): SuperlativesResponse {
  return {
    available: true,
    season: 2026,
    sport: 'nfl',
    throughWeek: 3,
    weeksScored: 3,
    early: true,
    superlatives: [
      {
        kind: 'HIGHEST_WEEK',
        available: true,
        early: true,
        value: 161.4,
        unit: 'POINTS',
        holders: [{ rosterId: 2, managerId: 2, teamName: 'Team 2', username: 'user2', avatarId: null }],
        detail: [],
        coverage: null,
        playerHolders: [],
        standings: [],
        playerStandings: [],
      },
    ],
  } as unknown as SuperlativesResponse
}

type Key = 'drafts' | 'history' | 'weekly' | 'analysis' | 'power' | 'awards'

function arrange(over: Partial<Record<Key, () => Promise<unknown>>> = {}) {
  invalidateRailLeagues()
  const ok = (v: unknown) => () => Promise.resolve(v)
  return {
    drafts: vi.spyOn(api, 'getDrafts').mockImplementation((over.drafts ?? ok([draft()])) as never),
    history: vi.spyOn(api, 'getLeagueHistory').mockImplementation((over.history ?? ok(history(STANDINGS))) as never),
    weekly: vi.spyOn(api, 'getWeeklyReport').mockImplementation((over.weekly ?? ok(weekly())) as never),
    analysis: vi.spyOn(api, 'getLeagueAnalysis').mockImplementation((over.analysis ?? ok(analysis())) as never),
    power: vi.spyOn(api, 'getPowerRankings').mockImplementation((over.power ?? ok(power())) as never),
    awards: vi.spyOn(api, 'fetchSuperlatives').mockImplementation((over.awards ?? ok(awards())) as never),
    // Spec 014: the spotlight is its own block; by default it does not apply, so no existing test sees it.
    // Spec 017: basketball's next opponent. Default is a non-member (me null), which renders nothing.
    nextMatchup: vi.spyOn(api, 'getNextMatchup').mockImplementation(
      (() =>
        Promise.resolve({ sport: 'nba', season: 2026, week: 1, available: true, reason: null, me: null, opponent: null })) as never,
    ),
    spotlight: vi
      .spyOn(api, 'getPlayerSpotlight')
      .mockImplementation(() => Promise.resolve({ applies: false, reason: 'PAST_SEASON', season: 2025, sport: 'nfl' }) as never),
  }
}

function renderHome() {
  return render(
    <MemoryRouter initialEntries={['/leagues/L1']}>
      <Routes>
        <Route path="/leagues/:sleeperLeagueId" element={<LeagueHome />} />
      </Routes>
    </MemoryRouter>,
  )
}

const section = (name: string) => screen.getByRole('heading', { name }).closest('section') as HTMLElement

beforeEach(() => invalidateRailLeagues())
afterEach(() => vi.restoreAllMocks())

describe('League home', () => {
  it('shows a member their record, standings position and latest matchup', async () => {
    arrange()
    renderHome()

    const you = await waitFor(() => section('Your season'))
    // Row 6 of 8 in the order the server sends (wins, then points for).
    expect(within(you).getByText('6th')).toBeInTheDocument()
    expect(within(you).getByText('3-4')).toBeInTheDocument()

    const latest = await waitFor(() => section('Latest matchup'))
    await waitFor(() => expect(within(latest).getByText('Team 2')).toBeInTheDocument())
    expect(within(latest).getByText('Won')).toBeInTheDocument()
    expect(within(latest).getByText('120.50')).toBeInTheDocument()
  })

  it('lists the top five and then your own row when you are outside them', async () => {
    arrange()
    renderHome()
    const standings = await waitFor(() => section('Standings'))
    await waitFor(() => expect(within(standings).getAllByRole('listitem')).toHaveLength(6))
    const names = within(standings)
      .getAllByRole('listitem')
      .map((li) => li.textContent ?? '')
    expect(names[4]).toContain('Team 5')
    expect(names[5]).toContain('Team 6')
  })

  it('adds the next opponent for football', async () => {
    arrange()
    renderHome()
    const next = await waitFor(() => section('Next opponent'))
    await waitFor(() => expect(within(next).getByText('Team 3')).toBeInTheDocument())
    expect(within(next).getByText(/A projection, not a result/)).toBeInTheDocument()
  })

  it('never asks basketball for the analysis projection', async () => {
    const spies = arrange({
      drafts: () => Promise.resolve([draft({ sport: 'nba' })]),
      weekly: () => Promise.resolve(weekly({ sport: 'nba' })),
    })
    renderHome()
    await waitFor(() => section('Latest matchup'))
    await waitFor(() => expect(section('Power rankings')).toBeInTheDocument())
    expect(spies.analysis).not.toHaveBeenCalled()
    await waitFor(() => expect(spies.nextMatchup).toHaveBeenCalled())
    // `me` is null in the default stub, so there is nothing to say about "you".
    expect(screen.queryByRole('heading', { name: 'Next opponent' })).toBeNull()
  })

  describe('basketball next opponent (specs/017 US3)', () => {
    const side = (rosterId: number, teamName: string | null, username: string | null) => ({
      rosterId,
      teamName,
      username,
      avatarId: null,
    })
    const arrangeNba = (nm: Partial<api.NextMatchup> = {}) => {
      const spies = arrange({
        drafts: () => Promise.resolve([draft({ sport: 'nba' })]),
        weekly: () => Promise.resolve(weekly({ sport: 'nba' })),
      })
      spies.nextMatchup.mockImplementation((() =>
        Promise.resolve({
          sport: 'nba',
          season: 2026,
          week: 3,
          available: true,
          reason: null,
          me: side(6, 'Team 6', 'user6'),
          opponent: side(3, 'Dunk Tank', 'popsharky'),
          ...nm,
        })) as never)
      return spies
    }

    it('names the opponent and shows no projected line', async () => {
      arrangeNba()
      renderHome()
      const next = await waitFor(() => section('Next opponent'))
      await waitFor(() => expect(within(next).getByText('Dunk Tank')).toBeInTheDocument())
      expect(within(next).getByText(/Week 3 against/)).toBeInTheDocument()
      expect(within(next).queryByText(/Projected/)).toBeNull()
    })

    it('falls back to the username when there is no team name', async () => {
      arrangeNba({ opponent: side(3, null, 'popsharky') })
      renderHome()
      const next = await waitFor(() => section('Next opponent'))
      await waitFor(() => expect(within(next).getByText('popsharky')).toBeInTheDocument())
    })

    it('falls back to "roster N" when there is neither', async () => {
      arrangeNba({ opponent: side(3, null, null) })
      renderHome()
      const next = await waitFor(() => section('Next opponent'))
      await waitFor(() => expect(within(next).getByText('roster 3')).toBeInTheDocument())
    })

    it('says so on a bye', async () => {
      arrangeNba({ opponent: null })
      renderHome()
      const next = await waitFor(() => section('Next opponent'))
      await waitFor(() => expect(within(next).getByText('You have a bye in week 3.')).toBeInTheDocument())
    })

    it('shows the reason when pairings are not available', async () => {
      arrangeNba({ available: false, me: null, opponent: null, reason: "Pairings for week 1 aren't out yet." })
      renderHome()
      const next = await waitFor(() => section('Next opponent'))
      await waitFor(() => expect(within(next).getByText(/aren't out yet/)).toBeInTheDocument())
    })

    it('renders nothing when the reader has no roster', async () => {
      const spies = arrangeNba({ me: null, opponent: null })
      renderHome()
      await waitFor(() => section('Power rankings'))
      await waitFor(() => expect(spies.nextMatchup).toHaveBeenCalled())
      expect(screen.queryByRole('heading', { name: 'Next opponent' })).toBeNull()
    })

    // F9: Analysis is football-only, so a basketball league links to the schedule grid.
    it('links to the schedule grid, not Team strength', async () => {
      arrangeNba()
      renderHome()
      const next = await waitFor(() => section('Next opponent'))
      const link = await waitFor(() => within(next).getByRole('link', { name: 'Schedule grid' }))
      expect(link).toHaveAttribute('href', '/leagues/L1/schedule')
      expect(within(next).queryByRole('link', { name: 'Team strength' })).toBeNull()
    })

    // F8: the visit's own refresh must refill this block without a reload.
    it('refetches when the league data version is bumped', async () => {
      const spies = arrangeNba()
      let bump: (id: string) => void = () => {}
      function Grab() {
        bump = useBumpLeagueDataVersion()
        return null
      }
      render(
        <LeagueDataVersionProvider>
          <Grab />
          <MemoryRouter initialEntries={['/leagues/L1']}>
            <Routes>
              <Route path="/leagues/:sleeperLeagueId" element={<LeagueHome />} />
            </Routes>
          </MemoryRouter>
        </LeagueDataVersionProvider>,
      )
      await waitFor(() => expect(spies.nextMatchup).toHaveBeenCalledTimes(1))
      act(() => bump('L1'))
      await waitFor(() => expect(spies.nextMatchup).toHaveBeenCalledTimes(2))
    })
  })

  // The guard until spec 017 T043: football is unchanged.
  it('keeps football on the analysis block, with its Team strength link and projected line', async () => {
    const spies = arrange()
    renderHome()
    const next = await waitFor(() => section('Next opponent'))
    await waitFor(() => expect(within(next).getByText(/Projected 110.2 to 105.4/)).toBeInTheDocument())
    expect(within(next).getByRole('link', { name: 'Team strength' })).toHaveAttribute('href', '/leagues/L1/analysis')
    expect(spies.nextMatchup).not.toHaveBeenCalled()
  })

  it('shows the power headline the Power page would show', async () => {
    arrange()
    renderHome()
    const block = await waitFor(() => section('Power rankings'))
    // Team 2 took over No. 1 in the latest week: PowerRankings' own buildHeadline.
    await waitFor(() => expect(within(block).getByText('Team 2 is your new No. 1')).toBeInTheDocument())
  })

  it('shows the top award, with the early caveat beside it', async () => {
    arrange()
    renderHome()
    const block = await waitFor(() => section('Awards'))
    await waitFor(() => expect(within(block).getByText('Highest week')).toBeInTheDocument())
    expect(within(block).getByText(/161.4 pts/)).toBeInTheDocument()
    expect(within(block).getByText('early — this is mostly noise')).toBeInTheDocument()
  })

  it('shows a non-member the league without any "you" block, and no error', async () => {
    const a = analysis()
    arrange({
      history: () => Promise.resolve(history(STANDINGS.map((r) => ({ ...r, isMe: false })))),
      weekly: () =>
        Promise.resolve(
          weekly({
            matchups: [
              {
                home: { rosterId: 1, teamName: 'Team 1', username: null, avatarId: null, record: '', points: 1, isMe: false },
                away: { rosterId: 2, teamName: 'Team 2', username: null, avatarId: null, record: '', points: 2, isMe: false },
              },
            ],
          }),
        ),
      analysis: () => Promise.resolve({ ...a, matchups: { ...a.matchups, matchups: [] } }),
    })
    renderHome()

    await waitFor(() => section('Standings'))
    await waitFor(() => expect(within(section('Standings')).getAllByRole('listitem').length).toBeGreaterThan(0))
    // Give the weekly and analysis blocks the chance to (wrongly) appear.
    await waitFor(() => expect(within(section('Power rankings')).getByText(/No\. 1/)).toBeInTheDocument())
    expect(screen.queryByRole('heading', { name: 'Your season' })).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Latest matchup' })).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Next opponent' })).toBeNull()
    expect(screen.queryByText(/Couldn't load/)).toBeNull()
  })

  it('leaves the other blocks standing when one endpoint fails', async () => {
    arrange({ power: () => Promise.reject(new Error('boom')) })
    renderHome()

    const power = await waitFor(() => section('Power rankings'))
    await waitFor(() => expect(within(power).getByText("Couldn't load the power rankings.")).toBeInTheDocument())
    expect(await screen.findByText('6th')).toBeInTheDocument()
    const awardsBlock = await waitFor(() => section('Awards'))
    await waitFor(() => expect(within(awardsBlock).getByText('Highest week')).toBeInTheDocument())
  })

  it('shows draft status and a draft-room link before the draft, instead of a matchup', async () => {
    arrange({
      drafts: () => Promise.resolve([draft({ status: 'pre_draft' })]),
      history: () => Promise.resolve(history([])),
    })
    renderHome()

    const block = await waitFor(() => section('Draft'))
    expect(within(block).getByText("The draft hasn't started.")).toBeInTheDocument()
    expect(within(block).getByRole('link', { name: 'Open the draft room' })).toHaveAttribute('href', '/drafts/D1')
    expect(within(block).getByRole('link', { name: 'Follow live' })).toHaveAttribute('href', '/drafts/D1/live')
    expect(screen.queryByRole('heading', { name: 'Latest matchup' })).toBeNull()
  })

  it('says so when no week has been scored', async () => {
    arrange({ weekly: () => Promise.resolve(weekly({ available: false, week: 0, matchups: [] })) })
    renderHome()
    const latest = await waitFor(() => section('Latest matchup'))
    await waitFor(() => expect(within(latest).getByText('No week has been scored yet.')).toBeInTheDocument())
  })

  /** Spec 013 parent review: a position needs games behind it. */
  it('claims no standings position before anyone has played', async () => {
    const zero = [1, 2, 3, 4].map((r) => row(r, 0, { losses: 0, isMe: r === 1 }))
    arrange({ history: () => Promise.resolve(history(zero)) })
    renderHome()
    expect(await screen.findByText("Your season hasn't started yet.")).toBeInTheDocument()
    expect(screen.queryByText(/1st of 4/)).not.toBeInTheDocument()
  })

  /** The weekly report falls back to the newest PLAYED season; that week is not this season's. */
  it("does not show another season's matchup as the latest", async () => {
    arrange({ weekly: () => Promise.resolve(weekly({ season: 2025, requestedSeason: 2026 })) })
    renderHome()
    const latest = await waitFor(() => section('Latest matchup'))
    await waitFor(() => expect(within(latest).getByText('No week of 2026 has been scored yet.')).toBeInTheDocument())
    expect(within(latest).queryByText('Team 2')).toBeNull()
  })

  it("does not headline another season's power vote", async () => {
    const p = power()
    arrange({
      power: () =>
        Promise.resolve({
          ...p,
          entries: (p.entries as unknown as { season: number }[]).map((e) => ({ ...e, season: 2025 })),
        } as unknown as PowerRankings),
    })
    renderHome()
    const block = await waitFor(() => section('Power rankings'))
    await waitFor(() => expect(within(block).getByText("The 2026 league vote hasn't started yet.")).toBeInTheDocument())
    expect(within(block).queryByText(/No\. 1/)).toBeNull()
  })

  it('calls a live game Leading, not Won', async () => {
    arrange({ weekly: () => Promise.resolve(weekly({ weekFinal: false })) })
    renderHome()
    const latest = await waitFor(() => section('Latest matchup'))
    await waitFor(() => expect(within(latest).getByText('Leading')).toBeInTheDocument())
    expect(within(latest).queryByText('Won')).toBeNull()
    expect(within(latest).getByText(/in progress/)).toBeInTheDocument()
  })

  it('calls a live game Trailing when behind', async () => {
    const w = weekly({ weekFinal: false })
    w.matchups[0].home.points = 50
    arrange({ weekly: () => Promise.resolve(w) })
    renderHome()
    const latest = await waitFor(() => section('Latest matchup'))
    await waitFor(() => expect(within(latest).getByText('Trailing')).toBeInTheDocument())
  })

  /** The awards endpoint can fall back a season; the home says so. */
  it('labels an award that comes from an earlier season', async () => {
    arrange({ awards: () => Promise.resolve({ ...awards(), season: 2025 }) })
    renderHome()
    expect(await screen.findByText(/From the 2025 season; 2026 has no games yet/)).toBeInTheDocument()
  })
})
