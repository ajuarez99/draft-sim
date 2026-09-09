import { useEffect, useState } from 'react'
import { getManagers, type ManualTendencies, type Seat, type Sport } from '../api'
import { hueFor } from '../hue'
import { PROVENANCE_LABEL } from '../provenance'
import { behaviourText, reachGapText } from '../managerBehaviour'
import TendenciesForm from './TendenciesForm'

/**
 * Formerly SeatList's per-seat card, now the popover a board column header
 * opens (claude/board-first-layout-and-pick-latency.md §C). One seat at a
 * time instead of fourteen cards in their own band -- the board's headers
 * already carry name/avatar/provenance for everyone; this is where you go
 * to read the rest or edit it.
 *
 * The card branches on PROVENANCE, not on pick counts. A seat you configured
 * by hand has no history and must not be described as the league-average
 * drafter -- that was a real bug, and it quietly presented your input as an
 * absence of input. Equally, a stated tendency must not be dressed up as
 * evidence.
 */
function behaviour(s: Seat) {
  return behaviourText({
    reachBias: s.reachBias,
    unpredictability: s.unpredictability,
    positionalTilt: s.positionalTilt,
    picksScored: s.picksScored,
  })
}

function footnote(s: Seat) {
  switch (s.provenance) {
    case 'NEUTRAL':
      // Two different seats land here. One has no history at all, and the body
      // line already says so. The other has real drafts whose picks could not
      // be scored for reach -- which is every basketball seat, permanently --
      // and that seat needs the reason said out loud, because the alternative
      // is a fitted-looking profile with an invented reach number behind it.
      return reachGapText(s)
    case 'STATED':
      // Not "no history": a seat with drafts but no scoreable picks is STATED
      // too (ProfileService's hasData is picksScored > 0, not draftsObserved),
      // and telling someone there is no history when there are two seasons of
      // it is the wrong correction to make.
      return reachGapText(s) ?? 'What you entered. No history to check it against.'
    case 'FITTED':
      return `${s.draftsObserved} draft${s.draftsObserved === 1 ? '' : 's'} observed · ${s.picksScored} picks scoreable`
    case 'BLENDED':
      return `Your input, pulled toward ${s.draftsObserved} draft${s.draftsObserved === 1 ? '' : 's'} of history (${s.picksScored} picks)`
  }
}

type Props = {
  seat: Seat
  /** Which sport's profile this seat is -- see TendenciesForm's `sport` prop. */
  sport: Sport
  isMe: boolean
  onChanged: () => void
  onClose: () => void
  onMakeMine: () => void
}

export default function SeatPopover({ seat: s, sport, isMe, onChanged, onClose, onMakeMine }: Props) {
  const [editing, setEditing] = useState(false)
  const [loadingStated, setLoadingStated] = useState(false)
  const [loadError, setLoadError] = useState<string | null>(null)
  // What TendenciesForm hydrates from. Seat carries the *effective* numbers
  // (post-blend), not the stated ones the form edits -- those only exist on
  // ManagerSummary.stated, which is why this has to be fetched separately
  // rather than read off `s` directly. ManagerTendencies.tsx doesn't need
  // this fetch because its ManagerSummary already carries `.stated`.
  const [stated, setStated] = useState<ManualTendencies | null>(null)

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const label = PROVENANCE_LABEL[s.provenance]
  const hue = hueFor(String(s.managerId))
  const avatarStyle = isMe
    ? { background: 'var(--crimson)', color: 'var(--bg)' }
    : { background: `oklch(28% 0.03 ${hue})`, color: `oklch(82% 0.1 ${hue})` }

  async function startEdit() {
    setEditing(true)
    setLoadingStated(true)
    setLoadError(null)
    try {
      const managers = await getManagers(sport)
      const mine = managers.find((m) => m.managerId === s.managerId)
      setStated(mine?.stated ?? { reachBias: null, unpredictability: null, note: null })
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoadingStated(false)
    }
  }

  const canClear = s.provenance === 'STATED' || s.provenance === 'BLENDED'

  return (
    <div className="modal-backdrop" onClick={onClose}>
      {/* stopPropagation so a click inside the card doesn't bubble to the backdrop and close it */}
      <div className="modal-card" onClick={(e) => e.stopPropagation()}>
        <button className="modal-close" onClick={onClose} aria-label="Close">
          ✕
        </button>

        <div className={`seat ${label.className}${isMe ? ' me' : ''}`}>
          <div className="seat-head">
            <span className="slot">{s.slot}</span>
            <span className="avatar" style={avatarStyle}>
              {s.manager.trim().charAt(0).toUpperCase()}
            </span>
            <span className={`who${isMe ? ' mine-name' : ''}`}>{s.manager}</span>
            <span className="seat-head-right">
              {label.badge && <span className={`prov ${label.className}`}>{label.badge}</span>}
              {isMe ? (
                <span className="chip on">you</span>
              ) : (
                <button className="chip" onClick={onMakeMine}>
                  this is me
                </button>
              )}
              <button
                className="seat-edit"
                onClick={() => (editing ? setEditing(false) : startEdit())}
                title={editing ? 'Stop editing without saving' : "Edit this manager's stated tendencies"}
              >
                {editing ? 'Cancel' : 'Edit'}
              </button>
            </span>
          </div>

          {editing ? (
            loadingStated ? (
              <p className="muted small">Loading…</p>
            ) : loadError ? (
              <p className="seat-form-error small">{loadError}</p>
            ) : (
              <TendenciesForm
                managerId={s.managerId}
                sport={sport}
                initial={stated ?? { reachBias: null, unpredictability: null, note: null }}
                canClear={canClear}
                onDone={() => {
                  setEditing(false)
                  onChanged()
                }}
                onCancel={() => setEditing(false)}
              />
            )
          ) : (
            <>
              {s.provenance === 'NEUTRAL' && s.draftsObserved === 0 ? (
                <p className="muted small">Drafts like the room — nothing entered for this seat.</p>
              ) : (
                // A NEUTRAL seat WITH drafts observed still gets the real
                // sentence: its positional tilt was fitted from every pick it
                // made, and only reach is missing. Calling that "nothing
                // entered" threw away a signal the engine is actually using.
                <p className="small">{behaviour(s)}</p>
              )}

              {s.note && <p className="note small">“{s.note}”</p>}
              {footnote(s) && <p className="muted tiny">{footnote(s)}</p>}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
