import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PlayerSpotlight as PlayerSpotlightData, PlayerSpotlightApplicable, WeeklyReport } from '../api'
import type { Block } from '../useBlock'
import PlayerSpotlight, { SpotlightLists } from './PlayerSpotlight'

const weekly: Block<WeeklyReport> = { status: 'loading' }

function applicable(over: Partial<PlayerSpotlightApplicable> = {}): PlayerSpotlightApplicable {
  return {
    applies: true,
    season: 2026,
    sport: 'nfl',
    playersPlayMultiplePerPeriod: false,
    period: { kind: 'WEEK', week: 3, weekFinal: true },
    periodUnavailable: null,
    laterNightInProgress: null,
    seasonStartDate: null,
    trending: { entries: [], lookbackHours: 24, fetchedAt: null, stale: false, omittedUnknownPlayers: 0, unavailable: 'NEVER_FETCHED' },
    rookieWatch: { entries: [], unavailable: 'NO_PERIOD' },
    ...over,
  }
}

const show = (s: PlayerSpotlightData) => render(<PlayerSpotlight spotlight={s} weekly={weekly} />)

describe('PlayerSpotlight foundation', () => {
  it('renders nothing when the spotlight does not apply', () => {
    const { container } = show({ applies: false, reason: 'PAST_SEASON', season: 2025, sport: 'nba' })
    expect(container).toBeEmptyDOMElement()
    expect(screen.queryAllByRole('heading')).toHaveLength(0)
  })

  it('labels a night with its calendar date, not a UTC-shifted one, in the section itself', () => {
    show(applicable({ period: { kind: 'NIGHT', date: '2026-10-21', gamesCount: 11 } }))
    expect(screen.getByRole('heading', { name: 'Rookie watch · Wed, Oct 21' })).toBeInTheDocument()
  })

  it('tags a week that is not final as in progress, beside the section label', () => {
    show(applicable({ period: { kind: 'WEEK', week: 3, weekFinal: false } }))
    expect(screen.getByRole('heading', { name: 'Rookie watch · Week 3 · not final yet' })).toBeInTheDocument()
  })

  it('does not tag a final week', () => {
    show(applicable())
    expect(screen.getByRole('heading', { name: 'Rookie watch · Week 3' })).toBeInTheDocument()
    expect(screen.queryByText(/not final/)).toBeNull()
  })

  it('prints no period line under the block title (each section carries its own label)', () => {
    const { container } = show(applicable())
    expect(container.querySelector('.lh-spotlight-period')).toBeNull()
    expect(container.querySelector('h2 + p')).toBeNull()
  })

  it('says our data is not final, not that games are live', () => {
    show(applicable({ period: null, periodUnavailable: 'NO_COMPLETE_NIGHT_YET', rookieWatch: { entries: [], unavailable: 'NO_PERIOD' } }))
    expect(screen.getByText("The latest night's scores aren't final in our data yet.")).toBeInTheDocument()
  })
})

const perf = (over: Record<string, unknown> = {}) => ({
  playerId: '1', name: 'AJ Dybantsa', position: 'SF', team: 'WAS', opponent: 'CHA', isAway: false, points: 41.5,
  ownership: { rostered: true, teamName: 'Dunk Tank', avatarId: 'av1', isMe: false }, ...over,
})
const night = { kind: 'NIGHT' as const, date: '2026-10-21', gamesCount: 11 }

describe('Top players of the night', () => {
  const nba = (over: Partial<PlayerSpotlightApplicable> = {}) =>
    applicable({ sport: 'nba', playersPlayMultiplePerPeriod: true, period: night, ...over })

  it('shows the pre-season sentence without a date when seasonStartDate is null', () => {
    show(nba({ period: null, periodUnavailable: 'NO_GAMES_YET', topOfNight: { entries: [], unavailable: 'NO_PERIOD' } }))
    expect(screen.getAllByText("No regular-season games yet. Preseason games aren't counted here.").length).toBeGreaterThan(0)
    expect(screen.queryByText(/regular season starts/)).toBeNull()
  })

  it('shows the regular-season start date when it is known', () => {
    show(nba({ period: null, periodUnavailable: 'NO_GAMES_YET', seasonStartDate: '2026-10-20', topOfNight: { entries: [], unavailable: 'NO_PERIOD' } }))
    expect(
      screen.getAllByText("No regular-season games yet. The regular season starts Tue, Oct 20; preseason games aren't counted here.").length,
    ).toBeGreaterThan(0)
  })

  it('renders exact points, the games count and the Yours pill', () => {
    show(nba({
      topOfNight: { entries: [perf(), perf({ playerId: '2', name: 'Mine Guy', points: 30, ownership: { rostered: true, teamName: 'Me', isMe: true } })], unavailable: null },
    }))
    expect(screen.getByText('41.50 pts')).toBeInTheDocument()
    expect(screen.getByText('30.00 pts')).toBeInTheDocument()
    expect(screen.getByText(/11 games/)).toBeInTheDocument()
    expect(screen.getAllByText('Yours')).toHaveLength(1)
  })

  it('never renders a bare empty list', () => {
    show(nba({ topOfNight: { entries: [], unavailable: 'SECTION_FAILED' } }))
    expect(screen.getByText("Couldn't load this section.")).toBeInTheDocument()
  })

  it('says no one on a roster played, naming the night', () => {
    show(nba({ period: night, topOfNight: { entries: [], unavailable: 'NO_ROSTERED_PLAYED' } }))
    expect(screen.getByText('No one on a roster in this league played on Wed, Oct 21.')).toBeInTheDocument()
  })

  it('notes a later night still in progress', () => {
    show(nba({ laterNightInProgress: '2026-10-22', topOfNight: { entries: [perf()], unavailable: null } }))
    expect(screen.getByText('Scores from Thu, Oct 22 are still updating.')).toBeInTheDocument()
  })
})

describe('Top players of the week (football)', () => {
  const performer = (n: number, pts: number) => ({
    playerId: `p${n}`, playerName: `Player ${n}`, position: 'RB', teamName: `Team ${n}`, points: pts,
    team: 'SEA', opponent: 'DAL', isAway: false, avatarId: 'abc',
  })

  it('renders the weekly report performers in order with their points', () => {
    const w = { status: 'ok', data: { week: 3, topPerformers: [performer(1, 31.2), performer(2, 28), performer(3, 25.55)] } } as unknown as Block<WeeklyReport>
    render(<PlayerSpotlight spotlight={applicable()} weekly={w} />)
    expect(screen.getByRole('heading', { name: 'Top players · Week 3' })).toBeInTheDocument()
    const names = screen.getAllByText(/^Player \d$/).map((n) => n.textContent)
    expect(names).toEqual(['Player 1', 'Player 2', 'Player 3'])
    expect(screen.getByText('31.20 pts')).toBeInTheDocument()
    expect(screen.getByText('25.55 pts')).toBeInTheDocument()
    expect(screen.getByText('Team 1')).toBeInTheDocument()
    expect(screen.queryByText(/Started for/)).toBeNull()
    expect(screen.getAllByText('RB · SEA · vs DAL')).toHaveLength(3)
  })

  it('labels the week from the weekly report data it renders, not the spotlight week', () => {
    const w = { status: 'ok', data: { week: 2, topPerformers: [performer(1, 31.2)] } } as unknown as Block<WeeklyReport>
    render(<PlayerSpotlight spotlight={applicable({ period: { kind: 'WEEK', week: 3, weekFinal: true } })} weekly={w} />)
    expect(screen.getByRole('heading', { name: 'Top players · Week 2' })).toBeInTheDocument()
  })

  it('still renders Trending while the weekly report loads', () => {
    show(applicable())
    expect(screen.getByRole('status', { name: 'Loading top players' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Trending · last 24h' })).toBeInTheDocument()
  })

  it('says so when the weekly report failed', () => {
    render(<PlayerSpotlight spotlight={applicable()} weekly={{ status: 'error', notFound: false }} />)
    expect(screen.getByText("Couldn't load this week's top players.")).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Trending · last 24h' })).toBeInTheDocument()
  })
})

describe('Trending', () => {
  const entry = (over: Record<string, unknown>) => ({
    rank: 1, playerId: 't1', name: 'Trend Guy', position: 'WR', team: 'DAL', addCount: 602,
    ownership: { rostered: false }, outcome: 'PLAYED', ...over,
  })
  const trending = (entries: unknown[], over: Record<string, unknown> = {}, top: Partial<PlayerSpotlightApplicable> = {}) =>
    applicable({ ...top, trending: { entries, lookbackHours: 24, fetchedAt: new Date().toISOString(), stale: false, omittedUnknownPlayers: 0, unavailable: null, ...over } as never })

  it('shows words, never a number, for a player who did not play', () => {
    const { container } = show(trending([entry({ outcome: 'DID_NOT_PLAY' })]))
    expect(screen.getByText('Did not play in Week 3')).toBeInTheDocument()
    expect(container.querySelector('.lh-pts')).toBeNull()
    expect(screen.queryByText(/^0(\.00)?$/)).toBeNull()
  })

  it('shows the bye week for a week period', () => {
    show(trending([entry({ outcome: 'NO_GAME' })]))
    expect(screen.getByText('Bye in Week 3')).toBeInTheDocument()
  })

  it('shows "No game on <date>" for a night period, never "Bye"', () => {
    show(trending([entry({ outcome: 'NO_GAME' })], {}, { sport: 'nba', playersPlayMultiplePerPeriod: true, period: night }))
    expect(screen.getByText('No game on Wed, Oct 21')).toBeInTheDocument()
    expect(screen.queryByText(/Bye/)).toBeNull()
  })

  it('shows points and adds for a player who played', () => {
    show(trending([entry({ points: 28, opponent: 'SAC', isAway: true })]))
    expect(screen.getByText('Wk 3 · 28.00 pts')).toBeInTheDocument()
    expect(screen.getByText(/602 adds/)).toBeInTheDocument()
  })

  it('formats add counts with thousands separators, on the meta line', () => {
    show(trending([entry({ addCount: 481232, points: 28, opponent: 'SAC', isAway: true })]))
    const meta = screen.getByText(/481,232 adds/)
    expect(meta.textContent).toBe('WR · DAL · @ SAC · 481,232 adds')
    expect(meta.className).toContain('lh-spot-sub')
    expect(meta.closest('.lh-spot-sub2')?.querySelector('.lh-fa')?.textContent).toBe('Free agent')
    expect(screen.queryByText(/\+/)).toBeNull()
  })

  it('puts the outcome words inline on the name line, not in a side column', () => {
    const { container } = show(trending([entry({ outcome: 'DID_NOT_PLAY', addCount: 1234 })]))
    const extra = container.querySelector('.lh-spot-extra')
    expect(extra?.textContent).toBe('Did not play in Week 3')
    expect(extra?.parentElement?.className).toBe('lh-spot-line')
    expect(screen.getByText(/1,234 adds/)).toBeInTheDocument()
  })

  it('carries the Sleeper credit and the all-leagues subline', () => {
    show(trending([entry({})]))
    expect(screen.getByText(/across all Sleeper leagues, last 24 hours · Trending data from Sleeper/)).toBeInTheDocument()
  })

  it('shows the age when stale', () => {
    const old = new Date(Date.now() - 5 * 3600_000).toISOString()
    show(trending([entry({})], { stale: true, fetchedAt: old }))
    expect(screen.getByText('Updated 5 hours ago')).toBeInTheDocument()
  })

  it('explains NEVER_FETCHED instead of an empty list', () => {
    show(applicable())
    expect(screen.getByText("Trending hasn't loaded yet; it updates when the league refreshes.")).toBeInTheDocument()
  })
})

describe('Rookie watch', () => {
  it('shows an unrostered rookie as a free agent', () => {
    show(applicable({ rookieWatch: { entries: [perf({ ownership: { rostered: false } })] as never, unavailable: null } }))
    expect(screen.getByText('Free agent')).toBeInTheDocument()
  })

  it('says no rookies played, naming the period', () => {
    show(applicable({ rookieWatch: { entries: [], unavailable: 'NO_ROOKIE_PLAYED' } }))
    expect(screen.getByText('No rookies played in Week 3.')).toBeInTheDocument()
  })
})

describe('Layout: rows, toggle, tabs', () => {
  const many = (n: number) => Array.from({ length: n }, (_, i) => perf({ playerId: `r${i}`, name: `Rookie ${i + 1}`, points: 20 - i }))
  const rookies = (n: number) => applicable({ rookieWatch: { entries: many(n) as never, unavailable: null } })

  afterEach(() => {
    window.localStorage.clear()
    vi.restoreAllMocks()
  })

  it('shows five rows, then ten after Show all 10, then five again', () => {
    show(rookies(10))
    expect(screen.getAllByText(/^Rookie \d+$/)).toHaveLength(5)
    const toggle = screen.getByRole('button', { name: 'Show all 10' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(screen.getAllByText(/^Rookie \d+$/)).toHaveLength(10)
    fireEvent.click(screen.getByRole('button', { name: 'Show fewer' }))
    expect(screen.getAllByText(/^Rookie \d+$/)).toHaveLength(5)
  })

  it('has no toggle for a list of five or fewer', () => {
    show(rookies(4))
    expect(screen.queryByRole('button', { name: /Show all/ })).toBeNull()
  })

  it('numbers the rows of all three lists', () => {
    const w = {
      status: 'ok',
      data: { week: 3, topPerformers: [{ playerId: 'a', playerName: 'Top One', position: 'RB', teamName: 'T', points: 9, team: null, opponent: null, isAway: null, avatarId: null }] },
    } as unknown as Block<WeeklyReport>
    const t = { rank: 1, playerId: 't1', name: 'Trend One', position: 'WR', team: 'DAL', addCount: 5, ownership: { rostered: false }, outcome: 'PLAYED', points: 3 }
    render(
      <PlayerSpotlight
        weekly={w}
        spotlight={applicable({
          trending: { entries: [t], lookbackHours: 24, fetchedAt: null, stale: false, omittedUnknownPlayers: 0, unavailable: null } as never,
          rookieWatch: { entries: many(1) as never, unavailable: null },
        })}
      />,
    )
    for (const id of ['top', 'trending', 'rookies']) {
      const panel = document.getElementById(`lh-spot-panel-${id}`) as HTMLElement
      expect(panel.querySelector('.lh-pos')?.textContent).toBe('1')
    }
  })

  it('renders the owner avatar for a rostered row and the pill for a free agent', () => {
    const { container } = show(
      applicable({ rookieWatch: { entries: [perf(), perf({ playerId: '2', ownership: { rostered: false } })] as never, unavailable: null } }),
    )
    expect(container.querySelectorAll('.lh-spot-avatar')).toHaveLength(1)
    expect(container.querySelector('.lh-spot-avatar img')).not.toBeNull()
    expect(screen.getByText('Dunk Tank')).toBeInTheDocument()
    expect(screen.getAllByText('Free agent')).toHaveLength(1)
  })

  it('selects Trending by default and switches tabs with click and arrow keys', () => {
    show(applicable())
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((t) => t.textContent)).toEqual(['Top', 'Trending', 'Rookies'])
    expect(screen.getByRole('tab', { name: 'Trending' })).toHaveAttribute('aria-selected', 'true')
    const panel = (k: string) => document.getElementById(`lh-spot-panel-${k}`) as HTMLElement
    expect(panel('trending')).toHaveAttribute('data-active', 'true')
    expect(panel('top')).toHaveAttribute('data-active', 'false')
    fireEvent.click(screen.getByRole('tab', { name: 'Rookies' }))
    expect(screen.getByRole('tab', { name: 'Rookies' })).toHaveAttribute('aria-selected', 'true')
    expect(panel('rookies')).toHaveAttribute('data-active', 'true')
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Rookies' }), { key: 'ArrowRight' })
    expect(screen.getByRole('tab', { name: 'Top' })).toHaveAttribute('aria-selected', 'true')
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Top' }), { key: 'ArrowLeft' })
    expect(screen.getByRole('tab', { name: 'Rookies' })).toHaveAttribute('aria-selected', 'true')
    expect(within(panel('rookies')).getByRole('heading')).toBeInTheDocument()
  })

  it('remembers the last tab', () => {
    const first = show(applicable())
    fireEvent.click(screen.getByRole('tab', { name: 'Top' }))
    first.unmount()
    show(applicable())
    expect(screen.getByRole('tab', { name: 'Top' })).toHaveAttribute('aria-selected', 'true')
  })

  it('still renders when localStorage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    show(applicable())
    expect(screen.getByRole('tab', { name: 'Trending' })).toHaveAttribute('aria-selected', 'true')
    fireEvent.click(screen.getByRole('tab', { name: 'Top' }))
    expect(screen.getByRole('tab', { name: 'Top' })).toHaveAttribute('aria-selected', 'true')
  })
})

describe('SpotlightLists', () => {
  // specs/015: every visited league tab keeps its lists mounted, so ids must not collide.
  it('builds every element id from idPrefix, so two copies in one page share no id', () => {
    const s = applicable()
    const { container } = render(
      <>
        <SpotlightLists spotlight={s} weekly={weekly} idPrefix="a" />
        <SpotlightLists spotlight={s} weekly={weekly} idPrefix="b" />
      </>,
    )
    const ids = Array.from(container.querySelectorAll('[id]')).map((el) => el.id)
    expect(ids.length).toBeGreaterThan(0)
    expect(new Set(ids).size).toBe(ids.length)
    expect(ids).toContain('a-spot-tab-top')
    expect(ids).toContain('b-spot-panel-rookies')
  })
})
