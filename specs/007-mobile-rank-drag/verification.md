# Verification log: reordering a ranking on a phone

**Feature**: 007-mobile-rank-drag | **Run**: 2026-09-22 | **Branch**: `007-mobile-rank-drag`

Every scenario in [quickstart.md](./quickstart.md) §3, run against the real app:
dev server on 5178 (5173 and 8080 were held by another session), the real
backend, and the real `(Foot) Ball Knowers` league — twelve teams, week 3,
league id `1346366555759341568`. Viewport 375×812 for scenarios 1–12 and 16,
the pane's own desktop size for 13–14.

**Nothing was submitted.** The network log for the whole session shows only
`GET /api/leagues/…/ballot` and no `POST` — which is also a live confirmation
of FR-019, that the board holds its ordering locally until the member submits.

---

## Read this before trusting the table

Three limits of this environment, stated up front because they decide how much
the PASSes below are worth.

1. **`requestAnimationFrame` never fires in the browser pane.** Measured: 0
   frames in 600ms with `document.visibilityState === 'visible'`. The pane does
   not paint, so rAF callbacks are never scheduled. Every autoscroll scenario
   (2, 3, 4, 5) was therefore run with `window.requestAnimationFrame` shimmed to
   `setTimeout(cb, 16)`. That runs the component's own loop body — the same
   `autoScrollStep`, `clampScroll`, `applyPointer` and the same end-stop — with
   only the scheduler swapped. It does **not** exercise the real 60fps cadence
   or frame budget.
2. **All touch input was synthesized `PointerEvent`s.** A dispatched event
   cannot reproduce the compositor's own handling of `touch-action`, so
   "a real finger swipe scrolls the list" is verified as *computed style*
   (`.rankboard-chip` → `touch-action: auto`, `.rankboard-handle` → `none`)
   plus *the app never starts a drag from a row-body touch*. Those are the two
   halves of scenario 1, and together they are strong — but no finger touched
   a screen.
3. **Scenario 17 has no honest number.** See the table.

What remains owed is therefore one pass on a real phone. Everything that could
be established without one has been.

---

## Results

| # | Scenario | Result | Evidence |
|---|---|---|---|
| 1 | Ten swipes on row bodies scroll, move nothing | **PASS** | 10 synthesized touch swipes → 0 drags started, order byte-identical. `.rankboard-chip` computes `touch-action: auto` (was `none`); `.rankboard-handle` computes `none`, box exactly 44×44 |
| 2 | Last → first in one continuous gesture | **PASS** | One pointerdown, two moves, then a stationary hold: list autoscrolled 137 → 0, `BamAddABio` landed rank 1, all 11 others kept relative order, 12 distinct teams, one announcement "Placed BamAddABio 1st of 12." |
| 3 | First → last | **PASS** | Symmetric: list travelled 0 → 137 |
| 4 | Hold at an edge already at the end | **PASS** | Scroll stopped at 0 (and at 137 downward) and stayed; gesture remained live |
| 5 | Hold still while the list scrolls | **PASS** | This is *how* 2 and 3 completed — after the second move no further pointer events were sent, so every remaining rank change came from the per-frame re-target |
| 6 | Ten deliberate drags to a named rank | **PASS** | 10/10 landed on the aimed rank; in all ten the rest of the board kept relative order with no duplicates |
| 7 | No oscillation at row boundaries | **PASS** | Finger parked mid-row, sampled 20× over 500ms: **0** order changes |
| 8 | Release outside the list | **PASS** | Order unchanged; announced "theadambomb98 is still ranked 5th of 12. Nothing changed." |
| 9 | Full reorder by taps alone | **PASS** | All 12 ranks reversed using only taps — 22 taps, 0 selection faults, 12 distinct teams, submit enabled |
| 10 | Copy describes gestures the device has | **PASS** *(after a fix)* | No "click", no "arrow keys"; says grip/tap. A **third** copy string was found during this scenario — see Defects |
| 11 | OS interruption mid-drag | **PASS** | `pointercancel` while autoscrolling: scrollTop froze at 108 and was still 108 after 700ms, ghost cleared, no stray `.dragging` class, board intact |
| 12 | Resize mid-drag | **PASS** | Board survived a `resize` mid-gesture; chip still landed on the aimed rank ("Placed theadambomb98 7th of 12.") |
| 13 | Desktop mouse, from anywhere on the chip | **PASS** | Drag live on the first qualifying 12px move, from the chip **body** not the handle; no timer, no delay |
| 14 | Keyboard path unchanged | **PASS** | Chip focusable, ArrowUp moved exactly one rank, focus followed the chip, Escape cleared the selection — same step count as before |
| 15 | One announcement per gesture | **PASS** | 8 live moves during one drag produced **0** `aria-live` updates; release produced exactly **1** |
| 16 | The other modals are untouched | **PASS** | Start-mock modal (plain `.modal-backdrop`) still computes backdrop padding 24px, card padding 22px 24px, width 327 — its pre-change values. No horizontal overflow |
| 17 | Full 12-team reorder under 90 seconds | **NOT MEASURED** | No honest number is available from automation. What was measured: the tap route needs 22 interactions (11 moves), and the drag route now does last→first in **one** gesture instead of the several it previously forced. A human with a real finger is still owed |

---

## What the layout actually measures now

At 375×812, taken from the live page rather than from arithmetic:

| | Before | After |
|---|---|---|
| Ballot card width | 327px | **359px** |
| Ballot card height | 650px (80vh) | **747px** (92vh) |
| Inner content width | ~279px | **331px** |
| Slot list overflow | — | 137px, i.e. ~2.9 rows of 12 hidden |

The phone-width modal is doing real work: it is roughly two more rows visible
before autoscroll has to do anything at all.

---

## Defects found by running

Both were found in the browser and would not have been found by reading — the
class `claude/lessons.md` #2 and #3 already name.

1. **`setPointerCapture` was unguarded.** It threw `NotFoundError` mid-gesture
   and the exception escaped `handleContainerPointerMove`, leaving
   `draggingRef.current` already `true` with no ghost ever rendered — a drag
   that is neither running nor cleaned up. `releasePointerCapture` in `endDrag`
   has always been wrapped; this one never was. Now wrapped, and a failure to
   capture degrades to "the drag works while the pointer is over the board"
   instead of throwing. Surfaced by synthetic events, but reachable for real on
   a fast flick or an interruption landing between `pointerdown` and the first
   qualifying move.
2. **A third instruction string still said "click".** T026 named two copy sites;
   there were three. `PowerRankings.tsx:1454` read "Drag a chip, or click one
   and use ↑ / ↓" — wrong on the device this feature exists for. Fixed. Found
   only because scenario 10 asserted on the concatenated text of *every* `<p>`
   in the modal rather than on the two strings the task pointed at.

## One harness mistake worth recording

Scenario 9 failed twice before passing, and both times the product was right.
A drag earlier in the session had left `suppressClickRef` armed, so the first
click of my tap loop was swallowed; my loop then ran with a stale selection,
and its next tap was correctly read as *place the selected chip here*. The
board behaved exactly as designed. Fixed by clearing the selection with Escape
and asserting `aria-pressed` between steps. Recorded because "the automation
desynced" and "the feature is broken" look identical in a failing assertion,
and the difference was only visible by tracing the selection state.
