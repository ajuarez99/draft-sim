import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import TopBar from './TopBar'
import JumpTo from './JumpTo'
import LeagueHeader from './LeagueHeader'
import BottomBar, { moreTabs } from './BottomBar'
import NavSheet from './NavSheet'
import { useNarrow } from '../useNarrow'
import { InsideLeagueContext, PageActionSlotContext, RailContextSlotContext, RailLeagueHintContext } from '../appSlots'
import { destinationsFor, tabFromPath, tabsFor } from '../destinations'
import { acceptsLeagueHint, invalidateRailLeagues, leagueRefFromPath, useAllLeagues, useRailLeague } from '../railLeague'
import { trackLeagueView, trackPageView } from '../analytics'
import { useSearchIndex } from '../searchIndex'
import { clearUser, useUser } from '../user'
import { LeagueDataVersionProvider } from '../leagueDataVersion'
import type { Sport } from '../api'

/**
 * Draft rooms and mocks get the compact chrome: the top bar itself carries the league switcher and
 * the tabs (the global links fold into its menu button) and the Draft sub-tabs stay as a thin row.
 * Not a style preference -- measured (claude/site-wide-shell-propagation.md §B): at a 1440px
 * viewport a 14-team board already overflows its scroller by 99px, and the board needs the height
 * a second bar would spend.
 *
 * `/mock/new` is the seat-setup form, not a room -- it has no board and reads like every other
 * content page, so it keeps the full header.
 *
 * This replaces `railDefaultCollapsed`; the collapsed/expanded override and its `bk-rail`
 * localStorage key went with the rail.
 */
export function isCompactChrome(pathname: string): boolean {
  if (pathname.startsWith('/drafts/')) return true
  return /^\/mock\/[^/]+$/.test(pathname) && pathname !== '/mock/new'
}

/**
 * The app's one shell: a top bar, the league header when the path is inside a league, an optional
 * page-context strip, then a `.app-main` pane that is the only thing that scrolls on a desktop. It
 * replaced a left rail on 2026-11-10 (claude/site-redesign.md Phase 2); the rail itself had been the
 * site-wide layout since claude/site-wide-shell-propagation.md.
 *
 * Mounted OUTSIDE `<Routes>` on purpose, and this is load-bearing rather than
 * incidental: App's four `Keyed*` wrappers force a remount on every draft/
 * session id change so an in-flight resim can't land on the next draft's
 * board. Put the shell inside a route element and it remounts with them,
 * which both kills the chrome's persistence and quietly changes what those keys
 * are actually keying. See App.tsx.
 */
export default function AppShell({ children }: { children: ReactNode }) {
  const user = useUser()
  const location = useLocation()
  const navigate = useNavigate()

  const [pageActionSlot, setPageActionSlot] = useState<HTMLDivElement | null>(null)
  const [contextSlot, setContextSlot] = useState<HTMLDivElement | null>(null)
  const [topbarLeagueSlot, setTopbarLeagueSlot] = useState<HTMLDivElement | null>(null)
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
  // -- the league-scoped routes share two URL shapes, and the header would
  // otherwise need the same block portaled from every one of them. See
  // railLeague.ts and destinations.ts.
  //
  // Spec 052: /api/drafts is scoped to the signed-in user, so a list cached while signed out is
  // wrong the moment someone signs in on a league deep link (and the other way round on sign-out).
  // The cache is dropped when the user id changes -- in an effect declared BEFORE useRailLeague so
  // it runs before its own effect re-resolves on that same id.
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

  // Every league the user can see, for the league header's switcher. Same cache as
  // above, so this costs no request of its own -- and only asked for once we
  // are actually inside a league, since that is the only place a switcher can
  // appear.
  const allLeagues = useAllLeagues(railLeague != null)

  const narrow = useNarrow()
  // Compact needs a league to show: without one the bar has nothing for its middle and would only
  // have hidden the global links for no reason.
  const compact = isCompactChrome(location.pathname) && !narrow && railLeague != null

  // The nav sheet: the top bar's menu button (the global links) or the bottom bar's More (the
  // remaining league tabs above the global links). The trigger is remembered so closing returns
  // focus to it.
  const [sheet, setSheet] = useState<'menu' | 'more' | null>(null)
  const sheetTrigger = useRef<HTMLElement | null>(null)
  const openSheet = useCallback((kind: 'menu' | 'more', trigger: HTMLElement) => {
    sheetTrigger.current = trigger
    setSheet(kind)
  }, [])
  const closeSheet = useCallback((returnFocus = true) => {
    setSheet(null)
    if (returnFocus) sheetTrigger.current?.focus()
  }, [])
  useEffect(() => {
    setSheet(null)
  }, [location.pathname])
  // The sheet is for the phone and for the compact desktop bar; leaving both closes it.
  useEffect(() => {
    if (!narrow && !compact) setSheet(null)
  }, [narrow, compact])

  const mainRef = useRef<HTMLElement>(null)

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
  // already fetched, and the shell has no business refetching it. So this is a
  // navigation carrying a request, not something the shell opens itself:
  // DraftPicker reads the flag off location.state and opens on arrival.
  // Navigating to "/" while already on "/" still produces a fresh location
  // key, which is what makes the request fire from Home too.
  function openMockModal(seed?: { sport: Sport; leagueId: string }) {
    navigate('/', { state: { openMock: true, mockSeed: seed ?? null } })
  }

  // `collapsed` stays in the context value so the hook's shape is unchanged for its callers; with
  // no rail it is always false.
  const contextValue = useMemo(() => ({ node: contextSlot, collapsed: false }), [contextSlot])

  const inLeaguePath = leagueRefFromPath(location.pathname) != null
  const offered = railLeague ? destinationsFor(railLeague) : []
  const leagueTabs = railLeague ? tabsFor(railLeague) : []
  const currentTab = railLeague ? tabFromPath(location.pathname, offered) : null

  // Signed out, SignIn is the whole screen: no top bar, no page chrome, nothing
  // to navigate to. Rendering the bar here would give the sign-in page what
  // looks like working navigation to pages the gate won't serve.
  if (!user) return <>{children}</>

  // The provider wraps the chrome AND the routes: the league header bumps a league's data version
  // when its refresh finishes, and the pages inside <main> read it (specs/009-auto-data-refresh T025).
  return (
    <LeagueDataVersionProvider>
    <div className={`app-shell${narrow && railLeague ? ' has-bottom-bar' : ''}`}>
      {/* First focusable element on the page. Focus is moved by hand: a bare #main link would add a
          hash to the URL under the router. */}
      <a
        href="#main"
        className="skip-link"
        onClick={(e) => {
          e.preventDefault()
          mainRef.current?.focus()
        }}
      >
        Skip to content
      </a>

      <TopBar
        username={user.username}
        displayName={user.displayName}
        avatarId={user.avatar}
        narrow={narrow}
        compact={compact}
        onSignOut={signOut}
        onOpenMockModal={() => openMockModal()}
        onOpenJumpTo={openJumpTo}
        onOpenSheet={(el) => openSheet('menu', el)}
        sheetOpen={sheet === 'menu'}
        managersLeagueId={railLeague?.season.sleeperLeagueId ?? null}
        leagueSlotRef={setTopbarLeagueSlot}
      />

      {railLeague && (
        <LeagueHeader
          league={railLeague}
          pathname={location.pathname}
          search={location.search}
          compact={compact}
          narrow={narrow}
          onMockIt={(leagueId, sport) => openMockModal({ leagueId, sport })}
          allLeagues={allLeagues}
          topbarSlot={topbarLeagueSlot}
        />
      )}

      {/* Page-portaled (appSlots.tsx): the sport filter on the picker and the Scouting report, who
          the manager is on their history page. `:empty` in CSS, so a page that portals nothing
          costs no vertical space. */}
      <div className="context-strip" ref={setContextSlot} />

      <main className="app-main" id="main" tabIndex={-1} ref={mainRef}>
        {/* Page-scoped actions (DraftView's settings gear today). `:empty`
            in CSS, so a page that portals nothing costs no vertical space. */}
        <div className="app-main-head" ref={setPageActionSlot} />
        <PageActionSlotContext.Provider value={pageActionSlot}>
          <RailContextSlotContext.Provider value={contextValue}>
            <RailLeagueHintContext.Provider value={setPageLeagueHint}>
              <InsideLeagueContext.Provider value={inLeaguePath}>{children}</InsideLeagueContext.Provider>
            </RailLeagueHintContext.Provider>
          </RailContextSlotContext.Provider>
        </PageActionSlotContext.Provider>
      </main>

      {/* Phone only, and only inside a league. With the 48px top bar it is the whole permanent
          chrome on a phone (the old header measured 252px of 812 and scrolled away). A child of
          .app-shell, never of .app-main, so scrolling cannot carry it off. */}
      {narrow && railLeague && (
        <BottomBar
          tabs={leagueTabs}
          currentTab={currentTab}
          onOpenSheet={(el) => openSheet('more', el)}
          sheetOpen={sheet === 'more'}
        />
      )}

      {sheet && (
        <NavSheet
          leagueTabs={sheet === 'more' ? moreTabs(leagueTabs) : []}
          currentTab={currentTab}
          name={user.displayName || user.username}
          managersLeagueId={railLeague?.season.sleeperLeagueId ?? null}
          onClose={() => closeSheet()}
          onSignOut={() => {
            closeSheet(false)
            signOut()
          }}
          onOpenMockModal={() => {
            closeSheet(false)
            openMockModal()
          }}
        />
      )}

      {jumpOpen && (
        <JumpTo index={jumpIndex} loading={jumpLoading} onClose={() => setJumpOpen(false)} />
      )}
    </div>
    </LeagueDataVersionProvider>
  )
}
