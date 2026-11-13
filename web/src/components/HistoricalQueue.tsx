import type { HistoricalPlayerCard, HistoricalSport } from '../api'
import { cardToPlayerRef, priorLine } from '../historicalCopy'
import { posPill } from '../positions'

type Props = {
  /** Queued player ids, in queue order (already pruned against the reveal view's pool). */
  ids: number[]
  /** The reveal view's available players, to look the ids up. */
  players: HistoricalPlayerCard[]
  sport: HistoricalSport
  /** True only on the fan's turn with no reveal running. */
  canPick: boolean
  busy: boolean
  onPick: (c: HistoricalPlayerCard) => void
  onMoveUp: (id: number) => void
  onMoveDown: (id: number) => void
  onRemove: (id: number) => void
}

/**
 * Spec 063 C4: the queue as its own view, in queue order. Reordering is by buttons, not dragging, so it is
 * keyboard-operable by construction. The queue can be edited while picks are still landing; Draft appears only on
 * the fan's turn.
 */
export default function HistoricalQueue({ ids, players, sport, canPick, busy, onPick, onMoveUp, onMoveDown, onRemove }: Props) {
  const byId = new Map(players.map((p) => [p.id, p]))
  const rows = ids.map((id) => byId.get(id)).filter((p): p is HistoricalPlayerCard => p != null)
  const sportKey = sport === 'NFL' ? 'nfl' : 'nba'
  return (
    <section className="panel hist-queue" aria-label="Your queue">
      <div className="panel-head">
        <h2>Your queue</h2>
        <span className="muted small">{rows.length === 0 ? 'empty' : `${rows.length} waiting`}</span>
      </div>
      {rows.length === 0 ? (
        <p className="muted hist-queue-empty">
          Nothing queued yet. Star the players you are hoping to land and they wait here, in your order. On your turn,
          draft the top one, or press Auto-pick and he is taken for you. Anyone the computer teams take drops out on
          his own.
        </p>
      ) : (
        <ol className="hist-queue-rows">
          {rows.map((p, i) => {
            const pill = posPill(cardToPlayerRef(p, sport), sportKey)
            return (
              <li key={p.id} className={`hist-queue-row${i === 0 ? ' top' : ''}`}>
                <span className="hist-queue-n mono">{i + 1}</span>
                <div className="hist-main">
                  <div className="hist-name">
                    <strong>{p.name}</strong>
                    {p.positions.length > 0 ? (
                      p.positions.map((pos) => (
                        <span key={pos} className={`pos ${pos}`}>
                          {pos}
                        </span>
                      ))
                    ) : (
                      <span className={pill.className} style={pill.style}>
                        --
                      </span>
                    )}
                    <span className="muted small">{p.team ?? '—'}</span>
                  </div>
                  <div className="hist-line small muted">{priorLine(p.prior, sport, p.rookiePick)}</div>
                </div>
                <div className="hist-queue-actions">
                  {canPick && (
                    <button type="button" className="chip on hist-draft" disabled={busy} aria-label={`Draft ${p.name}`} onClick={() => onPick(p)}>
                      Draft
                    </button>
                  )}
                  <button type="button" className="chip" disabled={i === 0} aria-label={`Move ${p.name} up`} onClick={() => onMoveUp(p.id)}>
                    <span aria-hidden="true">↑</span> Up
                  </button>
                  <button type="button" className="chip" disabled={i === rows.length - 1} aria-label={`Move ${p.name} down`} onClick={() => onMoveDown(p.id)}>
                    <span aria-hidden="true">↓</span> Down
                  </button>
                  <button type="button" className="chip" aria-label={`Remove ${p.name} from your queue`} onClick={() => onRemove(p.id)}>
                    Remove
                  </button>
                </div>
              </li>
            )
          })}
        </ol>
      )}
    </section>
  )
}
