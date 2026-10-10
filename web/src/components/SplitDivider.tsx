import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'

// ARBITRARY — from SC-001 ("at least 7 board rounds"), not derived. Before anyone
// drags, the board gets exactly this many compact rounds and the list gets the rest.
export const TARGET_ROUNDS = 7

export const SPLIT_STORAGE_KEY = 'draftRoom.split'
/** Height of the divider's own hit area (styles.css `.room-divider`). */
export const DIVIDER_PX = 8

export type BoardDensity = 'compact' | 'full'

type Props = {
  /** Pixels available to board + list together (divider and target strip already taken out). 0 = not measured yet. */
  totalHeight: number
  /** Board rounds, for the compact/full call (FR-001b). */
  rounds: number
  /** Board column-header height, and the height of one round at each density. */
  headerHeight: number
  compactRowHeight: number
  fullRowHeight: number
  /** List row height, and everything in the list above its first row (head, chips, search). */
  listRowHeight: number
  listChromeHeight: number
  minBoardRounds?: number
  minListRows?: number
  /** The board region's height in px, whenever it changes. Not called until totalHeight is known. */
  onBoardHeight: (px: number) => void
  onDensityChange?: (d: BoardDensity) => void
}

/** The stored fraction, or null when none (or an unusable one) is stored. */
function readFraction(): number | null {
  try {
    const raw = window.localStorage.getItem(SPLIT_STORAGE_KEY)
    const n = raw == null ? NaN : Number(raw)
    return Number.isFinite(n) && n > 0 && n < 1 ? n : null
  } catch {
    return null
  }
}

function writeFraction(f: number | null) {
  try {
    if (f == null) window.localStorage.removeItem(SPLIT_STORAGE_KEY)
    else window.localStorage.setItem(SPLIT_STORAGE_KEY, String(f))
  } catch {
    /* storage can be blocked or full; the split just won't be remembered */
  }
}

/**
 * The draggable bar between the board and the player list (spec 024 FR-001a).
 * Owns the split fraction (persisted per device, not per draft) and reports
 * the resulting board height and the board's density to DraftRoomLayout.
 *
 * Pointer drag, or the keyboard: Up/Down move it one board row, Home/End go to
 * the minimums, Enter or a double-click puts it back to the default. The
 * stored fraction is never clamped on write -- only the rendered height is --
 * so a split saved on a tall screen still means something on a short one.
 */
export default function SplitDivider({
  totalHeight,
  rounds,
  headerHeight,
  compactRowHeight,
  fullRowHeight,
  listRowHeight,
  listChromeHeight,
  minBoardRounds = 3,
  minListRows = 3,
  onBoardHeight,
  onDensityChange,
}: Props) {
  const [fraction, setFraction] = useState<number | null>(readFraction)

  const minBoard = headerHeight + minBoardRounds * compactRowHeight
  const minList = listChromeHeight + minListRows * listRowHeight
  // On a screen too short for both minimums the board keeps its own.
  const maxBoard = Math.max(minBoard, totalHeight - minList)
  // No stored fraction: the board shows TARGET_ROUNDS compact rounds. A drag stores one, which then wins.
  const wanted = fraction == null ? headerHeight + TARGET_ROUNDS * compactRowHeight : Math.round(fraction * totalHeight)
  const boardPx = Math.min(maxBoard, Math.max(minBoard, wanted))
  const measured = totalHeight > 0

  const fullHeight = rounds * fullRowHeight + headerHeight
  const density: BoardDensity = boardPx < fullHeight ? 'compact' : 'full'

  // Held in refs so a parent passing fresh inline callbacks doesn't re-fire the effects.
  const onHeightRef = useRef(onBoardHeight)
  onHeightRef.current = onBoardHeight
  const onDensityRef = useRef(onDensityChange)
  onDensityRef.current = onDensityChange
  useEffect(() => {
    if (measured) onHeightRef.current(boardPx)
  }, [measured, boardPx])
  const lastDensity = useRef<BoardDensity | null>(null)
  useEffect(() => {
    if (!measured || lastDensity.current === density) return
    lastDensity.current = density
    onDensityRef.current?.(density)
  }, [measured, density])

  const setBoardPx = useCallback(
    (px: number) => {
      if (totalHeight <= 0) return
      const clamped = Math.min(maxBoard, Math.max(minBoard, px))
      const f = clamped / totalHeight
      setFraction(f)
      writeFraction(f)
    },
    [totalHeight, maxBoard, minBoard],
  )
  const reset = useCallback(() => {
    setFraction(null)
    writeFraction(null)
  }, [])

  function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    switch (e.key) {
      case 'ArrowUp':
        setBoardPx(boardPx - compactRowHeight)
        break
      case 'ArrowDown':
        setBoardPx(boardPx + compactRowHeight)
        break
      case 'Home':
        setBoardPx(minBoard)
        break
      case 'End':
        setBoardPx(maxBoard)
        break
      case 'Enter':
        reset()
        break
      default:
        return
    }
    e.preventDefault()
  }

  const drag = useRef<{ startY: number; startPx: number } | null>(null)
  function onPointerDown(e: PointerEvent<HTMLDivElement>) {
    drag.current = { startY: e.clientY, startPx: boardPx }
    // jsdom has no pointer capture; the guard keeps tests from throwing.
    e.currentTarget.setPointerCapture?.(e.pointerId)
  }
  function onPointerMove(e: PointerEvent<HTMLDivElement>) {
    if (!drag.current) return
    setBoardPx(drag.current.startPx + (e.clientY - drag.current.startY))
  }
  function endDrag() {
    drag.current = null
  }

  return (
    <div
      className="room-divider"
      role="separator"
      aria-orientation="horizontal"
      aria-label="Resize board and player list"
      aria-valuenow={boardPx}
      aria-valuemin={minBoard}
      aria-valuemax={maxBoard}
      aria-valuetext={measured ? `Board ${Math.round((boardPx / totalHeight) * 100)}% of the room` : undefined}
      tabIndex={0}
      title="Drag to resize. Double-click to reset."
      onKeyDown={onKeyDown}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
      onDoubleClick={reset}
    >
      <span className="room-divider-grip" aria-hidden="true" />
    </div>
  )
}
