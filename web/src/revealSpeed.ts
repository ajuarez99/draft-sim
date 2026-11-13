export type RevealSpeed = 'SLOW' | 'NORMAL' | 'FAST' | 'INSTANT'

/**
 * Milliseconds between computer picks in the Time Machine room. All four are HAND-SET FOR FEEL (spec 063,
 * Allan's "picks arrive too fast"), not derived from anything and not measured against any real draft clock.
 * Retune by watching a draft, not by reasoning.
 */
export const REVEAL_MS: Record<RevealSpeed, number> = { SLOW: 2000, NORMAL: 1000, FAST: 400, INSTANT: 0 }

export const REVEAL_SPEEDS: RevealSpeed[] = ['SLOW', 'NORMAL', 'FAST', 'INSTANT']

const KEY = 'bk.tm.revealSpeed'

/** Missing, unreadable or unknown values read as NORMAL. */
export function loadRevealSpeed(): RevealSpeed {
  try {
    const v = localStorage.getItem(KEY)
    return v != null && (REVEAL_SPEEDS as string[]).includes(v) ? (v as RevealSpeed) : 'NORMAL'
  } catch {
    return 'NORMAL'
  }
}

export function saveRevealSpeed(s: RevealSpeed): void {
  try {
    localStorage.setItem(KEY, s)
  } catch {
    // The choice still holds for this page load.
  }
}
