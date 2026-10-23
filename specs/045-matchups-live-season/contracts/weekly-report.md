# Contract: `GET /api/leagues/{sleeperId}/weekly-report/{week}` (changed)

Scoping, 404 rules and every existing field are unchanged. Added on **every** shape:

```json
{ "live": false, "liveWeek": null, "sleeperLeagueId": "1339351318115946496" }
```

## Which week a request answers (week ≤ 0 means "default")

| Season state | `week` param | Answer |
|---|---|---|
| A live week L exists, L not stored, nothing stored | ≤ 0 | live L (`live: true`) |
| Any | = L, L not stored | live L |
| A live week L exists, some weeks stored | ≤ 0 | today's default (latest final, else latest stored) + `liveWeek: L` |
| No live week | any | today's behaviour, `liveWeek: null` |

## Live shape (example, NBA week 1, 2026-10-23)

```json
{
  "available": true, "live": true, "liveWeek": 1, "season": 2026, "requestedSeason": null,
  "sleeperLeagueId": "1339351318115946496", "week": 1,
  "latestScoredWeek": 0, "latestFinalWeek": 0, "weekFinal": false,
  "sport": "nba", "playersPlayMultiplePerPeriod": true,
  "matchups": [ { "home": { "rosterId": 1, "teamName": "...", "record": "0-0", "points": 201.5, "isMe": false, "...": "..." },
                  "away": { "rosterId": 5, "points": 192.5, "...": "..." } } ],
  "bestNights": [], "bestWeek": [], "basis": "ALL_GAMES_PLAYED", "sectionsUnavailable": [],
  "awards": [], "awardsOmitted": []
}
```

- `record` is the record **before** this week.
- Rosters with a null `matchup_id` are left out, as on stored weeks.

## Live read failed

```json
{ "available": false, "live": false, "liveWeek": 1, "season": 2026, "requestedSeason": null,
  "reason": "live scores unavailable from Sleeper right now", "week": 1, "...": "..." }
```

It never falls back to last season.
