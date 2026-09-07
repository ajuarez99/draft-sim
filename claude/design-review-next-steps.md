# Design review — what's left

Written 2026-09-07, straight after steps 1–3 shipped (`6a08ef1`, design doc
`claude/on-the-clock-and-name-abbreviation.md`). This is the live list. It
supersedes the review's own "what I'd do first" ordering, because building
steps 1–3 turned up seven things the review missed — including one gap that
step 2 *created*.

**A1, A5, A6 and the live-room half of A4 are done** (2026-09-07, same day) —
struck through in place rather than deleted, so the reasoning stays readable.
A1 also corrected a claim this doc got wrong; see it. Everything else is
untouched.

Every measurement was taken in the running app against league
`1391509063170293760` at 1440×900, not read off the source.

---

## A. Found while building steps 1–3

These come first, ahead of the review's own steps 4–7, because two of them are
consequences of what just landed and one is a feature the room is missing
rather than a styling problem.

### A1. ~~The live room is now the odd one out~~ — DONE 2026-09-07

**Built.** `LiveStatusBar` composes `OnTheClock` and keeps only what the other
two rooms don't have; `PickFeed` landed in the live room at the same time. The
`.live-onclock` block and its eight CSS rules are gone. `OnTheClock` grew two
things to absorb it: `done`/`doneLabel` became `idle`/`idleLabel` (the live
room can be complete, not-started, order-not-set, or not-connected, and those
are four different sentences), and an optional `onManagerClick` that makes the
identity block a real button — the live room opens SeatPopover from it, which
is worth more there than anywhere else.

**One thing below was wrong.** This doc said "`live.picks` is the landed
prefix". There is no `picks` array on `LiveState` — it carries counts only.
The landed prefix is `result.board` cut at `picksMade`, because the engine
replays every completed pick out of the DB identically in every iteration, so
those rows are the record rather than a projection. Same cut `takenPlayerIds`
was already taking. A consequence worth knowing: the feed is empty until the
first projection lands, which is correct — with no board there is nothing to
name the players or managers with.

**Verified:** the `drafting` branch can't be reached in a browser without a
real in-progress Sleeper draft, so it's covered by a new
`LiveStatusBar.test.tsx` (12 cases, including all four idle reasons and the
seat-popover click). The complete/idle path was checked in the running app.

<details>
<summary>Original finding</summary>

Step 2 unified two of the three draft rooms onto `OnTheClock`. It did not touch
the third. `LiveDraftView` still renders `LiveStatusBar`, which carries its own
`.live-onclock` block — avatar, "on the clock" kicker, manager name, round.pick
— in a different shape from the strip the other two rooms now share. **Three
rooms, two visual languages for one idea, where before there were two rooms and
two languages.** Unifying two of three and stopping is worse than not starting,
and this is the one item on the list that is a regression rather than a
pre-existing gap.

`LiveStatusBar` is not a pure duplicate, which is why step 2 left it alone: it
also carries the SSE freshness pill, the progress fraction, and the manual
`track` button, none of which the other rooms have. The fix is composition, not
deletion — `LiveStatusBar` renders `OnTheClock` and keeps the live-only
telemetry as its own trailing block, the way `TurnIndicator` already adapts the
mock session's turn state.

`PickFeed` should land in the live room at the same time. It is the room where
a position run matters most, because the picks are real and you cannot rewind.

</details>

### A2. The mock room has no "who's still there when you pick"

Measured: `document.querySelector('.avail-sheet')` is **null** in `/mock/:id`.
The batch room has the availability sheet. The live room has it. **The
interactive mock room — the one where you are actually on the clock making a
decision — does not.**

This is the largest *feature* gap the review missed entirely, because the
review looked at how screens were composed and this is a panel that simply
isn't mounted.

It is not a copy-paste job. `AvailabilityPanel` reads `AvailabilityRow[]` off a
`SimulationResult`, and a mock session has no simulation behind it — it has a
bot policy that picks one player at a time. Answering "who survives to your
next pick" here means running the same Monte Carlo forward from the session's
current state, which is real backend work and is why it was never there. Worth
scoping properly rather than faking with an ADP sort, which would quietly be a
different (and worse) answer wearing the same panel's clothes.

### A3. The tendencies form exists twice, and its labels are model internals

`SeatPopover` and `ManagerTendencies` both render the same three fields —
`reach bias`, `unpredictability`, `note` — with the same `save` / `clear` /
`cancel` actions and the same lowercase copy. Two files, one form.

Extract it, and fix the labels in the same pass, because they are the deepest
piece of engineer-facing language left in the app:

- **`reach bias`** is a number input from −20 to 20 with no units on screen.
  It means "picks earlier than ADP this manager tends to go". The
  `/managers` cards already say it properly — *"reaches ~4.8 picks early"* —
  so the phrasing exists; the form just doesn't use it. Label it
  **"Reaches this many picks early"** and let a negative number read as late.
- **`unpredictability`** is 0.1–3.0 with 1.0 as neutral, which no reader can
  infer. It wants named stops (predictable / normal / wild card), the way the
  gear's `chaos` slider already labels its own range.

### A4. The copy pass missed four files — HALF DONE 2026-09-07

Step 3 covered the six screens the review audited. These were never opened:

| File | Still reads | |
|---|---|---|
| `LiveStatusBar.tsx` | ~~`track`, `tracking…`, "Re-tick the poller and refresh seat mapping"~~ | done |
| `LiveDraftView.tsx` | ~~`fork to mock →`, `forking…`, `re-run`, "Board projected past pick 210"~~ | done |
| `SeatPopover.tsx` | `save`, `saving…`, `clear`, `cancel`, `reach bias`, `unpredictability`, `note` | **open** |
| `ManagerTendencies.tsx` | same six | **open** |

The two live-room files went along with A1, since the work was already in
them. The remaining two are the same duplicated form as A3, so do them
together — the labels are the substance and the button case is incidental.

### A5. ~~131 of 150 board cells render a placeholder em dash~~ — DONE 2026-09-07

**Built.** All 131 gone; an undrafted cell is now its pick number and nothing
else. Two things the fix needed that weren't obvious from the finding:

- **The pick number had to move into flow for that branch.** `.pickno` is
  absolutely positioned against the cell, so with the dash removed an empty
  cell had no flow content at all, collapsed to its own padding, and the number
  hung out the bottom of it.
- **The first attempt failed the contrast floor step 1 had just set.** Stacking
  `opacity: 0.55` over a lifted muted measured **4.16:1** — under AA, two rules
  below the fix that had raised `.pickno` out of exactly that. Plain
  `var(--muted)` is 5.37:1, is what the dash it replaces was already using, and
  stays quieter than a drafted cell's own number (5.6:1), which is the
  hierarchy wanted.

Side effect worth knowing, and a good one: rounds with no picks in them now
collapse to 28px against a drafted round's 70px, so the board's drafted region
visibly dominates instead of every round being the same height.

### A6. ~~There is no loading state anywhere in the app~~ — DONE 2026-09-07

**Built**, but not as "one shared skeleton at three call sites" — that plan was
wrong, and the reason is the useful part:

**A placeholder has to be the shape of what is coming, or it is a lie.** Only
`DraftPicker` gets skeleton rows, because only its two lists are built out of
`.draft-row`. The other two got words instead:

- `MockSetup` — a seat row is a label plus a full-width select, nothing like a
  `.draft-row`, and there is one fewer of them than there are teams. A row
  skeleton there would have been the wrong shape *and* the wrong count. It says
  "Loading managers you can seat…". (The original finding also overstated the
  harm: the controls sit *above* the seat list, so they don't move when it
  lands.)
- `MockDraftView` — what arrives is a board and its header strip. First version
  drew four list rows, which broke the rule that had just been written for
  MockSetup; it is now a centred line borrowing `.start-overlay-status`'s
  treatment, so a wait looks the same wherever the app does one.

**The skeleton's own geometry was wrong on the first pass, by 15px a row.** The
component's doc comment claimed it matched `.draft-row` exactly; measured, it
was 34px against a real 49px, so every row would have jumped when content
landed — the double-move a skeleton exists to prevent. The row's height comes
from the status chip, not the text, so the placeholder now carries a chip-shaped
element sized from it. Re-measured at 49px against 49px.

### A7. The room's vertical budget — our own regression, flagged in step 2

`OnTheClock` + `PickFeed` is ~150px where `RevealScrubber` was ~28px, and on
your own turn `PickPrompt` stacks under both. The board scroller still holds
7.6 rounds at 900px so nothing is unreachable, and the trade was deliberate —
but combined with the availability sheet floating over the board's lower half,
the number of rounds *visible without scrolling or collapsing* is down to about
three.

Revisit only if the room feels cramped in real use. The honest options are a
2-row feed, tighter feed rows, or hiding the feed while `PickPrompt` is up —
the last is best on content grounds and worst on layout stability, since it
makes the page jump exactly when you are trying to click a button.

---

## B. Carried over from the review

Unchanged in substance; see the review artifact for the before/after mockups.

- **B1. Home hero + data disclosure.** One primary action naming what you'd
  actually do next, leagues as quiet rows, every operator control (add a
  league, re-ingest, follow) folded into one disclosure. Today three panels of
  equal weight give database chores a third of the front door.
- **B2. Mock-setup seat strip.** Up to thirteen identical `<select>`s reading
  "Bot" become one seat control that shows the room's shape, with the CTA after
  the configuring instead of above it. Reusable for live-draft seat assignment,
  which has the same shape.
- **B3. Availability → survival strips.** Re-sort by what you'd lose rather
  than board rank, collapse four numeric columns onto one decay scale, drop
  trailing all-zero columns instead of rendering them. (The slider half of this
  item shipped in step 3.)
- **B4. Manager comparison axis.** Every card on a shared −8…+8 picks scale so
  the ranking is visible without reading fourteen sentences; the shouting green
  `FROM HISTORY` badge becomes the provenance dot the board already uses.
- **B5. The narrow-viewport bug.** Below ~700px the availability sheet, its
  toggle and (until step 3) its slider render outside the panel, over the
  board. `.avail-sheet` is positioned against `.board-stage` with no floor on
  the stage's width. Verified at 375×812.

---

## C. Suggested order

Payoff per hour, with the two consequences-of-step-2 items first because they
are load-bearing for consistency.

| | Item | Rough cost |
|---|---|---|
| ~~1~~ | ~~A1 live room onto `OnTheClock` + `PickFeed`~~ | done |
| ~~2~~ | ~~A4 copy pass, live-room half~~ | done |
| ~~1~~ | ~~A5 drop the placeholder dashes~~ | done |
| ~~2~~ | ~~A6 loading states~~ | done |
| 1 | B1 home hero + disclosure | ~2h |
| 2 | A3 + the rest of A4 — extract the tendencies form, fix its labels | ~2h |
| 3 | B2 mock-setup seat strip | ~3h |
| 4 | B3 survival strips | ~3h |
| 5 | B4 manager axis · B5 narrow viewport | ~4h |
| — | A2 availability in the mock room | scope first — backend work |
| — | A7 vertical budget | only if it bites |

Everything steps 1–3 opened is now closed. What is left is the review's own
steps 4–7 plus the tendencies-form extraction (A3 + the rest of A4), which is
the last place model internals are still facing the reader.

---

## D. Deliberately not doing

Recording these so nobody re-derives them.

- **Runs / chaos back into the room.** They live behind the gear on purpose
  (`claude/board-first-layout-and-pick-latency.md` §A). The room's header is
  for what is happening, not for tuning.
- **A manual reveal scrubber.** The reveal runs at its own 450ms tick and
  `skip` is the only way to move faster. That pacing is deliberate and is not
  the thing "too slow" ever referred to — see `project_sim_speed_not_pacing`.
- **Widening board columns past 96px at 14 teams.** Step 1 got truncation to
  zero at the existing width; spending horizontal space would undo the reason
  the abbreviation was the right fix rather than the fallback.
