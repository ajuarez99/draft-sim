import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { getDrafts, type DraftSummary } from './api'
import { leagueLineages, type LeagueLineage } from './leagueLineage'
import { leagueIdFromPath } from './destinations'

/**
 * Which league, if any, the current URL is inside -- resolved from the path
 * alone so the rail can show league context without every league-scoped page
 * having to hand it over. Several routes share two URL shapes
 * (claude/site-wide-shell-propagation.md Phase 3), and portaling the same
 * block from each page would be one idea copied many times.
 *
 * The patterns now come from destinations.ts rather than being written out
 * here. They used to be a literal alternation listing `history|power`, which
 * silently omitted `/leagues/:id/analysis` -- so the rail linked you to
 * Analysis and then, not recognising the route it had just sent you to,
 * deleted the League section that linked you. One list, one place.
 *
 * Still a hand-written matcher rather than `useParams`, because the rail lives
 * OUTSIDE `<Routes>` (AppShell wraps the route table so it survives the
 * Keyed* remounts) -- `useParams` there returns an empty object, since there
 * is no matched route above it to read params from.
 */
export type RailLeagueRef =
  | { kind: 'draft'; sleeperDraftId: string }
  | { kind: 'league'; sleeperLeagueId: string }
  | null

export function leagueRefFromPath(pathname: string): RailLeagueRef {
  const hit = leagueIdFromPath(pathname)
  if (!hit) return null
  return hit.idKind === 'draft'
    ? { kind: 'draft', sleeperDraftId: hit.id }
    : { kind: 'league', sleeperLeagueId: hit.id }
}

/**
 * Routes that belong to a league but cannot prove it from the URL.
 *
 * A manager's history page is reached by clicking a name in a league's
 * standings, and a mock can be seeded from a league -- in both cases the
 * league is real, and in neither case is it in the path. They accept a hint
 * from the navigation that got you there (route state) or from the page's own
 * data (the mock session's source league).
 *
 * Best-effort by design: a direct visit to a manager's history with no
 * referring league genuinely has no league, and the rail must then render no
 * League section rather than guess one.
 *
 * `/mock/new` is excluded deliberately -- it is the seat-setup form, not a
 * room, and it has no session to take a league from. Same carve-out
 * railDefaultCollapsed makes for the same reason.
 */
/**
 * Route state that tells the page you are navigating TO which league you came
 * from. Read back by AppShell as the rail's hint.
 *
 * A hook rather than a prop because the links that need it sit in small
 * presentational components several levels below the page that knows the
 * league -- and unlike the rail, those components ARE inside `<Routes>`, so
 * `useParams` works for them. Threading one string through four call sites
 * would be the kind of copy this feature exists to stop making.
 */
export function useLeagueLinkState(): { railLeagueId: string } | undefined {
  const { sleeperLeagueId } = useParams<{ sleeperLeagueId: string }>()
  return sleeperLeagueId ? { railLeagueId: sleeperLeagueId } : undefined
}

export function acceptsLeagueHint(pathname: string): boolean {
  if (/^\/managers\/[^/]+\/history\/?$/.test(pathname)) return true
  return /^\/mock\/[^/]+\/?$/.test(pathname) && !/^\/mock\/new\/?$/.test(pathname)
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

/**
 * The draft list the rail already holds, cache and all.
 *
 * Exported so the jump-to palette reads the same one instead of fetching an
 * identical list of its own. Anyone who has loaded Home or any league page has
 * it warm already, which is what makes opening the palette cost no request.
 */
export function cachedDrafts(): Promise<DraftSummary[]> {
  return loadDrafts(false)
}

/**
 * Every league the signed-in user can see, grouped into seasons.
 *
 * Off the same cache as everything else here, so the switcher costs no request
 * of its own. Empty until it resolves, which the rail reads as "no switcher
 * yet" rather than "no other leagues".
 *
 * `enabled` is not an optimisation: the switcher only exists inside a league,
 * so asking on Home or /managers would fetch a list for a control that cannot
 * appear there. On a warm cache it is free either way, but a cold visit
 * straight to a non-league route shouldn't pay for it.
 */
export function useAllLeagues(enabled: boolean): LeagueLineage[] {
  const [value, setValue] = useState<LeagueLineage[]>([])

  useEffect(() => {
    if (!enabled) return
    let live = true

    // Inside an async function so a synchronous throw from the fetch layer
    // becomes a rejected promise rather than escaping the effect. It does
    // throw synchronously under a partial module mock, and an uncaught throw
    // here would take the whole shell down with it.
    async function resolve() {
      const drafts = await cachedDrafts()
      if (live) setValue(leagueLineages(drafts))
    }

    resolve().catch(() => {
      // A switcher that cannot list leagues simply doesn't appear. Every other
      // way out of the page still works.
      if (live) setValue([])
    })

    return () => {
      live = false
    }
  }, [enabled])

  return value
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

/**
 * The league the rail should show for `pathname`.
 *
 * `hintLeagueId` covers the routes that carry their league out of band --
 * a manager history page reached from a league's standings, and a mock seeded
 * from a league. It is consulted only when the path itself yields nothing AND
 * the route is one that accepts a hint, so a hint left over from a previous
 * page cannot put a League section on a route that has no business with one.
 */
export function useRailLeague(pathname: string, hintLeagueId?: string | null): RailLeague | null {
  const [value, setValue] = useState<RailLeague | null>(null)

  useEffect(() => {
    const fromPath = leagueRefFromPath(pathname)
    const ref: RailLeagueRef =
      fromPath ??
      (hintLeagueId && acceptsLeagueHint(pathname)
        ? { kind: 'league', sleeperLeagueId: hintLeagueId }
        : null)
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
  }, [pathname, hintLeagueId])

  return value
}
