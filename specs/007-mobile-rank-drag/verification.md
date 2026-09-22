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

---

## After real-phone use, 2026-09-22

The seventeen scenarios above all passed and the feature was still bad on a
phone. Allan reported all four of the symptoms offered: the list flew, a press
on a row did nothing but scroll, the grip was hard to hit, and the chip lagged
the finger. Worth recording precisely how a green log missed each one.

| Symptom | Cause | Fix |
|---|---|---|
| List jumped / flew | 64px zones were 29% of the 435px lane, and 12px/frame is 720px/s against a 137px scroll range — end to end in **190ms**. A finger drifting within ~1.3 rows of an edge snapped the board. | Zone 40px and capped at a fifth of the container, 5px/frame, quadratic ramp so the zone's outer edge creeps. |
| Press did nothing, page scrolled | The grip was the only way to pick a team up on touch, and it is discoverable only once you know it is there. People grab the row. | Press-and-hold (320ms, 10px slop) anywhere on a row now starts a drag. The grip keeps its no-delay path; a mouse still never waits. |
| Couldn't hit the grip | A bare muted glyph on the panel reads as decoration, and its only affordance was a hover state — which a phone does not have. | Tinted at rest. |
| Chip lagged the finger | Two forced layouts per frame (the loop measured inline *and* its own scroll event re-measured), a 12-rect hit test every frame for a tray that usually is not rendered, and the ghost transform rewritten 60×/s even with a still finger. | Self-scroll guard on the re-measure listener, hit test skipped when there is no tray, ghost moved on pointermove only. |

**Why scenario 2 hid the worst of it.** It was written as "hold at the edge
until the list reaches the end, and confirm it stops". Flying the entire range
in 190ms *passes* that scenario — it reaches the end and stops. The scenario
that would have caught it is "move a team two rows and drop it", which nobody
wrote because it sounds too easy to be worth a line.

**The wider lesson.** Every number in the first pass was picked to be
defensible in isolation and none was checked against this board's real
geometry: a 435px lane with a 137px scroll range. The arithmetic that exposed
all of it took one minute and no browser.

## Also fixed in the same pass: the rail

Not part of spec 007, reported alongside it. At 375×812 `.app-rail` measured
**388px — 48% of the viewport before a single row of content**, because the
league's nine page links wrap onto five stacked lines (~200px). They are
~1130px laid end to end, so they fit no phone at any font size.

They now ride one horizontally scrolling lane (`.app-rail-pages`), which is
38px instead of ~200px. Rail 388 → **229px**, content top 404 → 245. Desktop is
untouched: at 1280px the lane is still a column, nine rows on nine lines,
`overflow-x: visible`.

The CSS already carried a note from a previous round saying "a third of the
viewport spent permanently on navigation is worse than navigation you scroll
back up to reach" — written when the bar measured 252px. It had since grown to
388px without anyone re-measuring. 229px is back inside that stated line, but
only just, and the remaining cost is four stacked rows: wordmark, league
identity, the page lane, and global nav + account.
