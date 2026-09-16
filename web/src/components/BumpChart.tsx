/**
 * The rank-by-week bump chart, extracted from PowerRankings.tsx so a second page
 * could draw one without a second implementation.
 *
 * Both League analysis briefs listed "a second bump chart" as a non-goal --
 * Power rankings already had one, and building it twice is the failure this
 * repo keeps re-learning. Moving the component here is how the second page
 * gets a bump chart while that non-goal still holds: one chart, several callers,
 * different series.
 *
 * It knows nothing about power rankings or projections. A caller hands it
 * ranks by week; what those ranks MEAN is the caller's business.
 *
 * <p><b>Colour changed in 001-readable-bump-chart, and it is worth knowing why.</b>
 * This used to stroke every series in its own hue. With fourteen rosters that
 * cannot work: measured on this app's panel surface, fourteen evenly-spaced
 * hues collapse to a worst-pair colour-blind delta-E of 0.5, and even eight
 * professionally tuned slots fail. Three simultaneous colours is the ceiling.
 * So identity moved to a label at the end of each line, and colour now marks
 * focus -- the reader, plus up to three rosters they pinned. `Series.hue`
 * survives for legend chips and avatars; it no longer draws the line. See
 * `web/src/managerColor.ts`.
 */

import { useMemo } from 'react'
import {
  NO_PINS,
  opacityFor,
  roleFor,
  strokeFor,
  widthFor,
  type ManagerColorRole,
  type PinSlots,
} from '../managerColor'

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
  /** Identity hue for legend chips and avatars. Does NOT stroke the line. */
  hue: number
  points: SeriesPoint[]
}

type Segment = { points: SeriesPoint[]; thin: boolean }

/**
 * Contiguous runs of equal coverage -- claude/league-suite.md AC7 (never draw a
 * week a mode does not have) plus the coverage split that feature added.
 * Unchanged by every move so far; PowerRankings.chart.test.ts covers it.
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

/**
 * How a chart spends colour. Required, and a discriminated union rather than an
 * optional flag, because the two answers are genuinely different charts and a
 * default would pick one silently.
 *
 * `focus` -- the series are ROSTERS. There are too many of them for hue to carry
 * identity, so lines recede to neutral and colour marks the reader plus up to
 * three pins.
 *
 * `series` -- the series are a small fixed set that is not a roster identity,
 * such as Power rankings' three ranking modes. Three series clear the all-pairs
 * colour gates comfortably, each hue already means something, and that chart's
 * own legend names them. Colour stays on the series.
 */
type BumpChartColoring =
  | {
      colorBy: 'focus'
      /**
       * Pinned rosters BY SLOT -- fixed length, `null` for a free slot, so a
       * roster keeps its colour for as long as it stays pinned. Required, not
       * optional-with-default: an omitted selection would assert "nothing is
       * pinned" as a rule while looking like a value, and this repo has shipped
       * that bug three times.
       */
      selection: PinSlots
      /** The reader's own roster, or null when signed out. Required, same reason. */
      meRosterId: number | null
      onToggle: (rosterId: number) => void
    }
  | { colorBy: 'series' }

type BumpChartProps = {
  series: Series[]
  weeks: number[]
  teamCount: number
  /** What one point means, for the hover readout. */
  scoreLabel?: string
} & BumpChartColoring

const nameOf = (s: Series) => s.manager ?? `roster ${s.rosterId}`

const noop = () => {}

/** Paint order: context underneath, then the reader, pinned lines on top. */
const DEPTH: Record<ManagerColorRole, number> = {
  context: 0,
  me: 1,
  'focus-3': 2,
  'focus-2': 3,
  'focus-1': 4,
}

export default function BumpChart(props: BumpChartProps) {
  const { series, weeks, teamCount, scoreLabel } = props
  const focused = props.colorBy === 'focus'
  const selection = props.colorBy === 'focus' ? props.selection : NO_PINS
  const meRosterId = props.colorBy === 'focus' ? props.meRosterId : null
  const onToggle = props.colorBy === 'focus' ? props.onToggle : noop

  const marginLeft = 36
  // Room for the end labels -- identity lives here now, not in the stroke.
  // `series` mode keeps the old margin: its own legend already names the lines.
  const marginRight = focused ? 104 : 20
  const marginTop = 16
  const marginBottom = 28
  const xStep = 56
  const yStep = 22
  /** Least vertical gap two end labels can sit at and still be read. */
  const labelGap = 11

  const minWeek = weeks.length > 0 ? weeks[0] : 1
  const maxWeek = weeks.length > 0 ? weeks[weeks.length - 1] : 1
  const weekSpan = Math.max(1, maxWeek - minWeek)
  const width = marginLeft + marginRight + weekSpan * xStep
  const plotHeight = Math.max(1, teamCount - 1) * yStep
  const height = marginTop + marginBottom + plotHeight

  const xOf = (week: number) => marginLeft + (week - minWeek) * xStep
  const yOf = (rank: number) => marginTop + (rank - 1) * yStep

  const roles = useMemo(
    () =>
      new Map(
        series.map((s) => [
          s.rosterId,
          focused ? roleFor(s.rosterId, selection, meRosterId) : ('context' as ManagerColorRole),
        ]),
      ),
    [series, selection, meRosterId, focused],
  )

  /** In `series` mode the hue still draws the line, exactly as it always did. */
  const inkFor = (s: Series, role: ManagerColorRole) =>
    focused ? strokeFor(role) : `oklch(70% 0.14 ${s.hue})`
  const weightFor = (role: ManagerColorRole) => (focused ? widthFor(role) : 1.75)
  const fadeFor = (role: ManagerColorRole) => (focused ? opacityFor(role) : 1)

  /**
   * Where each end label goes.
   *
   * Fourteen rosters put fourteen labels in one column, and at a 22px step the
   * ones that finish near each other overlap. So the labels are laid out rather
   * than just placed: sorted by the y they want, then pushed down until each
   * clears the one above by `labelGap`.
   *
   * If even that cannot fit them -- a league too deep for the plot -- only the
   * pinned rosters and the reader's own are labelled, and the rest fall back to
   * hover. Better to label some lines honestly than to stack fourteen names into
   * an unreadable smear.
   */
  const labels = useMemo(() => {
    if (!focused) return [] // that chart's own legend already names its three modes
    const wanted = series
      .map((s) => {
        const last = s.points[s.points.length - 1]
        return last == null
          ? null
          : { rosterId: s.rosterId, name: nameOf(s), week: last.week, y: yOf(last.rank) }
      })
      .filter((v): v is NonNullable<typeof v> => v != null)
      .sort((a, b) => a.y - b.y)

    const dense = wanted.length * labelGap > plotHeight + labelGap
    const shown = dense
      ? wanted.filter((l) => roles.get(l.rosterId) !== 'context')
      : wanted

    let lastY = -Infinity
    return shown.map((l) => {
      const y = Math.max(l.y, lastY + labelGap)
      lastY = y
      return { ...l, y }
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [series, roles, plotHeight, focused])

  const ordered = useMemo(
    () =>
      [...series].sort(
        (a, b) => DEPTH[roles.get(a.rosterId)!] - DEPTH[roles.get(b.rosterId)!],
      ),
    [series, roles],
  )

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

        {ordered.map((s) => {
          const role = roles.get(s.rosterId)!
          const stroke = inkFor(s, role)
          const strokeWidth = weightFor(role)
          const segments = segmentsOf(s.points)
          const last = s.points[s.points.length - 1]

          return (
            <g
              key={s.rosterId}
              data-roster-id={s.rosterId}
              data-role={role}
              className={`bump-series bump-series-${role}`}
              opacity={fadeFor(role)}
              onClick={() => onToggle(s.rosterId)}
            >
              <title>
                {nameOf(s)} · week {last?.week ?? '–'} · {scoreLabel ?? 'rank'} {last?.rank ?? '–'}
                {last?.score != null ? ` · ${last.score.toFixed(2)}` : ''}
                {last?.note ? ` · ${last.note}` : ''}
                {last?.thin ? ' · thin coverage' : ''}
              </title>

              {segments.map((seg, i) =>
                seg.points.length === 1 ? (
                  <circle
                    key={`seg-${i}`}
                    cx={xOf(seg.points[0].week)}
                    cy={yOf(seg.points[0].rank)}
                    r={focused && role !== 'context' ? 7 : 6}
                    fill="none"
                    stroke={stroke}
                    strokeWidth={1.25}
                    strokeDasharray={seg.thin ? '2 2' : undefined}
                  />
                ) : (
                  <polyline
                    key={`seg-${i}`}
                    points={seg.points.map((p) => `${xOf(p.week)},${yOf(p.rank)}`).join(' ')}
                    fill="none"
                    stroke={stroke}
                    strokeWidth={strokeWidth}
                    strokeDasharray={seg.thin ? '4 3' : undefined}
                  />
                ),
              )}

              {/* A transparent fat stroke so the hit target is the line, not the
                  1.25px of ink drawn on it. */}
              {segments
                .filter((seg) => seg.points.length > 1)
                .map((seg, i) => (
                  <polyline
                    key={`hit-${i}`}
                    className="bump-hit"
                    points={seg.points.map((p) => `${xOf(p.week)},${yOf(p.rank)}`).join(' ')}
                    fill="none"
                    stroke="transparent"
                    strokeWidth={12}
                  />
                ))}

              {s.points.map((p) => (
                <circle
                  key={`pt-${p.week}`}
                  cx={xOf(p.week)}
                  cy={yOf(p.rank)}
                  r={focused ? (role === 'context' ? 2.5 : 4) : 3}
                  fill={stroke}
                >
                  <title>
                    {nameOf(s)} · week {p.week} · {scoreLabel ?? 'rank'} {p.rank}
                    {p.score != null ? ` · ${p.score.toFixed(2)}` : ''}
                    {p.note ? ` · ${p.note}` : ''}
                    {p.thin ? ' · thin coverage' : ''}
                  </title>
                </circle>
              ))}
            </g>
          )
        })}

        {labels.map((l) => {
          const role = roles.get(l.rosterId)!
          return (
            <text
              key={`label-${l.rosterId}`}
              className={`bump-end-label bump-end-label-${role}`}
              data-label-for={l.rosterId}
              x={xOf(l.week) + 8}
              y={l.y}
              dominantBaseline="middle"
              onClick={() => onToggle(l.rosterId)}
            >
              {l.name}
            </text>
          )
        })}
      </svg>
    </div>
  )
}
