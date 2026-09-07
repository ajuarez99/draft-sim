# Design review — what's left

Written 2026-09-07, straight after steps 1–3 shipped (`6a08ef1`, design doc
`claude/on-the-clock-and-name-abbreviation.md`). This is the live list. It
supersedes the review's own "what I'd do first" ordering, because building
steps 1–3 turned up seven things the review missed — including one gap that
step 2 *created*.

Nothing here is started. Every measurement was taken in the running app
against league `1391509063170293760` at 1440×900, not read off the source.

---

## A. Found while building steps 1–3

These come first, ahead of the review's own steps 4–7, because two of them are
consequences of what just landed and one is a feature the room is missing
rather than a styling problem.

### A1. The live room is now the odd one out — and that is our doing

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
Its inputs are already there: `live.picks` is the landed prefix.

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

### A4. The copy pass missed four files

Step 3 covered the six screens the review audited. These were never opened:

| File | Still reads |
|---|---|
| `LiveStatusBar.tsx` | `track`, `tracking…`, `title="Re-tick the poller and refresh seat mapping"` |
| `SeatPopover.tsx` | `save`, `saving…`, `clear`, `cancel`, `reach bias`, `unpredictability`, `note` |
| `ManagerTendencies.tsx` | same six |
| `LiveDraftView.tsx` | `fork to mock →`, `forking…` |

"Re-tick the poller" is the single worst string left in the product. Same
treatment as `track` → `Follow` on the picker.

### A5. 131 of 150 board cells render a placeholder em dash

Measured in a 10-team mock at pick 20. Every undrafted cell draws `—` plus its
pick number, so the great majority of the board at any moment is two glyphs of
nothing. The pick number alone already says "this pick hasn't happened"; the
dash is 131 marks carrying no information, on the surface the whole app is
built around.

Cheap, and it makes the drafted cells read louder without touching them.

### A6. There is no loading state anywhere in the app

Three specific spots, all the same bug:

- `MockDraftView.tsx:78` — `return <div className="content" />`. A literally
  blank screen while the session fetches.
- `DraftPicker.tsx:86,90` — both branches are gated on `drafts &&`, so while
  the fetch is in flight the panel renders a heading with nothing under it,
  then rows pop in.
- `MockSetup.tsx:126` — the seat list is simply absent until managers load, so
  the form changes height under the cursor.

Not a redesign: one shared skeleton row and one "loading" treatment, applied at
three call sites.

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
| 1 | A1 live room onto `OnTheClock` + `PickFeed` | ~2h |
| 2 | A4 copy pass on the four missed files | ~1h |
| 3 | A5 drop the placeholder dashes | ~15m |
| 4 | A6 loading states | ~1h |
| 5 | B1 home hero + disclosure | ~2h |
| 6 | A3 extract the tendencies form, fix its labels | ~2h |
| 7 | B2 mock-setup seat strip | ~3h |
| 8 | B3 survival strips | ~3h |
| 9 | B4 manager axis · B5 narrow viewport | ~4h |
| — | A2 availability in the mock room | scope first — backend work |
| — | A7 vertical budget | only if it bites |

Items 1–4 are one session and close every loose end steps 1–3 opened.

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
