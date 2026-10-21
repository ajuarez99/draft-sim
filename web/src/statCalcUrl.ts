import type { LeaderboardRow, PlayerWindowKind } from './api'
import {
  NO_FILTERS,
  hasKnownOwnership,
  hasMine,
  ownershipOf,
  type Filters,
  type SortDir,
  type StatMode,
} from './statLeaderboard'

/**
 * specs/032-stat-calculator contract C2: the calculator's whole state, as search params, so a link
 * restores the identical chart (SC-004). Pure functions; the page reads and writes through them.
 */

export type CalcState = {
  /** The formula in canonical form (or whatever text the link carried, if it does not parse). */
  f: string
  /** A name of up to 40 characters; empty for none. */
  n: string
  w: PlayerWindowKind
  m: StatMode
  top: number
  /** Qualified players only. */
  q: boolean
  /** Standardize inputs. */
  z: boolean
  dir: SortDir
  filters: Filters
  /** US4/US5: which chart. */
  chart: ChartKind
  /** US5 Spread: one strip per position (`split=pos`). Kept in the link on other charts, like the scatter axes. */
  split: boolean
  /** US5 Change: the view compared with the current window. Never equal to `w` (see `defaultCmp`). */
  cmp: CompareKind
  /** US5 Change: sort by biggest rise or biggest fall. */
  chg: ChangeDir
  /** Scatter axes: a built-in stat id, or `AXIS_FORMULA` for the current formula. `''` = the page's default. */
  x: string
  y: string
}

export type ChartKind = 'ranked' | 'spread' | 'change' | 'parts' | 'scatter'
/** US5: what Change compares the current window with; PREV is the previous season, same window. */
export type CompareKind = PlayerWindowKind | 'PREV'
export type ChangeDir = 'up' | 'down'
const CHART_KINDS: ChartKind[] = ['ranked', 'spread', 'change', 'parts', 'scatter']
const COMPARE_KINDS: CompareKind[] = ['LAST_10', 'LAST_5', 'SEASON', 'PREV']

/**
 * The comparison when the link names none, or names the window itself (comparing a window with itself
 * shows nothing): the season against the last 10 games, and from a recent window back to the season (specs/032 US5, C2).
 */
export const defaultCmp = (w: PlayerWindowKind): CompareKind => (w === 'SEASON' ? 'LAST_10' : 'SEASON')
/** The scatter axis value that means "the current formula" (FR-018), so the link does not repeat `f` and cannot go stale when `f` is edited. */
export const AXIS_FORMULA = 'f'

export const NAME_MAX = 40
export const TOP_MIN = 5
export const TOP_MAX = 50
/** A hand-set display choice, not a principled one (spec assumptions). */
export const TOP_DEFAULT = 15

export const DEFAULT_STATE: CalcState = {
  f: '', n: '', w: 'SEASON', m: 'perGame', top: TOP_DEFAULT, q: true, z: false, dir: 'desc', filters: NO_FILTERS,
  chart: 'ranked', split: false, cmp: 'LAST_10', chg: 'up', x: '', y: '',
}

export const clampTop = (n: number): number => Math.min(TOP_MAX, Math.max(TOP_MIN, Math.round(n)))

const WINDOW_KINDS: PlayerWindowKind[] = ['SEASON', 'LAST_10', 'LAST_5']
const MODE_KINDS: StatMode[] = ['perGame', 'totals', 'per36']

/** The rule that roster ids repeat every season, so a roster filter cannot cross a season switch (F9). */
export function stripRoster(params: URLSearchParams): URLSearchParams {
  const out = new URLSearchParams(params)
  if (out.get('own')?.startsWith('r:')) out.delete('own')
  return out
}

/**
 * The position / team / availability filters from a query string, shared by the calculator and the
 * stat leaderboard so one link encoding serves both (specs/042 F9). `own=` is `free`, `rostered`,
 * `mine` or `r:<rosterId>`. Validated against `rows` once loaded; with `rows` null the filters are
 * taken as written.
 */
export function readFilters(params: URLSearchParams, rows: LeaderboardRow[] | null): { filters: Filters; dropped: string[] } {
  const dropped: string[] = []
  let position = params.get('pos') || null
  let team = params.get('team') || null
  let availability: Filters['availability'] = { kind: 'all' }
  const own = params.get('own')
  if (own === 'free') availability = { kind: 'free' }
  else if (own === 'rostered') availability = { kind: 'rostered' }
  else if (own === 'mine') availability = { kind: 'mine' }
  else if (own?.startsWith('r:')) {
    const id = Number(own.slice(2))
    if (own.slice(2) !== '' && Number.isInteger(id)) availability = { kind: 'roster', rosterId: id }
    else dropped.push(`availability ${own}`)
  } else if (own) dropped.push(`availability ${own}`)

  if (rows) {
    if (position && !rows.some((r) => r.positions.includes(position as string))) {
      dropped.push(`position ${position}`)
      position = null
    }
    if (team && !rows.some((r) => r.team === team)) {
      dropped.push(`team ${team}`)
      team = null
    }
    if (availability.kind === 'roster') {
      const id = availability.rosterId
      if (!rows.some((r) => ownershipOf(r).state === 'ROSTERED' && ownershipOf(r).rosterId === id)) {
        dropped.push(`roster ${id}`)
        availability = { kind: 'all' }
      }
    } else if (availability.kind === 'mine' && !hasMine(rows)) {
      // "mine" is re-resolved per season, so it survives a season switch; a viewer with no roster has none.
      dropped.push('availability mine')
      availability = { kind: 'all' }
    } else if (availability.kind !== 'all' && !hasKnownOwnership(rows)) {
      dropped.push(`availability ${own}`)
      availability = { kind: 'all' }
    }
  }

  return { filters: { position, team, availability }, dropped }
}

/** Writes the filters into `p`, only what differs from the default. The inverse of {@link readFilters}. */
export function writeFilters(f: Filters, p: URLSearchParams): void {
  if (f.position) p.set('pos', f.position)
  if (f.team) p.set('team', f.team)
  const a = f.availability
  if (a.kind === 'free' || a.kind === 'rostered' || a.kind === 'mine') p.set('own', a.kind)
  else if (a.kind === 'roster') p.set('own', `r:${a.rosterId}`)
}

/**
 * State from a query string. `rows`, once loaded, validates the filters: a position, team or roster
 * that is not in this season's rows is dropped and named in `dropped` (F9). With `rows` null (still
 * loading) the filters are taken as written.
 */
export function readCalcState(params: URLSearchParams, rows: LeaderboardRow[] | null): { state: CalcState; dropped: string[] } {
  const dropped: string[] = []
  const w = params.get('w')
  const m = params.get('m')
  const topRaw = Number(params.get('top'))
  const { filters, dropped: filterDropped } = readFilters(params, rows)
  dropped.push(...filterDropped)

  const wv: PlayerWindowKind = WINDOW_KINDS.includes(w as PlayerWindowKind) ? (w as PlayerWindowKind) : 'SEASON'
  const cmpRaw = params.get('cmp') as CompareKind | null
  const cmp: CompareKind = cmpRaw && COMPARE_KINDS.includes(cmpRaw) && cmpRaw !== wv ? cmpRaw : defaultCmp(wv)
  const chartRaw = params.get('chart') as ChartKind | null
  return {
    state: {
      f: params.get('f') ?? '',
      n: (params.get('n') ?? '').slice(0, NAME_MAX),
      w: wv,
      m: MODE_KINDS.includes(m as StatMode) ? (m as StatMode) : 'perGame',
      top: params.get('top') != null && params.get('top') !== '' && Number.isFinite(topRaw) ? clampTop(topRaw) : TOP_DEFAULT,
      q: params.get('q') !== '0',
      z: params.get('z') === '1',
      dir: params.get('dir') === 'asc' ? 'asc' : 'desc',
      filters,
      chart: chartRaw && CHART_KINDS.includes(chartRaw) ? chartRaw : 'ranked',
      split: params.get('split') === 'pos',
      cmp,
      chg: params.get('chg') === 'down' ? 'down' : 'up',
      x: params.get('x') ?? '',
      y: params.get('y') ?? '',
    },
    dropped,
  }
}

/** The query string for a state: only what differs from the default, so a link stays short. */
export function writeCalcState(s: CalcState): URLSearchParams {
  const p = new URLSearchParams()
  if (s.f) p.set('f', s.f)
  if (s.n) p.set('n', s.n.slice(0, NAME_MAX))
  if (s.w !== 'SEASON') p.set('w', s.w)
  if (s.m !== 'perGame') p.set('m', s.m)
  if (s.top !== TOP_DEFAULT) p.set('top', String(clampTop(s.top)))
  if (!s.q) p.set('q', '0')
  if (s.z) p.set('z', '1')
  if (s.dir === 'asc') p.set('dir', 'asc')
  writeFilters(s.filters, p)
  if (s.chart !== 'ranked') p.set('chart', s.chart)
  // specs/032 US5: like the axes, these stay in the link on other charts so a chart toggle keeps them.
  if (s.split) p.set('split', 'pos')
  if (s.cmp !== s.w && s.cmp !== defaultCmp(s.w)) p.set('cmp', s.cmp)
  if (s.chg === 'down') p.set('chg', 'down')
  // specs/032 code review B10: the axes stay in the link on the ranked view too (they are short), so toggling
  // Ranked and back does not reset the member's axis choices.
  if (s.x) p.set('x', s.x)
  if (s.y) p.set('y', s.y)
  return p
}
