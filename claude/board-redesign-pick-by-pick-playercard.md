# draft-sim — board visual redesign, pick-by-pick pause, player card

Design note, 2026-08-30. Requested by Allan against a screenshot of Sleeper's own
live draft room (not this app) as the visual reference. Three asks in one message,
grouped here because they touch the same three files. Built via a
review → build → code-review → verify pipeline (four separate agents), per this
project's established convention (`claude/live-reveal-and-tendencies-ui.md`,
`claude/live-poller-plan.md`). Allan is away for the pipeline's duration and asked
it to run without stopping for confirmation.

**Built and verified this session.** All 10 acceptance criteria below pass,
checked live in a real running browser, not just a clean `npm run build`
(though that's clean too). Code review (stage 3) found and fixed two real bugs
before verification ran: the player-card modal wasn't closed by `run()`, so
re-simulating while it was open left it showing a stale pick
(`DraftView.tsx`'s `run()` now calls `setOpenPick(null)`); and a comment on
`.board .cell:focus-visible` wrongly attributed overflow clipping to `.board`
after this same diff moved it to `.board-scroll` (comment corrected). The
verification stage (stage 4) then found two more, both fixed and re-verified
live: the "skip" control was gated on `isRevealing`, which goes false the
instant a pause starts — since this feature pauses on *every* one of your
picks, skip was invisible for long stretches of a real reveal
(`RevealScrubber.tsx`, now gated on `value < max` instead, matching what it
actually means: "there's still something left to reveal"); and, more
seriously, the board panel could collapse to 0px tall on anything shorter than
a maximized 1080p window (confirmed at a plain 720px-tall viewport), and
separately — found via a follow-up manual check after the agent pipeline
finished, at a 900px-wide window — the whole *page* scrolled horizontally
instead of the board's own scroller, because the grid's intrinsic minimum
width (14 columns × each column's `minmax()` floor) had no `min-width: 0`
anywhere in the flex chain above it to stop it propagating upward, the exact
same class of bug `min-height: 0` (see the file-level comment in
`styles.css`) already exists throughout this file to prevent on the vertical
axis — nobody had mirrored it for width until now. Both fixed in
`styles.css`: `.board-scroll` got an explicit `min-height`, and `min-width: 0`
was added alongside every existing `min-height: 0` in the board's flex chain
(`.content`, `.board-panel`, `.board-panel > .panel`, `.panel-body`).

The design below is preserved as-written for the record of what was planned;
the deviations above are the only places the implementation added something
this doc didn't originally call for.

**Deadline context:** fantasy(heart)'s real draft is 2026-08-31 21:15 CDT —
tomorrow, per `HANDOFF.md`. This is a same-day polish pass, not the start of
feature C (`claude/next-features-roadmap.md`'s interactive mock draft room, bots
picking, user picking on their turn). That feature needs new backend tables and a
`DraftContext` builder this doc doesn't touch. Scope here is presentation-layer
only, evolving the reveal system `live-reveal-and-tendencies-ui.md` already built
(Phase 1: stagger animation, Phase 2: scrubber) with a Phase 3, plus two smaller,
independent UI additions.

## 1. Pick-by-pick stepping that pauses at your picks

### What's there now

`useRevealedBoard.ts` reveals the whole precomputed board over a fixed ~4s
animation (chunked, not one pick at a time — `perTick = ceil(maxPickNo / ticks)`
can reveal several cells per 60ms tick), or the user drags `RevealScrubber`'s
slider to any point. Nothing about `myPicks` affects the animation; it runs
straight through your own picks the same as anyone else's. Allan: "the whole
simulation thing is still annoying, we want pick by pick and stops at your pick,
it should know what pick I'm at."

### Proposed design

Add a real pause mechanism keyed on `myPicks` (already computed server-side,
already threaded through as a prop). No backend change — `SimulationResult`
already has everything needed.

- `useRevealedBoard(board, maxPickNo, myPicks, opts)` — new `myPicks` param.
  Reveal one pick at a time (tickMs ~90-120ms — visibly sequential, not a blur)
  instead of the current chunked math. After each tick, if the just-revealed
  `pickNo` is in `myPicks`, clear the interval and set `pausedAt: pickNo`. Add
  `resume()`: clears `pausedAt`, restarts the interval from the current
  `revealedThrough`; if the paused pick was the final pick (`revealedThrough >=
  maxPickNo`), `resume()` is a no-op (nothing left to reveal). `skip()` keeps its
  current meaning (jump to fully revealed, bypassing all pauses — this is the
  existing, already-correct "let me get to the end" affordance, don't change it;
  it must also clear `pausedAt`). `scrubTo()` also keeps its current meaning
  (manual override, clears any pause) — dragging the slider past, before, or onto
  a pause point is an explicit user action, not something to fight; it must clear
  `pausedAt` too. **The existing top effect (keyed on `[b, maxPickNo]`, currently
  resets `revealedThrough`/`isRevealing` when a new board arrives) must also reset
  `pausedAt` to `null`** — otherwise starting a new simulation while paused leaves
  a stale banner referencing the old run's pick.
- `RevealScrubber.tsx` (or rename — it's becoming more than a scrubber) gets a
  third visual state alongside "revealing" / "idle": **paused**. Render a
  highlighted banner using the existing `.mine`/`--crimson` color already meaning
  "your pick" on the board (`.board .cell.mine`, `styles.css:170`) —
  e.g. "Your pick — Round 4.05 (pick 47) — `[Continue]`" — with a `Continue`
  button calling `resume()`. This directly answers "it should know what pick I'm
  at": the status line already renders `Pick {value} of {max}` (`RevealScrubber.tsx:21`);
  keep that always visible, paused or not, and add the round.pick label.
  `AvailabilityPanel.tsx` currently computes this inline as an unexported
  `label()` closure (`round = Math.ceil(pickNo / teams)`, `inRound = pickNo -
  (round-1)*teams`) — **extract it into a new shared util, e.g.
  `web/src/roundPickLabel.ts` exporting `roundPickLabel(pickNo: number, teams:
  number): string`**, and have both `AvailabilityPanel` and the
  scrubber/status-bar component import it. Same formula is needed again in §2's
  player card, below — one shared function, three call sites.
- Auto-play stays on by default (matches current behavior of animating without
  being asked); the only change in feel is that it now visibly stops on your
  turns instead of sailing through them.

### Not needed for this

Anything that lets the user *choose* a pick instead of seeing the predicted one —
that's feature C. This pauses on your picks so you can look, not so you can act.

## 2. Click a cell → player card

### What's there now

Each revealed `DraftBoard` cell has a native `title` attribute
(`DraftBoard.tsx:62-72`) carrying manager, probability, and alternatives —
browser-default tooltip styling, no click affordance, truncated names
(`.board .name` ellipsizes, `styles.css:172`) with no way to see the full name
short of widening the window. Allan: "clicking on a box should have some sort of
player card to see full name for now and pick at."

### Proposed design

- New `PlayerCard.tsx`, props `{ pick: PredictedPick; teams: number; onClose: ()
  => void }` — `teams` is required to compute the round/pick label via the same
  `roundPickLabel(pickNo, teams)` util from §1 (`PredictedPick` alone doesn't
  carry the in-round pick number). A small centered modal: backdrop `div` with
  `position: fixed; inset: 0; z-index: 100` (well above the board's sticky
  `.corner`/`.col-head`/`.rnd`, which use `z-index: 1-2` — without an explicit
  fixed position + higher z-index this renders as an inline block in normal page
  flow, not an overlay), dismissible via backdrop click, Escape key, or an
  explicit close button. Content for "for now": full player name (untruncated),
  position + team, the pick this cell is at in both forms (`roundPickLabel`
  output and overall `#pickNo`), the modal share (`Math.round(probability *
  100)}%` — already computed), and the existing `alternatives` list rendered as
  real rows instead of squeezed into a tooltip string.
- `DraftBoard.tsx`: cells that have a `visible` pick become clickable. Use a real
  `<button>` wrapping the cell's contents (not a `div` with `role="button"` —
  a native button gets focus styling, Enter/Space activation, and no accidental
  page-scroll-on-Space for free; don't hand-roll what the element already does).
  Add an explicit `:focus-visible` outline in CSS — `.board .cell` has none
  today. Add an `onCellClick?: (pick: PredictedPick) => void` prop. Hidden cells
  (pre-reveal, `—`) and cells with no pick stay inert (plain `div`, not `button`).
  Keep the `title` attribute as a fallback for anyone hovering without clicking —
  cheap to keep, no reason to remove it.
- `DraftView.tsx` owns `const [openPick, setOpenPick] = useState<PredictedPick |
  null>(null)`, passes `onCellClick={setOpenPick}` down, renders `{openPick &&
  <PlayerCard pick={openPick} teams={result.teams} onClose={() =>
  setOpenPick(null)} />}` at the top level (sibling of `.content`, inside `.app`)
  so it isn't a descendant of the board's own `overflow: auto` scroller
  (`.panel-body`, `styles.css:180`, is what actually clips — `.board-scroll` is
  just the class name on that same element, it carries no rule of its own). The
  fixed positioning above is what actually prevents clipping; being outside the
  scroller is necessary but not sufficient on its own.

### Not needed for this

Anything the card *does* beyond display — no queueing, no "draft this player."
Same non-goal as §1.

## 3. Visual redesign toward the Sleeper reference, and un-squishing

### What's there now, and the actual gap

The reference screenshot is Sleeper's live draft room: full-bleed width, a
compact header (league name, format line, avatar strip, icon-button row), a
dense grid with small cells, and a two-panel lower half (player pool + a
queue/roster/chat tab group) that also runs full width. draft-sim's `.app`
(`styles.css:45`) caps at `max-width: 1400px` and centers — on any screen wider
than that (Allan's, per the squished screenshot he sent, which shows real empty
gutter on both sides) the whole app sits in a fixed-width column while the
grid inside it still tries to fit 14+ columns, which is the actual cause of
"squished": it's not that there isn't room on screen, it's that the app has
fenced itself out of the room that exists. **"Don't be afraid to use the whole
screen"** — Allan's words, mid-session.

draft-sim is not rebuilding Sleeper's player-pool-to-draft-from panel — that
panel lets you draft, which is feature C, out of scope (see top of doc). The
redesign target is *visual family*, applied to the panels that already exist:
predicted board, availability table, confidence note, seats.

### Proposed design

- `.app` (`styles.css:45`): drop `max-width: 1400px`, keep a much larger sanity
  cap or none (e.g. `max-width: none` with existing `padding: 16px 20px` — at
  very wide monitors some side padding still reads better than edge-to-edge
  text, use judgment, but the current 1400px ceiling is the bug, not the
  padding).
- `.board` col-head / cell sizing (`styles.css:144-178`): with real width
  available, widen `minmax(84px, 1fr)` (`DraftBoard.tsx:26`) modestly (e.g.
  `minmax(96px, 1fr)`) so names stop needing to ellipsize as aggressively at
  normal team counts, rather than shrinking further — the reference's cells are
  compact but not truncation-heavy. `.board .corner`, `.col-head`, and `.rnd`
  (`styles.css:148-161`) all use `position: sticky` for the frozen row/column
  headers — verify these still stick correctly at the new width and column
  sizing; sticky positioning is sensitive to ancestor `overflow`/width changes,
  so this needs an explicit check, not an assumption.
- Header (`App.tsx`'s `.top`, plus `DraftView.tsx`'s `.controls`): restyle to
  read like the reference's header band — league identity prominent top-left,
  the run controls (slot/runs/chaos/run button) as a tighter inline toolbar
  rather than the current wrapped label stack. Do **not** add a "START DRAFT"
  button or anything implying a live/interactive session that doesn't exist —
  visual family, not a false affordance.
- `.lower-grid` (`styles.css:187-192`) and `.seats` (`styles.css:203`): both are
  already responsive grids (`minmax`/`auto-fill`); re-check their fixed pixel
  minimums (`300px` confidence column, `240px` seat cards) against the newly
  available width so they use it rather than leaving it as new dead gutter.
- General pass: existing spacing tokens (`.panel` padding, `gap` values) can
  scale up slightly at wide viewports — this is a "make it breathe," not a
  rewrite; the dark/panel/hairline-grid visual language already matches the
  reference's aesthetic (confirmed against `[[feedback_design_aesthetic]]` /
  `[[feedback_avoid_flat_uniform_cards]]` memory: card+avatar dark UI, tinted
  cells over flat uniform ones — keep it, this section is about space, not
  restyling colors or surfaces).

### Not needed for this

A literal clone of Sleeper's layout (player-pool-to-draft-from panel,
queue/roster/chat tabs) — those imply drafting functionality this app doesn't
have yet. Rebuilding `AvailabilityPanel` or `SeatList`'s actual content/columns —
their data model stays as-is, only their share of the newly-freed width changes.

## Acceptance criteria (for the verification pass)

1. App loads at a wide viewport (e.g. 1920px) with no large empty side gutters.
2. Running a simulation reveals the board one pick at a time (not a blur), and
   auto-play visibly stops the first time it reaches one of `myPicks`, with a
   banner naming the round/pick and a working Continue button.
3. The status line always shows current pick and round.pick label, paused or not.
4. Clicking any revealed cell opens a modal with the full (untruncated) player
   name, position, team, round/pick/overall numbers, modal %, and the
   alternatives list. Escape and backdrop-click both close it. Hidden cells do
   nothing on click.
5. Keyboard: a cell can be focused and opened with Enter/Space.
6. No backend files changed; no new network calls; `SimulationResult`'s shape
   is unchanged.
7. Existing scrubber drag-to-scrub and skip-to-end behavior still work exactly
   as before (regression check — this doc extends `useRevealedBoard`, easy to
   break `scrubTo`/`skip` while adding `pausedAt`).
8. Sticky column headers (`.col-head`) and round labels (`.rnd`) still stick to
   the top/left edges of the board scroller at a wide viewport, not just at the
   old 1400px width.
9. If your last pick is also the draft's final pick, pausing there and hitting
   Continue doesn't error or hang — it's a no-op, board stays fully revealed.
10. Re-running the simulation while paused at a pick clears the stale pause —
    no leftover "Your pick — Round X" banner referencing the previous run.
