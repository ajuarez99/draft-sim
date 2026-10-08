import { type AnalysisLineupPlayer, type AnalysisMatchups, type AnalysisMatchup, type AnalysisSide } from '../../api'
import { ManagerLink, NotYet, pts, useTeamName } from './shared'

/** The best projected starter on a side -- the card's one-line "why". */
export function topStarter(side: AnalysisSide): AnalysisLineupPlayer | null {
  return side.starters.reduce<AnalysisLineupPlayer | null>(
    (best, p) => (best == null || p.points > best.points ? p : best),
    null,
  )
}

export function GameCard({ game }: { game: AnalysisMatchup }) {
  const teamName = useTeamName()
  const [a, b] = game.sides
  const mine = game.sides.some((s) => s.isMe)

  // A group of one is a bye, which the wire carries deliberately: a manager
  // with no game next week needs telling, and a missing card tells them
  // nothing.
  if (!b) {
    return (
      <div className={`analysis-game bye${mine ? ' mine' : ''}`}>
        <div className="analysis-game-side">
          <ManagerLink
            managerId={a.managerId}
            manager={a.manager}
            rosterId={a.rosterId}
            avatarId={a.avatarId}
            isMe={a.isMe}
          />
          <span className="mono analysis-game-pts">{pts(a.projected)}</span>
        </div>
        <p className="muted small analysis-game-note">No opponent this week — a bye.</p>
      </div>
    )
  }

  const margin = a.projected - b.projected
  const total = a.projected + b.projected
  const share = total > 0 ? (a.projected / total) * 100 : 50
  return (
    <div className={`analysis-game${mine ? ' mine' : ''}`}>
      {[a, b].map((side, i) => {
        const top = topStarter(side)
        return (
          <div key={side.rosterId} className={`analysis-game-row${i === 0 ? ' lead' : ''}`}>
            <div className="analysis-game-side">
              <ManagerLink
                managerId={side.managerId}
                manager={side.manager}
                rosterId={side.rosterId}
                avatarId={side.avatarId}
                isMe={side.isMe}
              />
              <span className="mono analysis-game-pts">{pts(side.projected)}</span>
            </div>
            {/* Under the manager it belongs to. A shared footer of two top
                scorers made the reader guess which was whose, which is a
                second thing for one mark to encode. */}
            {top && (
              <p className="analysis-game-top muted small">
                <span className={`pos ${top.position}`}>{top.position}</span>
                {top.name}
                <span className="mono"> {pts(top.points)}</span>
              </p>
            )}
          </div>
        )
      })}

      {/* Two projected totals subtracted, and the page says exactly that. It
          is deliberately not dressed up as a win probability: that needs a
          variance model this block does not have. */}
      <div
        className="analysis-game-split"
        role="img"
        aria-label={`${teamName(a.manager, a.rosterId)} ${pts(a.projected)}, ${teamName(b.manager, b.rosterId)} ${pts(b.projected)}`}
      >
        <span className="analysis-game-fill" style={{ width: `${share}%` }} />
      </div>

      <p className="analysis-game-margin">
        {margin === 0 ? (
          <span className="muted small">Dead level on projection.</span>
        ) : (
          <>
            <strong>{teamName(a.manager, a.rosterId)}</strong>
            <span className="muted small"> projected ahead by </span>
            <span className="mono">{pts(margin)}</span>
          </>
        )}
      </p>
    </div>
  )
}

export function MatchupsBlock({ block }: { block: AnalysisMatchups }) {
  if (!block.available) return <NotYet reason={block.reason} />
  return (
    <div className="analysis-games">
      {block.matchups.map((m) => (
        <GameCard key={m.matchupId} game={m} />
      ))}
    </div>
  )
}
