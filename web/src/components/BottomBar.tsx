import { useRef } from 'react'
import { Link } from 'react-router-dom'
import type { LeagueTab } from '../destinations'

type TabLink = { tab: LeagueTab; label: string; href: string }

/** The four tabs a phone keeps one tap away. Everything else is behind "More". Fixed by name rather
 *  than "the first four" so Draft is never the tab that falls off a league without a Players tab. */
const PINNED: LeagueTab[] = ['home', 'matchups', 'season', 'draft']

/** The tabs the bottom bar does not show, in tab order: the nav sheet lists these above the global links. */
export function moreTabs(tabs: TabLink[]): TabLink[] {
  return tabs.filter((t) => !PINNED.includes(t.tab))
}

type Props = {
  tabs: TabLink[]
  currentTab: LeagueTab | null
  onOpenSheet: (trigger: HTMLElement) => void
  sheetOpen: boolean
}

/**
 * The league's tabs on a phone: a fixed bar at the bottom (claude/site-redesign.md Phase 2). Only
 * this and the 48px top bar are pinned on a phone; the league header scrolls with the page. Rendered
 * inside a league only -- outside one the top bar's menu button carries the global links.
 */
export default function BottomBar({ tabs, currentTab, onOpenSheet, sheetOpen }: Props) {
  const moreBtn = useRef<HTMLButtonElement>(null)
  const more = moreTabs(tabs)
  const moreIsCurrent = currentTab != null && more.some((t) => t.tab === currentTab)

  return (
    <nav className="bottom-bar" aria-label="League">
      {tabs
        .filter((t) => PINNED.includes(t.tab))
        .map((t) => {
          const on = t.tab === currentTab
          return (
            <Link key={t.tab} to={t.href} className={`bottom-bar-item${on ? ' on' : ''}`} aria-current={on ? 'page' : undefined}>
              {t.label}
            </Link>
          )
        })}
      <button
        type="button"
        ref={moreBtn}
        className={`bottom-bar-item${moreIsCurrent ? ' on' : ''}`}
        onClick={() => moreBtn.current && onOpenSheet(moreBtn.current)}
        aria-haspopup="dialog"
        aria-expanded={sheetOpen}
      >
        More
      </button>
    </nav>
  )
}
