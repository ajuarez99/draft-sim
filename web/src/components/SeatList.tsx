import type { Seat } from '../api'

/**
 * One card per seat. draftsObserved and picksScored are shown on every card on
 * purpose: a seat with 0 drafts observed is the league-average drafter wearing
 * someone's name, and the UI should not let that pass for a personality.
 */
export default function SeatList({ seats, mySlot }: { seats: Seat[]; mySlot: number }) {
  return (
    <section className="panel">
      <h2>Seats</h2>
      <div className="seats">
        {seats.map((s) => {
          const tilts = Object.entries(s.positionalTilt)
            .filter(([, v]) => Math.abs(v - 1) > 0.05)
            .sort((a, b) => b[1] - a[1])
            .slice(0, 2)
          return (
            <div key={s.slot} className={s.slot === mySlot ? 'seat me' : 'seat'}>
              <div className="seat-head">
                <span className="slot">{s.slot}</span>
                <span className="who">{s.manager}</span>
              </div>
              {s.picksScored === 0 ? (
                <p className="muted small">
                  No history. Running the league-average model.
                </p>
              ) : (
                <p className="small">
                  {s.reachBias > 0.5
                    ? `reaches ~${s.reachBias.toFixed(1)} picks early`
                    : s.reachBias < -0.5
                      ? `waits ~${Math.abs(s.reachBias).toFixed(1)} picks past board`
                      : 'drafts close to the board'}
                  {tilts.length > 0 && (
                    <>
                      {' · '}
                      {tilts.map(([pos, v]) => `${v > 1 ? 'leans' : 'fades'} ${pos}`).join(', ')}
                    </>
                  )}
                </p>
              )}
              <p className="muted tiny">
                {s.draftsObserved} draft{s.draftsObserved === 1 ? '' : 's'} observed ·{' '}
                {s.picksScored} pick{s.picksScored === 1 ? '' : 's'} scoreable
              </p>
            </div>
          )
        })}
      </div>
    </section>
  )
}
