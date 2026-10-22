# Contract: lineup check (new)

`GET /api/leagues/{sleeperLeagueId}/lineup-check` with `X-Sleeper-User` (the existing sign-in header).

- 200 with a `LineupCheck` body (data-model) in every handled case, including `available=false` with a
  `reason`. Same convention as the player page: an explained absence is a 200, not an error.
- 404 when the league is unknown (as the other league endpoints do).
- No header, or a stranger: **404**, the same as every league route (`visibleLeague`). *(Amended after review
  2026-10-20, F7.)* `NOT_MEMBER` (200) is only for an admin token with no header, or a chain member with no
  roster this season.
- League not drafted, or season complete: `NOT_IN_SEASON`.
- ESPN league: gated by a `lineupCheck` key in `LeagueAvailability` ("not available for ESPN leagues yet"). *(Amended, F12.)*
- Sleeper `/rosters` call fails: `ROSTERS_FAILED`. Logged, never thrown to the client. League home
  must still render (FR-013's spirit, applied to one section).

CORS: a plain GET with one custom header, the same as every other league endpoint that reads
`X-Sleeper-User`. Verified in a real browser, not curl (lessons class 6).

Example (NBA, one starter Out):

```json
{ "available": true, "reason": null, "week": 1, "statusAsOf": "2026-10-20T11:02:13Z", "gameCheck": true,
  "flags": [ { "slot": "PG", "player": { "sleeperPlayerId": "…", "name": "…", "positions": ["PG"], "team": "CHA", "known": true },
               "reason": "OUT", "injuryStatus": "Out", "playerStatus": null, "optionsOrder": "FP_PER_GAME",
               "options": [ { "player": { … }, "gamesLeft": 3, "fpPerGame": 31.4, "fpSeason": 2025, "fpGames": 71 } ] } ] }
```
