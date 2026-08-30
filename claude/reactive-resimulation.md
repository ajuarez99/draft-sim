# draft-sim — reactive resimulation: your pick changes the rest of the board

Design note, 2026-08-30, written directly after `claude/your-pick-and-pacing.md` shipped
and Allan reviewed it live. That doc's picker was explicit about a real limitation:
"Doesn't change the rest of the board — later picks are still the model's own
projection." Allan: "we need to resimulate the draft because that changes the whole
choice for future players." Requested to run unattended for ~2 hours with full
permissions, via the same plan → review → build → code-review → verify pipeline as
the prior two docs.

**Amended after the review pass**, before any code was written. The review found six
real gaps in the version above this line (see each section for the specific fix):
missing test coverage for `resolveStartState`'s silent-drop behavior that the
defensive check in §3 depends on; `AvailabilityPanel` defaulting to showing
already-decided picks instead of upcoming ones once resim ships; no guard against a
stale resim response landing after a fresh `run()`, including a cross-draft leak since
`App.tsx`'s `<Route>` doesn't key `DraftView` on `draftId`; an identical stale honesty
caveat in `PlayerPicker.tsx` that §4 only caught in `PickPrompt.tsx`; the defensive
mismatch check only validating the just-made pick instead of the whole locked prefix;
and an unversioned "measure it and decide" performance stance with no interim cap for
an overnight unattended build. All six are folded into the sections below.

## Scope decision, read this first

`claude/next-features-roadmap.md` scoped this exact idea as **Feature C**, calling it
"the largest lift" of four planned features, requiring: a `mock_draft_session`/
`mock_draft_pick` V3 migration, a `DraftContext`-from-explicit-config builder, and
extracting the private `DraftSimulator.choose()` into a reusable decide-and-apply unit
shared between the batch simulator and a new turn-by-turn service. That doc explicitly
says not to build this unattended the night before a real draft with zero backend test
coverage written for it.

Reading the actual engine code changes the picture. `SimulationRequest` already has a
`startState: Map<Integer pickNo, String sleeperPlayerId>` field
(`engine/SimulationRequest.java`) — "already-made picks... Empty = cold start."
`SimulationService.resolveStartState()` (lines 96-112) already prefers an explicit
`startState` over the DB, and feeds it straight into `DraftContext.completedPicks`.
`DraftSimulator.run()` (lines 30-80) already short-circuits `choose()` for any pick
number present in `completedPicks` — it just replays that exact player against local
`available`/`rosters`/`recent` state and moves on to the next pick, calling `choose()`
(the real scoring/sampling step) only for whatever isn't locked in. **This is Feature
C's entire "condition the rest of the board on picks that have already happened"
mechanism, already built, already covered by existing tests, currently only reachable
by hand-constructing a request in Postman** (confirmed nowhere in
`SimulationController.java` or the frontend's `SimRequest` type today —
`HANDOFF.md`'s own words: "still unexposed in the UI").

**What this builds**: expose `startState` through the existing `/api/sims/stream`
endpoint and existing `DraftView` pick-choosing flow. When you pick a player at one of
your paused picks, treat everything revealed so far (every pick 1..that pick, using
your own choice wherever you made one, the model's own already-shown prediction
everywhere else) as decided, and kick off a fresh Monte Carlo run seeded with that as
`startState`. The board that comes back is a real, correctly-conditioned simulation of
what happens *given* your pick — not a cosmetic overlay. Swap it in as the new
`result`, and let the reveal continue from exactly where it paused.

**What this does NOT build, deliberately**: no new tables, no session persistence, no
`DraftContext`-from-config builder, no extraction of `DraftSimulator.choose()`, no
turn-by-turn bot-reacts-immediately room where you watch each opposing pick land one at
a time. This is still a whole-draft Monte Carlo re-run per user pick, the same
mechanism and the same endpoint the app already runs once per "run" click today — it's
called more often (once per your pick, in addition to the initial run) and seeded
differently, not replaced with new machinery. `userPicks`/reveal state stays exactly as
ephemeral as before — nothing persisted beyond the current browser session. This is a
strictly smaller, already-battle-tested-at-the-engine-level surface than Feature C, and
it fully answers "future picks change" without touching schema the night before a real
draft.

Considered and rejected: building the real Feature C (session tables, turn-by-turn bot
reactions). Genuinely the right feature eventually — bots reacting to you turn-by-turn,
resumable across a refresh — but unjustifiable risk for tonight given zero backend test
coverage exists for it yet and the draft is tomorrow. This doc's approach reaches the
actual stated goal ("future picks change based on my pick") using a path already
exercised by `DraftSimulatorTest`/`MonteCarloRunnerTest`/`SimulationServiceTest` (the
`completedPicks` resume mechanism is exactly how the app already resumes a real,
partially-drafted Sleeper draft — this is not new engine surface, just a new caller).

## 1. One small, additive backend change: expose each player's Sleeper ID

### What's there now, and the actual gap

`startState`'s values are Sleeper player-id strings (`resolveStartState` resolves them
via `PlayerRepository.idsBySleeperId()`). But `SimulationResult.PlayerRef` — the only
player shape the frontend ever sees, in `board`, `availability`, `bestAvailable`,
everywhere — only carries the internal numeric `id` (`engine/SimulationResult.java:19`):

```java
public record PlayerRef(long id, String name, String position, String team, double adp) {}
```

`MonteCarloRunner.ref(BoardEntry e)` (`MonteCarloRunner.java:156-160`) is the one and
only place a `PlayerRef` is constructed:

```java
private static SimulationResult.PlayerRef ref(BoardEntry e) {
    if (e == null) return null;
    return new SimulationResult.PlayerRef(
            e.player().id(), e.player().name(), e.position().name(), e.player().team(), e.adp());
}
```

`e.player()` is a `domain.Player`, which already carries `sleeperId` (it's how ingest
matches players in the first place — `PlayerRepository.idsBySleeperId()` reads exactly
this column). The frontend cannot build a `startState` payload without it: every player
it knows about (a `PredictedPick.player`, an `AvailabilityRow.player`) is a `PlayerRef`
with only the internal id, and `startState`'s wire contract wants the Sleeper id.

### Proposed design

Add `sleeperId` to the record and thread it through the one call site:

```java
public record PlayerRef(long id, String sleeperId, String name, String position, String team, double adp) {}
```
```java
return new SimulationResult.PlayerRef(
        e.player().id(), e.player().sleeperId(), e.player().name(),
        e.position().name(), e.player().team(), e.adp());
```

This is the only backend production-code change this feature needs. No migration, no
new endpoint, no new table, no new service. `PlayerRef` is constructed in exactly one
place (confirmed by grep — the only two matches for `PlayerRef(` in `backend/src` are
the record declaration and this one call site), so this is a safe, purely-additive
field addition. Add `sleeperId` to the frontend's `PlayerRef` type in `api.ts` to match.

## 2. Threading `startState` through the frontend request path

### What's there now

`web/src/api.ts`'s `SimRequest` type has no `startState` field, even though the backend
`SimulationRequest` record already accepts one:

```ts
export type SimRequest = {
  draftSleeperId: string
  mySlot: number
  iterations: number
  temperature: number
}
```

### Proposed design

```ts
export type SimRequest = {
  draftSleeperId: string
  mySlot: number
  iterations: number
  temperature: number
  startState?: Record<number, string>
}
```

`streamSimulation()` already forwards `req` as-is to `JSON.stringify(req)` — no change
needed there. An `undefined` `startState` serializes as an absent key, which Jackson
already treats as "no explicit start state, resolve from the DB" (`resolveStartState`'s
`if (req.startState() != null && !req.startState().isEmpty())` guard) — so the existing
`run()` call, which never sets `startState`, keeps behaving exactly as it does today.

## 3. `DraftView`: build `startState` from what's already on screen, and trigger a resim on every pick

### What's there now

`choosePick()` (`DraftView.tsx:78-84`) only ever writes local state:

```tsx
function choosePick(player: PlayerRef) {
  if (reveal.pausedAt == null) return
  if (Object.values(userPicks).some((p) => p.id === player.id)) return
  setUserPicks((prev) => ({ ...prev, [reveal.pausedAt!]: player }))
  setPickerOpen(false)
  reveal.resume()
}
```

`DraftBoard`/`PlayerCard` render `userPicks` as a cosmetic overlay on top of `result`,
which never changes as a result of a pick. This is the exact behavior being replaced.

### Proposed design

`choosePick` becomes async. It builds a `startState` covering every pick from 1 through
the pick just made — your own choice wherever you have one (this pick, or an earlier
one of yours), the model's own already-revealed prediction everywhere else — and
re-runs the simulation with it before resuming the reveal:

```tsx
// Bumped at the start of *every* streamSimulation call (a fresh run() or a
// resim), never read for its value beyond identity -- exists purely so a
// response can tell whether it's still the most recent request before
// applying itself. See "stale-response guard" below for why this exists.
const requestSeqRef = useRef(0)

async function choosePick(player: PlayerRef) {
  if (reveal.pausedAt == null || resimming || !result) return
  if (Object.values(userPicks).some((p) => p.id === player.id)) return
  const pausedAt = reveal.pausedAt
  const nextUserPicks = { ...userPicks, [pausedAt]: player }
  setUserPicks(nextUserPicks)
  setPickerOpen(false)

  // Everything through this pick is now "decided": your own picks win, the
  // model's own already-shown prediction fills every other slot. A pick number
  // with no board entry at all (BoardAssembler.assemble skips a pick only when
  // literally every run ran out of distinct candidates there -- a late-round
  // edge case) is left out of startState rather than guessed at; the engine
  // just resimulates that one slot fresh, which is the same thing it would do
  // if this pick had never been reached yet.
  const startState: Record<number, string> = {}
  for (let n = 1; n <= pausedAt; n++) {
    const chosen = nextUserPicks[n]
    if (chosen) { startState[n] = chosen.sleeperId; continue }
    const predicted = result.board.find((p) => p.pickNo === n)
    if (predicted) startState[n] = predicted.player.sleeperId
  }

  const seq = ++requestSeqRef.current
  setResimming(true)
  setResimProgress(0)
  try {
    const r2 = await streamSimulation(
      {
        draftSleeperId: draftId,
        mySlot,
        iterations: Math.min(iterations, RESIM_ITERATION_CAP),
        temperature: result.temperature, // frozen to what produced the prefix being locked in, not the live slider -- see below
        startState,
      },
      setResimProgress,
    )
    if (seq !== requestSeqRef.current) return // superseded by a newer run()/pick -- see stale-response guard

    // Defensive: startState resolution can silently drop an unmapped sleeperId
    // (SimulationService.resolveStartState just skips it rather than erroring).
    // Checking only the pick just made isn't enough -- an earlier locked pick
    // could just as easily be the one silently dropped, quietly re-deciding a
    // pick the user already watched happen. Check the WHOLE locked prefix.
    const byPickNo = new Map(r2.board.map((p) => [p.pickNo, p]))
    for (const [pickNoStr, sleeperId] of Object.entries(startState)) {
      const landed = byPickNo.get(Number(pickNoStr))
      if (!landed || landed.player.sleeperId !== sleeperId) {
        throw new Error(`Resimulation didn't preserve pick ${pickNoStr} as decided — showing the prior board.`)
      }
    }
    setResult(r2)
    reveal.resume()
  } catch (e) {
    if (seq !== requestSeqRef.current) return // a newer request already owns the screen; don't surface a stale error over it
    setError(e instanceof Error ? e.message : String(e))
    reveal.resume() // don't strand the user paused forever; continue against the stale board
  } finally {
    if (seq === requestSeqRef.current) setResimming(false)
  }
}
```

New `DraftView` state: `const [resimming, setResimming] = useState(false)` and
`const [resimProgress, setResimProgress] = useState(0)`. Both reset in `run()`
alongside the existing `setUserPicks({})`/`setOpenPick(null)` resets — same staleness
class of bug already fixed twice this project, don't reintroduce it for a fourth piece
of state. `run()` also does `const seq = ++requestSeqRef.current` at its own start and
gates its `setResult(r)`/state-reset block on `seq === requestSeqRef.current` after the
`await`, symmetric with `choosePick` above.

**Stale-response guard, added from the review pass.** The original draft of this
section relied on `disabled={running || resimming}` alone to prevent overlapping
`streamSimulation()` calls. That's a UI nicety, not a correctness guarantee — a click
already queued before React commits the `disabled` attribute still fires `run()` or
`choosePick` unconditionally, and even with no double-click at all, nothing stopped
whichever of two in-flight requests happened to *resolve* last from winning regardless
of which was *started* last (acceptance criterion #6 explicitly asks to force exactly
this race via devtools). `requestSeqRef` fixes this cheaply: every `streamSimulation`
call captures the post-increment counter value before awaiting, and only applies its
own result if the counter still matches when it returns — a newer call, whether a
fresh run or another pick, always wins over an older one that resolves later.

**Cross-draft leak, also from the review pass.** `App.tsx`'s route
(`<Route path="/drafts/:draftId" element={<DraftView />} />`) doesn't key `DraftView`
on `draftId`, so React Router does not remount it when the user navigates from one
draft to another — `result`/`userPicks`/`resimming`/`requestSeqRef` all persist across
that navigation today, and a resim left in flight when the user switches drafts would
otherwise land later and silently paint one draft's resimulated board onto another
draft's now-open screen. Fix in `App.tsx`, not `DraftView.tsx`: `<Route
path="/drafts/:draftId" element={<DraftView key={draftId} />} />` (destructure
`draftId` from `useParams` at the `App` level, or read it via a small wrapper) forces a
full remount — and therefore a fresh `requestSeqRef`, fresh `result`, fresh everything
— on every draft change, which is simpler and more robust than manually auditing every
piece of state for a "reset on draftId change" effect.

`iterations` for the resim is capped at a new constant,
**`RESIM_ITERATION_CAP = 2000`**, via `Math.min(iterations, RESIM_ITERATION_CAP)` —
correction from the review pass on the first draft's "just reuse whatever `iterations`
is selected and measure later" stance: with no human in the loop overnight to react if
that turns out too slow, and a resim now firing on every one of a user's ~15 picks in a
14-team/15-round draft rather than once per session, shipping with no interim cap at
all was judged too risky to leave fully open. 2000 is a reasoned starting point (it's
this project's own existing default for a *whole-draft* run, per `DraftView.tsx`'s
`useState(2000)`), not a verified one. **The verification pass for this doc must still
measure actual resim wall-clock time** at this cap, with a realistic mid-draft prefix
(not an empty board), and report the real number — raise or lower the cap from 2000
based on that measurement, the same "run it, don't guess" discipline this project's own
`HANDOFF.md` round-1-probability writeup already established, just applied to picking
a safe default first instead of shipping with none. Only the resim call is capped —
`run()`'s initial whole-draft simulation still uses whatever the "runs" `<select>` has
chosen (500-10,000), unchanged. Re-simulating from a locked-in prefix is only cheaper
than the initial run at the same iteration count (every locked pick short-circuits
straight to a replay, skipping the scoring/sampling loop entirely —
`DraftSimulator.run()`'s `already != null` branch), so later-draft resims should be
faster than an equivalent-iteration initial run, not slower.

**`temperature` frozen to `result.temperature`, not the live "chaos" slider —
correction from the review pass.** The first draft read live `temperature` component
state at pick time. If the user changes the chaos slider after `run()` but before
making a paused pick, a resim seeded from live state would generate every future pick
at a different chaos level than the one that produced the already-shown, now-locked
prefix — engine-harmless (locked picks are replayed verbatim regardless of
temperature) but an inconsistency between what's displayed and what's requested. Using
`result.temperature` for the resim keeps it consistent with what's already on screen;
the live slider still fully controls the next fresh `run()`.

### Why `run()`'s existing "fresh board" reset must NOT fire on a resim

`useRevealedBoard`'s top effect resets `revealedThrough`/`pausedAt`/`isRevealing` to
their zero state whenever its `[b, maxPickNo]` dependency changes
(`useRevealedBoard.ts:45-60`). `setResult(r2)` from a resim swaps in a new `board`
array (new reference), which would fire that exact effect and restart the whole reveal
from pick 1 — completely breaking the feature, since the entire point is to keep
watching from exactly where you paused. **`useRevealedBoard` needs a fourth parameter,
`resetKey: number`**, and the effect's dependency array changes from `[b, maxPickNo]`
to `[resetKey]`:

```ts
export function useRevealedBoard(
  board: PredictedPick[] | undefined,
  maxPickNo: number,
  myPicks: number[] | undefined,
  resetKey: number,
  opts?: { tickMs?: number },
)
```

`DraftView` owns `const [runSeq, setRunSeq] = useState(0)`, incremented only inside
`run()` (`setRunSeq((n) => n + 1)`), never touched by `choosePick`/resim. Pass it as the
new fourth argument. A resim's `setResult(r2)` changes `board`/`maxPickNo` (well,
`maxPickNo` — `teams * rounds` — is actually invariant across a resim of the same
draft, only `board`'s identity changes) without changing `runSeq`, so the reset effect
does not fire; `revealedThrough`/`pausedAt` stay exactly where they were.

This is safe specifically because nothing `resume()`/`skip()`/`scrubTo()` read
(`mine`, `maxPickNo`, `pausedAt`, `revealedThrough`, `timerRef`) actually differs across
a resim of the same draft (same `mySlot`, same `teams`/`rounds` → same `myPicks`/
`maxPickNo`) — the only thing that changes is `board` content, which every consumer
(`DraftBoard`, `PickPrompt`, `AvailabilityPanel`) reads fresh off `result` on next
render regardless of when `reveal.resume()` was called relative to `setResult()`. Do
not "fix" this by making `resume()` take `board` as an argument — there is nothing
stale to fix; the only real bug this doc's design addresses is the effect's reset
trigger, and it is fixed by decoupling the reset trigger from board identity, not by
touching `resume()`/`skip()`/`scrubTo()` at all.

## 4. Disable interaction while a resim is in flight

### What's there now

Nothing gates on "a resim is happening" because nothing triggers one yet.

### Proposed design

While `resimming` is true:
- **`PickPrompt` and `PlayerPicker` do not render.** In their place (same slot in
  `DraftView`'s JSX, same `.pause-banner` visual family), render a status banner:
  "Recalculating the board past pick {pausedAt}... {pct}%" using `resimProgress`,
  reusing `.pause-banner` styling from `PickPrompt`'s own CSS rather than inventing a
  new banner style. This is not just a loading spinner choice — it's the reason a
  second pick attempt can never race the first: there is no picker to click while this
  is showing.
- **`RevealScrubber` gets a new `disabled?: boolean` prop**, passed `resimming`. When
  true: the range input gets `disabled`, and the skip button gets `disabled` too (skip
  during a resim would race `reveal.skip()`'s `setPausedAt(null)` against the resim's
  own eventual `reveal.resume()` call — trivially avoided by disabling it, not worth
  reasoning through the interleaving). The status line keeps rendering (still useful to
  see "Pick 47 of 210" while waiting).
- **The "run" button gets `disabled={running || resimming}`** — re-running a fresh
  simulation while a resim for the current one is in flight is a real, if unlikely,
  race (two different `SimulationResult`s could land out of order); simplest fix is
  not letting it start, not reasoning about which `setResult` wins.

### Update the honesty caveat — in BOTH places it appears

`PickPrompt.tsx`'s existing line — "Doesn't change the rest of the board — later picks
are still the model's own projection" — becomes false once this ships; it must be
corrected to actually describe the new behavior, not left as a stale claim (this is
the exact "numbers/claims that read as more authoritative than they are" class of bug
`claude/lessons.md` and this project's prior two design docs both call out). Replace
with something like: "Recalculates every pick after this one based on what you took —
may take a few seconds."

**Correction from the review pass: the identical sentence also appears verbatim in
`PlayerPicker.tsx`** ("Doesn't change the rest of the board — later picks are still the
model's own projection," in its `<p className="muted small">` under the round/pick
label) — the first draft of this doc only named `PickPrompt.tsx`. Since `PlayerPicker`
is the other of the two entry points into the same `choosePick`, shipping with only
`PickPrompt` corrected would leave the picker modal telling the user their choice won't
affect the board immediately before it does. Both files get the same corrected wording.

## 5. What's already correct and needs no change

- `DraftBoard.tsx`'s `chosen` vs. model-board rendering (§3 of
  `claude/your-pick-and-pacing.md`) needs zero changes. After a resim, the locked pick
  number's own board entry will independently show your exact player at 100%/modal
  (since every iteration replays it identically) — `userPicks`-driven `chosen` styling
  still takes precedence for the "✓ yours" badge, and the two data sources agree by
  construction rather than conflicting.
- `AvailabilityPanel`'s existing `pickedPlayerIds` filtering keeps working unchanged —
  it already reads off `userPicks`, and the resimulated `result.availability` is a
  fresh, internally-consistent set of curves that the same filter applies to correctly.
  **Correction from the review pass: the panel's default *view* is not unaffected**,
  even though its filtering logic is. `AvailabilityPanel`'s `picks = myPicks.slice(0,
  depth)` (default `depth=4`) takes the user's *first* few picks of the whole draft,
  unconditionally. Once the user is past pick 4 and has resimulated, every one of those
  first `depth` picks is now a locked, already-decided pick — its survival curve reads
  ~100% everywhere because every iteration replays it identically, which is not wrong,
  but it is dead information for a pick that already happened, and it silently hides
  the genuinely new future-pick numbers this whole feature exists to produce until the
  user manually drags the depth slider forward. Fix in `DraftView.tsx`, not inside
  `AvailabilityPanel.tsx` (which stays a dumb slicer, no internal changes needed): pass
  it only the user's *undecided* picks — `result.myPicks.filter((p) =>
  !(p in userPicks))` — instead of `result.myPicks` directly. `DraftBoard` and
  `RevealScrubber` keep receiving the full, unfiltered `result.myPicks` (they need
  every one of your slots marked/tick-marked on the board and scrubber respectively,
  decided or not) — only the `AvailabilityPanel` call site changes.
- `PlayerCard`'s "your pick" vs. "model's own projection" split needs no change —
  post-resim, the model's own projection for a locked cell is 100%/certain, which is
  simply true now, not a display bug.
- No backend endpoint, controller, or `DraftContext`/`DraftSimulator`/`MonteCarloRunner`
  change beyond §1's one-field, one-call-site addition. `resolveStartState`,
  `DraftContext.completedPicks`, and `DraftSimulator.run()`'s replay branch are
  exercised today only via `DraftSimulatorTest.completedPicksAreReplayedExactly` (a
  3-pick prefix with no interaction with `myPicks`) and are otherwise reachable in
  production only through the live-poller's resume path, where `completedPicks` is
  always *other* teams' historical picks. **Correction from the review pass: this is
  weaker coverage than the first draft of this doc claimed** — `SimulationServiceTest`
  does not exist in the repo at all, so `resolveStartState`'s silent-drop-on-unresolved-
  sleeperId behavior (the exact failure mode §3's defensive check exists to catch) is
  currently untested. §6 below adds the two tests this gap actually needs before this
  ships, rather than leaving the claim uncorrected.

## 6. Test coverage this doc adds (new, from the review pass)

1. **`SimulationServiceTest` (new file)** — a focused unit test (mock/stub repos,
   following whatever pattern `DraftSimulatorTest`/`MonteCarloRunnerTest` already use)
   asserting `resolveStartState` drops an unresolvable `sleeperId` from `startState`
   without throwing, rather than corrupting or erroring the whole request. This is the
   one method the frontend's silent-wrongness safety net (§3) depends on staying
   fail-soft; it needs to actually be pinned, not just read and trusted.
2. **Extend `DraftSimulatorTest`** with a case where `completedPicks` includes a pick
   number that is also in the caller's `myPicks` — the combination this feature makes
   reachable in production for the first time (previously `completedPicks` was always
   *other* teams' picks in the live-poller path; now it can be the viewing user's own).
   Assert `RunResult.availableAtMyPicks`'s snapshot for that pick number is well-formed
   (no exception, no corrupted/empty array where a real one is expected) — the snapshot
   is taken before the `completedPicks` short-circuit (`DraftSimulator.java`'s ordering:
   snapshot first, then check `already`), so this is about confirming that ordering
   stays harmless under the new combination, not about changing it.

## Acceptance criteria (for the verification pass)

1. Picking a player (either "take the model's pick" or a `PlayerPicker` row) at a
   paused pick shows a distinct "recalculating" state, then updates the board so that
   picks *after* the one you made reflect a genuinely different simulation — not the
   same numbers as before your pick, when the pick you made was a real reach relative
   to the model's own suggestion. (Concretely: pick someone the model gave under ~10%
   probability at that slot; confirm at least one downstream cell's modal player or
   probability changes versus what was displayed before the pick.)
2. The pick you made is displayed identically to today (name/position/team, "✓ yours"
   badge, model numbers dropped) — this doc doesn't change that presentation, only what
   feeds the picks after it.
3. Rapid-fire picking (accept the model's suggestion at every one of your pauses in a
   real run) works end to end without a stuck spinner, a stale board, or a JS error —
   each resim completes and hands control back to the reveal before the next pause is
   reachable (ticking is stopped throughout each resim, per §3/§4).
4. While a resim is in flight: the picker/prompt UI is replaced by a progress banner,
   not clickable; the reveal scrubber's slider and skip button are disabled; the "run"
   button is disabled. All four re-enable correctly once the resim resolves (success or
   error).
5. If a resim fails (network error, or the defensive mismatch check in §3 trips): an
   error is shown, the pick you made is still recorded and displayed as yours, the
   reveal resumes against the last-good board rather than hanging paused forever.
6. Re-running the simulation (the "run" button) while paused or mid-resim behaves
   safely — no leftover resim result can land after a fresh run has started. Verify by
   forcing the race: start a resim, immediately click run, confirm the final displayed
   board is the fresh run's, not a stale resim's — this must hold because of the
   `requestSeqRef` guard in §3, not merely because the "run" button happened to be
   disabled in time. Also verify the cross-draft case: start a resim, navigate to a
   different draft before it resolves, confirm the new draft's screen never shows the
   first draft's resimulated board (the `key={draftId}` fix in §3/`App.tsx`).
7. **Measured, post-build.** A real resim against the actual fantasy(heart) board (14
   teams, popsharky at slot 11, a genuine reach pick — Chris Godwin, not the model's
   suggestion — locked in at pick 11 of 210) at 2000 iterations took **18.5-18.8s**
   wall clock (`MonteCarloRunner`'s own log line), materially slower than the UI's "may
   take a few seconds" copy promises. A second measurement at 500 iterations took
   **~4.9s** — the cost scales roughly linearly with iteration count rather than being
   dominated by the fixed `ProfileService.fit()` cost every `simulate()` call pays (the
   efficiency concern raised in code review), which means lowering the cap actually
   buys a proportionally faster resim instead of hitting a fixed floor. **Verdict:
   `RESIM_ITERATION_CAP` lowered from 2000 to 500** to match the "few seconds" the UI
   promises — 500 is not a new guess, it's an option already offered in the app's own
   "runs" dropdown. The `ProfileService.fit()` re-fit-per-resim cost itself was not
   removed (that's a real, separate inefficiency the code review flagged and this doc
   deliberately left unaddressed tonight — see the review's efficiency finding — but it
   turned out not to be the dominant cost at these iteration counts, so the interim cap
   alone is sufficient for draft night).
8. `./gradlew test` still passes, including the two new tests from §6
   (`SimulationServiceTest`'s unresolved-`sleeperId` case and `DraftSimulatorTest`'s
   `completedPicks`-overlapping-`myPicks` case). Confirm no test constructs `PlayerRef`
   positionally with the old 5-arg shape (the record gained `sleeperId`).
9. The availability panel, once the user is a few picks in and has resimulated, shows
   upcoming (undecided) picks by default — not a wall of ~100% columns for picks
   already locked in (the `AvailabilityPanel`-prop fix in §5).
10. Both `PickPrompt.tsx` and `PlayerPicker.tsx` describe the new reactive behavior —
    neither still claims picking "doesn't change the rest of the board."
11. Everything from the previous two docs' acceptance criteria still holds (regression
    check): sticky headers, full-width layout, keyboard-openable cells, pick-by-pick
    pacing, the picker's position filters and dedup, player-card content, no horizontal
    overflow, board panel never collapses to 0 height.
12. No new database migration, no new table, no new persisted state — `userPicks`,
    `resimming`, and every other piece of state this doc adds is exactly as ephemeral
    as `userPicks` already was (gone on next `run()`), consistent with this project's
    existing convention for anything derived from a `result`.
