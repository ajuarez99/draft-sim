import PlayerLink from './PlayerLink'
import type { StatLeaderboard as Board } from '../api'
import type { Part } from '../statFormula'

/**
 * specs/032-stat-calculator US5 (FR-019): the calculator's Breakdown chart, stacked contribution bars.
 *
 * Each bar is a player's result split into what each term of the formula adds. Positive parts stack right
 * from the zero line, negative parts stack left of it, so the parts add up to the total printed beside the
 * bar (the page tests this to 1e-9). A 1 px surface gap separates segments. At most MAX_NAMED terms get a
 * colour of their own (those that move the players shown the most); the rest fold into "other" in the muted
 * colour, and every part's exact value is in the segment's tooltip and the row's accessible name. The legend
 * names each colour, so identity is never colour alone. HTML rows, so it reflows at 375 px.
 */

export type BreakdownRow = { id: string; name: string | null; parts: Part[]; total: number }

export const MAX_NAMED = 5
/** Existing palette tokens; the sixth colour, for "other", is the muted ink. */
const PART_COLOURS = ['var(--teal)', 'var(--wr)', 'var(--te)', 'var(--fitted)', 'var(--qb)']
const OTHER = 'var(--muted)'

/** Which term indexes get a colour: those with the largest total |value| over `rows`, ties to the earlier term. */
export function namedTerms(rows: BreakdownRow[]): number[] {
  const n = rows[0]?.parts.length ?? 0
  const weight = Array.from({ length: n }, (_, i) => rows.reduce((a, r) => a + Math.abs(r.parts[i]?.value ?? 0), 0))
  return weight
    .map((w, i) => ({ w, i }))
    .sort((a, b) => b.w - a.w || a.i - b.i)
    .slice(0, Math.min(n, MAX_NAMED))
    .map((x) => x.i)
    .sort((a, b) => a - b)
}

export default function ContributionBars({
  title, rows, sport, leagueId, format,
}: {
  title: string
  rows: BreakdownRow[]
  sport: Board['sport']
  leagueId: string
  format: (v: number) => string
}) {
  // With exactly MAX_NAMED + 1 terms "other" would hold one term; that is still the rule (at most 6 colours).
  const named = namedTerms(rows)
  const terms = rows[0]?.parts ?? []
  const colourOf = (i: number): string => {
    const k = named.indexOf(i)
    return k >= 0 ? PART_COLOURS[k] : OTHER
  }
  const others = terms.filter((_, i) => !named.includes(i))
  // Scale: the widest negative stack to the widest positive stack across every row.
  const sums = rows.map((r) => ({
    neg: r.parts.reduce((a, p) => a + Math.min(0, p.value), 0),
    pos: r.parts.reduce((a, p) => a + Math.max(0, p.value), 0),
  }))
  const lo = Math.min(0, ...sums.map((s) => s.neg))
  const hi = Math.max(0, ...sums.map((s) => s.pos))
  const span = hi - lo || 1
  const zero = (-lo / span) * 100
  return (
    <figure className="sc-chart">
      <figcaption className="sc-chart-title">{title}</figcaption>
      <ul className="sc-legend small" aria-label="Legend">
        {named.map((i) => (
          <li key={i}><span className="sc-swatch" style={{ background: colourOf(i) }} aria-hidden="true" />{terms[i].text}</li>
        ))}
        {others.length > 0 && (
          <li>
            <span className="sc-swatch" style={{ background: OTHER }} aria-hidden="true" />
            {`Other: ${others.map((t) => t.text).join(', ')}`}
          </li>
        )}
      </ul>
      <ol className="sc-bars" aria-label={title}>
        {rows.map((r, ri) => {
          const name = r.name ?? 'Unknown player'
          let posAt = zero
          let negAt = zero
          const segs = r.parts.map((p, i) => {
            const wd = (Math.abs(p.value) / span) * 100
            const left = p.value >= 0 ? posAt : negAt - wd
            if (p.value >= 0) posAt += wd
            else negAt -= wd
            return { p, i, left, wd }
          })
          const detail = r.parts.map((p) => `${p.text} ${format(p.value)}`).join('; ')
          return (
            <li key={r.id} className="sc-bar-row" aria-label={`${name}: ${format(r.total)} = ${detail}`}>
              <span className="sc-bar-rank muted small" aria-hidden="true">{ri + 1}</span>
              <span className="sc-bar-name">
                <PlayerLink sleeperLeagueId={leagueId} sleeperPlayerId={r.id} sport={sport}>{name}</PlayerLink>
              </span>
              <span className="sc-bar-track">
                <span className="sc-bar-zero" style={{ left: `${zero}%` }} />
                {segs.map(({ p, i, left, wd }) => (
                  <span
                    key={i}
                    className="sc-seg"
                    data-testid="part"
                    title={`${name} · ${p.text}: ${format(p.value)}`}
                    style={{ left: `${left}%`, width: `${wd}%`, background: colourOf(i) }}
                  />
                ))}
              </span>
              <span className="sc-bar-val pp-strong">{format(r.total)}</span>
            </li>
          )
        })}
      </ol>
      <p className="muted small sc-scatter-note">Each bar adds up to the result printed beside it; parts that lower it sit left of the line. Hover a segment for its exact value.</p>
    </figure>
  )
}
