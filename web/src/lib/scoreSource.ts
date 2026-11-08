import type { ScoreSource, WeekScoreSource } from '../api'

/** [1,2,3,5] -> "1–3, 5". Weeks are sorted and de-duplicated first. */
export function weekRuns(weeks: number[]): string {
  const sorted = [...new Set(weeks)].sort((a, b) => a - b)
  const runs: string[] = []
  let i = 0
  while (i < sorted.length) {
    let j = i
    while (j + 1 < sorted.length && sorted[j + 1] === sorted[j] + 1) j++
    runs.push(j > i ? `${sorted[i]}–${sorted[j]}` : String(sorted[i]))
    i = j + 1
  }
  return runs.join(', ')
}

function weeksWord(weeks: number[]): string {
  return `${new Set(weeks).size === 1 ? 'Week' : 'Weeks'} ${weekRuns(weeks)}`
}

/**
 * Spec 057: the sentences that name which weeks do not use Sleeper's public feed, in words. Nothing is said for
 * FEED weeks: the feed is the default, and a note about it would be noise on every league without an override.
 */
export function scoreSourceNotes(sources: WeekScoreSource[]): string[] {
  const commissioner = sources.filter((s) => s.source === 'COMMISSIONER').map((s) => s.week)
  const official = sources.filter((s) => s.source === 'OFFICIAL').map((s) => s.week)
  const notes: string[] = []
  if (commissioner.length > 0) {
    const verb = new Set(commissioner).size === 1 ? 'uses' : 'use'
    notes.push(`${weeksWord(commissioner)} ${verb} scores the commissioner entered from the Sleeper app.`)
  }
  if (official.length > 0) {
    const verb = new Set(official).size === 1 ? 'uses' : 'use'
    notes.push(`${weeksWord(official)} ${verb} Sleeper's official totals.`)
  }
  return notes
}

/** The one-line note under a single week's matchups; null for FEED, a live week, or an unavailable report. */
export function reportScoreNote(source: ScoreSource | null | undefined): string | null {
  if (source === 'COMMISSIONER') return 'Scores entered by the commissioner from the Sleeper app.'
  if (source === 'OFFICIAL') return "Scores are Sleeper's official totals."
  return null
}

/** Why a bench or lineup number can disagree with a team's score on a non-feed week (FR-012). */
export const PER_PLAYER_FEED_NOTE =
  "Player points, bench and lineup numbers still come from Sleeper's public feed."
