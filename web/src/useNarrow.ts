import { useEffect, useState } from 'react'

/**
 * The phone-layout breakpoint, the one JS copy of styles.css's
 * `@media (max-width: 860px)` block (useNarrow.test.ts fails if the CSS loses
 * it). Below it the rail is a horizontal bar across the top, not a column.
 *
 * Shared by AppShell (the rail renders a "BK" mark vs the wordmark, and
 * "collapsed" means nothing there) and LeagueRailSection (spec 013: every nav
 * group is expanded in the phone lane, so no page costs an extra tap). It was
 * briefly two hooks with two copies of this string. Keep it one.
 *
 * `matchMedia` is guarded: jsdom doesn't implement it, and these components
 * mount in every App test.
 */
export const NARROW = '(max-width: 860px)'

export function useNarrow(): boolean {
  const supported = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  const [narrow, setNarrow] = useState(() => (supported ? window.matchMedia(NARROW).matches : false))

  useEffect(() => {
    if (!supported) return
    const mq = window.matchMedia(NARROW)
    const sync = () => setNarrow(mq.matches)
    sync()
    mq.addEventListener('change', sync)
    return () => mq.removeEventListener('change', sync)
  }, [supported])

  return narrow
}
