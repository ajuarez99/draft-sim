// Shared by AvailabilityPanel, the reveal status bar, and PlayerCard -- one
// formula, three call sites, so it only needs fixing in one place.
export function roundPickLabel(pickNo: number, teams: number): string {
  const round = Math.ceil(pickNo / teams)
  const inRound = pickNo - (round - 1) * teams
  return `${round}.${String(inRound).padStart(2, '0')}`
}
