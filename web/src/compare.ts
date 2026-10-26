import type { PlayerCounting, PlayerStatsPage, PlayerWindow, PlayerWindowKind, Rate } from './api'
import { DASH, fixed, madeAttempted, num } from './statFormat'
import { reasonShort, type StatReason } from './statCopy'

/**
 * specs/049-player-compare: the comparison's row table and the one rule that decides whether a row is marked.
 *
 * Pure, no React. The page composes two `PlayerStatsPage` responses and asks `markRow` per row; every figure is
 * read off those responses and printed by `statFormat`, so it equals the player page's (FR-002) by construction.
 *
 * The rule is a guard list (data-model.md), and each guard exists because a mark that fails it would look like a
 * verdict the data cannot back. A row is marked ONLY when every one holds. The minutes-per-game floor and a
 * SEASON window's `qualified` are ranking rules, not mark guards: the player page ranks only qualified players,
 * but comparing two players' own averages doesn't need a rank (plan A7, a stated design choice).
 */

export type Direction = 'HIGHER' | 'LOWER' | 'NONE'
export type Side = 'A' | 'B'

export type Row = {
  key: string
  label: string
  /** The window whose figures this row prints; its guards are the ones applied. */
  window: PlayerWindowKind
  /** NONE rows are never marked: a rate on a tiny denominator, a count that isn't value, a total that isn't credited. */
  direction: Direction
  /** The number compared; null when the player has none (no games, or a `Rate` with a `reason`). */
  value: (p: PlayerStatsPage) => number | null
  /** What the cell prints; never blank (a dash, or the short reason for a rate with none). */
  display: (p: PlayerStatsPage) => string
  /** Made-attempted beneath a shooting percentage. */
  sub?: (p: PlayerStatsPage) => string | null
}

export type Mark = { mark: Side | null; withheld: { A?: string; B?: string } }

export const WINDOW_LABELS: Record<PlayerWindowKind, string> = { SEASON: 'Season', LAST_10: 'Last 10', LAST_5: 'Last 5' }
export const WINDOW_ORDER: PlayerWindowKind[] = ['SEASON', 'LAST_10', 'LAST_5']

const win = (p: PlayerStatsPage, w: PlayerWindowKind): PlayerWindow | undefined => p.windows?.[w]

/** `perGame` is null when he has no games in the window; a count is then missing, not zero. */
const perGame = (w: PlayerWindowKind, k: keyof PlayerCounting) => (p: PlayerStatsPage) => win(p, w)?.perGame?.[k] ?? null

const rateValue = (r: Rate | undefined): number | null => (r && r.value != null ? r.value : null)
const rateText = (r: Rate | undefined): string => {
  if (!r) return DASH
  if (r.value != null) return `${fixed(r.value, 1)}%`
  return reasonShort((r.reason ?? 'UNAVAILABLE') as StatReason)
}

function countRow(w: PlayerWindowKind, key: keyof PlayerCounting, label: string, direction: Direction): Row {
  return {
    key: `${w}.${key}`,
    label,
    window: w,
    direction,
    value: perGame(w, key),
    display: (p) => num(perGame(w, key)(p)),
  }
}

function shootingRow(w: PlayerWindowKind, key: 'fgPct' | 'tpPct' | 'ftPct', label: string,
  made: 'fgm' | 'tpm' | 'ftm', att: 'fga' | 'tpa' | 'fta'): Row {
  return {
    key: `${w}.${key}`,
    label,
    window: w,
    direction: 'NONE', // F4: a percentage on a handful of attempts says nothing; the made-attempted beneath does
    value: (p) => rateValue(win(p, w)?.shooting[key]),
    display: (p) => rateText(win(p, w)?.shooting[key]),
    sub: (p) => {
      const x = win(p, w)
      return x && x.shooting[key].value != null ? madeAttempted(x.totals[made], x.totals[att]) : null
    },
  }
}

/** Per window: games, fantasy points per game, minutes per game. The three windows always show. */
export function windowRows(w: PlayerWindowKind): Row[] {
  return [
    {
      key: `${w}.games`,
      label: 'Games',
      window: w,
      direction: 'NONE', // F6: more games is availability, not a better player
      value: (p) => win(p, w)?.games ?? null,
      display: (p) => String(win(p, w)?.games ?? 0),
    },
    {
      key: `${w}.fp`,
      label: 'Fantasy pts / game',
      window: w,
      direction: 'HIGHER',
      value: (p) => p.fantasy?.fpPerGame?.[w] ?? null,
      display: (p) => num(p.fantasy?.fpPerGame?.[w] ?? null, 2),
    },
    {
      key: `${w}.mpg`,
      label: 'Minutes / game',
      window: w,
      direction: 'HIGHER', // a role signal
      value: (p) => {
        const x = win(p, w)
        return x && x.games > 0 ? x.minutesPerGame : null
      },
      display: (p) => {
        const x = win(p, w)
        return x && x.games > 0 ? num(x.minutesPerGame) : DASH
      },
    },
  ]
}

/** The season's box-score total: the player page's `seasonTotal`, not the league's credited points (F6). */
export const TOTAL_ROW: Row = {
  key: 'SEASON.total',
  label: 'Season total (box-score total, not the league’s credited points)',
  window: 'SEASON',
  direction: 'NONE',
  value: (p) => p.fantasy?.seasonTotal ?? null,
  display: (p) => num(p.fantasy?.seasonTotal ?? null, 1),
}

/** Per game for the chosen window, then turnover rate, then shooting. */
export function boxRows(w: PlayerWindowKind): Row[] {
  return [
    countRow(w, 'pts', 'PTS', 'HIGHER'),
    countRow(w, 'reb', 'REB', 'HIGHER'),
    countRow(w, 'ast', 'AST', 'HIGHER'),
    countRow(w, 'stl', 'STL', 'HIGHER'),
    countRow(w, 'blk', 'BLK', 'HIGHER'),
    countRow(w, 'tpm', '3PM', 'HIGHER'),
    countRow(w, 'tov', 'TOV', 'NONE'), // F5: fewer turnovers is lower usage as often as it is better play
    countRow(w, 'pf', 'PF', 'NONE'),
    {
      key: `${w}.tovPct`,
      label: 'Turnover rate',
      window: w,
      direction: 'LOWER',
      value: (p) => rateValue(win(p, w)?.advanced.tovPct),
      display: (p) => rateText(win(p, w)?.advanced.tovPct),
    },
    shootingRow(w, 'fgPct', 'FG%', 'fgm', 'fga'),
    shootingRow(w, 'tpPct', '3P%', 'tpm', 'tpa'),
    shootingRow(w, 'ftPct', 'FT%', 'ftm', 'fta'),
  ]
}

const DAY_MS = 86_400_000
const daysBetween = (laterIso: string, earlierIso: string) =>
  Math.round((Date.parse(`${laterIso}T00:00:00Z`) - Date.parse(`${earlierIso}T00:00:00Z`)) / DAY_MS)

/**
 * Why this player's window can't be compared, or null when it can. Reads only what the response carries:
 * `smallSample` (the player page's own minutes rule) and, for a last-N window, the server's `windowQualification`
 * (never a literal 10 or 5 here). An older backend has none, so every last-N window is withheld.
 */
export function windowWithheld(p: PlayerStatsPage, other: PlayerStatsPage, w: PlayerWindowKind): string | null {
  const x = win(p, w)
  if (!x) return null // no window to compare: the row's value is null and says so on its own
  // Order: no games, then a games shortfall, then stale, then a small sample -- the reasons a reader can act on
  // first, and the game count always visible (FR-005, review S2).
  if (x.games === 0) return 'no games'
  const q = w === 'SEASON' ? null : p.windowQualification?.[w]
  if (w !== 'SEASON') {
    if (!q || q.lastN == null) return 'last-N rule unavailable'
    if (x.games < q.lastN) return `${x.games} of ${q.lastN} games`
    // `stale` is the server's recency rule on its own; `reason` alone misses a window that also failed the
    // minutes floor (review B2). An older backend sends no `stale`, so its reason is the fallback.
    if (q.stale ?? q.reason === 'NOT_QUALIFIED_STALE') {
      // The newest game this pair shows is a floor for the season's newest, so N is only reported when it clears
      // the rule on its own; otherwise say what the rule is rather than a number that contradicts it.
      const newest = [x.lastGameDate, win(other, w)?.lastGameDate, win(p, 'SEASON')?.lastGameDate, win(other, 'SEASON')?.lastGameDate]
        .filter((d): d is string => d != null)
        .sort()
        .at(-1)
      const recency = p.qualification?.recencyDays
      const days = newest && x.lastGameDate ? daysBetween(newest, x.lastGameDate) : null
      if (days != null && recency != null && days > recency) return `last game ${days} days before the newest`
      return recency != null ? `last game more than ${recency} days before the season’s newest` : 'last game too long ago'
    }
  }
  if (x.smallSample) return `small sample (${Math.round(x.minutes)} min)`
  return null
}

/**
 * Per window, why each side can't be compared (shown once in the section header, review N4). Under the same
 * preconditions as `markRow`: both players present, and from the same refresh.
 */
export function windowNotes(a: PlayerStatsPage | null, b: PlayerStatsPage | null, w: PlayerWindowKind): { A?: string; B?: string } {
  if (!a || !b || !a.available || !b.available) return {}
  if (a.season !== b.season || a.dataAsOf !== b.dataAsOf) return {}
  const wa = windowWithheld(a, b, w)
  const wb = windowWithheld(b, a, w)
  return { ...(wa ? { A: wa } : {}), ...(wb ? { B: wb } : {}) }
}

const NOTHING: Mark = { mark: null, withheld: {} }

/** The guard list, in data-model.md's order. A null mark with `withheld` text is a window the cell should explain. */
export function markRow(row: Row, a: PlayerStatsPage | null, b: PlayerStatsPage | null): Mark {
  if (!a || !b || !a.available || !b.available) return NOTHING
  if (a.season !== b.season || a.dataAsOf !== b.dataAsOf) return NOTHING
  if (row.direction === 'NONE') return NOTHING
  const withheld = windowNotes(a, b, row.window)
  if (withheld.A || withheld.B) return { mark: null, withheld }
  const va = row.value(a)
  const vb = row.value(b)
  if (va == null || vb == null) return NOTHING
  // The DISPLAYED strings decide equality: 12.04 vs 12.03 both print "12.0", and a mark between two identical
  // cells would point at a difference the reader cannot see.
  if (row.display(a) === row.display(b)) return NOTHING
  const aWins = row.direction === 'HIGHER' ? va > vb : va < vb
  return { mark: aWins ? 'A' : 'B', withheld: {} }
}
