import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import Avatar from './Avatar'

/** Same breakpoint as styles.css's phone-chrome block. Sign out lives behind the avatar at or below it. */
const PHONE_QUERY = '(max-width: 700px)'

function useIsPhone(): boolean {
  const [phone, setPhone] = useState(() => window.matchMedia?.(PHONE_QUERY).matches ?? false)
  useEffect(() => {
    const mq = window.matchMedia?.(PHONE_QUERY)
    if (!mq) return
    const onChange = () => setPhone(mq.matches)
    onChange()
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])
  return phone
}

type Props = {
  username: string
  displayName: string | null
  avatarId: string | null
  collapsed: boolean
  onToggleCollapsed: () => void
  onSignOut: () => void
  /** "Mock drafts" is a menu row that opens the new-mock modal rather than a
   *  route (design_handoff_multisport_mock_drafts/README.md's sidebar spec).
   *  The modal itself lives on Home, so from anywhere else this routes to
   *  Home and asks it to open -- see AppShell. */
  onOpenMockModal: () => void
  /** Opens the jump-to palette. A visible control as well as a shortcut: a
   *  feature only reachable by Ctrl/Cmd+K is a feature most people never learn
   *  they have. */
  onOpenJumpTo: () => void
  /** Filled by AppShell with the ref callback for the page-context region.
   *  See appSlots.tsx: the current page portals into it. */
  contextSlotRef: (el: HTMLDivElement | null) => void
  /** The league the rail is showing right now (railLeague.ts), if any. Carried to /managers in route state so the Scouting report can list that league's managers first -- the same channel manager history already uses to learn its league. Null when the rail shows no league. */
  managersLeagueId?: string | null
  /** Context AppShell can render itself because it is derivable from the URL
   *  -- league context today (railLeague.ts). A sibling of the portal target
   *  rather than its content: React owns the portal node's children, so
   *  rendering into it here would fight the portal for it. The two never
   *  apply at once anyway -- Home is not a league route. */
  children?: ReactNode
}

/**
 * The app's global left rail -- wordmark, a page-supplied context region,
 * Menu, account. Shipped Home-only in `0c6f0ab` as `Sidebar`; this is the
 * site-wide version (claude/site-wide-shell-propagation.md §C).
 *
 * Two things changed in the promotion, both because it is no longer allowed
 * to assume which page it is on:
 *
 *   1. The current Menu row is derived from `useLocation()` rather than
 *      hardcoded to Home.
 *   2. The Sports filter moved out. It was a home-only control living in
 *      global chrome; it now portals in through `contextSlotRef` from
 *      whichever page has something to say there.
 *
 * `collapsed` is the 56px icon rail. Draft rooms default to it because they
 * genuinely have no horizontal room to give (§B: the board already overflows
 * a 1440px viewport by 99px with no rail at all, and a column is 96px). Row
 * labels are wrapped in their own span so the icon rail can hide the text and
 * keep the dot without the markup changing shape.
 */
export default function Rail({
  username,
  displayName,
  avatarId,
  collapsed,
  onToggleCollapsed,
  onSignOut,
  onOpenMockModal,
  onOpenJumpTo,
  contextSlotRef,
  managersLeagueId = null,
  children,
}: Props) {
  const location = useLocation()
  const name = displayName || username
  const phone = useIsPhone()
  const [accountOpen, setAccountOpen] = useState(false)
  const accountRef = useRef<HTMLDivElement>(null)

  // A menu, not a mode: navigating, Escape or a tap elsewhere closes it.
  useEffect(() => {
    setAccountOpen(false)
  }, [location.pathname])
  useEffect(() => {
    if (!accountOpen) return
    function onDown(e: MouseEvent) {
      if (!accountRef.current?.contains(e.target as Node)) setAccountOpen(false)
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setAccountOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [accountOpen])

  // `.startsWith` on Managers so its own sub-route (/managers/:id/history)
  // still marks the row current, the same way a browser tab would -- carried
  // over from the header nav this replaces.
  const onHome = location.pathname === '/'
  const onManagers = location.pathname.startsWith('/managers')

  return (
    <nav className={`app-rail${collapsed ? ' collapsed' : ''}`} aria-label="Main">
      <div className="app-rail-top">
        <Link to="/" className="app-wordmark cond" aria-label="Ball Knowers — home">
          {collapsed ? (
            <span className="app-wordmark-mark" aria-hidden="true">
              BK
            </span>
          ) : (
            // Two spans rather than a <br>: the horizontal mobile bar sets
            // them `display: inline` so the wordmark costs one line instead of
            // two, and the whitespace between them only renders in that state.
            // A <br> can't be un-broken by CSS, and dropping it would join the
            // words into "BallKnowers".
            <>
              <span className="app-wordmark-line">Ball</span>{' '}
              <span className="app-wordmark-line">Knowers</span>
            </>
          )}
        </Link>
        <button
          type="button"
          className="app-rail-toggle"
          onClick={onToggleCollapsed}
          aria-expanded={!collapsed}
          aria-label={collapsed ? 'Expand navigation' : 'Collapse navigation'}
          title={collapsed ? 'Expand navigation' : 'Collapse navigation'}
        >
          {collapsed ? '»' : '«'}
        </button>
      </div>

      {/* Above the league block and the Menu rows both, because it reaches
          past either of them -- every league, every page, every manager. */}
      <button type="button" className="rail-jumpto" onClick={onOpenJumpTo} title="Jump to (Ctrl+K)">
        <span className="rail-jumpto-glyph" aria-hidden="true">
          ⌕
        </span>
        <span className="rail-jumpto-text app-rail-row-label">
          Jump to<kbd className="rail-jumpto-kbd">Ctrl K</kbd>
        </span>
      </button>

      {/* URL-derived (league context). */}
      {children}

      {/* Page-portaled. Empty on most routes, and `:empty` collapses it so
          those routes don't spend the rail's gap on a hole. */}
      <div className="app-rail-context" ref={contextSlotRef} />

      <div className="app-rail-section">
        <span className="app-rail-label">Menu</span>
        <Link to="/" className={`app-rail-row${onHome ? ' on' : ''}`} title="Home" aria-label="Home">
          <span className="app-rail-glyph" aria-hidden="true">
            ◆
          </span>
          <span className="app-rail-row-label">Home</span>
        </Link>
        <Link
          to="/managers"
          state={managersLeagueId ? { railLeagueId: managersLeagueId } : undefined}
          className={`app-rail-row${onManagers ? ' on' : ''}`}
          title="Managers"
          aria-label="Managers"
        >
          <span className="app-rail-glyph" aria-hidden="true">
            ●
          </span>
          <span className="app-rail-row-label">Managers</span>
        </Link>
        <button
          type="button"
          className="app-rail-row"
          onClick={() => onOpenMockModal()}
          title="Mock drafts"
          aria-label="Mock drafts"
        >
          <span className="app-rail-glyph" aria-hidden="true">
            ▶
          </span>
          <span className="app-rail-row-label">Mock drafts</span>
        </button>
      </div>

      <div className="app-rail-spacer" />

      {phone ? (
        // Phone: Sign out is not a control of its own. Next to Home / Managers
        // / Mock drafts a one-tap ⏻ signed you out by accident; behind the
        // avatar it takes a deliberate second tap.
        <div className="app-account-wrap" ref={accountRef}>
          <button
            type="button"
            className="app-account-button"
            onClick={() => setAccountOpen((v) => !v)}
            aria-haspopup="menu"
            aria-expanded={accountOpen}
            aria-label={`Account: ${name}`}
            title={name}
          >
            <Avatar avatarId={avatarId} seed={username} label={name} />
          </button>
          {accountOpen && (
            <div className="app-account-menu" role="menu">
              <span className="app-account-menu-name">{name}</span>
              <button
                type="button"
                role="menuitem"
                className="app-account-menu-item"
                onClick={() => {
                  setAccountOpen(false)
                  onSignOut()
                }}
              >
                Sign out
              </button>
            </div>
          )}
        </div>
      ) : (
        <>
          <div className="app-account" title={name}>
            <Avatar avatarId={avatarId} seed={username} label={name} />
            <span className="app-account-name app-rail-row-label">{name}</span>
          </div>
          <button type="button" className="app-signout" onClick={onSignOut} title="Sign out" aria-label="Sign out">
            <span className="app-rail-row-label">Sign out</span>
            <span className="app-signout-glyph" aria-hidden="true">
              ⏻
            </span>
          </button>
        </>
      )}
    </nav>
  )
}
