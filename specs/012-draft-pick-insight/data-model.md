# Data model: Live draft pick insight

Everything here is **derived on the client, in memory**. Nothing is persisted and
there is no migration. The only new wire shape is the pool endpoint
([contracts/pool-endpoint.md](contracts/pool-endpoint.md)). The inputs already
exist: `RealPick`, `LiveState`, `Seat`, `SeatsResponse.rosterPositions`,
`SimulationResult`.

## Inputs (existing)

| Input | Source | Note |
| --- | --- | --- |
| `landed: RealPick[]` | `getRealDraftBoard` on mount, then `live.recentPicks` merged over it (R3) | *(Amended after review, F2.)* Completeness is **per seat** for fit, open slots and on-brand: no missing pick number belongs to that seat. It is **global** (no missing pick numbers at all) only for `startState` (DM-4) |
| `seats: Seat[]` | `getSeats` | `reachBias`, `positionalTilt`, `provenance`, `draftsObserved` |
| `rosterPositions` | `SeatsResponse` | Starting slots = `computeTeamNeeds(sport, rosterPositions, []).length` |
| `projections` | `resimulate()` results, each wrapped as a `StampedProjection` | Ring of the last 2 (R5) |
| `pool: PlayerRef[]` | **new** `GET /api/drafts/{id}/pool?limit=S` | Board order, S = teams × starting slots |

## StampedProjection

| Field | Type | Rule |
| --- | --- | --- |
| `result` | `SimulationResult` | As returned |
| `asOfPick` | `number \| null` | **DM-1**: the highest `pickNo` sent in an explicit `startState`. `null` when the request fell back to DB replay (landed list incomplete). |
| `busy` | `boolean` | True when the run was refused with a 429 that survived `streamSimulationQuietly`'s own retries (review F1). `result` is then absent. |

- **DM-2**: A projection is **post-pick** for pick *p* iff `asOfPick != null && asOfPick >= p`.
- **DM-3**: A projection is **pre-pick** for pick *p* iff `asOfPick != null && asOfPick < p`. It is labelled "as of pick {asOfPick}" when `asOfPick < p − 1`.
- **DM-4**: If any pick number up to `picksMade` is missing from the landed list, or any landed pick lacks a `player.sleeperId`, do not send `startState`; stamp `asOfPick: null` (R2's known gap; review F2).

## PickInsight (one per landed pick, frozen once complete — R9)

| Field | Type | Source / rule |
| --- | --- | --- |
| `pick` | `RealPick` | The landed pick |
| `seat` | `Seat \| undefined` | `seats.find(s => s.slot === pick.slot)` |
| `fills` | `string \| null` | `fitSlot(sport, position, computeTeamNeeds(sport, rosterPositions, priorRoster))`. `null` means "Depth". **Must be the same call `feedPicks` makes**; extract it so both use one function. |
| `openAfter` | `string[]` | Slots in `computeTeamNeeds(..., priorRoster + player)` with `player == null`, in roster order |
| `rosterComplete` | `boolean` | `openAfter.length === 0` |
| `adpDelta` | `number \| null` | `round(player.adp) − pickNo`. **Positive = taken before ADP (a reach)**, the same sign as `reachBias`. `null` when no ADP (`adp` ≥ 999 or missing). |
| `modelShare` | `{ share: number, bound: 'exact' \| 'under', asOfPick: number } \| null` | R5, from the newest pre-pick projection (DM-3). `null` when there is none. |
| `surprise` | `boolean` | `modelShare` exists and (`share < 0.05` exact, or `under` with bound ≤ 0.05) |
| `nextPickNo` | `number \| null` | That seat's next pick after `pickNo` in snake order with `reversalRound`, via `snake.ts` `pickNoAt(round, slot, teams, reversalRound)` for round + 1 onward. `null` if none left. |
| `likelyNext` | `{ state: 'ready', top: Candidate, rest: Candidate[], wideOpen: boolean } \| { state: 'updating' } \| { state: 'busy' } \| { state: 'none', reason: string }` | R4 on the newest **post-pick** projection (DM-2). `wideOpen = top.probability < 0.25`. `updating` while a run covering *p* is in flight; `busy` on a 429; `none` if `nextPickNo` is null, or no projection has ever returned. |
| `provenanceLabel` | `string` | "fitted from N drafts" / "stated by you" / "no history — neutral seat" |
| `scarcityLine` | `string \| null` | From `PositionScarcity` for this player's position, only when it is running or the count left now is ≤ 3 (hand-set) |
| `summary` | `string` | **DM-5**: built only from `fills`, `openAfter`, and positions this seat has no player at yet. Every clause maps to a field above; no free text. |

**Frozen-when-complete rule (DM-6).** A `PickInsight` is stored in a
`Map<pickNo, PickInsight>` once `likelyNext.state` is `ready`, `busy` or `none`.
After that it is not recomputed, so reopening an old card from the feed shows
what was known then.

## PositionScarcity (one per runnable position, recomputed per pick)

| Field | Type | Rule |
| --- | --- | --- |
| `position` | `string` | From exported `RUNNABLE_BY_SPORT[sport]` (`pickRun.ts`) |
| `poolSize` | `number` | Players at this position among the first S of `pool` |
| `leftNow` | `number` | Of those, how many have no landed pick |
| `expectedAtNext` | `number \| null` | R7: Σ `survivalByPick[myNextPick]` over the undrafted pool players at this position, from the newest post-pick projection. **`null` unless** `slotKnown`, a post-pick projection exists, and the total undrafted pool players are ≤ `SNAPSHOT_DEPTH_MIRROR` (75) |
| `running` | `boolean` | `positionRun(landed, 6, 4, sport)?.position === position`, the same call the feed makes |

Shared, shown once: `startersPerTeam`, `teams`, `S`, and the sentence
"Starter pool: the board's top S (teams × starters)".

## OnBrandRead (one per seat, recomputed per pick)

| Field | Type | Rule |
| --- | --- | --- |
| `slot`, `manager`, `provenance` | from `Seat` | |
| `picks` | `number` | That seat's landed picks |
| `reachSoFar` | `number \| null` | Mean of `adpDelta` over picks with an ADP. `null` if there are none. |
| `profileReach` | `number` | `seat.reachBias` (positive = reaches) |
| `reachVerdict` | `'on' \| 'off' \| null` | `null` if NEUTRAL or `picks < 3`. `'on'` if both within ±3, or both beyond ±3 with the same sign (hand-set). *Amended after live verification (T042): the original "same sign" rule called a +0.7 drafter "on brand" against a +10 profile.* |
| `lean` | `{ position, tilt } \| null` | Top of `seat.positionalTilt` if ≥ 1.10 (hand-set). Always `null` for STATED or NEUTRAL (tilt is only ever fitted). |
| `leanShare`, `roomShare` | `number` | This seat's share of picks at `lean.position` vs every landed pick's share at it |
| `leanVerdict` | `'on' \| 'off' \| null` | `null` if no lean or `picks < 3`. `'on'` iff `leanShare > roomShare`. |
| `mix` | `Record<position, number>` | Counts so far, always shown |

## Hand-set constants (one exported object, labelled arbitrary)

`WIDE_OPEN_SHARE 0.25` · `SURPRISE_SHARE 0.05` · `MIN_PICKS_FOR_VERDICT 3` ·
`REACH_TOLERANCE 3` · `LEAN_TILT 1.10` · `SCARCE_LEFT 3` ·
`CARD_DISMISS_MS 8000`. `SNAPSHOT_DEPTH_MIRROR 75` is not hand-set: it mirrors
`MonteCarloRunner.SNAPSHOT_DEPTH` and is pinned by a backend test (R7).

## State transitions: the card

```
(pick arrives, visible, initial load done, cards on, not on the clock)
        → OPEN(p)
OPEN(p) —newer pick q→ OPEN(q)            (replace, never queue)
OPEN(p) —8s, not hovered/focused→ CLOSED
OPEN(p) —× / Esc→ CLOSED
OPEN(p) —you go on the clock→ CLOSED
CLOSED  —click feed row r→ OPEN(r)        (frozen insight if complete)
```
