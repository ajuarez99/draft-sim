import { useState } from 'react'
import { saveWeeklyPotSettings, type WeeklyPot } from '../api'

/** Hand-set bounds, mirroring the server's 1..100000 cents. Arbitrary: a sanity cap, not a league rule. */
export const MIN_STAKE_CENTS = 1
export const MAX_STAKE_CENTS = 100000

/**
 * "1.5" -> 150, "0.29" -> 29. Null when the text is not a dollar amount with at most two decimals, or is
 * outside $0.01-$1,000. `Math.round` because 0.29 * 100 is 28.999999999999996 in floating point.
 */
export function dollarsToCents(text: string): number | null {
  const t = text.trim()
  if (t === '' || !/^\d*(\.\d{1,2})?$/.test(t)) return null
  const n = Number(t)
  if (!Number.isFinite(n)) return null
  const cents = Math.round(n * 100)
  return cents >= MIN_STAKE_CENTS && cents <= MAX_STAKE_CENTS ? cents : null
}

type Props = {
  leagueSleeperId: string
  enabled: boolean
  stakeCents: number
  /** Called with the server's returned body after a successful save. */
  onSaved: (next: WeeklyPot) => void
}

/** The commissioner's switch and stake for the weekly pot. Mounted by the page only when `canEdit`. */
export default function CommissionerPotPanel({ leagueSleeperId, enabled, stakeCents, onSaved }: Props) {
  const [on, setOn] = useState(enabled)
  const [dollars, setDollars] = useState((stakeCents / 100).toFixed(2))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save() {
    const cents = dollarsToCents(dollars)
    if (cents == null) {
      setError('Enter an amount from $0.01 to $1,000.00, with at most two decimals.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      onSaved(await saveWeeklyPotSettings(leagueSleeperId, { enabled: on, stakeCents: cents }))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="panel wp-commish" aria-label="Commissioner settings for the weekly pot">
      <h2 className="wp-h">Commissioner settings</h2>
      <label className="wp-switch">
        <input type="checkbox" checked={on} onChange={(e) => setOn(e.target.checked)} />
        <span>Run the weekly pot in this league</span>
      </label>
      <label className="wp-stake">
        <span>Stake per week (USD)</span>
        <input
          type="text"
          inputMode="decimal"
          value={dollars}
          onChange={(e) => setDollars(e.target.value)}
          aria-label="Stake per week in dollars"
        />
      </label>
      <button type="button" className="wp-save" onClick={save} disabled={saving}>
        {saving ? 'Saving…' : 'Save'}
      </button>
      {error && <p className="wp-error" role="alert">{error}</p>}
    </section>
  )
}
