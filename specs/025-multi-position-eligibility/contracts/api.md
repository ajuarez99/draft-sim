# Contracts: Multi-position eligibility (spec 025)

## Wire change (additive)

Every endpoint that returns a `PlayerRef` now also returns `positions`. That includes the board, pool and availability; `RealPick.player`; `MockSessionState` picks and available players; and `/api/targets`:

```json
{ "id": 812, "sleeperId": "4278", "name": "Anthony Edwards", "position": "PG", "positions": ["PG","SG"], "team": "MIN", "adp": 6.1, "positionalRank": 3 }
```

- `position` keeps its old meaning (the alphabetical first), so existing clients are unaffected.
- `positions` is always present from a 025 backend, possibly empty. A 024 frontend ignores it, and a 025 frontend on an older backend falls back to `[position]`.

## UI contract (draft rooms only)

| Surface | Before | After |
|---|---|---|
| Position pill (board cell, list row, pick card, target chip, feed) | "PG3" for Edwards | "PG/SG" with a split-colour pill. **No NBA badge carries a rank** (Jokić shows "C"). Compact cells show a family code: "G", "F", "G/F", "F/C" *(amended after review)* |
| Position filter SG | players whose first position is SG | every player eligible at SG |
| Scarcity chip | 12-team 2026: 4 of 5 shown (SG hidden) | all five shown, counted by eligibility: PG 38 · SG 41 · SF 37 · PF 45 · C 31 on the top 108 *(amended after review)* |
| "Your team" | two-pass greedy on one position | matroid greedy plus augmentation, identical to the backend (parity fixture) |
| "Fills X" tag / pick-card fit clause | dedicated slot first, by one position | seatable by augmentation; names a directly eligible open slot, or else the open slot the shift fills, never a filled slot *(A1)* |
| Pick-run detector | one position per pick | NBA by family: Guard, Forward, Center. A pick counts only within one family *(amended)* |

Football: no visible change, apart from its 9 genuinely multi-position players.
