import type { DraftGrades, DraftGradesReason, ProductionBasis } from './api'

/**
 * One muted sentence per unavailable reason. Deliberately free of operator
 * language ("ingest", an endpoint path): the reader is a league member, not
 * whoever runs the server (spec 018 N4).
 */
export function reasonSentence(reason: DraftGradesReason | null, weeksMissingGameData: number[] = []): string {
  switch (reason) {
    case 'DRAFT_NOT_COMPLETE':
      return "This draft isn't finished, so there is nothing to grade yet."
    case 'NO_SCORED_WEEKS':
      if (weeksMissingGameData.length > 0) {
        const w = [...weeksMissingGameData].sort((a, b) => a - b)
        return `${w.length === 1 ? 'Week' : 'Weeks'} ${w.join(', ')} ${w.length === 1 ? 'is' : 'are'} scored, but ${
          w.length === 1 ? 'its' : 'their'
        } game-by-game stats aren't loaded yet, so there's nothing to grade.`
      }
      return 'No weeks have been scored yet, so there is nothing to grade yet.'
    case 'NOT_CONFIGURED':
    default:
      return "Draft grades aren't available for this draft."
  }
}

/** What "production" is a sum of. NBA states the averaging; NFL has one game a week. */
export function productionLabel(basis: ProductionBasis): string {
  return basis === 'WEEKLY_AVERAGE_GAME'
    ? "season points, counting each week's average game"
    : 'season points'
}

function isContiguous(weeks: number[]): boolean {
  for (let i = 1; i < weeks.length; i++) if (weeks[i] !== weeks[i - 1] + 1) return false
  return true
}

export function weekWord(n: number): string {
  return n === 1 ? 'week' : 'weeks'
}

/** The legend under the board: the basis, the weeks counted (or which ones), then any weeks missing game data. */
export function legendText(g: Pick<DraftGrades, 'productionBasis' | 'countedWeeks' | 'weeksCounted' | 'weeksMissingGameData'>): string {
  const parts: string[] = []
  const label = productionLabel(g.productionBasis)
  parts.push(label.charAt(0).toUpperCase() + label.slice(1) + '.')
  if (g.productionBasis === 'WEEKLY_AVERAGE_GAME') {
    parts.push('In the basketball leagues measured so far, Sleeper credited one game per starter per week.')
  }
  const weeks = [...g.countedWeeks].sort((a, b) => a - b)
  if (weeks.length > 0 && !isContiguous(weeks)) {
    parts.push(`Counts weeks ${weeks.join(', ')}.`)
  } else {
    parts.push(`Counts ${g.weeksCounted} ${weekWord(g.weeksCounted)}.`)
  }
  if (g.weeksMissingGameData.length > 0) {
    parts.push(`Not counted, no game data yet: ${weekWord(g.weeksMissingGameData.length)} ${g.weeksMissingGameData.join(', ')}.`)
  }
  return parts.join(' ')
}

/**
 * Cell tint strength, 0..1, for a value over slot. Its own scale -- |value|
 * over the 90th percentile of |value| in this draft, capped at 1 -- because
 * stealsReaches.tintPercent is scaled in picks and would saturate on points.
 * Relative to the draft's own spread, so the biggest tenth reads full strength
 * and the rest grade down from there.
 */
export function valueTint(value: number, allValues: readonly number[]): number {
  const abs = allValues.map(Math.abs).sort((a, b) => a - b)
  if (abs.length === 0) return 0
  const idx = Math.min(abs.length - 1, Math.ceil(0.9 * abs.length) - 1)
  const p90 = abs[Math.max(0, idx)]
  const scale = p90 > 0 ? p90 : abs[abs.length - 1]
  if (!(scale > 0)) return 0
  return Math.min(1, Math.abs(value) / scale)
}

/** "+12.3" / "−8.0" with a real minus sign; one decimal because values are points. */
export function signedPoints(v: number): string {
  const r = Math.round(v * 10) / 10
  if (r === 0) return '0.0'
  return r > 0 ? `+${r.toFixed(1)}` : `−${Math.abs(r).toFixed(1)}`
}

// Re-exported so PlayerCard and draftGrades.test keep their imports (spec 021).
export { ordinal } from './format'
