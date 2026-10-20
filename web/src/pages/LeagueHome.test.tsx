import { act, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '../api'
import type {
  DraftSummary,
  SeasonStart,
  LeagueAnalysis,
  LeagueHistory,
  PowerRankings,
  StandingRow,
  SuperlativesResponse,
  WeeklyReport,
} from '../api'
import { invalidateRailLeagues } from '../railLeague'
import { invalidateLeagueAvailability } from '../useLeagueAvailability'
import { strength } from '../testSeasonStart'
import { LeagueDataVersionProvider, useBumpLeagueDataVersion } from '../leagueDataVersion'
import LeagueHome from './LeagueHome'

/*
 * specs/013 US4. One block per endpoint, so the tests are mostly about what
 * happens when an endpoint says nothing, fails, or does not apply.
 */

function draft(over: Partial<DraftSummary> = {}): DraftSummary {
  return {
    platform: 'SLEEPER',
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
        playerStandings: [], playerContributions: [],
      },
    ],
  } as unknown as SuperlativesResponse
}

/** A season-start payload; defaults to IN_SEASON, whose layout is the page as it was before spec 030. */
function seasonStart(over: Partial<SeasonStart> = {}): SeasonStart {
  return {
    sport: 'nfl',
    season: 2026,
    state: 'IN_SEASON',
    firstWeek: null,
    strength: null,
    draft: { sleeperDraftId: 'D1', grades: null },
    ...over,
  }
}

type Key = 'drafts' | 'history' | 'weekly' | 'analysis' | 'power' | 'awards' | 'seasonStart'

function arrange(over: Partial<Record<Key, () => Promise<unknown>>> = {}) {
  invalidateRailLeagues()
  const ok = (v: unknown) => () => Promise.resolve(v)
  return {
    drafts: vi.spyOn(api, 'getDrafts').mockImplementation((over.drafts ?? ok([draft()])) as never),
    history: vi.spyOn(api, 'getLeagueHistory').mockImplementation((over.history ?? ok(history(STANDINGS))) as never),
    weekly: vi.spyOn(api, 'getWeeklyReport').mockImplementation((over.weekly ?? ok(weekly())) as never),
    analysis: vi.spyOn(api, 'getLeagueAnalysis').mockImplementation((over.analysis ?? ok(analysis())) as never),
    seasonStart: vi.spyOn(api, 'getSeasonStart').mockImplementation((over.seasonStart ?? ok(seasonStart())) as never),
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

beforeEach(() => {
  invalidateRailLeagues()
  invalidateLeagueAvailability()
})
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
      seasonStart: () => Promise.resolve(seasonStart({ sport: 'nba' })),
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
      seasonStart: () => Promise.resolve(seasonStart({ state: 'PRE_DRAFT', draft: { sleeperDraftId: 'D1', grades: null } })),
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

/** Spec 030: the layout follows `season-start.state`, and DRAFTED_UNSCORED drops last season's blocks. */
describe('League home by season state (spec 030)', () => {
  const nbaSide = (rosterId: number, teamName: string) => ({ rosterId, teamName, username: `u${rosterId}`, avatarId: null })
  // Before week 1 the weekly report falls back to the newest PLAYED season, which is last year's.
  const lastSeasonsWeek = () => weekly({ sport: 'nba', season: 2025, requestedSeason: 2026 })
  const arrangeNba = (ss: Partial<SeasonStart>, nm: Partial<api.NextMatchup> = {}, wk: () => unknown = lastSeasonsWeek) => {
    const spies = arrange({
      drafts: () => Promise.resolve([draft({ sport: 'nba' })]),
      weekly: () => Promise.resolve(wk()),
      seasonStart: () => Promise.resolve(seasonStart({ sport: 'nba', ...ss })),
    })
    spies.nextMatchup.mockImplementation((() =>
      Promise.resolve({
        sport: 'nba',
        season: 2026,
        week: 1,
        available: true,
        reason: null,
        me: nbaSide(9, 'Dunk Tank'),
        opponent: nbaSide(3, 'Brick City'),
        ...nm,
      })) as never)
    return spies
  }

  const unscored = (over: Partial<SeasonStart> = {}): Partial<SeasonStart> => ({
    state: 'DRAFTED_UNSCORED',
    firstWeek: { week: 1, startDate: '2026-10-20', startersWithoutGame: null },
    strength: strength(),
    draft: { sleeperDraftId: 'D7', grades: null },
    ...over,
  })

  const heading = (name: string) => screen.queryByRole('heading', { name })

  describe('DRAFTED_UNSCORED', () => {
    it('drops the matchup, power, award and standings blocks and shows the strength block', async () => {
      arrangeNba(unscored())
      renderHome()
      await waitFor(() => section('Projected strength'))
      expect(heading('Latest matchup')).toBeNull()
      expect(heading('Power rankings')).toBeNull()
      expect(heading('Awards')).toBeNull()
      expect(heading('Standings')).toBeNull()
    })

    it('names the season and the date of the first games, formatted without a UTC parse', async () => {
      arrangeNba(unscored())
      renderHome()
      expect(await screen.findByText('2026 · draft complete · first games Oct 20')).toBeInTheDocument()
    })

    it('says the date is not known when startDate is null', async () => {
      arrangeNba(unscored({ firstWeek: { week: 1, startDate: null, startersWithoutGame: null } }))
      renderHome()
      expect(await screen.findByText('2026 · draft complete · first games: date not known yet')).toBeInTheDocument()
    })

    it('links the completed draft board by the Sleeper draft id', async () => {
      arrangeNba(unscored())
      renderHome()
      const block = await waitFor(() => section('Draft'))
      expect(within(block).getByRole('link', { name: 'View the draft board' })).toHaveAttribute('href', '/drafts/D7/board')
    })

    it('keeps the matchup block only when it holds a week of this season', async () => {
      arrangeNba(unscored(), {}, () => weekly({ sport: 'nba' }))
      renderHome()
      await waitFor(() => section('Projected strength'))
      await waitFor(() => section('Latest matchup'))
      // Power and awards stay dropped either way.
      expect(heading('Power rankings')).toBeNull()
      expect(heading('Awards')).toBeNull()
    })

    it('does not ask for the football analysis and renders exactly one Next opponent heading', async () => {
      const spies = arrangeNba(unscored())
      renderHome()
      await waitFor(() => section('Next opponent'))
      expect(spies.analysis).not.toHaveBeenCalled()
      expect(screen.getAllByRole('heading', { name: 'Next opponent' })).toHaveLength(1)
      expect(document.querySelectorAll('#lh-next-h')).toHaveLength(1)
      // Basketball's pairing block keeps linking the schedule grid, not Team strength.
      const next = section('Next opponent')
      expect(within(next).getByRole('link', { name: 'Schedule grid' })).toHaveAttribute('href', '/leagues/L1/schedule')
    })

    it('says how many projected starters have no game in week 1, by name', async () => {
      arrangeNba(
        unscored({
          firstWeek: {
            week: 1,
            startDate: '2026-10-20',
            startersWithoutGame: {
              week: 1,
              mine: [{ sleeperPlayerId: 'p1', name: 'Victor Wembanyama', team: 'SAS' }, { sleeperPlayerId: 'p2', name: 'Some Guy', team: null }],
              countByRoster: { '9': 2 },
            },
          },
        }),
      )
      renderHome()
      const next = await waitFor(() => section('Next opponent'))
      await waitFor(() => expect(within(next).getByText('2 of your projected starters have no game in week 1')).toBeInTheDocument())
      expect(within(next).getByText(/Victor Wembanyama \(SAS\), Some Guy\. Scheduled games only\./)).toBeInTheDocument()
    })

    it('says so when every projected starter plays', async () => {
      arrangeNba(
        unscored({
          firstWeek: { week: 1, startDate: '2026-10-20', startersWithoutGame: { week: 1, mine: [], countByRoster: {} } },
        }),
      )
      renderHome()
      expect(await screen.findByText('All your projected starters play in week 1.')).toBeInTheDocument()
    })

    it('says the schedule is not loaded when startersWithoutGame is null', async () => {
      arrangeNba(unscored())
      renderHome()
      expect(await screen.findByText("The week 1 schedule isn't loaded yet.")).toBeInTheDocument()
    })

    it('says nothing about the schedule when strength is unavailable (no lineup, no starters)', async () => {
      arrangeNba(
        unscored({
          strength: strength({ available: false, reason: 'raw', reasonCode: 'PROJECTIONS_NOT_LOADED', top: [], me: null, favorite: null, teamLabels: [] }),
        }),
      )
      renderHome()
      await waitFor(() => section('Projected strength'))
      expect(screen.queryByText(/schedule isn't loaded/)).toBeNull()
      expect(screen.queryByText(/projected starters/)).toBeNull()
    })

    it('says nothing when the server has no idle-starter answer for the caller (mine null)', async () => {
      arrangeNba(
        unscored({
          firstWeek: { week: 1, startDate: '2026-10-20', startersWithoutGame: { week: 1, mine: null, countByRoster: {} } },
        }),
      )
      renderHome()
      await waitFor(() => section('Projected strength'))
      expect(screen.queryByText(/All your projected starters/)).toBeNull()
      expect(screen.queryByText(/schedule isn't loaded/)).toBeNull()
    })

    it('shows no grades block and no games note to a reader with no roster', async () => {
      arrangeNba(unscored(), { me: null })
      renderHome()
      await waitFor(() => section('Projected strength'))
      expect(heading('Draft grades')).toBeNull()
      expect(screen.queryByText(/projected starters/)).toBeNull()
    })
  })

  describe('while season-start loads or fails', () => {
    it('holds back last season\'s blocks until season-start resolves', async () => {
      let release: (v: unknown) => void = () => {}
      arrange({ seasonStart: () => new Promise((r) => (release = r)) })
      renderHome()
      await screen.findByText('6th')
      expect(heading('Latest matchup')).toBeNull()
      expect(heading('Power rankings')).toBeNull()
      expect(heading('Awards')).toBeNull()
      await act(async () => release(seasonStart()))
      await waitFor(() => section('Latest matchup'))
      expect(section('Power rankings')).toBeInTheDocument()
    })

    it('falls back to today\'s layout on an error', async () => {
      arrange({ seasonStart: () => Promise.reject(new Error('boom')) })
      renderHome()
      await waitFor(() => section('Latest matchup'))
      expect(section('Power rankings')).toBeInTheDocument()
      expect(section('Awards')).toBeInTheDocument()
      expect(section('Standings')).toBeInTheDocument()
    })
  })

  describe('PRE_DRAFT, IN_SEASON and COMPLETE', () => {
    const headings = () =>
      screen
        .getAllByRole('heading', { level: 2 })
        .map((h) => h.textContent)
        .sort()

    it('IN_SEASON keeps exactly the blocks the page had before spec 030', async () => {
      arrange()
      renderHome()
      await waitFor(() => section('Latest matchup'))
      await waitFor(() => section('Awards'))
      expect(headings()).toEqual(['Awards', 'Latest matchup', 'Next opponent', 'Power rankings', 'Standings', 'Your season'])
    })

    it('COMPLETE renders the same blocks as IN_SEASON', async () => {
      arrange({ seasonStart: () => Promise.resolve(seasonStart({ state: 'COMPLETE' })) })
      renderHome()
      await waitFor(() => section('Awards'))
      expect(headings()).toEqual(['Awards', 'Latest matchup', 'Next opponent', 'Power rankings', 'Standings', 'Your season'])
    })

    it('PRE_DRAFT shows the draft block and no matchup', async () => {
      arrange({
        drafts: () => Promise.resolve([draft({ status: 'pre_draft' })]),
        history: () => Promise.resolve(history([])),
        seasonStart: () => Promise.resolve(seasonStart({ state: 'PRE_DRAFT' })),
      })
      renderHome()
      await waitFor(() => section('Draft'))
      expect(heading('Latest matchup')).toBeNull()
      expect(heading('Projected strength')).toBeNull()
    })

    it('ignores the draft status once season-start has spoken', async () => {
      // The draft row says "not complete", but the state says the draft is done and games are on.
      arrange({
        drafts: () => Promise.resolve([draft({ status: 'drafting' })]),
        seasonStart: () => Promise.resolve(seasonStart({ state: 'IN_SEASON' })),
      })
      renderHome()
      await waitFor(() => section('Latest matchup'))
    })
  })

  describe('IN_SEASON draft grades summary', () => {
    const grades = (over = {}) => ({
      best: { manager: 'popsharky', avatarId: null, grade: 'A', rank: 1 },
      worst: { manager: 'kieriskash', avatarId: null, grade: 'D', rank: 12 },
      gradesEarly: true,
      earlyThresholdWeeks: 4,
      ...over,
    })

    it('shows best and worst with the early badge and a link to the draft board', async () => {
      arrange({ seasonStart: () => Promise.resolve(seasonStart({ draft: { sleeperDraftId: 'D7', grades: grades() } })) })
      renderHome()
      const block = await waitFor(() => section('Draft grades'))
      expect(within(block).getByText('popsharky')).toBeInTheDocument()
      expect(within(block).getByText('A')).toBeInTheDocument()
      expect(within(block).getByText('kieriskash')).toBeInTheDocument()
      expect(within(block).getByText('D')).toBeInTheDocument()
      expect(within(block).getByText(/early/)).toBeInTheDocument()
      expect(within(block).getByRole('link', { name: 'Draft board and grades' })).toHaveAttribute('href', '/drafts/D7/board')
    })

    it('omits the early badge once grades are settled', async () => {
      arrange({
        seasonStart: () => Promise.resolve(seasonStart({ draft: { sleeperDraftId: 'D7', grades: grades({ gradesEarly: false }) } })),
      })
      renderHome()
      const block = await waitFor(() => section('Draft grades'))
      expect(within(block).queryByText(/early/)).toBeNull()
    })

    it('shows no grades block when grades are null, and is the only IN_SEASON addition when present', async () => {
      arrange()
      renderHome()
      await waitFor(() => section('Awards'))
      expect(heading('Draft grades')).toBeNull()
    })
  })
})

describe('League home for an ESPN league (spec 035 F13)', () => {
  const reason = (code: string, message: string) => ({ code, message })
  const espnAvailability = (over: Partial<api.LeagueAvailability> = {}): api.LeagueAvailability => ({
    leagueKey: 'L1',
    platform: 'ESPN',
    unmatchedPlayers: 0,
    lastRefresh: { succeededAt: new Date(Date.now() - 5 * 60000).toISOString(), failedAt: null, failure: null },
    canReplaceCookies: false,
    unmatched: [],
    unavailable: {
      draftRoom: reason('PLATFORM_NOT_SUPPORTED', 'Live and simulated drafts follow Sleeper drafts only.'),
      draftGrades: reason('PLATFORM_NOT_SUPPORTED', 'Draft grades are built from Sleeper drafts only.'),
      stats: reason('SCORING_UNTRANSLATED', 'Scoring has rules we cannot translate yet.'),
      schedule: reason('PLATFORM_NOT_SUPPORTED', 'The schedule grid follows Sleeper leagues only.'),
      superlatives: reason('PLATFORM_NOT_SUPPORTED', 'Season superlatives follow Sleeper leagues only.'),
      weeklyReport: reason('PLATFORM_NOT_SUPPORTED', 'The weekly report follows Sleeper leagues only.'),
    },
    ...over,
  })

  it('does not fetch a block the league cannot show, says so in one line each, and keeps standings and power', async () => {
    const avail = vi.spyOn(api, 'getLeagueAvailability').mockResolvedValue(espnAvailability())
    const spies = arrange({ drafts: () => Promise.resolve([draft({ platform: 'ESPN' })]) })
    renderHome()

    await waitFor(() => section('Standings'))
    await waitFor(() => expect(within(section('Standings')).getAllByRole('listitem').length).toBeGreaterThan(0))
    await waitFor(() => expect(within(section('Power rankings')).getByText(/No\. 1/)).toBeInTheDocument())

    expect(avail).toHaveBeenCalledWith('L1')
    // The gated blocks were never requested, not requested-and-refused.
    expect(spies.weekly).not.toHaveBeenCalled()
    expect(spies.awards).not.toHaveBeenCalled()
    expect(spies.spotlight).not.toHaveBeenCalled()
    expect(spies.nextMatchup).not.toHaveBeenCalled()
    // One line per hidden block, in the server's words.
    expect(screen.getByText(/Season superlatives follow Sleeper leagues only\./)).toBeInTheDocument()
    expect(screen.getByText(/The weekly report follows Sleeper leagues only\./)).toBeInTheDocument()
    expect(screen.getByText(/Scoring has rules we cannot translate yet\./)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Latest matchup' })).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Awards' })).toBeNull()
    // The last-refresh line is there.
    expect(screen.getByText(/Last refreshed 5 minutes ago/)).toBeInTheDocument()
  })

  it('shows the failure as text with its code, and only the cookie owner is offered "Replace cookies"', async () => {
    const failed = espnAvailability({
      lastRefresh: { succeededAt: new Date(Date.now() - 3600000).toISOString(), failedAt: new Date().toISOString(), failure: 'ESPN_ACCESS_DENIED' },
    })
    vi.spyOn(api, 'getLeagueAvailability').mockResolvedValue({ ...failed, canReplaceCookies: false })
    arrange({ drafts: () => Promise.resolve([draft({ platform: 'ESPN' })]) })
    const first = renderHome()
    expect(await screen.findByText('ESPN_ACCESS_DENIED')).toBeInTheDocument()
    expect(screen.getByText(/refused the stored login cookies/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Replace cookies' })).toBeNull()
    first.unmount()
    invalidateLeagueAvailability()

    vi.spyOn(api, 'getLeagueAvailability').mockResolvedValue({ ...failed, canReplaceCookies: true })
    arrange({ drafts: () => Promise.resolve([draft({ platform: 'ESPN' })]) })
    renderHome()
    expect(await screen.findByRole('button', { name: 'Replace cookies' })).toBeInTheDocument()
  })

  it('shows the unmatched-player count and names for an ESPN league (FR-007)', async () => {
    vi.spyOn(api, 'getLeagueAvailability').mockResolvedValue(espnAvailability({
      unmatchedPlayers: 2,
      unmatched: [
        { name: 'Zed Ambig', position: 'SF', team: 'ATL', outcome: 'AMBIGUOUS' },
        { name: 'Ghost Player', position: null, team: null, outcome: 'UNMATCHED' },
      ],
    }))
    arrange({ drafts: () => Promise.resolve([draft({ platform: 'ESPN' })]) })
    renderHome()
    expect(await screen.findByText(/2 players on these rosters could not be matched/)).toBeInTheDocument()
    expect(screen.getByText(/Zed Ambig/)).toBeInTheDocument()
    expect(screen.getByText(/Ghost Player/)).toBeInTheDocument()
  })

  it('never asks the availability endpoint for a Sleeper league, and fetches every block as before', async () => {
    const avail = vi.spyOn(api, 'getLeagueAvailability')
    const spies = arrange()
    renderHome()
    await waitFor(() => section('Standings'))
    await waitFor(() => expect(spies.weekly).toHaveBeenCalled())
    expect(spies.awards).toHaveBeenCalled()
    expect(spies.spotlight).toHaveBeenCalled()
    expect(avail).not.toHaveBeenCalled()
    expect(screen.queryByText(/Last refreshed/)).toBeNull()
  })
})
