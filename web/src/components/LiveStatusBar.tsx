import { useState } from 'react'
import { trackDraft, type LiveState, type Seat } from '../api'
import { hueFor } from '../hue'
import { roundPickLabel } from '../roundPickLabel'
import { STALE_AFTER_SECONDS } from '../useLiveDraft'

type Props = {
  draftId: string
  live: LiveState | null
  connected: boolean
  secondsSinceContact: number | null
  seats: Seat[]
  mySlot?: number
  onSeatClick: (slot: number) => void
  /** Called after a manual /track so the page can refetch anything it derives from seats. */
  onTracked?: () => void
}

function ago(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}m ${s}s`
}

/**
 * One line above the board carrying four readings: what the draft is doing,
 * whose pick it is, how far in it is, and how long ago the backend last said
 * anything.
 *
 * A bar rather than four cards on purpose -- there is one word, one identity,
 * one fraction and one clock here, and four equal boxes would give the same
 * weight to the status word as to the thing you actually look at (whose pick
 * it is). The on-the-clock seat is the largest element; the freshness pill on
 * the right is the component's real job.
 */
export default function LiveStatusBar({
  draftId,
  live,
  connected,
  secondsSinceContact,
  seats,
  mySlot,
  onSeatClick,
  onTracked,
}: Props) {
  const [tracking, setTracking] = useState(false)
  const [trackNote, setTrackNote] = useState<string | null>(null)

  async function track() {
    setTracking(true)
    setTrackNote(null)
    try {
      const r = await trackDraft(draftId)
      // observed === false means Sleeper was unreachable and `status` is the
      // stale DB value -- say so rather than presenting it as the truth. And a
      // backend older than the seatsMapped field answers 200 without it, so
      // don't render "undefined/undefined".
      const seats = typeof r.seatsMapped === 'number' ? `${r.seatsMapped}/${r.teams} seats · ` : ''
      setTrackNote(`${seats}${r.status ?? 'unknown'}${r.observed === false ? ' (stale)' : ''}`)
      onTracked?.()
    } catch (e) {
      setTrackNote(e instanceof Error ? e.message : String(e))
    } finally {
      setTracking(false)
    }
  }

  const status = live?.status ?? (connected ? 'unknown' : 'offline')
  const onClockSlot = live?.status === 'drafting' ? live.onTheClockSlot : null
  const onClockSeat = onClockSlot != null ? seats.find((s) => s.slot === onClockSlot) : undefined
  // The pick the room is waiting on, not the last one made.
  const onClockPickNo = live ? live.picksMade + 1 : 0
  const hue = onClockSeat ? hueFor(String(onClockSeat.managerId)) : hueFor(String(onClockSlot ?? 0))
  const isMe = onClockSlot != null && onClockSlot === mySlot

  const total = live?.totalPicks ?? 0
  const made = live?.picksMade ?? 0
  const pct = total > 0 ? Math.round((made / total) * 100) : 0

  const stale = secondsSinceContact == null || secondsSinceContact >= STALE_AFTER_SECONDS

  return (
    <div className="live-bar">
      <span className={`chip status-${status}`}>{status.replace('_', ' ')}</span>

      {onClockSeat ? (
        <button
          type="button"
          className={`live-onclock${isMe ? ' mine' : ''}`}
          onClick={() => onSeatClick(onClockSeat.slot)}
          title={`${onClockSeat.manager} — click for details`}
        >
          <span
            className="avatar live-avatar"
            style={
              isMe
                ? { background: 'var(--crimson)', color: 'var(--bg)' }
                : { background: `oklch(28% 0.03 ${hue})`, color: `oklch(82% 0.1 ${hue})` }
            }
          >
            {onClockSeat.manager.trim().charAt(0).toUpperCase()}
          </span>
          <span className="live-onclock-text">
            <span className="live-onclock-label cond">on the clock</span>
            <span className="live-onclock-name">{onClockSeat.manager}</span>
          </span>
          <span className="live-onclock-pick mono">
            {live ? roundPickLabel(onClockPickNo, live.teams) : ''}
          </span>
        </button>
      ) : (
        <span className="live-onclock idle">
          <span className="live-onclock-text">
            <span className="live-onclock-label cond">on the clock</span>
            <span className="live-onclock-name muted">
              {live?.status === 'complete'
                ? 'draft over'
                : live?.status === 'pre_draft'
                  ? 'not started'
                  : '—'}
            </span>
          </span>
        </span>
      )}

      <div className="live-count">
        <span className="live-count-figure mono">
          {made}
          <span className="muted">/{total || '—'}</span>
        </span>
        <div className="progress live-progress">
          <div className="progress-bar" style={{ width: `${pct}%` }} />
        </div>
      </div>

      <div className="live-right">
        {trackNote && <span className="muted tiny live-track-note">{trackNote}</span>}
        <button className="chip live-track" onClick={track} disabled={tracking} title="Re-tick the poller and refresh seat mapping">
          {tracking ? 'tracking…' : 'track'}
        </button>
        <span className={`live-fresh ${stale ? 'stale' : 'ok'}`}>
          {secondsSinceContact == null
            ? 'no contact'
            : `${stale ? 'stale' : 'live'} · ${ago(secondsSinceContact)}`}
        </span>
      </div>
    </div>
  )
}
