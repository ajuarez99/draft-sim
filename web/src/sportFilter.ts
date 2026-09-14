import { useEffect, useState } from 'react'
import type { Sport } from './api'

export type SportFilter = 'all' | Sport

const SPORT_FILTER_KEY = 'bk-sport-filter'

/**
 * The sport the reader is currently looking at, shared by every page that
 * shows both sports in one list.
 *
 * Lived inside DraftPicker while Home was the only such page. `/managers` is
 * the other one -- it fetches both sports and prints a per-card NFL/NBA pill
 * precisely because the list is mixed -- and it had no filter at all while
 * the rail on Home had one doing nothing on that route
 * (claude/site-wide-shell-propagation.md Phase 5).
 *
 * One key, so the choice follows you between the two rather than each page
 * remembering its own idea of which sport you care about.
 */
export function useSportFilter(): [SportFilter, (f: SportFilter) => void] {
  const [sportFilter, setSportFilter] = useState<SportFilter>(() => {
    try {
      const saved = localStorage.getItem(SPORT_FILTER_KEY)
      return saved === 'nfl' || saved === 'nba' ? saved : 'all'
    } catch {
      return 'all'
    }
  })

  useEffect(() => {
    try {
      localStorage.setItem(SPORT_FILTER_KEY, sportFilter)
    } catch {
      // Private-mode/blocked storage: the filter still works for this visit.
    }
  }, [sportFilter])

  return [sportFilter, setSportFilter]
}
