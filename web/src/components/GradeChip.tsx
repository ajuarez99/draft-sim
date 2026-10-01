import type { ReactNode } from 'react'

type Props = {
  /** Letter from the server, or null/undefined when none was computed. */
  grade?: string | null
  /** The number the grade describes. A grade is never drawn without it. */
  children: ReactNode
}

/**
 * A grade beside the number it grades. Taking the number as `children` is the
 * point: there is no way to place the letter on its own, away from the figure
 * it summarises. With no grade it renders the number untouched, so a league
 * with no configured cutoffs, or a row with nothing to grade, looks as it did.
 *
 * The "early" caveat is not drawn per row: twelve identical badges down a column
 * is noise. It sits once on the grade column's header (GradesEarlyBadge), beside
 * every grade it qualifies (spec 013 parent review).
 */
export default function GradeChip({ grade, children }: Props) {
  if (grade == null || grade === '') return <>{children}</>
  return (
    <span className="grade-wrap">
      {children}
      <span className="grade-chip" title={`Grade ${grade}`}>
        {grade}
      </span>
    </span>
  )
}

/** The column-level "early" caveat for grades, driven only by the payload's `gradesEarly`. */
export function GradesEarlyBadge({ early }: { early?: boolean }) {
  if (!early) return null
  return <span className="sl-early small grade-early">early — this is mostly noise</span>
}

/** The grades sentence for How this works, using the server's threshold; no local copy of the number. */
export function gradesEarlySentence(weeks: number | undefined): string {
  return weeks != null
    ? `Grades are marked early while fewer than ${weeks} weeks are scored.`
    : 'Grades are marked early for the first weeks of the season.'
}
