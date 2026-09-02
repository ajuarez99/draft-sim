import type { Provenance } from './api'

// Shared between the board's column-header provenance dot (§C of
// claude/board-first-layout-and-pick-latency.md) and the seat popover's text
// badge -- same three states, same colors, so a manager's seat reads the
// same way in both places instead of drifting into two color systems.
//
// Neutral seats get no badge/dot label on purpose: eleven of fourteen
// headers carrying an identical "league average" mark is noise that buries
// the three that actually matter (see SeatPopover.tsx's original comment on
// this, carried over from the old SeatList).
export const PROVENANCE_LABEL: Record<Provenance, { badge: string | null; className: string }> = {
  NEUTRAL: { badge: null, className: 'neutral' },
  STATED: { badge: 'your call', className: 'stated' },
  FITTED: { badge: 'from history', className: 'fitted' },
  BLENDED: { badge: 'both', className: 'fitted' },
}
