# T000: Adversarial plan review

This review read the plan documents cold on 2026-09-30 and checked each claim
against `main` @ `de1e1dd`. It ran before any code was written.

## Claims confirmed against the code

| Claim | Where checked | Result |
| --- | --- | --- |
| R1: the live room projects once per seat | `LiveDraftView.tsx`, `useEffect(..., [seats, mySlot])`; commit `41b9426` | ✅ |
| R2: an explicit `startState` wins | `SimulationService.resolveStartState` | ✅ An empty map falls back to DB replay, so only send a non-empty one |
| R4: a cell holds the chosen player plus 3 others | `BoardAssembler.assemble` filters `chosenId` out of `alternatives`, `ALTERNATIVES = 3` | ✅ |
| R7: the snapshot is the top 75 in board order | `MonteCarloRunner.SNAPSHOT_DEPTH = 75`, `DraftSimulator.topAvailable` iterates `available` in board order, skipping `!isDraftable` | ✅ |
| Permits | `SimulationPermits`: `max(2, cores/4)`, 3s wait, then 429; one lease per identity per draft, and a new request cancels the old | ✅ |
| `SimRequest.startState` exists on the client | `api.ts` `SimRequest.startState?: Record<number, string>` | ✅ |
| `getRealDraftBoard` works for a *drafting* draft | `LeagueController.realBoard` reads `drafts.picks(draft.id())` with no status filter | ✅ It returns whatever the poller has written so far |

## Findings: the plan was wrong or incomplete

**F1. A 429 is already retried; T021 said "do not retry" (amended).**
`streamSimulationQuietly` (`api.ts`) already retries a 429 up to 3 times with
capped backoff (`Retry-After` × attempt, ≤ 8s). That is the audit's own decision
("expected weather, not a failure"). Adding a second no-retry policy on top would
be a second implementation of one rule. **Amendment**: keep the existing quiet
retries. A 429 that survives them is thrown as `ApiError` with `status === 429`,
and that is what becomes `busy`. The card shows `updating` for the whole retry
window, which can reach about 3 × 8s.

**F2. The real board drops picks it cannot name, so "no gaps" can be false for
the rest of the draft (amended).**
`PickNaming.rows` skips a pick whose `playerId` is null, and one whose player row
is gone (`LeagueController.java`, the `row(...)` nulls). A single unresolvable
pick, for example a rookie missing from the players table until the next refresh,
would make the landed list "incomplete" permanently. Under DM-1/DM-4 that
disables every `startState` stamp, and under the existing feed gate it disables
every fit clause. **Amendment**:
- **Fit, open slots and on-brand are complete per seat**: a seat's roster is
  trusted when none of the missing pick numbers belong to that seat. The owning
  slot of a missing pick is computed with `snake.ts` `pickNoAt` over that round.
  A card for another seat is unaffected by the hole.
- **`startState` stays all-or-nothing** (DM-4). Any gap means `asOfPick: null`,
  because the engine would treat the hole as an open pick. This is the same as
  today's DB replay, which skips null `playerId` too, so no behaviour gets worse.
  The card then shows "none — projection can't be pinned to a pick" for the
  projection rows.

**F3. The real-board fetch can fail (amended).** An old backend, a 404, or a
network error. **Amendment to T008**: if `realPicks` is null, fall back to
today's source (`result.board` prefix merged under `recentPicks`), so the page
does no worse than today.

**F4. Off-board players already carry ADP 999.** `fallbackPlayerRef` sets
`adp = 999, positionalRank = 999`. DM's "`null` when `adp` ≥ 999" rule handles
them, and they are never in `/pool`, so scarcity ignores them. No change is
needed, but T017's test should use a `fallbackPlayerRef`-shaped player.

**F5. Two tabs, one identity.** The permits lease is per identity per draft, so
the same user with two live tabs will have each tab's run cancel the other's.
That is pre-existing behaviour, and with per-pick runs it becomes visible as
`updating` → `busy` more often. It is noted rather than fixed, because it is out
of scope.

## Verdict

The plan stands with the amendments F1–F3 applied to data-model.md and tasks.md
(T008, T012, T021, T026). No finding changes scope.
