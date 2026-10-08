/**
 * Copy for the player page (specs/022-player-stat-analysis). Kept out of the
 * component so the wording is testable and every code the server can send has a
 * sentence: a code with no sentence would render as nothing, and an empty cell
 * is exactly what this page promises never to show.
 */

/** Every reason the page can have to show words where a number would go. */
export type StatReason =
  | 'NO_ATTEMPTS'
  | 'NO_MINUTES'
  | 'NO_TEAM_ROW'
  | 'NOT_QUALIFIED'
  | 'NOT_QUALIFIED_STALE'
  | 'NO_GAMES'
  | 'NO_PLAYER_GAMES'
  | 'NOT_BASKETBALL'
  | 'NOT_CONFIGURED'
  | 'NOT_DRAFTED'
  | 'UNAVAILABLE'

export const ALL_STAT_REASONS: readonly StatReason[] = [
  'NO_ATTEMPTS',
  'NO_MINUTES',
  'NO_TEAM_ROW',
  'NOT_QUALIFIED',
  'NOT_QUALIFIED_STALE',
  'NO_GAMES',
  'NO_PLAYER_GAMES',
  'NOT_BASKETBALL',
  'NOT_CONFIGURED',
  'NOT_DRAFTED',
  'UNAVAILABLE',
]

/** "Mar 4, 2026" from an ISO date or instant; the input unchanged if it is not one. */
export function longDate(iso: string): string {
  const d = new Date(iso.length === 10 ? `${iso}T12:00:00` : iso)
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}

/** One sentence per reason. `date` is only read by NOT_QUALIFIED_STALE (his last game). */
export function reasonSentence(code: StatReason, opts: { date?: string | null } = {}): string {
  switch (code) {
    case 'NO_ATTEMPTS':
      return 'No attempts in these games, so there is no percentage.'
    case 'NO_MINUTES':
      return 'No minutes played, so there is no per-36 rate.'
    case 'NO_TEAM_ROW':
      return 'Needs team totals for his games, which we don’t have.'
    case 'NOT_QUALIFIED':
      return 'Hasn’t played enough games or minutes to be ranked.'
    case 'NOT_QUALIFIED_STALE':
      return opts.date ? `Hasn’t played since ${longDate(opts.date)}, so he isn’t ranked.` : 'Hasn’t played recently, so he isn’t ranked.'
    case 'NO_GAMES':
      return 'No games have been played this season yet.'
    case 'NO_PLAYER_GAMES':
      return 'He hasn’t played a game this season.'
    case 'NOT_BASKETBALL':
      return 'Player pages are for basketball leagues.'
    case 'NOT_CONFIGURED':
      return 'Player pages aren’t set up on this server.'
    case 'NOT_DRAFTED':
      return 'This league hasn’t drafted yet, so nobody owns him.'
    case 'UNAVAILABLE':
      return 'Who owned him then isn’t available.'
  }
}

/** The two kinds of figure the page shows; every number on it carries one (FR-005). */
export const REAL_STAT_LABEL = 'Real stat'
export const FANTASY_LABEL = 'This league’s fantasy'

/** The league's scoring keys (Sleeper's NBA names) in words. */
export const SCORING_KEY_LABELS: Record<string, string> = {
  pts: 'Points',
  reb: 'Rebounds',
  ast: 'Assists',
  stl: 'Steals',
  blk: 'Blocks',
  to: 'Turnovers',
  tpm: 'Three-pointers made',
  dd: 'Double-doubles',
  td: 'Triple-doubles',
  ff: 'Flagrant fouls',
  tf: 'Technical fouls',
  bonus_pt_40p: '40-point game bonus',
  bonus_pt_50p: '50-point game bonus',
  bonus_reb_20p: '20-rebound game bonus',
  bonus_ast_15p: '15-assist game bonus',
}

/** A scoring key in words; an unrecognised key is shown as sent, never dropped. */
export function scoringKeyLabel(key: string): string {
  return SCORING_KEY_LABELS[key] ?? key
}

/** Start-year numbering: season 2025 is "2025–26". */
export function seasonLabel(season: number): string {
  return `${season}–${String((season + 1) % 100).padStart(2, '0')}`
}

/** Shown when the page fell back from the league's own season to the last one with games. */
export function fallbackNote(requested: string, shown: string): string {
  return `${requested} has no games yet; showing ${shown}. Trends may show a different season.`
}

/**
 * When an ownership reading was taken, in words. A WEEK reading on the player page
 * is the end of the regular season of the season shown; a CURRENT one is a fetch
 * time. Null when the server has no point in time to name (NOT_DRAFTED, some
 * UNAVAILABLE): the caller then labels the line plainly rather than inventing one.
 */
export function ownershipAsOf(
  asOf: { kind: 'CURRENT' | 'WEEK'; fetchedAt: string | null; week: number | null } | null,
  season: number,
): string | null {
  if (!asOf) return null
  if (asOf.kind === 'WEEK' && asOf.week != null) {
    return `End of ${seasonLabel(season)} regular season (week ${asOf.week})`
  }
  if (asOf.kind === 'CURRENT' && asOf.fetchedAt) return `As of ${longDate(asOf.fetchedAt)}`
  return null
}

/** A few words for a cell that cannot show a number; the full sentence rides in its title. */
export function reasonShort(code: StatReason): string {
  switch (code) {
    case 'NO_ATTEMPTS':
      return 'no att.'
    case 'NO_MINUTES':
      return 'no min.'
    case 'NO_TEAM_ROW':
      return 'no team data'
    case 'NOT_QUALIFIED':
    case 'NOT_QUALIFIED_STALE':
      return 'not ranked'
    case 'NO_GAMES':
    case 'NO_PLAYER_GAMES':
      return 'no games'
    case 'NOT_BASKETBALL':
      return 'n/a'
    case 'NOT_CONFIGURED':
      return 'not set up'
    case 'NOT_DRAFTED':
      return 'not drafted'
    case 'UNAVAILABLE':
      return 'unavailable'
  }
}

/**
 * How the league's scoring moves a player. pointsRank is the rank by REAL points per
 * game among the same qualified group; leagueRank is the rank by this league's fantasy
 * points per game. rankMove = pointsRank - leagueRank (positive: better here).
 */
export function rankMoveSentence(r: {
  pointsRank: number
  leagueRank: number
  groupSize: number | null
  rankMove: number | null
}): string {
  const of = r.groupSize != null && r.groupSize > 0 ? ` of ${r.groupSize}` : ''
  const head = `By real points per game he’s #${r.pointsRank}${of}; in this league’s scoring he’s #${r.leagueRank}`
  const move = r.rankMove ?? 0
  if (move === 0) return `${head} — the same in both.`
  const n = Math.abs(move)
  return `${head} — ${n} ${n === 1 ? 'place' : 'places'} ${move > 0 ? 'higher' : 'lower'}.`
}
