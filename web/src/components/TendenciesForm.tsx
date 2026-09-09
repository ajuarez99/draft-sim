import { useState } from 'react'
import { setTendencies, clearTendencies, type ManualTendencies, type Sport } from '../api'

/**
 * The stated-tendencies form, previously hand-duplicated in SeatPopover.tsx
 * (a seat inside a draft board) and ManagerTendencies.tsx (the standalone
 * /managers page) -- same save/clear/cancel actions, same PUT/DELETE
 * endpoints, same copy. Extracted per claude/design-review-next-steps.md A3.
 *
 * Only `note` is user-editable. `reachBias`/`unpredictability` are model
 * internals -- a signed float and a 0.1-3.0 multiplier don't mean anything to
 * someone who hasn't read the engine, and Allan's call (2026-09-07) is that a
 * human shouldn't be typing numbers into a model; the engine should fit them
 * from history instead (see `ManagerTendencies.tsx`'s empiricalReachBias
 * comparison). The PUT endpoint still replaces all three fields on every call
 * (`ManagerController.set`'s javadoc: "omitting it is the same as sending
 * null"), so `save` below round-trips `initial.reachBias`/`unpredictability`
 * unchanged rather than sending null -- otherwise editing just the note on a
 * seat that already has a stated reach (set before this change, or by a
 * future non-UI path) would silently wipe it.
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
  /**
   * Manual tendencies are stored per (manager, sport), and the same Sleeper
   * user id is a manager in both a football and a basketball league here --
   * ten of twelve, in Allan's own leagues. Both endpoints below default this
   * to nfl server-side, so a missing sport is not an error, it is a write to
   * the wrong sport's row. Required prop for that reason.
   */
  sport: Sport
  initial: ManualTendencies
  /** STATED/BLENDED seats have something typed-in to delete; FITTED/NEUTRAL don't. */
  canClear: boolean
  /** Save or Clear went through -- caller closes edit mode and refetches. */
  onDone: () => void
  /** User backed out with no write. */
  onCancel: () => void
}

export default function TendenciesForm({ managerId, sport, initial, canClear, onDone, onCancel }: TendenciesFormProps) {
  const [note, setNote] = useState(initial.note ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await setTendencies(managerId, sport, {
        reachBias: initial.reachBias,
        unpredictability: initial.unpredictability,
        note: note.trim() === '' ? null : note.trim(),
      })
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
      await clearTendencies(managerId, sport)
      onDone()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="seat-form">
      <label className="small">
        Note
        {/* Clamped to 280 server-side by ManualTendencies -- without this the
            box would let you type text that gets silently cut on save. */}
        <input type="text" maxLength={280} value={note} onChange={(e) => setNote(e.target.value)} />
      </label>

      {error && <p className="seat-form-error small">{error}</p>}

      <div className="seat-form-actions">
        <button onClick={() => void save()} disabled={saving} title="Save this note for this manager">
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
