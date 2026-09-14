import { Link } from 'react-router-dom'
import Avatar from './Avatar'
import type { Sport } from '../api'

export type SportFilter = 'all' | Sport

type Props = {
  sportFilter: SportFilter
  onSportFilterChange: (f: SportFilter) => void
  counts: { all: number; nfl: number; nba: number }
  username: string
  displayName: string | null
  avatarId: string | null
  onSignOut: () => void
  /** "Mock drafts" is a menu row that opens the new-mock modal rather than a
   *  route (design_handoff_multisport_mock_drafts/README.md's sidebar spec). */
  onOpenMockModal: () => void
}

const SPORT_ROWS: { key: SportFilter; label: string }[] = [
  { key: 'all', label: 'All sports' },
  { key: 'nba', label: 'NBA' },
  { key: 'nfl', label: 'NFL' },
]

/**
 * The redesigned home screen's left rail (design_handoff_multisport_mock_drafts).
 * Scoped to Home only -- App.tsx suppresses the app-wide `.top` header on `/`
 * and this carries the wordmark/nav/account chrome instead. Every other route
 * keeps the old header; this isn't a site-wide nav redesign.
 */
export default function Sidebar({
  sportFilter,
  onSportFilterChange,
  counts,
  username,
  displayName,
  avatarId,
  onSignOut,
  onOpenMockModal,
}: Props) {
  return (
    <nav className="home-sidebar" aria-label="Home">
      <div className="home-wordmark cond">
        Ball
        <br />
        Knowers
      </div>

      <div className="home-sidebar-section">
        <span className="home-sidebar-label">Sports</span>
        {SPORT_ROWS.map((row) => (
          <button
            key={row.key}
            type="button"
            className={`home-sport-row${sportFilter === row.key ? ' on' : ''}`}
            aria-pressed={sportFilter === row.key}
            onClick={() => onSportFilterChange(row.key)}
          >
            <span className={`home-sport-dot${sportFilter === row.key ? ' on' : ''} ${row.key}`} />
            {row.label}
            <span className="home-sport-count">{counts[row.key]}</span>
          </button>
        ))}
      </div>

      <div className="home-sidebar-section">
        <span className="home-sidebar-label">Menu</span>
        <span className="home-menu-row on">Home</span>
        <Link to="/managers" className="home-menu-row">
          Managers
        </Link>
        <button type="button" className="home-menu-row" onClick={onOpenMockModal}>
          Mock drafts
        </button>
      </div>

      <div className="home-sidebar-spacer" />

      <div className="home-account">
        <Avatar avatarId={avatarId} seed={username} label={displayName || username} />
        <span className="home-account-name">{displayName || username}</span>
      </div>
      <button type="button" className="home-signout" onClick={onSignOut}>
        Sign out
      </button>
    </nav>
  )
}
