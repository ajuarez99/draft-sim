import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { searchDestinations, type SearchDestination, type SearchKind } from '../searchIndex'

type Props = {
  index: SearchDestination[]
  /** True while the index is still being built -- shown as a note rather than
   *  an empty list, so "nothing matches" and "nothing loaded yet" don't look
   *  the same. */
  loading: boolean
  onClose: () => void
}

/**
 * Jump to anything from anywhere.
 *
 * Before this, reaching another league's page meant rail -> Home -> find the
 * card -> click, four steps whose first one threw away where you were. Fifty-
 * odd manager profiles had no entry point at all outside a standings table.
 *
 * Hand-rolled rather than a palette library (research.md R3): the index is a
 * few hundred local rows and the matching is a `filter`. What actually needs
 * care here is keyboard and focus behaviour, and that is a documented handful
 * rather than a dependency.
 */

const GROUP_LABEL: Record<SearchKind, string> = {
  'league-page': 'Pages',
  'season-board': 'Seasons',
  manager: 'Managers',
}

const GROUP_ORDER: SearchKind[] = ['league-page', 'season-board', 'manager']

/** Caps the list so a long index can't turn the overlay into the page. */
const MAX_RESULTS = 40

export default function JumpTo({ index, loading, onClose }: Props) {
  const navigate = useNavigate()
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)

  const results = useMemo(() => searchDestinations(index, query).slice(0, MAX_RESULTS), [index, query])

  // Grouped for display, but `results` stays the flat keyboard order -- the
  // arrow keys walk what the eye walks, which they would not if the groups
  // re-ordered rows behind the selection.
  const grouped = useMemo(() => {
    const out: { kind: SearchKind; rows: { row: SearchDestination; flatIndex: number }[] }[] = []
    for (const kind of GROUP_ORDER) {
      const rows = results
        .map((row, flatIndex) => ({ row, flatIndex }))
        .filter((r) => r.row.kind === kind)
      if (rows.length > 0) out.push({ kind, rows })
    }
    return out
  }, [results])

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  // A new query invalidates the old selection: keeping index 3 while the list
  // changes underneath it means Enter navigates somewhere you never looked at.
  useEffect(() => {
    setActive(0)
  }, [query])

  // Keeps the highlighted row on screen when the arrows walk past the fold.
  useEffect(() => {
    listRef.current?.querySelector('.jumpto-row.on')?.scrollIntoView({ block: 'nearest' })
  }, [active])

  function go(row: SearchDestination | undefined) {
    if (!row) return
    onClose()
    navigate(row.href)
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'Escape') {
      e.preventDefault()
      onClose()
      return
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((i) => (results.length === 0 ? 0 : (i + 1) % results.length))
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((i) => (results.length === 0 ? 0 : (i - 1 + results.length) % results.length))
      return
    }
    if (e.key === 'Enter') {
      e.preventDefault()
      go(results[active])
    }
  }

  return (
    <div
      className="jumpto-backdrop"
      // A click outside is a dismissal, same as Escape. Guarded on the target
      // so a click that started inside the panel and drifted out doesn't close it.
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div
        className="jumpto"
        role="dialog"
        aria-modal="true"
        aria-label="Jump to"
        onKeyDown={onKeyDown}
      >
        <input
          ref={inputRef}
          className="jumpto-input"
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Jump to a league, page or manager…"
          aria-label="Jump to a league, page or manager"
          aria-controls="jumpto-results"
          autoComplete="off"
          spellCheck={false}
        />

        <div className="jumpto-results" id="jumpto-results" ref={listRef} role="listbox">
          {loading && results.length === 0 && (
            <p className="muted small jumpto-note">Loading your leagues…</p>
          )}
          {!loading && results.length === 0 && (
            <p className="muted small jumpto-note">Nothing matches “{query}”.</p>
          )}

          {grouped.map((group) => (
            <div className="jumpto-group" key={group.kind}>
              <span className="jumpto-group-label cond">{GROUP_LABEL[group.kind]}</span>
              {group.rows.map(({ row, flatIndex }) => (
                <button
                  type="button"
                  key={row.id}
                  role="option"
                  aria-selected={flatIndex === active}
                  className={`jumpto-row${flatIndex === active ? ' on' : ''}`}
                  // Mouse and keyboard share one selection rather than each
                  // keeping its own, so Enter always takes the row you can see
                  // is highlighted.
                  onMouseEnter={() => setActive(flatIndex)}
                  onClick={() => go(row)}
                >
                  <span className="jumpto-row-label">{row.label}</span>
                  <span className="jumpto-row-context muted small">{row.context}</span>
                  {row.sports.map((s) => (
                    <span className={`sport-pill ${s}`} key={s}>
                      {s.toUpperCase()}
                    </span>
                  ))}
                </button>
              ))}
            </div>
          ))}
        </div>

        <div className="jumpto-foot muted small">
          <span>↑↓ to move</span>
          <span>↵ to open</span>
          <span>esc to close</span>
        </div>
      </div>
    </div>
  )
}
