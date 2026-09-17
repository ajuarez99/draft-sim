import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { searchDestinations, type SearchDestination, type SearchKind, type SearchMark } from '../searchIndex'
import Avatar from './Avatar'

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

/**
 * The mark left of a row's label -- a league's crest, or a manager's photo.
 *
 * Decorative in both cases: the label and the context beside it already say
 * everything the mark says, so it is `aria-hidden` rather than a second thing
 * for a screen reader to read out per row. See `SearchMark` for why the league
 * gets the mark here and the destination gets it in the rail.
 */
function RowMark({ mark, label }: { mark: SearchMark; label: string }) {
  if (mark.kind === 'avatar') {
    return (
      <Avatar
        avatarId={mark.avatarId}
        seed={mark.seed}
        label={label}
        className="jumpto-row-mark"
      />
    )
  }
  return (
    <span
      className="avatar league-crest jumpto-row-mark"
      style={{ background: `oklch(30% 0.05 ${mark.hue})`, color: `oklch(84% 0.12 ${mark.hue})` }}
      aria-hidden="true"
    >
      {mark.letter}
    </span>
  )
}

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
              {/* Not `.cond`: the rail's own section labels ("League", "Menu")
                  are the app's vocabulary for naming a group of navigation
                  rows, and this shares their rule rather than inventing a
                  second one -- see .app-rail-label in styles.css. */}
              <span className="jumpto-group-label">{GROUP_LABEL[group.kind]}</span>
              {group.rows.map(({ row, flatIndex }) => (
                // An <a> with a real href, not a <button>: every row here
                // navigates, and the house rule is that navigation is a link.
                // It is also the whole difference between a list you can scan
                // and one you can work -- a button has no href, so Cmd-click
                // and middle-click cannot open a destination in a background
                // tab and the browser shows no target on hover.
                <a
                  key={row.id}
                  href={row.href}
                  role="option"
                  aria-selected={flatIndex === active}
                  className={`jumpto-row${flatIndex === active ? ' on' : ''}`}
                  // Mouse and keyboard share one selection rather than each
                  // keeping its own, so Enter always takes the row you can see
                  // is highlighted.
                  onMouseEnter={() => setActive(flatIndex)}
                  onClick={(e) => {
                    // Modified clicks stay the browser's: they are how someone
                    // opens three leagues' histories at once. Only the plain
                    // left click is ours to turn into a client-side navigation.
                    if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return
                    e.preventDefault()
                    go(row)
                  }}
                >
                  <RowMark mark={row.mark} label={row.label} />
                  <span className="jumpto-row-label">{row.label}</span>
                  <span className="jumpto-row-context muted small">{row.context}</span>
                  {row.sports.map((s) => (
                    <span className={`sport-pill ${s}`} key={s}>
                      {s.toUpperCase()}
                    </span>
                  ))}
                </a>
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
