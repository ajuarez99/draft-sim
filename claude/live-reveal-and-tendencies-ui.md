# draft-sim — pick-by-pick reveal, and a UI for manager tendencies

Design note, 2026-08-29 (evening). Requested by Allan after using the app for the
first time against real data. Two features; not otherwise related, grouped in one
doc because they were requested together.

**Built and verified this session**, via an architect → coder → code-reviewer →
verification pipeline (each a separate agent). All 14 acceptance criteria the
architect stage defined (§5 of its plan, reproduced by the coder/reviewer/
verification agents) pass, checked live in a real browser against the real
running backend — not just a clean `npm run build`. One real bug came out of the
verification stage and looped back to the coder: `WebConfig`'s CORS config never
allowed `PUT`/`DELETE`, so the tendencies save/clear calls 403'd from any browser
the entire time that backend feature has existed (curl and Postman, HANDOFF's own
prescribed testing method, don't send CORS preflights, so this was invisible until
something actually drove it from a UI). Fixed, re-verified, now passes. Full
writeup: `claude/lessons.md` #14.

The design below is preserved as-written for the record of what was planned;
nothing in the actual implementation deviated from it in substance.

## 1. Pick-by-pick reveal instead of the board appearing all at once

### What's there now

`App.tsx` calls `streamSimulation` (`web/src/api.ts`), which shows a progress bar
driven by SSE `progress` events (`{completed, total, fraction}` — iteration count,
not pick data) while the run is in flight, then one `result` event lands with the
*entire* `SimulationResult` — all 210 `PredictedPick` cells — and `DraftBoard`
renders the whole grid at once. The experience today: a progress bar, then a wall
of a fully-populated table appearing in one frame.

### Why this can't be literally "watch the Monte Carlo run"

`MonteCarloRunner.aggregate()` (`backend/.../engine/MonteCarloRunner.java`) only
runs once, after every iteration has finished — a per-cell "modal pick" isn't a
meaningful concept until enough iterations have accumulated. There's no natural
mid-run moment where "pick 47 is decided" the way it would be in a real draft; the
whole board resolves together at the end. So this has to be a **reveal animation
of a result that already exists**, not a literal live feed of the computation —
and it should be presented that way (see "Honesty" below), not as if the backend
is deciding picks in real time while the frontend watches.

### Proposed design

**Phase 1 — client-side staged reveal, no backend change.**

When the `result` event lands, don't hand the full board to `DraftBoard`
immediately. Instead:

- Sort `result.board` by `pickNo` (already pick-ordered from the backend, but
  don't rely on that implicitly).
- Reveal cells in that order with a short stagger — `requestAnimationFrame` or a
  small `setInterval`, a handful of cells per tick rather than one every frame, so
  210 picks finish in ~3-6 seconds rather than a slow crawl. Cells not yet
  revealed render as empty (`—`), matching the existing empty-cell style in
  `DraftBoard.tsx`.
- A "skip" affordance (click anywhere on the board, or a button) jumps straight to
  the fully-revealed state — nobody wants to sit through the animation twice.
- State lives in `App.tsx` or a small hook (`useRevealedBoard(fullBoard)` returning
  `{revealed, isRevealing, skip}`), not inside `DraftBoard` itself, so
  `AvailabilityPanel` and `SeatList` (which don't need staging) aren't affected.

**Honesty framing, per this project's convention:** label it as a reveal, not a
simulation-in-progress. "Revealing predicted board…" not "Simulating pick 47 of
210" — the second claims something that isn't happening. The `progress` bar during
the actual run already correctly says "simulating N%"; this is a second, separate
phase after that, and should read as one.

**Phase 2 — a pick scrubber, addresses "let the user choose from there"**
(see the open question below on what that phrase means):

Add a slider/scrubber under the board, range `1..210`, defaulting to fully
revealed once the animation finishes. Dragging it re-derives which cells are
shown (`pickNo <= scrubberValue`), independent of the animation. Mark the user's
own `myPicks` as ticks on the scrubber so they can jump straight to "what did the
board look like right before my 3rd pick." This reuses the same revealed/hidden
cell rendering the animation already needs, so it's a small addition once Phase 1
exists, not a separate mechanism.

**Open question, confirm before building:** "letting the user choose from there
depending on what pick they have" could mean (a) the scrubber above, (b) something
about `mySlot` selection happening *after* seeing the board rather than before
running the sim (i.e., reorder the UI so slot is chosen post-reveal) or (c)
something else. Phase 2 above is the reading that fits best with how the rest of
the page already works (`mySlot` already drives `myPicks` highlighting and the
availability panel) — build that unless told otherwise.

### Not needed for this

Backend changes. `SimulationResult` already carries everything `DraftBoard` needs,
in pick order. This is presentation-layer only.

---

## 2. A UI for manager tendencies

### What's there now

`PUT /api/managers/{managerId}/tendencies` and `DELETE` exist and work
(`ManagerController.java`) — `{"reachBias": 8, "unpredictability": 1.6, "note":
"..."}`, partial updates replace the whole stored blob (no partial-field merge on
the backend; the frontend must send the full set it wants kept). `GET
/api/managers` returns `stated` next to `effectiveReachBias` so the blend is
visible. None of this has ever had a UI — HANDOFF's oldest open wart. `SeatList`
(`web/src/components/SeatList.tsx`) already *displays* provenance correctly
(verified live this session); it has never let you *set* it.

### Proposed design

Make each seat card in `SeatList` editable in place rather than adding a separate
page or modal — the card already shows exactly the fields that need editing
(`behaviour()` renders `reachBias`, `unpredictability`, positional tilt, `note`).

- An "edit" affordance on each card (small icon/button, not on `mySlot`'s card
  necessarily — you can set tendencies for anyone, not just yourself) toggles the
  card into a small inline form: number input for `reachBias` (roughly -20..20,
  matching the scale already visible in fitted values like "reaches ~14 picks
  early"), number input for `unpredictability` (0.1-3.0ish, `1.0` = default), text
  input for `note`.
- Save → `PUT`. Clear → `DELETE`, only shown when `provenance` is `STATED` or
  `BLENDED` (nothing to clear on a `NEUTRAL` or pure-`FITTED` seat).
- After either, refetch `/api/drafts/{id}/seats` so the card immediately reflects
  the new `provenance` and blended values — this is the same call `App.tsx`
  already makes on mount (`getSeats`), so `SeatList` needs an `onChanged` callback
  prop that triggers the same refetch, rather than owning its own fetch.
- New `api.ts` functions: `setTendencies(managerId, body)` and
  `clearTendencies(managerId)`, mirroring `streamSimulation`'s existing error
  handling (`json<T>` helper already does this generically).

### Type currently missing

`api.ts`'s `Seat` type has no `managerId`-keyed write path — it exists for reads
already (`Seat.managerId` is there). Add `ManualTendencies` as a type mirroring
the Java record (`reachBias: number | null`, `unpredictability: number | null`,
`note: string | null`), matching this project's stated convention that a backend
contract change needs the matching `api.ts` edit in the same commit — this one
isn't a backend change, but the same discipline applies to any new field the
frontend starts depending on.

### Small thing worth deciding alongside this

Should editing a seat's tendencies invalidate a simulation result already on
screen? Right now nothing recomputes automatically — a stale `result` would sit
there having been computed against the old tendencies with no visual signal that
it's stale. Cheapest fix: a small "seats changed since this simulation ran" banner
rather than auto-rerunning (auto-rerun could surprise someone mid-edit of several
seats). Not required for a first version, but decide before shipping so it isn't
silently wrong in the way `claude/lessons.md` #6 was.

---

## Related, not one of the two features above

Allan also flagged, same message: simulations sometimes draft one position 6+
times in a row for one team, which real drafters don't do. This is real and now
quantified (23/1000 trials hit a run of 6+, one hit 9) — root-caused to
`benchFloor` plus a large same-position share of `candidatePool`, not a
broken `rosterNeed` calculation. Full writeup: `claude/lessons.md` #13. That's a
scoring-model change, not a UI change, so it's tracked separately rather than
folded into this doc.
