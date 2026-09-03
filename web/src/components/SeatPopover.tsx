import { useEffect, useState } from 'react'
import { getManagers, setTendencies, clearTendencies, type Seat } from '../api'
import { hueFor } from '../hue'
import { PROVENANCE_LABEL } from '../provenance'
import { behaviourText } from '../managerBehaviour'

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
  return behaviourText({ reachBias: s.reachBias, unpredictability: s.unpredictability, positionalTilt: s.positionalTilt })
}

function footnote(s: Seat) {
  switch (s.provenance) {
    case 'NEUTRAL':
      return null // the body line already says it; saying it twice is worse than once
    case 'STATED':
      return 'What you entered. No history to check it against.'
    case 'FITTED':
      return `${s.draftsObserved} draft${s.draftsObserved === 1 ? '' : 's'} observed · ${s.picksScored} picks scoreable`
    case 'BLENDED':
      return `Your input, pulled toward ${s.draftsObserved} draft${s.draftsObserved === 1 ? '' : 's'} of history (${s.picksScored} picks)`
  }
}

type Props = {
  seat: Seat
  isMe: boolean
  onChanged: () => void
  onClose: () => void
  onMakeMine: () => void
}

export default function SeatPopover({ seat: s, isMe, onChanged, onClose, onMakeMine }: Props) {
  const [editing, setEditing] = useState(false)
  const [loadingStated, setLoadingStated] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [reachBias, setReachBias] = useState('')
  const [unpredictability, setUnpredictability] = useState('')
  const [note, setNote] = useState('')

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
    setSaveError(null)
    try {
      const managers = await getManagers()
      const mine = managers.find((m) => m.managerId === s.managerId)
      const stated = mine?.stated ?? { reachBias: null, unpredictability: null, note: null }
      setReachBias(stated.reachBias != null ? String(stated.reachBias) : '')
      setUnpredictability(stated.unpredictability != null ? String(stated.unpredictability) : '')
      setNote(stated.note ?? '')
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoadingStated(false)
    }
  }

  async function save() {
    const rb = reachBias.trim() === '' ? null : Number(reachBias)
    const up = unpredictability.trim() === '' ? null : Number(unpredictability)
    if ((rb !== null && Number.isNaN(rb)) || (up !== null && Number.isNaN(up))) {
      setSaveError('reach bias and unpredictability must be numbers')
      return
    }
    setSaving(true)
    setSaveError(null)
    try {
      await setTendencies(s.managerId, { reachBias: rb, unpredictability: up, note: note.trim() === '' ? null : note.trim() })
      setEditing(false)
      onChanged()
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  async function clear() {
    setSaving(true)
    setSaveError(null)
    try {
      await clearTendencies(s.managerId)
      setEditing(false)
      onChanged()
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
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
              <button className="seat-edit" onClick={() => (editing ? setEditing(false) : startEdit())}>
                {editing ? 'cancel' : 'edit'}
              </button>
            </span>
          </div>

          {editing ? (
            <div className="seat-form">
              {loadingStated ? (
                <p className="muted small">Loading…</p>
              ) : (
                <>
                  <label className="small">
                    reach bias
                    <input
                      type="number"
                      min={-20}
                      max={20}
                      step={0.1}
                      value={reachBias}
                      onChange={(e) => setReachBias(e.target.value)}
                    />
                  </label>
                  <label className="small">
                    unpredictability
                    <input
                      type="number"
                      min={0.1}
                      max={3.0}
                      step={0.1}
                      value={unpredictability}
                      onChange={(e) => setUnpredictability(e.target.value)}
                    />
                  </label>
                  <label className="small">
                    note
                    <input type="text" value={note} onChange={(e) => setNote(e.target.value)} />
                  </label>
                  {saveError && <p className="seat-form-error small">{saveError}</p>}
                  <div className="seat-form-actions">
                    <button onClick={save} disabled={saving}>
                      {saving ? 'saving…' : 'save'}
                    </button>
                    {canClear && (
                      <button onClick={clear} disabled={saving}>
                        clear
                      </button>
                    )}
                    <button onClick={() => setEditing(false)} disabled={saving}>
                      cancel
                    </button>
                  </div>
                </>
              )}
            </div>
          ) : (
            <>
              {s.provenance === 'NEUTRAL' ? (
                <p className="muted small">Drafts like the room — nothing entered for this seat.</p>
              ) : (
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
