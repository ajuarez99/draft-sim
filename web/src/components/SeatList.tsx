import type { Seat, Provenance } from '../api'

/**
 * One card per seat.
 *
 * The card branches on PROVENANCE, not on pick counts. A seat you configured by
 * hand has no history and must not be described as the league-average drafter --
 * that was a real bug, and it quietly presented your input as an absence of input.
 * Equally, a stated tendency must not be dressed up as evidence.
 */
// Neutral seats get no badge on purpose. Eleven of fourteen cards carrying an
// identical "league average" chip is noise, and it buries the three that matter.
const LABEL: Record<Provenance, { badge: string | null; className: string }> = {
  NEUTRAL: { badge: null, className: 'neutral' },
  STATED: { badge: 'your call', className: 'stated' },
  FITTED: { badge: 'from history', className: 'fitted' },
  BLENDED: { badge: 'both', className: 'fitted' },
}

function behaviour(s: Seat) {
  const bits: string[] = []
  if (s.reachBias > 0.5) bits.push(`reaches ~${s.reachBias.toFixed(1)} picks early`)
  else if (s.reachBias < -0.5) bits.push(`waits ~${Math.abs(s.reachBias).toFixed(1)} picks past board`)
  else bits.push('drafts close to the board')

  if (s.unpredictability >= 1.25) bits.push('erratic')
  else if (s.unpredictability <= 0.8) bits.push('very predictable')

  const tilts = Object.entries(s.positionalTilt)
    .filter(([, v]) => Math.abs(v - 1) > 0.05)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 2)
    .map(([pos, v]) => `${v > 1 ? 'leans' : 'fades'} ${pos}`)

  return [...bits, ...tilts].join(' · ')
}

function footnote(s: Seat) {
  switch (s.provenance) {
    case 'NEUTRAL':
      return null   // the body line already says it; saying it twice is worse than once
    case 'STATED':
      return 'What you entered. No history to check it against.'
    case 'FITTED':
      return `${s.draftsObserved} draft${s.draftsObserved === 1 ? '' : 's'} observed · ${s.picksScored} picks scoreable`
    case 'BLENDED':
      return `Your input, pulled toward ${s.draftsObserved} draft${s.draftsObserved === 1 ? '' : 's'} of history (${s.picksScored} picks)`
  }
}

export default function SeatList({ seats, mySlot }: { seats: Seat[]; mySlot: number }) {
  return (
    <section className="panel">
      <h2>Seats</h2>
      <div className="seats">
        {seats.map((s) => {
          const label = LABEL[s.provenance]
          return (
            <div key={s.slot} className={`seat ${label.className}${s.slot === mySlot ? ' me' : ''}`}>
              <div className="seat-head">
                <span className="slot">{s.slot}</span>
                <span className="who">{s.manager}</span>
                {label.badge && <span className={`prov ${label.className}`}>{label.badge}</span>}
              </div>

              {s.provenance === 'NEUTRAL' ? (
                <p className="muted small">Drafts like the room — nothing entered for this seat.</p>
              ) : (
                <p className="small">{behaviour(s)}</p>
              )}

              {s.note && <p className="note small">“{s.note}”</p>}
              {footnote(s) && <p className="muted tiny">{footnote(s)}</p>}
            </div>
          )
        })}
      </div>
    </section>
  )
}
