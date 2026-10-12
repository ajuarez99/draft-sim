import type { ProjectionProvenance, ProjectionReasonCode, ProjectionUnit } from './api'

/*
 * spec 030: the words around a projection, in one place. The league home's
 * strength block and the Analysis page both describe the same projection, and
 * two copies of "what this number is" would drift. The server sends codes
 * (reasonCode, basisNote, unprojectedCategories); the sentences live here.
 */

/** What a projected total is a total OF, printed beside every number. */
export function unitLabel(unit: ProjectionUnit): string {
  return unit === 'PER_WEEK' ? 'pts/week (projected)' : 'pts (projected)'
}

/** The compact unit printed beside a number in a tight row; the caption says "projected" once for them all. */
export function unitShort(unit: ProjectionUnit): string {
  return unit === 'PER_WEEK' ? 'pts/wk' : 'pts'
}

/** The one place a block of rows says that its numbers are projected, and in what unit. */
export function unitCaption(unit: ProjectionUnit): string {
  return unit === 'PER_WEEK' ? 'Projected points per week' : 'Projected points, rest of the regular season'
}

/**
 * Sleeper scoring keys the NBA projection can't produce, in words. The list of
 * WHICH keys is the server's (`unprojectedCategories`); this only names them.
 * Any key not listed here is shown raw rather than guessed at.
 */
const CATEGORY_NAMES: Record<string, string> = {
  bonus_ast_15p: '15+ assist bonus',
  bonus_pt_40p: '40+ point bonus',
  bonus_pt_50p: '50+ point bonus',
  bonus_reb_20p: '20+ rebound bonus',
  ff: 'flagrant fouls',
  tf: 'technical fouls',
}

export function categoryNames(keys: readonly string[]): string[] {
  return keys.map((k) => CATEGORY_NAMES[k] ?? k)
}

/** Sentences for the codes the basketball path raises. Football keeps the server's own sentences. */
const REASON_SENTENCES: Partial<Record<ProjectionReasonCode, string>> = {
  PROJECTIONS_NOT_LOADED: "Basketball projections haven't loaded yet. They refresh daily, so check back soon.",
  ROSTERS_EMPTY: "Rosters aren't filled in yet, so there is nothing to rank.",
  SCORING_NOT_LOADED: "This league's scoring settings aren't stored yet, so projections can't be scored.",
  UNSUPPORTED_SLOTS: "This league's lineup slots aren't ones the basketball projection can build a lineup for.",
}

/**
 * The sentence for an unavailable projection. Per-week (basketball) results
 * map the code; everything else shows the server's sentence, so football's
 * wording is untouched. An unmapped code falls back to the sentence too.
 */
export function reasonText(
  code: ProjectionReasonCode | null,
  reason: string | null,
  unit: ProjectionUnit,
): string | null {
  if (unit === 'PER_WEEK' && code && REASON_SENTENCES[code]) return REASON_SENTENCES[code]!
  return reason ?? (code ? (REASON_SENTENCES[code] ?? null) : null)
}

export const ONE_GAME_NOTE =
  'One average game per starter. Sleeper credits one game a week, and the credited game has run about 15% above the average, so these read low.'

export const NOT_MODELLED_NOTE = "Players tagged Out count as zero wherever they sit. Day-to-day and game-time players count in full, including ones parked on IR+, since one game a week is credited."

/** Whole days between a timestamp and now; never negative. */
export function ageInDays(iso: string, now: Date = new Date()): number {
  return Math.max(0, Math.floor((now.getTime() - new Date(iso).getTime()) / 86_400_000))
}

function ageWords(days: number): string {
  return days === 0 ? 'today' : days === 1 ? '1 day ago' : `${days} days ago`
}

/** "Source: sleeper · updated 2 days ago · accuracy for this league not measured". */
export function provenanceLine(p: ProjectionProvenance | null, now: Date = new Date()): string {
  const parts: string[] = []
  if (p?.source) parts.push(`Source: ${p.source}`)
  if (p?.sourceModifiedAt) parts.push(`updated ${ageWords(ageInDays(p.sourceModifiedAt, now))}`)
  parts.push('accuracy for this league not measured')
  const line = parts.join(' · ')
  return line.charAt(0).toUpperCase() + line.slice(1)
}

/** "Projections are 5 days old" once past the hand-set threshold, otherwise null. */
export function staleLine(p: ProjectionProvenance | null, staleAfterDays: number, now: Date = new Date()): string | null {
  if (!p?.sourceModifiedAt) return null
  const days = ageInDays(p.sourceModifiedAt, now)
  return days > staleAfterDays ? `Projections are ${days} days old.` : null
}

type Footnoted = {
  unit: ProjectionUnit
  basisNote: 'ONE_AVERAGE_GAME_PER_STARTER' | null
  provenance: ProjectionProvenance | null
  unprojectedCategories: string[]
  staleAfterDays: number
}

/**
 * Every footnote a projection carries, in reading order: where it came from,
 * how old it is, what one number is, what it leaves out. Football's
 * rest-of-season block has none of the basketball caveats, so it gets only
 * the source line.
 */
export function projectionNotes(p: Footnoted, now: Date = new Date()): string[] {
  const notes = [provenanceLine(p.provenance, now)]
  const stale = staleLine(p.provenance, p.staleAfterDays, now)
  if (stale) notes.push(stale)
  if (p.basisNote === 'ONE_AVERAGE_GAME_PER_STARTER') notes.push(ONE_GAME_NOTE)
  if (p.unprojectedCategories.length > 0) notes.push(`Not projected: ${categoryNames(p.unprojectedCategories).join(', ')}.`)
  if (p.unit === 'PER_WEEK') notes.push(NOT_MODELLED_NOTE)
  return notes
}
