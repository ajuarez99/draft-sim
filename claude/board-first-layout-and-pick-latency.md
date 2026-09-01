# draft-sim — board-first layout, and making the simulation fast

Design note, 2026-09-01. Allan marked up a screenshot of the running draft view and
gave five pieces of feedback in one message:

1. Remove the top control strip — "it functionally doesn't make sense with the new
   mock draft button."
2. "We need to increase the draft picker, it's taking too long. Can you research how
   other websites pick faster?" — clarified in a follow-up as **the simulation
   itself**, not the reveal pacing: "the draft picker can take long if they want."
3. Move Seats up, and put the seat names under the names for each draft pick.
4. Let the predicted board take up most of the space.
5. The confidence box ("How much to trust this") "is still bugged behind and doesn't
   need to be there."

Same convention as `claude/ui-polish-roadmap.md` and `claude/next-features-roadmap.md`:
the separate plans aren't kept as separate files, this is the reconciled result,
written to stand on its own as the brief for whoever builds next.

**Nothing here has been built. This is planning only.**

## How they relate, and recommended order

(4) is not its own change — it's the *outcome* of (1), (3) and (5). The board is
`flex: 3` of a four-band vertical budget today (`styles.css:228`); every band this
doc deletes hands its share back to the board. So there is no "make the board
bigger" task, only "stop spending the screen on things that don't earn it," and a
final layout pass (§E) to spend the reclaimed height deliberately.

**Recommended order: E-prep → D → C → A → B.**

1. **D (retire the confidence panel)** first — smallest, deletes a visible bug, and
   frees the lower-right column immediately.
2. **C (seats into the board's column headers)** second, because it is where the
   `slot` control has to land before A can delete it.
3. **A (remove the top strip)** third. Doing A before C would orphan the slot input
   with nowhere to go, and orphan the re-run affordance the `seatsDirty` banner
   (`DraftView.tsx:305-306`) points at.
4. **B (engine speed)** last — much the largest, entirely backend, and completely
   independent of A/C/D at the code level. Nothing about the layout work blocks
   starting B in parallel if you'd rather; they touch disjoint files.

**What none of these touch**: no database migration, no change to the SSE contract, no
change to `SimulationResult`'s shape. B is almost entirely a pure refactor of the
scoring hot path with byte-identical output; the one place it could add a *request*
field (`horizonPicks`) is §B6, explicitly held back unless the refactor falls short.
A/C/D are frontend-only.

---

## A. Delete the top control strip

Allan: "let's remove this top column, it functionally doesn't make sense with the new
mock draft button."

### What's there now

`DraftView.tsx:250-293` renders `.controls` — four things in one row: `slot` (number
input), `runs` (iteration count select), `chaos` (temperature slider), and a `start`
button.

He's right that it doesn't make sense any more, and it's worth being precise about
*why*, because it's four separate reasons, not one:

- **`start` is now duplicated.** The instant-start reveal work
  (`claude/ui-polish-roadmap.md` §C, shipped in `54fbb45`) put a big
  `.start-button` — "Start the mock draft" — inside the empty board's own overlay
  (`DraftView.tsx:379-381`). Two start buttons on one screen, one of them
  eight times the size of the other, and after the first run the small one silently
  changes meaning from "start" to "throw away this board and run again."
- **`slot` is about to stop being needed.** `ui-polish-roadmap.md` §A (auto-detect
  which slot is you) makes this input the fallback path rather than the default one,
  and §C's overlay copy already tells you to set it *before* starting — a moment
  when a persistent toolbar isn't the right home for it.
- **`runs` and `chaos` are model knobs, not draft-room chrome.** Both are read once,
  at `run()` (`DraftView.tsx:214`), and both are then deliberately *frozen* for the
  rest of the session — a resim reuses `result.temperature`, not the live slider
  (`DraftView.tsx:170`, and see `reactive-resimulation.md` §3 for why). So they are
  live controls that stop being live the moment you press start, which is exactly the
  kind of thing that shouldn't sit permanently across the top of the screen.
- **It costs a band of vertical space** in a layout whose whole stated design goal is
  "a Sleeper draft room fits on one screen" (`styles.css:33-46`).

### Proposed design

Delete the `.controls` block from `DraftView.tsx` entirely (lines 250-293). Each of
its four controls gets a home that matches when it's actually used:

| control | new home | when you reach it |
| --- | --- | --- |
| `start` | the existing `.start-button` in the start overlay | before the first run |
| `start` (again, as *re-run*) | a `re-run` chip in the board panel's head, beside `skip` | after a board exists |
| `slot` | the seat column header popover (§C) — "this seat is me" | before the first run |
| `runs`, `chaos` | one settings popover, opened from a gear chip in `.top` | before the first run |

**The re-run affordance is not optional.** `seatsDirty` renders "Seats changed since
this simulation ran — re-run to refresh the board" (`DraftView.tsx:305-306`), which
points at a button this change removes. Give that banner its own inline re-run
button in the same edit, or the message becomes an instruction you can't follow.

**The settings popover** holds `runs` and `chaos` with their existing markup moved
verbatim (they're already labelled and already have the `temp-value` readout,
`DraftView.tsx:281-289`). Anchor it in the header rather than the board panel: it
configures the *next* run, and the header is the one region that isn't part of the
draft room proper. Disable the trigger, or mark the fields "applies to the next run,"
while `running || resimming` — that's already true today and currently invisible.

### Watch out for

`.controls` (`styles.css:61-78`) is **shared with `DraftPicker.tsx`'s add-a-draft
form** (`DraftPicker.tsx:68`, plus `.add-draft .controls` at `styles.css:135`).
Delete the JSX usage in `DraftView`, not the CSS rule.

### Acceptance

- A cold draft view shows: header, board panel, availability. No toolbar.
- `start`, `re-run`, `slot`, `runs`, `chaos` are each reachable in at most two
  clicks, and no control is reachable in two places.
- The `seatsDirty` banner can be acted on from the banner itself.

---

## B. Make the simulation itself fast

Allan, clarifying: "I meant the simulation itself — the draft picker can take long if
they want."

So this is engine wall-clock, and only engine wall-clock. **The reveal pacing is
explicitly out of scope** — the 450 ms tick stays where `your-pick-and-pacing.md`
deliberately put it, and however long you take to choose a player is your business.
What has to shrink is the time the machine spends with a progress bar up.

### The budget

| what | measured | source |
| --- | --- | --- |
| first run, 2000 iterations | **~18.5 s** | `DraftView.tsx:26-33` |
| resim, 500 iterations | **~4.9 s** | same |
| resim, 2000 iterations | ~18.7 s (why the cap exists) | same |

Two targets, both derived from something already on screen rather than picked out of
the air:

- **A resim must finish inside one reveal tick (450 ms).** That is the threshold
  below which the recompute stops being an event at all — the board simply continues
  and the pause banner never appears. Anything under ~1 s is a big improvement;
  under 450 ms is the real goal.
- **A first run should be under ~3 s at full 2000 iterations**, which is roughly how
  long the start overlay's copy takes to read. Today it's 18.5 s of staring.

That's a **~6x speedup wanted on the first run and ~10x on a resim.** The rest of this
section argues that's available, and not by cutting corners on the model.

### Research: why every other simulator is instant

Every mainstream mock draft simulator drives opponent picks from a static ADP/tier
board plus a small randomization, so a bot pick costs microseconds and there is
nothing to wait for:

- FantasyPros' Draft Wizard automates opponents so a full draft finishes "in minutes,"
  and markets its pick logic as better than plain ADP — but that logic still runs
  per-pick against a precomputed board, not a Monte Carlo. Its differentiator is the
  analysis *after* the draft.
- Pro Football Network offers "from any point in a live draft, simulate the remaining
  picks" — the fast path is a batch jump, not a faster animation.
- NFL Fantasy Edge ships the escape hatches this implies: auto-draft any single pick,
  or autopilot straight to the grade.
- Sleeper's AI mocks are pitched on testing strategies "in minutes"; the pick clock
  exists for *human* opponents.

**This is not a design we can copy, and we shouldn't want to.** A static board deletes
the one thing this project exists for. But it does set the bar honestly: the rest of
the market treats "waiting on the simulator" as a bug, not a cost of doing business,
and 18.5 s is far outside what anyone else asks of a user. The way to meet that bar is
to make the Monte Carlo cheap — and reading the hot path, it is *extravagantly*
expensive for what it computes.

### B1 — Measure honestly first (do this before touching anything)

`MonteCarloRunner.java:68` logs `"{} iterations in {} ms"`. Note what that number does
**not** include: `aggregate()` is called *after* the log statement (line 70), so the
wall clock the UI actually sees is the logged number **plus** an unmeasured
aggregation pass. Before optimizing anything, split the timing into three: request
setup (`SimulationService` lines 57 and 66, both hitting Postgres), the simulation
loop, and `aggregate()`. It is entirely possible aggregation is a third of the time
and nobody knows.

Also measure a **second** run in the same JVM. 18.5 s may include JIT warmup on a cold
path; a run-2 number tells you whether you're optimizing steady state or startup.

Everything below is read from the code, not from a profiler. The *direction* is
solid — the inner loop allocates heavily and recomputes invariants — but the
multipliers are hypotheses until B1 produces numbers.

### B2 — The inner loop is where the 18 seconds are

Work out the shape: 2000 iterations × 210 picks × 30 candidates
(`weights.yml: candidatePool: 30`) = **12.6 million `PickScorer.score()` calls** per
full run. At 18.5 s that's ~1.5 µs per call — enormous for something that is nominally
four multiplies and an add. Here is where it goes, in descending order of cost.

**(a) `rosterNeed` copies the whole roster and revalues the lineup twice — per
candidate.** `FootballRules.rosterNeed()` does `roster.copy()`, then
`startingLineupValue()` twice (before and after). Per candidate. So per *pick* that's
30 roster copies and 60 lineup valuations; across a full run, **~12.6M roster copies
and ~25M lineup valuations.** Three separate fixes, each independently valid:

- `before = startingLineupValue(roster, settings)` is **identical for all 30
  candidates** at a pick. Compute it once per pick and pass it in. Halves the
  valuations immediately.
- `RosterState.at(pos)` **allocates a copy and sorts it on every single call**
  (`RosterState.java:25-30`), and `startingLineupValue` calls it once per dedicated
  starter position (~5) per valuation. Keep `byPosition`'s lists sorted on insert —
  they hold at most 15 elements — and return them directly. This alone removes on the
  order of 10 allocations + 10 sorts per candidate.
- `roster.copy()` per candidate can go entirely. Adding one player at position *p* can
  only change the starting lineup by displacing the weakest starter (or flex) he
  competes with; that delta is arithmetic off the already-sorted position lists. If
  the analytic version feels risky, the intermediate step is: candidates at a pick
  fall into ≤5 positions, so compute the marginal structure **once per position per
  pick** and make each candidate's delta a subtraction.

**(b) `value()` is `Math.exp` and it is called from inside comparators.**
`FootballRules.value()` is `Math.exp(-adp / valueDecay)` — a pure function of the
entry's ADP, which never changes. It's called once per starter *and* inside
`flexPool.sort()`'s comparator (`FootballRules.java:82`), so an O(n log n) pile of
`Math.exp` per valuation, ~25M valuations deep. **Precompute it once per board entry**
when `DraftContext` is built (~600 doubles) and index it. This removes essentially
every `Math.exp` in the program from the hot path.

**(c) Two of the four score terms don't depend on the candidate at all — only on his
position.** `priors.logProbability(round, pos)` is two map lookups and a `Math.log`;
`Math.log(profile.tilt(pos))` is another map lookup and another `Math.log`;
`runPressure(recent, pos)` walks a deque calling **`Math.pow(decay, age)`** per element
(`PickScorer.java:88`). All three are constant across every candidate sharing a
position. With ≤5 positions among 30 candidates that's a **6x reduction** on those
terms, and it takes `Math.log`/`Math.pow` out of the innermost loop entirely. (The
`Math.pow` is gratuitous regardless: decay^age over a 6-element window is an
incremental multiply, or a 6-entry precomputed table.)

**(d) Allocation per pick.** `DraftSimulator.choose()` allocates a fresh `ArrayList`
and a fresh `double[] scores` every pick (`DraftSimulator.java:96-98`), and `sample()`
allocates another `double[] w` (line 137). That's ~4 allocations × 210 picks × 2000
iterations ≈ **1.7M short-lived arrays** per run. Each `DraftSimulator` is
single-threaded and used for exactly one iteration — hoist all three into reusable
instance buffers allocated once in `run()`.

Taken together these are not 10% improvements stacked; they compound
multiplicatively, because they remove nested work from inside a 12.6M-iteration loop.
A 5-15x total is a reasonable expectation, which would put a 2000-iteration run in the
1.5-4 s range and a 500-iteration resim comfortably under the 450 ms tick. **None of
it changes a single number the model produces** — every one is a pure refactor with
identical output, which also makes it testable: capture a seeded-RNG result before,
assert byte-identical output after.

### B3 — Structural waste outside the scoring loop

- `DraftSimulator.run()` rebuilds an identical `Map<Long, BoardEntry> byId` over the
  whole ~600-entry board on **every iteration** (`DraftSimulator.java:47-48`),
  unconditionally — even when `completedPicks` is empty and it is never read.
  `MonteCarloRunner.java:84-85` builds the same map again. Build it once, on
  `DraftContext`.
- `available.remove(choice)` (`DraftSimulator.java:73`) and `available.remove(e)`
  (line 62) are `ArrayList.remove(Object)` — a linear identity scan over ~600
  elements, ~210 times per iteration ≈ 126k comparisons per iteration. `choose()`
  already walks the list; return the index and use `remove(int)`, or move to an
  index-addressed pool with a draftable mask.
- Boxing on the per-pick path: `rosters` is a `Map<Integer, RosterState>`, `myPickSet`
  a `HashSet<Integer>`, `completedPicks` a `Map<Integer, Long>`
  (`DraftSimulator.java:37-46, 59`). All three are dense small-integer keyed and want
  arrays.

### B4 — Executor shape

`MonteCarloRunner.java:46` submits N tasks to
`Executors.newVirtualThreadPerTaskExecutor()`. Virtual threads buy nothing for
CPU-bound work — the carrier pool is already sized to the core count — and 2000 of
them each allocate a `DraftSimulator`, a 600-element `ArrayList` copy, and (until B3)
a `byId` map. Chunk into `availableProcessors()` platform tasks of `iterations/cores`
simulations each, reusing one `DraftSimulator` per chunk once B2(d)'s buffers make
that safe. Lowest-confidence item here; do it last, and keep it only if B1's numbers
justify it.

### B5 — Aggregation

Unmeasured today (see B1). Its shape is suspicious: `aggregate()` builds 211
`HashMap<Long,Integer>` and performs `iterations × totalPicks` = **420k boxed
`merge()` calls** for the modal-pick counts alone (`MonteCarloRunner.java:78-83`), then
`iterations × myPicks × SNAPSHOT_DEPTH` = 2000 × 15 × 75 ≈ **2.25M more** for the
availability curves (lines 118-128). Both want dense `int[]` over a board-index space
with the `Long` player ids resolved once at the end. Do this only if B1 says it
matters.

### B6 — Only if the above isn't enough

Two levers that trade something real for speed. Neither should be needed if B2 lands,
and both should be held back until it has:

- **Fewer iterations.** The standard error of a displayed proportion at *p* = 0.10 is
  ±1.3 points at n = 500 and ±0.67 at n = 2000, against a UI that rounds to whole
  percent and already says "low percentages mean the model does not know." 2000 is
  buying precision the display cannot show. Dropping the default to 500 is a 4x
  speedup available *today*, for free, at a cost that is arguably zero — but it is
  also the kind of change that quietly makes the product worse if those numbers are
  ever used for something finer-grained. Measure B2 first.
- **Horizon truncation.** `DraftSimulator.run()` loops all 210 picks with no early exit
  (`DraftSimulator.java:50`); a resim at pick 28 simulates 182 remaining picks to
  answer a question about the next 14. Add a nullable `horizonPicks` to
  `SimulationRequest` and stop at `min(total, lastCompleted + horizon)`. Roughly 4x on
  a mid-draft resim — but it leaves the board past the horizon with no fresh
  prediction, which then needs either a visible "not recalculated" state or a
  background full-fidelity run that swaps in behind the reveal head. Real complexity
  for a problem B2 should already have solved.

### Acceptance

- Seeded-RNG output is **byte-identical** before and after B2/B3/B4. These are
  refactors; any change in the numbers is a bug, not an improvement.
- 500-iteration resim on the real fantasy(heart) board at pick 28: **< 450 ms**
  end-to-end (request in, result out), measured the way B1 establishes.
- 2000-iteration cold run: **< 3 s**.
- The timing log covers setup + simulation + aggregation, so the next person doesn't
  have to rediscover that it didn't.
---

## C. Seats move into the board's column headers

Allan: "seats, let's move it up and have the seat names under the names for each
draft pick."

### What's there now

Two places already show every manager's name:

- `DraftBoard.tsx:38-52` — each column head has the manager's avatar (hue-keyed off
  `managerId`) and name, sticky at the top of the grid.
- `SeatList.tsx` — a 228-line panel of fourteen cards, one per seat, in its own
  bottom band (`.seats-panel`, `styles.css:275-276`), holding the same fourteen names
  plus behaviour text, provenance badge, and the tendency editor.

So the bottom band is a second, larger copy of information the board already carries,
and it's claiming `flex: 2` of the vertical budget — as much as the availability
table and two thirds of the board itself.

### Proposed design

**Delete the `.seats-panel` band** (`DraftView.tsx:406-410`) and move everything it
does into the column header the board already draws:

- **The header grows** to carry avatar + manager name + a provenance dot (the
  `LABEL` map's three states from `SeatList.tsx:15-20`, as a colored dot rather than
  a text chip — fourteen "league average" chips across the top would be the noise
  `SeatList`'s own comment already warns about) + a `you` marker on your slot.
- **Clicking a header opens a seat popover** containing today's `SeatCard` body
  almost verbatim: the `behaviour(s)` line, the note, the footnote, and the edit form
  with its save/clear/cancel actions. `SeatList` becomes `SeatPopover`; `SeatCard`'s
  internals move over with near-zero change, including its `onChanged` →
  `handleSeatsChanged` wiring (`DraftView.tsx:89-92`).
- **The popover carries "this seat is me,"** which calls the existing `setMySlot`
  (`DraftView.tsx:95-106`). That's where §A's deleted `slot` input goes, and it's a
  better control than a number input: you pick your seat by clicking your name.

This satisfies both halves of the ask — Seats moves up (into the board, at the top of
the screen instead of the bottom), and each column of draft picks is labelled with
its seat name.

### One open reading

"Have the seat names under the names for each draft pick" could instead mean the
manager's name inside **every cell**, under the player's name. That's buildable —
`PredictedPick.manager` is already on the wire and already in each cell's `title`
attribute (`DraftBoard.tsx:82`) — but it would repeat fourteen names across 210 cells
that are already organized *into columns by manager*, in cells currently 11px tall
holding four elements. The column-header reading is the one this plan assumes. Say so
if it's the other one; the change is small either way and the layout consequences are
identical.

### Watch out for

- `hueFor(managerId)` must stay the shared identity color between header, popover,
  and `.cell.mine` crimson, or the board stops reading as one system.
- The header is `position: sticky; top: 0` inside `.board-scroll` (`styles.css:160-164`).
  A popover anchored to a sticky element inside a clipping scroller
  (`.board-scroll` has `border-radius` + `overflow`) will get clipped — render it in a
  portal or reuse the existing `.modal-backdrop`/`.modal-card` machinery
  (`styles.css:315-330`), which already solves this for `PlayerCard`.
- Seat editing before a run still has to work: `seats` is fetched independently of
  `result` (`DraftView.tsx:84-87`) precisely so the grid and its headers exist before
  any simulation does. Keep that — the empty pre-start board becomes the seat-setup
  screen, which is a better use of it than the current overlay-only state.

---

## D. Retire the confidence panel

Allan: "this thing is still bugged behind and doesn't need to be there."

### What's actually wrong with it

It isn't only misplaced — it renders broken, and the screenshot shows exactly how.
`ConfidenceNote` is `.lower-grid`'s second column (`DraftView.tsx:402`), styled
`align-self: stretch` with an internally-scrolling body (`styles.css:265-273`). That
rule exists for a real reason — a previous fix, commented in place, for caveats
bleeding out of the grid row — but the result is a tan box whose `<h2>` ("How much to
trust this") has scrolled out of view, showing a paragraph starting mid-sentence
("Of 14 seats: 0 have draft history…") with an unreachable `<ul>` of caveats below
it. It reads as a rendering bug because functionally it is one: a panel of prose in a
scroll container two lines taller than its content.

### Proposed design

Delete `ConfidenceNote` from `.lower-grid` and let `AvailabilityPanel` take the full
row width (`styles.css:260-273` collapses to a single-column band, and the
`max-width: 900px` override at line 279 becomes a no-op to remove).

**Don't just delete the content.** `ConfidenceNote.tsx`'s own doc comment argues it is
"deliberately not tucked into a collapsed details section" because "the numbers look
more precise than they are." That argument is still right; what's changed is that a
permanently half-scrolled tan box serves it worse than a well-placed line would. Two
options:

- **(1) Recommended — one honest line, inline.** Fold the numbers into the board's
  existing description paragraph (`DraftView.tsx:315-321`), which already does this
  job in the same voice ("Low percentages mean the model does not know, which is most
  of the board after round three"). Append a sentence built from
  `result.confidence`: *"N of 14 seats have draft history; the rest draft like the
  league average. Nothing here has been backtested."* Full caveat list moves behind a
  `why?` chip opening the existing `.modal-card`.
- **(2) Delete outright.** Least code, and defensible — the caveats are also in
  `README.md`'s "What not to trust" — but it removes the honesty note from the one
  place the numbers are actually read. Only take this if you also accept that.

Either way `.panel.warn` (`styles.css:89`) and the `--warn` token become dead unless
option (1)'s modal reuses them; delete what nothing references.

---

## E. The layout that falls out

Before (four bands sharing 100vh under the header):

    ┌ controls ──────────────────────────────────┐  fixed
    ├ board panel ───────────────────────────────┤  flex 3
    ├ availability │ confidence ─────────────────┤  flex 2
    └ seats ─────────────────────────────────────┘  flex 2

After:

    ┌ board panel ───────────────────────────────┐  flex 5
    │  seat headers carry name + tendencies      │
    ├ availability ──────────────────────────────┤  flex 2
    └────────────────────────────────────────────┘

Concretely: `.board-panel { flex: 3 1 0 }` → `flex: 5 1 0` (`styles.css:228`),
`.lower-grid` drops to one column, `.seats-panel` and its rules (`styles.css:275-276`)
are deleted along with `.seats`/`.seat*` if the popover doesn't reuse them — check
before deleting, §C's popover probably reuses most of `.seat-form*`.

Worth considering once the space exists: the board currently shows all 15 rounds in a
scroller. With ~5/7 of the viewport it will show roughly 8-9 rounds at once without
scrolling, which is the first time the round-to-round shape of a draft is legible at a
glance. Don't add anything to fill the space — that's what §4 of the feedback was
asking for.

---

## Open questions

1. **Seat names in column headers, or in every cell** — see §C's "one open reading."
2. **`runs` and `chaos` after §A** — the plan keeps both behind a settings popover.
   The alternative is dropping `runs` from the UI entirely and fixing it at a default,
   since §B's whole thrust is that iteration count should be the engine's problem
   rather than the user's. Worth deciding before building A.

## Explicitly not in scope

- Feature C from `next-features-roadmap.md` (a real turn-by-turn mock draft room with
  `mock_draft_session` tables). §B makes the existing reactive-resim flow fast; it
  does not replace it with a different architecture.
- Replacing the Monte Carlo with a static ADP board to make picks instant — see §B's
  research, "Refuse."
- Any change to the board's cell content, the snake math, the SSE contract, or the
  reveal's pause-at-your-picks behavior.
- **The reveal's 450 ms pacing, and anything about how long the user takes to pick.**
  Allan, explicitly: "the draft picker can take long if they want." A speed control
  for the reveal remains available as a later, separate feature
  (`your-pick-and-pacing.md:258-262` already scoped it), but it is not an answer to
  anything in this doc.
- Ad-hoc league-size customization, which stays last per Allan's standing priority.

## Sources (§B research)

- [FantasyPros Mock Draft Simulator](https://draftwizard.fantasypros.com/football/mock-draft-simulator/)
- [FantasyPros Draft Wizard — draft tools](https://draftwizard.fantasypros.com/football/draft-tools/)
- [Pro Football Network mock draft simulator](https://www.profootballnetwork.com/fantasy-hq/mock-draft)
- [NFL Fantasy Edge mock draft](https://nflfantasyedge.com/mock-draft/)
- [Sleeper mock drafts](https://sleeper.com/mockdraft)
- [Draft Sharks mock draft simulator](https://www.draftsharks.com/mock-draft)
