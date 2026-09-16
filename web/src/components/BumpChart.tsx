/**
 * The rank-by-week bump chart, extracted from PowerRankings.tsx unchanged so a
 * second page can draw one without a second implementation.
 *
 * Both League analysis briefs listed "a second bump chart" as a non-goal --
 * Power rankings already had one, and building it twice is the failure this
 * repo keeps re-learning. Moving the component here is how the second page
 * gets a bump chart while that non-goal still holds: one chart, two callers,
 * two different series.
 *
 * It knows nothing about power rankings or projections. A caller hands it
 * ranks by week and a hue per roster; what those ranks MEAN is the caller's
 * business -- Power rankings feeds its composite ladder, League analysis feeds
 * what each roster actually scored.
 */

export type SeriesPoint = {
  week: number
  rank: number
  score: number | null
  note: string | null
  ballotCount: number | null
  /** Drawn dashed: this point rests on thinner evidence than its neighbours. */
  thin: boolean
}

export type Series = {
  rosterId: number
  managerId: number | null
  manager: string | null
  hue: number
  points: SeriesPoint[]
}

type Segment = { points: SeriesPoint[]; thin: boolean }

/**
 * Contiguous runs of equal coverage -- claude/league-suite.md AC7 (never draw a
 * week a mode does not have) plus the coverage split that feature added.
 * Unchanged by either move; PowerRankings.chart.test.ts covers it.
 */
export function segmentsOf(points: SeriesPoint[]): Segment[] {
  const segments: Segment[] = []
  let current: SeriesPoint[] = []
  for (const p of points) {
    const prev = current[current.length - 1]
    if (prev) {
      if (p.week !== prev.week + 1) {
        segments.push({ points: current, thin: prev.thin })
        current = []
      } else if (p.thin !== prev.thin) {
        segments.push({ points: current, thin: prev.thin })
        current = [prev] // carried into the next segment, so the boundary is drawn
      }
    }
    current.push(p)
  }
  if (current.length > 0) segments.push({ points: current, thin: current[current.length - 1].thin })
  return segments
}

type BumpChartProps = {
  series: Series[]
  weeks: number[]
  teamCount: number
  highlighted: number | null
  onHighlight: (rosterId: number | null) => void
  /** What one point means, for the hover title: "rank" on both callers today. */
  scoreLabel?: string
}

export default function BumpChart({
  series,
  weeks,
  teamCount,
  highlighted,
  onHighlight,
  scoreLabel,
}: BumpChartProps) {
  const marginLeft = 36
  const marginRight = 20
  const marginTop = 16
  const marginBottom = 28
  const xStep = 56
  const yStep = 22

  const minWeek = weeks.length > 0 ? weeks[0] : 1
  const maxWeek = weeks.length > 0 ? weeks[weeks.length - 1] : 1
  const weekSpan = Math.max(1, maxWeek - minWeek)
  const width = marginLeft + marginRight + weekSpan * xStep
  const height = marginTop + marginBottom + Math.max(1, teamCount - 1) * yStep

  const xOf = (week: number) => marginLeft + (week - minWeek) * xStep
  const yOf = (rank: number) => marginTop + (rank - 1) * yStep

  return (
    <div className="table-wrap">
      <svg viewBox={`0 0 ${width} ${height}`} width={width} height={height} className="bump-chart">
        {Array.from({ length: teamCount }, (_, i) => i + 1).map((rank) => (
          <g key={`rank-${rank}`}>
            <line x1={marginLeft} y1={yOf(rank)} x2={width - marginRight} y2={yOf(rank)} className="bump-gridline" />
            <text x={marginLeft - 10} y={yOf(rank)} className="bump-rank-label mono" textAnchor="end" dominantBaseline="middle">
              {rank}
            </text>
          </g>
        ))}
        {weeks.map((w) => (
          <text key={`week-${w}`} x={xOf(w)} y={height - marginBottom + 18} className="bump-week-label mono" textAnchor="middle">
            {w}
          </text>
        ))}

        {series.map((s) => {
          const hue = s.hue
          const isHighlighted = highlighted === s.rosterId
          const dimmed = highlighted != null && !isHighlighted
          return (
            <g
              key={s.rosterId}
              className={`bump-series${dimmed ? ' dimmed' : ''}`}
              onClick={() => onHighlight(isHighlighted ? null : s.rosterId)}
            >
              {segmentsOf(s.points).map((seg, i) =>
                seg.points.length === 1 ? (
                  <circle
                    key={`seg-${i}`}
                    cx={xOf(seg.points[0].week)}
                    cy={yOf(seg.points[0].rank)}
                    r={isHighlighted ? 7 : 6}
                    fill="none"
                    stroke={`oklch(70% 0.14 ${hue})`}
                    strokeWidth={1.25}
                    strokeDasharray={seg.thin ? '2 2' : undefined}
                  />
                ) : (
                  <polyline
                    key={`seg-${i}`}
                    points={seg.points.map((p) => `${xOf(p.week)},${yOf(p.rank)}`).join(' ')}
                    fill="none"
                    stroke={`oklch(70% 0.14 ${hue})`}
                    strokeWidth={isHighlighted ? 3 : 1.75}
                    strokeDasharray={seg.thin ? '4 3' : undefined}
                  />
                ),
              )}
              {s.points.map((p) => (
                <circle key={`pt-${p.week}`} cx={xOf(p.week)} cy={yOf(p.rank)} r={isHighlighted ? 4 : 3} fill={`oklch(70% 0.14 ${hue})`}>
                  <title>
                    {s.manager ?? `roster ${s.rosterId}`} · week {p.week} · {scoreLabel ?? 'rank'} {p.rank}
                    {p.score != null ? ` · ${p.score.toFixed(2)}` : ''}
                    {p.note ? ` · ${p.note}` : ''}
                    {p.thin ? ' · thin coverage' : ''}
                  </title>
                </circle>
              ))}
            </g>
          )
        })}
      </svg>
    </div>
  )
}
