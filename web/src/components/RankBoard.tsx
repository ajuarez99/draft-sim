import { useEffect, useMemo, useRef, useState } from 'react'
import { hueFor } from '../hue'
import {
  isComplete,
  makeEmptyOrder,
  move,
  moveChipBy,
  ordinal,
  place,
  slotOf,
  toRosterIds,
  unplace,
  unplacedOf,
  type ChipId,
  type RankOrder,
} from '../rankOrder'

/**
 * claude/power-rankings-ballots.md "The drag board", built per the "Amended
 * after review (finding 10)" block, which is the actual spec -- read it
 * before touching this file. Used twice by PowerRankings.tsx (commissioner
 * ranking, member ballot) with different `ariaLabel`/`submitLabel` copy and
 * a different `onSubmit`; this component owns none of that copy or the
 * network call, only the ordering UI.
 *
 * Two parallel interaction paths, both first-class (AC3):
 *  - Pointer Events, hand-rolled (no DnD library -- see the design doc for
 *    why). Capture lives on the root container, never on a chip, because a
 *    chip's DOM node does NOT survive a placement: it moves from the tray
 *    list to a slot list (or back), which is a different React parent, so
 *    the node unmounts and remounts under a new one. Capturing on a node
 *    that's about to unmount is exactly the `lostpointercapture` trap finding
 *    10 names -- the container never unmounts mid-drag, so it can't happen.
 *  - Click-to-select + click-to-place, plus ArrowUp/ArrowDown on a focused
 *    placed chip and Escape to deselect. Not a fallback: this is how the
 *    board is used on a keyboard, by a screen reader, and by anyone for whom
 *    a slow careful drag is a worse experience than two clicks.
 *
 * Both paths funnel through the same three pure functions in rankOrder.ts
 * (place/move/unplace) -- the only ordering logic this file contains of its
 * own is deriving *which* of those three to call from where a chip started
 * and where it landed.
 */

export type RankBoardMember = {
  rosterId: number
  managerId: number | null
  /** Display name -- already resolved (Sleeper display name, or better),
   *  same convention as `StandingRow.manager` in api.ts. */
  manager: string | null
  /** Sleeper's `metadata.team_name`, resolved by the caller. Rendered only
   *  when present and not the literal string "TBD" (design doc finding 19) --
   *  checked again here defensively, since a caller could pass it through
   *  unresolved. */
  teamName: string | null
  /** This board's viewer. At most one true. Gets --crimson, house rule. */
  isMe?: boolean
}

export type RankBoardProps = {
  /** The league's members, one chip each. `members`' own order is only used
   *  as the tray's default order -- placement state lives entirely in this
   *  component. The slot count is fixed to `members.length` at mount, the
   *  same "seed once" contract as `initialOrder` below -- if membership can
   *  change while a ballot is open, pass a `key` that changes with it so
   *  React remounts rather than leaving a stale slot count. */
  members: RankBoardMember[]
  /** Accessible name for the whole widget (e.g. "Commissioner ranking",
   *  "Your ballot") -- this component renders no heading of its own, so the
   *  two call sites' different copy lives with the caller. */
  ariaLabel: string
  /** Seeds the board from an existing ordering (roster ids, rank 1 first) --
   *  e.g. re-opening a submitted ballot to edit it. Read once, on mount, the
   *  usual React way to say "this is initial state, not a controlled prop";
   *  pass a different `key` from the caller to force a fresh seed. Any id
   *  not in `members`, or beyond the slot count, is silently dropped. */
  initialOrder?: number[]
  /** Fires with the complete ordering (rank 1 first) when the user submits.
   *  Never called with a partial ordering -- the button is disabled until
   *  every slot is filled. This component makes no network call itself. */
  onSubmit: (orderedRosterIds: number[]) => void
  /** Submit button copy. Default 'Submit'. */
  submitLabel?: string
  /** True while the caller's own submit request is in flight -- disables the
   *  control and swaps its label. This component has no idea whether the
   *  request succeeded; the caller decides what happens next. */
  submitting?: boolean
}

const DRAG_THRESHOLD_PX = 6

type DragOrigin = { kind: 'tray' } | { kind: 'slot'; index: number }

type DragSession = {
  pointerId: number
  chipId: ChipId
  origin: DragOrigin
  startX: number
  startY: number
  grabDX: number
  grabDY: number
  lastX: number
  lastY: number
}

type HitResult = { kind: 'slot'; index: number } | { kind: 'tray' } | null

function chipName(m: RankBoardMember | undefined, chipId: ChipId): string {
  const name = m?.manager?.trim()
  return name && name.length > 0 ? name : `Roster ${chipId}`
}

function chipTeam(m: RankBoardMember | undefined): string | null {
  const t = m?.teamName?.trim()
  if (!t || t.toUpperCase() === 'TBD') return null
  return t
}

/** Same seed/formula as SeatPopover's `avatarStyle` and DraftBoard's `hue` --
 *  `hueFor(String(managerId))`, matching the chart (design doc finding 17).
 *  `?? rosterId` is the same defensive fallback PowerRankings.tsx:96 already
 *  uses; `league_member`'s FK means a null `managerId` shouldn't occur here,
 *  but this stays consistent with the rest of the app if it ever does. */
function avatarStyleFor(m: RankBoardMember): { background: string; color: string } {
  if (m.isMe) return { background: 'var(--crimson)', color: 'var(--bg)' }
  const hue = hueFor(String(m.managerId ?? m.rosterId))
  return { background: `oklch(28% 0.03 ${hue})`, color: `oklch(82% 0.1 ${hue})` }
}

function buildInitialOrder(members: RankBoardMember[], initialOrder: number[] | undefined): RankOrder {
  let order = makeEmptyOrder(members.length)
  if (!initialOrder) return order
  const validIds = new Set(members.map((m) => m.rosterId))
  initialOrder.forEach((rosterId, i) => {
    if (i < order.length && validIds.has(rosterId)) {
      order = place(order, rosterId, i)
    }
  })
  return order
}

export default function RankBoard({
  members,
  ariaLabel,
  initialOrder,
  onSubmit,
  submitLabel = 'Submit',
  submitting = false,
}: RankBoardProps) {
  const [order, setOrder] = useState<RankOrder>(() => buildInitialOrder(members, initialOrder))
  const [selected, setSelected] = useState<ChipId | null>(null)
  const [draggingChipId, setDraggingChipId] = useState<ChipId | null>(null)
  const [liveMessage, setLiveMessage] = useState('')

  const membersById = useMemo(() => new Map(members.map((m) => [m.rosterId, m])), [members])
  const allIds = useMemo(() => members.map((m) => m.rosterId), [members])
  const unplaced = unplacedOf(order, allIds)
  const complete = isComplete(order)

  // ---- refs: mutable drag/measurement state that must never trigger a
  // render on its own (a pointermove can fire dozens of times a second). ----
  const containerRef = useRef<HTMLDivElement | null>(null)
  const trayRef = useRef<HTMLDivElement | null>(null)
  const slotElsRef = useRef<Array<HTMLLIElement | null>>([])
  const ghostElRef = useRef<HTMLDivElement | null>(null)
  const slotRectsRef = useRef<Array<DOMRect | null>>([])
  const trayRectRef = useRef<DOMRect | null>(null)
  const dragRef = useRef<DragSession | null>(null)
  const draggingRef = useRef(false)
  const hoverRef = useRef<HitResult>(null)
  const suppressClickRef = useRef(false)
  const pendingFocusRef = useRef<ChipId | null>(null)
  const chipButtonRefs = useRef(new Map<ChipId, HTMLButtonElement>())

  function measureRects() {
    slotRectsRef.current = slotElsRef.current.map((el) => el?.getBoundingClientRect() ?? null)
    trayRectRef.current = trayRef.current?.getBoundingClientRect() ?? null
  }

  // Re-measure whenever the board's own content changes shape -- a
  // placement can shrink the tray and reflow it to fewer rows, which moves
  // every slot below it (design doc, finding 10). Keeping this unconditional
  // rather than "only during a drag" means the very first measurement at the
  // next drag's threshold-crossing is already correct too.
  useEffect(() => {
    measureRects()
  }, [order])

  // Re-focus the chip a keyboard or click action just moved -- see the
  // per-slot `<li key={i}>` note below on why this can't just rely on React
  // preserving the DOM node.
  useEffect(() => {
    if (pendingFocusRef.current != null) {
      chipButtonRefs.current.get(pendingFocusRef.current)?.focus()
      pendingFocusRef.current = null
    }
  })

  function announce(chipId: ChipId, nextOrder: RankOrder) {
    const name = chipName(membersById.get(chipId), chipId)
    const rank = slotOf(nextOrder, chipId)
    setLiveMessage(rank === null ? `${name} moved back to the tray, unranked.` : `Placed ${name} ${ordinal(rank + 1)} of ${members.length}.`)
  }

  // ---------------------------------------------------------------------
  // Click / keyboard path
  // ---------------------------------------------------------------------

  // These three handlers read `order` from the render closure rather than
  // using `setOrder`'s functional-updater form -- each fires from a single
  // discrete browser event (a click, a keydown, a pointerup), never as part
  // of a batch of same-tick updates to `order`, so there's no staleness risk.
  // That in turn means the announce/focus side effects below can sit as
  // plain statements in the handler body instead of inside a `setOrder`
  // updater, which React requires to be a pure function -- StrictMode
  // double-invokes updaters in dev specifically to catch side effects like
  // these living in the wrong place.
  function placeSelectedInto(targetSlot: number) {
    if (selected == null) return
    const chipId = selected
    const originSlot = slotOf(order, chipId)
    const next = originSlot == null ? place(order, chipId, targetSlot) : move(order, originSlot, targetSlot)
    if (next !== order) {
      pendingFocusRef.current = chipId
      announce(chipId, next)
      setOrder(next)
    }
    setSelected(null)
  }

  function handleChipClick(chipId: ChipId, slotIndex: number | null) {
    if (suppressClickRef.current) {
      // The click that always follows a pointerup, even one that just
      // finished a real drag -- see the pointerdown/pointerup comments.
      suppressClickRef.current = false
      return
    }
    if (slotIndex == null) {
      // A tray chip: click always (re)selects it, or deselects if it was
      // already the selection. A tray chip has no slot to hand off to, so
      // there's no "place the previous selection here" reading available.
      setSelected((cur) => (cur === chipId ? null : chipId))
      return
    }
    if (selected === null) {
      setSelected(chipId)
    } else if (selected === chipId) {
      setSelected(null)
    } else {
      placeSelectedInto(slotIndex)
    }
  }

  function handleEmptySlotClick(slotIndex: number) {
    if (suppressClickRef.current) {
      suppressClickRef.current = false
      return
    }
    placeSelectedInto(slotIndex)
  }

  function handleChipKeyDown(e: React.KeyboardEvent<HTMLButtonElement>, chipId: ChipId, slotIndex: number | null) {
    if (e.key === 'Escape') {
      setSelected(null)
      return
    }
    if (slotIndex == null) return // arrow keys only move an already-placed chip
    const delta = e.key === 'ArrowUp' ? -1 : e.key === 'ArrowDown' ? 1 : null
    if (delta == null) return
    e.preventDefault()
    const next = moveChipBy(order, chipId, delta)
    if (next !== order) {
      pendingFocusRef.current = chipId
      announce(chipId, next)
      setOrder(next)
    }
  }

  // ---------------------------------------------------------------------
  // Pointer path
  // ---------------------------------------------------------------------

  function hitTest(x: number, y: number): HitResult {
    const slots = slotRectsRef.current
    for (let i = 0; i < slots.length; i++) {
      const r = slots[i]
      if (r && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return { kind: 'slot', index: i }
    }
    const tray = trayRectRef.current
    if (tray && x >= tray.left && x <= tray.right && y >= tray.top && y <= tray.bottom) return { kind: 'tray' }
    return null
  }

  function setSlotHoverClass(index: number | null) {
    slotElsRef.current.forEach((el, i) => el?.classList.toggle('drop-target', i === index))
  }

  function handleChipPointerDown(e: React.PointerEvent<HTMLButtonElement>, chipId: ChipId, slotIndex: number | null) {
    if (e.button !== 0) return // ignore non-primary buttons (right/middle click, secondary touch)
    const rect = e.currentTarget.getBoundingClientRect()
    dragRef.current = {
      pointerId: e.pointerId,
      chipId,
      origin: slotIndex == null ? { kind: 'tray' } : { kind: 'slot', index: slotIndex },
      startX: e.clientX,
      startY: e.clientY,
      grabDX: e.clientX - rect.left,
      grabDY: e.clientY - rect.top,
      lastX: e.clientX,
      lastY: e.clientY,
    }
    // Capture is deliberately NOT taken here. It is taken in pointermove, the
    // moment the drag threshold is crossed (see handleContainerPointerMove).
    //
    // Capturing on pointerdown breaks the entire click path, and does it
    // invisibly: while a pointer is captured, the `click` event synthesized
    // from the pointer sequence is dispatched to the CAPTURE TARGET, not to
    // the element under the cursor. Capturing on the container therefore
    // meant every chip's onClick was retargeted to the container and never
    // fired -- so click-to-select did nothing, and the whole keyboard/click
    // route this component treats as first-class was dead on arrival while
    // dragging still worked perfectly. Verified in the real browser, not by
    // reading: the drag put a chip in slot 1, and clicking a chip then a slot
    // did nothing at all.
    //
    // Below the threshold there is no drag to capture for anyway -- a press
    // that never moves is a click, and it should reach the chip like one.
  }

  function endDrag(pointerId: number) {
    // Null the ref *before* releasing capture: `releasePointerCapture` can
    // dispatch `lostpointercapture` synchronously, and that handler's own
    // "is a drag still live for this pointer" guard reads `dragRef.current`
    // -- nulling it first makes that guard see "no" immediately, rather than
    // racing a reentrant call back into this same function.
    dragRef.current = null
    draggingRef.current = false
    hoverRef.current = null
    try {
      containerRef.current?.releasePointerCapture(pointerId)
    } catch {
      // already released -- fine, this is defensive
    }
    setSlotHoverClass(null)
    setDraggingChipId(null)
  }

  function handleContainerPointerMove(e: React.PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== e.pointerId) return
    drag.lastX = e.clientX
    drag.lastY = e.clientY

    if (!draggingRef.current) {
      const dx = e.clientX - drag.startX
      const dy = e.clientY - drag.startY
      if (Math.hypot(dx, dy) < DRAG_THRESHOLD_PX) return
      draggingRef.current = true
      // Capture starts HERE, not on pointerdown -- this is the first moment
      // we know it is a drag rather than a click (see handleChipPointerDown
      // for why capturing earlier silently kills the click path). Captured on
      // the stable root, never on the chip: the chip's DOM node does not
      // survive a placement (it moves between the tray list and a slot list,
      // a different React parent), and capturing a node that is about to
      // unmount is the `lostpointercapture` trap this component exists to
      // avoid. The container never unmounts mid-drag.
      containerRef.current?.setPointerCapture(e.pointerId)
      measureRects()
      setDraggingChipId(drag.chipId)
    }

    e.preventDefault()
    if (ghostElRef.current) {
      ghostElRef.current.style.transform = `translate3d(${e.clientX - drag.grabDX}px, ${e.clientY - drag.grabDY}px, 0)`
    }
    const hit = hitTest(e.clientX, e.clientY)
    hoverRef.current = hit
    setSlotHoverClass(hit?.kind === 'slot' ? hit.index : null)
  }

  function handleContainerPointerUp(e: React.PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== e.pointerId) return
    if (draggingRef.current) {
      e.preventDefault()
      suppressClickRef.current = true
      const hit = hoverRef.current
      let next = order
      if (hit?.kind === 'slot') {
        next = drag.origin.kind === 'tray' ? place(order, drag.chipId, hit.index) : move(order, drag.origin.index, hit.index)
      } else if (hit?.kind === 'tray' && drag.origin.kind === 'slot') {
        next = unplace(order, drag.chipId)
      }
      if (next !== order) {
        pendingFocusRef.current = drag.chipId
        announce(drag.chipId, next)
        setOrder(next)
      }
    }
    endDrag(drag.pointerId)
  }

  function handleContainerPointerCancel(e: React.PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== e.pointerId) return
    // OS-level interruption. No order change -- the chip stays exactly
    // where it started, and the ghost must not be left stranded (AC4).
    endDrag(drag.pointerId)
  }

  function handleContainerLostPointerCapture(e: React.PointerEvent<HTMLDivElement>) {
    // Fires after our own explicit release too (normal end-of-drag), so this
    // must be idempotent: only act if we still think a drag is live for this
    // pointer. Also the safety net if capture is ever released some other
    // way than pointerup/pointercancel.
    if (dragRef.current?.pointerId === e.pointerId) {
      endDrag(e.pointerId)
    }
  }

  function handleContextMenu(e: React.MouseEvent) {
    if (dragRef.current) e.preventDefault()
  }

  // ---------------------------------------------------------------------
  // Submit
  // ---------------------------------------------------------------------

  function handleSubmit() {
    const rosterIds = toRosterIds(order)
    if (rosterIds) onSubmit(rosterIds)
  }

  function renderChip(m: RankBoardMember, slotIndex: number | null) {
    const chipId = m.rosterId
    const avatarStyle = avatarStyleFor(m)
    const name = chipName(m, chipId)
    const team = chipTeam(m)
    const isSelected = selected === chipId
    const isDragSource = draggingChipId === chipId
    const classes = [
      'rankboard-chip',
      m.isMe ? 'mine' : '',
      isSelected ? 'selected' : '',
      isDragSource ? 'drag-source' : '',
    ]
      .filter(Boolean)
      .join(' ')

    return (
      <button
        key={chipId}
        type="button"
        ref={(el) => {
          if (el) chipButtonRefs.current.set(chipId, el)
          else chipButtonRefs.current.delete(chipId)
        }}
        className={classes}
        aria-pressed={isSelected}
        aria-label={
          slotIndex == null
            ? `${name}${team ? `, ${team}` : ''}, unranked`
            : `${name}${team ? `, ${team}` : ''}, rank ${slotIndex + 1} of ${members.length}`
        }
        onPointerDown={(e) => handleChipPointerDown(e, chipId, slotIndex)}
        onClick={() => handleChipClick(chipId, slotIndex)}
        onKeyDown={(e) => handleChipKeyDown(e, chipId, slotIndex)}
      >
        <span className="avatar" style={avatarStyle} aria-hidden="true">
          {name.charAt(0).toUpperCase()}
        </span>
        <span className="rankboard-chip-text">
          <span className="rankboard-chip-name">{name}</span>
          {team && <span className="rankboard-chip-team tiny muted">{team}</span>}
        </span>
      </button>
    )
  }

  const draggingMember = draggingChipId != null ? membersById.get(draggingChipId) : undefined

  return (
    <div
      className={`rankboard${draggingChipId != null ? ' dragging' : ''}`}
      ref={containerRef}
      role="group"
      aria-label={ariaLabel}
      onPointerMove={handleContainerPointerMove}
      onPointerUp={handleContainerPointerUp}
      onPointerCancel={handleContainerPointerCancel}
      onLostPointerCapture={handleContainerLostPointerCapture}
      onContextMenu={handleContextMenu}
    >
      <div aria-live="polite" className="rankboard-sr-only">
        {liveMessage}
      </div>

      {/* Rendered only while something is actually unplaced. A seeded or
          restored board opens complete (see PowerRankings.tsx's seedOrder),
          and submission requires every slot filled anyway -- there is no
          reason to keep a drop target around for "send a chip back to
          unranked" once nothing benefits from it, and reordering is already
          fully covered by dragging one slot onto another. This does mean a
          chip can never be unplaced once the board is complete; that's
          intentional, not a gap -- see the effort of getting back here. */}
      {unplaced.length > 0 && (
        <div className="rankboard-tray" ref={trayRef}>
          {unplaced.map((id) => renderChip(membersById.get(id)!, null))}
        </div>
      )}

      <ol className="rankboard-slots">
        {order.map((chipId, i) => (
          <li
            key={i}
            className="rankboard-slot"
            ref={(el) => {
              slotElsRef.current[i] = el
            }}
          >
            <span className="rankboard-slot-index mono" aria-hidden="true">
              {i + 1}
            </span>
            {chipId != null ? (
              renderChip(membersById.get(chipId)!, i)
            ) : (
              <button type="button" className="rankboard-slot-empty" aria-label={`Slot ${i + 1}, empty`} onClick={() => handleEmptySlotClick(i)}>
                <span className="tiny muted">Empty</span>
              </button>
            )}
          </li>
        ))}
      </ol>

      {draggingChipId != null && draggingMember && (
        <div className="rankboard-ghost" ref={ghostElRef} aria-hidden="true">
          <span className="avatar" style={avatarStyleFor(draggingMember)}>
            {chipName(draggingMember, draggingMember.rosterId).charAt(0).toUpperCase()}
          </span>
          <span className="rankboard-chip-name">{chipName(draggingMember, draggingMember.rosterId)}</span>
        </div>
      )}

      <div className="controls">
        <button type="button" onClick={handleSubmit} disabled={!complete || submitting}>
          {submitting ? 'Submitting…' : submitLabel}
        </button>
        {!complete && (
          <span className="small muted">
            {unplaced.length} of {members.length} still unranked
          </span>
        )}
      </div>
    </div>
  )
}
