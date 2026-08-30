# draft-sim — pick your own player at your picks, and slow the reveal down

Design note, 2026-08-30 (same day as `claude/board-redesign-pick-by-pick-playercard.md`,
after Allan tried that build live). Two asks in one message: it wasn't obvious the
pause at your pick is an actual decision point ("not sure... not clear that the user
has a choice"), and the pick-by-pick reveal felt too fast to read ("slow the draft
down to make it a better simulation like in Sleeper"). Built via the same
review → build → code-review → verify pipeline as the previous doc. Allan asked for
this to run unattended again ("allow anything").

## Scope decision, read this first

This does **not** build feature C (`claude/next-features-roadmap.md`'s interactive
mock draft room — bots reacting turn-by-turn to your real picks). That needs new
`mock_draft_session`/`mock_draft_pick` tables, a `DraftContext`-from-explicit-config
builder, and extracting `DraftSimulator.choose()` so batch and turn-by-turn share
one implementation — real backend work, per that doc "the largest lift" of the four
features it scoped, and not something to build unattended the night before a real
draft with zero backend test coverage written for it yet.

What this builds instead, and why it's still an honest answer to "let the user pick
players": `SimulationResult.availability` (`AvailabilityRow[]`, already computed by
`MonteCarloRunner`, already partly used by `AvailabilityPanel`) gives, for every
player, the real modeled probability he's still on the board at each of *your*
picks — `survivalByPick: Record<pickNo, number>`. That's an honest, already-correct
"who's realistically available here" list with zero backend changes. Selecting one
records it as your actual pick for that slot and is displayed as such — but the
**rest of the board stays the model's original, independent projection**, computed
without knowledge of your choice. It does not re-simulate downstream picks to
react to what you took. This is the same class of honesty tradeoff
`live-reveal-and-tendencies-ui.md` made for the reveal animation itself ("a reveal
animation of a result that already exists, not a literal live feed"), and it needs
the same explicit framing in the UI so nobody mistakes this for adaptive drafting:
the picker doesn't remove your pick from anyone else's realistic pool, doesn't add
him back for other seats, and picks after yours may still show him as available
(their probabilities were computed without knowing you took him). Caveat this
visibly (see §1's "Honesty" note) rather than silently.

Considered and rejected: using `SimulationResult.bestAvailable`
(`Map<Integer, List<Candidate>>` in `backend/.../engine/SimulationResult.java:15`,
currently unused anywhere in the frontend) instead of `availability`.
`MonteCarloRunner.java:124-126` shows why it's the wrong source: it only counts
each run's *single top-ranked* player at that pick (`avail[0]`), so it's "which
player was most often the single best option," a narrower and less complete signal
than `availability`'s real per-player survival curve, which is also the exact data
`AvailabilityPanel` already uses for this same "still there" question. One data
source, not two competing ones.

---

## 1. A real "make your pick" prompt when paused

### What's there now

`useRevealedBoard.ts` pauses (`pausedAt`) the instant the reveal hits one of
`myPicks`. `RevealScrubber.tsx:38-47` renders a banner — "Your pick — Round X.YY
(pick N)" plus a single "Continue" button. Continue is the *only* action: it just
un-pauses, identical to any other pick. Nothing distinguishes "this is a pick you
could make" from "this is just where the animation happens to have stopped."
Allan: "when its my pick im not sure and its not clear that the user has a choice
to pick a player."

### Proposed design

**Pull the pause banner out of `RevealScrubber` into its own component**,
`PickPrompt.tsx`. `RevealScrubber` goes back to being reveal mechanics only
(status line, skip, slider) — it doesn't need `pausedAt`/`onResume` once the
banner it was rendering moves out; drop those two props and the whole
`.pause-banner` block from it. Reasoning: the banner is about to need data
`RevealScrubber` has no other reason to hold (`availability`, the model's
`PredictedPick` for `pausedAt`) — better to give the new responsibility its own
component than thread more props through one that's about slider mechanics.

`PickPrompt` props: `{ pausedAt: number; teams: number; modelPick: PredictedPick |
undefined; onPick: (player: PlayerRef) => void; onOpenPicker: () => void }`.
`DraftView` renders it (instead of `RevealScrubber` rendering the banner) right
after `RevealScrubber`, only when `reveal.pausedAt != null`; `modelPick` is
`result.board.find(p => p.pickNo === reveal.pausedAt)` (a `Map` lookup like
`DraftBoard` already builds would be cheap to reuse — don't recompute a linear
`.find` if a lookup structure already exists nearby, but this is a one-off per
pause, not a hot path, either is fine). `PickPrompt` itself never opens
`PlayerPicker` — it only reports the click via `onOpenPicker`; see below for
where that state actually lives, and no, there is no third "skip this one pick"
action — the reveal's existing skip-to-end (`RevealScrubber`'s `onSkip`) is the
only skip concept in this app, don't add a second, narrower one under a similar
name.

**Where "is the picker open" lives, precisely, because this is the exact kind of
state a back-to-back-picks scenario (already confirmed real and already tested in
the prior doc — e.g. slot 14 gets picks 14 and 15 back to back) will expose if
it's wrong:** a new `DraftView` state, `const [pickerOpen, setPickerOpen] =
useState(false)`. `PickPrompt`'s `onOpenPicker` sets it `true`. It is reset to
`false` in exactly two places: inside `choosePick()` (§3 — picking anything,
either path, closes the picker) and inside `run()` alongside the existing
`setOpenPick(null)` reset (`DraftView.tsx:66`). This guarantees it can never carry
over from one pause to the next: `choosePick` is the only way a pause ends (either
directly, or via the picker's row-click which itself calls `choosePick`), so by
the time the *next* pause happens, `pickerOpen` has already been forced back to
`false`. Do not derive `pickerOpen` from `pausedAt` (e.g. "open whenever paused")
— that would reopen the modal on every future pause instead of only when the user
actually clicked "Choose a player."

Content: "Your pick — Round X.YY (pick N)" (unchanged wording), then two actions:
- **Primary**: "Take {modelPick.player.name}" (shows the model's own prediction —
  keeps today's one-click "just watch it" flow alive for anyone who doesn't want to
  browse every time). Calls `onPick(modelPick.player)` directly, no modal.
- **Secondary**: "Choose a player" — calls `onOpenPicker` (sets `pickerOpen`
  true, per above); does not call `onPick` itself.

Both paths funnel through the same `onPick` — accepting the model's suggestion
*is* a pick, not a different code path, so there's exactly one place that records
a chosen player and resumes the reveal (see §3's `choosePick` in `DraftView`).

**Honesty note, put directly in the UI, not just this doc:** the picker (§2) needs
a visible line making clear this doesn't ripple through the rest of the board —
something like "Doesn't change the rest of the board — later picks are still the
model's own projection." Skipping this framing risks the same silent-wrongness
class of bug `claude/lessons.md` warns about elsewhere in this project: numbers
that read as more authoritative than they are.

## 2. The available-players picker

### Proposed design

New `PlayerPicker.tsx` modal, same visual family as `PlayerCard.tsx` (backdrop +
centered card, Escape/backdrop/× close, `modal-backdrop`/`modal-card` classes
already exist — reuse them, add a size variant if the row list needs more width/
height, e.g. `modal-card wide`, rather than inventing new modal chrome). Props:
`{ pausedAt: number; teams: number; availability: AvailabilityRow[];
alreadyPicked: Set<number>; onPick: (player: PlayerRef) => void; onClose: () =>
void }`. `DraftView` renders it when `pickerOpen && reveal.pausedAt != null`.
**`onClose` and `onPick` are different actions, not two names for the same
thing**: `onClose` (Escape/backdrop/× ) only sets `pickerOpen` back to `false` —
still paused, `PickPrompt` still underneath with "Take model's pick" still
available, nothing recorded. `onPick` (a row click) goes to `choosePick` (§3),
which records the player *and* resumes — `pickerOpen` gets reset there too (per
above) as a side effect of that, not because `PlayerPicker` called `onClose`
itself.

- Rows: `availability.filter(r => (r.survivalByPick[String(pausedAt)] ?? 0) > 0.01
  && !alreadyPicked.has(r.player.id)).sort(descending by that probability)`. Each
  row: position badge (`.pos` class, already exists), name, team, ADP, and the
  survival % at this exact pick — reuse `AvailabilityPanel`'s existing bar-cell
  presentation (`.bar-cell`/`.bar`/`.bar-label`, `styles.css`) for the probability
  rather than inventing a second way to draw the same kind of number.
- Position filter chips — `ALL`/`QB`/`RB`/`WR`/`TE`, identical mechanism to
  `AvailabilityPanel.tsx`'s existing `POSITIONS`/`filter` state (copy the pattern,
  it's a few lines, not worth extracting a shared component for this alone).
  Simple text search input is a nice-to-have if there's time left after the must-
  haves below pass verification; not required for acceptance.
- Clicking a row calls `onPick(row.player)` — `choosePick` (§3) records the pick,
  clears `pickerOpen`, and calls `resume()`, and `resume()` clears `pausedAt` too,
  so both `PickPrompt` and `PlayerPicker` disappear together once a pick lands.
- Empty state: if the filtered list is empty (extreme temperature/late-round
  edge case where nothing survives above 1%), show a message and keep the
  "Take {modelPick}" fallback reachable (it's on `PickPrompt`, still behind the
  now-open modal — make sure closing the modal via Escape/backdrop doesn't lose
  that option, it shouldn't, since `PickPrompt` stays mounted underneath).

## 3. Recording a pick, and where it shows up

### Proposed design

`DraftView.tsx` gets `const [userPicks, setUserPicks] = useState<Record<number,
PlayerRef>>({})`, reset to `{}` in `run()` alongside the existing `setOpenPick(null)`
(`DraftView.tsx:66`) — same staleness class of bug already fixed once this session,
don't reintroduce it for a third piece of state.

```
function choosePick(player: PlayerRef) {
  if (reveal.pausedAt == null) return
  if (Object.values(userPicks).some((p) => p.id === player.id)) return // already claimed at an earlier pick
  setUserPicks((prev) => ({ ...prev, [reveal.pausedAt!]: player }))
  setPickerOpen(false)
  reveal.resume()
}
```

The dedup check lives here, once, precisely because both entry points
(`PickPrompt`'s "Take model's pick" and `PlayerPicker` row clicks) call this same
function — `PlayerPicker`'s own `alreadyPicked` filtering (§2) keeps the *list*
clean, but "Take model's pick" has no list to filter, so the real guarantee has to
be in `choosePick` itself, not duplicated in two places. If it refuses (silently
no-ops, stays paused, nothing recorded) — a real but rare case, since the model's
own suggestion at pick N colliding with something you already took at pick M<N
means the model didn't know either — showing an inline error isn't worth the
complexity for something this unlikely; not resuming makes it visible enough that
you'll notice nothing happened and pick differently via the picker instead.

Passed to both `PickPrompt` (its "Take model's pick" button) and `PlayerPicker`
(row clicks) as the same `onPick`/`choosePick` — one function, both entry points.

`alreadyPicked` (passed into `PlayerPicker`, see §2) is `new
Set(Object.values(userPicks).map(p => p.id))` — computed in `DraftView`, not
memoized unless a profiler says otherwise (this is a handful of ids, recomputing
per render is not a hot path).

**`DraftBoard.tsx` gets a new prop `userPicks: Record<number, PlayerRef>`.** In the
per-cell loop (`DraftBoard.tsx:52-104`), when `userPicks[pickNo]` exists (call the
value `chosen: PlayerRef | undefined`), the cell renders **only** `chosen`'s own
data — name, position, team — and drops every model-derived number entirely for
that cell: no probability, no `bar-fill`/`bar-track`, no `prob` span. Those
numbers describe the model's prediction, and `chosen` is not the model's
prediction, so showing them next to his name would misattribute a probability
that was never computed for him being picked by you (this is the same
"tooltip/percentage left stale" problem the review pass for this doc flagged —
fix it by removing those elements for a chosen cell, not by leaving them pointed
at the wrong player). `cls` (`DraftBoard.tsx:55-58`) becomes:
`'cell' + (mine.has(pickNo) ? ' mine' : '') + (chosen ? ' chosen' :
visible && !visible.isModal ? ' uncertain' : '')` — **`chosen` and `uncertain`
are mutually exclusive**; a cell you actually picked is never "uncertain," that
label only describes the model's own guess-quality. `titleAttr` for a chosen cell
becomes something like `` `Your pick — ${chosen.name}` `` instead of the
model-manager/probability/alternatives string built today — same reasoning, don't
show model numbers as if they describe your pick. `chosen` always implies `mine`
(pausing only ever happens on `myPicks`), so visually treat it as an addition to
the existing crimson `mine` styling — a small badge/icon, not a full recolor that
would make `mine`-but-not-yet-`chosen` cells (the far more common case at any
given moment) harder to spot. The underlying `visible` (model) `PredictedPick` is
still what gets passed to `onCellClick` regardless of `chosen` — `PlayerCard`
needs the model's context too, see below.

**`PlayerCard.tsx` gets a new optional prop `yourPick?: PlayerRef`.** `DraftView`
passes `userPicks[openPick.pickNo]` when opening the card for a picked cell. When
present, show it prominently above the existing model content — "You picked:
{yourPick.name}" — and relabel what's currently shown as *the* pick to make clear
it's the model's own projection instead ("Model's own pick here: {pick.player.name}
— {probability}%"), not a second, competing "the pick" without qualification.

**`AvailabilityPanel.tsx` gets a new prop `pickedPlayerIds: Set<number>`.**
Filter it into the existing `rows` computation (`AvailabilityPanel.tsx`'s
`useMemo`) so a player you've already claimed doesn't keep appearing as "still
available" in the table below the board — the same
`Object.values(userPicks).map(p => p.id)` set from `DraftView`, reused, not
recomputed a second way.

### Not needed for this

Persisting `userPicks` anywhere (URL, localStorage, backend) — it's ephemeral like
everything else derived from a `result`, gone on next `run()`, consistent with how
`reveal` state already works. No changes to `PredictedPick`, `AvailabilityRow`, or
any backend/API contract — `PlayerRef` (already in `api.ts`) is all the picker
needs to hand back.

---

## 4. Slow the reveal down

### What's there now

`useRevealedBoard.ts`'s `startTicking()` reveals one pick per `tickMs`, default
`100` (`useRevealedBoard.ts`, `opts?.tickMs ?? 100`). At 100ms/pick a 14-team round
finishes in 1.4s between pauses — reads as a blur, not a draft happening. Allan:
"we need to slow the draft down to make it a better simulation like in sleeper."

### Proposed design

Raise the default to `450`ms. Not configurable via UI for this pass (no slider/
setting) — a single tuned constant is enough to answer "too fast," and adding a
speed control is a separate, smaller feature to reach for later if 450ms turns out
to be the wrong number, not something to design now on top of everything else in
this doc. Comment the constant with *why* 450 (roughly: fast enough that a 14-pick
round between your turns is ~6s, not so fast it blurs, not so slow it drags) so a
future change to it is a deliberate retune, not a guess.

### Explicitly not building this pass

An auto-pick countdown (Sleeper's real per-pick clock, autopicking the model's
choice if you don't act in time) and a speed control are natural extensions of
both halves of this doc, and both were considered. Cut for scope discipline, not
because they're bad ideas — two solid, fully-verified features beat three rushed
ones landing the night before a real draft. Worth a follow-up doc if wanted.

## Acceptance criteria (for the verification pass)

1. Pausing at one of your picks shows two clear actions: take the model's
   suggested player in one click, or open a picker of other realistic options —
   not just an unlabeled "Continue."
2. The picker lists players still plausibly available at that exact pick
   (nonzero `survivalByPick` for that pick number), ranked most-likely-available
   first, excludes anyone already chosen at an earlier one of your picks (via the
   picker or the "take model's pick" shortcut, either one), and has working
   position filter chips.
3. Choosing a player (either the quick "take model's pick" or a picker row)
   immediately: closes the picker, resumes the reveal past that pick, and updates
   the board cell at that pick to show the chosen player, visually marked as
   chosen (not just re-showing the model's guess).
4. Opening the player card for a picked cell shows both your actual pick and the
   model's own original projection, clearly distinguished — not one overwriting
   the other silently.
5. The availability table below the board no longer lists a player you've already
   picked as available at your later picks.
6. The picker modal's Escape/backdrop/close all work, exactly like `PlayerCard`'s
   already do, and don't lose the ability to reopen it or still take the model's
   suggestion afterward.
7. The pick-by-pick reveal visibly runs slower than before — a full round between
   two of your picks should read as a sequence, not an instant jump.
8. Re-running the simulation clears `userPicks` along with the existing
   `openPick`/`pausedAt` resets — no stale "you picked X" surviving into a new run.
9. Everything from the previous doc's 10 criteria still holds (regression check):
   sticky headers, full-width layout, keyboard-openable cells, skip visible while
   paused, no horizontal page overflow at a narrow window, board panel never
   collapses to 0 height.
10. No backend files touched, no new network calls, no changes to any type in
    `api.ts` beyond what the frontend already had.
