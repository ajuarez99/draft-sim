# Contract: player trends

> **Amended after review, 2026-10-06:**
> - The per-list seasons are `streamingSeason`/`rolesSeason` (F1).
> - `rostered` is nullable (F4).
> - New fields: `excludedStale`, `excludedNoTeam`, `missedTeamGames`, `lastGameDate`,
>   `seasonPts` (F2, F7).
> - `oneGameCredit` replaces the server-held `gameCountNote` sentence (F8).
> - `SEASON_COMPLETE` is a streaming reason (F3).

> **Amended after code review, 2026-10-06** (data-model "Amended after code review"):
> - New top-level fields: `staleReferenceDate` (date, nullable), plus `risersFreeAgentTotal`,
>   `risersRosteredTotal`, `fallersFreeAgentTotal` and `fallersRosteredTotal` (0 when ownership is
>   unknown).
> - With ownership known, risers and fallers hold up to `listSize` **per group** (B2).
> - `TrendRow.team` is **nullable**: a completed season keeps teamless players and shows the team
>   of their last game (B4). Invariant 5's "none has a null team" applies only to live seasons and
>   fallbacks.
> - `excludedStale` and `excludedNoTeam` count only players who'd otherwise be listed (B3).

## C1. `GET /api/leagues/{sleeperLeagueId}/player-trends`

`X-Sleeper-User` as on every league route. Scoped by `LeagueMembership.visibleLeague`: 404 when
the caller can't see it. Added to `AccessControlMvcIT`'s hand-listed league routes.

```json
{
  "sport": "nba", "season": 2026, "available": true, "reason": null,
  "rolesSeason": 2025, "rolesFallback": true,
  "streamingSeason": 2025, "streamingFallback": true,
  "windows": { "recentGames": 3, "formGames": 5, "formMinGames": 3, "minSeasonGames": 5, "recencyDays": 14 },
  "roleThresholdMinutes": 6,
  "currentWeek": 1,
  "risersTotal": 12, "fallersTotal": 9, "excludedStale": 41, "excludedNoTeam": 86,
  "risers": [ "TrendRow" ], "fallers": [ "TrendRow" ],
  "streamingReason": null, "rostersFetchedAt": "2026-10-11T02:10:00Z",
  "streaming": [ "TrendRow" ],
  "oneGameCredit": { "code": "ONE_GAME_CREDITED", "share": 0.99, "seasonMeasured": 2025 }
}
```

`TrendRow`:

```json
{
  "sleeperPlayerId": "4635", "name": "…", "positions": ["PG"], "team": "DEN",
  "games": 71, "lastGameDate": "2026-04-12",
  "recentMin": 34.2, "seasonMin": 28.1, "minDelta": 6.1, "role": "RISER",
  "recentUsg": 27.3, "seasonUsg": 24.0, "ptsPerMin": 1.21,
  "seasonPts": 38.0, "formPts": 41.6, "missedTeamGames": 0,
  "rostered": false, "rosteredBy": null, "gamesThisWeek": 4, "gamesNextWeek": 3
}
```

**Nullable:**

- top level: `reason`, `rolesSeason`, `streamingSeason`, `roleThresholdMinutes` (only under
  `NOT_CONFIGURED`), `currentWeek`, `streamingReason`, `rostersFetchedAt`, `oneGameCredit`;
- `TrendRow`: `recentMin`, `seasonMin`, `minDelta`, `role`, `recentUsg`, `seasonUsg`,
  `ptsPerMin`, `seasonPts`, `formPts`, `rostered`, `rosteredBy`, `gamesThisWeek`,
  `gamesNextWeek`. `team` is never null (no-team players are excluded).

**Enums:**

- `role` ∈ `RISER | FALLER | STEADY`
- `reason` ∈ `NOT_CONFIGURED | NOT_BASKETBALL | NO_GAMES`
- `streamingReason` ∈ `SEASON_COMPLETE | NOT_DRAFTED | ROSTERS_NOT_LOADED`

### Invariants (test these)

1. No `sleeperPlayerId` starts with `TEAM_`, and no row's games include an All-Star game.
2. Every streaming row has `rostered === false`, and its id isn't in the stored rosters.
3. `streamingReason != null` ⇒ `streaming = []` **and** every row's `rostered` is null.
4. `risers` ⊆ RISER, `fallers` ⊆ FALLER, `|risers| ≤ listSize`, `risersTotal ≥ |risers|`.
5. No row is stale (`lastGameDate` within `recencyDays` of the list's season's last game date), and
   none has a null team.
6. For a row with a team, `gamesThisWeek` = the grid cell for that team at `currentWeek`.
7. `*Fallback = true` ⇒ that list's season = `season − 1`.
8. Streaming is sorted by `seasonPts` desc.

## TypeScript mirror (`web/src/api.ts`, same change)

`TrendRole`, `PlayerTrendsReason`, `StreamingReason`, `OneGameCredit`, `TrendRow`,
`PlayerTrends` mirror the above field for field (`rostered: boolean | null`).
`getPlayerTrends(sleeperLeagueId)`.

## UI contract

- **Navigation:** destination `trends` ("Trends"), `sports: ['nba']`, route
  `/leagues/:sleeperLeagueId/trends`, keyed on `useLeagueDataVersion`.
- **Season labels:** each section names its season. When a list falls back, it leads with "2025
  season (2026 is too early: fewer than half the teams have played {n} games)". Fallback
  risers/fallers say "end of the 2025 season" (N15).
- **Streaming:**
  - It comes first. Columns: player + team; "season avg {seasonPts}" (the sort); "last 5:
    {formPts}"; minutes "{recentMin} recent · {seasonMin} season" plus a delta pill; games
    "this week (incl. played) / next".
  - "Missed last {n} team games" shows when n > 0.
  - The header note shows only when `oneGameCredit` is non-null: "In this league a starter's
    week counts one game ({share}% of multi-game weeks in {seasonMeasured}). Extra games help a
    little: measured +4% for 4 vs 2 scheduled games (same player, 2025, Ball Knowers)."
  - Unavailable shows the streamingReason sentence, with no promise of a refresh for
    `SEASON_COMPLETE` and no ingest wording.
  - The label says "not on a roster (includes players on waivers)" (N10).
- **Risers and fallers:** split "Free agents" / "Rostered" only when `rostered` is non-null.
  Otherwise one list, with a line explaining why. Usage is labelled "usage rate (pooled over the
  window)". "+N more" appears beyond the top 10. "{excludedStale} not shown: no game in the last
  {recencyDays} days. {excludedNoTeam} without an NBA team."
- **Layout:** every number names its window. At 375px the tables scroll inside their card, with
  no page scroll.
