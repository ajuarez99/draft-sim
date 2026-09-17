import { useCallback, useEffect, useState } from 'react'
import { destinationsFor, labelOf, type LeagueContext } from './destinations'
import { leagueLineages } from './leagueLineage'
import { cachedDrafts } from './railLeague'
import { getManagers, type DraftSummary, type ManagerSummary, type Sport } from './api'

/**
 * Everything you can navigate to, flattened into one searchable list.
 *
 * Built entirely from data the app already has: the draft list the rail caches
 * at module scope (railLeague.ts) and the manager list `/managers` fetches.
 * No new endpoint, and on a warm cache no request at all -- opening the palette
 * must not cost a round trip.
 *
 * It is a `filter` over a few hundred rows, not a search engine. Five leagues
 * across nine seasons plus fifty-odd managers is small enough that anything
 * cleverer would be a library's worth of machinery to solve a problem this
 * codebase does not have.
 */

export type SearchKind = 'league-page' | 'season-board' | 'manager'

export type SearchDestination = {
  id: string
  kind: SearchKind
  /** What the user reads -- "History", "2025 board", a manager's name. */
  label: string
  /** Which league or sport it belongs to. Required: five leagues each have a
   *  page called "History", and a result list that doesn't say which is which
   *  is a list of identical rows. */
  context: string
  href: string
  /** Every sport this row belongs to. A manager who plays both appears once,
   *  carrying both -- see `mergeManagers`. */
  sports: Sport[]
  /** Lowercased haystack: everything worth matching against, joined. */
  terms: string[]
}

const SPORTS: Sport[] = ['nfl', 'nba']

function lower(...parts: (string | number | null | undefined)[]): string[] {
  return parts.filter((p): p is string | number => p != null && p !== '').map((p) => String(p).toLowerCase())
}

/**
 * League pages and season boards for every league the user can see.
 *
 * Destinations come from `destinationsFor`, the same table the rail renders --
 * so a basketball league is never offered Analysis here either. Stating that
 * rule twice is how the two would eventually disagree.
 */
export function leagueDestinations(drafts: DraftSummary[]): SearchDestination[] {
  const out: SearchDestination[] = []

  for (const lineage of leagueLineages(drafts)) {
    const current = lineage.current
    const ctx: LeagueContext = { lineage, season: current }

    for (const dest of destinationsFor(ctx)) {
      // "Mock it" opens a modal from the rail rather than going anywhere. A
      // palette result that navigates to "/" and hopes is not a destination.
      if (dest.isAction) continue
      const label = labelOf(dest, current)
      out.push({
        id: `league:${current.sleeperLeagueId}:${dest.key}`,
        kind: 'league-page',
        label,
        context: current.leagueName,
        href: dest.href(ctx),
        sports: [current.sport],
        terms: lower(label, current.leagueName, current.sport, dest.key),
      })
    }

    // Older seasons, each its own board. Only worth listing when there is more
    // than one -- a single-season league's board is already a league page above.
    if (lineage.seasons.length > 1) {
      for (const season of lineage.seasons) {
        out.push({
          id: `season:${season.sleeperLeagueId}`,
          kind: 'season-board',
          label: `${season.season} board`,
          context: current.leagueName,
          href:
            (season.status ?? 'unknown') === 'complete'
              ? `/drafts/${season.sleeperDraftId}/board`
              : `/drafts/${season.sleeperDraftId}`,
          sports: [season.sport],
          terms: lower(season.season, 'board', current.leagueName, season.sport),
        })
      }
    }
  }

  return out
}

/**
 * One row per person, not one per person per sport.
 *
 * `/api/managers` answers per sport, and ten of the twelve Ball Knowers
 * managers are the same Sleeper user in both football and basketball
 * (api.ts:426). Keyed on manager id rather than display name: a name is not an
 * identity, and two people can share one.
 */
export function mergeManagers(bySport: { sport: Sport; managers: ManagerSummary[] }[]): SearchDestination[] {
  const byId = new Map<number, SearchDestination>()

  for (const { sport, managers } of bySport) {
    for (const m of managers) {
      const existing = byId.get(m.managerId)
      if (existing) {
        if (!existing.sports.includes(sport)) existing.sports.push(sport)
        continue
      }
      byId.set(m.managerId, {
        id: `manager:${m.managerId}`,
        kind: 'manager',
        label: m.manager,
        context: 'Manager',
        href: `/managers/${m.managerId}/history`,
        sports: [sport],
        terms: lower(m.manager, 'manager'),
      })
    }
  }

  return [...byId.values()]
}

export function buildSearchIndex(
  drafts: DraftSummary[],
  managersBySport: { sport: Sport; managers: ManagerSummary[] }[],
): SearchDestination[] {
  return [...leagueDestinations(drafts), ...mergeManagers(managersBySport)]
}

/** The sports a full index build asks for. Exported so the caller and the
 *  merge cannot disagree about which ones were fetched. */
export const INDEXED_SPORTS = SPORTS

/*
 * Module-scope cache, the same shape railLeague.ts uses and for the same
 * reason: the palette gets opened repeatedly in one session, and rebuilding
 * the index per open would refetch the manager lists every time.
 */
let cachedIndex: SearchDestination[] | null = null
let inFlight: Promise<SearchDestination[]> | null = null

/** Drops the cached index. Exported for tests and for anything that ingests a
 *  league the index cannot know about. */
export function invalidateSearchIndex() {
  cachedIndex = null
  inFlight = null
}

async function loadIndex(): Promise<SearchDestination[]> {
  const [drafts, ...managerLists] = await Promise.all([
    // Shared with the rail rather than fetched again -- warm for anyone who
    // has loaded Home or any league page, which is the whole of NFR-001.
    cachedDrafts(),
    ...SPORTS.map((sport) => getManagers(sport).then((managers) => ({ sport, managers }))),
  ])
  return buildSearchIndex(drafts, managerLists)
}

/**
 * The index, built once per session and only when something actually asks.
 *
 * `load()` is deliberately separate from mounting: the shell holds this hook on
 * every route, and building the index eagerly would make every page load fetch
 * two manager lists nobody had asked to search yet. The palette calls it the
 * first time it opens.
 */
export function useSearchIndex() {
  const [index, setIndex] = useState<SearchDestination[]>(cachedIndex ?? [])
  const [loading, setLoading] = useState(false)
  const [wanted, setWanted] = useState(false)

  const load = useCallback(() => setWanted(true), [])

  useEffect(() => {
    if (!wanted || cachedIndex) {
      if (cachedIndex) setIndex(cachedIndex)
      return
    }

    let live = true
    setLoading(true)
    if (!inFlight) inFlight = loadIndex()
    inFlight
      .then((rows) => {
        cachedIndex = rows
        if (live) setIndex(rows)
      })
      .catch(() => {
        // An index that failed to build is an empty palette, not a broken
        // page. The rail and every link on the page still work.
        if (live) setIndex([])
      })
      .finally(() => {
        inFlight = null
        if (live) setLoading(false)
      })

    return () => {
      live = false
    }
  }, [wanted])

  return { index, loading, load }
}

/**
 * Ranked matches for `query`.
 *
 * Ranking is deliberately crude and explainable: a term that starts with the
 * query beats one that merely contains it, and league pages outrank managers
 * on an equal footing so that typing a league name surfaces its pages first.
 * An empty query returns everything, which is what makes the palette usable as
 * a plain list before you type.
 */
export function searchDestinations(index: SearchDestination[], query: string): SearchDestination[] {
  const tokens = query.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (tokens.length === 0) return index

  const scored: { row: SearchDestination; score: number }[] = []

  for (const row of index) {
    // Every token must land somewhere, so "west coast history" finds the
    // History page of the West Coast league rather than everything that
    // mentions either word. The tokens are matched independently and in any
    // order -- nobody types a page's name and its league in a fixed one.
    let total = 0
    let matchedAll = true

    for (const token of tokens) {
      let best = 0
      for (const term of row.terms) {
        if (term.startsWith(token)) best = Math.max(best, 3)
        else if (term.includes(token)) best = Math.max(best, 2)
      }
      if (best === 0) {
        matchedAll = false
        break
      }
      total += best
    }

    if (matchedAll) scored.push({ row, score: total })
  }

  return scored
    .sort((a, b) => b.score - a.score || a.row.label.localeCompare(b.row.label))
    .map((s) => s.row)
}
