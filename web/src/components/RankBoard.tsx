import { useEffect, useMemo, useRef, useState } from 'react'
import Avatar from './Avatar'
import { autoScrollStep, clampScroll, targetSlotFor } from '../dragGesture'
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
  avatarId?: string | null
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

// Autoscroll feel, retuned 2026-09-22 after the first numbers were tried on a
// real phone and were plainly wrong.
//
// They were 64px and 12px/frame. On the 435px lane a 12-team ballot gets at
// 375x812 that made 29% of the visible rows an autoscroll trigger, and 12px a
// frame is 720px/s against a scroll range of only 137px -- the whole list
// travelled end to end in 190ms. Drift a finger within about a row and a third
// of either edge and the board snapped to the end.
//
// None of this showed up in verification because the scenario run was "hold at
// the edge until it reaches the end and confirm it stops", which is exactly
// the case where flying looks correct.
//
// The first correction (40px, 5px/frame, a quadratic ramp starting at zero)
// overshot in the other direction: five pixels into the zone came out at
// 4.7px/s, eighteen seconds to cross the list, so the outer half of the zone
// did nothing and the honest report was that it would not scroll at all.
//
// 56px is a little wider than one row, so the zone is actually reachable while
// aiming at the top or bottom row. dragGesture.ts caps it at a fifth of the
// container and ramps from a floor, so entering the zone creeps visibly
// (~95px/s) and only the last few pixels commit to 420px/s.
const AUTOSCROLL_ZONE_PX = 56
const AUTOSCROLL_MAX_PX_PER_FRAME = 7

/** How far above the fingertip the ghost rides on touch. A finger covers
 *  roughly its own width of screen; without this the chip you are moving is
 *  the one thing you cannot see (FR-011). Zero for a mouse, which occludes
 *  nothing. */
const TOUCH_GHOST_LIFT_PX = 28

/** How long a finger must rest on a row before it becomes draggable.
 *
 *  Was 320ms, and that made the list impossible to scroll. People do not
 *  flick a list the instant they touch it -- they land a thumb, settle, then
 *  move -- and any settle longer than the timer armed a drag, after which the
 *  non-passive touchmove below refuses the browser its scroll. So roughly
 *  every other attempt to scroll moved a team instead.
 *
 *  450ms is near the platform long-press convention and leaves room to land
 *  and go. The grip is still there for anyone who does not want to wait at
 *  all. */
const LONG_PRESS_MS = 450

/** Movement that cancels a pending long press. Bigger than a resting thumb's
 *  wobble, smaller than any intentional swipe. */
const LONG_PRESS_SLOP_PX = 10

/**
 * The nearest ancestor that actually scrolls, or null for the viewport.
 *
 * Not hard-coded to `.rankboard-slots` even though that is what scrolls
 * today (styles.css gives it `overflow-y: auto` inside `.modal-card.wide`):
 * this component is used twice and owns none of its callers' chrome, so the
 * scroller is a property of where it was rendered, not of what it is.
 */
function resolveScrollParent(from: Element | null): Element | null {
  let el: Element | null = from
  while (el && el !== document.body && el !== document.documentElement) {
    const overflowY = getComputedStyle(el).overflowY
    if ((overflowY === 'auto' || overflowY === 'scroll') && el.scrollHeight > el.clientHeight) return el
    el = el.parentElement
  }
  return null
}

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
  /** Resolved once at threshold-crossing, never per frame. Null = viewport. */
  scrollParent: Element | null
  /** Where this chip sits *right now* under live reordering -- which is not
   *  `origin.index` after the first move, and is the authority for the next
   *  one. Null for a chip dragged out of the tray, which is not in the
   *  ordering yet and is still placed on drop. */
  currentSlot: number | null
  /** What is driving this gesture. Touch must start on the grip; a mouse or
   *  pen may start anywhere on the chip (FR-021 -- the handle must cost the
   *  pointer path nothing). */
  pointerType: string
  startedOnHandle: boolean
  /** True between a touch landing on a row body and the hold timer firing --
   *  the window in which this gesture could still turn out to be a scroll.
   *  Nothing is captured and nothing moves while it is true. */
  pending: boolean
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
  const slotsElRef = useRef<HTMLOListElement | null>(null)
  const autoScrollRef = useRef<number | null>(null)
  const remeasureRef = useRef<(() => void) | null>(null)
  const longPressRef = useRef<number | null>(null)
  /** Set while the autoscroll loop is driving scrollTop itself, so the
   *  document scroll listener does not re-measure a second time in the same
   *  frame -- two forced layouts per frame on a phone is exactly the kind of
   *  cost that reads as the chip lagging the finger. */
  const selfScrollingRef = useRef(false)
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

  // Once a touch drag is live the page must not also scroll under it. React
  // attaches `onTouchMove` as a PASSIVE listener, where preventDefault is a
  // no-op with a console warning, so the only way to say "this gesture is
  // mine now" is a listener we add ourselves with `{ passive: false }`.
  //
  // This is safe precisely because of the hold: the finger has been still for
  // LONG_PRESS_MS, so the browser has not begun a scroll it would refuse to
  // give back. A grip drag does not need this -- `.rankboard-handle` carries
  // `touch-action: none` -- but it costs nothing to cover both.
  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const onTouchMove = (e: TouchEvent) => {
      const drag = dragRef.current
      if (drag && !drag.pending && draggingRef.current && drag.pointerType === 'touch') {
        e.preventDefault()
      }
    }
    el.addEventListener('touchmove', onTouchMove, { passive: false })
    return () => el.removeEventListener('touchmove', onTouchMove)
  }, [])

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

  /** One rank up or down, from the keyboard or from the on-screen buttons --
   *  the single nudge rule both routes call. Keeps the chip selected, because
   *  moving a team two ranks means pressing twice. */
  function nudge(chipId: ChipId, delta: -1 | 1) {
    const next = moveChipBy(order, chipId, delta)
    if (next === order) return
    pendingFocusRef.current = chipId
    announce(chipId, next)
    setOrder(next)
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
    nudge(chipId, delta)
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

  // ---- re-measure while a drag is live --------------------------------
  //
  // The original review of this board (claude/power-rankings-ballots.md,
  // finding 10) said it in as many words: "Cache, but re-measure on `scroll`
  // and `resize`, and after every placement." Only the last third shipped --
  // the useEffect on `order` above -- and the missing two thirds are exactly
  // why a drop could land on the wrong row once anything scrolled. A cached
  // `getBoundingClientRect()` is viewport-relative and goes stale the instant
  // the list moves under it.
  //
  // Listening on `document` in the capture phase rather than on the resolved
  // scroll parent: scroll events do not bubble, and an ancestor other than
  // the one we resolved can still move us.
  function attachRemeasure() {
    if (remeasureRef.current) return
    const onMove = () => {
      // The autoscroll loop measures inline right after it sets scrollTop, so
      // the scroll event it causes would be a second forced layout in the same
      // frame. Only other people's scrolls need this listener.
      if (selfScrollingRef.current) return
      measureRects()
    }
    remeasureRef.current = onMove
    document.addEventListener('scroll', onMove, { capture: true, passive: true })
    window.addEventListener('resize', onMove)
  }

  function detachRemeasure() {
    const onMove = remeasureRef.current
    if (!onMove) return
    document.removeEventListener('scroll', onMove, { capture: true })
    window.removeEventListener('resize', onMove)
    remeasureRef.current = null
  }

  // ---- autoscroll ------------------------------------------------------
  //
  // A twelve-slot board overflows its modal on a phone by about three rows,
  // so without this a chip can only ever be dropped somewhere already on
  // screen and last-to-first cannot be done in one gesture (US1).
  function startAutoScroll() {
    if (autoScrollRef.current != null) return
    const frame = () => {
      const drag = dragRef.current
      if (!drag || !draggingRef.current) {
        autoScrollRef.current = null
        return
      }
      const parent = drag.scrollParent
      if (parent) {
        const r = parent.getBoundingClientRect()
        const step = autoScrollStep(drag.lastY, r.top, r.bottom, AUTOSCROLL_ZONE_PX, AUTOSCROLL_MAX_PX_PER_FRAME)
        if (step !== 0) {
          const next = clampScroll(parent.scrollTop + step, parent.scrollHeight, parent.clientHeight)
          if (next !== parent.scrollTop) {
            selfScrollingRef.current = true
            parent.scrollTop = next
            selfScrollingRef.current = false
            // Inline rather than waiting for the scroll listener: that event
            // is dispatched asynchronously, and applyPointer below needs
            // rects from *after* this scroll, not one frame behind it.
            measureRects()
          } else if (step !== 0) {
            // Already hard against an end. Nothing will change until the
            // pointer leaves the zone, so stop paying for the rest of the
            // frame's work.
            autoScrollRef.current = requestAnimationFrame(frame)
            return
          }
        }
      }
      // Every frame, not only on pointermove. A finger can be perfectly still
      // while the list travels underneath it -- the rank under it changes with
      // no pointer event to announce it, and driving placement from
      // pointermove alone is the way to build autoscroll that looks right and
      // lands wrong.
      applyPointer(drag.lastX, drag.lastY)
      autoScrollRef.current = requestAnimationFrame(frame)
    }
    autoScrollRef.current = requestAnimationFrame(frame)
  }

  function stopAutoScroll() {
    if (autoScrollRef.current != null) {
      cancelAnimationFrame(autoScrollRef.current)
      autoScrollRef.current = null
    }
  }

  function handleChipPointerDown(
    e: React.PointerEvent<Element>,
    chipId: ChipId,
    slotIndex: number | null,
    fromHandle = false,
  ) {
    if (e.button !== 0) return // ignore non-primary buttons (right/middle click, secondary touch)

    // A touch on the row body starts PENDING: neither a scroll nor a drag yet.
    //
    // This used to return outright, leaving the grip as the only way to pick a
    // team up on a phone, and the reasoning was that a hold timer has to guess
    // at intent while a handle does not. Real use said otherwise -- people
    // reach for the row, get a scroll, and conclude dragging is broken. The
    // handle is discoverable only once you already know it is there.
    //
    // So: hold still and the row becomes draggable; move before the timer and
    // it was a scroll after all, which is the reading a finger that is already
    // travelling deserves. The grip keeps its no-delay path for anyone who
    // does know, and a mouse never waits (FR-021).
    const pending = e.pointerType === 'touch' && !fromHandle

    // Measure the CHIP, not `e.currentTarget` -- when the gesture starts on
    // the grip those are different elements, and a ghost offset from the grip
    // rather than the chip jumps sideways the instant it appears.
    const rect = (chipButtonRefs.current.get(chipId) ?? e.currentTarget).getBoundingClientRect()
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
      scrollParent: null,
      currentSlot: slotIndex,
      pointerType: e.pointerType,
      startedOnHandle: fromHandle,
      pending,
    }

    if (pending) {
      clearLongPress()
      longPressRef.current = window.setTimeout(() => {
        longPressRef.current = null
        const drag = dragRef.current
        if (!drag || !drag.pending) return
        drag.pending = false
        beginDrag(drag, drag.lastX, drag.lastY)
      }, LONG_PRESS_MS)
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
    // Invariant: autoScrollRef is null whenever dragRef is null. A loop that
    // outlives its drag scrolls a list nobody is touching. Both of these are
    // idempotent, which matters because endDrag is reached from pointerup,
    // pointercancel AND lostpointercapture (FR-014).
    clearLongPress()
    stopAutoScroll()
    detachRemeasure()
    try {
      containerRef.current?.releasePointerCapture(pointerId)
    } catch {
      // already released -- fine, this is defensive
    }
    setSlotHoverClass(null)
    setDraggingChipId(null)
  }

  function clearLongPress() {
    if (longPressRef.current != null) {
      window.clearTimeout(longPressRef.current)
      longPressRef.current = null
    }
  }

  /**
   * Commit to a drag. Reached two ways -- a mouse or grip crossing the 6px
   * threshold, and a touch resting on a row for LONG_PRESS_MS -- so it lives
   * here rather than inline in pointermove, which is where it used to be when
   * the threshold was the only route in.
   */
  function beginDrag(drag: DragSession, x: number, y: number) {
    draggingRef.current = true
    // Capture starts HERE, not on pointerdown. Capturing on pointerdown breaks
    // the entire click path, and does it invisibly: while a pointer is
    // captured, the `click` synthesized from the pointer sequence is
    // dispatched to the CAPTURE TARGET, not to the element under the cursor.
    // Capturing on the container therefore retargeted every chip's onClick to
    // the container, so click-to-select did nothing while dragging still
    // worked perfectly. Verified in a real browser, not by reading.
    try {
      containerRef.current?.setPointerCapture(drag.pointerId)
    } catch {
      // NotFoundError if the pointer is no longer active by now -- a fast
      // flick, or an interruption landing between pointerdown and here.
      // Without capture the drag still works while the pointer is over the
      // board, and pointerup/pointercancel still end it; that is a far better
      // failure than an uncaught throw leaving draggingRef true with no ghost.
    }
    measureRects()
    drag.scrollParent = resolveScrollParent(slotsElRef.current)
    attachRemeasure()
    startAutoScroll()
    setDraggingChipId(drag.chipId)
    // A phone has no cursor to change and no hover to lift, so the only way to
    // say "this is yours to move now" is to buzz. Absent on iOS Safari, which
    // is why nothing depends on it.
    try {
      navigator.vibrate?.(10)
    } catch {
      // Some browsers throw on vibrate in a non-interactive context.
    }
    moveGhost(drag, x, y)
  }

  function handleContainerPointerMove(e: React.PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== e.pointerId) return
    drag.lastX = e.clientX
    drag.lastY = e.clientY

    // Still deciding whether this touch was a scroll. Any real travel says it
    // was: drop the gesture entirely and let the browser have it.
    if (drag.pending) {
      if (Math.hypot(e.clientX - drag.startX, e.clientY - drag.startY) >= LONG_PRESS_SLOP_PX) {
        clearLongPress()
        dragRef.current = null
      }
      return
    }

    if (!draggingRef.current) {
      const dx = e.clientX - drag.startX
      const dy = e.clientY - drag.startY
      if (Math.hypot(dx, dy) < DRAG_THRESHOLD_PX) return
      beginDrag(drag, e.clientX, e.clientY)
    }

    e.preventDefault()
    moveGhost(drag, e.clientX, e.clientY)
    applyPointer(e.clientX, e.clientY)
  }

  /** The floating chip under the finger. Separated from applyPointer because
   *  the ghost tracks the POINTER, not the list: while autoscroll runs with a
   *  still finger the ghost must not move, so there is no reason to rewrite
   *  its transform sixty times a second. */
  function moveGhost(drag: DragSession, x: number, y: number) {
    const el = ghostElRef.current
    if (!el) return
    const lift = drag.pointerType === 'touch' ? TOUCH_GHOST_LIFT_PX : 0
    el.style.transform = `translate3d(${x - drag.grabDX}px, ${y - drag.grabDY - lift}px, 0)`
  }

  /**
   * Everything that follows the pointer. Called from `pointermove`, and from
   * every autoscroll frame -- which is why it takes coordinates rather than
   * an event.
   *
   * A chip already in the ordering reorders LIVE: the board commits
   * `move()` as the pointer crosses each row's midpoint, exactly as
   * ArrowUp/ArrowDown already does. That is what makes the stale-rect class
   * of bug impossible rather than merely less likely -- there is no remembered
   * measurement consulted at drop time, because there is no placement at drop
   * time. It is also what shows the landing spot around a fingertip: the
   * indication is the board itself, renumbered.
   *
   * A chip dragged out of the tray is not in the ordering yet, so it keeps
   * the deferred hit-test-and-place path and its `.drop-target` tint.
   */
  function applyPointer(x: number, y: number) {
    const drag = dragRef.current
    if (!drag || !draggingRef.current) return

    // The tray is the only reason a placed chip still needs a hit test, and it
    // is not rendered at all once every slot is filled -- which is the normal
    // state of a seeded ballot. Skipping it then takes twelve rect
    // comparisons out of every animation frame.
    if (drag.currentSlot == null || trayRef.current) {
      const hit = hitTest(x, y)
      hoverRef.current = hit
      if (drag.currentSlot == null) {
        setSlotHoverClass(hit?.kind === 'slot' ? hit.index : null)
        return
      }
    }

    const from = drag.currentSlot
    const target = targetSlotFor(y, slotRectsRef.current, from)
    if (target !== from) {
      drag.currentSlot = target
      // Functional updater, and deliberately side-effect free: unlike the
      // click and keyboard handlers above, this one fires from an rAF loop
      // whose closure would otherwise hold a stale `order`. The announcement
      // is made once, on release -- announcing each micro-move would flood a
      // screen reader.
      setOrder((prev) => move(prev, from, target))
    }
  }

  function handleContainerPointerUp(e: React.PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== e.pointerId) return
    if (draggingRef.current) {
      e.preventDefault()
      suppressClickRef.current = true
      const hit = hoverRef.current
      const name = chipName(membersById.get(drag.chipId), drag.chipId)

      if (drag.currentSlot == null) {
        // Out of the tray: still a deferred placement, because a tray chip is
        // not in the ordering to reorder live.
        const next = hit?.kind === 'slot' ? place(order, drag.chipId, hit.index) : order
        if (next !== order) {
          pendingFocusRef.current = drag.chipId
          announce(drag.chipId, next)
          setOrder(next)
        } else {
          setLiveMessage(`${name} is still unranked. Nothing changed.`)
        }
      } else if (hit?.kind === 'tray') {
        const next = unplace(order, drag.chipId)
        if (next !== order) {
          pendingFocusRef.current = drag.chipId
          announce(drag.chipId, next)
          setOrder(next)
        }
      } else {
        // Live reordering has already applied every move this gesture made,
        // including when the pointer ends up outside the list -- the board has
        // been showing the result the whole way, so committing what is on
        // screen is the honest end, and snapping back would contradict it.
        // All that is left is to say what happened, once (FR-012, FR-013).
        pendingFocusRef.current = drag.chipId
        const startedAt = drag.origin.kind === 'slot' ? drag.origin.index : null
        if (drag.currentSlot === startedAt) {
          setLiveMessage(`${name} is still ranked ${ordinal(drag.currentSlot + 1)} of ${members.length}. Nothing changed.`)
        } else {
          setLiveMessage(`Placed ${name} ${ordinal(drag.currentSlot + 1)} of ${members.length}.`)
        }
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
        <Avatar avatarId={m.avatarId} seed={String(m.managerId ?? m.rosterId)} label={name} isMe={m.isMe} />
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

      <ol className="rankboard-slots" ref={slotsElRef}>
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
              <>
                {renderChip(membersById.get(chipId)!, i)}
                {/* The tap route's "arrows", made real on a device with no
                    arrow keys. Same `moveChipBy` the keydown handler calls, so
                    there is one nudge rule and not two. Shown only on the
                    selected chip: twenty-four always-on buttons would be a
                    toolbar, not a ranking. */}
                {selected === chipId && (
                  <span className="rankboard-nudge">
                    <button
                      type="button"
                      aria-label={`Move ${chipName(membersById.get(chipId), chipId)} up one rank`}
                      disabled={i === 0}
                      onClick={() => nudge(chipId, -1)}
                    >
                      ▲
                    </button>
                    <button
                      type="button"
                      aria-label={`Move ${chipName(membersById.get(chipId), chipId)} down one rank`}
                      disabled={i === order.length - 1}
                      onClick={() => nudge(chipId, 1)}
                    >
                      ▼
                    </button>
                  </span>
                )}
                {/* A sibling of the chip, not a child: the chip is a <button>
                    and nesting one interactive element inside another is
                    invalid. `aria-hidden` because this is a drag affordance
                    and dragging is the one route assistive technology cannot
                    take -- the operable paths (tap-to-place, ArrowUp/Down,
                    and the selected chip's own move buttons) are all on the
                    chip itself and all announced. Offering AT a "reorder"
                    control that does nothing when activated would be worse
                    than offering none. */}
                <span
                  className="rankboard-handle"
                  aria-hidden="true"
                  onPointerDown={(e) => handleChipPointerDown(e, chipId, i, true)}
                >
                  <svg viewBox="0 0 10 16" width="10" height="16" focusable="false">
                    {[3, 8, 13].map((cy) =>
                      [2.5, 7.5].map((cx) => <circle key={`${cx}-${cy}`} cx={cx} cy={cy} r="1.4" fill="currentColor" />),
                    )}
                  </svg>
                </span>
              </>
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
          <Avatar
            avatarId={draggingMember.avatarId}
            seed={String(draggingMember.managerId ?? draggingMember.rosterId)}
            label={chipName(draggingMember, draggingMember.rosterId)}
            isMe={draggingMember.isMe}
          />
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
