import { useEffect, useId, useMemo, useState, type KeyboardEvent } from 'react'
import { fetchDraftStats } from '../draftStatsCache'
import { useLeagueDataVersion } from '../leagueDataVersion'
import type { LeaderboardRow, StatLeaderboard } from '../api'
import { fallbackNote, reasonSentence, seasonLabel } from '../statCopy'
import { SEARCH_MIN_CHARS, matchesSearch } from '../targets'
import OwnershipMark from './OwnershipMark'
import PlayerFace from './PlayerFace'

/**
 * specs/049 US3: pick a player to compare by typing part of his name.
 *
 * The candidates are the season leaderboard's rows (`fetchDraftStats`, the module-level promise cache the draft
 * room's Stats view already shares), so there is no search endpoint. The board is fetched only when the chooser
 * is opened, never on mount: a page that has both players needs no list. The fold is `targets.ts`'s, so
 * "jokic" finds "Jokić". It lists players with a game in the answered season (a player with none can still be
 * compared by address).
 */

const MAX_SHOWN = 8

type Load = { status: 'idle' } | { status: 'loading' } | { status: 'error' } | { status: 'ok'; board: StatLeaderboard }

export default function PlayerChooser({
  leagueId,
  excludeId,
  onPick,
  label = 'Choose a player',
  startOpen = false,
  onCancel,
}: {
  leagueId: string
  /** The player already in the other column: he is not offered. */
  excludeId?: string | null
  onPick: (sleeperPlayerId: string) => void
  label?: string
  /** Opens straight into the search box (a column's "Change" has already been clicked). */
  startOpen?: boolean
  /** Called when the manager closes the box without picking. */
  onCancel?: () => void
}) {
  const [open, setOpen] = useState(startOpen)
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const [load, setLoad] = useState<Load>({ status: 'idle' })
  const version = useLeagueDataVersion(leagueId)
  const inputId = useId()
  const listId = useId()

  // Opening is what starts the load. `load.status` is deliberately not a dependency: setting it would re-run
  // the effect and cancel the very request it just made. A re-open reads the module cache, not the network.
  useEffect(() => {
    if (!open) return
    let cancelled = false
    setLoad({ status: 'loading' })
    fetchDraftStats(leagueId, 'SEASON', version).then(
      (board) => {
        if (!cancelled) setLoad({ status: 'ok', board })
      },
      () => {
        if (!cancelled) setLoad({ status: 'error' })
      },
    )
    return () => {
      cancelled = true
    }
  }, [open, leagueId, version])

  const searching = query.trim().length >= SEARCH_MIN_CHARS
  const { shown, more } = useMemo(() => {
    if (load.status !== 'ok' || !searching) return { shown: [] as LeaderboardRow[], more: 0 }
    // A row with no name can't be found by name (A11); the id already in the other column is not offered.
    const all = load.board.rows.filter(
      (r) => r.name != null && r.sleeperPlayerId !== excludeId && matchesSearch(query, r.name),
    )
    return { shown: all.slice(0, MAX_SHOWN), more: Math.max(0, all.length - MAX_SHOWN) }
  }, [load, searching, query, excludeId])

  function choose(r: LeaderboardRow) {
    setOpen(false)
    setQuery('')
    onPick(r.sleeperPlayerId)
  }

  function onKeyDown(ev: KeyboardEvent<HTMLInputElement>) {
    if (ev.key === 'ArrowDown') {
      ev.preventDefault()
      setActive((a) => (shown.length === 0 ? 0 : (a + 1) % shown.length))
    } else if (ev.key === 'ArrowUp') {
      ev.preventDefault()
      setActive((a) => (shown.length === 0 ? 0 : (a - 1 + shown.length) % shown.length))
    } else if (ev.key === 'Enter') {
      if (shown[active]) {
        ev.preventDefault()
        choose(shown[active])
      }
    } else if (ev.key === 'Escape') {
      ev.preventDefault()
      setOpen(false)
      onCancel?.()
    }
  }

  if (!open) {
    return (
      <button type="button" className="pc-choose-btn" onClick={() => setOpen(true)}>
        {label}
      </button>
    )
  }

  const board = load.status === 'ok' ? load.board : null
  const optionId = (i: number) => `${listId}-o${i}`
  return (
    <div className="pc-chooser">
      <label htmlFor={inputId} className="pc-choose-label">
        {label}
      </label>
      <input
        id={inputId}
        // eslint-disable-next-line jsx-a11y/no-autofocus
        autoFocus
        role="combobox"
        autoComplete="off"
        aria-expanded={searching}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={shown[active] ? optionId(active) : undefined}
        placeholder="Search by name"
        value={query}
        onChange={(e) => {
          setQuery(e.target.value)
          setActive(0)
        }}
        onKeyDown={onKeyDown}
      />
      {load.status === 'loading' && (
        <p className="muted small" role="status">
          Loading players…
        </p>
      )}
      {load.status === 'error' && <p className="muted small">Couldn’t load the player list.</p>}
      {board && !board.available && (
        <p className="muted small">{board.reason ? reasonSentence(board.reason) : 'No player list for this league.'}</p>
      )}
      {board?.available && board.requestedSeason != null && (
        <p className="small pt-note" role="note">
          {fallbackNote(seasonLabel(board.requestedSeason), seasonLabel(board.season))}
        </p>
      )}
      {board?.available && !searching && (
        <p className="muted small">{`Type at least ${SEARCH_MIN_CHARS} letters of a name.`}</p>
      )}
      {board?.available && searching && (
        <ul id={listId} role="listbox" aria-label="Matching players" className="pc-options">
          {shown.length === 0 && <li className="muted small pc-noopt">{`No player matches “${query.trim()}”.`}</li>}
          {shown.map((r, i) => (
            <li
              key={r.sleeperPlayerId}
              id={optionId(i)}
              role="option"
              aria-selected={i === active}
              className={`pc-option${i === active ? ' on' : ''}`}
              // mousedown, not click, so a blur can't close the list before the pick lands
              onMouseDown={(ev) => {
                ev.preventDefault()
                choose(r)
              }}
              onMouseEnter={() => setActive(i)}
            >
              <PlayerFace
                sport={board.sport}
                sleeperId={r.sleeperPlayerId}
                team={r.team}
                position={r.positions[0] ?? ''}
                name={r.name ?? ''}
                size={28}
              />
              <span className="pc-opt-name">{r.name}</span>
              <span className="muted small">{[r.positions[0], r.team].filter(Boolean).join(' · ')}</span>
              <span className="pc-opt-own">
                <OwnershipMark o={r.ownership} />
              </span>
            </li>
          ))}
          {more > 0 && <li className="muted small pc-noopt">{`${more} more: keep typing to narrow it.`}</li>}
        </ul>
      )}
      <button type="button" className="pc-choose-cancel muted small" onClick={() => { setOpen(false); onCancel?.() }}>
        Cancel
      </button>
    </div>
  )
}
