import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ManagerHistory from './ManagerHistory'
import type {
  CareerProfile,
  CareerSeason,
  ManagerHistory as ManagerHistoryData,
  StandingRow,
} from '../api'

vi.mock('react-router-dom', () => ({
  useParams: () => ({ managerId: '7' }),
  Link: ({ children }: { children: React.ReactNode }) => <a href="#">{children}</a>,
}))

const getManagerHistory = vi.fn()
vi.mock('../api', () => ({
  getManagerHistory: (...args: unknown[]) => getManagerHistory(...args),
}))

/**
 * specs/006-deeper-history-both-sports T012 (US1, SC-001). Mirrors the exact
 * shape baseline.md's T002 measured live for popsharky (manager 7): three
 * seasons stamped `nfl`, three stamped `nba`, spread across three leagues --
 * two of them football leagues that both played a 2026 season.
 */
function standing(overrides: Partial<StandingRow>): StandingRow {
  return {
    leagueId: 1,
    rosterId: 1, managerId: 7, manager: 'popsharky', avatarId: null,
    wins: 0, losses: 0, ties: 0, pointsFor: 0, pointsAgainst: 0,
    champion: false, season: 2025, sleeperLeagueId: 'L1',
    sport: 'nfl', leagueName: 'League', complete: true,
    ...overrides,
  }
}

/**
 * A row as `careers[].seasons[]` carries it: `standing()`'s shape plus
 * `counted`. Since T076 this is the ONLY place season rows reach this page --
 * the flat `seasons[]` these fixtures used to set is gone from the response,
 * so a fixture that sets one would be testing a payload the server cannot
 * send.
 */
function careerSeason(overrides: Partial<CareerSeason> = {}): CareerSeason {
  return { ...standing(overrides), counted: true, ...overrides }
}

const NFL_ROWS: CareerSeason[] = [
  careerSeason({
    sport: 'nfl', season: 2026, leagueName: 'West Coast Fantasy Football', sleeperLeagueId: 'L-wc',
    rosterId: 7, wins: 1, losses: 0, pointsFor: 189.9, pointsAgainst: 140, complete: false,
  }),
  careerSeason({
    sport: 'nfl', season: 2026, leagueName: '(Foot) Ball Knowers', sleeperLeagueId: 'L-fbk-26',
    rosterId: 1, wins: 1, losses: 0, pointsFor: 157.4, pointsAgainst: 147.9, complete: false, champion: true,
  }),
  careerSeason({
    sport: 'nfl', season: 2025, leagueName: '(Foot) Ball Knowers', sleeperLeagueId: 'L-fbk-25',
    rosterId: 1, wins: 10, losses: 4, pointsFor: 2042.84, pointsAgainst: 1771.16, champion: true,
  }),
]

const NBA_ROWS: CareerSeason[] = [
  careerSeason({
    sport: 'nba', season: 2025, leagueName: 'Ball Knowers', sleeperLeagueId: 'L-bk-25',
    rosterId: 7, wins: 8, losses: 10, pointsFor: 4370.0, pointsAgainst: 4200.0,
  }),
  careerSeason({
    sport: 'nba', season: 2024, leagueName: 'Ball Knowers', sleeperLeagueId: 'L-bk-24',
    rosterId: 7, wins: 9, losses: 11, pointsFor: 5146.0, pointsAgainst: 5000.0,
  }),
]

function twoSportHistory(): ManagerHistoryData {
  return {
    managerId: 7,
    manager: 'popsharky',
    avatarId: null,
    draftHistory: [],
    // T076: the rows live HERE now, one career per sport, because the flat
    // `seasons[]` these fixtures used to carry no longer exists on the wire.
    // The totals match what the page used to derive by summing those rows
    // (nfl 12-4 over three, nba 17-21 over two), so every assertion below
    // still describes the same screen -- the difference is that the numbers
    // now have exactly one source, which is the point of the removal.
    careers: [
      careerProfile({
        sport: 'nfl', seasons: NFL_ROWS, seasonsCounted: 3,
        wins: 12, losses: 4, ties: 0, titles: 1,
      }),
      careerProfile({
        sport: 'nba', seasons: NBA_ROWS, seasonsCounted: 2,
        wins: 17, losses: 21, ties: 0, titles: 0,
      }),
    ],
  }
}

/**
 * specs/006-deeper-history-both-sports T029/T034: mirrors
 * ManagerCareerService.CareerProfile, and contracts/manager-profile-api.md's
 * worked example for popsharky's football career, scoped to (Foot) Ball
 * Knowers alone.
 */
function careerProfile(overrides: Partial<CareerProfile> = {}): CareerProfile {
  return {
    sport: 'nfl',
    seasonsCounted: 2,
    seasons: [],
    wins: 12,
    losses: 4,
    ties: 0,
    winRate: 0.75,
    pointsFor: 2390.14,
    pointsAgainst: 2059.06,
    pointsPerSeason: 796.71,
    averageEfficiency: 0.916,
    weeksCounted: 19,
    weeksExcluded: 0,
    winsAboveExpected: 1.82,
    titles: 1,
    unavailable: [
      {
        figure: 'playoffAppearances',
        reason: "only the champion's placement is stored; the bracket is not parsed",
      },
      {
        figure: 'tradesPerSeason',
        reason: 'trades are not attributed to a manager (a trade names several rosters)',
      },
    ],
    ranks: [
      {
        figure: 'winRate',
        position: 2,
        population: 12,
        leagueName: '(Foot) Ball Knowers',
        sleeperLeagueId: 'L-fbk-26',
      },
      {
        figure: 'averageEfficiency',
        position: 4,
        population: 12,
        leagueName: '(Foot) Ball Knowers',
        sleeperLeagueId: 'L-fbk-26',
      },
    ],
    // specs/006-deeper-history-both-sports T075 (US6). Mirrors
    // contracts/manager-profile-api.md's own worked example: one FAAB season
    // ((Foot) Ball Knowers 2026) and one excluded for waiver priority
    // ((Foot) Ball Knowers 2025) -- both real seasons in the fixture above.
    waivers: {
      movesPerSeason: 29.5,
      seasonsCounted: 2,
      faab: {
        typicalBidPct: 0.26,
        largestBidPct: 0.26,
        spentPerSeasonPct: 0.26,
        claimsPerSeason: 1.0,
        bidSuccessRate: 0.5,
      },
      faabExcludedSeasons: [
        { season: 2025, leagueName: '(Foot) Ball Knowers', reason: 'used waiver priority, not FAAB' },
      ],
    },
    ...overrides,
  }
}

function historyWithCareer(overrides: Partial<CareerProfile> = {}): ManagerHistoryData {
  const base = twoSportHistory()
  base.careers = [careerProfile(overrides)]
  return base
}

beforeEach(() => {
  getManagerHistory.mockReset()
})

describe('per-sport grouping (FR-002)', () => {
  it('renders no combined record, points total or season count spanning both sports', async () => {
    getManagerHistory.mockResolvedValue(twoSportHistory())
    render(<ManagerHistory />)

    await screen.findByText('popsharky')

    // The old bug: 1+1+10 wins football, 8+9 wins basketball, summed to 29.
    // Neither the old combined win total (29) nor the old combined loss
    // total (25) may appear anywhere on the page.
    expect(screen.queryByText(/29-25/)).toBeNull()
    // The old combined season count -- five rows across two sports is not
    // "5 seasons" of anything real.
    expect(screen.queryByText(/across 5 season/)).toBeNull()

    // The true per-sport records: nfl 12-4, nba 17-21.
    expect(await screen.findByText(/12-4 across 3 seasons/)).toBeTruthy()
    expect(await screen.findByText(/17-21 across 2 seasons/)).toBeTruthy()
  })

  it('renders one table block per sport, each scoped to its own rows', async () => {
    getManagerHistory.mockResolvedValue(twoSportHistory())
    render(<ManagerHistory />)

    await screen.findByText('popsharky')

    // Scoped to the standings section: since the fixture carries real
    // careers, an `NFL` sport-pill in a `.panel-head` also appears in the
    // career panel, which is a different block about the same sport.
    const seasons = within(document.querySelector('.manager-seasons') as HTMLElement)

    const nflHeading = seasons.getByText('NFL', { selector: '.panel-head .sport-pill' })
    const nflBlock = nflHeading.closest('.manager-sport-block') as HTMLElement
    expect(within(nflBlock).getAllByRole('row')).toHaveLength(4) // header + 3 seasons

    const nbaHeading = seasons.getByText('NBA', { selector: '.panel-head .sport-pill' })
    const nbaBlock = nbaHeading.closest('.manager-sport-block') as HTMLElement
    expect(within(nbaBlock).getAllByRole('row')).toHaveLength(3) // header + 2 seasons

    // NBA's 5146.00 must never land in a row alongside NFL's 2042.84 -- the
    // exact contamination baseline.md's T002 measured.
    expect(within(nflBlock).queryByText('5146.00')).toBeNull()
    expect(within(nbaBlock).getByText('5146.00')).toBeTruthy()
  })

  it('shows the league name so two same-season, same-sport rows are distinguishable', async () => {
    getManagerHistory.mockResolvedValue(twoSportHistory())
    render(<ManagerHistory />)

    await screen.findByText('popsharky')

    // Both 2026 football rows must be tellable apart by league name alone --
    // the fixture's (Foot) Ball Knowers name appears on its two rows (2025
    // and 2026), the West Coast name on its one.
    const seasons = within(document.querySelector('.manager-seasons') as HTMLElement)
    expect(seasons.getByText('West Coast Fantasy Football')).toBeTruthy()
    expect(seasons.getAllByText('(Foot) Ball Knowers')).toHaveLength(2)
  })
})

describe('a manager who plays one sport (T020)', () => {
  it('renders only that sport, with no empty second block', async () => {
    const oneSport = twoSportHistory()
    oneSport.careers = oneSport.careers.filter((c) => c.sport === 'nfl')
    getManagerHistory.mockResolvedValue(oneSport)
    render(<ManagerHistory />)

    await screen.findByText('popsharky')

    const seasons = within(document.querySelector('.manager-seasons') as HTMLElement)
    expect(seasons.getByText('NFL', { selector: '.panel-head .sport-pill' })).toBeTruthy()
    expect(seasons.queryByText('NBA', { selector: '.panel-head .sport-pill' })).toBeNull()
  })
})

/**
 * specs/006-deeper-history-both-sports T032/T042/T043 (US3). The career
 * profile panel: FR-008's rank-population rule, FR-007/SC-008's per-figure
 * season count, FR-006's excluded-week visibility, and FR-011's stated
 * reasons in place of zeros.
 */
describe('career profile panel (US3)', () => {
  it('T032/FR-008: shows every rank with its population and league name, never a bare ordinal', async () => {
    getManagerHistory.mockResolvedValue(historyWithCareer())
    render(<ManagerHistory />)
    await screen.findByText('popsharky')

    const heading = await screen.findByText('Career profile')
    const panel = heading.closest('section') as HTMLElement

    // Both ranks in the fixture carry their population (12) and their league
    // chain ((Foot) Ball Knowers) directly beside the ordinal.
    expect(within(panel).getByText('2nd of 12')).toBeTruthy()
    expect(within(panel).getByText('4th of 12')).toBeTruthy()
    expect(within(panel).getAllByText('in (Foot) Ball Knowers')).toHaveLength(2)

    // The defect this guards against: a rank rendered as a bare ordinal, with
    // no population or league name beside it to say what it was ranked
    // against.
    expect(within(panel).queryByText('#2')).toBeNull()
  })

  it('FR-007/SC-008: states the seasons counted beside every career figure, not once for the block', async () => {
    getManagerHistory.mockResolvedValue(historyWithCareer())
    render(<ManagerHistory />)
    await screen.findByText('popsharky')

    const heading = await screen.findByText('Career profile')
    const panel = heading.closest('section') as HTMLElement

    // Six career stat cards -- record, win rate, points per season, average
    // efficiency, wins above expected, titles -- plus four waiver/FAAB cards
    // added by US6 (T075: moves per season, typical bid, largest bid, spent
    // per season), each stating "over 2 seasons" on its own, since a reader
    // scanning one figure should not have to find a caption shared with the
    // others.
    expect(within(panel).getAllByText('over 2 seasons')).toHaveLength(10)
  })

  it('FR-006: carries weeksCounted/weeksExcluded beside averageEfficiency so an excluded week stays visible', async () => {
    getManagerHistory.mockResolvedValue(historyWithCareer({ weeksCounted: 17, weeksExcluded: 2 }))
    render(<ManagerHistory />)
    await screen.findByText('popsharky')

    expect(
      await screen.findByText('17 weeks counted, 2 excluded for want of a per-player breakdown'),
    ).toBeTruthy()
  })

  it('renders a null averageEfficiency as no potential to divide by, never 100%', async () => {
    getManagerHistory.mockResolvedValue(
      historyWithCareer({ averageEfficiency: null, weeksCounted: 0, weeksExcluded: 0 }),
    )
    render(<ManagerHistory />)
    await screen.findByText('popsharky')

    expect(
      await screen.findByText('no scored week with a per-player breakdown yet'),
    ).toBeTruthy()
  })

  it('T043/FR-011: renders unavailable figures as their stated reasons, never as zeros', async () => {
    getManagerHistory.mockResolvedValue(historyWithCareer())
    render(<ManagerHistory />)
    await screen.findByText('popsharky')

    expect(
      await screen.findByText(
        "Playoff appearances: only the champion's placement is stored; the bracket is not parsed",
      ),
    ).toBeTruthy()
    expect(
      await screen.findByText(
        'Trades per season: trades are not attributed to a manager (a trade names several rosters)',
      ),
    ).toBeTruthy()

    // Never a bare 0 standing in for a figure this app cannot answer.
    expect(screen.queryByText('Playoff appearances: 0')).toBeNull()
    expect(screen.queryByText('Trades per season: 0')).toBeNull()
  })

  it('T076: the season tables are built from careers[].seasons, the one season list on the wire', async () => {
    // The flat `seasons[]` is gone from the wire, so `careers` is the only
    // thing that can produce a table. An empty one therefore produces no
    // tables AND no panel -- there is no second list left to fall back to,
    // which is exactly what the removal was for.
    const noCareers = twoSportHistory()
    noCareers.careers = []
    getManagerHistory.mockResolvedValue(noCareers)
    render(<ManagerHistory />)
    await screen.findByText('popsharky')

    expect(screen.queryByText('Career profile')).toBeNull()
    expect(document.querySelectorAll('.manager-seasons .manager-sport-block')).toHaveLength(0)
  })
})

/**
 * specs/006-deeper-history-both-sports T075 (US6). Moves-per-season and FAAB
 * figures, the excluded-season reason, and the "no FAAB at all" case -- each
 * a stated reason, never a bare zero, mirroring UnavailableList's own rule
 * one level down.
 */
describe('waiver activity panel (US6)', () => {
  it('renders moves per season and FAAB percentages, each stating its own seasons-counted', async () => {
    getManagerHistory.mockResolvedValue(historyWithCareer())
    render(<ManagerHistory />)
    await screen.findByText('popsharky')

    const heading = await screen.findByText('Waiver activity')
    const block = heading.closest('.career-waivers') as HTMLElement

    expect(within(block).getByText('29.5')).toBeTruthy()
    expect(within(block).getByText('Moves per season')).toBeTruthy()
    // 0.26 -> 26.0%, and it says explicitly it is a percentage OF the
    // season's own starting budget, not a dollar figure.
    expect(within(block).getAllByText('26.0%').length).toBeGreaterThan(0)
    expect(within(block).getAllByText(/of that season's own starting budget/)).toHaveLength(2)
  })

  it('names the excluded season and its reason, not a silent gap', async () => {
    getManagerHistory.mockResolvedValue(historyWithCareer())
    render(<ManagerHistory />)
    await screen.findByText('popsharky')

    expect(
      await screen.findByText('2025 ((Foot) Ball Knowers): used waiver priority, not FAAB'),
    ).toBeTruthy()
  })

  it('states the reason rather than rendering an empty FAAB block when no season ran FAAB', async () => {
    getManagerHistory.mockResolvedValue(
      historyWithCareer({
        waivers: {
          movesPerSeason: 18.0,
          seasonsCounted: 2,
          faab: null,
          faabExcludedSeasons: [
            { season: 2025, leagueName: '(Foot) Ball Knowers', reason: 'used waiver priority, not FAAB' },
            { season: 2026, leagueName: '(Foot) Ball Knowers', reason: 'used waiver priority, not FAAB' },
          ],
        },
      }),
    )
    render(<ManagerHistory />)
    await screen.findByText('popsharky')

    expect(
      await screen.findByText('No FAAB bids in any counted season -- see below for which seasons used waiver priority instead.'),
    ).toBeTruthy()
    // No FAAB card rendered at all -- never a 0% standing in for "no answer".
    expect(screen.queryByText('Typical FAAB bid')).toBeNull()
  })

  it('T068: tradesPerSeason stays a stated reason, never a number, once waivers renders beside it', async () => {
    getManagerHistory.mockResolvedValue(historyWithCareer())
    render(<ManagerHistory />)
    await screen.findByText('popsharky')

    expect(await screen.findByText('Waiver activity')).toBeTruthy()
    expect(
      await screen.findByText(
        'Trades per season: trades are not attributed to a manager (a trade names several rosters)',
      ),
    ).toBeTruthy()
    expect(screen.queryByText('Trades per season: 0')).toBeNull()
  })
})

/**
 * specs/006-deeper-history-both-sports, found by LIVE verification on
 * 2026-09-21 rather than by a test -- which is why it gets one now.
 *
 * The page header recomputed its own season count from `seasons` while the
 * career panel printed `seasonsCounted` from `careers`. For popsharky's
 * basketball that read "NBA 17-21 across 3 seasons" in the header and
 * "17-21-1 over 2 seasons" in the panel, eight lines apart: the header
 * counted the ingested-but-unplayed NBA 2026 row, the panel correctly did
 * not, and the header dropped the tie as well.
 *
 * This is the repo's own "count and label, one source" lesson -- a live
 * ballot tally once printed under a lagging week label for the same reason.
 * FR-007 says a figure states the seasons it covers; it is worth nothing if
 * two figures on one page state different ones.
 */
describe('the header and the career panel agree (FR-007)', () => {
  it('takes its record, tie and season count from careers, not from the season rows', async () => {
    const data = twoSportHistory()
    // Three LISTED basketball seasons, one of them ingested but unplayed --
    // exactly the live shape -- against a career that counts two. Since T076
    // both numbers come out of the same object, so the header cannot read the
    // list while the panel reads the count; this test now guards that they
    // stay different fields with different meanings rather than that two
    // sources agree.
    data.careers = [
      careerProfile({ sport: 'nfl', seasons: NFL_ROWS, seasonsCounted: 3, wins: 12, losses: 4, ties: 0, titles: 1 }),
      careerProfile({
        sport: 'nba',
        seasons: [
          ...NBA_ROWS,
          careerSeason({
            sport: 'nba', season: 2026, leagueName: 'Ball Knowers', sleeperLeagueId: 'L-bk-26',
            rosterId: 7, wins: 0, losses: 0, pointsFor: 0, pointsAgainst: 0, complete: false,
            counted: false,
          }),
        ],
        seasonsCounted: 2, wins: 17, losses: 21, ties: 1, titles: 0,
      }),
    ]
    getManagerHistory.mockResolvedValue(data)
    render(<ManagerHistory />)

    // The counted seasons, with the tie, exactly as the panel below states.
    expect(await screen.findByText(/17-21-1 across 2 seasons/)).toBeTruthy()
    // What the bug printed: the listed-row count, and no tie.
    expect(screen.queryByText(/17-21-1 across 3 seasons/)).toBeNull()
    expect(screen.queryByText(/17-21 across 3 seasons/)).toBeNull()
    // Football is unaffected -- all three of its listed seasons are counted,
    // so its clause and its panel agree at three.
    expect(screen.getByText(/12-4 across 3 seasons/)).toBeTruthy()
  })

  // The companion test that used to sit here -- "still renders against a
  // server older than careers[], falling back to the rows" -- is deleted with
  // T076, not ported. That fallback existed for the one release in which the
  // response carried both lists; a server old enough to omit careers[] now
  // sends a payload this page cannot render at all, and pretending otherwise
  // would be testing a reconstruction of the very second source the removal
  // was meant to delete.
})
