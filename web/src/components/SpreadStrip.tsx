import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { niceTicks } from './StatScatter'
import { SPREAD_MIN, quantiles } from '../statFormula'

/**
 * specs/032-stat-calculator US5 (FR-019): the calculator's Spread chart, a distribution strip.
 *
 * One dot per player along one value axis. A group is one strip; "split by position" passes one group per
 * position and they share the axis, so strips compare directly. The 25th, 50th and 75th percentiles are
 * drawn (a tinted band for the middle half, a longer tick for the median) and printed under each group's
 * name. The top N are the accent colour, the rest muted, and the legend says so in words, so colour is not
 * the only cue. Dots are jittered across the strip's height by a hash of the player id, so two loads draw
 * the same picture (SC-004); the jitter carries no meaning and the page says so. Like the scatter, the SVG
 * is as wide as its container (viewBox in pixels), each dot has a 24 px target, and the tooltip is clamped
 * inside the container so it can never widen the page. A table view sits underneath.
 *
 * Amended after US5 review: a strip with fewer than SPREAD_MIN players shows its dots and count but no quartiles
 * (C7). The dots are not tab stops (hundreds of them would stand between the keyboard and the rest of the page);
 * the table under the chart is the keyboard path to every value, and the tooltip is for pointer and touch (C10).
 * The tooltip is placed in the SVG's rendered pixels, so it stays inside a container narrower than the SVG's
 * minimum width, where the SVG scales down.
 */

export type SpreadPoint = { id: string; name: string | null; value: number }
export type SpreadGroup = { key: string; label: string; points: SpreadPoint[] }

const FALLBACK_W = 480
const MIN_W = 260
const MAX_W = 760
const M = { left: 14, right: 14 }
const R = 5
const HIT_R = 12
const TIP_W = 200
const HEAD = 34
const STRIP = 44
const GAP = 8
const AXIS_H = 28

/** A stable number in [0, 1) from a player id (FNV-1a), so the jitter is the same on every load. */
export function jitterOf(id: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < id.length; i++) {
    h ^= id.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return (h >>> 0) / 2 ** 32
}

export default function SpreadStrip({
  title, groups, top, format,
}: {
  title: string
  groups: SpreadGroup[]
  /** Ids of the current top N, drawn in the accent. */
  top: Set<string>
  format: (v: number) => string
}) {
  const box = useRef<HTMLDivElement>(null)
  const [w, setW] = useState(FALLBACK_W)
  /** The container's real width: below MIN_W the SVG is scaled down to it, and the tooltip must follow. */
  const [cw, setCw] = useState(FALLBACK_W)
  const [active, setActive] = useState<string | null>(null)

  useLayoutEffect(() => {
    const el = box.current
    if (!el) return
    const measure = () => {
      const cw = el.clientWidth
      if (cw > 0) {
        setW(Math.min(MAX_W, Math.max(MIN_W, cw)))
        setCw(cw)
      }
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

  const all = groups.flatMap((g) => g.points.map((p) => p.value))
  let lo = all.length ? Math.min(...all) : 0
  let hi = all.length ? Math.max(...all) : 1
  if (lo === hi) {
    lo -= 1
    hi += 1
  }
  const pad = (hi - lo) * 0.04
  const x0 = lo - pad
  const x1 = hi + pad
  const iw = w - M.left - M.right
  const sx = (v: number) => M.left + ((v - x0) / (x1 - x0)) * iw
  const ticks = niceTicks(x0, x1, w < 400 ? 4 : 6)
  const h = groups.length * (HEAD + STRIP + GAP) + AXIS_H
  const name = (p: SpreadPoint) => p.name ?? 'Unknown player'

  const hit = active
    ? (groups.flatMap((g, gi) => g.points.map((p) => ({ g, gi, p }))).find(({ g, p }) => `${g.key}:${p.id}` === active) ?? null)
    : null
  let tip: { left: number; top: number; below: boolean; width: number } | null = null
  if (hit) {
    const scale = Math.min(1, cw / w)
    const width = Math.max(40, Math.min(TIP_W, cw - 8))
    const py = (hit.gi * (HEAD + STRIP + GAP) + HEAD + STRIP / 2) * scale
    tip = { left: Math.max(width / 2 + 4, Math.min(cw - width / 2 - 4, sx(hit.p.value) * scale)), top: py, below: py < 70, width }
  }

  return (
    <figure className="sc-chart sc-spread">
      <figcaption className="sc-chart-title">{title}</figcaption>
      <ul className="sc-legend small" aria-label="Legend">
        <li><span className="sc-swatch sc-swatch-top" aria-hidden="true" />{`Top ${top.size} in the ranking`}</li>
        <li><span className="sc-swatch sc-swatch-rest" aria-hidden="true" />Everyone else</li>
        <li><span className="sc-swatch sc-swatch-band" aria-hidden="true" />Middle half (25th to 75th percentile)</li>
      </ul>
      <div className="sc-scatter-box" ref={box}>
        <svg
          viewBox={`0 0 ${w} ${h}`}
          width={w}
          height={h}
          role="group"
          aria-label={`${title}. ${groups.map((g) => `${g.label}: ${g.points.length} players`).join('; ')}.`}
          onClick={(e) => {
            if (e.target === e.currentTarget) setActive(null)
          }}
        >
          {ticks.map((v) => (
            <line key={`g${v}`} className="sc-grid" x1={sx(v)} x2={sx(v)} y1={0} y2={h - AXIS_H} />
          ))}
          {groups.map((g, gi) => {
            const q = quantiles(g.points.map((p) => p.value))
            const enough = q.n >= SPREAD_MIN
            const y0 = gi * (HEAD + STRIP + GAP)
            const mid = y0 + HEAD + STRIP / 2
            return (
              <g key={g.key} data-testid="spread-group">
                <text className="sc-axis-label" x={M.left} y={y0 + 12}>{`${g.label} · ${q.n} ${q.n === 1 ? 'player' : 'players'}`}</text>
                {q.n > 0 && (
                  <text className="sc-tick" x={M.left} y={y0 + 27}>
                    {enough ? `25th ${format(q.q1)} · median ${format(q.median)} · 75th ${format(q.q3)}` : 'too few players for quartiles'}
                  </text>
                )}
                {enough && (
                  <>
                    <rect className="sc-iqr" x={sx(q.q1)} width={Math.max(1, sx(q.q3) - sx(q.q1))} y={y0 + HEAD} height={STRIP} rx={4} />
                    <line className="sc-quart" x1={sx(q.q1)} x2={sx(q.q1)} y1={y0 + HEAD + 6} y2={y0 + HEAD + STRIP - 6} />
                    <line className="sc-quart" x1={sx(q.q3)} x2={sx(q.q3)} y1={y0 + HEAD + 6} y2={y0 + HEAD + STRIP - 6} />
                    <line className="sc-median" x1={sx(q.median)} x2={sx(q.median)} y1={y0 + HEAD} y2={y0 + HEAD + STRIP} />
                  </>
                )}
                {g.points.map((p) => {
                  const key = `${g.key}:${p.id}`
                  const cy = mid + (jitterOf(p.id) * 2 - 1) * (STRIP / 2 - R - 2)
                  const isTop = top.has(p.id)
                  return (
                    <g
                      key={key}
                      className={`sc-dot${key === active ? ' on' : ''}${isTop ? ' top' : ' rest'}`}
                      data-testid="spread-dot"
                      role="img"
                      aria-label={`${name(p)}: ${format(p.value)}${isTop ? `, in the top ${top.size}` : ''}`}
                      onPointerEnter={() => setActive(key)}
                      onPointerLeave={() => setActive((a) => (a === key ? null : a))}
                      onClick={() => setActive((a) => (a === key ? null : key))}
                    >
                      <circle className="sc-hit" cx={sx(p.value)} cy={cy} r={HIT_R} />
                      <circle className="sc-pt" cx={sx(p.value)} cy={cy} r={R} />
                    </g>
                  )
                })}
              </g>
            )
          })}
          <line className="sc-axis" x1={M.left} x2={w - M.right} y1={h - AXIS_H} y2={h - AXIS_H} />
          {ticks.map((v) => (
            <text key={`t${v}`} className="sc-tick" x={sx(v)} y={h - AXIS_H + 14} textAnchor="middle">{format(v)}</text>
          ))}
        </svg>
        {hit && tip && (
          <div
            className="sc-tip"
            role="status"
            style={{ left: tip.left, top: tip.top, width: tip.width, transform: tip.below ? 'translate(-50%, 14px)' : 'translate(-50%, calc(-100% - 12px))' }}
          >
            <strong>{name(hit.p)}</strong>
            <span>{format(hit.p.value)}</span>
          </div>
        )}
      </div>
      <p className="muted small sc-scatter-note">
        Each dot is one player. Left to right is the value; the up-and-down position inside a strip only keeps dots from overlapping and means nothing.
      </p>
      <details className="sc-scatter-table">
        <summary className="small">Show these values as a table</summary>
        <div className="sl-wrap">
          <table className="sl-table">
            <thead>
              <tr>
                <th scope="col">Player</th>
                {groups.length > 1 && <th scope="col">Position</th>}
                <th scope="col">Value</th>
              </tr>
            </thead>
            <tbody>
              {groups.flatMap((g) =>
                [...g.points].sort((a, b) => b.value - a.value || a.id.localeCompare(b.id)).map((p) => (
                  <tr key={`${g.key}:${p.id}`}>
                    <th scope="row">{name(p)}</th>
                    {groups.length > 1 && <td>{g.label}</td>}
                    <td>{format(p.value)}</td>
                  </tr>
                )),
              )}
            </tbody>
          </table>
        </div>
      </details>
    </figure>
  )
}
