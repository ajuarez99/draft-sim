# Contract delta: player page (spec 022 C1)

`GET /api/leagues/{sleeperLeagueId}/players/{sleeperPlayerId}` with optional `X-Sleeper-User`.
Additive only. Every existing field keeps its meaning and value (SC-004).

New fields on the response (see data-model "PlayerStatsPage"):

```json
{
  "status":   { "injuryStatus": "Questionable", "asOf": "2026-10-20T11:02:13Z" },
  "nextGame": { "gameId": "…", "date": "2026-10-21", "opponent": "OKC", "isHome": true, "week": 1 },
  "nextGameReason": null,
  "upcoming": [ { "gameId": "…", "date": "2026-10-21", "opponent": "OKC", "isHome": true, "week": 1 } ]
}
```

*(Amended after review 2026-10-20, F1 BLOCKER.)* `status`/`nextGame`/`upcoming` come from the URL's league
row and the player's current `player.team`, **not** from `requestedSeason`. `requestedSeason` is also set
when the current season simply has no box scores yet (opening night). They're null with
`NO_GAMES_LEFT` only when that URL league is complete. `status` also carries `status` (e.g. SUS).
~~Original: null whenever `requestedSeason` is set.~~
