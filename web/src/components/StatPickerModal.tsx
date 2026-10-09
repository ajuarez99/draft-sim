import { useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { COLUMNS, DEFAULT_COLUMNS, PICKABLE } from '../statLeaderboard'

type Props = {
  /** The chosen stat ids, in table order. The caller owns this; the modal keeps no copy. */
  columns: string[]
  /**
   * Called on every change, so the table updates behind the modal at once. Toggles and moves pass
   * an updater rather than a list, so clicks faster than a re-render compose instead of the last
   * one winning.
   */
  onChange: (next: string[] | ((cur: string[]) => string[])) => void
  onClose: () => void
}

/**
 * The live room's stat picker (specs/023 US2): turn stats on and off and put them in order. It
 * holds no state of its own, only what the caller passes in, so a pick landing behind it
 * re-renders the table and leaves this open with every toggle where it was. Reordering is up and
 * down buttons rather than drag so it works on a phone and with a keyboard.
 */
export default function StatPickerModal({ columns, onChange, onClose }: Props) {
  const cardRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  // Focus moves into the dialog on open and back to whatever opened it on close.
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null
    cardRef.current?.focus()
    return () => opener?.focus?.()
  }, [])

  const chosen = new Set(columns)
  // By id, not index: an updater runs against the latest list, where the index may have moved.
  const move = (id: string, by: -1 | 1) =>
    onChange((cur) => {
      const i = cur.indexOf(id)
      const j = i + by
      if (i < 0 || j < 0 || j >= cur.length) return cur
      const next = [...cur]
      ;[next[i], next[j]] = [next[j], next[i]]
      return next
    })
  const toggle = (id: string) => onChange((cur) => (cur.includes(id) ? cur.filter((c) => c !== id) : [...cur, id]))

  // Portaled to <body>: the sheet it opens from is its own stacking context, which would cap the
  // backdrop's layer 100 below the board's overlays.
  return createPortal(
    <div className="modal-backdrop" onClick={onClose}>
      <div
        ref={cardRef}
        tabIndex={-1}
        className="modal-card stat-picker"
        role="dialog"
        aria-modal="true"
        aria-label="Choose stats"
        onClick={(ev) => ev.stopPropagation()}
      >
        <button type="button" className="modal-close" onClick={onClose} aria-label="Close">
          ✕
        </button>
        <h2 className="stat-picker-title cond">Choose stats</h2>

        <div className="stat-picker-body">
          <h3 className="stat-picker-h">Shown</h3>
          {columns.length === 0 ? (
            <p className="muted small">No stats chosen.</p>
          ) : (
            <ol className="stat-picker-shown">
              {columns.map((id, i) => {
                const c = COLUMNS[id]
                if (!c) return null
                return (
                  <li key={id}>
                    <span className="stat-picker-name" title={c.title}>{c.label}</span>
                    <button type="button" aria-label={`Move ${c.label} up`} disabled={i === 0} onClick={() => move(id, -1)}>▲</button>
                    <button type="button" aria-label={`Move ${c.label} down`} disabled={i === columns.length - 1} onClick={() => move(id, 1)}>▼</button>
                    <button type="button" aria-label={`Remove ${c.label}`} onClick={() => toggle(id)}>✕</button>
                  </li>
                )
              })}
            </ol>
          )}

          {PICKABLE.map((g) => (
            <fieldset key={g.id} className="stat-picker-group">
              <legend className="stat-picker-h">{g.label}</legend>
              {g.columns.map((c) => (
                <label key={c.id} className="stat-picker-opt">
                  <input type="checkbox" checked={chosen.has(c.id)} onChange={() => toggle(c.id)} />
                  <span className="stat-picker-name">{c.label}</span>
                  <span className="muted small">{c.title}</span>
                </label>
              ))}
            </fieldset>
          ))}
        </div>

        <div className="stat-picker-footer">
          <button type="button" className="league-link" onClick={() => onChange([...DEFAULT_COLUMNS])}>
            Reset to default
          </button>
          <button type="button" className="home-hero-cta" onClick={onClose}>
            Done
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
