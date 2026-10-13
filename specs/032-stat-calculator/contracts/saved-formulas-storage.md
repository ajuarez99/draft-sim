# Contract C3: Saved formulas in browser storage

**Key**: `bk.statCalc.saved.v1`

**Value**:

```json
{ "v": 1, "formulas": [
  { "id": "k3f9", "name": "Usage value", "expr": "(usg + vor) / tov",
    "settings": { "window": "SEASON", "mode": "perGame", "top": 15, "qualifiedOnly": true, "standardize": false, "dir": "desc" },
    "savedAt": "2026-10-12T18:04:00Z" }
] }
```

## Read

- Anything other than `{ v: 1, formulas: [...] }` reads as an empty list.
- Malformed entries are skipped.
- An entry whose `expr` doesn't parse against the current input set is kept, and shown as
  "uses a stat that no longer exists".

## Write

- Wrapped in try/catch, as `web/src/statChoice.ts` does.
- On failure, the in-memory list still works for the page load, and the page says the formula
  wasn't saved to this browser.
- There are at most 50 entries. Saving the 51st drops the oldest and says so.
- Each write re-reads the store first. Two tabs saving at the same moment still resolve as last
  write wins, which is accepted (plan review N7).

## What it is not

- It isn't synced to the account, so other devices and browsers don't see it, and the list says
  so in those words (spec US3, Q2: A).
- It isn't league-scoped. A saved formula opens in whatever league the member is in.
