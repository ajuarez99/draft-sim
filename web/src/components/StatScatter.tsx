import { useEffect, useLayoutEffect, useRef, useState } from 'react'

/**
 * specs/032-stat-calculator US4 (FR-018): the calculator's scatter, two stats against each other.
 *
 * An SVG whose viewBox is the container's measured width (1 unit = 1 px), so it fits a 375 px phone
 * and its labels keep their size. One series, one colour (the ranked chart's), so the dots mean one
 * thing; each dot carries the 2 px surface ring and a 24 px tap target. Exact values show in a tooltip
 * on hover, focus or tap; the tooltip is clamped inside the container, so it can never widen the page.
 * Rows missing either value are not plotted and their count is stated. The same points are in a
 * table under the chart, for readers who cannot use the plot.
 */

export type ScatterPoint = { id: string; name: string | null; x: number; y: number }

const FALLBACK_W = 480
const MIN_W = 260
const MAX_W = 760
const M = { top: 12, right: 16, bottom: 46, left: 58 }
const R = 5
/** A 24 px target (WCAG 2.5.8), centred on the dot. */
const HIT_R = 12
const TIP_W = 210

/** Round tick values spanning [lo, hi], about `n` of them. */
export function niceTicks(lo: number, hi: number, n = 5): number[] {
  if (!(hi > lo)) return [lo]
  const raw = (hi - lo) / Math.max(1, n - 1)
  const mag = 10 ** Math.floor(Math.log10(raw))
  const f = raw / mag
  const step = (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * mag
  const out: number[] = []
  for (let v = Math.ceil(lo / step) * step; v <= hi + step * 1e-9; v += step) out.push(Math.abs(v) < step * 1e-9 ? 0 : Number(v.toPrecision(12)))
  return out
}

/** A domain that holds every value with a little air; a single value gets a width of its own. */
function domain(values: number[]): [number, number] {
  let lo = Math.min(...values)
  let hi = Math.max(...values)
  if (lo === hi) {
    lo -= 1
    hi += 1
  }
  const pad = (hi - lo) * 0.06
  return [lo - pad, hi + pad]
}

export default function StatScatter({
  title, xLabel, yLabel, points, excluded, formatX, formatY,
}: {
  title: string
  /** Stat name and unit, e.g. "USG (percentage points)". */
  xLabel: string
  yLabel: string
  points: ScatterPoint[]
  /** Players left out because one of the two values is missing. */
  excluded: number
  formatX: (v: number) => string
  formatY: (v: number) => string
}) {
  const box = useRef<HTMLDivElement>(null)
  const [w, setW] = useState(FALLBACK_W)
  const [active, setActive] = useState<string | null>(null)

  useLayoutEffect(() => {
    const el = box.current
    if (!el) return
    const measure = () => {
      const cw = el.clientWidth
      if (cw > 0) setW(Math.min(MAX_W, Math.max(MIN_W, cw)))
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    if (!active) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setActive(null)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [active])

  const h = Math.round(Math.min(380, Math.max(240, w * 0.72)))
  const iw = w - M.left - M.right
  const ih = h - M.top - M.bottom
  const [x0, x1] = points.length ? domain(points.map((p) => p.x)) : [0, 1]
  const [y0, y1] = points.length ? domain(points.map((p) => p.y)) : [0, 1]
  const sx = (v: number) => M.left + ((v - x0) / (x1 - x0)) * iw
  const sy = (v: number) => M.top + ih - ((v - y0) / (y1 - y0)) * ih
  const xt = niceTicks(x0, x1, w < 400 ? 4 : 6)
  const yt = niceTicks(y0, y1, 5)
  const on = points.find((p) => p.id === active) ?? null
  const name = (p: ScatterPoint) => p.name ?? 'Unknown player'

  // The tooltip is centred on the dot but clamped to the container, above it unless that would leave the top.
  let tip: { left: number; top: number; below: boolean; width: number } | null = null
  if (on) {
    const width = Math.min(TIP_W, w - 8)
    const py = sy(on.y)
    tip = { left: Math.min(w - width / 2 - 4, Math.max(width / 2 + 4, sx(on.x))), top: py, below: py < 80, width }
  }

  return (
    <figure className="sc-chart sc-scatter">
      <figcaption className="sc-chart-title">{title}</figcaption>
      <div className="sc-scatter-box" ref={box}>
        <svg
          viewBox={`0 0 ${w} ${h}`}
          width={w}
          height={h}
          role="group"
          aria-label={`${title}. ${points.length} players plotted.`}
          onClick={(e) => {
            if (e.target === e.currentTarget) setActive(null)
          }}
        >
          {yt.map((v) => (
            <g key={`y${v}`}>
              <line className="sc-grid" x1={M.left} x2={w - M.right} y1={sy(v)} y2={sy(v)} />
              <text className="sc-tick" x={M.left - 6} y={sy(v)} textAnchor="end" dominantBaseline="middle">{formatY(v)}</text>
            </g>
          ))}
          {xt.map((v) => (
            <g key={`x${v}`}>
              <line className="sc-grid" x1={sx(v)} x2={sx(v)} y1={M.top} y2={M.top + ih} />
              <text className="sc-tick" x={sx(v)} y={M.top + ih + 14} textAnchor="middle">{formatX(v)}</text>
            </g>
          ))}
          <line className="sc-axis" x1={M.left} x2={w - M.right} y1={M.top + ih} y2={M.top + ih} />
          <line className="sc-axis" x1={M.left} x2={M.left} y1={M.top} y2={M.top + ih} />
          <text className="sc-axis-label" x={M.left + iw / 2} y={h - 6} textAnchor="middle">{xLabel}</text>
          <text className="sc-axis-label" transform={`translate(12 ${M.top + ih / 2}) rotate(-90)`} textAnchor="middle">{yLabel}</text>
          {points.map((p) => (
            <g
              key={p.id}
              className={`sc-dot${p.id === active ? ' on' : ''}`}
              data-testid="scatter-dot"
              tabIndex={0}
              role="img"
              aria-label={`${name(p)}: ${xLabel} ${formatX(p.x)}, ${yLabel} ${formatY(p.y)}`}
              onPointerEnter={() => setActive(p.id)}
              onPointerLeave={() => setActive((a) => (a === p.id ? null : a))}
              onFocus={() => setActive(p.id)}
              onBlur={() => setActive((a) => (a === p.id ? null : a))}
              onClick={() => setActive((a) => (a === p.id ? null : p.id))}
            >
              <circle className="sc-hit" cx={sx(p.x)} cy={sy(p.y)} r={HIT_R} />
              <circle className="sc-pt" cx={sx(p.x)} cy={sy(p.y)} r={R} />
            </g>
          ))}
        </svg>
        {on && tip && (
          <div
            className="sc-tip"
            role="status"
            style={{ left: tip.left, top: tip.top, width: tip.width, transform: tip.below ? 'translate(-50%, 14px)' : 'translate(-50%, calc(-100% - 12px))' }}
          >
            <strong>{name(on)}</strong>
            <span>{`${xLabel}: ${formatX(on.x)}`}</span>
            <span>{`${yLabel}: ${formatY(on.y)}`}</span>
          </div>
        )}
      </div>
      <p className="muted small sc-scatter-note">
        {`${points.length} ${points.length === 1 ? 'player' : 'players'} plotted.`}
        {excluded > 0 ? ` ${excluded} ${excluded === 1 ? 'player is' : 'players are'} not plotted because one of the two values is missing; none is drawn as 0.` : ''}
      </p>
      {points.length > 0 && (
        <details className="sc-scatter-table">
          <summary className="small">Show these points as a table</summary>
          <div className="sl-wrap">
            <table className="sl-table">
              <thead>
                <tr>
                  <th scope="col">Player</th>
                  <th scope="col">{xLabel}</th>
                  <th scope="col">{yLabel}</th>
                </tr>
              </thead>
              <tbody>
                {points.map((p) => (
                  <tr key={p.id}>
                    <th scope="row">{name(p)}</th>
                    <td>{formatX(p.x)}</td>
                    <td>{formatY(p.y)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}
    </figure>
  )
}
