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
  `(sport, season)` rows and inserts all of them, in one `@Transactional` method. A
  no-op with a warning when `games` is empty (FR-002).
- *Amended after review:*
  - The insert is `on conflict (sport, season, game_id) do update`, and `games` comes
    from the parser's de-duplicated map, so a duplicate id can't fail every run (F3).
  - Binds: `fetched_at` is an `OffsetDateTime` and `game_date` a `LocalDate`, via
    `setObject`, as in `SportTrendingRepository` (N2).
  - Called **after** the per-game week loop. A failure is caught and reported as
    `PlayerGameIngestService.Result.scheduleStoreFailed` (new `boolean` component), and
    `LeagueRefreshService.refreshChain` throws for it after the season loop and after
    trending (F3).
- **Not referenced by `league`**: shared across leagues, like `player_game` and
  `sport_week_stats`.
- No `CHECK` on `status`, deliberately. Sleeper's vocabulary isn't documented, and a
  check would turn a new status into a failed refresh.

## Lifted class: `ingest/SportSchedule` (was `PlayerGameIngestService.Schedule`)

Same behavior. Changes:

- top-level, `public final class SportSchedule`; `record Game(gameId, week, date, status, home, away)` public
- `public static SportSchedule parse(List<Map<String,Object>>)` (unchanged logic)
- `public List<Game> games()`, built from the de-duplicated `byId` map (last wins), for
  storage. *Amended after review (F3):* it's not built from the raw rows.
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
| `playoffWindowRefusal()` | *Amended after review (N3):* a code when `lastPlayoffWeek()` is empty: `NO_START` (start < 2), `TOO_FEW_TEAMS` (teams < 2), `ROUND_TYPE_UNKNOWN` (null), or `ROUND_TYPE_UNSUPPORTED` (any non-0 value, e.g. West Coast FF's 1, F5). `engine/` turns the code into the sentence |

Adding `playoffRoundType` changes `PlayoffFormat`'s positional constructor, so
`PlayoffOddsServiceTest:51, 116, 128` get the extra argument, with no assertion
change (F4).

### League span for the grid (*added after review, F2*)

| Field | Rule |
|---|---|
| `lastLeagueWeek` | `lastPlayoffWeek()` when present; else `playoff_week_start − 1` when start ≥ 2; else the last stored schedule week |
| `seasonOver` | league `status` is complete (`LeagueRow.isComplete`), **or** `currentLeg` > `lastLeagueWeek` |

### Which league row (*added after review, F1*)

Both services read `leagues.bySleeperId(urlId)`, the row `visibleLeague` returned.
**Never `LeagueSeasonResolver`.** That walks back to the newest season with scored
weeks, so it would serve NBA 2025 for NBA 2026's whole pre-scoring window. The
response's `season` is that row's season.

### Current week

`LeagueRepository.currentLeg(leagueId)` returns `OptionalInt` from
`settings_json->>'leg'`. Empty when absent. Never defaulted (research R6).

### Next matchup (`NextMatchupService`)

Inputs: league row (F1: the URL's), `currentLeg`, `playoffFormat`, `league_matchup`
fixtures for that one week, `roster_season` standings rows (username = `managerName`,
avatar, manager id), **`league_member` rows for team names** (amended after review
F10: `LeagueMemberRepository.forLeague`, not `roster_season`), and the caller's
manager id.

*Rules added after review (F10):*
- **The caller's roster**: the `roster_season` row(s) whose `managerId` equals the
  caller's. If there are two (a manager with two rosters), take the **lowest roster
  id** and note it in a comment. That's a rule, not an accident.
- **Bye**: `between` filters `matchup_id is null`, so a bye roster has no row. "Caller
  on a bye" = the caller has a roster, fixtures exist for `leg`, and either no
  fixture row carries the caller's roster, or no other roster shares its
  `matchup_id`.
- Names go out raw and nullable. The fallback (team name → username → "roster N")
  stays client-side (`LeagueHome.tsx:409-410`).

| State | `available` | `week` | `me` | `opponent` | `reason` |
|---|---|---|---|---|---|
| no `leg` | false | null | null | null | "Sleeper hasn't said which week this league is in yet." |
| `leg ≥ playoff_week_start` (start ≥ 2), or league `complete` | false | leg | null | null | "The regular season is over." |
| no fixtures stored for `leg` | false | leg | null | null | "Pairings for week {leg} aren't out yet. Sleeper publishes them shortly before the week starts." |
| caller has no roster | true | leg | null | null | null |
| caller on a bye (no partner) | true | leg | side | null | null |
| paired | true | leg | side | side | null |

The page tells "not a member" apart from "bye" by `me == null` vs `opponent == null`.
That's the existing `LeagueHome` contract (no "you" rows and no error).
