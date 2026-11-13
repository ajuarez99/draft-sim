import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import Avatar from './Avatar'
import { usePopup } from '../popupFocus'

type Props = {
  username: string
  displayName: string | null
  avatarId: string | null
  /** At or below the phone breakpoint (useNarrow): the global links leave the bar for the menu sheet. */
  narrow: boolean
  /** A draft room or mock on a desktop: the bar also carries the league switcher and the tabs, so
   *  the global links collapse into the menu button too. */
  compact: boolean
  onSignOut: () => void
  /** "Mock drafts" opens the new-mock modal rather than going to a route. The modal itself lives on
   *  Home, so from anywhere else this routes there and asks it to open -- see AppShell. */
  onOpenMockModal: () => void
  /** Opens the jump-to palette. A visible control as well as a shortcut: a feature only reachable
   *  by Ctrl/Cmd+K is a feature most people never learn they have. */
  onOpenJumpTo: () => void
  /** Opens the nav sheet; the trigger is handed over so the sheet can return focus to it. */
  onOpenSheet: (trigger: HTMLElement) => void
  sheetOpen: boolean
  /** The league the shell is showing (railLeague.ts), if any. Carried to /managers in route state so
   *  the Scouting report can list that league's managers first -- the same channel manager history
   *  already uses to learn its league. */
  managersLeagueId?: string | null
  /** Ref callback for the compact league bar's mount point (LeagueHeader portals into it). */
  leagueSlotRef: (el: HTMLDivElement | null) => void
}

function SearchIcon() {
  return (
    <svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true" focusable="false">
      <circle cx="9" cy="9" r="5.5" />
      <path d="M13.2 13.2 17 17" />
    </svg>
  )
}

function MenuIcon() {
  return (
    <svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true" focusable="false">
      <path d="M3 6h14M3 10h14M3 14h14" />
    </svg>
  )
}

/**
 * The site's top bar: wordmark, the global links, search, the account menu. It replaces the left
 * rail (claude/site-redesign.md Phase 2, which reverses the 2026-09-14 "the rail is the site's
 * navigation" call). Text labels only; the three icons are inline SVG and always sit beside an
 * accessible name.
 *
 * Three shapes, one component:
 *   - desktop: wordmark, Leagues / Mock drafts / Managers / Time Machine, search, account;
 *   - compact (draft room or mock, desktop): the league switcher and tabs take the middle of the
 *     bar, the global links fold into the menu button -- a board has no vertical room for a second
 *     bar (plan review PR-02);
 *   - phone: wordmark, search, menu button, account. Outside a league this menu is the ONLY way to
 *     Managers, Time Machine and Mock drafts (plan review PR-01).
 */
export default function TopBar({
  username,
  displayName,
  avatarId,
  narrow,
  compact,
  onSignOut,
  onOpenMockModal,
  onOpenJumpTo,
  onOpenSheet,
  sheetOpen,
  managersLeagueId = null,
  leagueSlotRef,
}: Props) {
  const location = useLocation()
  const name = displayName || username
  const [accountOpen, setAccountOpen] = useState(false)
  const accountMenu = useRef<HTMLDivElement>(null)
  const accountBtn = useRef<HTMLButtonElement>(null)
  const menuBtn = useRef<HTMLButtonElement>(null)

  // A menu, not a mode: navigating closes it.
  useEffect(() => {
    setAccountOpen(false)
  }, [location.pathname])
  usePopup(accountOpen, () => setAccountOpen(false), accountMenu, accountBtn)

  // `.startsWith` on Managers so its own sub-route (/managers/:id/history) still marks the link
  // current, the same way a browser tab would.
  const path = location.pathname
  const here = (on: boolean) => (on ? ({ 'aria-current': 'page' } as const) : {})
  const showLinks = !narrow && !compact
  const showMenu = narrow || compact

  return (
    <header className={`topbar${compact ? ' compact' : ''}`}>
      <Link to="/" className="topbar-wordmark cond" aria-label="Ball Knowers — home">
        Ball Knowers
      </Link>

      {showLinks && (
        <nav className="topbar-links" aria-label="Main">
          <Link to="/" className="topbar-link" {...here(path === '/')}>
            Leagues
          </Link>
          <button type="button" className="topbar-link" onClick={() => onOpenMockModal()}>
            Mock drafts
          </button>
          <Link
            to="/managers"
            state={managersLeagueId ? { railLeagueId: managersLeagueId } : undefined}
            className="topbar-link"
            {...here(path.startsWith('/managers'))}
          >
            Managers
          </Link>
          <Link to="/history" className="topbar-link" {...here(path.startsWith('/history'))}>
            Time Machine
          </Link>
        </nav>
      )}

      {/* Compact only: LeagueHeader portals the league identity, switcher and tabs here. */}
      {compact && !narrow && <div className="topbar-league" ref={leagueSlotRef} />}

      <div className="topbar-actions">
        <button
          type="button"
          className="topbar-icon-button"
          onClick={onOpenJumpTo}
          aria-label="Jump to a league, page or manager"
          title="Jump to (Ctrl+K)"
        >
          <SearchIcon />
        </button>

        {showMenu && (
          <button
            type="button"
            ref={menuBtn}
            className="topbar-icon-button"
            onClick={() => menuBtn.current && onOpenSheet(menuBtn.current)}
            aria-label="Menu"
            aria-haspopup="dialog"
            aria-expanded={sheetOpen}
          >
            <MenuIcon />
          </button>
        )}

        <div className="topbar-account">
          <button
            type="button"
            ref={accountBtn}
            className="topbar-account-button"
            onClick={() => setAccountOpen((v) => !v)}
            aria-haspopup="menu"
            aria-expanded={accountOpen}
            aria-label={`Account: ${name}`}
            title={name}
          >
            <Avatar avatarId={avatarId} seed={username} label={name} />
          </button>
          {accountOpen && (
            <div className="topbar-account-menu" role="menu" ref={accountMenu}>
              <span className="topbar-account-name">{name}</span>
              <button
                type="button"
                role="menuitem"
                className="topbar-account-item"
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
      </div>
    </header>
  )
}
