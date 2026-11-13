import type { SportFilter } from '../sportFilter'

type Props = {
  sportFilter: SportFilter
  onSportFilterChange: (f: SportFilter) => void
  counts: { all: number; nfl: number; nba: number }
}

const SPORT_ROWS: { key: SportFilter; label: string }[] = [
  { key: 'all', label: 'All sports' },
  { key: 'nba', label: 'NBA' },
  { key: 'nfl', label: 'NFL' },
]

/**
 * The sport filter, portaled into the page-context strip under the top bar (appSlots.tsx) as a
 * horizontal segmented control: an exclusive choice, so `.segmented` and exactly one `.on` (the
 * control hierarchy at the top of styles.css).
 *
 * It was part of the left rail's own markup while the rail was Home-only, then a vertical list of
 * rows in the rail's context region. The strip is horizontal, so it is a row now. The counts come
 * off `drafts`, which only DraftPicker (and the Scouting report) fetch, so it lives with its data
 * and portals into the chrome -- claude/site-wide-shell-propagation.md §C step 2.
 */
export default function SportFilterRail({ sportFilter, onSportFilterChange, counts }: Props) {
  return (
    <div className="context-strip-content">
      <span className="context-strip-label">Sports</span>
      <div className="segmented sm" role="group" aria-label="Sport">
        {SPORT_ROWS.map((row) => {
          const on = sportFilter === row.key
          return (
            <button
              key={row.key}
              type="button"
              className={`segment${on ? ' on' : ''}`}
              aria-pressed={on}
              onClick={() => onSportFilterChange(row.key)}
            >
              {row.label} <span className="context-strip-count">{counts[row.key]}</span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
