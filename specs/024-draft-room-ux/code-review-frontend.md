# Spec 024 — frontend bug-hunting code review

Reviewer pass, read-only, 2026-10-09. Scope: `git diff HEAD -- web/` plus the untracked web files in the
`024-draft-room-ux` worktree (HEAD d145f38).

**Executed:** `npx tsc -b` ran clean. `npx vitest run` ran: 104 files and 1408 tests, all passing.
**Not executed:** nothing here was driven in a browser. Each finding says whether it was **verified by reading
the code** or is **inferred**. None of them was reproduced with a new test, because this pass was not allowed
to write test files.

Severity scale:
- **BUG**: wrong behaviour on a reachable path.
- **RISK**: plausible failure, an edge case, or a regression in perf or accessibility.
- **NIT**: worth knowing, low cost either way.

---

## BUG

### B1. The projection room shows target survival and "Your team" for an assumed seat (FR-006 / FR-012)

**Where.** `web/src/pages/DraftView.tsx:456` sets `slotKnown = slotParam != null || seats != null`. That flag
goes to:
- `survivalFor(..., slotKnown)` at `:678`;
- `CompactRow needs={slotKnown && ...}` at `:587`.

The board itself is marked with `mySlotAssumed={slotParam == null}` at `:615`.

**Failure.** Open `/drafts/:id` for a draft where the backend finds no owner match (`seats.mySlot == null`), so no
`?slot=` is ever adopted. Then:
- the column header for slot 1 is dashed and says "assumed";
- the target chips show plain "54%" for slot 1's picks;
- the compact row says "Your team" with slot 1's roster.

That is one room making two contradictory claims about the same seat. FR-012 says "no number … otherwise", meaning
when the seat isn't known.

**Fix.** Use the same predicate as the board, `slotParam != null`, for both survival and needs.

**Status.** Verified by reading.

### B2. Target survival can be another seat's number right after a Claim or seat change (FR-012)

**Where.**
- `LiveDraftView.tsx:950`: `survivalFor(t.player, result?.availability, upcomingMyPicks[0], slotKnown)`
- `DraftView.tsx:678`: the same call with `undecidedMyPicks[0]`

**What goes wrong.** `upcomingMyPicks` and `availability` come from the last `result`, and that result was run
for `result.mySlot`. A Claim (`claimSeat` / `makeSeatMine`) changes `mySlot` and flips `slotKnown` to true
immediately, but `result` stays the old one:
- **Live room.** The new run is 1.5 s of debounce plus the run time away. Until it lands, the chips show slot 1's
  (DEFAULT_SLOT's) survival as if it were the claimed seat's.
- **Projection room.** Nothing re-runs on a claim, and `makeSeatMine` does not set `seatsDirty`. The wrong-seat
  numbers stay until the user re-runs.

**Fix.** Pass `slotKnown && result?.mySlot === mySlot` (or make `survivalFor` take the result's slot).

**Same flaw in older code.** The AvailabilityPanel survival columns and LiveStatusBar's `nextOwnPick` predate this
change and have the same flaw. The target strip is new surface for it.

**Status.** Verified by reading. `SimulationResult.mySlot` exists in `api.ts:73`.

### B3. A taken row in the player list still shows a survival strip and a verdict ("Act now", "Safe")

**Where.**
- `AvailabilityPanel.tsx:648`: `verdict(...)` and `:679-707` (the strip) do not check `r.taken`.
- `:313`: drafted extras added with "Hide drafted" off have `row: null`, so `survivalAt` returns 0.

**Failure.** Turn "Hide drafted" off in the live or projection room.
- A drafted player the room supplied separately renders 0% tiles and an **"Act now"** verdict.
- A drafted player who is still in a stale `availability` keeps his old number, for example "Coin flip" at 50%.

This is visible in the existing test fixture at `AvailabilityPanel.test.tsx` ("hides drafted players by default…").
There "Plain Pete" is taken and still has `survivalByPick` 0.5, so his row renders "Coin flip".

**Same in the Stats view.** `AvailabilityPanel.tsx:401-403` builds `survival` for every availability row, taken or
not. A taken row there still reads "· 50% there at your next pick" (`DraftStatsTable.tsx` `ds-sub`).

**Fix.** Render a blank (or "—") strip and verdict cell for `r.taken`, and skip survival for taken ids in
`statRows`.

**Status.** Verified by reading.

### B4. LiveStatusBar shows the pre-draft "waiting" detail during a running draft (FR-016 regression)

**Where.** `LiveStatusBar.tsx:121-131`. `waitingDetail` is now gated only on `onClockSeat == null` and
`status !== 'complete'`. In HEAD it was shown only when `waiting` (`live == null || status === 'pre_draft'`), at
`LiveDraftView.tsx@HEAD:726,918,936`.

**Failure.** The draft is drafting and the on-the-clock slot has no mapped seat. That is a partly mapped order, or
the A3 league with `draft_order: null`. The bar then reads:
- with some seats mapped: "Waiting for the draft order" plus "8 seats mapped · starts 7:00 PM", where the start
  time is now in the past;
- with none mapped: "Waiting for the draft order" plus "Waiting for the commissioner to set the draft order."
  That is two waiting statements.

It also shows briefly in a normal draft while `seats` hasn't loaded, because `seats=[]` makes `onClockSeat`
undefined.

**Fix.** Gate the detail on `live == null || live.status === 'pre_draft'`.

**Status.** Verified by reading.

---

## RISK

### R1. A new array and Set every second undo the Stats table's memoisation (spec 023 SC-006 property)

**What changed.** `LiveDraftView.tsx:965-976` passes inline props that are new on every render:
- `draftedPlayers={landedPicks.map(...)}`
- `targetIds` = `new Set(...)` at `:758`

The page re-renders on the 1 s freshness tick.

**What it costs.**
- `rows` (`AvailabilityPanel.tsx:334`) and `statRows` (`:424`) list `draftedPlayers` as a dependency, so both
  recompute every second: tiering, sort, the stats join and sort, plus an O(n·m) `universe.some` when Hide drafted
  is off.
- `DraftStatsTable` (memo) receives a new `rows` and `targetIds` every second and re-renders its shell.
- `StatRow`'s comparator still saves the rows.
- The comment at `:426-427` says this is exactly what the callbacks were kept stable to avoid.

**Fix.** `useMemo` both in the page.

**Status.** Verified by reading. Not measured.

### R2. Mock: Auto-pick or Auto-finish can race a manual pick, and the last response to arrive wins

**Where.** `MockDraftView.tsx:84-111, 213-233`.
- "Auto-pick" and "Auto-finish" are disabled only by `autoBusy`, not by `submitting`.
- `pick()` is not blocked by `autoBusy`.

**Failure.**
1. The user submits a pick in the picker.
2. A backdrop click closes the modal while the request is still in flight.
3. The user clicks Auto-finish.
4. Both requests run. If the FINISH response arrives first, the older `/pick` response then calls `setState`. The
   room shows an IN_PROGRESS board while the server says COMPLETE.

The opposite order gives a 409 banner. Neither request applies a sequence check.

**Fix.** Disable every pick and auto control on `submitting || autoBusy`, or drop out-of-order responses with a
request counter.

**Status.** Inferred: the ordering is not reproduced.

### R3. Below 1280 px the hidden board pane stays in the tab order

**Where.** `styles.css:5704`. `.room-pane-hidden` is 0×0 with `overflow:hidden` and `pointer-events:none`, but
that does not make it inert.

**Failure.** On the Players tab, Tab walks through every board header and cell button inside an invisible pane.
That is 14 headers plus up to about 200 cell buttons, and focus disappears.

**Fix.** Add `inert` (or `aria-hidden` plus `tabIndex=-1`) to the board's own content, but not to the pick card.
The pick card has to stay usable, and the `pointer-events` fix at `:5709` shows why.

**Status.** Verified by reading.

### R4. Caveats became hover-only on touch devices

**Where.** `AvailabilityPanel.tsx:52-58`. `InfoMark` replaces visible caveat text (no-availability reason,
Stats-unavailable reason) with an ⓘ that carries `title` and `aria-label`.

**The problem.** `title` never shows on touch, and the mark has no click or tap handler. On a phone, "Availability
appears once your seat is known" and "Mock drafts don't run a simulation" can no longer be read. AGENTS.md says
caveats must not be quietly dropped. The text is in the DOM for screen readers, but sighted touch users lose it.

**Status.** Verified by reading.

### R5. The target strip's width observer is lost after a load failure and retry

**Where.** `TargetStrip.tsx:46-53`. The ResizeObserver effect runs once (`[]`) against the first `.target-strip-wrap`.

**What goes wrong.**
1. `status` goes `loading` → `loadFailed`. The early-return branch unmounts that div.
2. Retry → `ready` mounts a new div that is never observed.
3. `width` stays at 0 or its stale value. When it is 0, `fit = items.length`, so every chip renders: no "+N", and
   the row overflows. That breaks FR-011a's "never wraps, shows +N".

**Fix.** Use a callback ref, or depend on whether the main branch is mounted.

**Status.** Verified by reading.

### R6. Moving between the live and projection rooms can show a list that is missing the last edit

**Where.** `useTargets.ts`. Each room mounts its own hook.

**Failure.** Unmounting with a PUT in flight lets that PUT finish, which is fine. But the new room's first GET can
reach the server before that PUT commits, and it then shows the pre-edit list. That list persists until a focus
refetch. Nothing is lost on the server.

**Status.** Inferred.

### R7. A non-snake draft type contradicts the board

**Where.** `FormatSummary` prints `seats.draftType` verbatim (`DraftView.tsx:462`, `LiveDraftView.tsx:866`).

**The contradiction.** `DraftBoard` always draws snake order and snake arrows (`snake.ts`). A Sleeper `linear` (or
`auction`) draft would therefore read "linear" next to alternating ←/→ arrows. That board behaviour is older than
this change, but the new summary is what makes the contradiction visible.

**Also.** The live and projection summaries omit the reversal round. Only the mock passes `reversalRound`.

**Status.** Inferred: I did not check whether ingest admits linear drafts.

### R8. A signed-out or admin-token caller sees star buttons that can never save

**Where.** `useTargets` relies on the contract's behaviour for a caller with no identity: GET returns an empty
list, so the hook goes `ready`, while PUT returns 401.

**Failure.** Every star then produces a persistent "Couldn't save targets — retry".

**Fix.** Treat a 401 on PUT like `unavailable` (with copy), or hide the stars when there is no `currentUserId()`.

**Status.** Inferred: it depends on whether the sign-in gate can be bypassed on these routes.

---

## NIT

- **N1.** `useTargets.ts:121-129`: the scope-reset is a passive `useEffect`. The first commit after a draft-id
  change renders the previous draft's items with `status: 'ready'`. A click handled before the effect flushes
  would PUT the old draft's ids plus one to the new scope, because `add` reads `itemsRef` and `scopeRef` is
  already new. React 18 flushes passive effects synchronously after a discrete-event commit, so this is close to
  unreachable. It is still cheap to close with `useLayoutEffect`, or by keying the state by `key`. No test covers
  a scope change.
- **N2.** `TargetStrip`: removing the last target while the popover is open leaves an empty dialog.
  - `open` stays true, but the Edit button (`openerRef`) is gone.
  - Escape and Done then focus `null`.
- **N3.** `LiveStatusBar`: the seat count is stated twice on one bar: "8 seats mapped" (detail) and
  "Only 8 of 12 managers identified" (`liveSeatNote`). `startsAt` is time-only, so a draft days away reads
  "starts 7:00 PM". The "seats and the board below are still real" clause was dropped from the offline caveat.
- **N4.** `formatTimer(7170)` prints "1 h 60 min", because of `Math.round` on the minutes. Real Sleeper timers are
  round numbers.
- **N5.** `SplitDivider`:
  - `onPointerDown` starts a drag on any button, right-click included.
  - Before measurement the room renders `density='full'` and then flips to compact: a one-frame flash.
- **N6.** `ScarcityMeter`: if every position has `poolSize 0`, an empty `<ul>` renders above the note.
- **N7.** `survivalFor` vs `survivalAt`. The backend's `survivalByPick` is sparse (`MonteCarloRunner.java:200-217`,
  top-N snapshots only):
  - `survivalFor` reads a missing entry as "unknown" and shows no number;
  - the list's `survivalAt` reads the same entry as 0 and shows "Act now".

  The strip's reading is the honest one (absent can mean "gone" or "outside the top-N snapshot"). But the strip and
  the list can now disagree about the same player. That disagreement is older than this change, on the list side.
- **N8.** `LiveDraftView`/`DraftView` render `TargetStrip` with `sport = seats?.sport ?? 'nfl'`. If targets load
  before seats on an NBA draft, the chips briefly use football faces and abbreviation rules.

## Checked and found sound

**useTargets**
- One PUT in flight at a time, coalescing to the latest list.
- A response is never applied over newer local state.
- Epoch-guarded late responses on a scope change.
- The focus refetch is skipped while dirty, in flight or errored.
- `editSeq` drops a GET that overlaps an edit.
- A 404 on save sets `unavailable`.
- `loadFailed` holds editing back: stars only appear on `status === 'ready'`, in all three pages.

**Mock**
- `userPicks` counts `USER | AUTO`. It is the only site, by grep.
- The auto controls are hidden on a 404, and "Pick from full list" is still offered on your turn.
- The `auto` tag is in the feed (FR-022).

**DraftBoard**
- `round.pick` and the arrows use `isForward`/`pickNoAt` with the required `reversalRound`.
- Claim only appears when there is no Seat and `onClaim` is passed. The mock doesn't pass it.
- The column mark comes from `mySlot` only, with an "assumed" variant.
- On the clock:
  - live: only while drafting;
  - mock: not when complete;
  - projection: only at `pausedAt`.

**LiveDraftView**
- The board is memoised per density, and the 1 s tick returns the same element, so the cells bail out.
- Survival, needs, chime and the team strip are all gated on `slotKnown`, apart from B2's staleness.

**DraftRoomLayout and SplitDivider**
- The ResizeObservers are disconnected on cleanup.
- The stored fraction is validated (finite, between 0 and 1) and never clamped on write.
- NaN is guarded: `rowGap || 0`, and `totalHeight <= 0` is a no-op.
- The keyboard handlers call `preventDefault` only for handled keys.

**api.ts**
- The new fields are optional and nullable.
- `getTargets` and `autoMock` 404s degrade, as `unavailable` and hidden controls respectively.
