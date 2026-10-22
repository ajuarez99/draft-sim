# Contract delta: player trends

`GET /api/leagues/{sleeperLeagueId}/player-trends` with optional `X-Sleeper-User`. Additive.

Each row gains `rosteredByMe: boolean | null` (see data-model "TrendRow"). With no `X-Sleeper-User` (only
possible with the admin token; a stranger gets 404, F7), it is false for every rostered row.
