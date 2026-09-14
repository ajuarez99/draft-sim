import type { Sport } from '../api'

export type SportFilter = 'all' | Sport

type Props = {
  sportFilter: SportFilter
  onSportFilterChange: (f: SportFilter) => void
  counts: { all: number; nfl: number; nba: number }
  /** The 56px icon rail hides row labels and counts but keeps the dots, so
   *  the filter stays usable (and stays *visible* as a filter) rather than
   *  disappearing the moment someone collapses the rail. */
  collapsed: boolean
}

const SPORT_ROWS: { key: SportFilter; label: string }[] = [
  { key: 'all', label: 'All sports' },
  { key: 'nba', label: 'NBA' },
  { key: 'nfl', label: 'NFL' },
]

/**
 * Home's sport filter, portaled into the rail's page-context region.
 *
 * It was part of the rail's own markup while the rail was Home-only
 * (`0c6f0ab`'s `Sidebar`). The site-wide rail can't own it: the counts come
 * off `drafts`, which only DraftPicker fetches, and on nine of ten routes
 * this control filters nothing. So it lives with its data and portals into
 * the chrome -- claude/site-wide-shell-propagation.md §C step 2.
 */
export default function SportFilterRail({
  sportFilter,
  onSportFilterChange,
  counts,
  collapsed,
}: Props) {
  return (
    <div className="app-rail-section">
      <span className="app-rail-label">Sports</span>
      {SPORT_ROWS.map((row) => {
        const on = sportFilter === row.key
        return (
          <button
            key={row.key}
            type="button"
            className={`app-rail-row${on ? ' on' : ''}`}
            aria-pressed={on}
            // The label is display:none in the icon rail, so the accessible
            // name has to come from somewhere that isn't the text node.
            aria-label={collapsed ? `${row.label} (${counts[row.key]})` : undefined}
            title={`${row.label} · ${counts[row.key]}`}
            onClick={() => onSportFilterChange(row.key)}
          >
            <span className={`app-rail-dot${on ? ' on' : ''} ${row.key}`} aria-hidden="true" />
            <span className="app-rail-row-label">{row.label}</span>
            <span className="app-rail-count app-rail-row-label">{counts[row.key]}</span>
          </button>
        )
      })}
    </div>
  )
}
