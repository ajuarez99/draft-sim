import { useState } from 'react'
import { trackDraft, type LiveState, type Seat } from '../api'
import { STALE_AFTER_SECONDS } from '../useLiveDraft'
import OnTheClock from './OnTheClock'

type Props = {
  draftId: string
  live: LiveState | null
  connected: boolean
  secondsSinceContact: number | null
  seats: Seat[]
  mySlot?: number
  /** Your next pick that hasn't landed yet, for the "picks until you" readout. */
  nextOwnPick?: number | null
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
 * The live room's header: OnTheClock, plus the telemetry only this room has.
 *
 * It used to draw its own `.live-onclock` identity block, which meant three
 * draft rooms carried two visual languages for "whose turn is it" once the
 * batch and mock rooms were unified onto OnTheClock. Composition rather than
 * deletion, because this bar genuinely does more than the other two: an SSE
 * freshness pill, a progress bar against reality, and a manual re-poll. Those
 * ride along as OnTheClock's `children`.
 *
 * The freshness pill is still this component's real job. Everything else here
 * is a number the backend sent, and a number it sent ten minutes ago looks
 * identical to one it sent a second ago.
 */
export default function LiveStatusBar({
  draftId,
  live,
  connected,
  secondsSinceContact,
  seats,
  mySlot,
  nextOwnPick,
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
      // stale DB value -- worth surfacing since the SSE stream can't tell the
      // difference. Everything else (seat count, status) already arrives live
      // over SSE every ~10s -- see `liveSeatNote` below -- so don't duplicate
      // it here, or this note goes stale the moment the next tick lands.
      setTrackNote(r.observed === false ? `${r.status ?? 'unknown'} (stale)` : null)
      onTracked?.()
    } catch (e) {
      setTrackNote(e instanceof Error ? e.message : String(e))
    } finally {
      setTracking(false)
    }
  }

  // Reactive: `live` is pushed over SSE roughly every 10s, including during
  // pre_draft, so this keeps counting up on its own -- the manual Refresh
  // button below is a force-check, not the only way this number moves.
  const liveSeatNote =
    live && live.teams > 0
      ? live.seatsMapped === live.teams
        ? `All ${live.teams} managers identified`
        : `Only ${live.seatsMapped} of ${live.teams} managers identified`
      : null

  const onClockSlot = live?.status === 'drafting' ? live.onTheClockSlot : null
  const onClockSeat = onClockSlot != null ? seats.find((s) => s.slot === onClockSlot) : undefined
  // The pick the room is waiting on, not the last one made.
  const onClockPickNo = live ? live.picksMade + 1 : 0
  const isMe = onClockSlot != null && onClockSlot === mySlot

  const stale = secondsSinceContact == null || secondsSinceContact >= STALE_AFTER_SECONDS
  const total = live?.totalPicks ?? 0
  const made = live?.picksMade ?? 0
  const pct = total > 0 ? Math.round((made / total) * 100) : 0

  // Four distinct reasons nobody is on the clock, and they are not
  // interchangeable -- "not started" and "we can't reach the backend" would be
  // the same word if this collapsed them.
  const idleLabel =
    live == null
      ? connected
        ? 'Connecting to the draft'
        : 'Not connected'
      : live.status === 'complete'
        ? 'Draft complete'
        : live.status === 'pre_draft'
          ? 'Draft has not started'
          : 'Waiting for the draft order'

  const telemetry = (
    <>
      {total > 0 && (
        <div className="progress live-progress" title={`${made} of ${total} picks made`}>
          <div className="progress-bar" style={{ width: `${pct}%` }} />
        </div>
      )}
      <div className="live-right">
        {(liveSeatNote || trackNote) && (
          <span className="muted tiny live-track-note">
            {[liveSeatNote, trackNote].filter(Boolean).join(' · ')}
          </span>
        )}
        <button
          className="chip live-track"
          onClick={track}
          disabled={tracking}
          title="Check Sleeper again now and refresh which managers are in which seats"
        >
          {tracking ? 'Refreshing…' : 'Refresh'}
        </button>
        <span className={`live-fresh ${stale ? 'stale' : 'ok'}`}>
          {secondsSinceContact == null ? 'No contact' : `${stale ? 'Stale' : 'Live'} · ${ago(secondsSinceContact)}`}
        </span>
      </div>
    </>
  )

  return (
    <div className="live-bar">
      <OnTheClock
        manager={onClockSeat?.manager ?? null}
        isMine={isMe}
        hueSeed={String(onClockSeat?.managerId ?? onClockSlot ?? 0)}
        pickNo={onClockPickNo}
        maxPickNo={total}
        teams={live?.teams ?? 0}
        rounds={live?.rounds ?? 0}
        nextOwnPick={nextOwnPick}
        idle={onClockSeat == null}
        idleLabel={idleLabel}
        onManagerClick={onClockSeat ? () => onSeatClick(onClockSeat.slot) : undefined}
      >
        {telemetry}
      </OnTheClock>
    </div>
  )
}
