import { useEffect, useState } from 'react'
import { getManagers, setTendencies, clearTendencies, type ManagerSummary } from '../api'
import { hueFor } from '../hue'
import { PROVENANCE_LABEL } from '../provenance'
import { behaviourText } from '../managerBehaviour'

/**
 * /managers -- the standalone place to declare tendencies for a real manager
 * without opening a draft, and to see whether that belief matches what their
 * own draft history actually says. The per-seat popover inside a board
 * (SeatPopover.tsx) covers the same PUT/DELETE endpoints for the seat you're
 * looking at mid-draft; this page is the "manage all of them, any time" view,
 * plus the stated-vs-empirical comparison SeatPopover has no room for.
 */

function behaviour(m: ManagerSummary) {
  return behaviourText({ reachBias: m.effectiveReachBias, unpredictability: m.unpredictability, positionalTilt: m.positionalTilt })
}

/** The actual point of this page: does the stated number agree with history? */
function comparison(m: ManagerSummary) {
  const stated = m.stated.reachBias
  const empirical = m.empiricalReachBias
  if (stated == null || empirical == null) return null
  return `you said ${stated > 0 ? '+' : ''}${stated.toFixed(1)} · history says ${empirical > 0 ? '+' : ''}${empirical.toFixed(1)} over ${m.draftsObserved} draft${m.draftsObserved === 1 ? '' : 's'} (${m.picksScored} picks)`
}

type RowProps = { m: ManagerSummary; onChanged: () => void }

function ManagerRow({ m, onChanged }: RowProps) {
  const [editing, setEditing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [reachBias, setReachBias] = useState(m.stated.reachBias != null ? String(m.stated.reachBias) : '')
  const [unpredictability, setUnpredictability] = useState(
    m.stated.unpredictability != null ? String(m.stated.unpredictability) : '',
  )
  const [note, setNote] = useState(m.stated.note ?? '')

  const label = PROVENANCE_LABEL[m.provenance]
  const hue = hueFor(String(m.managerId))
  const avatarStyle = { background: `oklch(28% 0.03 ${hue})`, color: `oklch(82% 0.1 ${hue})` }
  const cmp = comparison(m)

  function startEdit() {
    setReachBias(m.stated.reachBias != null ? String(m.stated.reachBias) : '')
    setUnpredictability(m.stated.unpredictability != null ? String(m.stated.unpredictability) : '')
    setNote(m.stated.note ?? '')
    setSaveError(null)
    setEditing(true)
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
      await setTendencies(m.managerId, { reachBias: rb, unpredictability: up, note: note.trim() === '' ? null : note.trim() })
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
      await clearTendencies(m.managerId)
      setEditing(false)
      onChanged()
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  const canClear = m.provenance === 'STATED' || m.provenance === 'BLENDED'

  return (
    <div className={`seat ${label.className}${m.provenance === 'NEUTRAL' ? ' neutral-row' : ''}`}>
      <div className="seat-head">
        <span className="avatar" style={avatarStyle}>
          {m.manager.trim().charAt(0).toUpperCase()}
        </span>
        <span className="who">{m.manager}</span>
        <span className="seat-head-right">
          {label.badge && <span className={`prov ${label.className}`}>{label.badge}</span>}
          <button className="seat-edit" onClick={() => (editing ? setEditing(false) : startEdit())}>
            {editing ? 'cancel' : 'edit'}
          </button>
        </span>
      </div>

      {editing ? (
        <div className="seat-form">
          <label className="small">
            reach bias
            <input type="number" min={-20} max={20} step={0.1} value={reachBias} onChange={(e) => setReachBias(e.target.value)} />
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
        </div>
      ) : (
        <>
          {m.provenance === 'NEUTRAL' ? (
            <p className="muted small">Drafts like the room — nothing entered, no history yet.</p>
          ) : (
            <p className="small">{behaviour(m)}</p>
          )}
          {m.note && <p className="note small">“{m.note}”</p>}
          {cmp && <p className="tiny mono">{cmp}</p>}
        </>
      )}
    </div>
  )
}

export default function ManagerTendencies() {
  const [managers, setManagers] = useState<ManagerSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  function refetch() {
    getManagers()
      .then((m) => {
        setManagers(m)
        setError(null)
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }

  useEffect(refetch, [])

  // Configured seats first (real signal, worth reading), neutral ones after --
  // eleven identical "nothing entered" cards burying the three that matter is
  // exactly the noise provenance.ts already avoids on the board's own headers.
  const sorted = managers
    ? [...managers].sort((a, b) => {
        const an = a.provenance === 'NEUTRAL' ? 1 : 0
        const bn = b.provenance === 'NEUTRAL' ? 1 : 0
        return an !== bn ? an - bn : a.manager.localeCompare(b.manager)
      })
    : null

  return (
    <div className="content">
      <section className="panel">
        <div className="panel-head">
          <h2>Manager tendencies</h2>
        </div>
        <p className="muted small">
          What you believe about a manager versus what their own draft history says. A stated
          reach bias is the shrinkage target the engine blends toward -- it is never overridden
          outright, so this is the place to check whether your call and the data still agree.
        </p>

        {error && <div className="error">{error}</div>}

        {sorted && sorted.length === 0 && <p className="muted">No managers ingested yet.</p>}

        {sorted && sorted.length > 0 && (
          <div className="manager-grid">
            {sorted.map((m) => (
              <ManagerRow key={m.managerId} m={m} onChanged={refetch} />
            ))}
          </div>
        )}
      </section>
    </div>
  )
}
