# Contract: `GET /api/drafts/{sleeperDraftId}/pool`

The only backend addition. It returns the head of the board the engine simulates
against, with player ids, so the live room can define the starter pool (R6).

## Request

```
GET /api/drafts/{sleeperDraftId}/pool?limit={n}
X-Sleeper-User: <sleeper user id>      (same scoping header every draft route reads)
```

| Param | Rule |
| --- | --- |
| `limit` | Optional, default 200. Clamped to `[1, 400]` rather than rejected, the same policy as `SimulationRequest.MAX_ITERATIONS`. 400 covers 14 teams × 15 starters with room to spare. The value is hand-set. |

## Response `200`

`PlayerRef[]`: the first `limit` entries of `boards.currentBoard(sport)`, **in
that order**, each mapped through `SimulationResult.PlayerRef.from(BoardEntry)`,
the one mapper the engine and the mock room already share. `sport` comes from the
draft's league, as in `/seats`.

```json
[
  { "id": 412, "sleeperId": "9509", "name": "Bijan Robinson", "position": "RB",
    "team": "ATL", "adp": 1.8, "positionalRank": 1 },
  { "id": 97, "sleeperId": "4866", "name": "…", "position": "WR",
    "team": null, "adp": 3.1, "positionalRank": 1 }
]
```

- `team` can be null (a free agent). Serialize the record directly; do **not** build a `Map.of(...)` (AGENTS.md hard rule).
- Drafted players are **not** filtered out. The client subtracts landed picks, so the pool's definition does not shift as the draft moves.
- An empty board returns `[]`. Unlike `SimulationService`, it does not throw; the meter then says "no board built yet" (FR-013).

## Errors

| Status | When |
| --- | --- |
| `404` | `membership.visibleDraft(sleeperUserId, sleeperDraftId)` is empty: an unknown draft, or one outside the caller's leagues. Same response as `/seats`, so the route does not reveal whether a draft exists. |

## Frontend mirror (same change)

`web/src/api.ts`:

```ts
export const getDraftPool = (draftId: string, limit: number) =>
  apiFetch(`/api/drafts/${draftId}/pool?limit=${limit}`).then(json<PlayerRef[]>)
```

There is no new type: `PlayerRef` already mirrors `SimulationResult.PlayerRef`
field for field.

## Tests

- Scoping: another league's draft → 404 (as `AccessControlMvcIT` does for `/seats`).
- Order equals `currentBoard(sport)` for both sports. `limit` clamps at 0 and at 10,000.
- A null `team` round-trips as JSON `null`.
- Postgres-backed ITs **skip silently** when the DB is down. Check the skip count, not just BUILD SUCCESSFUL.
