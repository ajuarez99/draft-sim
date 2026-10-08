import type { LeaderboardRow, PlayerCounting, PlayerOwnership, Rate } from './api'

/**
 * specs/022-player-stat-analysis US4: the stats leaderboard's rules, kept out of the page so one
 * comparator, the column groups, the filters and the leaders all live in one place and are
 * testable (research R14). The server sends every row once, in rank order; everything here is
 * client-side over that list.
 *
 * Sort semantics, in one paragraph: a column header click sorts by that column. The first click
 * on a column uses its natural direction (best first: high-to-low, except the columns where lower
 * is better or the column is text); clicking it again flips. Whatever the direction, a row with no
 * value in that column sorts LAST, and ties break on games played (more first), then name, then
 * id, so the order never depends on the input order.
 */

export type StatMode = 'perGame' | 'totals' | 'per36'
export type SortDir = 'asc' | 'desc'
export type ColumnGroup = 'basic' | 'shooting' | 'advanced' | 'fantasy' | 'draft'
export type CellFormat = 'int' | 'dec' | 'pct' | 'fp' | 'signed' | 'signedInt' | 'rank' | 'text'

export type StatColumn = {
  id: string
  label: string
  /** Longer name for the tooltip / aria-label. */
  title: string
  /** Counting columns follow the per game / totals / per 36 control; the rest ignore it. */
  counting: boolean
  /** A smaller number is the better one (turnover rate, ranks, pick number). */
  lowerIsBetter: boolean
  format: CellFormat
  /** The column's sortable value: a number, a string for text columns, null when the row has none. */
  value: (r: LeaderboardRow, mode: StatMode) => number | string | null
  /** The Rate behind a percentage column, so a missing value can name its reason. */
  rate?: (r: LeaderboardRow) => Rate
  /** Made-attempts printed beneath a shooting percentage. */
  made?: (r: LeaderboardRow, mode: StatMode) => string | null
}

const counting = (r: LeaderboardRow, mode: StatMode): PlayerCounting | null =>
  mode === 'totals' ? r.stats.totals : mode === 'per36' ? r.stats.per36 : r.stats.perGame

const cnt =
  (k: keyof PlayerCounting) =>
  (r: LeaderboardRow, mode: StatMode): number | null =>
    counting(r, mode)?.[k] ?? null

function countCol(id: string, label: string, title: string, k: keyof PlayerCounting, lowerIsBetter = false): StatColumn {
  return { id, label, title, counting: true, lowerIsBetter, format: 'dec', value: cnt(k) }
}

function rateCol(
  id: string,
  label: string,
  title: string,
  get: (r: LeaderboardRow) => Rate,
  opts: { lowerIsBetter?: boolean; made?: StatColumn['made'] } = {},
): StatColumn {
  return {
    id, label, title, counting: false, lowerIsBetter: opts.lowerIsBetter ?? false, format: 'pct',
    value: (r) => get(r).value, rate: get, made: opts.made,
  }
}

const maText = (m: number | undefined, a: number | undefined, mode: StatMode): string | null => {
  if (m == null || a == null) return null
  return mode === 'totals' ? `${Math.round(m)}-${Math.round(a)}` : `${m.toFixed(1)}-${a.toFixed(1)}`
}
const made = (mk: keyof PlayerCounting, ak: keyof PlayerCounting) => (r: LeaderboardRow, mode: StatMode) => {
  const c = counting(r, mode)
  return maText(c?.[mk], c?.[ak], mode)
}

const plain = (
  id: string, label: string, title: string, format: CellFormat, value: StatColumn['value'], lowerIsBetter = false,
): StatColumn => ({ id, label, title, counting: false, lowerIsBetter, format, value })

const NAME: StatColumn = {
  id: 'name', label: 'Player', title: 'Player name', counting: false, lowerIsBetter: false, format: 'text',
  value: (r) => r.name,
}

export const COLUMNS: Record<string, StatColumn> = {}
const reg = (c: StatColumn) => {
  COLUMNS[c.id] = c
  return c
}
reg(NAME)

const BASIC = [
  plain('gp', 'GP', 'Games played', 'int', (r) => r.stats.games),
  {
    id: 'min', label: 'MIN', title: 'Minutes (per game, or total in totals mode)', counting: true, lowerIsBetter: false,
    format: 'dec',
    value: (r, mode) => (mode === 'totals' ? r.stats.minutes : r.stats.games > 0 ? r.stats.minutesPerGame : null),
  } as StatColumn,
  countCol('pts', 'PTS', 'Points', 'pts'),
  countCol('reb', 'REB', 'Rebounds', 'reb'),
  countCol('ast', 'AST', 'Assists', 'ast'),
  countCol('stl', 'STL', 'Steals', 'stl'),
  countCol('blk', 'BLK', 'Blocks', 'blk'),
  countCol('tpm', '3PM', 'Three-pointers made', 'tpm'),
  countCol('tov', 'TO', 'Turnovers (fewer is better)', 'tov', true),
  rateCol('fgPct', 'FG%', 'Field goal percentage', (r) => r.stats.shooting.fgPct),
  rateCol('ftPct', 'FT%', 'Free throw percentage', (r) => r.stats.shooting.ftPct),
].map(reg)

const SHOOTING = [
  rateCol('fgPctMA', 'FG%', 'Field goal percentage, with makes-attempts', (r) => r.stats.shooting.fgPct, { made: made('fgm', 'fga') }),
  rateCol('tpPctMA', '3P%', 'Three-point percentage, with makes-attempts', (r) => r.stats.shooting.tpPct, { made: made('tpm', 'tpa') }),
  rateCol('ftPctMA', 'FT%', 'Free throw percentage, with makes-attempts', (r) => r.stats.shooting.ftPct, { made: made('ftm', 'fta') }),
  rateCol('ts', 'TS%', 'True shooting percentage', (r) => r.stats.advanced.ts),
  rateCol('efg', 'eFG%', 'Effective field goal percentage', (r) => r.stats.advanced.efg),
  rateCol('ftr', 'FTr', 'Free throw rate (FTA per FGA)', (r) => r.stats.advanced.ftr),
  rateCol('tpar', '3PAr', 'Three-point attempt rate (3PA per FGA)', (r) => r.stats.advanced.tpar),
].map(reg)

const ADVANCED = [
  rateCol('usg', 'USG', 'Usage rate', (r) => r.stats.advanced.usg),
  rateCol('minutesShare', 'MIN%', 'Share of his team’s minutes', (r) => r.stats.advanced.minutesShare),
  rateCol('astPct', 'AST%', 'Assist rate', (r) => r.stats.advanced.astPct),
  rateCol('orbPct', 'ORB%', 'Offensive rebound rate', (r) => r.stats.advanced.orbPct),
  rateCol('drbPct', 'DRB%', 'Defensive rebound rate', (r) => r.stats.advanced.drbPct),
  rateCol('trbPct', 'TRB%', 'Total rebound rate', (r) => r.stats.advanced.trbPct),
  rateCol('stlPct', 'STL%', 'Steal rate', (r) => r.stats.advanced.stlPct),
  rateCol('blkPct', 'BLK%', 'Block rate', (r) => r.stats.advanced.blkPct),
  rateCol('tovPct', 'TOV%', 'Turnover rate (lower is better)', (r) => r.stats.advanced.tovPct, { lowerIsBetter: true }),
  plain('gameScore', 'GmSc', 'Game score per game', 'dec', (r) => r.stats.gameScorePerGame),
].map(reg)

const LEAGUE_RANK = plain('leagueRank', 'Rank', 'Rank by this league’s fantasy points per game', 'rank', (r) => r.leagueRank, true)
const FANTASY = [
  plain('fp', 'FP/G', 'Fantasy points per game in this league’s scoring', 'fp', (r) => r.fpPerGame),
  LEAGUE_RANK,
  plain('positionRank', 'Pos rank', 'Rank among players at his position', 'rank', (r) => r.positionRank, true),
  plain('pointsRank', 'Pts rank', 'Rank by real points per game', 'rank', (r) => r.pointsRank, true),
  plain('rankMove', 'Move', 'Places gained (+) or lost (−) in this league’s scoring versus real points', 'signedInt', (r) => r.rankMove),
  plain('vor', 'VOR', 'Fantasy points per game over the replacement level at his position', 'signed', (r) => r.valueOverReplacement),
].map(reg)

const DRAFT = [
  plain('pick', 'Pick', 'Overall pick number', 'int', (r) => r.draft?.pickNo ?? null, true),
  plain('round', 'Rd', 'Round', 'int', (r) => r.draft?.round ?? null, true),
  plain('manager', 'Drafted by', 'Manager who drafted him', 'text', (r) => r.draft?.managerName ?? null),
  plain('adp', 'Board ADP', 'Board ADP: blend of Sleeper search rank and observed mock drafts', 'dec', (r) => r.adp, true),
  plain('draftValue', 'Draft value', 'Draft Grades’ fantasy points over the pick’s slot, summed over the league’s counted weeks (a season total, not per week)', 'signed', (r) => r.draftValue),
  LEAGUE_RANK,
].map(reg)

export type GroupDef = { id: ColumnGroup; label: string; columns: StatColumn[]; defaultSort: string }
export const GROUPS: readonly GroupDef[] = [
  { id: 'basic', label: 'Basic', columns: BASIC, defaultSort: 'pts' },
  { id: 'shooting', label: 'Shooting', columns: SHOOTING, defaultSort: 'ts' },
  { id: 'advanced', label: 'Advanced', columns: ADVANCED, defaultSort: 'usg' },
  { id: 'fantasy', label: 'Fantasy', columns: FANTASY, defaultSort: 'fp' },
  { id: 'draft', label: 'Draft value', columns: DRAFT, defaultSort: 'pick' },
]
export const groupDef = (g: ColumnGroup): GroupDef => GROUPS.find((x) => x.id === g) ?? GROUPS[0]

// --- sorting ---------------------------------------------------------------------------------

export type SortState = { col: string; dir: SortDir }

/** The direction a column starts in: best first. Text columns start A to Z. */
export function defaultDir(col: StatColumn): SortDir {
  return col.lowerIsBetter || col.format === 'text' ? 'asc' : 'desc'
}

/** A header click: the same column flips, a new one starts in its natural direction. */
export function toggleSort(cur: SortState, colId: string): SortState {
  const col = COLUMNS[colId]
  if (!col) return cur
  return cur.col === colId ? { col: colId, dir: cur.dir === 'asc' ? 'desc' : 'asc' } : { col: colId, dir: defaultDir(col) }
}

/** Whether the group offers this sort: its own columns, plus the always-present name. */
export function sortOffered(group: ColumnGroup, colId: string): boolean {
  return colId === 'name' || groupDef(group).columns.some((c) => c.id === colId)
}

/** Switching group keeps the sort when its column is still there; otherwise the group's own default. */
export function resolveSort(group: ColumnGroup, sort: SortState): SortState {
  if (sortOffered(group, sort.col)) return sort
  const g = groupDef(group)
  const col = COLUMNS[g.defaultSort]
  return { col: g.defaultSort, dir: col ? defaultDir(col) : 'desc' }
}

/**
 * The one comparator. Missing values last in either direction; then value in `dir`; then games
 * played (more first), name (A to Z, a missing name last), and id, so the order is total.
 */
export function compareRows(col: StatColumn, dir: SortDir, mode: StatMode) {
  const sign = dir === 'asc' ? 1 : -1
  return (a: LeaderboardRow, b: LeaderboardRow): number => {
    const va = col.value(a, mode)
    const vb = col.value(b, mode)
    if (va == null && vb != null) return 1
    if (va != null && vb == null) return -1
    if (va != null && vb != null && va !== vb) {
      const c =
        typeof va === 'string' || typeof vb === 'string'
          ? String(va).localeCompare(String(vb))
          : (va as number) - (vb as number)
      if (c !== 0) return sign * c
    }
    if (a.stats.games !== b.stats.games) return b.stats.games - a.stats.games
    const na = a.name ?? '￿'
    const nb = b.name ?? '￿'
    const nc = na.localeCompare(nb)
    if (nc !== 0) return nc
    return a.sleeperPlayerId < b.sleeperPlayerId ? -1 : a.sleeperPlayerId > b.sleeperPlayerId ? 1 : 0
  }
}

export function sortRows(rows: LeaderboardRow[], sort: SortState, mode: StatMode): LeaderboardRow[] {
  const col = COLUMNS[sort.col] ?? NAME
  return [...rows].sort(compareRows(col, sort.dir, mode))
}

// --- filters ---------------------------------------------------------------------------------

export type Availability =
  | { kind: 'all' }
  | { kind: 'free' }
  | { kind: 'rostered' }
  | { kind: 'roster'; rosterId: number }

export type Filters = {
  /** A position code, or null for all. */
  position: string | null
  /** An NBA team code, or null for all. */
  team: string | null
  availability: Availability
}
export const NO_FILTERS: Filters = { position: null, team: null, availability: { kind: 'all' } }

/**
 * The ownership a row is filtered and shown by. When the board fell back to an earlier season's stats the
 * server also sends `currentOwnership` (the requested season's league); that one answers "who has him now?",
 * so it is used instead (code-review V1, F6). With no fallback it is absent and `ownership` is the answer.
 */
export function ownershipOf(r: LeaderboardRow): PlayerOwnership {
  return r.currentOwnership ?? r.ownership
}

export function matchesAvailability(r: LeaderboardRow, a: Availability): boolean {
  const o = ownershipOf(r)
  switch (a.kind) {
    case 'all': return true
    case 'free': return o.state === 'FREE_AGENT'
    case 'rostered': return o.state === 'ROSTERED'
    case 'roster': return o.state === 'ROSTERED' && o.rosterId === a.rosterId
  }
}

/** Whether any row's ownership is known (ROSTERED or FREE_AGENT); when none is, the ownership filters are moot (V5). */
export function hasKnownOwnership(rows: LeaderboardRow[]): boolean {
  return rows.some((r) => {
    const s = ownershipOf(r).state
    return s === 'ROSTERED' || s === 'FREE_AGENT'
  })
}

/**
 * Whether "Qualified players only" applies when sorting by this column (FR-025): it applies to RATE and RANK
 * columns -- percentages, per-game-derived advanced figures (game score), FP/G, ranks, rank move and VOR -- where a
 * short sample could top the sort. Counting stats, totals, the draft columns and text show every row.
 */
export function qualificationApplies(col: StatColumn): boolean {
  return col.format === 'pct' || col.format === 'rank' || ['fp', 'vor', 'rankMove', 'gameScore'].includes(col.id)
}

export type FilterOptions = {
  /** The page's "Qualified players only" switch. */
  qualifiedOnly: boolean
  /** The id of the column the table is sorted by; the switch only bites on rate and rank columns. */
  sortCol: string
}

/** Filters, then the qualification switch (see {@link qualificationApplies}). */
export function applyFilters(rows: LeaderboardRow[], f: Filters, o: FilterOptions): LeaderboardRow[] {
  const hide = o.qualifiedOnly && qualificationApplies(COLUMNS[o.sortCol] ?? NAME)
  return rows.filter(
    (r) =>
      (!hide || r.qualified) &&
      (f.position == null || r.positions.includes(f.position)) &&
      (f.team == null || r.team === f.team) &&
      matchesAvailability(r, f.availability),
  )
}

/** The stat leaders always use qualified rows, whatever the table's switch says. */
export function leaderRows(rows: LeaderboardRow[], f: Filters): LeaderboardRow[] {
  return applyFilters(rows, f, { qualifiedOnly: true, sortCol: 'fp' })
}

/** A rank column's header names its window when it is not the season (V6). */
export function columnLabel(col: StatColumn, windowLabel: string | null): string {
  const isRank = col.format === 'rank' || col.id === 'rankMove'
  return isRank && windowLabel ? `${col.label} (${windowLabel})` : col.label
}

// --- leaders ---------------------------------------------------------------------------------

export const LEADERS_SIZE = 5

export type LeaderCategory = { id: string; label: string; col: StatColumn; mode: StatMode }
/** The stat-leader categories. Counting stats are per game whatever mode the table is in. */
export const LEADER_CATEGORIES: readonly LeaderCategory[] = [
  { id: 'pts', label: 'Points', col: COLUMNS.pts, mode: 'perGame' },
  { id: 'reb', label: 'Rebounds', col: COLUMNS.reb, mode: 'perGame' },
  { id: 'ast', label: 'Assists', col: COLUMNS.ast, mode: 'perGame' },
  { id: 'stl', label: 'Steals', col: COLUMNS.stl, mode: 'perGame' },
  { id: 'blk', label: 'Blocks', col: COLUMNS.blk, mode: 'perGame' },
  { id: 'tpm', label: 'Threes', col: COLUMNS.tpm, mode: 'perGame' },
  { id: 'ts', label: 'True shooting %', col: COLUMNS.ts, mode: 'perGame' },
  { id: 'usg', label: 'Usage', col: COLUMNS.usg, mode: 'perGame' },
  { id: 'fp', label: 'Fantasy pts / game', col: COLUMNS.fp, mode: 'perGame' },
]

export type Leader = { row: LeaderboardRow; value: number }

/** Top `size` for one category: qualified, non-stale rows only, best first, by the table's comparator. */
export function categoryLeaders(rows: LeaderboardRow[], cat: LeaderCategory, size = LEADERS_SIZE): Leader[] {
  const dir = defaultDir(cat.col)
  return rows
    .filter((r) => r.qualified && r.reason == null && typeof cat.col.value(r, cat.mode) === 'number')
    .sort(compareRows(cat.col, dir, cat.mode))
    .slice(0, size)
    .map((r) => ({ row: r, value: cat.col.value(r, cat.mode) as number }))
}

// --- formatting ------------------------------------------------------------------------------

const MINUS = '−'
const fixed = (n: number, d: number) => n.toFixed(d).replace('-', MINUS)

export function formatValue(col: StatColumn, v: number | string | null, mode: StatMode): string {
  if (v == null) return ''
  if (typeof v === 'string') return v
  if (col.counting && mode === 'totals') return String(Math.round(v))
  switch (col.format) {
    case 'int':
    case 'rank': return String(Math.round(v))
    case 'dec': return fixed(v, 1)
    case 'pct': return `${fixed(v, 1)}%`
    case 'fp': return fixed(v, 2)
    case 'signedInt': return `${v > 0 ? '+' : v < 0 ? MINUS : ''}${Math.abs(Math.round(v))}`
    case 'signed': return `${v > 0 ? '+' : v < 0 ? MINUS : ''}${Math.abs(v).toFixed(1)}`
    case 'text': return String(v)
  }
}
