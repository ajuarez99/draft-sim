import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import Rail from './Rail'
import { PageActionSlotContext, RailContextSlotContext } from '../appSlots'
import { clearUser, useUser } from '../user'

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

  // null = follow the route's own default. Once someone has chosen, the
  // choice sticks across routes and reloads -- a 13" laptop wants the icon
  // rail everywhere, a 27" monitor wants the full one even in a draft room.
  const [override, setOverride] = useState<'expanded' | 'collapsed' | null>(readOverride)

  const collapsed = override ? override === 'collapsed' : railDefaultCollapsed(location.pathname)

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

  // The new-mock modal lives on Home -- it needs the league list Home has
  // already fetched, and the rail has no business refetching it. So this is a
  // navigation carrying a request, not something the rail opens itself:
  // DraftPicker reads the flag off location.state and opens on arrival.
  // Navigating to "/" while already on "/" still produces a fresh location
  // key, which is what makes the request fire from Home too.
  function openMockModal() {
    navigate('/', { state: { openMock: true } })
  }

  const railContextValue = useMemo(
    () => ({ node: railContextSlot, collapsed }),
    [railContextSlot, collapsed],
  )

  // Signed out, SignIn is the whole screen: no rail, no page chrome, nothing
  // to navigate to. Rendering the rail here would give the sign-in page what
  // looks like working navigation to pages the gate won't serve.
  if (!user) return <>{children}</>

  return (
    <div className="app-shell">
      <Rail
        username={user.username}
        displayName={user.displayName}
        avatarId={user.avatar}
        collapsed={collapsed}
        onToggleCollapsed={toggleCollapsed}
        onSignOut={signOut}
        onOpenMockModal={openMockModal}
        contextSlotRef={setRailContextSlot}
      />

      <main className="app-main">
        {/* Page-scoped actions (DraftView's settings gear today). `:empty`
            in CSS, so a page that portals nothing costs no vertical space. */}
        <div className="app-main-head" ref={setPageActionSlot} />
        <PageActionSlotContext.Provider value={pageActionSlot}>
          <RailContextSlotContext.Provider value={railContextValue}>
            {children}
          </RailContextSlotContext.Provider>
        </PageActionSlotContext.Provider>
      </main>
    </div>
  )
}
