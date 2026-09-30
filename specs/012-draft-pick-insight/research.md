# Research: Live draft pick insight

Phase 0 for [plan.md](plan.md). Every decision below was checked against the code
on `main` @ `de1e1dd`, not taken from a doc. Where a figure has not been measured
it says so.

---

## R1. Where the projection comes from — the live room does not re-project per pick

**Finding.** `LiveDraftView.tsx` runs **one** baseline projection per seat
(`useEffect` on `[seats, mySlot]`), plus the manual "Project again" button.
Commit `41b9426` (2026-09-11, "Live draft: instant real picks, resim only for
mock") removed the per-pick resim. The reason was **latency on the critical
path**: the board and the taken-player set waited on a 1.5s debounce plus a
500-iteration run after every pick. That was fixed by overlaying
`live.recentPicks` onto the board, feed and taken set, so facts no longer wait on
any simulation.

The spec's Stories 2–4 need a projection that knows about the pick that just
landed. That projection does not exist today. The existing `AvailabilityPanel`
already shows survival odds from the baseline run, which can be dozens of picks
old: a staleness bug that predates this feature and has no label saying so.

**Decision.** Bring back a background re-projection after each landed pick,
**off the critical path**. Nothing factual waits on it: the overlay built in
`41b9426` stays exactly as it is. The existing `resimulate()` machinery already
provides trailing debounce (`RESIM_DEBOUNCE_MS` 1500), coalescing
(`pendingRef`), sequence guarding (`requestSeqRef`) and abort on unmount; only
the trigger changes, from `[seats, mySlot]` to "a new landed pick, or
`[seats, mySlot]`".

**Rationale.** Real drafts pick every 30–120s and autodraft bursts coalesce into
one run, so one run per pick is well inside the gap. The original objection
(facts waiting on it) no longer applies. This also fixes the stale availability
panel for free.

**Cost: UNMEASURED.** The ~5s figure is from the mock room. The live-stack cost is
still recorded as unmeasured in `LiveDraftView.tsx`'s own header. Each open tab
runs its own projection, so a 12-manager league with every manager watching is
12 runs per pick on Railway. The quickstart measures both (Q4). If one run costs
more than the typical gap between picks, the fallback is R1-alt below, not
quietly lowering `RESIM_ITERATIONS`.

**The server already rations this, and it was built for exactly this load.**
`SimulationPermits` (audit 2026-09-28/05) allows `max(2, cores/4)` runs at once.
A caller waits up to 3s (`draftsim.sims.wait-ms`) and is otherwise refused with
HTTP 429. Its javadoc names the case: "about a dozen managers each have the live
room open, and every pick makes every tab resimulate at nearly the same moment".
Its sizes are all marked as guesses. With 2 permits and ~5s runs, a 12-tab room
**will** see 429s on most tabs at every pick. The client must therefore treat a
429 on the post-pick run as *"projection busy"*: the projection-dependent
sections say so (FR-013) and the next landed pick tries again. It must not retry
in a loop, since that only adds load to the queue it lost. The per-caller lease
(one per identity per draft; a new request cancels the old) already stops one
tab from competing with itself.

**Alternatives considered.**
- *One shared projection per draft per pick, computed once server-side and
  pushed on the SSE stream.* `SeatSpec` says a USER seat "is modelled exactly
  like a MANAGER seat for scoring", so the **board** (per-cell marginals: Stories
  2 and 3) does not depend on who is watching. Only the availability snapshots
  (Story 4's projected column) depend on `mySlot`. This is the real fix for the
  12-tab load. It is **R1-alt**: build it if Q4's measurement shows 429s make
  "likely next" unreliable in practice. It is not in this plan's first cut
  because it moves simulation into the poller's lifecycle, which is new
  server-side work with its own failure modes.
- *Keep the baseline and label its age* ("as of pick 14"). Rejected for "likely
  next". Forty picks later the baseline's candidate for a manager's next cell
  can be a player who is already gone, and filtering taken players out of a
  marginal and renormalising is not the conditional distribution. It would be a
  number that looks right and isn't (lessons.md class 5).
- *A new "just this manager's next pick" endpoint that stops the simulation
  early.* It would be cheaper per call, but it needs engine changes
  (`DraftSimulator.run` has no early stop). It would also be a second projection
  path, while the full run also feeds Story 4's survival odds and the board.
  Rejected in favour of the shared projection above as the fallback.

## R2. Knowing which side of a pick a projection is on (FR-007, SC-003)

**Finding.** A `SimulationResult` does not say which picks it replayed. Without a
`startState`, `SimulationService.resolveStartState` replays whatever
`draft_pick` holds **when the backend reads it**. The poller can write more
picks between the client's request and that read, so the client's `picksMade`
at request time is only a lower bound.

**Decision.** When the client has the **complete** landed list (R3), it sends it
as an explicit `startState` (`pickNo → sleeperId`). An explicit `startState`
wins in `resolveStartState`, so the client knows exactly which picks the result
is conditioned on, and stamps the result client-side with `asOfPick` = the
highest pick number sent. When the landed list is incomplete, the request falls
back to today's DB replay and the result is stamped `asOfPick: null`. Sections
that depend on the projection then treat it as unusable for pick-relative claims
(FR-013).

**Rationale.** No backend change, and no widening of `SimulationResult`, a record
the football-parity baseline hashes the raw shape of (`api.ts` comment at
`SimulationResult`). The explicit prefix equals what the DB replay would give
when complete, so the board is unchanged.

**Known gap, carried over, not introduced.** A landed pick whose sleeperId
`idsBySleeperId` cannot resolve is silently dropped from `startState`
(`SimulationService.java:123–126`), the same as the DB path drops a null
`playerId`. The simulator would then treat that pick as open. This behaves the
same today and is not made worse. The data-model rule (DM-4) excludes such
results from "asOf" stamping anyway: if any landed pick lacks a sleeperId, stamp
null.

**Alternatives considered.** Adding `replayedThroughPick` to `SimulationResult`
server-side is more robust, but it widens the hashed record and needs the Java
record plus the `api.ts` mirror in the same change. Keep it in reserve if R2's
client stamping proves wrong in verification.

## R3. The complete landed list — the card cannot wait for a projection

**Finding.** `feedPicks` gates the fit clause on
`landedPicks.length === picksMade`. Before the first projection, `landedPicks`
holds only `live.recentPicks` (last 12), so past pick 12 there is no fit until a
projection returns. That fails SC-002 (fit within 1s) whenever the page opens
mid-draft.

**Decision.** Fetch `getRealDraftBoard(draftId)` (`GET /drafts/{id}/board`,
already exists, already scoped) once on mount. Merge `recentPicks` over it, facts
winning, as today. Refetch when a `state` frame shows a gap: the oldest
`recentPicks.pickNo` is more than the last known pick number + 1, which happens
after a reconnect or a burst of more than 12 picks. `landedPicks` stops
depending on `result.board`.

**Rationale.** The fit, the scarcity "left now" and the on-brand meter are all
claims about facts. They should need facts only.

## R4. "Likely next" — which number to show

**Finding.** `PredictedPick.player` is the **globally assigned** player
(`BoardAssembler`: first candidate not already assigned earlier), and
`isModal: false` marks a cell where that isn't the most-voted player. Each cell
carries its chosen player plus the 3 most-voted *others* (`ALTERNATIVES = 3`),
each with its marginal share.

**Decision.** For "what will manager X take at pick n", merge
`{player, probability}` with `alternatives`, sort by share, and show the top one
plus up to two runners-up. This is the **per-cell marginal**, which is the right
statistic for this question. Do not show the board's assigned player when
`isModal` is false: that player is an artifact of making one coherent board.

**Rationale.** lessons.md class 5 in reverse: the board's coherent assignment is
the right thing for a board and the wrong thing for "what does X do next".

## R5. "The model had this at N%" (FR-009)

**Decision.** Take the newest projection with `asOfPick < pick.pickNo` and look
up the cell at `pick.pickNo`. If the picked player is among the cell's four
candidates, show their share. If not, say "under X%", where X is the smallest of
the four shares shown, because the true share is unknown below that. If
`asOfPick < pick.pickNo − 1`, add "as of pick {asOfPick}", since the picks in
between were not known to it. The "surprise" label (< 5%) applies only when the
share is known or bounded below 5%. The threshold is hand-set.

**Storage.** A ring of the last 2 stamped projections is enough: the one before
the pick and the one after. Keeping every projection is not needed.

## R6. Starter-quality cutoff for the scarcity meter

**Spec assumption (amended, see spec).** The spec assumed a per-position cutoff
from dedicated slots plus a share of shared slots. That forces an arbitrary rule
for dividing FLEX / G / F / UTIL among positions. Basketball has three shared
slot types, so the rule would carry most of the weight.

**Decision.** **Starter pool = the board's top S players, S = teams × starting
slots per team.** Starting slots per team is the non-bench length of
`rosterPositions`, i.e. `computeTeamNeeds(...).length`, the same count as the
"n of 10 starters" line. For each position, the meter shows how many of that
position's starter-pool players are still undrafted. Shared slots are then
allocated by the market (board order) rather than by a rule we made up. The
meter states the definition in one line: "Starter pool: the board's top 120
(12 teams × 10 starters)".

**Positions shown.** `RUNNABLE_BY_SPORT` from `pickRun.ts`: football QB/RB/WR/TE
(K/DEF excluded for the same reason the run detector excludes them), basketball
all five. Export the constant rather than copying it, so the meter and the run
flag cannot disagree.

**Source of the board.** The client has no endpoint that returns the board with
player ids: `GET /api/board` is a debug endpoint without ids and without draft
scoping. **One new endpoint**: `GET /api/drafts/{sleeperDraftId}/pool?limit=N`
returns the first N of `boards.currentBoard(sport)` as `PlayerRef[]`, the same
list `SimulationService` simulates against (`SimulationService.java:72`). It is
scoped through `membership.visibleDraft`, like `/seats`. See
[contracts/pool-endpoint.md](contracts/pool-endpoint.md).

**Alternatives considered.** A cutoff by Sleeper's `positionalRank`: rejected,
because it is the source snapshot's rank, not the blended board order the engine
uses, it is not guaranteed contiguous, and it uses 999 as a sentinel. Deriving
the pool from `availability` rows: rejected, because they are truncated (R7).

## R7. "Expected left at your next pick" — only when it can be exact

**Finding.** `AvailabilityRow`s record survival only for players in the **top 75
available** at each of your picks (`MonteCarloRunner.SNAPSHOT_DEPTH = 75`,
`DraftSimulator.topAvailable`, board order, skipping positions the rules say are
not draftable yet). A starter-pool player ranked below that on a given run has no
row, even though he survived.

**Decision.** Expected left at P at your next pick
= Σ over undrafted starter-pool players at P of `survivalByPick[nextPick]`.
Show it **only when the number of undrafted starter-pool players (all positions)
is ≤ 75**. Then every surviving starter-pool player falls inside the snapshot and
the sum is exact within the model. Before that point, show "left now" only, with
"projected count from pick ~N". In a 12×10 league that means from about pick 46;
before then, the whole meter is roughly "most are left".

**The constant is duplicated.** The frontend needs 75, and it lives in
`MonteCarloRunner`. This is the "two implementations of one rule" landmine
(memory: multi-sport landmines). Mitigation: a named constant on the frontend
with a comment pointing at `MonteCarloRunner.SNAPSHOT_DEPTH`, plus a backend unit
test asserting the value, so a change on one side fails a test naming the other.
Exposing it on the wire would widen `SimulationResult` (R2).

## R8. On-brand meter — what "on brand" compares

**Finding.** `ManagerProfile.reachBias` = mean(boardPosition − pickNumber),
**positive = reaches** (the engine's convention, which the javadoc notes is the
inverse of the design doc's). `positionalTilt` is a multiplicative nudge
centred on 1.0, **only ever fitted**: a STATED seat has none. `Seat` carries
both plus `provenance`, `draftsObserved` and `picksScored`, and is already in
the live room (`seats.seats`).

**Decision.**
- **Reach so far** = mean(player.adp − pickNo) over that manager's landed picks
  with an ADP. This uses the same sign as `reachBias`, so "+8" means the same
  thing on both sides. Compared with `reachBias`, the reach read is **on brand**
  when both have the same sign or both are within ±3 picks. The 3 is hand-set.
  The comparison uses current-board ADP, while `reachBias` was fitted against
  the board at the time of each past draft (`adp_at_time`), so the two are
  close but not identical. The card says "vs board ADP".
- **Lean** = the manager's top tilt position if its tilt is ≥ 1.10 (hand-set).
  The lean read is **on brand** when that position's share of their picks is
  above the room's share of picks at that position so far.
- **No verdict**: NEUTRAL provenance; fewer than 3 picks (FR-020); lean for a
  STATED seat (tilt is never stated), which still gets a reach read against the
  stated `reachBias`, labelled "vs what you entered".
- Every hand-set constant lives in one exported object with a comment
  labelling it arbitrary (AGENTS.md: hand-set is labelled, not presented as
  principled).

**Rationale.** It stays inside the numbers the engine already uses, in the
engine's units, and says nothing where there is nothing to compare.

## R9. Card placement, lifetime, and "your turn"

**Decisions.**
- The card overlays the **board stage** only, anchored top. It never covers
  `LiveStatusBar`, `PickFeed` or the team strip, which sit above the stage. On a
  phone it spans the stage width, at the top, so it does not collide with the
  `AvailabilityPanel` sheet.
- It closes itself after **8s** (hand-set). Hover or focus pauses the timer. Esc
  or × dismisses it. A newer pick replaces it (no queue).
- **On the clock** (`slotKnown && live.onTheClockSlot === mySlot`): an open card
  closes and no new card auto-opens. The newest feed row still updates, and
  clicking it opens the card on demand. This satisfies FR-011 without layout
  arithmetic.
- **Reopen from the feed**: clicking any feed row opens that pick's card. The
  figures that depend on a projection are **frozen per pick** when first
  computed (R5's ring is too short to recompute old picks honestly), so an old
  card shows what was known then rather than recomputing against a later
  projection.
- **Hidden tab / mid-draft join**: the card opens only for a pick that arrives
  while `document.visibilityState === 'visible'` and after the initial landed
  list has loaded. Anything else is history.
- **Preference**: `localStorage` key `bk.pickCards.v1`, read and written through
  try/catch helpers in the pattern of `sound.ts`. Default on. It hides cards
  only, not the meters (FR-012).

## R10. Testing approach and what only a live draft can prove

- **vitest (unit)**: the pure derivations (`pickInsight.ts`: fit, open slots,
  summary sentence, ADP delta, model-share lookup incl. the "under X%" bound,
  asOf selection; `scarcity.ts`; `onBrand.ts`). Include preference-**ordering**
  tests, not only shape tests (lessons.md class 1): e.g. a reach of +14 must
  read as "before ADP", never after; a manager whose RB share rises above the
  room's reads as on brand for an RB lean, never off.
- **vitest (component)**: card lifetime (replace, not queue; auto-dismiss;
  closes on your turn; not raised for history), the preference toggle, and
  375px layout via class assertions only (jsdom has no layout, so real width is
  checked live).
- **Backend**: `LeagueControllerIT` case for `/pool` (scoping 404, order equals
  `currentBoard`, limit clamp, nullable team). ITs **skip silently** when
  Postgres is down (memory: backend suite skips ITs), so check the skip count.
  Add a unit test pinning `SNAPSHOT_DEPTH` (R7).
- **Live**: a real disposable Sleeper draft, the recipe used on 2026-09-02
  (HANDOFF "Live-draft dry run"). Only that exercises SSE bursts, the
  per-pick resim cost, and a real pick arriving. See [quickstart.md](quickstart.md).
