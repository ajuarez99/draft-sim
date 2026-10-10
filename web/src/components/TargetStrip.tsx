import { useEffect, useRef, useState } from 'react'
import type { Sport } from '../api'
import type { TargetsStatus } from '../useTargets'
import type { MarkedTarget } from '../targets'
import { shortName } from '../playerName'
import PlayerFace from './PlayerFace'

// ARBITRARY: how much width one chip is budgeted when deciding how many fit. Chips are
// capped in CSS (max-width), so this is an upper-ish estimate, not a measurement of any one.
const CHIP_SLOT_PX = 138
// Room kept for the label, the "+N" and the Edit button.
const CHROME_PX = 150

export const EMPTY_COPY = 'Star a player in the list to target them.'
export const LIVE_COPY = 'Picks are made on Sleeper — this list only watches.'
export const MOCK_NOTE = "Mocks don't run a simulation, so there's no survival number."
export const UNAVAILABLE_COPY = "Targets aren't available on this server yet."
export const SIGNED_OUT_COPY = 'Sign in to keep a target list.'

type Props = {
  items: MarkedTarget[]
  status: TargetsStatus
  /** The last save failed: the list shown is the user's edit, not what the server has. */
  error: boolean
  sport: Sport
  /** The chance a target is still there at the user's next pick; undefined shows nothing (never 0). */
  survivalOf?: (t: MarkedTarget) => number | undefined
  /** 'live' adds the "this only watches" line (FR-015); 'mock' adds the no-survival note. */
  room: 'live' | 'projection' | 'mock'
  onMove: (sleeperId: string, delta: -1 | 1) => void
  onRemove: (sleeperId: string) => void
  onRetry: () => void
  /** Test seam: how many chips to show before "+N". Measured from the strip's width when omitted. */
  maxVisible?: number
}

/**
 * The target list as one row of chips pinned above the player list (spec 024 FR-011a).
 * It never wraps: chips that don't fit collapse into "+N", and reordering and removing
 * live in a popover. A taken target stays, labelled "taken" (FR-013); a target with no
 * survival number shows none (FR-012).
 */
export default function TargetStrip({ items, status, error, sport, survivalOf, room, onMove, onRemove, onRetry, maxVisible }: Props) {
  const [open, setOpen] = useState(false)
  const [width, setWidth] = useState(0)
  const rootRef = useRef<HTMLDivElement>(null)

  // The measured div only exists in the main branch; the unavailable / loadFailed branches unmount
  // it. Depending on whether it is mounted re-attaches the observer to the new div after a
  // load-failure retry, so "+N" collapsing keeps working.
  const mainMounted = status !== 'unavailable' && status !== 'signedOut' && status !== 'loadFailed'
  useEffect(() => {
    const el = rootRef.current
    if (!mainMounted || !el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(() => setWidth(el.clientWidth))
    ro.observe(el)
    setWidth(el.clientWidth)
    return () => ro.disconnect()
  }, [mainMounted])

  // Escape closes the popover and hands focus back to its opener.
  const openerRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false)
        openerRef.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open])

  if (status === 'unavailable' || status === 'signedOut') {
    return (
      <div className="target-strip target-strip-note" data-room={room}>
        <span className="muted small">{status === 'signedOut' ? SIGNED_OUT_COPY : UNAVAILABLE_COPY}</span>
      </div>
    )
  }
  if (status === 'loadFailed') {
    return (
      <div className="target-strip target-strip-note" data-room={room}>
        <button type="button" className="league-link" onClick={onRetry}>
          Couldn't load targets — retry
        </button>
      </div>
    )
  }

  const fit = width > 0 ? Math.max(1, Math.floor((width - CHROME_PX) / CHIP_SLOT_PX)) : items.length
  const visible = Math.min(items.length, maxVisible ?? fit)
  const hidden = items.length - visible

  return (
    <div className="target-strip-wrap" ref={rootRef}>
      <div className="target-strip" data-room={room} role="group" aria-label="Targets">
        <span className="target-label cond">Targets</span>
        {items.length === 0 && status !== 'loading' && <span className="muted small target-empty">{EMPTY_COPY}</span>}
        {items.length > 0 && (
          <ul className="target-chips">
            {items.slice(0, visible).map((t) => {
              const s = t.taken || !t.player ? undefined : survivalOf?.(t)
              const label = t.player ? shortName(t.player, sport) : null
              return (
                <li key={t.sleeperId} className={`target-chip${t.taken ? ' taken' : ''}`} title={t.name}>
                  {t.player && (
                    <PlayerFace sport={sport} sleeperId={t.player.sleeperId} team={t.player.team} position={t.player.position} name={t.name} size={16} />
                  )}
                  <span className="target-name">{label ? `${label.lead}${label.rest}` : t.name}</span>
                  {t.taken && <span className="target-taken">taken</span>}
                  {s != null && <span className="target-pct mono">{Math.round(s * 100)}%</span>}
                </li>
              )
            })}
          </ul>
        )}
        {hidden > 0 && (
          <button type="button" className="chip target-more" onClick={() => setOpen(true)} aria-label={`${hidden} more targets, open the list`}>
            +{hidden}
          </button>
        )}
        {items.length > 0 && (
          <button
            ref={openerRef}
            type="button"
            className="chip target-edit"
            aria-expanded={open}
            aria-haspopup="dialog"
            onClick={() => setOpen((o) => !o)}
          >
            Edit
          </button>
        )}
        {error && (
          <button type="button" className="target-error" onClick={onRetry}>
            Couldn't save targets — retry
          </button>
        )}
        {room === 'live' && <span className="muted small target-copy">{LIVE_COPY}</span>}
        {room === 'mock' && items.length > 0 && <span className="muted small target-copy">{MOCK_NOTE}</span>}
      </div>

      {open && (
        <div className="target-pop" role="dialog" aria-label="Edit targets">
          <ol className="target-pop-list">
            {items.map((t, i) => (
              <li key={t.sleeperId} className={`target-pop-row${t.taken ? ' taken' : ''}`}>
                <span className="target-pop-name">{t.name}</span>
                {t.taken && <span className="target-taken">taken</span>}
                {t.player == null && <span className="muted small">not on the board</span>}
                <button type="button" className="target-btn" aria-label={`Move ${t.name} up`} disabled={i === 0} onClick={() => onMove(t.sleeperId, -1)}>
                  ↑
                </button>
                <button
                  type="button"
                  className="target-btn"
                  aria-label={`Move ${t.name} down`}
                  disabled={i === items.length - 1}
                  onClick={() => onMove(t.sleeperId, 1)}
                >
                  ↓
                </button>
                <button type="button" className="target-btn" aria-label={`Remove ${t.name}`} onClick={() => onRemove(t.sleeperId)}>
                  ✕
                </button>
              </li>
            ))}
          </ol>
          <button type="button" className="chip" onClick={() => { setOpen(false); openerRef.current?.focus() }}>
            Done
          </button>
        </div>
      )}
    </div>
  )
}
