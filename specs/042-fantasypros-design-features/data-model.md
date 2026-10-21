# Data model: spec 042

No tables are added or changed. These are payload shapes, each mirrored in `web/src/api.ts`.

## Ownership (existing, unchanged)

`PlayerOwnership.Ownership`: `state` ROSTERED | FREE_AGENT | NOT_DRAFTED | UNAVAILABLE, `rosterId?`,
`ownerName?`, `avatarId?`, `isMe`, `asOf?`. See `engine/PlayerOwnership.java`.

## TrendRow (+1 field)

| Field | Type | Rule |
|---|---|---|
| `rosteredByMe` | boolean \| null | null exactly when `rostered` is null (ownership unknown). Otherwise `RosterOwner.isMe` of the owning roster, or false for a free agent. |

## PlayerStatsPage (+3 fields)

| Field | Type | Rule |
|---|---|---|
| `status` | `PlayerStatus` \| null | null when the page is unavailable. |
| `nextGame` | `NextGame` \| null | null when the player has no team, or no qualifying game remains. `nextGameReason` says which. |
| `nextGameReason` | `NO_TEAM` \| `NO_GAMES_LEFT` \| `NO_SCHEDULE` \| null | null exactly when `nextGame` is set. |
| `upcoming` | `NextGame[]` | up to 7 of the team's next qualifying games (the Schedule tab). Empty when nextGame is null. |

`PlayerStatus`: `injuryStatus` (string \| null, Sleeper's raw value), `status` (string \| null, `player.status`,
so SUS shows), `asOf` (timestamp \| null, research R4 as amended: greatest of status_capture and the daily
PLAYERS capture). *(Amended after review 2026-10-20, F2/F4.)*

`NextGame`: `gameId`, `date` (ISO date, **no time**), `opponent` (string \| null), `isHome` (boolean \|
null), `week` (int).

## LineupCheck (new)

| Field | Type | Rule |
|---|---|---|
| `available` | boolean | false: only `reason` is meaningful. |
| `reason` | `NOT_MEMBER` \| `NOT_IN_SEASON` \| `ROSTERS_FAILED` \| null | null when available. *(Amended after review 2026-10-20, F7/F12: ESPN is gated by a `lineupCheck` LeagueAvailability key, not `NO_STARTERS`. `NOT_MEMBER` is only an admin token with no header, or a chain member with no roster this season.)* |
| `week` | int \| null | the league's current leg. |
| `statusAsOf` | timestamp \| null | as in PlayerStatus. |
| `scheduleAsOf` | timestamp \| null | `SportScheduleRepository.fetchedAt`; null when no schedule is stored. *(Amended after code review 2026-10-20 (S2).)* |
| `gameCheck` | boolean | false when the game check could not run (see `gameCheckReason`). The UI then says so once. *(Amended after code review 2026-10-20 (S3): also false when the stored schedule does not hold the league's current week, the `ScheduleGridService.scheduleHoldsWeek` guard `teamsWithoutGame` applies.)* |
| `gameCheckReason` | `NO_SCHEDULE` \| `WEEK_UNKNOWN` \| `WEEK_NOT_IN_SCHEDULE` \| null | why `gameCheck` is false; null when it is true. *(Added after code review 2026-10-20 (S5).)* |
| `flags` | `LineupFlag[]` | starters with a reason, in starter order. Empty means "All starters are active". |

`LineupFlag`: `slot` (string), `player` (`PlayerRef` \| null; null for an empty slot), `reason` (`EMPTY_SLOT` \|
`OUT` \| `LISTED` \| `NO_GAME_IN_WEEK` \| `UNKNOWN_PLAYER`), `injuryStatus` (string \| null), `playerStatus` (string \| null, e.g. SUS),
`options` (`BenchOption[]`), `optionsOrder` (`FP_PER_GAME` \| `NAME` \| `SLOT_UNSUPPORTED`).
*(Amended after review 2026-10-20: F4 splits STATUS into OUT, which is `isUnavailable` or suspended, and
LISTED, any other non-null status. F6 changes NO_GAME_LEFT to NO_GAME_IN_WEEK and drops GAMES_LEFT. F3 adds
SLOT_UNSUPPORTED.)*
*(Amended after code review 2026-10-20 (S4): a starter with no player row is flagged `UNKNOWN_PLAYER` (no
options, `player.known=false`) rather than dropped, so "All starters are active" is never said about a player
nothing is known about. Last in the reason order; it is the only reason that is not about a tag or the schedule.)*

`BenchOption`: `player` (`PlayerRef`), `gamesLeft` (int \| null; null when gameCheck is false; a fact, not
a sort key), `fpPerGame` (number \| null), `fpSeason` (int \| null, the season the average is from),
`fpGames` (int, games behind it), `injuryStatus` (string \| null; Sleeper's raw tag for a LISTED bench player).
*(Amended after review 2026-10-20, F10. `injuryStatus` added after code review 2026-10-20 (B1).)*
`optionsOrder` is `FP_PER_GAME` only when every option in the flag has an average from the same season;
otherwise `NAME` *(code review B2)*.

**Validation**: a starter can carry several reasons (Out *and* no game). He is listed once, with the
first reason in the order EMPTY_SLOT, OUT, LISTED, NO_GAME_IN_WEEK, and his `injuryStatus` is always filled when
known. Options never include a player who is himself flagged.

> **Amended after code review 2026-10-20 (B1):** options exclude a bench player who is OUT (`isUnavailable`
> or suspended) or has no game in the week. A LISTED bench player (DTD, GTD, Questionable, ...) is kept and
> carries his tag in `BenchOption.injuryStatus`, so the UI can show "Listed: DTD". The original sentence
> dropped them silently and made the empty-list copy false (FR-010 and research R3: nothing is dropped
> silently).
