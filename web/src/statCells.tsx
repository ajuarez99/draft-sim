import type { LeaderboardRow, StatLeaderboard as Board } from './api'
import { formatValue, type StatColumn, type StatMode } from './statLeaderboard'
import { longDate, reasonSentence, reasonShort, type MissingReason } from './statCopy'

/**
 * One leaderboard cell and the copy behind it, shared by the stat leaderboard page and the
 * draft room's stats panel (spec 023, FR-002: one renderer). Moved verbatim out of
 * pages/StatLeaderboard.tsx.
 */

export const DASH = '—'

export const GRADES_REASONS: Record<string, string> = {
  NOT_CONFIGURED: 'Draft Grades aren’t set up on this server, so there is no draft value.',
  DRAFT_NOT_COMPLETE: 'Draft value appears once the draft is complete and games are scored.',
  NO_SCORED_WEEKS: 'Draft value appears once games are scored.',
  NO_DRAFT: 'This league has no draft, so there is no draft value.',
}

export function adpSentence(d: Board): string | null {
  const a = d.adp
  if (!a) return null
  if (a.capturedOn) {
    return `Board ADP — blend of Sleeper search rank and observed mock drafts, captured ${longDate(a.capturedOn)}.`
  }
  if (a.reason === 'NO_DRAFT_DATE') return 'Board ADP: no draft date, so there is no ADP to read.'
  return 'Board ADP: no ADP stored for this season.'
}


function noPickText(d: Board): { short: string; long: string } {
  switch (d.draft?.state) {
    case 'COMPLETE':
      return { short: 'undrafted', long: 'Undrafted: no pick in this league’s draft.' }
    case 'NOT_HAPPENED':
      return { short: DASH, long: 'The draft hasn’t happened yet.' }
    default:
      return { short: DASH, long: 'This league has no draft.' }
  }
}

/** The columns whose value is a rank or value-over-replacement: blank for an unqualified row. */
const isRankCol = (col: StatColumn) => col.format === 'rank' || col.id === 'rankMove' || col.id === 'vor'

/**
 * Why `col` has no number for `row` in `mode` (specs/032 R15). The one place the answer lives:
 * `Cell` words it for the table and the calculator words it for its excluded list, so the two
 * cannot disagree. Only meant to be called when `col.value(row, mode)` is null.
 *
 * `fp` (FP/G) is deliberately NOT qualification-gated (F3): it is computed for every row, so a
 * null there is UNKNOWN_NULL rather than "not qualified".
 */
export function missingReason(col: StatColumn, row: LeaderboardRow, mode: StatMode): MissingReason {
  // A zero-game row has no figure of any kind, so say that before anything column-specific; otherwise
  // FP/G or game score on such a row would fall through to UNKNOWN_NULL (Phase 2 review).
  if (row.stats.games === 0) return 'NO_GAMES'
  if (col.rate) return col.rate(row).reason ?? 'UNKNOWN_NULL'
  if (col.counting && mode === 'per36' && row.stats.per36 == null) return 'NO_MINUTES'
  if (isRankCol(col)) {
    if (!row.qualified) return row.reason ?? 'NOT_QUALIFIED'
    if (col.id === 'vor') return 'NO_REPLACEMENT_LEVEL'
    if (col.id === 'positionRank') return 'NO_POSITION'
  }
  return 'UNKNOWN_NULL'
}

export function Cell({ col, row, d, mode }: { col: StatColumn; row: LeaderboardRow; d: Board; mode: StatMode }) {
  const v = col.value(row, mode)
  if (v != null) {
    const text = formatValue(col, v, mode)
    if (col.id === 'rankMove' && typeof v === 'number') {
      const cls = v > 0 ? 'pt-up' : v < 0 ? 'pt-down' : 'pt-flat'
      return <span className={`pt-pill ${cls} sl-move`}>{text}</span>
    }
    if (col.id === 'vor') {
      return (
        <>
          <span>{text}</span>
          {row.vorPosition && <span className="pp-made muted small">{`vs ${row.vorPosition}`}</span>}
        </>
      )
    }
    const sub = col.made?.(row, mode)
    return (
      <>
        <span>{text}</span>
        {sub && <span className="pp-made muted small">{sub}</span>}
      </>
    )
  }
  if (col.rate) {
    const code = missingReason(col, row, mode)
    const s = reasonSentence(code)
    return <span className="pp-rate-none" title={s} aria-label={s}>{reasonShort(code)}</span>
  }
  if (col.id === 'pick' || col.id === 'round' || col.id === 'manager') {
    const t = noPickText(d)
    return <span className="pp-rate-none" title={t.long} aria-label={t.long}>{col.id === 'pick' ? t.short : DASH}</span>
  }
  if (col.id === 'adp') {
    const s = adpSentence(d) ?? 'No ADP.'
    return <span className="pp-rate-none" title={s} aria-label={s}>{DASH}</span>
  }
  if (col.id === 'draftValue') {
    const g = d.draftGrades
    const s =
      g && !g.available
        ? (GRADES_REASONS[g.reason ?? ''] ?? 'No draft value available.')
        : row.draft
          ? 'No draft value for this pick.'
          : noPickText(d).long
    return <span className="pp-rate-none" title={s} aria-label={s}>{DASH}</span>
  }
  const code = missingReason(col, row, mode)
  if (col.counting && mode === 'per36' && code === 'NO_MINUTES') {
    const s = reasonSentence('NO_MINUTES')
    return <span className="pp-rate-none" title={s} aria-label={s}>{reasonShort('NO_MINUTES')}</span>
  }
  const s = reasonSentence(code, { date: row.stats.lastGameDate })
  return <span className="pp-rate-none" title={s} aria-label={s}>{DASH}</span>
}
