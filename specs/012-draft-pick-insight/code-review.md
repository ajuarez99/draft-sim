# T041: Bug-hunting code review

**Reviewer**: the parent session (the session's own model, not Sonnet), reading
every sub-agent's diff as it landed and the whole diff at the end. This was a
correctness pass, not a style pass. The focus list comes from tasks.md T041.

## Fixed during review

| # | Where | Defect | Fix |
| --- | --- | --- | --- |
| C1 | `pickInsight.modelShare` | When the picked player was not among a cell's candidates, it always reported "under X%", with X the smallest share shown. A cell carries **every** player any run voted for, up to 4 (the assigned player plus `MonteCarloRunner.ALTERNATIVES` = 3). With fewer than 4 shown, the list is the whole tally, so the true share is exactly 0. "Under 40%" would have been true and misleading (lessons.md class 5). | Returns `{share: 0, bound: 'exact'}` when fewer than `CELL_CANDIDATES` (4) are shown. A test was added. |
| C2 | `pickInsight.ts` | `CELL_CANDIDATES` mirrors a backend constant. This is the "two implementations of one rule" landmine. | `MonteCarloRunner.ALTERNATIVES` widened to package-private and pinned at 3 in `MonteCarloRunnerSnapshotDepthTest`, whose failure message names the web mirror. `SNAPSHOT_DEPTH` is pinned the same way (T004). |
| C3 | `pickInsight.ts` | It redeclared `Candidate`, which `api.ts` already exports. | Re-exports the `api.ts` type. |

## Checked and correct

- **Pre- and post-pick stamping (DM-2/DM-3).**
  - `likelyNext` only uses stamps with `asOfPick >= pickNo`.
  - `modelShare` only uses stamps with `asOfPick < pickNo`.
  - "as of pick N" appears only when `asOfPick < pickNo − 1`.
  - `buildStartState` is all-or-nothing and reads the landed list through refs **at run time**, so a coalesced re-run includes picks that arrived mid-run.
- **Stale projections never pose as current.**
  - The meter's `postPickResult` is used only when the newest non-busy stamp's `asOfPick === highestLanded`.
  - `likelyNext` filters out landed players, so a stale cell cannot name someone already drafted.
- **`nextPickFor`** searches from the current round, so it serves both "that seat's next pick after its own" and "my next pick after the newest landed pick" (on the clock this round → this round's pick).
- **Freezing (DM-6).** It freezes only on `ready`, `busy`, or `none` with no picks left. The sub-agent correctly refused to freeze the transient "not back yet" state, which would have pinned every card there. Picks whose fit is untrusted are not frozen.
- **History versus news.** The first settled render records `seenThrough`. Picks delivered while the tab is hidden advance it without opening a card.
- **429.** One retry policy only (`streamSimulationQuietly`'s). A 429 that survives it becomes a `busy` stamp with no page error.
- **Sign conventions.** `adpDelta` is positive for a reach, as is `reachBias`. The ordering tests assert both directions.
- **Feed and card agree.** Both call `fillsFor`.
- **`/pool`.** Serializes records directly (no `Map.of`); a nullable `team` is tested; it is scoped like `/seats`.
- **`api.ts`** gained `getDraftPool` over the existing `PlayerRef`, with no record changes.
- **Scope.** `MockDraftView.tsx` and `DraftView.tsx` are byte-identical to `main`. `PickFeed` and `SeatPopover` changes are optional-prop only.

## Noted, not changed

- **N1.** `cardInsight`'s `useMemo` writes to `frozenInsightsRef`, a side effect inside a memo. It is idempotent, so it is harmless under StrictMode's double invoke, but it is impure. Moving it to an effect would cost a render of lag. Left as is.
- **N2.** Between a pick landing and its run finishing (measured 0.2–2.2s), the meter's "~N at your pick" figure disappears rather than showing the previous value. That is honest (the old value is from a different state), but it flickers once per pick. Revisit only if it reads badly live.
- **N3.** Reopening an old card shows the on-brand and scarcity lines **as the room stands now**, because they are computed at render time. The projection rows show what was known at the time. The mix is deliberate (facts versus projections) and was reported by the sub-agent.
- **N4.** The same user with two live tabs has each tab's run cancel the other's (the permits lease is per identity per draft; review F5). This is pre-existing.
- **N5.** A backend `resolveStartState` silently drops ids it cannot resolve. This was found during T023, when a text export with `\r` produced an unlocked simulation with no error. The client is not exposed to it, because it sends `RealPick.player.sleeperId`. It is recorded for `claude/lessons.md`.

## Verdict

No open correctness findings. Layout, z-order against the availability sheet, and
375px behaviour cannot be judged in jsdom. Those are T042's job.
