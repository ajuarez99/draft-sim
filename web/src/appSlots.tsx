import { createContext, useContext } from 'react'

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
 * The rail's context region, between the wordmark and the Menu rows -- what
 * the *current page* wants to say in the global rail. Home fills it with the
 * sport filter; §C Phase 3 fills it with league context on league-scoped
 * routes. A page that portals nothing just leaves the rail shorter.
 *
 * `collapsed` rides along because the portaled content has to render
 * differently in the icon rail (labels and counts hidden, dots kept) and a
 * portal can't read a CSS class on its target's ancestor.
 */
export type RailContext = { node: HTMLDivElement | null; collapsed: boolean }

export const RailContextSlotContext = createContext<RailContext>({ node: null, collapsed: false })

export function useRailContextSlot() {
  return useContext(RailContextSlotContext)
}
