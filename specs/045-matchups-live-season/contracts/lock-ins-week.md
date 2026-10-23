# Contract: `GET /api/leagues/{sleeperId}/lock-ins?week=N` (new parameter)

Same scoping as today. `/lock-ins` without `week` is **unchanged byte for byte**, both with and without
`player`. *Amended after plan review F8:* `weekCovered` is omitted (`NON_NULL`) unless `week` is given.

With `week=N` (and no `player`):

| Field | Value |
|---|---|
| `status`, `reason`, `thresholds`, `caveats`, `sport`, `season` | as in the league report |
| `locks` | every provable lock with `week == N`, all rosters (`Lock` shape from 044, unchanged) |
| `managers`, `players`, `payoff` | empty (keeps the payload small, like the `player` form) |
| `live` | null |
| `weekCovered` | `status == MEASURED` and N is a computed (final, scorable) week, else false (F7) |

`player` and `week` together are a 400, sent after the membership 404 (F18). The page never sends both, and refusing is cheaper than defining
their intersection.

Page use: a final NBA week fetches this once and groups `locks` by `rosterId`.
