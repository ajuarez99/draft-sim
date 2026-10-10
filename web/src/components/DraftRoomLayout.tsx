import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { ROOM_STACKED, useNarrow } from '../useNarrow'
import SplitDivider, { DIVIDER_PX, type BoardDensity } from './SplitDivider'

// Heights the divider needs to count rows. MEASURED 2026-10-09 at 1440x900 on
// main (research.md A4), not derived: filled cell 70 + 4 gap, col-head 72,
// empty cell 28 (the compact one-liner is the design's ~36 incl. gap), list row
// 27, list head + chips + search ~126. Re-measure if the cell or panel CSS moves.
const BOARD_HEADER_PX = 77 // measured 2026-10-09: 77px from board top to first cell
const BOARD_ROW_FULL_PX = 74
const BOARD_ROW_COMPACT_PX = 36 // measured 2026-10-09: 36px pitch between compact rounds; an earlier value of 33 was a mis-derivation (29px cell + 4px gap), not a measurement
const LIST_ROW_PX = 27
const LIST_CHROME_PX = 88 // measured 2026-10-09: list head + chips + search above the first row

type Props = {
  status: ReactNode
  /** Banners and progress bars: errors, "seats changed", re-run progress. */
  notices?: ReactNode
  controls?: ReactNode
  /** What the room is asking of you right now (PickPrompt, "Pick from full list"). */
  prompt?: ReactNode
  /** FR-001c: feed ticker, your team, scarcity, room read. */
  compactRow?: ReactNode
  /**
   * The board, or a render prop that receives the density the split currently
   * allows (FR-001b). Below 1280px, or before the room has been measured, the
   * density is 'full'.
   */
  board: ReactNode | ((density: BoardDensity) => ReactNode)
  /**
   * Content laid over the board only (an absence message, a start CTA). It is a
   * sibling of the board inside the board's non-scrolling wrapper, so it covers
   * the board but never the rest of the room.
   */
  boardOverlay?: ReactNode
  /** Adds `.pre-start`, which lifts the board's column headers above the overlay (styles.css). */
  boardPreStart?: boolean
  /** The survival strip between the divider and the list. */
  targets?: ReactNode
  /** The player list, or an explained absence for it. */
  list: ReactNode
  /** Board rounds, to decide compact vs full cells. */
  rounds: number
}

/**
 * The one layout all three draft rooms render through (spec 024 FR-003).
 *
 * At 1280px and up the room fills the pane's height: fixed regions on top, then
 * board / divider / targets / list, with the board and the list each scrolling
 * inside themselves. Below that it is one column with a Board | Players toggle
 * and the page never scrolls sideways.
 *
 * The board region is a non-scrolling `position: relative` wrapper around the
 * element that scrolls (DraftBoard's own `.board-scroll`), so PickInsightCard,
 * which is absolute, anchors to the board and not to the scrolled content
 * (research.md A12).
 */
export default function DraftRoomLayout({
  status,
  notices,
  controls,
  prompt,
  compactRow,
  board,
  boardOverlay,
  boardPreStart,
  targets,
  list,
  rounds,
}: Props) {
  const stacked = useNarrow(ROOM_STACKED)
  const [pane, setPane] = useState<'board' | 'list'>('board')
  const [density, setDensity] = useState<BoardDensity>('full')
  const [boardPx, setBoardPx] = useState<number | null>(null)

  // Pixels the divider has to share between board and list. Measured from the
  // room container (height: 100%, bounded by the viewport, independent of the
  // rows we set) less the fixed regions above the split, the gaps between them,
  // the divider and the target strip. Measuring the split zone itself fed back:
  // its size follows the grid rows this value sets, and a missed observer
  // callback left it stale (spec 024, measured live on the projection room).
  const roomRef = useRef<HTMLDivElement>(null)
  const zoneRef = useRef<HTMLDivElement>(null)
  const targetsRef = useRef<HTMLDivElement>(null)
  const [totalHeight, setTotalHeight] = useState(0)
  const hasRegions = [notices, controls, prompt, compactRow, targets].map((r) => (r ? 1 : 0)).join('')
  useEffect(() => {
    const room = roomRef.current
    const zone = zoneRef.current
    if (stacked || !room || !zone || typeof ResizeObserver === 'undefined') return
    const measure = () => {
      let fixed = 0
      let items = 0
      for (const el of Array.from(room.children) as HTMLElement[]) {
        if (el === zone) continue
        if (el.offsetHeight > 0) {
          fixed += el.offsetHeight
          items++
        }
      }
      const gap = parseFloat(getComputedStyle(room).rowGap) || 0
      const t = targetsRef.current?.offsetHeight ?? 0
      setTotalHeight(Math.max(0, room.clientHeight - fixed - items * gap - DIVIDER_PX - t))
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(room)
    for (const el of Array.from(room.children)) if (el !== zone) ro.observe(el)
    if (targetsRef.current) ro.observe(targetsRef.current)
    return () => ro.disconnect()
  }, [stacked, hasRegions])

  const effectiveDensity: BoardDensity = stacked ? 'full' : density
  const boardNode = typeof board === 'function' ? board(effectiveDensity) : board

  const zoneStyle: CSSProperties | undefined =
    !stacked && boardPx != null ? { gridTemplateRows: `${boardPx}px ${DIVIDER_PX}px auto minmax(0, 1fr)` } : undefined

  // Below 1280 the board pane is hidden by clipping it to nothing rather than
  // display:none, because the pick card lives inside it and is position:fixed
  // there: a fixed child of a display:none box disappears with it, and a card
  // you can't see on the Players tab is exactly the bug A12 names.
  const boardHidden = stacked && pane !== 'board'
  const listHidden = stacked && pane !== 'list'

  // The hidden pane is clipped to 0x0, not removed, so without this its ~200 cell buttons stay in
  // the tab order and focus vanishes into it. React 18 has no `inert` prop, so it is set on the DOM.
  // It goes on the board's own content (every direct child of the pane that doesn't contain the
  // pick card, plus the scroller) and never on the pane itself: `inert` can't exempt a descendant,
  // and the pick card must stay focusable and clickable on the Players tab. Re-applied after every
  // render while hidden, because the board's children change as picks land.
  const boardPaneRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const pane = boardPaneRef.current
    if (!boardHidden || !pane) return
    const marked: Element[] = []
    for (const el of Array.from(pane.children)) {
      if (el.querySelector('.pick-card') || el.classList.contains('pick-card')) continue
      el.setAttribute('inert', '')
      marked.push(el)
    }
    for (const el of Array.from(pane.querySelectorAll('.board-scroll'))) {
      if (el.querySelector('.pick-card') || el.closest('[inert]')) continue
      el.setAttribute('inert', '')
      marked.push(el)
    }
    return () => marked.forEach((el) => el.removeAttribute('inert'))
  })

  return (
    <div className={`room ${stacked ? 'room-stacked' : 'room-grid'}`} ref={roomRef}>
      <div className="room-status" data-region="status">{status}</div>
      {notices && <div className="room-notices" data-region="notices">{notices}</div>}
      {controls && <div className="room-controls" data-region="controls">{controls}</div>}
      {prompt && <div className="room-prompt" data-region="prompt">{prompt}</div>}
      {compactRow && <div className="room-compact" data-region="compactRow">{compactRow}</div>}

      {stacked && (
        <div className="segmented room-toggle" role="group" aria-label="Show board or players">
          <button type="button" className={`segment${pane === 'board' ? ' on' : ''}`} aria-pressed={pane === 'board'} onClick={() => setPane('board')}>
            Board
          </button>
          <button type="button" className={`segment${pane === 'list' ? ' on' : ''}`} aria-pressed={pane === 'list'} onClick={() => setPane('list')}>
            Players
          </button>
        </div>
      )}

      <div className="room-split" ref={zoneRef} style={zoneStyle}>
        <div
          className={`room-board board-stage${boardPreStart ? ' pre-start' : ''}${boardHidden ? ' room-pane-hidden' : ''}`}
          data-region="board"
          ref={boardPaneRef}
        >
          {boardNode}
          {boardOverlay}
        </div>
        {!stacked && (
          <SplitDivider
            totalHeight={totalHeight}
            rounds={rounds}
            headerHeight={BOARD_HEADER_PX}
            compactRowHeight={BOARD_ROW_COMPACT_PX}
            fullRowHeight={BOARD_ROW_FULL_PX}
            listRowHeight={LIST_ROW_PX}
            listChromeHeight={LIST_CHROME_PX}
            onBoardHeight={setBoardPx}
            onDensityChange={setDensity}
          />
        )}
        {targets && (
          <div className="room-targets" data-region="targets" ref={targetsRef}>
            {targets}
          </div>
        )}
        <div className={`room-list${listHidden ? ' room-pane-off' : ''}`} data-region="list">
          {list}
        </div>
      </div>
    </div>
  )
}
