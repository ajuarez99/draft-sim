import type { ReactNode } from 'react'

export type PrimaryAction = {
  label: string
  onClick: () => void
  disabled?: boolean
  /** Shown on hover. Required when disabled: a disabled button owes the reader a reason. */
  title: string
}

type Props = {
  /** The one prominent action. Stays rendered, disabled with a title reason, when it can't be used yet (FR-019). */
  primary?: PrimaryAction
  /** Quiet toggles and readouts, left of the primary. */
  children?: ReactNode
}

/**
 * The room-level controls as one compact group with exactly one primary action
 * (spec 024 FR-019). All three rooms render their `controls` region through it so
 * the primary always sits in the same place, last, and looks the same.
 */
export default function RoomControls({ primary, children }: Props) {
  return (
    <div className="room-controls-group">
      {children}
      {primary && (
        <button
          type="button"
          className="chip on room-primary"
          onClick={primary.onClick}
          disabled={primary.disabled}
          title={primary.title}
        >
          {primary.label}
        </button>
      )}
    </div>
  )
}
