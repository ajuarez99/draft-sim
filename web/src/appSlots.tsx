import { createContext, useContext, useEffect } from 'react'

/*
 * Two DOM nodes inside AppShell -- the chrome that survives across every page
 * and isn't part of any single route's own content. Pages portal into them
 * rather than each growing a toolbar of its own.
 *
 * Replaces topSlot.tsx, whose single target lived inside the old `.top`
 * header. That header is gone (claude/site-wide-shell-propagation.md §C), so
 * its one consumer -- DraftView's settings gear -- now portals into
 * `PageActionSlotContext` instead.
 */

/**
 * The right-hand action row at the top of `.app-main`. Page-scoped controls
 * that shouldn't cost the page a toolbar go here: DraftView's simulation-
 * settings gear is the only one today. The row is `:empty { display: none }`,
 * so a page that portals nothing costs no vertical space at all.
 *
 * §C step 3: deliberately NOT the rail. The rail is global navigation; the
 * gear belongs to one draft room. Phase 4 folds this row into `PageHeader`'s
 * action area, which is the same position with a title beside it.
 */
export const PageActionSlotContext = createContext<HTMLDivElement | null>(null)

export function usePageActionSlot() {
  return useContext(PageActionSlotContext)
}

/**
 * The page-context strip, under the top bar (and under the league header when there is one): what
 * the *current page* wants to say in the global chrome. The picker and the Scouting report fill it
 * with the sport filter; a manager's history page fills it with who the manager is. A page that
 * portals nothing leaves no strip at all (`:empty` in CSS).
 *
 * It used to be the left rail's middle region. The hook keeps its name and shape so its three
 * callers compile; `collapsed` has no meaning without a rail and is always false.
 */
export type RailContext = { node: HTMLDivElement | null; collapsed: boolean }

export const RailContextSlotContext = createContext<RailContext>({ node: null, collapsed: false })

export function useRailContextSlot() {
  return useContext(RailContextSlotContext)
}

/**
 * How a page tells the rail which league it is inside, when the URL cannot.
 *
 * Only one route needs it: a mock session knows its seeding league from its
 * own fetched state (`sourceSleeperLeagueId`), and nothing in `/mock/:id`
 * carries that league. Manager history takes the other route -- it is reached
 * by a link, so its league rides along in route state and AppShell reads it
 * without the page's help.
 *
 * Data rather than DOM, which is why this is a context and not a portal slot
 * like the two above.
 */
export const RailLeagueHintContext = createContext<(sleeperLeagueId: string | null) => void>(() => {})

/**
 * Publishes this page's league to the rail for as long as the page is mounted.
 *
 * Clears on unmount, so a mock's league cannot follow you to the next page --
 * `acceptsLeagueHint` also refuses the hint on any route that isn't entitled
 * to one, which is the belt to this braces.
 */
export function useRailLeagueHint(sleeperLeagueId: string | null | undefined) {
  const publish = useContext(RailLeagueHintContext)
  useEffect(() => {
    publish(sleeperLeagueId ?? null)
    return () => publish(null)
  }, [publish, sleeperLeagueId])
}

/**
 * True when the current path is inside a league (the same detection the shell uses for the league
 * header: railLeague.ts's leagueRefFromPath). PageHeader reads it to drop its eyebrow there, since the
 * league header already says which league and season.
 *
 * A context rather than a router hook because PageHeader sits under 25 pages whose tests render it
 * without a router, or with a partial `react-router-dom` mock. The default is false, which is the old
 * behaviour: outside the shell the eyebrow renders as it always did.
 */
export const InsideLeagueContext = createContext(false)

export function useInsideLeague() {
  return useContext(InsideLeagueContext)
}
