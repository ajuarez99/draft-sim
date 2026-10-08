import type { PlayerQualificationRule } from './api'

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
  | 'GROUP_TOO_SMALL'
  | 'OWNERSHIP_UNAVAILABLE'
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
  'GROUP_TOO_SMALL',
  'OWNERSHIP_UNAVAILABLE',
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
export function reasonSentence(code: StatReason, opts: { date?: string | null; noPosition?: boolean } = {}): string {
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
    case 'GROUP_TOO_SMALL':
      return opts.noPosition
        ? 'He has no position on file, so there is no peer group to compare him with.'
        : 'Too few other players qualify to make a fair comparison.'
    case 'OWNERSHIP_UNAVAILABLE':
      return 'Who is rostered in this league isn’t available, so there is no group to compare against.'
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
    case 'GROUP_TOO_SMALL':
      return 'group too small'
    case 'OWNERSHIP_UNAVAILABLE':
      return 'rosters unavailable'
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

/** The advanced rates, in the order the Advanced section lists them. */
export const ADVANCED_KEYS = [
  'ts', 'efg', 'ftr', 'tpar', 'usg', 'minutesShare', 'astPct', 'orbPct', 'drbPct', 'trbPct', 'stlPct', 'blkPct', 'tovPct',
] as const
export type AdvancedKey = (typeof ADVANCED_KEYS)[number]

export const ADVANCED_LABELS: Record<AdvancedKey, string> = {
  ts: 'True shooting %',
  efg: 'Effective FG %',
  ftr: 'Free throw rate',
  tpar: '3-point attempt rate',
  usg: 'Usage rate',
  minutesShare: 'Share of game minutes played',
  astPct: 'Assist %',
  orbPct: 'Offensive rebound %',
  drbPct: 'Defensive rebound %',
  trbPct: 'Total rebound %',
  stlPct: 'Steal %',
  blkPct: 'Block %',
  tovPct: 'Turnover %',
}

/**
 * Appended to every definition that compares him with his team's totals. Basketball
 * Reference uses the team's whole-season totals; we pair each of his games with that
 * game's totals (research R5), so a player who missed games or was traded can differ.
 */
export const POOLING_NOTE =
  'Uses only the games he played: each of his games is compared with his team’s totals in that same game.'

export const ADVANCED_DEFINITIONS: Record<AdvancedKey, string> = {
  ts: 'Points per shooting attempt, counting threes and free throws at their real value. A better single measure of scoring efficiency than field goal percentage.',
  efg: 'Field goal percentage that gives a made three-pointer 1.5 times the credit of a made two.',
  ftr: 'Free throw attempts for every 100 field goal attempts: how often he gets to the line.',
  tpar: 'Three-point attempts for every 100 field goal attempts: how much of his shooting comes from deep.',
  usg: 'The share of his team’s possessions that end with him shooting, getting to the line or turning it over while he is on the floor.',
  minutesShare: 'The share of the game’s minutes he was on the floor: 100% is all 48, and about 71% is 34 of 48. Not a share of his team’s 240 player-minutes.',
  astPct: 'The share of his teammates’ made field goals that he assisted while he was on the floor.',
  orbPct: 'The share of available offensive rebounds he grabbed while he was on the floor.',
  drbPct: 'The share of available defensive rebounds he grabbed while he was on the floor.',
  trbPct: 'The share of all available rebounds, offensive and defensive, he grabbed while he was on the floor.',
  stlPct: 'Steals per 100 opposing possessions while he was on the floor.',
  blkPct: 'The share of opposing two-point attempts he blocked while he was on the floor.',
  tovPct: 'Turnovers per 100 of his own shooting-or-turnover plays. Lower is better, so a high percentile here means few turnovers.',
}

/** The keys whose definition compares him with team totals, and so carry the pooling note. */
const TEAM_RELATIVE: ReadonlySet<AdvancedKey> = new Set([
  'usg', 'minutesShare', 'astPct', 'orbPct', 'drbPct', 'trbPct', 'stlPct', 'blkPct',
])

export function advancedDefinition(key: AdvancedKey): string {
  return TEAM_RELATIVE.has(key) ? `${ADVANCED_DEFINITIONS[key]} ${POOLING_NOTE}` : ADVANCED_DEFINITIONS[key]
}

/** "vs Cs across the NBA" / "vs players rostered in this league". */
export function pctGroupLabel(group: 'NBA_POSITION' | 'LEAGUE_ROSTERED', position: string | null | undefined): string {
  return group === 'NBA_POSITION'
    ? `vs ${position ? `${position}s` : 'players'} across the NBA`
    : 'vs players rostered in this league'
}

export const PLUS_MINUS_NOISY =
  'Plus-minus is noisy: it moves with who else is on the floor and the opponent, not just with him.'

/**
 * 71 -> "71st". Clamped to 1st..99th: a rounded 100th or 0th would claim he is alone at an
 * extreme when the exact value (99.69, 0.3) only says he is near it. The exact value belongs in a title.
 */
export function ordinal(n: number): string {
  const r = Math.min(99, Math.max(1, Math.round(n)))
  const v = r % 100
  const suffix = v >= 11 && v <= 13 ? 'th' : ({ 1: 'st', 2: 'nd', 3: 'rd' } as Record<number, string>)[r % 10] ?? 'th'
  return `${r}${suffix}`
}

/**
 * Who counts as a qualified peer, in words, from the rule the API sends (C1 `qualification`), so the
 * page can never describe a different rule than the one the server applied.
 */
export function qualificationRule(q: PlayerQualificationRule): string {
  const share = q.minGamesShare === 0.5 ? 'half' : `${Math.round(q.minGamesShare * 100)}%`
  return (
    `Ranked among players with at least ${q.minGames} games (${share} of the most any team has played, ${q.maxTeamGames}) ` +
    `and ${q.minMinutesPerGame}+ minutes per game; last-5/last-10 also require a game in the last ${q.recencyDays} days.`
  )
}

/** Bars on these would read as good or bad; they describe style or role, not quality. */
export const STYLE_KEYS: ReadonlySet<AdvancedKey> = new Set(['ftr', 'tpar', 'usg', 'minutesShare'])

/** What a percentile means for one stat, in words (U5): a position in the group, not a grade. */
export function percentileMeaning(key: AdvancedKey): string {
  if (key === 'tovPct') return 'higher percentile = fewer turnovers'
  if (STYLE_KEYS.has(key)) return 'higher percentile = does this more often'
  return 'higher percentile = higher rate'
}

/**
 * The rostered group's date, beside its toggle. `fellBack`: the page shows an earlier season than
 * the league's own, so the rosters are that season's rather than today's.
 */
export function rosteredGroupNote(
  asOf: Parameters<typeof ownershipAsOf>[0],
  season: number,
  fellBack: boolean,
): string {
  const when = ownershipAsOf(asOf, season)
  const base = when ? `Players rostered in this league · ${when}` : 'Players rostered in this league · date not available'
  return fellBack ? `${base}. These are ${seasonLabel(season)} rosters, not today’s.` : `${base}.`
}
