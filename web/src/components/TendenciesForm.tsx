import { useState } from 'react'
import { setTendencies, clearTendencies, type ManualTendencies } from '../api'

/**
 * The stated-tendencies form, previously hand-duplicated in SeatPopover.tsx
 * (a seat inside a draft board) and ManagerTendencies.tsx (the standalone
 * /managers page) -- same three fields, same save/clear/cancel actions, same
 * PUT/DELETE endpoints, same copy. Extracted per
 * claude/design-review-next-steps.md A3.
 *
 * The two call sites read from differently-shaped API types (Seat vs
 * ManagerSummary). managerBehaviour.ts already solved that same mismatch for
 * the read-mode sentence by asking each caller to narrow its own type down to
 * a small shared shape rather than teaching the shared function about either
 * type. Same move here: both `Seat.stated`-ish data (SeatPopover fetches it
 * async off getManagers()) and `ManagerSummary.stated` are already
 * `ManualTendencies` (api.ts), so that's the prop -- this component never
 * needs to know a Seat or a ManagerSummary exists. Everything about *how* a
 * caller gets there (fetching, the editing on/off toggle, the "loading
 * stated values" spinner SeatPopover shows before this even mounts) stays at
 * the call site.
 */
export type TendenciesFormProps = {
  managerId: number
  initial: ManualTendencies
  /** STATED/BLENDED seats have something typed-in to delete; FITTED/NEUTRAL don't. */
  canClear: boolean
  /** Save or Clear went through -- caller closes edit mode and refetches. */
  onDone: () => void
  /** User backed out with no write. */
  onCancel: () => void
}

// Same 0.5 threshold behaviourText() (managerBehaviour.ts) uses for "drafts
// close to the board" -- if this picked a different cutoff, the live preview
// under the input and the read-mode sentence you see after saving would
// disagree about where "basically at ADP" ends, for the same number.
function reachPreview(raw: string): string | null {
  if (raw.trim() === '') return 'Unset — this manager drafts at the room average.'
  const n = Number(raw)
  if (Number.isNaN(n)) return null // the inline error below already covers this case
  if (n > 0.5) return `Reads as ~${n.toFixed(1)} picks early.`
  if (n < -0.5) return `Reads as ~${Math.abs(n).toFixed(1)} picks late.`
  return 'Reads as drafting at ADP.'
}

// Named stops for a 0.1-3.0 float that means nothing as a bare number.
// Thresholds match managerBehaviour.ts's "erratic"/"very predictable" cutoffs
// (>=1.25 / <=0.8) exactly, for the same reason as reachPreview above. Modeled
// on the gear's own `chaos` slider (DraftView.tsx's Simulation settings
// modal), which already labels its 0-3 range as words-plus-value instead of a
// bare float -- this is that pattern applied to the other unlabeled range in
// the app rather than a new one invented for it.
function unpredictabilityPreview(raw: string): string | null {
  if (raw.trim() === '') return 'Unset — uses the league default.'
  const n = Number(raw)
  if (Number.isNaN(n)) return null
  if (n <= 0.8) return `Predictable (${n.toFixed(1)})`
  if (n >= 1.25) return `Wild card (${n.toFixed(1)})`
  return `Normal (${n.toFixed(1)})`
}

export default function TendenciesForm({ managerId, initial, canClear, onDone, onCancel }: TendenciesFormProps) {
  const [reachBias, setReachBias] = useState(initial.reachBias != null ? String(initial.reachBias) : '')
  const [unpredictability, setUnpredictability] = useState(
    initial.unpredictability != null ? String(initial.unpredictability) : '',
  )
  const [note, setNote] = useState(initial.note ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save() {
    const rb = reachBias.trim() === '' ? null : Number(reachBias)
    const up = unpredictability.trim() === '' ? null : Number(unpredictability)
    // Carried over unchanged from the pre-extraction SeatPopover/ManagerTendencies
    // code. A compliant <input type="number"> already sanitizes any keystroke
    // that isn't a valid float back to '' (verified against jsdom -- "abc",
    // "-", "1.", "5,5" all round-trip to empty), so this can't actually fire
    // through the fields above today. Kept anyway as a backstop against a
    // future input-type change or a non-compliant UA silently letting garbage
    // through -- cheap insurance, and removing it would be the kind of "it
    // can't happen" call that this codebase's own history argues against.
    if ((rb !== null && Number.isNaN(rb)) || (up !== null && Number.isNaN(up))) {
      setError('Picks early and unpredictability must both be numbers.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      await setTendencies(managerId, { reachBias: rb, unpredictability: up, note: note.trim() === '' ? null : note.trim() })
      onDone()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  async function clear() {
    setSaving(true)
    setError(null)
    try {
      await clearTendencies(managerId)
      onDone()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  const reachHint = reachPreview(reachBias)
  const upHint = unpredictabilityPreview(unpredictability)

  return (
    <div className="seat-form">
      <label className="small">
        Picks early
        {/* Sent to the API unchanged as a signed `reachBias` -- the sign
            itself never has to mean anything on screen, because "-4.8" reads
            as an error to anyone who hasn't read the model. reachPreview
            below says which way it goes in words instead. */}
        <input
          type="number"
          min={-20}
          max={20}
          step={0.1}
          value={reachBias}
          onChange={(e) => setReachBias(e.target.value)}
        />
      </label>
      {reachHint && <p className="muted tiny field-hint">{reachHint}</p>}

      <label className="small">
        Unpredictability
        <input
          type="number"
          min={0.1}
          max={3.0}
          step={0.1}
          value={unpredictability}
          onChange={(e) => setUnpredictability(e.target.value)}
        />
      </label>
      {upHint && <p className="muted tiny field-hint">{upHint}</p>}

      <label className="small">
        Note
        {/* Clamped to 280 server-side by ManualTendencies -- without this the
            box would let you type text that gets silently cut on save. */}
        <input type="text" maxLength={280} value={note} onChange={(e) => setNote(e.target.value)} />
      </label>

      {error && <p className="seat-form-error small">{error}</p>}

      <div className="seat-form-actions">
        <button onClick={() => void save()} disabled={saving} title="Save these tendencies for this manager">
          {saving ? 'Saving…' : 'Save'}
        </button>
        {canClear && (
          <button
            onClick={() => void clear()}
            disabled={saving}
            title="Delete what you entered and fall back to history or the league average"
          >
            Clear
          </button>
        )}
        <button onClick={onCancel} disabled={saving} title="Discard changes and stop editing">
          Cancel
        </button>
      </div>
    </div>
  )
}
