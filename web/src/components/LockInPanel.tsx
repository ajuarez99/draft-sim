import { useEffect, useState } from 'react'
import { ApiError } from '../apiError'
import {
  fetchLockIns,
  type LiveCall,
  type LiveCalls,
  type LockBaselineFigure,
  type LockBaselineKind,
  type LockInReport,
  type LockInThresholds,
  type LockPayoffCell,
  type LockSummary,
} from '../api'
import Avatar from './Avatar'

/*
 * spec 044 US1: how far above his baseline each manager locked a starter's score.
 *
 * Rules this panel keeps (the spec's FR-004 / FR-010 and the repo's "label the axis, spell out the number"):
 *  - one baseline at a time, and the toggle switches EVERY block. A stand-in margin and a projection margin never
 *    share a column, a bar or a sentence (the stand-in is the player's own season average, not a projection);
 *  - the IQR bar always has its exact q1 and q3 printed beside it, and the axis is named;
 *  - a row below the server's minimum prints its count and "too few", never a median it can't stand behind;
 *  - what was left out (no baseline, ties of unknown game) comes from the payload's counts, and so do the caveats.
 *
 * US2-US4 add (all from the same payload): a Players block (sortable, too-few rows last), the evidence grid of when
 * locking paid off, the learned Lock/Wait thresholds, and this week's live calls. The live calls carry their own
 * baseline label (season average, or last season's) because they are made before any projection is frozen.
 */

const KIND_LABEL: Record<LockBaselineKind, string> = {
  STAND_IN: 'Season-average stand-in',
  PROJECTION: 'Projection',
}

/** +47% / −8% / 0%; whole percent, a real minus sign. */
export function signedPct(margin: number): string {
  const p = Math.round(margin * 100)
  if (p === 0) return '0%'
  return `${p > 0 ? '+' : '−'}${Math.abs(p)}%`
}

function figureOf(m: LockSummary, kind: LockBaselineKind): LockBaselineFigure {
  return kind === 'STAND_IN' ? m.standIn : m.projection
}

/** Median high to low, rows without one last, then more locks first. */
function ordered(rows: LockSummary[], kind: LockBaselineKind): LockSummary[] {
  return [...rows].sort((a, b) => {
    const fa = figureOf(a, kind)
    const fb = figureOf(b, kind)
    if (fa.median == null && fb.median != null) return 1
    if (fb.median == null && fa.median != null) return -1
    if (fa.median != null && fb.median != null && fa.median !== fb.median) return fb.median - fa.median
    return fb.n - fa.n || a.key.localeCompare(b.key)
  })
}

/** One shared axis for every bar in the block, so the bars compare. */
function axisOf(rows: LockSummary[], kind: LockBaselineKind): { lo: number; hi: number } {
  let lo = 0
  let hi = 0
  for (const r of rows) {
    const f = figureOf(r, kind)
    if (f.q1 != null) lo = Math.min(lo, f.q1)
    if (f.q3 != null) hi = Math.max(hi, f.q3)
  }
  return { lo, hi: hi > lo ? hi : lo + 1 }
}

function IqrBar({ f, lo, hi }: { f: LockBaselineFigure; lo: number; hi: number }) {
  if (f.q1 == null || f.q3 == null || f.median == null) return null
  const span = hi - lo
  const left = ((f.q1 - lo) / span) * 100
  const width = Math.max(((f.q3 - f.q1) / span) * 100, 1)
  const mid = ((f.median - lo) / span) * 100
  return (
    <span className="lockin-bar" aria-hidden="true">
      <span className="lockin-bar-zero" style={{ left: `${((0 - lo) / span) * 100}%` }} />
      <span className="lockin-bar-iqr" style={{ left: `${left}%`, width: `${width}%` }} />
      <span className="lockin-bar-median" style={{ left: `${mid}%` }} />
    </span>
  )
}

/** Players: by median or by lock count, and a row below the minimum always after the rows above it (US2). */
export type PlayerSort = 'median' | 'n'

export function orderedPlayers(rows: LockSummary[], kind: LockBaselineKind, by: PlayerSort): LockSummary[] {
  const enough = (r: LockSummary) => figureOf(r, kind).median != null
  return [...rows].sort((a, b) => {
    if (enough(a) !== enough(b)) return enough(a) ? -1 : 1
    const fa = figureOf(a, kind)
    const fb = figureOf(b, kind)
    if (by === 'median' && fa.median != null && fb.median != null && fa.median !== fb.median) return fb.median - fa.median
    return fb.n - fa.n || (a.name ?? a.key).localeCompare(b.name ?? b.key)
  })
}

const PLAYERS_SHOWN = 25

function PlayerRow({ p, kind, minLocks }: { p: LockSummary; kind: LockBaselineKind; minLocks: number }) {
  const f = figureOf(p, kind)
  const name = p.name ?? `Player ${p.key}`
  const enough = f.median != null && f.q1 != null && f.q3 != null
  return (
    <li className="lockin-prow" aria-label={name}>
      <span className="lockin-name">{name}</span>
      {enough ? (
        <>
          <span className="lockin-median mono" title="Median margin above the baseline">
            <strong>{signedPct(f.median as number)}</strong>
          </span>
          <span className="lockin-iqr-text mono small">
            middle half {signedPct(f.q1 as number)} to {signedPct(f.q3 as number)}
          </span>
        </>
      ) : (
        <span className="lockin-toofew2 muted mono" title={`A median needs at least ${minLocks} locks`}>
          {f.n} locks — too few
        </span>
      )}
      <span className="lockin-pill mono small" title="Locks with a margin against this baseline">{f.n} locks</span>
    </li>
  )
}

function PlayersBlock({ r, kind }: { r: LockInReport; kind: LockBaselineKind }) {
  const [by, setBy] = useState<PlayerSort>('median')
  const [all, setAll] = useState(false)
  if (r.players.length === 0) return null
  const rows = orderedPlayers(r.players, kind, by)
  const shown = all ? rows : rows.slice(0, PLAYERS_SHOWN)
  return (
    <section aria-label="Players">
      <h3 className="lockin-h">Players</h3>
      <p className="muted small lockin-axis">
        Every manager's locks of each player, pooled. Players with fewer than {r.minLocks} locks print their count and sort after the rest.
      </p>
      <div className="lockin-sort" role="group" aria-label="Sort players by">
        {([['median', 'Median margin'], ['n', 'Lock count']] as const).map(([k, label]) => (
          <button key={k} type="button" className={`segment${by === k ? ' on' : ''}`} aria-pressed={by === k} onClick={() => setBy(k)}>
            {label}
          </button>
        ))}
      </div>
      <ul className="lockin-list">
        {shown.map((p) => (
          <PlayerRow key={p.key} p={p} kind={kind} minLocks={r.minLocks} />
        ))}
      </ul>
      {rows.length > PLAYERS_SHOWN && (
        <button type="button" className="action-button lockin-more" onClick={() => setAll((v) => !v)}>
          {all ? `Show the first ${PLAYERS_SHOWN}` : `Show all ${rows.length} players`}
        </button>
      )}
    </section>
  )
}

/** "When locking paid off": band x games-left, from this league's past weeks (US3). */
export function PayoffGrid({ r, kind }: { r: LockInReport; kind: LockBaselineKind }) {
  const cells = r.payoff[kind] ?? []
  if (cells.length === 0) return null
  const bands = [...new Set(cells.map((c) => c.band))]
  const cols = [...new Set(cells.map((c) => c.gamesLeft))].sort((a, b) => a - b)
  const at = (band: string, gl: number): LockPayoffCell | undefined => cells.find((c) => c.band === band && c.gamesLeft === gl)
  const colLabel = (gl: number) => `${gl}${gl === r.gamesLeftCap ? '+' : ''} later ${gl === 1 ? 'game' : 'games'}`
  return (
    <section aria-label="When locking paid off">
      <h3 className="lockin-h">When locking paid off</h3>
      <p className="muted small lockin-axis">
        Rows are how far above the {kind === 'STAND_IN' ? 'season average' : 'projection'} the locked game was; columns are how many
        more games the player played that week. Each cell: how often riding it out (the week's last game) scored more than the lock.
      </p>
      <div className="lockin-grid-wrap">
        <table className="lockin-grid">
          <thead>
            <tr>
              <th scope="col">Locked at</th>
              {cols.map((gl) => (
                <th key={gl} scope="col">{colLabel(gl)}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {bands.map((band) => (
              <tr key={band}>
                <th scope="row" className="mono">{band === '<0' ? (kind === 'STAND_IN' ? 'below average' : 'below projection') : bandLabel(band)}</th>
                {cols.map((gl) => {
                  const c = at(band, gl)
                  if (!c) return <td key={gl} />
                  return (
                    <td key={gl} data-testid={`cell-${band}-${gl}`}>
                      {c.rateLast == null || c.rateAny == null ? (
                        <span className="muted small mono">{c.n} locks — too few</span>
                      ) : (
                        <>
                          <span className="lockin-cell-main mono">
                            riding it out beat it <strong>{Math.round(c.rateLast * 100)}%</strong> ({c.n})
                          </span>
                          <span className="lockin-cell-sub muted small mono">in hindsight {Math.round(c.rateAny * 100)}%</span>
                        </>
                      )}
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted small lockin-axis">
        This describes this league's past weeks; it is not a prediction. A later game means a game the player actually played.
        "In hindsight" is any later game scoring more, which nobody could pick in advance.
      </p>
    </section>
  )
}

/** "0-10" -> "0–10%", "50+" -> "50%+" (the server's labels carry no unit). */
export function bandLabel(band: string): string {
  return band.endsWith('+') ? `${band.slice(0, -1)}%+` : `${band.replace('-', '–')}%`
}

const thresholdLabel = (gl: number, cap: number) => `${gl}${gl === cap ? '+' : ''} left`

/** The learned Lock/Wait bar for each games-left count, with the seasons and sample it came from (US4). */
export function ThresholdStrip({ t, kind }: { t: LockInThresholds; kind?: LockBaselineKind }) {
  if (t.status !== 'OK') {
    return (
      <p className="muted small lockin-axis">
        No completed lock-in season in this league's history to learn thresholds from, so no Lock/Wait calls can be made.
      </p>
    )
  }
  const cap = t.byGamesLeft.length
  const n = t.byGamesLeft[0]?.n ?? 0
  return (
    <div className="lockin-thresholds" aria-label="Lock thresholds">
      <h3 className="lockin-h">Lock thresholds</h3>
      <ul className="lockin-thr-list">
        {t.byGamesLeft.map((p) => (
          <li key={p.gamesLeft} className="lockin-thr">
            <span className="muted small">{thresholdLabel(p.gamesLeft, cap)}</span>
            <strong className="mono">{signedPct(p.threshold)}</strong>
          </li>
        ))}
      </ul>
      <p className="muted small">
        Lock a game at or above the bar for the games still to come. Learned from <span className="mono">{n.toLocaleString('en-US')}</span> starter
        games in {t.from.join(' + ')}
        {t.pMissed != null && (
          <>
            ; the last game was missed (scored 0) <span className="mono">{(t.pMissed * 100).toFixed(1)}%</span> of the time
          </>
        )}
        .
      </p>
      {kind === 'PROJECTION' && (
        <p className="muted small">Thresholds use the season-average stand-in, whichever baseline the tables below are showing.</p>
      )}
    </div>
  )
}

export const BASELINE_NAME: Record<string, string> = { STAND_IN: 'season avg', LAST_SEASON: "last season's avg" }

function CallRow({ c }: { c: LiveCall }) {
  const name = c.player ?? `Player ${c.playerId}`
  const pill = c.call === 'LOCK' ? 'Lock' : c.call === 'WAIT' ? 'Wait' : 'Too early'
  return (
    <li className="lockin-call" aria-label={name}>
      <span className="lockin-call-who">
        <strong>{name}</strong>
        <span className="muted small">{[c.team, c.manager].filter(Boolean).join(' · ')}</span>
      </span>
      <span className="lockin-call-line mono small">
        {c.latestGame.points.toFixed(1)} pts
        {c.margin != null && c.baseline != null && c.baselineKind != null ? (
          <>
            {' '}
            · <strong>{signedPct(c.margin)}</strong> vs {BASELINE_NAME[c.baselineKind] ?? c.baselineKind} {c.baseline.toFixed(1)}
          </>
        ) : (
          <> · too early to call (no earlier games to compare)</>
        )}
      </span>
      <span className="lockin-call-left mono small">
        {c.gamesLeft != null && `${c.gamesLeft} left`}
        {c.threshold != null && ` · bar ${signedPct(c.threshold)}`}
      </span>
      <span className={`lockin-call-pill lockin-call-${c.call.toLowerCase()}`}>{pill}</span>
    </li>
  )
}

export function LiveCallsBlock({ live, thresholds }: { live: LiveCalls; thresholds: LockInThresholds | null }) {
  const n = thresholds?.byGamesLeft[0]?.n
  return (
    <section aria-label="This week's calls">
      <h3 className="lockin-h">This week's calls</h3>
      {live.status === 'NO_HISTORY' && (
        <p className="muted small">No completed lock-in season in this league's history to learn from, so no calls are made.</p>
      )}
      {live.status === 'ROSTERS_FAILED' && <p className="muted small">The current lineups couldn't be read from Sleeper just now.</p>}
      {live.status === 'OK' && live.calls.length === 0 && (
        <p className="muted small">No starter has played this week with games still to come.</p>
      )}
      {live.status === 'OK' && live.calls.length > 0 && (
        <>
          <p className="muted small lockin-axis">
            Each starter's most recent game this week, against the bar for the games he has left. Only that latest game can still be locked.
          </p>
          <ul className="lockin-calls">
            {live.calls.map((c) => (
              <CallRow key={`${c.rosterId}-${c.playerId}`} c={c} />
            ))}
          </ul>
        </>
      )}
      {live.status === 'OK' && (
        <p className="muted small lockin-axis">
          A rule learned from past weeks{n != null ? ` (${n.toLocaleString('en-US')} starter games)` : ''}. It doesn't know about injuries or rest days,
          and it isn't a guarantee.
        </p>
      )}
    </section>
  )
}

function ManagerRow({ m, kind, lo, hi, minLocks }: { m: LockSummary; kind: LockBaselineKind; lo: number; hi: number; minLocks: number }) {
  const f = figureOf(m, kind)
  const name = m.name ?? `Manager ${m.key}`
  const enough = f.median != null && f.q1 != null && f.q3 != null
  return (
    <li className="lockin-row" aria-label={name}>
      <span className="lockin-who">
        <Avatar avatarId={m.avatarId} seed={m.key} label={name} className="lockin-avatar" />
        <span className="lockin-name">{name}</span>
      </span>
      {enough ? (
        <>
          <span className="lockin-median mono" title="Median margin above the baseline">
            <strong>{signedPct(f.median as number)}</strong>
          </span>
          <span className="lockin-iqr">
            <IqrBar f={f} lo={lo} hi={hi} />
            <span className="lockin-iqr-text mono small">
              middle half {signedPct(f.q1 as number)} to {signedPct(f.q3 as number)}
            </span>
          </span>
        </>
      ) : (
        <span className="lockin-toofew muted mono" title={`A median needs at least ${minLocks} locks`}>
          {f.n} locks — too few
        </span>
      )}
      <span className="lockin-counts mono small">
        <span className="lockin-pill" title="Locks with a margin against this baseline">{f.n} locks</span>
        <span className="lockin-pill lockin-pill-quiet" title="Weeks he rode the last game out instead">{m.rodeOut} rode out</span>
      </span>
    </li>
  )
}

/** What the current baseline left out, from the payload's own counts. */
function leftOut(r: LockInReport, kind: LockBaselineKind): string[] {
  const t = r.totals
  const out: string[] = []
  if (kind === 'STAND_IN') {
    if (t.noBaselineStandIn > 0) {
      out.push(`${t.noBaselineStandIn} locks have no stand-in (fewer than ${r.minPriorGames} earlier games this season, or an average of 0)`)
    }
  } else {
    if (t.noSnapshot > 0) out.push(`${t.noSnapshot} locks have no frozen projection`)
    if (t.nonPositiveProjection > 0) out.push(`${t.nonPositiveProjection} locks were projected for 0 or less`)
  }
  if (t.tieNonLast > 0) out.push(`${t.tieNonLast} locks tied several games, so which one is unknown and they have no margin`)
  return out
}

export function LockInPanelView({ r }: { r: LockInReport }) {
  const [kind, setKind] = useState<LockBaselineKind>('STAND_IN')
  const rows = ordered(r.managers, kind)
  const { lo, hi } = axisOf(rows, kind)
  const t = r.totals
  const skipped = leftOut(r, kind)
  const noBaselineLocks =
    kind === 'PROJECTION' && [...r.managers, ...r.players].every((x) => figureOf(x, kind).n === 0)
  return (
    <>
      <p className="muted small lockin-summary">
        Over <span className="mono">{r.weeksCovered}</span> final weeks:{' '}
        <span className="mono">{t.lock + t.tieNonLast}</span> provable locks (a later game existed),{' '}
        <span className="mono">{t.rodeOut}</span> weeks ridden out to the last game, of{' '}
        <span className="mono">{t.multiGame}</span> starter-weeks with a choice.
      </p>

      <div className="segmented sm lockin-tabs" role="tablist" aria-label="Which baseline the margins are measured against">
        {(['STAND_IN', 'PROJECTION'] as const).map((k) => (
          <button
            key={k}
            type="button"
            role="tab"
            aria-selected={k === kind}
            className={`segment${k === kind ? ' on' : ''}`}
            onClick={() => setKind(k)}
          >
            {KIND_LABEL[k]}
          </button>
        ))}
      </div>

      {r.live && <LiveCallsBlock live={r.live} thresholds={r.thresholds} />}
      {r.thresholds && <ThresholdStrip t={r.thresholds} kind={kind} />}

      {noBaselineLocks ? (
        <p className="muted small lockin-axis">
          No frozen pre-game projections cover this season's locks, so there is no projection margin to show. Snapshots only exist for games played after they began being kept.
          {skipped.length > 0 && ` (${skipped.join('; ')})`}
        </p>
      ) : (
      <>
      <section aria-label="Managers">
        <h3 className="lockin-h">Managers</h3>
        <p className="muted small lockin-axis">
          Margin of the locked game's points above the player's {kind === 'STAND_IN' ? 'season average before it' : 'frozen pre-game projection'}.
          The bar spans the middle half of that manager's locks; the tick is the median.
        </p>
        <ul className="lockin-list">
          {rows.map((m) => (
            <ManagerRow key={m.key} m={m} kind={kind} lo={lo} hi={hi} minLocks={r.minLocks} />
          ))}
        </ul>
        {skipped.length > 0 && (
          <ul className="muted small lockin-skipped">
            {skipped.map((s) => (
              <li key={s}>{s}</li>
            ))}
          </ul>
        )}
      </section>

      <PlayersBlock r={r} kind={kind} />
      <PayoffGrid r={r} kind={kind} />
      </>
      )}

      <ul className="muted small lockin-caveats">
        {r.caveats.map((c) => (
          <li key={c}>{c}</li>
        ))}
      </ul>
    </>
  )
}

/** Statuses where lock-ins simply don't apply to this league: the panel isn't drawn at all. */
const HIDDEN = new Set(['NOT_NBA', 'NOT_SLEEPER', 'GAME_MODE'])

/** Self-fetching: a separate read from `/analysis`, so a slow or failed one cannot hold the page. */
export default function LockInPanel({
  sleeperLeagueId,
  dataVersion,
}: {
  sleeperLeagueId: string
  dataVersion?: number
}) {
  const [r, setR] = useState<LockInReport | null>(null)
  const [failed, setFailed] = useState(false)
  // A 422 is the server's per-platform refusal (an ESPN league): not a transient failure and not worth a sentence.
  const [refused, setRefused] = useState(false)

  useEffect(() => {
    let live = true
    setR(null)          // never show the previous league's managers under the new league's name
    setFailed(false)
    setRefused(false)
    fetchLockIns(sleeperLeagueId, { live: true })
      .then((x) => live && setR(x))
      .catch((e: unknown) => {
        if (!live) return
        if (e instanceof ApiError && e.status === 422) setRefused(true)
        else setFailed(true)
      })
    return () => {
      live = false
    }
  }, [sleeperLeagueId, dataVersion])

  if (refused) return null
  if (r && HIDDEN.has(r.status)) return null
  return (
    <section className="section analysis-section" id="lock-ins">
      <div className="panel-head">
        <h2 className="section-title">Lock-ins</h2>
        <span className="muted small">how far above his baseline each manager locked · inferred, not recorded</span>
      </div>
      {failed && <p className="muted small">Lock-ins couldn't be loaded just now.</p>}
      {!failed && !r && <p className="muted small">Loading…</p>}
      {r && r.status !== 'MEASURED' && <p className="muted small">{r.reason ?? 'Lock-ins are not available for this league yet.'}</p>}
      {/* A season's first week has no scored week yet, which is exactly when the live calls matter most. */}
      {r && r.status !== 'MEASURED' && r.live && <LiveCallsBlock live={r.live} thresholds={r.thresholds} />}
      {r && r.status !== 'MEASURED' && r.thresholds && <ThresholdStrip t={r.thresholds} />}
      {r && r.status === 'MEASURED' && <LockInPanelView r={r} />}
    </section>
  )
}
