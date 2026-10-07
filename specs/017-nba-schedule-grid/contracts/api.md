# API contracts: spec 017

Both routes are scoped like every league route: `LeagueMembership.visibleLeague(id,
X-Sleeper-User)` empty → **404**, the same as a league that doesn't exist. Both
responses are Java records serialized by Jackson (no `Map.of`; several fields are
nullable). `web/src/api.ts` mirrors them field for field in the same change (FR-011).

## C1. `GET /api/leagues/{sleeperLeagueId}/schedule`

### 200, basketball league, schedule stored

```json
{
  "sport": "nba",
  "season": 2026,
  "available": true,
  "reason": null,
  "fetchedAt": "2026-10-05T18:02:11Z",
  "currentWeek": 1,
  "lastLeagueWeek": 22,
  "seasonOver": false,
  "weeks": [
    { "week": 1, "firstDate": "2026-10-20", "lastDate": "2026-10-25" },
    { "week": 2, "firstDate": "2026-10-26", "lastDate": "2026-11-01" }
  ],
  "playoff": { "startWeek": 20, "endWeek": 22, "reason": null },
  "teams": [
    { "team": "ATL", "games": [3, 4], "seasonTotal": 80 }
  ],
  "excluded": { "postponed": 0, "canceled": 0, "exhibition": 0 }
}
```

| Field | Type | Rule |
|---|---|---|
| `currentWeek` | `int \| null` | league `settings.leg` (research R6); null when absent |
| `lastLeagueWeek` | `int \| null` | *added after review (F2)*: the last week this league plays. Rule in [data-model.md](../data-model.md#league-span-for-the-grid-added-after-review-f2). The client's default columns and the Next-N sum stop here |
| `seasonOver` | boolean | *added after review (F2)*: league `complete`, or `currentWeek` > the playoff end **when known** (*amended after code review R2*; it used to compare with `lastLeagueWeek`). Server-computed so the client doesn't restate the rule. If `currentWeek` > `lastLeagueWeek` and it isn't over, the client shows `currentWeek` … last stored week |
| `season` | int | *amended after review (F1)*: the season of the league row the URL names, never a resolver fallback |
| `weeks` | array | **every** week in the stored schedule, ascending. The client picks the columns (from `currentWeek`, or the playoff window). Dates are from counted games only. Both are null for a week with none |
| `teams[].games` | `int[]` | same length and order as `weeks`; counted games (FR-004) |
| `teams[].seasonTotal` | int | sum of `games` |
| `teams` order | team code ascending | the client sorts. The server doesn't encode a ranking |
| `playoff` | object | `startWeek`: int or null. `endWeek`: int or null. `reason`: string or null, non-null iff `endWeek` is null (research R5). The sentence is written in `engine/` from a `store/` code (N3). A playoff week beyond the last stored week is shown by the client as "not in Sleeper's schedule" (N6) |
| `excluded` | object | how many stored games the rule left out, so the page can say so instead of hiding it |

### 200, unavailable

`available: false`, `reason` set, `weeks: []`, `teams: []`, `playoff` still filled if
it can be (it doesn't depend on the schedule). `fetchedAt` is null.

| Case | `reason` |
|---|---|
| football league | "The schedule grid is for basketball leagues: an NFL team plays once a week." |
| nothing stored, league not complete | "The {season} NBA schedule hasn't been loaded yet. It loads with the league's next refresh." |
| nothing stored, league complete (*added after code review R1*: completed seasons are never refreshed again) | "The {season} NBA schedule wasn't saved for this season: it finished before the app started storing schedules." |

## C2. `GET /api/leagues/{sleeperLeagueId}/next-matchup`

### 200, both sports

```json
{
  "sport": "nba",
  "season": 2026,
  "week": 1,
  "available": false,
  "reason": "Pairings for week 1 aren't out yet. Sleeper publishes them shortly before the week starts.",
  "me": null,
  "opponent": null
}
```

`me` and `opponent` are `MatchupSide | null`:

```json
{ "rosterId": 4, "teamName": "Dunk Tank", "username": "popsharky", "avatarId": "abc123" }
```

`teamName`, `username` and `avatarId` are each nullable (a roster with no owner, or no
team name set). The full state table is in [data-model.md](../data-model.md#next-matchup-nextmatchupservice).
`week` is null only when `leg` is absent.

*Amended after review:* the league row is the URL's, never the resolver's (F1). The
bye rule, the team-name source (`league_member`) and the two-rosters rule (lowest
roster id) are in data-model (F10).

## TypeScript mirror (`web/src/api.ts`)

```ts
export type ScheduleWeek = { week: number; firstDate: string | null; lastDate: string | null }
export type ScheduleTeam = { team: string; games: number[]; seasonTotal: number }
export type PlayoffWindow = { startWeek: number | null; endWeek: number | null; reason: string | null }
export type LeagueSchedule = {
  sport: Sport; season: number; available: boolean; reason: string | null
  fetchedAt: string | null; currentWeek: number | null
  lastLeagueWeek: number | null; seasonOver: boolean   // added after review (F2)
  weeks: ScheduleWeek[]; playoff: PlayoffWindow; teams: ScheduleTeam[]
  excluded: { postponed: number; canceled: number; exhibition: number }
}
export type MatchupSide = { rosterId: number; teamName: string | null; username: string | null; avatarId: string | null }
export type NextMatchup = {
  sport: Sport; season: number; week: number | null; available: boolean; reason: string | null
  me: MatchupSide | null; opponent: MatchupSide | null
}
```

## Invariants (tested)

- `teams[i].games.length === weeks.length` for every i.
- No team appears whose every game is postponed/canceled (2025 fixture: no STP/STR).
- *Amended 2026-10-07 (found in production):* the status rule isn't enough. Sleeper's 2024
  schedule keeps the All-Star final (CHK vs SHQ) as `complete`. A side with fewer than a quarter
  of the median team's counted games is an exhibition team, and its games go to
  `excluded.exhibition` (2024: 1). The 2025 fixture passed only because its All-Star game was
  `canceled`.
- `playoff.reason === null` ⇔ `playoff.endWeek !== null`.
- `next-matchup` never returns `opponent` without `me`.
- *Added after review:* `seasonOver === true` when the league is complete (2025 fixture,
  `leg` 21). For NBA 2026 the response's `season` is 2026 even though 2025 is the
  newest season with scores (F1). The client looks columns up by `week`, not by index
  offset (N5).
- Neither response contains `/api/ingest` or `POST /api/` in any `reason`
  (`NoIngestHintsInMessagesTest`). The "hasn't been loaded" reason deliberately
  doesn't tell the reader to call an ingest route.
