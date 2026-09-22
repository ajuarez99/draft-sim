# Phase 0 Research: Reordering a ranking on a phone

**Feature**: 007-mobile-rank-drag | **Date**: 2026-09-22

Everything below was read out of this tree, not recalled. File and line references
are to `007-mobile-rank-drag` at the time of writing.

---

## 0. The review already called this

`claude/power-rankings-ballots.md`, in the block headed "Amended after review
(finding 10)", says:

> Caching slot rects once at drag start is unsafe here, for a reason beyond perf.
> `getBoundingClientRect()` is viewport-relative, so whatever scroller ends up
> wrapping the board invalidates every cached rect the moment it moves -- and a
> 12-slot board on a phone *will* scroll mid-drag. `touch-action: none` stops the
> dragging pointer from scrolling; it stops nothing else. Cache, but re-measure on
> `scroll` and `resize`, **and after every placement**.

The shipped component does the last third of that and none of the first two:
`measureRects()` is called from a `useEffect` keyed on `order`, and at the moment
the drag threshold is crossed. There is no `scroll` listener and no `resize`
listener anywhere in the file.

The same document's "Not verified" section says, in as many words, that real touch
input was never exercised -- the drag was driven by mouse-derived pointer events.

**So this is not a regression and not a mystery.** It is a known, written-down gap
that shipped, on a path nobody has ever run on a finger. That is worth stating
plainly because it sets the bar for this feature: the fix is not done when the code
looks right, it is done when it has been driven with touch input at 375px.

---

## 1. Why a swipe moves a team instead of scrolling

`styles.css:2221` puts `touch-action: none` on `.rankboard-chip` itself. The chip
is the full-width row body, `min-height: 44px`, and the rows tile the list with a
4px gap. So the entire scrollable surface is declared non-scrollable, and
`DRAG_THRESHOLD_PX = 6` means any swipe that travels more than six pixels becomes a
drag.

There is no gesture arbitration of any kind -- no delay, no direction test, no
handle. The first six pixels of every touch decide, and they always decide "drag".

**Decision: a dedicated drag handle, not a long-press delay.**

`touch-action: none` moves from `.rankboard-chip` to a new `.rankboard-handle`
element inside each placed chip. The row body reverts to the browser default, so a
swipe starting anywhere on it scrolls the list the way it always should have.

**Rationale**:

- It removes the ambiguity rather than arbitrating it. A timer-based long-press has
  to *guess* whether a moving finger meant to scroll or to drag, and every guess it
  gets wrong is a new flavour of "wonky" -- which is the complaint we are here to
  answer, not to relocate.
- It keeps one code path. The existing pointer architecture (capture on the
  container, threshold in `pointermove`, the `lostpointercapture` and
  `pointercancel` handling that finding 10 forced) survives untouched. Only the
  element carrying `onPointerDown` and `touch-action` changes.
- It costs the mouse nothing. See §2.
- It is what every native reorder list does -- iOS Settings, Reminders, the
  Files app -- so the grip glyph is already a learned signifier.

**Alternatives considered**:

- **Long-press (~250ms) with movement cancel**, the SortableJS `delay` /
  dnd-kit `TouchSensor` pattern. Rejected on three counts. First, it makes every
  deliberate drag wait a quarter-second, which reads as lag to anyone who already
  knows what they want. Second, it needs `preventDefault()` inside `touchmove` to
  stop the browser scrolling once the press commits, and **React registers
  `onTouchMove` passively**, so `preventDefault()` there is a no-op with a console
  warning -- it would require a manual `addEventListener('touchmove', h, { passive:
  false })` against a ref, which is exactly the kind of parallel event plumbing
  finding 10 was written to keep out of this file. Third, a press that must be held
  still is undiscoverable: nothing on screen says "wait".
- **Whole row draggable, page scrolled by autoscroll only.** Rejected: it leaves no
  way to simply *read* the board without moving something.

**Spec consequence**: FR-007 currently reads "a vertical swipe beginning anywhere
on the board MUST scroll the board". A handle is a deliberate exception to
"anywhere". See plan.md, "Spec deltas".

---

## 2. Keeping the mouse exactly as it is

FR-021 and SC-008 forbid making the pointer path worse. A handle would normally do
that -- a mouse user who today grabs anywhere on a chip would have to aim at a grip.

**Decision**: keep `onPointerDown` on the chip, and reject the gesture there only
when it is a touch that did not start on the handle:

```
if (e.pointerType === 'touch' && !startedOnHandle) return   // let the browser scroll
```

A mouse or pen keeps the entire chip as its grab area, with no delay and no
threshold change. Touch gets the handle. One branch, one property, no second code
path, and `pointerType` is universally supported on every browser that supports
Pointer Events at all.

---

## 3. Reaching ranks that are off screen

**The scroller is `.rankboard-slots`, not the page.** `styles.css:2645` --
`.modal-card.wide .rankboard-slots { flex: 1; min-height: 0; overflow-y: auto; }`.
Both call sites render the board inside `.modal-card.wide`
(`PowerRankings.tsx:1383`), so the list always scrolls internally.

**How much is off screen.** At 375×812: `.modal-backdrop` has `padding: 24px`
(1403) and `.modal-card` has `padding: 22px 24px` (1406), with
`max-height: 80vh` on the wide variant (1411). That leaves a card about 327px wide
and 650px tall, an inner width of ~279px, and -- after the head, the instruction
paragraph, the seeded-order footnote and the submit row -- roughly 420px of slot
list. At 48px per row (44px min-height + 4px gap) that is **about 8 or 9 rows of
12 visible**, so three or four are always out of reach.

That is less dire than "half the board", but it does not soften the defect: moving
a team twelve rows through a nine-row window still cannot be done in one gesture,
and *any* move has to survive scrolling.

**Decision: two independent fixes, because they address different halves.**

**(a) Give the modal the screen at phone width.** There is currently no
`@media` rule anywhere that touches `.modal-card` (verified: `grep modal
styles.css` returns no match inside any media block). At ≤700px the ballot modal
should go near-full-bleed -- backdrop padding 24px → 8px, card padding → 14px,
`max-height` 80vh → 92vh. That reclaims about 70px of width and about 100px of
height, which is roughly two more rows visible and a chip that can afford a handle.
This is the cheapest real improvement available and it reduces how much anything
else has to work.

**(b) Autoscroll while dragging.** While a chip is held and the pointer sits within
an edge zone of the scroll container, scroll toward that edge on a
`requestAnimationFrame` loop, at a rate proportional to how far into the zone the
pointer is. Stop at `scrollTop === 0` and at `scrollHeight - clientHeight`.

Edge zone and rate are implementation constants, not spec: start at a 64px zone and
a maximum of ~12px per frame, and tune them in the browser -- these are the numbers
most likely to need adjusting by feel, which is a reason to keep them named
constants at the top of the module rather than sprinkled.

**The loop must re-run the hit test every frame, not only on `pointermove`.** While
autoscrolling, the finger can be perfectly still and the content still moves
underneath it -- so the target rank changes with no pointer event to trigger it.
Driving placement only from `pointermove` is the single most likely way to build
autoscroll that looks right and lands wrong.

**Scroll parent resolution**: walk up from the slots list for the first ancestor
whose computed `overflow-y` is `auto` or `scroll` and whose `scrollHeight >
clientHeight`; fall back to the viewport. Resolve once per drag, not per frame.
Hard-coding `.rankboard-slots` would work today and break the first time this
component is rendered outside a modal -- and the component's own docblock is
explicit that it is used twice and owns none of its callers' chrome.

---

## 4. Landing on the row the finger is actually over

Two ways to stop a scroll from invalidating the cached rects:

- **Re-measure on every scroll** of the resolved scroll parent, plus on `resize`,
  for the duration of the drag only. Twelve `getBoundingClientRect()` calls on a
  scroll event is nothing, and the listeners exist only between pick-up and
  release.
- **Don't defer the placement at all** -- reorder live, as the finger moves, so
  there is never a stale measurement to consult at drop time.

**Decision: do both, with live reordering as the primary mechanism.**

Live reordering means the board commits `move(order, from, to)` continuously during
the drag, exactly as it already does on a keyboard `ArrowUp`. The drop then commits
nothing -- it just ends the gesture. This is strictly better than a deferred hit
test for three reasons:

1. It deletes the entire class of "landed somewhere I didn't aim". There is no
   moment where a remembered rect is consulted; what you see is the order.
2. It satisfies FR-011 for free. The indication of where the chip will land *is*
   the board, renumbered, with the placeholder sitting in the destination -- and
   unlike a tint on the row under the fingertip, it is legible around a finger
   because the whole list has moved.
3. `measureRects()` already re-runs on every `order` change, so the existing effect
   keeps the rects honest through each live move.

The `scroll`/`resize` re-measure is still needed on top, for the frames where the
list scrolls without the order changing.

**Hysteresis is required.** Swapping whenever the pointer enters a neighbouring
rect oscillates at the boundary, because each swap moves the boundary back under
the pointer. Commit a move only when the pointer passes the **midpoint** of the
neighbouring row, in the direction of travel. This is the standard fix and it is
pure arithmetic over rects, so it is unit-testable (§6).

**Consequence**: `.rankboard-slot.drop-target` (styles.css:2201) loses its job and
should go, rather than being left as a rule nothing sets.

---

## 5. Saying what happened

- **Nothing changed.** With live reordering, a gesture that ends where it started
  genuinely changed nothing, and the board shows that by having never moved. The
  case that still needs a voice is a release *outside* the list -- currently
  `hitTest` returns null and `handleContainerPointerUp` silently keeps `order`. With
  live reorder the chip has already returned to wherever it last legitimately sat,
  so the honest announcement is "unchanged", via the existing `aria-live` region
  plus a brief visible settle of the chip back into its row.
- **Something changed.** The existing `announce()` already emits "Placed Nth of M"
  into `.rankboard-sr-only` (an `aria-live="polite"` region, the first in this
  codebase per finding 22). Keep it; do not fire it on every live micro-move during
  a drag -- that would flood a screen reader. Announce once, on release.
- **Haptics.** `navigator.vibrate(10)` on pick-up, guarded by a capability check.
  Not supported in iOS Safari at all, which is why the spec lists it as desirable
  and not required. One line, no fallback, no apology.

---

## 6. How any of this gets tested

`claude/power-rankings-ballots.md` (finding 18) already established the constraint
and the pattern:

> jsdom implements neither `setPointerCapture` nor layout -- `getBoundingClientRect()`
> returns zeros -- so no slot-hit test can run in the existing vitest harness.

So the split is forced, and the project has already chosen how to handle it:
`web/src/rankOrder.ts` holds the ordering as pure array operations with real unit
tests in `rankOrder.test.ts`, and only the pointer plumbing is browser-only.

**Decision**: extend that split rather than invent a new one. A new pure module
`web/src/dragGesture.ts` takes rectangles and numbers in and returns numbers out:

- `targetSlotFor(pointerY, rects, fromIndex)` -- the midpoint-hysteresis rule of §4.
- `autoScrollStep(pointerY, containerTop, containerBottom, zonePx, maxPxPerFrame)`
  -- signed pixels per frame, zero outside the zone.
- `clampScroll(next, scrollHeight, clientHeight)` -- the end-stops of FR-002.

These take plain `{top, bottom}` numbers, not DOM nodes, so they test in jsdom with
fabricated geometry and cover FR-002, FR-004, FR-005 and FR-006 as arithmetic.

What remains browser-only: `setPointerCapture`, the rAF loop, `touch-action`, the
scroll-parent walk, and whether any of it *feels* right. That is verified by hand at
375px with touch emulation, and written up -- `PowerRankings.verify.tsx` is the
existing precedent for a dev-only self-check page in this codebase, though for a
gesture the honest artifact is a short verification log, not a checklist component.

A jsdom component test is still worth writing for the parts that need no layout: the
tap-to-select-then-tap-to-place path, the instruction copy (FR-016), and that a
touch `pointerdown` on the row body is ignored while one on the handle is not.

---

## 7. The instructions are wrong on a phone

`PowerRankings.tsx:1428` tells every member, on every device:

> Drag to reorder -- or tap a team and use the arrows.

The arrows are `ArrowUp`/`ArrowDown` in `handleChipKeyDown`. A phone has no arrow
keys, so this sentence points touch users at a control that does not exist for them,
while not mentioning tap-then-tap, which does exist and works.

**Decision**: fix the copy, and give the selected chip real up/down buttons so the
sentence becomes true on every device rather than merely device-specific. Two 44px
buttons that appear on the selected chip only, calling the same `moveChipBy` the
keyboard handler already calls. This closes FR-015 and FR-016 together and adds one
small control rather than a second interaction model.

The commissioner side says only "Drag to disagree" (`PowerRankings.tsx:1416`) and
needs the same treatment.

---

## 8. What is explicitly not changing

- **No backend, no API, no schema.** Every requirement in this spec is satisfied
  inside `web/src/`. `MemberRankingService`, the ballot endpoints and the stored
  ordering are untouched.
- **`rankOrder.ts` keeps its current semantics.** `move()` splices and reinserts
  (`rankOrder.ts:104-112`) -- it shifts, it does not swap -- which is what FR-005
  already requires. Verified, not assumed.
- **The submit contract.** Local until submitted, complete before submittable;
  neither changes (FR-019, FR-020).
- **The keyboard and screen-reader paths.** Floor, not ceiling (FR-017).
