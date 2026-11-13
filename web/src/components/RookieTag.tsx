import type { PlayerRef } from '../api'
import { isRookie } from '../rookie'

/** The "R" beside a first-season player's name in the draft rooms' player lists. Renders nothing for anyone else. */
export default function RookieTag({ player }: { player: Pick<PlayerRef, 'rookie'> }) {
  if (!isRookie(player)) return null
  return (
    <span className="rookie-tag" title="Rookie: first season" aria-label="Rookie">
      R
    </span>
  )
}

/** The "Rookies" toggle beside the position chips. It narrows the list further; it does not replace the position. */
export function RookieChip({ on, onChange }: { on: boolean; onChange: (on: boolean) => void }) {
  return (
    <button
      type="button"
      className={`chip rookie-chip${on ? ' on' : ''}`}
      aria-pressed={on}
      title="Show only first-season players"
      onClick={() => onChange(!on)}
    >
      Rookies
    </button>
  )
}
