import { useState } from 'react'
import type { LockBaselineFigure, LockBaselineKind, LockInReport, ProvableLock } from '../api'
import { signedPct } from './LockInPanel'

/*
 * spec 044 US2: one player's lock-ins in one league, on the player page's `lock-ins` tab.
 *
 * The report comes from `fetchLockIns(id, { player, live: false })`, which the server narrows to this player's summary
 * and locks (plan review F14). Same rules as the league panel: one baseline at a time and always named, the count
 * beside every median, "too few" below the minimum, and a plain sentence (not a 0%) when he has no provable lock.
 */

const KIND_LABEL: Record<LockBaselineKind, string> = {
  STAND_IN: 'Season-average stand-in',
  PROJECTION: 'Projection',
}

function marginOf(l: ProvableLock, kind: LockBaselineKind): number | null {
  return kind === 'STAND_IN' ? l.standIn.margin : (l.projection?.margin ?? null)
}

function baselineText(l: ProvableLock, kind: LockBaselineKind): string {
  if (kind === 'STAND_IN') return l.standIn.baseline != null ? l.standIn.baseline.toFixed(1) : 'none (too few games)'
  return l.projection ? l.projection.projectedPoints.toFixed(1) : 'none frozen'
}

function pointsText(l: ProvableLock, kind: LockBaselineKind): string {
  // A projection margin is graded on the restricted scoring map, so its "points" are that map's, not the credited value.
  return kind === 'STAND_IN' ? l.points.toFixed(1) : l.projection ? l.projection.actualRestricted.toFixed(1) : l.points.toFixed(1)
}

function MedianLine({ f, minLocks, kind }: { f: LockBaselineFigure; minLocks: number; kind: LockBaselineKind }) {
  return (
    <p className="pl-lockins-median">
      <span className="muted small">{KIND_LABEL[kind]}: </span>
      {f.median != null && f.q1 != null && f.q3 != null ? (
        <>
          <strong className="mono">{signedPct(f.median)}</strong> median over <span className="mono">{f.n}</span> locks{' '}
          <span className="muted small mono">(middle half {signedPct(f.q1)} to {signedPct(f.q3)})</span>
        </>
      ) : (
        <span className="muted mono" title={`A median needs at least ${minLocks} locks`}>
          {f.n} locks — too few for a median
        </span>
      )}
    </p>
  )
}

export function PlayerLockInsView({ r }: { r: LockInReport }) {
  const [kind, setKind] = useState<LockBaselineKind>('STAND_IN')
  const me = r.players[0]
  const locks = r.locks
  if (locks.length === 0 && (!me || (me.standIn.n === 0 && me.tieNonLast === 0))) {
    return (
      <section className="pp-card" aria-label="Lock-ins">
        <h3 className="pp-h">Lock-ins</h3>
        <p className="muted small">No provable locks for this player in this league this season.</p>
      </section>
    )
  }
  return (
    <section className="pp-card pl-lockins" aria-label="Lock-ins">
      <h3 className="pp-h">Lock-ins</h3>
      <p className="muted small">
        How far above his baseline each manager who started him locked his score. Locks are inferred from the credited points; a later game
        means a later game he actually played.
      </p>
      <div className="segmented sm" role="tablist" aria-label="Which baseline the margins are measured against">
        {(['STAND_IN', 'PROJECTION'] as const).map((k) => (
          <button key={k} type="button" role="tab" aria-selected={k === kind} className={`segment${k === kind ? ' on' : ''}`} onClick={() => setKind(k)}>
            {KIND_LABEL[k]}
          </button>
        ))}
      </div>
      {me && <MedianLine f={kind === 'STAND_IN' ? me.standIn : me.projection} minLocks={r.minLocks} kind={kind} />}
      {locks.length > 0 && (
        <div className="pl-lockins-wrap">
          <table className="pl-lockins-table">
            <thead>
              <tr>
                <th scope="col">Week</th>
                <th scope="col">Manager</th>
                <th scope="col">{kind === 'STAND_IN' ? 'Points' : 'Points (scored like the projection)'}</th>
                <th scope="col">{kind === 'STAND_IN' ? 'Season avg before' : 'Projected'}</th>
                <th scope="col">Margin</th>
                <th scope="col">Later games</th>
              </tr>
            </thead>
            <tbody>
              {locks.map((l) => {
                const m = marginOf(l, kind)
                return (
                  <tr key={`${l.week}-${l.rosterId}`}>
                    <td className="mono">
                      {l.week}
                      {l.playoff && <span className="pl-lockins-playoff"> playoffs</span>}
                    </td>
                    <td>{l.manager ?? `Roster ${l.rosterId}`}</td>
                    <td className="mono">{pointsText(l, kind)}</td>
                    <td className="mono">{baselineText(l, kind)}</td>
                    <td className="mono">{m != null ? <strong>{signedPct(m)}</strong> : <span className="muted">no margin</span>}</td>
                    <td className="mono">{l.laterGames}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      <ul className="muted small lockin-caveats">
        {r.caveats.map((c) => (
          <li key={c}>{c}</li>
        ))}
      </ul>
    </section>
  )
}
