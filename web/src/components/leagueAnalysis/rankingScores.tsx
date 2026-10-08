import { type AnalysisRankingScores } from '../../api'
import HowThisWorks from '../../components/HowThisWorks'
import GradeChip, { GradesEarlyBadge, gradesEarlySentence } from '../../components/GradeChip'
import { extremes } from '../../extremes'
import { FormulaNote, HeatCell, ManagerLink, NotYet, heatKind, pts } from './shared'

/**
 * The ranking score's methodology, folded away under the table: what the
 * number is, the raw figures it was scaled from (they used to sit under each
 * score; the scaling is ours, not ffwrapped's, so they are still shown), the
 * formula, and when the ranking appears at all.
 */
export function RankingScoreHow({ block }: { block: AnalysisRankingScores }) {
  return (
    <HowThisWorks>
      <p>
        A composite of scoring and record over the {block.weeksScored} week
        {block.weeksScored === 1 ? '' : 's'} played, scaled 1–100 with the league average at 50. It
        is built from games already played, so it describes the season so far, not who is best from
        here; the ladders, the vote and the bump chart are on Power rankings.
      </p>
      <p>
        Rankings appear after {block.weeksRequired} scored weeks. Before that, high and low are
        the same number or close to it, and the score would mean nothing.
      </p>
      <p>
        The letter beside each score is that team&apos;s rank in the league, from cutoffs set by
        hand rather than fitted to anything. {gradesEarlySentence(block.earlyThresholdWeeks)} The
        ranking itself appears once {block.weeksRequired} weeks are scored.
      </p>
      {block.available && (
        <p>
          Raw formula output, before it is scaled to 1–100:{' '}
          {block.entries.map((e, i) => (
            <span key={e.rosterId}>
              {i > 0 ? ' · ' : ''}
              {e.manager ?? `roster ${e.rosterId}`} {e.raw.toFixed(1)}
            </span>
          ))}
          .
        </p>
      )}
      <FormulaNote formula={block.formula} />
    </HowThisWorks>
  )
}

export function RankingScoresBlock({ block }: { block: AnalysisRankingScores }) {
  if (!block.available) {
    return (
      <>
        <NotYet reason={block.reason} />
        <RankingScoreHow block={block} />
      </>
    )
  }
  const highs = extremes(block.entries.map((e) => e.high), true)
  const lows = extremes(block.entries.map((e) => e.low), true)
  return (
    <>
      <div className="table-wrap">
        <table className="standings analysis-scores">
          <thead>
            <tr>
              <th className="mono">#</th>
              <th>Manager</th>
              <th className="mono">
                Score <GradesEarlyBadge early={block.gradesEarly} />
              </th>
              <th className="mono">Record</th>
              <th className="mono">Avg</th>
              <th className="mono">High</th>
              <th className="mono">Low</th>
            </tr>
          </thead>
          <tbody>
            {block.entries.map((e) => (
              <tr key={e.rosterId}>
                <td className="mono analysis-rank">{e.rank}</td>
                <td>
                  <ManagerLink
                    managerId={e.managerId}
                    manager={e.manager}
                    rosterId={e.rosterId}
                    avatarId={e.avatarId}
                  />
                </td>
                {/* The 1-100 number. The formula's own raw output, which it was
                    scaled FROM, is listed under "How this works" below the
                    table rather than under every score. */}
                <td className="mono">
                  <GradeChip grade={e.grade}>
                    <span className="analysis-score-pill">{e.score.toFixed(1)}</span>
                  </GradeChip>
                </td>
                <td className="mono">
                  {e.wins}-{e.losses}
                  {e.ties > 0 ? `-${e.ties}` : ''}
                </td>
                <td className="mono">{pts(e.avgWeekly)}</td>
                <td className="mono">
                  <HeatCell value={e.high} kind={heatKind(e.high, highs)} />
                </td>
                <td className="mono">
                  <HeatCell value={e.low} kind={heatKind(e.low, lows)} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <RankingScoreHow block={block} />
    </>
  )
}
