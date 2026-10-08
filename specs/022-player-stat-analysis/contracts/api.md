# Contract: player stat analysis (spec 022)

All three routes are league routes. They take `X-Sleeper-User` and are scoped by
`LeagueMembership.visibleLeague`: a caller who can't see the league gets the same 404 as a league
that doesn't exist. Each route is added to `AccessControlMvcIT`'s hand-listed league routes.

The `{sleeperLeagueId}` in the path names the **season**, as the chain does (research R7). The
season picker in the web client switches it.

## Fields shared by all three responses

| Field | Type | Meaning |
|---|---|---|
| `sport`, `season` | `"nba"`, int | The season answered about |
| `requestedSeason` | int, nullable | Set when the resolver moved to an earlier season (R7) |
| `available` | bool | |
| `reason` | string, nullable | `NOT_BASKETBALL`, `NOT_CONFIGURED` or `NO_GAMES` (this season has no stored games) |
| `dataAsOf` | timestamp, nullable | `max(player_game.fetched_at)` for the season (FR-008) |
| `seasons` | `[{season, sleeperLeagueId}]` | The picker's options: every chain season with stored games, newest first |

`Rate` is `{ "value": 61.6, "reason": null }`. Exactly one of the two is non-null. `reason` is one
of `NO_ATTEMPTS`, `NO_MINUTES` or `NO_TEAM_ROW`.

`Pct` is `{ "value": 88.0, "group": "NBA_POSITION" | "LEAGUE_ROSTERED", "n": 97, "reason": null }`.
`reason` is one of `NOT_QUALIFIED`, `GROUP_TOO_SMALL` or `OWNERSHIP_UNAVAILABLE`.

`Ownership` is:

```json
{ "state": "ROSTERED", "ownerName": "…", "avatarId": "…", "isMe": false,
  "asOf": { "kind": "CURRENT", "fetchedAt": "…" } }
```

- `state` is one of `ROSTERED`, `FREE_AGENT`, `NOT_DRAFTED` or `UNAVAILABLE`.
- `asOf.kind` is `CURRENT` (with `fetchedAt`) or `WEEK` (with `week`).

## C1. `GET /api/leagues/{sleeperLeagueId}/players/{sleeperPlayerId}`

The player page (US1 and US2).

```json
{
  "sport": "nba", "season": 2025, "requestedSeason": 2026, "available": true, "reason": null,
  "dataAsOf": "2026-04-13T11:04:00Z", "seasons": [{ "season": 2026, "sleeperLeagueId": "…" }],
  "player": { "sleeperPlayerId": "4046", "name": "Nikola Jokić", "positions": ["C"],
              "team": "DEN", "known": true },
  "teamsThisSeason": ["DEN"],
  "missedTeamGames": 17,
  "ownership": { "…": "Ownership" },
  "windows": {
    "SEASON":  { "WindowStats": "…" },
    "LAST_10": { "WindowStats": "…" },
    "LAST_5":  { "WindowStats": "…" }
  },
  "fantasy": {
    "fpPerGame": { "SEASON": 61.2, "LAST_10": 58.0, "LAST_5": 66.1 },
    "ranks": { "leagueRank": 1, "positionRank": 1, "pointsRank": 8, "rankMove": 7,
               "groupSize": 214, "positionGroupSize": 41, "position": "C", "reason": null },
    "breakdown": [ { "key": "reb", "points": 820.0, "share": 0.21 } ],
    "seasonTotal": 3978.5
  },
  "percentiles": { "ts": ["Pct", "Pct"], "usg": ["Pct", "Pct"] },
  "gameLog": [ { "GameLogRow": "…" } ]
}
```

`WindowStats` holds:

- `games` (the games the window actually covers), `minutes`, `minutesPerGame`, `smallSample`;
- `perGame`, `totals` and `per36`, each `{pts, reb, oreb, dreb, ast, stl, blk, tov, pf, fgm, fga,
  tpm, tpa, ftm, fta}`;
- `shooting`: `{fgPct, tpPct, ftPct}`, each a `Rate`;
- `advanced`: `{ts, efg, ftr, tpar, usg, minutesShare, astPct, orbPct, drbPct, trbPct, stlPct,
  blkPct, tovPct}`, each a `Rate`;
- `gameScorePerGame` and `plusMinusPerGame`.

`GameLogRow` holds `gameId`, `date`, `week`, `team`, `opponent`, `isHome` (nullable), `minutes`,
the full counting line, `plusMinus`, `gameScore` and `fantasyPoints`. Rows are newest first.

**Rules:**

- **Unknown player**: a player id with stored games but no `player` row is
  `player.known: false`, `name: null`, `positions: []`, `team: null`.
- **No games**: a player id with no stored games and no player row gives 404.
- **No games this season**: `available` is true and `reason` is `NO_GAMES`. The windows are empty
  and the game log is `[]`.
- **Unqualified player**: `fantasy.ranks.reason` is `NOT_QUALIFIED`, and the rank numbers are null.

## C2. `GET /api/leagues/{sleeperLeagueId}/stats?window=SEASON|LAST_10|LAST_5`

The leaderboard (US4). `window` is **required**: a missing or unknown value gives 400, because the
window is a rule and is never defaulted.

```json
{
  "sport": "nba", "season": 2025, "requestedSeason": null, "available": true, "reason": null,
  "dataAsOf": "…", "seasons": [], "window": "SEASON",
  "qualification": { "minGames": 41, "minMinutesPerGame": 15, "maxTeamGames": 82 },
  "ownershipAsOf": { "kind": "WEEK", "week": 21 },
  "draft": { "state": "COMPLETE", "draftId": "…" },
  "adp": { "source": "blend", "capturedOn": null, "reason": "NO_ADP_STORED" },
  "replacement": { "byPosition": { "PG": 31.2, "SG": 27.0, "SF": 28.4, "PF": 29.1, "C": 33.5 },
                   "rule": "GREEDY_SLOT_FILL", "teams": 12,
                   "slots": ["PG","SG","SF","PF","C","G","F","UTIL","UTIL"] },
  "rows": [ { "LeaderboardRow": "…" } ]
}
```

- `draft.state` is `COMPLETE`, `NOT_HAPPENED` or `NONE`.
- `adp` is `{source, capturedOn}` when ADP is stored, or carries `reason: NO_ADP_STORED`.

`LeaderboardRow` holds:

- **Identity**: `sleeperPlayerId`, `name`, `positions`, `team`.
- **Ownership**: an `Ownership` value.
- **Stats**: `qualified`, and the `WindowStats` for the requested window.
- **Fantasy and ranks**: `fpPerGame`, plus `leagueRank`, `positionRank`, `pointsRank` and
  `rankMove`, all null if the player is unqualified.
- **Replacement**: `valueOverReplacement`, and `vorPosition` (the position it used).
- **Draft**: `{pickNo, round, managerName}` or null; null means undrafted when `draft.state` is
  `COMPLETE`.
- **Draft value**: `draftValue`, Draft Grades' `valueOverSlot`, or null.
- **ADP**: `adp`, a number or null.

**Rules:**

- Rows cover every player with at least one game in the window, qualified or not. Sorting,
  filtering, column groups and the stat leaders are client-side, with one comparator
  (research R14).
- Ranks are computed server-side over the qualified group only. A leaderboard value equals the
  player page's value for the same window (I6).

## C3. `GET /api/leagues/{sleeperLeagueId}/nightly?date=YYYY-MM-DD`

The nightly report (US3). `date` is optional, because the default is a stated rule rather than a
value: the most recent date with at least one complete game. The response always names the date
it answers about.

```json
{
  "sport": "nba", "season": 2025, "requestedSeason": null, "available": true, "reason": null,
  "dataAsOf": "…", "seasons": [],
  "date": "2026-01-14", "dates": ["2025-10-21", "…"],
  "complete": true, "missingGames": 0,
  "games": [ { "gameId": "…", "home": "DEN", "away": "LAL", "homePts": 121, "awayPts": 114,
               "status": "complete", "boxStored": true } ],
  "topByGameScore": [ { "NightLine": "…" } ],
  "topByFantasy": [ { "NightLine": "…" } ],
  "mine": { "ownership": { "kind": "WEEK", "week": 13 }, "played": ["NightLine"],
            "didNotPlay": [ { "sleeperPlayerId": "…", "name": "…", "team": "…" } ], "reason": null },
  "standouts": [ { "line": "NightLine", "rule": "SEASON_HIGH_PTS", "values": { "tonight": 41, "previous": 33 } } ],
  "standoutRules": { "minPriorGames": 5, "tsDelta": 15, "tsMinAttempts": 10, "minutesJump": 10 }
}
```

`NightLine` holds the `GameLogRow` fields plus `sleeperPlayerId`, `name`, `positions`, `gameScore`,
`fantasyPoints` and `ownership`.

**Rules:**

- `mine.reason` is `NOT_SIGNED_IN`, `NO_ROSTER` or `OWNERSHIP_UNAVAILABLE`.
- A date with no scheduled games gives `games: []` and `reason: "NO_GAMES_ON_DATE"`, with no empty
  sections.
- A date outside the season gives 400.

## Health

`/api/health` gains `playerStatsLoaded` (bool): true when the `draftsim.player-stats` block is
present.

## Web (`web/src/api.ts`)

Types mirror these records field for field, in the same change as the Java records (the
constitution's second rule). The sentence for every reason and rule code lives in the web client,
not in the server.
