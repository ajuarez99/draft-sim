# Data model: NBA schedule grid and next opponent

## New table: `sport_schedule` (V27)

Check the highest `V<n>` on the branch **at build time** (V26 on `main` @ `fffce72`).
Concurrent sessions exist, so if V27 is taken, use the next free number and say so.

| Column | Type | Null | Notes |
|---|---|---|---|
| `sport` | text | no | `nba` / `nfl` (`Sport.code()`) |
| `season` | int | no | |
| `game_id` | text | no | Sleeper's id, as a string |
| `week` | int | no | Sleeper's `week` (FR-003), never derived from `game_date` |
| `game_date` | date | yes | Sleeper's `date`, first 10 chars. Null if unparseable, as the parser already does |
| `home` | text | yes | team code via `SportSchedule.sideTeam` (both payload shapes) |
| `away` | text | yes | same |
| `status` | text | yes | `pre_game`, `in_game`, `complete`, `postponed`, `canceled`, as sent. Not an enum: an unknown value is stored, not rejected |
| `fetched_at` | timestamptz | no | same value for every row of one write |

- **PK** `(sport, season, game_id)`. Index `(sport, season, week)`.
- **Write**: `replaceSeason(sport, season, games, fetchedAt)` deletes the
  `(sport, season)` rows and inserts all of them, in one transaction. A no-op with a
  warning when `games` is empty (FR-002).
- **Not referenced by `league`**: shared across leagues, like `player_game` and
  `sport_week_stats`.
- No `CHECK` on `status`, deliberately. Sleeper's vocabulary isn't documented, and a
  check would turn a new status into a failed refresh.

## Lifted class: `ingest/SportSchedule` (was `PlayerGameIngestService.Schedule`)

Same behavior. Changes:

- top-level, `public final class SportSchedule`; `record Game(gameId, week, date, status, home, away)` public
- `public static SportSchedule parse(List<Map<String,Object>>)` (unchanged logic)
- `public List<Game> games()` in parse order, for storage
- `public static boolean counts(String status)`: `!"postponed".equals(status) && !"canceled".equals(status)`. A `null` status counts. It's a scheduled game whose status Sleeper didn't send, and dropping it would under-count.
- existing instance methods (`lastStartedWeek`, `isAway`, `teamOf`, `hasCompleteGame`, `teamPlayed`, `isFinal`) unchanged, still used by the ingest

## Derived, not stored

### Team-week counts (`ScheduleGridService`, pure core)

`Map<team, int[weeks]>` built from stored `Game`s where `SportSchedule.counts(status)`.
A home and an away team each get +1. A team row exists iff it has ≥ 1 counted game in
the season (research R4).

### Playoff window (`LeagueRepository.PlayoffFormat` extension)

| Field | Rule |
|---|---|
| `playoffWeekStart` | existing |
| `playoffTeams` | existing |
| `playoffRoundType` | **new, `Integer` (nullable)**: `settings_json->>'playoff_round_type'`, not coalesced (research R5) |
| `lastPlayoffWeek()` | `OptionalInt`: present iff `playoffRoundType == 0 && start >= 2 && teams >= 2`, giving `start + ceil(log2(teams)) - 1` |

### Current week

`LeagueRepository.currentLeg(leagueId)` returns `OptionalInt` from
`settings_json->>'leg'`. Empty when absent. Never defaulted (research R6).

### Next matchup (`NextMatchupService`)

Inputs: league row, `currentLeg`, `playoffFormat`, `league_matchup` fixtures for that
one week, `roster_season` standings rows (team name, username, avatar, manager id),
and the caller's manager id.

| State | `available` | `week` | `me` | `opponent` | `reason` |
|---|---|---|---|---|---|
| no `leg` | false | null | null | null | "Sleeper hasn't said which week this league is in yet." |
| `leg ≥ playoff_week_start` (start ≥ 2) | false | leg | null | null | "The regular season is over." |
| no fixtures stored for `leg` | false | leg | null | null | "Pairings for week {leg} aren't out yet. Sleeper publishes them shortly before the week starts." |
| caller has no roster | true | leg | null | null | null |
| caller on a bye (no partner) | true | leg | side | null | null |
| paired | true | leg | side | side | null |

The page tells "not a member" apart from "bye" by `me == null` vs `opponent == null`.
That's the existing `LeagueHome` contract (no "you" rows and no error).
