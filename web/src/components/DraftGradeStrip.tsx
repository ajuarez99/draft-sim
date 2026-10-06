import type { PickGrade, TeamGrade } from '../api'
import Avatar from './Avatar'
import GradeChip from './GradeChip'
import { signedPoints } from '../draftGrades'

type Props = {
  teams: TeamGrade[]
  /** Used only to turn bestPickNo/worstPickNo into names. */
  picks: PickGrade[]
}

/**
 * One item per draft slot: who, how the draft did against the average team in
 * this draft (signed points, with its letter), and his best and worst pick.
 *
 * Its own component and class on purpose: `TeamStrip`/`.team-strip` already
 * exist and mean "your roster against the lineup template" (spec 018 N10).
 * The strip scrolls horizontally inside itself so a 12-14 team draft never
 * pushes the page wider than a phone (FR-012).
 *
 * A team with no graded pick carries a null draftValue: it prints "—" and no
 * grade, never a zero, because zero would read as "exactly average".
 */
export default function DraftGradeStrip({ teams, picks }: Props) {
  if (teams.length === 0) return null
  const nameOf = new Map(picks.map((p) => [p.pickNo, p.playerName]))
  const ordered = [...teams].sort((a, b) => a.slot - b.slot)
  return (
    <div className="draft-grade-strip" role="list" aria-label="Draft grade by team">
      {ordered.map((t) => {
        const best = t.bestPickNo != null ? nameOf.get(t.bestPickNo) : undefined
        const worst = t.worstPickNo != null ? nameOf.get(t.worstPickNo) : undefined
        const label = t.manager ?? `Slot ${t.slot}`
        const value = t.draftValue
        return (
          <div key={t.slot} className="dgs-item" role="listitem">
            <div className="dgs-who">
              <Avatar avatarId={t.avatarId} seed={String(t.slot)} label={label} />
              <span className="dgs-name mono">{label}</span>
            </div>
            <div className="dgs-value">
              {value == null ? (
                <span className="dgs-none mono" title="No graded picks for this team">
                  —
                </span>
              ) : (
                <GradeChip grade={t.grade}>
                  <span className={`dgs-num mono ${value >= 0 ? 'up' : 'down'}`}>{signedPoints(value)}</span>
                </GradeChip>
              )}
            </div>
            <div className="dgs-axis muted">vs. the average team in this draft</div>
            <div className="dgs-picks small">
              <span className="dgs-pick">
                <span className="muted">Best </span>
                {best ?? '—'}
              </span>
              <span className="dgs-pick">
                <span className="muted">Worst </span>
                {worst ?? '—'}
              </span>
            </div>
          </div>
        )
      })}
    </div>
  )
}
