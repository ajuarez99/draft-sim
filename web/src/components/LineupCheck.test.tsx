import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import LineupCheck from './LineupCheck'
import type { BenchOption, LineupCheck as LineupCheckData, LineupFlag, LineupFlagReason, PlayerStatsPlayer } from '../api'

const ref = (id: string, name: string, positions = ['PG'], team: string | null = 'ATL'): PlayerStatsPlayer => ({
  sleeperPlayerId: id,
  name,
  positions,
  team,
  known: true,
})

const option = (id: string, name: string, over: Partial<BenchOption> = {}): BenchOption => ({
  player: ref(id, name),
  gamesLeft: 2,
  fpPerGame: 31.4,
  fpSeason: 2025,
  fpGames: 71,
  injuryStatus: null,
  ...over,
})

const flag = (reason: LineupFlagReason, over: Partial<LineupFlag> = {}): LineupFlag => ({
  slot: 'PG',
  player: ref('s1', 'Starter One'),
  reason,
  injuryStatus: null,
  playerStatus: null,
  options: [option('b1', 'Bench One')],
  optionsOrder: 'FP_PER_GAME',
  ...over,
})

const check = (over: Partial<LineupCheckData> = {}): LineupCheckData => ({
  available: true,
  reason: null,
  week: 1,
  statusAsOf: '2026-10-21T11:02:00Z',
  scheduleAsOf: '2026-10-20T09:00:00Z',
  gameCheck: true,
  gameCheckReason: null,
  flags: [],
  ...over,
})

const renderIt = (c: LineupCheckData, sport: 'nba' | 'nfl' = 'nba') =>
  render(
    <MemoryRouter>
      <LineupCheck check={c} leagueId="L1" sport={sport} />
    </MemoryRouter>,
  )

describe('LineupCheck', () => {
  it('says all starters are active when nothing is flagged', () => {
    renderIt(check())
    expect(screen.getByText('All starters are active')).toBeInTheDocument()
  })

  it('shows slot, player, the reason, the status time and the bench options with their basis', () => {
    renderIt(check({ flags: [flag('OUT', { injuryStatus: 'Out' })] }))
    const row = screen.getByText('Starter One').closest('li') as HTMLElement
    expect(row.querySelector('.lc-slot')).toHaveTextContent('PG')
    expect(within(row).getByText('Out')).toBeInTheDocument()
    expect(within(row).getByText(/as of Oct 21, 7:02 AM EDT/)).toBeInTheDocument()
    expect(within(row).getByText('Bench One')).toBeInTheDocument()
    expect(within(row).getByText(/31\.4 fp\/g · 2025 avg · 71 games · 2 games left this week/)).toBeInTheDocument()
    expect(within(row).getByText(/Sorted by fantasy points per game, highest first/)).toBeInTheDocument()
  })

  it('says the status time is unknown rather than implying it is fresh', () => {
    renderIt(check({ statusAsOf: null, flags: [flag('LISTED', { injuryStatus: 'GTD' })] }))
    expect(screen.getByText('Listed: GTD')).toBeInTheDocument()
    expect(screen.getByText('status time unknown')).toBeInTheDocument()
  })

  it('shows a suspension by its tag when there is no injury status', () => {
    renderIt(check({ flags: [flag('OUT', { injuryStatus: null, playerStatus: 'SUS' })] }))
    expect(screen.getByText('Out (SUS)')).toBeInTheDocument()
  })

  it('names the team and week for a starter with no game, and shows an empty slot without a player', () => {
    renderIt(check({ week: 3, flags: [flag('NO_GAME_IN_WEEK', { player: ref('s1', 'Idle Guy', ['C'], 'SAS') }), flag('EMPTY_SLOT', { player: null, slot: 'UTIL' })] }))
    expect(screen.getByText('SAS has no game in week 3')).toBeInTheDocument()
    expect(screen.getByText('Nobody in this slot')).toBeInTheDocument()
    expect(screen.getByText('Empty slot')).toBeInTheDocument()
  })

  it('shows an option with no average as such, not as zero', () => {
    renderIt(check({ flags: [flag('OUT', { options: [option('b1', 'Rookie Guy', { fpPerGame: null, fpSeason: null, fpGames: 0 })] })] }))
    expect(screen.getByText(/no average yet/)).toBeInTheDocument()
  })

  it('does not claim "no bench player" for a slot it cannot judge', () => {
    renderIt(check({ flags: [flag('OUT', { slot: 'SUPER_FLEX', options: [], optionsOrder: 'SLOT_UNSUPPORTED' })] }))
    expect(screen.getByText(/Bench options aren’t shown for a SUPER_FLEX slot yet/)).toBeInTheDocument()
    expect(screen.queryByText(/No bench player is eligible/)).toBeNull()
  })

  it('says no bench player fits when the list is empty for a known slot', () => {
    renderIt(check({ flags: [flag('OUT', { options: [] })] }))
    expect(screen.getByText(/No bench player is eligible for PG who isn’t out and has a game this week/)).toBeInTheDocument()
  })

  it('does not say "has a game this week" about a list it could not check against the schedule', () => {
    renderIt(check({ gameCheck: false, gameCheckReason: 'NO_SCHEDULE', flags: [flag('OUT', { options: [] })] }))
    expect(screen.getByText(/No bench player is eligible for PG who isn’t out\./)).toBeInTheDocument()
  })

  it('shows a listed bench player with his tag', () => {
    renderIt(check({ flags: [flag('OUT', { options: [option('b1', 'Dee Tee Dee', { injuryStatus: 'DTD' })] })] }))
    expect(screen.getByText(/Listed: DTD/)).toBeInTheDocument()
  })

  it('says it has no details for an unknown starter and does not say all are active', () => {
    renderIt(check({ flags: [flag('UNKNOWN_PLAYER', { player: ref('ghost', 'ghost'), options: [], optionsOrder: 'NAME' })] }))
    expect(screen.getByText('We don’t have this player’s details')).toBeInTheDocument()
    expect(screen.queryByText('All starters are active')).toBeNull()
    expect(screen.queryByText(/No bench player/)).toBeNull()
  })

  it('says why the game check did not run: week unknown, or a week the schedule lacks', () => {
    const { unmount } = renderIt(check({ gameCheck: false, gameCheckReason: 'WEEK_UNKNOWN' }))
    expect(screen.getByText(/Sleeper hasn’t said which week it is/)).toBeInTheDocument()
    expect(screen.queryByText(/schedule isn’t loaded/)).toBeNull()
    unmount()
    renderIt(check({ gameCheck: false, gameCheckReason: 'WEEK_NOT_IN_SCHEDULE', week: 30 }))
    expect(screen.getByText(/doesn’t include week 30/)).toBeInTheDocument()
  })

  it('prints when the schedule was fetched, or says the time is unknown', () => {
    const { unmount } = renderIt(check())
    expect(screen.getByText(/Games from the schedule as of Oct 20/)).toBeInTheDocument()
    unmount()
    renderIt(check({ scheduleAsOf: null }))
    expect(screen.getByText(/schedule time unknown/)).toBeInTheDocument()
  })

  it('says once that there is no game check, in football words for football', () => {
    const { unmount } = renderIt(check({ gameCheck: false, gameCheckReason: 'NO_SCHEDULE' }), 'nfl')
    expect(screen.getAllByText('No game check for football yet.')).toHaveLength(1)
    unmount()
    renderIt(check({ gameCheck: false, gameCheckReason: 'NO_SCHEDULE' }), 'nba')
    expect(screen.queryByText('No game check for football yet.')).toBeNull()
    expect(screen.getByText(/schedule isn’t loaded/)).toBeInTheDocument()
  })

  it('never shows the weekly-lock line: the cadence is not established', () => {
    renderIt(check({ flags: [flag('OUT')] }))
    expect(screen.queryByText(/lock/i)).toBeNull()
  })

  it('renders nothing when the check does not apply, and a plain line when Sleeper could not be read', () => {
    const { container, unmount } = renderIt(check({ available: false, reason: 'NOT_IN_SEASON' }))
    expect(container).toBeEmptyDOMElement()
    unmount()
    renderIt(check({ available: false, reason: 'ROSTERS_FAILED' }))
    expect(screen.getByText(/Couldn’t read your live lineup/)).toBeInTheDocument()
  })

  // SC-006: facts, never verdicts. Rendered for every reason, with and without options.
  it('never uses start / sit / add / drop / recommend language, for any reason', () => {
    const forbidden = /\b(start|sit|add|drop|recommend\w*|should|must)\b/i
    const reasons: LineupFlagReason[] = ['EMPTY_SLOT', 'OUT', 'LISTED', 'NO_GAME_IN_WEEK', 'UNKNOWN_PLAYER']
    const flags = reasons.flatMap((r) => [
      flag(r, { injuryStatus: 'Out', playerStatus: 'SUS', options: [option('b9', 'Tagged', { injuryStatus: 'DTD' })] }),
      flag(r, { options: [], optionsOrder: 'NAME' }),
      flag(r, { slot: 'SUPER_FLEX', options: [], optionsOrder: 'SLOT_UNSUPPORTED' }),
      flag(r, { optionsOrder: 'NAME' }),
    ])
    for (const sport of ['nba', 'nfl'] as const) {
      for (const gameCheck of [true, false]) {
        for (const statusAsOf of ['2026-10-21T11:02:00Z', null]) {
          const { container, unmount } = renderIt(check({ flags, gameCheck, statusAsOf }), sport)
          expect(container.textContent).not.toMatch(forbidden)
          unmount()
        }
      }
    }
    for (const c of [check(), check({ available: false, reason: 'ROSTERS_FAILED' })]) {
      const { container, unmount } = renderIt(c)
      expect(container.textContent).not.toMatch(forbidden)
      unmount()
    }
  })
})
