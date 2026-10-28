import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import Rail from './Rail'
import JumpTo from './JumpTo'
import LeagueRailSection from './LeagueRailSection'
import { useNarrow } from '../useNarrow'
import { PageActionSlotContext, RailContextSlotContext, RailLeagueHintContext } from '../appSlots'
import { acceptsLeagueHint, invalidateRailLeagues, leagueRefFromPath, useAllLeagues, useRailLeague } from '../railLeague'
import { trackLeagueView, trackPageView } from '../analytics'
import { useSearchIndex } from '../searchIndex'
import { clearUser, useUser } from '../user'
import { LeagueDataVersionProvider } from '../leagueDataVersion'
import type { Sport } from '../api'

const RAIL_KEY = 'bk-rail'

/**
 * Draft rooms open with the icon rail; everything else opens with the full
 * one. Not a style preference -- measured (claude/site-wide-shell-
 * propagation.md §B): at a 1440px viewport a 14-team board already overflows
 * its scroller by 99px with no rail at all, and one column is 96px, so a
 * 200px rail would push two more columns off a laptop screen.
 *
 * `/mock/new` is the seat-setup form, not a room -- it has no board and reads
 * like every other content page, so it keeps the full rail.
 */
export function railDefaultCollapsed(pathname: string): boolean {
  if (pathname.startsWith('/drafts/')) return true
  return /^\/mock\/[^/]+$/.test(pathname) && pathname !== '/mock/new'
}

function readOverride(): 'expanded' | 'collapsed' | null {
  try {
    const saved = localStorage.getItem(RAIL_KEY)
    return saved === 'expanded' || saved === 'collapsed' ? saved : null
  } catch {
    return null
  }
}

/**
 * The app's one shell: the global rail beside a `.app-main` pane that scrolls
 * on its own. Shipped in `0c6f0ab` as `.home-shell`, scoped to "/" only with
 * every other route still on the old horizontal `.top` header; this is that
 * layout promoted to the whole site (claude/site-wide-shell-propagation.md).
 *
 * Mounted OUTSIDE `<Routes>` on purpose, and this is load-bearing rather than
 * incidental: App's four `Keyed*` wrappers force a remount on every draft/
 * session id change so an in-flight resim can't land on the next draft's
 * board. Put the shell inside a route element and it remounts with them,
 * which both kills the rail's persistence and quietly changes what those keys
 * are actually keying. See App.tsx.
 */
export default function AppShell({ children }: { children: ReactNode }) {
  const user = useUser()
  const location = useLocation()
  const navigate = useNavigate()

  const [pageActionSlot, setPageActionSlot] = useState<HTMLDivElement | null>(null)
  const [railContextSlot, setRailContextSlot] = useState<HTMLDivElement | null>(null)
  const [jumpOpen, setJumpOpen] = useState(false)
  const { index: jumpIndex, loading: jumpLoading, load: loadJumpIndex } = useSearchIndex()

  // Published by a page that knows its league when the URL doesn't -- only
  // MockDraftView today, from the session's own sourceSleeperLeagueId.
  const [pageLeagueHint, setPageLeagueHint] = useState<string | null>(null)

  // Manager history takes the other path: it is always arrived at by a link
  // from a league's standings, so the league rides in route state and needs
  // nothing from the page. A direct visit has no state and gets no league,
  // which is the honest answer rather than a guess.
  const stateLeagueHint =
    (location.state as { railLeagueId?: string } | null)?.railLeagueId ?? null

  // Resolved from the path where the path can say, from a hint where it can't
  // -- the league-scoped routes share two URL shapes, and the rail would
  // otherwise need the same block portaled from every one of them. See
  // railLeague.ts and destinations.ts.
  //
  // Spec 052: /api/drafts is scoped to the signed-in user, so a list cached while signed out is
  // wrong the moment someone signs in on a league deep link (and the other way round on sign-out).
  // The cache is dropped when the user id changes -- in an effect declared BEFORE useRailLeague so
  // it runs before the rail's own effect re-resolves on that same id.
  const userId = user?.sleeperUserId ?? null
  const lastUserId = useRef(userId)
  useEffect(() => {
    if (lastUserId.current === userId) return
    lastUserId.current = userId
    invalidateRailLeagues()
  }, [userId])
  const railLeague = useRailLeague(location.pathname, pageLeagueHint ?? stateLeagueHint, userId)

  // Spec 052: one page view per route change, signed in or out. This effect lives up here, above
  // the signed-out early return below, because signed-out visitors are the ones arriving from a
  // shared link and need counting too. Keyed on signedIn as well as the path: signing in changes
  // the page without changing the pathname.
  //
  // ORDER IS LOAD-BEARING: this effect must stay declared BEFORE the league-view effect below.
  // React runs effects in declaration order, and trackPageView re-arms the league-view latch
  // (analytics.ts) -- the reset has to land before the league view is sent on the same render.
  const signedIn = user != null
  useEffect(() => {
    trackPageView(location.pathname, signedIn)
  }, [signedIn, location.pathname])

  // Spec 052: which league a league-scoped page was for. railLeague deliberately does not clear
  // on navigation (railLeague.ts), so after A -> B it still holds A's league until B resolves.
  // Send only when the resolved season is the one THIS path expects -- by the path's own id, or
  // by the hint on the routes that take one -- and only signed in (signed-out pages send none).
  const hintForPath = pageLeagueHint ?? stateLeagueHint
  useEffect(() => {
    if (!signedIn || !railLeague) return
    const fromPath = leagueRefFromPath(location.pathname)
    const expected =
      fromPath ??
      (hintForPath && acceptsLeagueHint(location.pathname)
        ? ({ kind: 'league', sleeperLeagueId: hintForPath } as const)
        : null)
    if (!expected) return
    const matches =
      expected.kind === 'draft'
        ? railLeague.season.sleeperDraftId === expected.sleeperDraftId
        : railLeague.season.sleeperLeagueId === expected.sleeperLeagueId
    if (!matches) return
    trackLeagueView(
      location.pathname,
      signedIn,
      railLeague.lineage.current.sport,
      railLeague.lineage.current.sleeperLeagueId,
    )
  }, [railLeague, signedIn, location.pathname, hintForPath])

  // Every league the user can see, for the rail's switcher. Same cache as
  // above, so this costs no request of its own -- and only asked for once we
  // are actually inside a league, since that is the only place a switcher can
  // appear.
  const allLeagues = useAllLeagues(railLeague != null)

  // null = follow the route's own default. Once someone has chosen, the
  // choice sticks across routes and reloads -- a 13" laptop wants the icon
  // rail everywhere, a 27" monitor wants the full one even in a draft room.
  const [override, setOverride] = useState<'expanded' | 'collapsed' | null>(readOverride)

  const narrow = useNarrow()
  const collapsed =
    narrow ? false : override ? override === 'collapsed' : railDefaultCollapsed(location.pathname)

  const toggleCollapsed = useCallback(() => {
    const next = collapsed ? 'expanded' : 'collapsed'
    setOverride(next)
    try {
      localStorage.setItem(RAIL_KEY, next)
    } catch {
      // Private-mode/blocked storage: the toggle still works for this visit.
    }
  }, [collapsed])

  function signOut() {
    clearUser()
    navigate('/', { replace: true })
  }

  const openJumpTo = useCallback(() => {
    loadJumpIndex()
    setJumpOpen(true)
  }, [loadJumpIndex])

  /*
   * Ctrl/Cmd+K, globally.
   *
   * Deliberately not a bare `/`: draft rooms have their own player search and
   * several pages have text inputs, and a bare key would fight every one of
   * them. The modifier is what makes this safe to bind at document level.
   *
   * Bound while signed in only -- the hook runs before the signed-out early
   * return below, so it checks `user` itself rather than relying on position.
   */
  useEffect(() => {
    if (!user) return
    function onKey(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        openJumpTo()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [user, openJumpTo])

  // The new-mock modal lives on Home -- it needs the league list Home has
  // already fetched, and the rail has no business refetching it. So this is a
  // navigation carrying a request, not something the rail opens itself:
  // DraftPicker reads the flag off location.state and opens on arrival.
  // Navigating to "/" while already on "/" still produces a fresh location
  // key, which is what makes the request fire from Home too.
  function openMockModal(seed?: { sport: Sport; leagueId: string }) {
    navigate('/', { state: { openMock: true, mockSeed: seed ?? null } })
  }

  const railContextValue = useMemo(
    () => ({ node: railContextSlot, collapsed }),
    [railContextSlot, collapsed],
  )

  // Signed out, SignIn is the whole screen: no rail, no page chrome, nothing
  // to navigate to. Rendering the rail here would give the sign-in page what
  // looks like working navigation to pages the gate won't serve.
  if (!user) return <>{children}</>

  // The provider wraps the rail AND the routes: the rail bumps a league's data
  // version when its refresh finishes, and the pages inside <main> read it
  // (specs/009-auto-data-refresh T025).
  return (
    <LeagueDataVersionProvider>
    <div className="app-shell">
      <Rail
        username={user.username}
        displayName={user.displayName}
        avatarId={user.avatar}
        collapsed={collapsed}
        onToggleCollapsed={toggleCollapsed}
        onSignOut={signOut}
        onOpenMockModal={openMockModal}
        onOpenJumpTo={openJumpTo}
        contextSlotRef={setRailContextSlot}
        managersLeagueId={railLeague?.season.sleeperLeagueId ?? null}
      >
        {railLeague && (
          <LeagueRailSection
            league={railLeague}
            pathname={location.pathname}
            search={location.search}
            collapsed={collapsed}
            onMockIt={(leagueId, sport) => openMockModal({ leagueId, sport })}
            allLeagues={allLeagues}
            onOpenJumpTo={openJumpTo}
          />
        )}
      </Rail>

      <main className="app-main">
        {/* Page-scoped actions (DraftView's settings gear today). `:empty`
            in CSS, so a page that portals nothing costs no vertical space. */}
        <div className="app-main-head" ref={setPageActionSlot} />
        <PageActionSlotContext.Provider value={pageActionSlot}>
          <RailContextSlotContext.Provider value={railContextValue}>
            <RailLeagueHintContext.Provider value={setPageLeagueHint}>
              {children}
            </RailLeagueHintContext.Provider>
          </RailContextSlotContext.Provider>
        </PageActionSlotContext.Provider>
      </main>

      {/* Phone-only way back to navigation. The bar itself scrolls away with
          the page on purpose -- pinning it measured 252px of an 812px viewport
          (styles.css, the 860px block), and a third of the screen spent
          permanently on navigation is worse than navigation you scroll up to
          reach. This costs a button and solves only the return trip. */}
      <button
        type="button"
        className="jumpto-fab"
        onClick={openJumpTo}
        aria-label="Jump to a league, page or manager"
        title="Jump to"
      >
        <span aria-hidden="true">⌕</span>
      </button>

      {jumpOpen && (
        <JumpTo index={jumpIndex} loading={jumpLoading} onClose={() => setJumpOpen(false)} />
      )}
    </div>
    </LeagueDataVersionProvider>
  )
}
