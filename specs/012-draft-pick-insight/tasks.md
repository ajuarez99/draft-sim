---
description: "Task list for 012-draft-pick-insight"
---

# Tasks: Live draft pick insight

**Input**: `specs/012-draft-pick-insight/`: spec.md (read its "Amendments after
planning"), plan.md, research.md (R1–R10), data-model.md (DM-1…DM-6),
contracts/pool-endpoint.md, contracts/ui-live-room.md, quickstart.md (Q1–Q7).

**Scope guard**: the **live draft room only** (`web/src/pages/LiveDraftView.tsx`).
The mock room (`MockDraftView.tsx`) and the pre-draft simulator (`DraftView.tsx`)
must render exactly as before. `PickFeed` is shared by all three rooms, so any
change to it is additive and optional-prop only.

**Tests are requested.** Plan R10 and AGENTS.md's recurring bug class 1 (sign
inversion) require **ordering** tests, not only shape tests. Where a task says
"tests first", write the test, see it fail, then implement.

**Amended after T000** (see `plan-review.md`): T008, T020 and T021 carry the F1–F3 corrections inline.

**Line numbers are from `main` @ `de1e1dd`.** Re-find each anchor by the quoted
code, not by line.

Path prefixes:
- `BE` = `backend/src/main/java/com/ballknowers/draftsim`
- `BT` = `backend/src/test/java/com/ballknowers/draftsim`
- `web` = `web/src`

**Hard rules that apply to every task** (AGENTS.md):
- Nothing factual waits on a simulation (the `41b9426` invariant).
- No `Map.of(...)` in a JSON path that can carry a null.
- `web/src/api.ts` changes land in the same commit as the Java they mirror.
- Never retune `RESIM_ITERATIONS`, `SimulationPermits` or any hand-set constant
  to make a measurement come out right. Report the number.
- Coding subagents run on Sonnet (`model: "sonnet"`). Review and verification
  stages do not.
- Ask before committing. The tree is shared with concurrent sessions.

---

## Phase 0: Adversarial plan review (the repo's pipeline stage, before any code)

- [X] T000 Read plan.md, research.md, data-model.md and both contracts **cold**. Hunt for gaps, and **check each claim against the code**:
  - R1 (live room projects once per seat): `LiveDraftView.tsx`, the `useEffect` on `[seats, mySlot]`.
  - R2 (an explicit `startState` wins): `BE/engine/SimulationService.java` `resolveStartState`.
  - R4 (a cell carries the chosen player plus 3 others): `BE/engine/BoardAssembler.java`, `MonteCarloRunner.ALTERNATIVES`.
  - R7 (the snapshot is 75 deep, in board order): `MonteCarloRunner.SNAPSHOT_DEPTH`, `DraftSimulator.topAvailable`.
  - The permits behaviour: `BE/api/SimulationPermits.java`.
  - Whether `getRealDraftBoard` returns every landed pick for a *drafting* draft, not only a completed one: `BE/api/LeagueController.java` `@GetMapping("/drafts/{sleeperDraftId}/board")`.

  Write the findings to `specs/012-draft-pick-insight/plan-review.md`. Apply any correction to the plan documents as a visible "amended after review" note, never a silent rewrite. **Do not start Phase 1 until this is done.**

---

## Phase 1: Setup

- [X] T001 Confirm the environment per quickstart Prerequisites. Check that Postgres answers on `localhost:5433`, whether anything stale is listening on 8080 or 5173, and that the worktree's own servers will run, since `preview_start` from a worktree runs the main checkout's `launch.json`. Record the baseline counts from `cd backend && ./gradlew test` (passed / failed / **skipped**) and `cd web && npx vitest run` in `specs/012-draft-pick-insight/verification.md` under "Baseline". `RefreshControllerIT` is known-failing on the base branch; note it and move on.

---

## Phase 2: Foundational (blocks every story)

- [X] T002 [P] Export `RUNNABLE_BY_SPORT` from `web/pickRun.ts` (it is a module-private `const` today). There is no behaviour change and `pickRun.test.ts` stays green.
- [X] T003 [P] Create `web/insightConstants.ts` with one exported, frozen object. Its header comment must say these are **hand-set, not fitted**, and that changing them to match a measurement is forbidden. Contents: `WIDE_OPEN_SHARE: 0.25`, `SURPRISE_SHARE: 0.05`, `MIN_PICKS_FOR_VERDICT: 3`, `REACH_TOLERANCE: 3`, `LEAN_TILT: 1.10`, `SCARCE_LEFT: 3`, `CARD_DISMISS_MS: 8000`, `POOL_LIMIT_MAX: 400`. Add a separate exported `SNAPSHOT_DEPTH_MIRROR = 75` whose comment says it mirrors `MonteCarloRunner.SNAPSHOT_DEPTH` and is pinned by `BT/engine/MonteCarloRunnerSnapshotDepthTest.java`, and so is **not** hand-set.
- [X] T004 [P] Create `BT/engine/MonteCarloRunnerSnapshotDepthTest.java`. It asserts the snapshot depth is 75 and fails with a message naming `web/src/insightConstants.ts` `SNAPSHOT_DEPTH_MIRROR`. If the constant is `private static final`, widen it to package-private in `BE/engine/MonteCarloRunner.java`. Change nothing else.
- [X] T005 [P] Tests first: `web/pickCardsPref.test.ts`, then `web/pickCardsPref.ts`, with `readPickCardsPref(): boolean` and `writePickCardsPref(on: boolean): void` on key `bk.pickCards.v1`. Default is **on** when the key is absent. Every `localStorage` access is inside try/catch; a throwing accessor reads as the default and a write is a no-op. Follow `web/sound.ts`'s `readSoundPref`/`writeSoundPref` pattern.
- [X] T006 Tests first: in `web/pickInsight.test.ts`, then `web/pickInsight.ts`, add `fillsFor(sport, rosterPositions, priorRoster, player): string | null`. It wraps `fitSlot(sport, player.position, computeTeamNeeds(sport, rosterPositions, priorRoster))`, where `null` means "Depth". It also adds `openAfter(sport, rosterPositions, priorRoster, player): string[]`, the slots with `player == null` after adding the player, in roster order. Test cases:
  - An RB into RB1-filled / RB2-open fills `RB2`.
  - A sixth WR fills `null`.
  - Basketball: a PG with PG filled and G open fills `G`, not `UTIL`.
  - A final pick leaves `openAfter` as `[]`.
- [X] T007 Repoint `feedPicks` in `web/pages/LiveDraftView.tsx` (the `const slot = fitSlot(sport, newest.player.position, computeTeamNeeds(...))` line) at `fillsFor` from T006, so the feed and the card share one function (contract ui-live-room "Feed"). The output is identical and existing tests stay green.
- [X] T008 Make the landed list come from facts (R3) in `web/pages/LiveDraftView.tsx`:
  - Add state `realPicks: RealPick[] | null`, filled by `getRealDraftBoard(draftId)` on mount.
  - Rebuild `landedPicks` as `realPicks` merged under `live.recentPicks` (recentPicks win), **no longer reading `result.board`**.
  - Refetch `getRealDraftBoard` when a `state` frame's oldest `recentPicks[0].pickNo` exceeds the highest known pick number + 1 (a gap), and on reconnect.
  - *(Amended after review, F2/F3.)* Add a helper `missingPickNos(landed, picksMade): number[]` and `seatComplete(slot, missing, teams, reversalRound)`. It is true when no missing pick belongs to that slot, computed via `snake.ts` `pickNoAt`. Replace `feedPicks`' global `complete` gate with `seatComplete` for the newest pick's seat. If `getRealDraftBoard` fails (an old backend, 404, or network error), fall back to today's source: the `result.board` prefix merged under `recentPicks`.
  - Keep `takenPlayerIds` and `boardWithLive` behaviour, sourcing taken ids from `landedPicks`.
  - Add a component test, `web/pages/LiveDraftView.landed.test.tsx`, with `getRealDraftBoard`, `getSeats`, `useLiveDraft` and `streamSimulationQuietly` mocked. It asserts the feed's newest row shows its fit clause at pick 30 **before any simulation resolves**.

**Checkpoint**: the feed behaves as before, but its fit now appears mid-draft without waiting on a projection. The mock room and the pre-draft simulator are untouched (`git diff --stat` shows no change to `MockDraftView.tsx` or `DraftView.tsx`).

---

## Phase 3: User Story 1: how a pick fits the team that made it (P1) 🎯 MVP

**Goal**: a passive card for every landed pick, showing the player, manager, pick, fit, open slots and a summary.
**Independent test**: quickstart Q5's first two bullets. The card appears within about 1s of the feed row, and "Fills" and "Still needs" match the seat's board column.

- [X] T009 [US1] Tests first: in `web/pickInsight.test.ts` and `web/pickInsight.ts`, add `summarize(sport, fills, openAfter, rosterSoFar): string`. **DM-5**: "built only from `fills`, `openAfter`, and positions this seat has no player at yet. Every clause maps to a field above; no free text." Cases:
  - `fills: 'RB2'`, `openAfter: ['TE','FLEX']`, no QB yet → mentions RB2, TE, FLEX and QB.
  - `fills: null` → says depth, not a slot.
  - `openAfter: []` → "roster complete" and no "still needs".
  - Basketball positions produce basketball wording.
- [X] T010 [US1] In `web/pickInsight.ts`, add the `PickInsight` type (data-model.md) and `buildFactInsight(pick, landed, seats, sport, rosterPositions): PickInsight`. It fills `pick`, `seat`, `fills`, `openAfter`, `rosterComplete` and `summary` (via T006/T009), and sets `provenanceLabel` to exactly `"fitted from N drafts"` / `"stated by you"` / `"no history — neutral seat"` from `seat.provenance` and `seat.draftsObserved`. `BLENDED` counts as fitted. The projection fields start as `modelShare: null` and `likelyNext: { state: 'none', reason: 'no projection yet' }`. Add a unit test for each provenance label.
- [X] T011 [P] [US1] Tests first: `web/components/PickInsightCard.test.tsx`, then `web/components/PickInsightCard.tsx`, per contracts/ui-live-room.md "Content" rows 1–3. The card shows the avatar (reuse the existing avatar component the feed/board uses), manager, `roundPickLabel`, player name, a position chip in the position colour (the inline-style approach `PickFeed`'s newest row uses), team, "Fills X"/"Depth", "Still needs: …" or "Roster complete", and the summary. Use `role="dialog"`, `aria-modal="false"`, `aria-live="polite"` and a × button. Esc calls `onClose`. The card takes an `autoFocus` prop: false on auto-open, true when opened from a feed click. Leave rows 4–7 out entirely when their data is absent (FR-013); do not render empty containers.
- [X] T012 [US1] Card lifecycle in `web/pages/LiveDraftView.tsx`, per the data-model.md state machine:
  - Open a card for pick *p* only when it **arrives** on a `state` frame after the initial landed list has loaded, `document.visibilityState === 'visible'`, `readPickCardsPref()` is true, and you are not on the clock (`slotKnown && live.onTheClockSlot === mySlot`).
  - A newer pick replaces the open card (no queue).
  - It auto-closes after `CARD_DISMISS_MS`; hover or focus inside pauses the timer.
  - It closes when you go on the clock.
  - Picks already landed on mount, or delivered while hidden, never auto-open (history).
  - Render the card overlaying the top of `.board-stage`, never above it (it must not cover `LiveStatusBar`, `PickFeed`, the team strip, or the scarcity row added later).
- [X] T013 [US1] Make feed rows clickable in `web/components/PickFeed.tsx`: add **optional** `onPickClick?: (pickNo: number) => void`. When absent, rows render and behave exactly as today (the mock room and simulator pass nothing). When present, rows are buttons with an accessible name. In `LiveDraftView.tsx`, pass a handler that opens that pick's card with `autoFocus`, using the frozen insight if one exists (DM-6, see T024) and otherwise building a fact insight. Extend `web/components/PickFeed.test.tsx` with one case showing it is inert without the prop and one showing a click fires with it.
- [X] T014 [US1] Preference chip in `.live-panel-head` of `web/pages/LiveDraftView.tsx`, beside the "Announce picks" chip: `Pick cards on` / `Pick cards off`, `aria-pressed`, persisted via T005. Turning it off closes any open card. It does **not** affect the meters (FR-012).
- [X] T015 [US1] Component tests in `web/pages/LiveDraftView.card.test.tsx`, with the same mocks as T008 and fake timers:
  - Three picks within 10s leave exactly one card open, showing the newest (SC-004).
  - Auto-dismiss at 8s, paused while hovered.
  - Going on the clock closes it and suppresses new ones.
  - Picks present on mount raise no card.
  - With the preference off, no card appears.
  - A feed-row click opens a card for an older pick.
- [X] T016 [US1] Styles in the existing stylesheet the live room uses (find where `.live-team` / `.live-panel-head` are defined): dark rounded card, circular avatar, tinted position pill, hover depth. Avoid flat uniform cards (memory: feedback-avoid-flat-uniform-cards). At ≤ 480px it spans the stage width with no horizontal overflow.

**Checkpoint**: US1 is shippable alone. Run quickstart Q2, then a quick Q5 against any drafting draft for fit only.

---

## Phase 4: User Story 3: reach and surprise tags (P2); ADP half first, facts only

**Goal**: "14 before ADP" / "9 past ADP" / "On ADP" on the card. The model-share half lands in Phase 6.
**Independent test**: a player with ADP 30 taken at pick 16 reads "14 before ADP".

- [X] T017 [US3] Tests first, in `web/pickInsight.test.ts` / `web/pickInsight.ts`: `adpDelta(player, pickNo): number | null` = `round(player.adp) − pickNo`. **"Positive = taken before ADP (a reach), the same sign as `reachBias`. `null` when no ADP (`adp` ≥ 999 or missing)."** Ordering tests:
  - ADP 30 at pick 16 gives `+14`, labelled "before ADP", **never** "past".
  - ADP 10 at pick 19 gives `−9`, labelled "past ADP".
  - `|Δ| ≤ 1` gives "On ADP".
  - ADP 999 gives `null` and no tag.

  Add `adpLabel(delta)` alongside it.
- [X] T018 [US3] Render the ADP chip in `web/components/PickInsightCard.tsx` (contract row 4), set `adpDelta` in `buildFactInsight`, and extend `PickInsightCard.test.tsx` for the null-ADP omission.

---

## Phase 5: Per-pick background re-projection (blocks US2, US3's model half, and US4's projected column)

Not a user story by itself. It is the R1/R2 prerequisite. **Measure before building on it.**

- [X] T019 In `web/pages/LiveDraftView.tsx`, change `resimulate()`'s trigger from only `[seats, mySlot]` to also include "a new landed pick arrived" (the highest landed `pickNo` increased). Keep the existing `RESIM_DEBOUNCE_MS` debounce, `pendingRef` coalescing, `requestSeqRef` guard and abort-on-unmount **unchanged**. Update the long comment above that `useEffect` ("One baseline projection per seat, not one per pick…") to explain the partial reversal of `41b9426`: facts still never wait on it, and it now exists to keep the projections fresh for the card and meters. Do not change `RESIM_ITERATIONS`.
- [X] T020 In the same file, send an explicit `startState` and stamp the result (R2, DM-1, DM-4).
  - When `missingPickNos` is empty **and** every landed pick has `player.sleeperId` (startState stays all-or-nothing, F2): send `startState` as `{ [pickNo]: sleeperId }` and record `asOfPick = max pickNo sent`.
  - Otherwise: send no `startState` and record `asOfPick: null`.
  - Keep the last 2 results as `StampedProjection { result, asOfPick, busy }` in a ref-backed ring, alongside the existing `result` state that the board and `AvailabilityPanel` still read.
  - Track an `inFlightAsOf: number | null` for the run currently in flight, so the card can show `updating`.
- [X] T021 429 handling in the same file. *(Amended after review, F1: `streamSimulationQuietly` already retries a 429 up to 3× with capped backoff. Keep that; do not add a second retry policy.)* When a run throws an `ApiError` with `status === 429`, meaning it survived those retries:
  - record a `busy: true` stamp for that attempt;
  - do **not** `fail(e)` (no page-level error banner);
  - add **no further** retry; the next landed pick triggers the next attempt.

  Other errors keep today's behaviour.
- [X] T022 Add `LiveDraftView.resim.test.tsx`, with the T008 mocks:
  - A new landed pick triggers exactly one debounced run, and a burst of 3 picks triggers one.
  - The request carries a `startState` equal to the landed list when complete, and none when incomplete.
  - The stamp's `asOfPick` equals the max sent.
  - A 429 produces `busy`, no error banner and no retry.
- [X] T023 **Measure (quickstart Q4)** against a drafting draft, locally, with backend and frontend from **this worktree**:
  - pick-to-projection wall-clock time, min / median / max over ≥ 10 picks;
  - the 429 count with 12 concurrent `POST /api/sims/stream` callers carrying distinct `X-Sleeper-User` headers.

  Write both to `verification.md` as **measured**, naming the machine. **Gate**: if the median is above the typical gap between picks, or most callers get 429, stop. Tell the user with the numbers and propose R1-alt (a shared server-side projection per draft, research.md R1) as its own spec. Phases 6 and 7's projected column then ship as "omitted with reason". Phases 3, 4, 7's "left now" and 8 still ship.

---

## Phase 6: User Story 2: what they will probably do next (P2), plus US3's model share

**Goal**: "Likely next @ R.PP" with a share and runners-up; "Model had this at N%" / "under X%" / "Surprise".
**Independent test**: after the projection refreshes, the card's likely-next player and share match the per-cell marginal at that manager's next pick. For one pick, the model share matches the pre-pick projection's cell (SC-003).

- [X] T024 [US2] Tests first, in `web/pickInsight.test.ts` / `web/pickInsight.ts`:
  - `nextPickFor(slot, afterPickNo, teams, rounds, reversalRound): number | null` using `web/snake.ts` `pickNoAt`, covering a normal snake, `reversalRound = 3`, and "no picks left" → `null`.
  - `cellCandidates(cell: PredictedPick): Candidate[]`: `[{player, probability}, ...alternatives]` sorted by share descending (R4). Test that when `isModal` is false the top result is the most-voted alternative, **not** `cell.player`.
  - `likelyNext(stamps, pick, nextPickNo, inFlightAsOf)`, which returns the data-model union and uses the newest stamp with `asOfPick >= pick.pickNo` (**DM-2**). Return:
    - `ready` with `top`, up to 2 `rest`, and `wideOpen = top.probability < WIDE_OPEN_SHARE`;
    - `updating` when a run covering the pick is in flight;
    - `busy` when the newest attempt was refused;
    - `none` with a reason when `nextPickNo` is null or no stamp exists.

    **Never return a candidate who is in the landed list.** A test must feed a stale stamp and assert it is not used.
- [X] T025 [US3] Tests first, in the same files: `modelShare(stamps, pick)`. It uses the newest stamp with `asOfPick < pick.pickNo` (**DM-3**) and finds the picked player among that cell's candidates.
  - Found → `{share, bound:'exact', asOfPick}`.
  - Not found → `{share: min shown share, bound:'under', asOfPick}`.
  - No pre-pick stamp → `null`.

  Also add `surprise = (exact && share < SURPRISE_SHARE) || (under && share <= SURPRISE_SHARE)`. Tests cover each branch, plus "as of pick N" labelling when `asOfPick < pick.pickNo − 1`.
- [X] T026 [US2] Wire these into `web/pages/LiveDraftView.tsx`. Recompute the open card's insight whenever the stamp ring or `inFlightAsOf` changes. **Freeze** a pick's insight into a `Map<number, PickInsight>` once `likelyNext.state` is `ready`, `busy` or `none` (**DM-6**), and never recompute a frozen one.
- [X] T027 [P] [US2] Render contract rows 4 (model share, "Surprise", "as of pick N") and 6 in `web/components/PickInsightCard.tsx`. Row 6 is "Likely next @ R.PP" with the top candidate and share, up to 2 more, "Wide open" when flagged, the states `updating` / `busy` ("projection server busy, will retry next pick") / `none` with its reason, and the provenance label. Percentages are whole numbers. Extend `PickInsightCard.test.tsx` for every state.

---

## Phase 7: User Story 4: position scarcity meter (P2)

**Goal**: per runnable position, `RB 9 / 34` left now, plus `· ~5 at 3.04` when that figure can be exact. Runs are flagged.
**Independent test**: quickstart Q6. The "left now" count matches a hand count against `/pool`.

- [X] T028 [P] [US4] Tests first: `BT/api/LeagueControllerPoolTest.java`, in the Mockito style of `BT/api/LeagueControllerRealBoardTest.java` (same mocks, same lenient `membership.visibleDraft` delegation). Cases:
  - An invisible draft gives 404.
  - The order equals `boards.currentBoard(sport)` for NFL and NBA, with the sport taken from the draft's league as `/seats` does.
  - `limit` defaults to 200 and clamps to `[1, 400]` (0 → 1, 10000 → 400).
  - A null `team` serializes as JSON `null`.
  - Drafted players are **not** filtered out.
  - An empty board gives `[]`, not an exception.
- [X] T029 [US4] Implement `GET /api/drafts/{sleeperDraftId}/pool` in `BE/api/LeagueController.java`, next to `seats`, per contracts/pool-endpoint.md:
  - Read the `X-Sleeper-User` header and scope with `membership.visibleDraft`.
  - Take the sport from the league row.
  - Return `boards.currentBoard(sport).stream().limit(clamped).map(SimulationResult.PlayerRef::from).toList()`: records directly, **no `Map.of`**.
- [X] T030 [P] [US4] Add a scoping case for `/api/drafts/{id}/pool` in `BT/api/AccessControlMvcIT.java`, mirroring its `/seats` case. **Postgres-backed; check the skip count.**
- [X] T031 [US4] Add `getDraftPool(draftId, limit)` to `web/api.ts`, returning `PlayerRef[]`, in the **same commit as T029**.
- [X] T032 [US4] Tests first: `web/scarcity.test.ts`, then `web/scarcity.ts` with `positionScarcity(...)`.
  - **Inputs**: sport, the pool (board order), `S = teams × startersPerTeam`, where `startersPerTeam = computeTeamNeeds(sport, rosterPositions, []).length`, plus landed picks, the newest post-pick stamp, `myNextPick` and `slotKnown`.
  - **Output**: `PositionScarcity[]` for `RUNNABLE_BY_SPORT[sport]` (T002), plus the definition sentence "Starter pool: the board's top S (teams × starters)".
  - **Rules** (data-model.md):
    - `leftNow` counts undrafted pool players at the position.
    - `expectedAtNext` is "Σ `survivalByPick[myNextPick]` over the undrafted pool players at this position, from the newest post-pick projection. **`null` unless** `slotKnown`, a post-pick projection exists, and the total undrafted pool players are ≤ `SNAPSHOT_DEPTH_MIRROR` (75)".
    - `running` is `positionRun(landed, 6, 4, sport)?.position === position`, the same call the feed makes.
  - Also export `projectedFromPick(S)` for the "projected count from pick ~N" line.
  - **Tests**:
    - A hand-built 12×10 pool.
    - K/DEF absent for NFL; all five positions present for NBA.
    - The gate is off at 76 undrafted and on at 75.
    - The expected value equals a hand-summed fixture.
    - An unknown seat gives `null`.
- [X] T033 [P] [US4] Tests first: `web/components/ScarcityMeter.test.tsx`, then `web/components/ScarcityMeter.tsx`, per contract "Scarcity row": one tinted chip per position; the running marker uses the feed's run wording; one muted definition line; the "projected count from pick ~N" line when gated off; and "no board built yet" when the pool is empty or its fetch failed (FR-013). The fetch failing means an old backend without `/pool`, which must degrade this row only, never the page.
- [X] T034 [US4] Wire it up in `web/pages/LiveDraftView.tsx`:
  - Fetch `getDraftPool(draftId, min(S, POOL_LIMIT_MAX))` once `seats` is loaded (a catch sets the failed state).
  - Render `ScarcityMeter` under the "Your team" block, where it would sit when the seat is unknown too.
  - Add the card's `scarcityLine` (contract row 7) when the picked player's position is `running` or `leftNow <= SCARCE_LEFT`.
- [X] T035 [US4] Styles for the scarcity row in the same stylesheet as T016, with no horizontal scroll at 375px (wrap the chips).

---

## Phase 8: User Story 5: on-brand meter per manager (P3)

**Goal**: each manager's position mix and reach so far vs their profile, with verdicts only where there is something to compare.
**Independent test**: quickstart Q6's on-brand bullet.

- [X] T036 [US5] Tests first: `web/onBrand.test.ts`, then `web/onBrand.ts` with `onBrandReads(seats, landed): OnBrandRead[]`, per data-model.md verbatim.
  - `reachSoFar`: mean of `adpDelta` (T017) over picks with an ADP, else `null`.
  - `profileReach`: `seat.reachBias`, positive = reaches.
  - `reachVerdict`: "`null` if NEUTRAL or `picks < 3`. `'on'` if same sign, or both within ±3 (hand-set)."
  - `lean`: "Top of `seat.positionalTilt` if ≥ 1.10 (hand-set). Always `null` for STATED or NEUTRAL (tilt is only ever fitted)."
  - `leanVerdict`: "`null` if no lean or `picks < 3`. `'on'` iff `leanShare > roomShare`."
  - A STATED seat's reach read is labelled "vs what you entered".
  - The reach comparison is labelled "vs board ADP" (R8: the profile was fitted against `adp_at_time`).
  - **Ordering tests**:
    - A manager with an RB lean whose RB share rises above the room's reads `'on'`, never `'off'`.
    - A profile of `+8` and a draft-so-far of `+10` reads `'on'`.
    - `+8` against `−6` reads `'off'`.
    - A neutral seat gives no verdict.
    - Two picks give no verdict.
    - A stated seat has `lean === null`.
- [X] T037 [P] [US5] Tests first: `web/components/OnBrandPanel.test.tsx`, then `web/components/OnBrandPanel.tsx`. It is a disclosure, collapsed by default, with one row per seat: avatar, name, mix counts, reach so far vs profile with a signed number, and both verdicts or their reasons ("too early", "no history", "lean not fitted"). Hand-set thresholds carry a `title` saying so.
- [X] T038 [US5] Wire it up: render `OnBrandPanel` beside `ScarcityMeter` in `web/pages/LiveDraftView.tsx`; add the drafting manager's read to the card (contract row 5) via `web/components/PickInsightCard.tsx`; add the same read for that seat to `web/components/SeatPopover.tsx`, as an optional prop so other rooms' use of `SeatPopover` is unchanged.

---

## Phase 9: Polish, review and live verification

- [X] T039 Run quickstart Q1–Q3. Backend: read the **skipped** count, since any skipped IT (e.g. T030) means it did not run. Frontend: `npx tsc -b && npx vitest run && npm run build`. Record the results in `verification.md` against T001's baseline.
- [X] T040 Confirm the scope guard. `git diff main --stat` must show no change to `web/pages/MockDraftView.tsx` or `web/pages/DraftView.tsx`. Open `/mock/<id>` and a pre-draft `/draft/<id>` in the browser and confirm there are no cards, no meters, and the feed rows are not clickable.
- [X] T041 **Bug-hunting code review** (a separate pass, not a style pass; the session's own model, not Sonnet), over the full diff. Focus on:
  - pre/post-pick stamping off-by-ones (DM-2/DM-3);
  - a frozen insight being recomputed;
  - a card opening for history;
  - a 429 surfacing as a page error;
  - sign conventions (`adpDelta` vs `reachBias`);
  - `feedPicks` and the card disagreeing;
  - `Map.of` or other null traps in `/pool`;
  - `api.ts` drift.

  Write it to `specs/012-draft-pick-insight/code-review.md` and fix confirmed findings.
- [X] T042 **Live verification**, quickstart Q5–Q7, with a disposable 4-team Sleeper draft (the 2026-09-02 recipe in HANDOFF.md "Live-draft dry run") and servers built from **this worktree**. Cover every check in Q5, Q6 and Q7, including 375px via `resize_window` (reset afterwards), a basketball draft for the meters, and the preference across a reload. Record everything, pass or fail, in `verification.md`, keeping **measured** and **assumed** separate. Share a screenshot of the card and meters.
- [X] T043 [P] Update `HANDOFF.md` (current state) and `README.md`'s feature list if it lists live-room features, stating what was verified and what was not. Add a durable lesson to `claude/lessons.md` if T041/T042 found one. Update the auto-memory entry for live pick announcements and team fit (`project_live_draft_pick_announcements_idea.md`) to point at spec 012.

---

## Dependencies

```
T000 → T001 → Phase 2 (T002–T008)
Phase 2 → US1 (T009–T016)          ← MVP
Phase 2 → US3-ADP (T017–T018)      (independent of US1's lifecycle; needs T010's card to render into)
Phase 2 → Phase 5 (T019–T023) ── gate T023 ──→ US2 + US3-model (T024–T027)
                                              └→ US4 projected column (inside T032/T034)
Phase 2 → US4 left-now (T028–T035)  (backend T028–T031 can run in parallel with any web phase)
T017 → US5 (T036–T038)              (reuses adpDelta)
All → Phase 9 (T039–T043)
```

- US1 needs only Phase 2.
- US3's ADP half needs T010 and T011 to exist, so it renders into the card.
- US2 and US3's model half need Phase 5 **and** T023's gate to pass. If the gate fails, they ship as the `none` state with a reason, and R1-alt becomes a new spec.
- US4's "left now" needs only Phase 2 plus the endpoint. Its projected column needs Phase 5.
- US5 needs T017 (`adpDelta`) and the landed list (T008).

## Parallel opportunities

- **Phase 2**: T002, T003, T004 and T005 touch separate files, so they run together. T006 → T007 → T008 are sequential (same functions / same page).
- **Backend vs web**: T028–T031 (Java + one `api.ts` line) can proceed alongside Phases 3–6 on the web side. T031 must land in the same commit as T029.
- **Within stories**: new-component tasks marked [P] (T011, T027, T033, T037) can be built alongside their pure-logic siblings. All `LiveDraftView.tsx` wiring tasks (T012–T014, T019–T021, T026, T034, T038) are **sequential**: they are one file.

## Implementation strategy

1. **MVP = Phase 0 → 2 → US1.** A fit card for every live pick that needs no projection, plus the fix for the fit gap when opening mid-draft. Stop here and verify live if time is short.
2. **Add US3's ADP tag and US4's "left now"** (facts only, plus one small endpoint).
3. **Phase 5, then measure (T023).** This is the only risky step. Its gate decides whether US2 and US3's model share ship with numbers or with "omitted — projection unavailable".
4. **US2 + US3's model half, US4's projected column, US5.**
5. **Review (T041) and live verification (T042)** before reporting anything as done. A green suite alone is not "verified" here.
