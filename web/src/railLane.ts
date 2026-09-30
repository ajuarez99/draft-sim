/**
 * The phone league-pages lane (LeagueRailSection's `.app-rail-pages`) is a
 * horizontal swipe strip that is wider than the screen. Nothing on it says so,
 * so it gets an edge fade -- but only on an edge that still has content behind
 * it. A fade on an edge with nothing past it reads as a clipped label.
 */
export type LaneOverflow = { start: boolean; end: boolean }

/** Sub-pixel scroll positions and zoom make `scrollLeft` fractional; one px of slack avoids a phantom fade. */
const SLACK = 1

export function laneOverflow(scrollLeft: number, clientWidth: number, scrollWidth: number): LaneOverflow {
  const scrollable = scrollWidth - clientWidth > SLACK
  if (!scrollable) return { start: false, end: false }
  return {
    start: scrollLeft > SLACK,
    end: scrollLeft + clientWidth < scrollWidth - SLACK,
  }
}
