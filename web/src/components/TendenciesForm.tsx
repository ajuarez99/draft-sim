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
 * comparison). Since claude/audit-2026-09-28/02 the server enforces that: PUT
 * answers 400 to a body carrying either number, so `save` sends only `note`.
 * The note is private to the signed-in user (manager_note, V25).
 *
 * The two call sites read from differently-shaped API types (Seat vs
 * ManagerSummary). managerBehaviour.ts already solved that same mismatch for
 * the read-mode sentence by asking each caller to narrow its own type down to
 * a small shared shape rather than teaching the shared function about either
 * type. Same move here: both `Seat.stated`-ish data (SeatPopover fetches it
 * async off getManagers()) and `ManagerSummary.stated` are already
 * `ManualTendencies` (api/managers.ts), so that's the prop -- this component never
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
  /** Whether you have a note to delete. */
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
        {/* The server refuses anything over 140 (ManagerController.MAX_NOTE_LENGTH);
            the cap here keeps you from typing what it would reject. Only you
            can see what you write. */}
        <input type="text" maxLength={140} value={note} onChange={(e) => setNote(e.target.value)} />
      </label>

      {error && <p className="seat-form-error small">{error}</p>}

      <div className="seat-form-actions">
        <button onClick={() => void save()} disabled={saving} title="Save your private note about this manager">
          {saving ? 'Saving…' : 'Save'}
        </button>
        {canClear && (
          <button
            onClick={() => void clear()}
            disabled={saving}
            title="Delete your note about this manager"
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
