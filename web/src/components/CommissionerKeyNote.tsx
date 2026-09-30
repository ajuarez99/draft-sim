import { clearCommissionerKey, useCommissionerKey } from '../commissionerKey'

/**
 * The way to forget a saved commissioner key. Renders nothing when no key is
 * saved, so it only appears once someone has actually been asked for one.
 * Sits beside the commissioner controls (power rankings, the conduct list).
 */
export default function CommissionerKeyNote() {
  const key = useCommissionerKey()
  if (!key) return null
  return (
    <span className="small muted commissioner-key-note">
      Commissioner key saved on this device.{' '}
      <button type="button" className="link-button" onClick={clearCommissionerKey}>
        Clear
      </button>
    </span>
  )
}
