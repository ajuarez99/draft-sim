import type { DraftSummary } from './api'

/**
 * Groups a flat list of drafts into one entry per league, newest season first.
 *
 * Every season of a Sleeper league is its own league object with its own id, so
 * the picker used to render one card per season: "West Coast Fantasy Football
 * 2026" and "West Coast Fantasy Football 2025" sat side by side, each carrying
 * its own identical History and Power rankings links. Both of those are
 * league-scoped and walk the whole chain themselves, so the duplicate pills
 * pointed at the same two pages twice.
 *
 * The seasons are chained by `previousLeagueId`, Sleeper's own back-pointer.
 * That is the key rather than `leagueName` because a name both over- and
 * under-groups: two unrelated leagues can share one, and a league that gets
 * renamed between seasons would split into two cards.
 */

export type LeagueLineage = {
  /** The most recent season's draft. The card's identity, links and status. */
  current: DraftSummary
  /** Every season, newest first, `current` included. */
  seasons: DraftSummary[]
}

export function leagueLineages(drafts: DraftSummary[]): LeagueLineage[] {
  // One draft per league. A league with several drafts (rare, but the schema
  // allows it) keeps its newest -- `drafts` arrives newest-first from the
  // backend's own ordering, so the first one seen per league wins.
  const byLeague = new Map<string, DraftSummary>()
  for (const d of drafts) {
    if (!byLeague.has(d.sleeperLeagueId)) byLeague.set(d.sleeperLeagueId, d)
  }

  // Walk back to the earliest season we actually hold, and use it as the group
  // key. Walking backwards rather than forwards because `previousLeagueId` is
  // the only direction the data points, and stopping at the earliest INGESTED
  // season (not the true origin) is what makes two seasons group even when the
  // chain continues into years nobody has ingested.
  function rootOf(d: DraftSummary): string {
    const seen = new Set<string>()
    let cur = d
    while (cur.previousLeagueId) {
      // Defensive: a cycle would hang the picker, and this runs on every render
      // of the home screen. Breaking alone is not enough though -- each member
      // of a cycle would break at a different point and report a different
      // root, so one league would split back into several cards, which is the
      // bug this whole module exists to fix. Every node in a cycle sees the
      // same set of ids, so agreeing on the smallest makes them agree on one
      // group. Sleeper should never produce a cycle; this just fails tidily.
      if (seen.has(cur.sleeperLeagueId)) {
        return [...seen].sort()[0]
      }
      seen.add(cur.sleeperLeagueId)
      const prev = byLeague.get(cur.previousLeagueId)
      if (!prev) break
      cur = prev
    }
    return cur.sleeperLeagueId
  }

  const groups = new Map<string, DraftSummary[]>()
  for (const d of byLeague.values()) {
    const key = rootOf(d)
    const g = groups.get(key)
    if (g) g.push(d)
    else groups.set(key, [d])
  }

  return [...groups.values()]
    .map((seasons) => {
      const sorted = [...seasons].sort((a, b) => b.season - a.season)
      return { current: sorted[0], seasons: sorted }
    })
    // Newest league first, so a league that drafted this year outranks one that
    // last drafted two years ago -- the same intent the backend's own
    // start_time ordering has, preserved across the grouping.
    .sort((a, b) => b.current.season - a.current.season)
}
