import { useEffect, useState } from 'react'

/**
 * The phone-layout breakpoint, the one JS copy of styles.css's
 * `@media (max-width: 860px)` block (useNarrow.test.ts fails if the CSS loses
 * it). At or below it the top bar is wordmark / search / menu / account, a
 * league's tabs become the fixed bottom bar, and the global links live in the
 * menu sheet.
 *
 * Shared by AppShell (which chrome to render) and anything else that needs the
 * phone layout; the top bar's account menu used to carry a private 700px copy
 * and no longer does. Keep it one.
 *
 * `matchMedia` is guarded: jsdom doesn't implement it, and these components
 * mount in every App test.
 */
export const NARROW = '(max-width: 860px)'

/**
 * Below this the draft room stops stacking board over list and offers a
 * Board | Players toggle instead (spec 024 FR-001/FR-002). ARBITRARY: 1280 is
 * the width the spec names, not one derived from a measurement. The room
 * (DraftRoomLayout) is the only caller; styles.css repeats it in its own
 * `@media (max-width: 1279px)` block and DraftRoomLayout.test.tsx checks both.
 */
export const ROOM_STACKED = '(max-width: 1279px)'

/** `query` defaults to the phone breakpoint; the draft room passes ROOM_STACKED. */
export function useNarrow(query: string = NARROW): boolean {
  const supported = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  const [narrow, setNarrow] = useState(() => (supported ? window.matchMedia(query).matches : false))

  useEffect(() => {
    if (!supported) return
    const mq = window.matchMedia(query)
    const sync = () => setNarrow(mq.matches)
    sync()
    mq.addEventListener('change', sync)
    return () => mq.removeEventListener('change', sync)
  }, [supported, query])

  return narrow
}
