# API contract changes (spec 063)

No endpoint is added or removed. Requests are unchanged. Two response bodies gain fields, and the game state gains `reversalRound` (int).

## `GET /api/history/games/{id}`, `POST .../pick`, `POST .../auto`

`result` (present when `status` is `COMPLETE`) follows [data-model.md](../data-model.md). For a game completed
before this feature the stored JSON is returned byte for byte, without `format`, `playoffs`, `finalPlaces`,
`userOutcome` or `weeks[].phase`.

The pick and auto responses still contain every computer pick made by that request. The client paces them.

## `GET /api/history/games`

Each row gains:

```json
{ "id": 41, "sport": "NBA", "seasonLabel": "1995-96", "status": "COMPLETE", "userSlot": 2,
  "finish": 2, "formatVersion": 2, "outcome": "LOST_FINAL", "createdAt": "..." }
```

`formatVersion` and `outcome` are `null` for unfinished games and for old-format games (where `finish` is a
standings rank).

## Web mirror

`web/src/api.ts`: `HistoricalResult`, `HistoricalWeek`, the new nested types, and the game summary type change in
the same commit as the Java records.
