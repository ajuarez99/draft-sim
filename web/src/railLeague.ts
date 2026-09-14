import { useEffect, useState } from 'react'
import { getDrafts, type DraftSummary } from './api'
import { leagueLineages, type LeagueLineage } from './leagueLineage'

/**
 * Which league, if any, the current URL is inside -- resolved from the path
 * alone so the rail can show league context without every league-scoped page
 * having to hand it over. Six routes share these two shapes
 * (claude/site-wide-shell-propagation.md Phase 3), and portaling the same
 * block from six pages would be six copies of one idea.
 *
 * A hand-written matcher rather than `useParams` because the rail lives
 * OUTSIDE `<Routes>` (AppShell wraps the route table so it survives the
 * Keyed* remounts) -- `useParams` there returns an empty object, since there
 * is no matched route above it to read params from.
 */
export type RailLeagueRef =
  | { kind: 'draft'; sleeperDraftId: string }
  | { kind: 'league'; sleeperLeagueId: string }
  | null

export function leagueRefFromPath(pathname: string): RailLeagueRef {
  const draft = pathname.match(/^\/drafts\/([^/]+)(?:\/(?:board|live))?\/?$/)
  if (draft) return { kind: 'draft', sleeperDraftId: draft[1] }

  const league = pathname.match(/^\/leagues\/([^/]+)\/(?:history|power)(?:\/verify)?\/?$/)
  if (league) return { kind: 'league', sleeperLeagueId: league[1] }

  return null
}

/*
 * One /api/drafts response serves every lookup here -- it carries both keys
 * (sleeperDraftId and sleeperLeagueId) plus the name, sport and size the rail
 * renders. Cached at module scope so walking between a league's board, its
 * history and its power rankings doesn't refetch the same list three times.
 */
let cached: DraftSummary[] | null = null
let inFlight: Promise<DraftSummary[]> | null = null

function loadDrafts(force: boolean): Promise<DraftSummary[]> {
  if (!force && cached) return Promise.resolve(cached)
  if (!inFlight) {
    inFlight = getDrafts()
      .then((d) => {
        cached = d
        return d
      })
      .finally(() => {
        inFlight = null
      })
  }
  return inFlight
}

/** Drops the cache so the next lookup refetches. Called after an ingest adds
 *  a league the cache can't know about. */
export function invalidateRailLeagues() {
  cached = null
}

function findLineage(lineages: LeagueLineage[], ref: NonNullable<RailLeagueRef>): LeagueLineage | null {
  for (const l of lineages) {
    if (ref.kind === 'draft') {
      if (l.seasons.some((s) => s.sleeperDraftId === ref.sleeperDraftId)) return l
    } else if (l.seasons.some((s) => s.sleeperLeagueId === ref.sleeperLeagueId)) return l
  }
  return null
}

export type RailLeague = {
  lineage: LeagueLineage
  /** The season actually being viewed -- not always `lineage.current`, since
   *  an older season's board is its own route. */
  season: DraftSummary
}

export function useRailLeague(pathname: string): RailLeague | null {
  const [value, setValue] = useState<RailLeague | null>(null)

  useEffect(() => {
    const ref = leagueRefFromPath(pathname)
    if (!ref) {
      setValue(null)
      return
    }

    let live = true
    // A miss is the newly-ingested case: the cache predates the league you
    // just navigated into. Refetch once and try again rather than plumbing an
    // invalidation through every screen that can ingest -- and only once, so
    // a genuinely unknown id (a mistyped deep link) can't refetch in a loop.
    async function resolve() {
      for (const force of [false, true]) {
        const drafts = await loadDrafts(force)
        if (!live) return
        const lineages = leagueLineages(drafts)
        const lineage = findLineage(lineages, ref!)
        if (lineage) {
          const season =
            ref!.kind === 'draft'
              ? (lineage.seasons.find((s) => s.sleeperDraftId === ref!.sleeperDraftId) ?? lineage.current)
              : (lineage.seasons.find((s) => s.sleeperLeagueId === ref!.sleeperLeagueId) ?? lineage.current)
          setValue({ lineage, season })
          return
        }
        if (force) break
      }
      if (live) setValue(null)
    }

    // Not cleared first: navigating board -> history within one league would
    // otherwise blink the rail's context empty and back between two renders
    // that show the same league. A genuine change lands when resolve() does.
    resolve().catch(() => {
      if (live) setValue(null)
    })

    return () => {
      live = false
    }
  }, [pathname])

  return value
}
