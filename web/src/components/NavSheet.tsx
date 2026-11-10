import { useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'
import type { LeagueTab } from '../destinations'

type Props = {
  /** League tabs the bottom bar does not show; listed above the global links. Empty outside a league. */
  leagueTabs: { tab: LeagueTab; label: string; href: string }[]
  currentTab: LeagueTab | null
  name: string
  managersLeagueId: string | null
  onClose: () => void
  onSignOut: () => void
  onOpenMockModal: () => void
}

const FOCUSABLE = 'a[href], button:not([disabled])'

/**
 * The menu sheet. On a phone it is where Leagues, Mock drafts, Managers, Time Machine and Sign out
 * live (plan review PR-01: outside a league nothing else reaches them), and the bottom bar's "More"
 * opens the same sheet with the remaining league tabs on top. In a draft room on a desktop it opens
 * as a popover under the top bar, because the global links fold into the menu button there.
 *
 * It is a modal dialog, so it carries its own small focus trap: focus moves in on open, Tab wraps,
 * Escape and a press on the backdrop close it. The caller returns focus to the trigger on close.
 */
export default function NavSheet({ leagueTabs, currentTab, name, managersLeagueId, onClose, onSignOut, onOpenMockModal }: Props) {
  const sheet = useRef<HTMLDivElement>(null)

  useEffect(() => {
    sheet.current?.querySelector<HTMLElement>(FOCUSABLE)?.focus()
  }, [])

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'Escape') {
      e.preventDefault()
      e.stopPropagation()
      onClose()
      return
    }
    if (e.key !== 'Tab') return
    const items = [...(sheet.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [])]
    if (items.length === 0) return
    const first = items[0]
    const last = items[items.length - 1]
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault()
      last.focus()
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault()
      first.focus()
    }
  }

  return (
    <div
      className="nav-sheet-backdrop"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div
        className="nav-sheet"
        role="dialog"
        aria-modal="true"
        aria-label="Menu"
        ref={sheet}
        onKeyDown={onKeyDown}
        // A link to the page you are already on changes no pathname, so the shell's close-on-navigate
        // never fires and the sheet would stay over the page. Close on the click itself.
        onClick={(e) => {
          if ((e.target as HTMLElement).closest('a')) onClose()
        }}
      >
        {leagueTabs.length > 0 && (
          <nav className="nav-sheet-group" aria-label="More league sections">
            <span className="nav-sheet-label">This league</span>
            {leagueTabs.map((t) => {
              const on = t.tab === currentTab
              return (
                <Link key={t.tab} to={t.href} className={`nav-sheet-item${on ? ' on' : ''}`} aria-current={on ? 'page' : undefined}>
                  {t.label}
                </Link>
              )
            })}
          </nav>
        )}

        <nav className="nav-sheet-group" aria-label="Site">
          <span className="nav-sheet-label">Ball Knowers</span>
          <Link to="/" className="nav-sheet-item">
            Leagues
          </Link>
          <button type="button" className="nav-sheet-item" onClick={onOpenMockModal}>
            Mock drafts
          </button>
          <Link
            to="/managers"
            state={managersLeagueId ? { railLeagueId: managersLeagueId } : undefined}
            className="nav-sheet-item"
          >
            Managers
          </Link>
          <Link to="/history" className="nav-sheet-item">
            Time Machine
          </Link>
        </nav>

        <div className="nav-sheet-foot">
          <span className="nav-sheet-name">{name}</span>
          <button type="button" className="nav-sheet-signout" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      </div>
    </div>
  )
}
