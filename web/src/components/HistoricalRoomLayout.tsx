import { useState, type ReactNode } from 'react'
import { ROOM_STACKED, useNarrow } from '../useNarrow'

export type RoomPane = 'players' | 'queue' | 'board' | 'picks' | 'roster'

type Props = {
  /** The sticky clock banner. */
  banner: ReactNode
  /** Errors and the like, shown under the banner. */
  notices?: ReactNode
  queueCount: number
  players: ReactNode
  queue: ReactNode
  board: ReactNode
  rail: ReactNode
  roster: ReactNode
}

const WIDE_TABS: { id: RoomPane; label: (n: number) => string }[] = [
  { id: 'players', label: () => 'Players' },
  { id: 'queue', label: (n) => `Queue (${n})` },
  { id: 'board', label: () => 'Board' },
]
const NARROW_TABS: { id: RoomPane; label: (n: number) => string }[] = [
  ...WIDE_TABS,
  { id: 'picks', label: () => 'Picks' },
  { id: 'roster', label: () => 'Roster' },
]

/**
 * Spec 063 C1: the Time Machine room's own layout. Not DraftRoomLayout, which the mock, live and simulator rooms
 * share and which stays exactly as it was.
 *
 * At 1280px and up (the same breakpoint the shared room uses, ROOM_STACKED): the sticky banner, then pick rail |
 * main | roster, with the main column switching between Players, Queue and Board. Choosing Board collapses the rail
 * and the roster so the board gets the full width (plan review PR-12). Below that: one segmented control with five
 * views and one pane on screen at a time. Panes other than Players are not rendered while not showing; nothing in
 * them is position: fixed, so there is no hidden-pane bookkeeping.
 */
export default function HistoricalRoomLayout({ banner, notices, queueCount, players, queue, board, rail, roster }: Props) {
  const narrow = useNarrow(ROOM_STACKED)
  const [chosen, setChosen] = useState<RoomPane>('players')
  // Rail and roster only exist as views on a narrow screen; on a wide one they are always beside the main column.
  const pane: RoomPane = !narrow && (chosen === 'picks' || chosen === 'roster') ? 'players' : chosen
  const tabs = narrow ? NARROW_TABS : WIDE_TABS

  // The players list stays mounted (hidden) while another pane shows, so its search text, filter and paging
  // survive a look at the queue or the roster (CR-09). `hidden` also takes it out of the tab order and the
  // accessibility tree. The other panes mount on demand, as before.
  const playersShowing = pane === 'players'
  const otherNode =
    pane === 'queue' ? queue : pane === 'board' ? <div className="board-stage room-board hist-board-pane">{board}</div> : pane === 'picks' ? rail : pane === 'roster' ? roster : null
  const mainNode = (
    <>
      <div hidden={!playersShowing}>{players}</div>
      {otherNode}
    </>
  )

  const switcher = (
    <div className="segmented hist-room-tabs" role="group" aria-label="Room view">
      {tabs.map((t) => (
        <button
          key={t.id}
          type="button"
          className={`segment${pane === t.id ? ' on' : ''}`}
          aria-pressed={pane === t.id}
          onClick={() => setChosen(t.id)}
        >
          {t.label(queueCount)}
        </button>
      ))}
    </div>
  )

  return (
    <div className={`hist-room ${narrow ? 'hist-room-narrow' : 'hist-room-wide'}${!narrow && pane === 'board' ? ' board-wide' : ''}`}>
      {banner}
      {notices}
      {narrow ? (
        <>
          {switcher}
          <div className="hist-pane" data-pane={pane}>
            {mainNode}
          </div>
        </>
      ) : (
        <div className="hist-room-body">
          {pane !== 'board' && <div className="hist-col hist-col-rail">{rail}</div>}
          <div className="hist-col hist-col-main">
            {switcher}
            <div className="hist-pane" data-pane={pane}>
              {mainNode}
            </div>
          </div>
          {pane !== 'board' && <div className="hist-col hist-col-roster">{roster}</div>}
        </div>
      )}
    </div>
  )
}
