/**
 * One per-sport position list, replacing the three duplicated `POSITIONS`
 * constants that used to live in AvailabilityPanel.tsx, PlayerPicker.tsx and
 * OnTheClockPickInput.tsx (plus pickRun.ts's own `RUNNABLE`) -- all four independently
 * hardcoded football's six positions. Mirrors backend/.../domain/Position.java's
 * enum exactly (forSport order): football QB,RB,WR,TE,K,DEF; basketball
 * PG,SG,SF,PF,C. See claude/multi-sport-and-rebrand.md Phase 6.
 *
 * Every position-scoped list in the frontend must ask for ONE sport's list,
 * never the union of both -- a football board showing a `C` filter chip, or a
 * basketball one showing `DEF`, would be a bug.
 */

import type { Sport } from './api'

export const POSITIONS_BY_SPORT: Record<Sport, readonly string[]> = {
  nfl: ['QB', 'RB', 'WR', 'TE', 'K', 'DEF'],
  nba: ['PG', 'SG', 'SF', 'PF', 'C'],
}

/** The shape every position-filter chip row wants: the sport's positions, `ALL` leading. */
export function filterPositions(sport: Sport): readonly string[] {
  return ['ALL', ...POSITIONS_BY_SPORT[sport]]
}
