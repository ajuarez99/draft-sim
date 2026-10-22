# Data model: Lock-in threshold (spec 044)

Everything is derived on read. Nothing is stored, and there's no migration.

## Inputs (existing)

| Source | Used for |
|---|---|
| `league.settings_json.game_mode`, `sport` | the lock-in gate (R1) |
| `league.scoring_json` | scoring every game (`GameScoringService`) |
| `league.settings_json.playoff_week_start` | the `playoff` flag |
| `roster_week_points(league_id, week, roster_id, players_points, starters)` | credited value per starter-week |
| `roster_season(league_id, roster_id, manager_id)` | roster → manager |
| `player_game(sport, season, week, sleeper_player_id, game_id, game_date, stats)` | games played (R4: played only) |
| `projection_snapshot(sport, season, sleeper_player_id, game_id, stats)` | the projection baseline (R5) |

## Derived entities

### CreditedGame (internal)

- `week`, `rosterId`, `playerId`, `credited` (the double from `players_points`)
- `classification`: `LOCK | RODE_OUT | TIE_NON_LAST | TIE_WITH_LAST | UNMATCHED | SINGLE_GAME | NO_GAMES`
- For `LOCK`: `gameIndex` and `gameId`

Rules: the games are those with that week, ordered `(game_date, game_id)`. A match is `|score − credited| < 0.005`.
One match at the last index is `RODE_OUT`, and one match anywhere else is `LOCK`. Two or more matches give
`TIE_NON_LAST` when the last index isn't among them, and `TIE_WITH_LAST` otherwise. Only starters whose id is in
`players_points` are counted.

### Lock (served)

- `week`, `playoff`, `managerId`, `manager` (display, nullable), `rosterId`, `playerId`, `player` (nullable)
- `gameDate`, `gameId`, `points` (the credited value, full league scoring)
- `standIn`: `{ baseline, margin }`, where both are nullable (no prior game, or baseline ≤ 0)
- `projection`: `{ projectedPoints, actualRestricted, margin }`, or null when there's no snapshot for that game
- `laterGames` (≥1), `bestLater`, `beatenLater` (`bestLater > points`, full scoring)

### LockSummary (served, per manager and per player)

- the key (`managerId` or `playerId`) and the display name
- per baseline kind (`STAND_IN`, `PROJECTION`): `n`, `median`, `q1`, `q3`, all nullable below `minLocks` except `n`
- `rodeOut`, `tieNonLast` (counts, baseline-independent)

### PayoffCell (served, per baseline kind)

- `band` (`"<0" | "0-10" | "10-25" | "25-50" | "50+"`, built from config), `gamesLeft` (`1..cap`, cap = "3+")
- `n`, `rate` (share with `beatenLater`, null below `minCell`)

### LockInReport (served)

- `sport`, `season`, `status` (`MEASURED | NOT_LOCK_IN | NO_SCORING | NO_SCORED_WEEKS`), `reason` (nullable)
- `minLocks`, `minCell`, `bands`, `gamesLeftCap`
- `totals`: counts of every classification plus `noBaselineStandIn`, `noSnapshot`
- `managers: LockSummary[]`, `players: LockSummary[]`, `payoff: { STAND_IN: PayoffCell[], PROJECTION: PayoffCell[] }`
- `locks: Lock[]`, only when `?player=` is given (otherwise empty, to keep the payload small)
- `excludedCategories` (the projection restricted map, R5), `caveats: string[]`

## Validation

- A margin is never computed with a null or ≤ 0 baseline.
- The STAND_IN and PROJECTION figures never share a number.
- `totals.lock + rodeOut + tieNonLast + tieWithLast + unmatched == multiGameStarterWeeks`. This invariant is
  asserted in tests.

## Amended after plan review (2026-10-21)

- `classification` gains `RODE_OUT_MISSED` (credited 0, no 0-point game). The invariant becomes
  `lock + rodeOut + rodeOutMissed + tieNonLast + tieWithLast + unmatched == multiGame`, with the classes
  disjoint (F12).
- The game set is raw `player_game` minus the All-Star game. The Cup final is **kept** (F1/F2).
- A stand-in requires ≥ `minPriorGames` prior games (F10).
- `status` vocabulary: `MEASURED | NOT_SLEEPER | NOT_NBA | GAME_MODE | SHARE_BELOW_THRESHOLD | NO_SCORING |
  NO_SCORED_WEEKS` (F8).
- `Lock` gains `lastGame` (the week's last played game score) and `beatenByLast`. `PayoffCell` gains `rateLast`
  (the headline) next to `rateAny` (in hindsight) (F4).

### New: LockThreshold

- `gamesLeft` (1..4, where 4 means 4+), `threshold` (a margin, as a fraction), `n` (games in the distribution)
- the report-level `thresholdsFrom: int[]` (the seasons pooled), `pMissed`, and `thresholdStatus: OK | NO_HISTORY`

### New: LiveCall (US4)

- `rosterId`, `managerId`, `manager`, `playerId`, `player`, `team`
- `latestGame: {gameId, gameDate, points}` (the only lockable game, per analyze F1), `baseline`, `baselineKind: STAND_IN | LAST_SEASON`, `margin`,
  `gamesLeft`, `threshold`
- `call: LOCK | WAIT | TOO_EARLY`
- `live: {week, asOf, status: OK | NOT_LIVE | ROSTERS_FAILED | NO_HISTORY, calls[]}`

### New: LockInCard (US5, inside the AdminDigest's SportDigest)

- `leagueName`, `sleeperLeagueId`, `week`, `status: OK | NONE_TONIGHT | NO_HISTORY | ROSTERS_FAILED`
- `entries: LiveCall[]` (call = LOCK, latest game on the digest night, sorted by margin desc)
- `tweet: string` (≤ 280 characters)
