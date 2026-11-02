# API Contract: Historical Draft Game (spec 053)

> **Amended after review (2026-11-02)**:
> - The import is async. `POST /api/admin/historical/import` returns 202 with
>   `{ "jobId" }`, and `GET /api/admin/historical/import/{jobId}` returns
>   `{ status: RUNNING|DONE|FAILED, report }` with the report shape below (F14).
> - Standings rows gain `"ties"` (F16).
> - `season` gains `"statCoverage": {"ast": 0.946, ...}` and `"settingsSource":
>   "App standard 12-team NBA roster · Legacy Ball Knowers scoring"` (FR-007, F13).
> - Card `prior` gains `"bestOfTwo": "1994-95"`, naming which season the ranking
>   used (F6). `fantasyPoints` excludes bonuses and is labelled so (F12).
> - `hindsight` gains `"userBeatIt": bool` (F4).
> - `biggestBust` uses `seasonValueRank − pickNo` (F1).
> - A missing `X-Sleeper-User` returns 401 (F18).

Every user endpoint takes the `X-Sleeper-User` header, exactly like `/api/mocks`
(`api/MockDraftController.java`). A missing header → 401. Another user's game →
404 (the same collapse `MockDraftService.get` uses).

Every type below is mirrored field-for-field in `web/src/api.ts` in the same
change (constitution rule 2). No `Map.of` in any response path: `age`, `team`,
`prior` and `result` are nullable (constitution rule 4).

## `GET /api/history/seasons`
The offered seasons, for the "about this game" panel.
```json
[{ "seasonStart": 1995, "label": "1995-96", "source": "KAGGLE",
   "countedStats": ["pts","reb","ast","stl","blk","to","tpm","dd","td","bonus_pt_40p", "..."],
   "excludedStats": { "tf": "not recorded in historical box scores", "ff": "..." },
   "rankingSource": "PRIOR_SEASON_POINTS" }]
```

## `POST /api/history/games`
Starts a game: random season, random slot unless given.
Request: `{ "userSlot": 5 }`. Optional, 1..12.
Response 201: `HistoricalGameState`, with bots already advanced to the user's first turn.

## `GET /api/history/games`
The caller's games, newest first:
`[{ "id", "seasonLabel", "status", "userSlot", "finish": 3 | null, "createdAt" }]`

## `GET /api/history/games/{id}` → `HistoricalGameState`

## `POST /api/history/games/{id}/pick`
`{ "playerId": 123 }`. 409 if it isn't the user's turn or the player is taken.
→ `HistoricalGameState`, advanced through the bots. If this was the last pick,
the state comes back `COMPLETE` with `result`.

## `POST /api/history/games/{id}/auto`
`{ "scope": "PICK" | "FINISH" }`. Same meaning as the mock room.

## `POST /api/history/games/{id}/abandon` → 204

## `HistoricalGameState`
```json
{
  "id": 1, "status": "DRAFTING",
  "season": { "label": "1995-96", "countedStats": [...], "excludedStats": {...},
              "rankingSource": "PRIOR_SEASON_POINTS", "rankingLabel": "ranked by last season's points" },
  "teams": 12, "rounds": 14, "rosterPositions": [...], "userSlot": 5,
  "myPicks": [5, 20, ...], "currentPickNo": 5, "onTheClockSlot": 5, "isUsersTurn": true,
  "picks": [{ "pickNo": 1, "round": 1, "draftSlot": 1, "seatType": "BOT", "player": HistoricalPlayerCard }],
  "available": [HistoricalPlayerCard],
  "result": null
}
```
`HistoricalPlayerCard`:
`{ "id", "name", "positions": ["PG","SG"], "team": "ORL" | null, "age": 24 | null,
   "rank": 3, "positionalRank": 1,
   "prior": { "seasonLabel": "1994-95", "games": 79, "perGame": {"pts": 29.3, ...}, "fantasyPoints": 3010.5 } | null }`

**Nothing from the drafted season appears in this card or anywhere else in the
state while `status` = `DRAFTING`** (FR-003). The drafted season's team is not
shown either: `team` is the prior season's team.

## `result` (present only when `COMPLETE`, frozen)
```json
{
  "weeks": [{ "week": 1, "startDate": "1995-11-06",
              "matchups": [{ "homeSlot": 1, "awaySlot": 7, "homePoints": 401.5, "awayPoints": 388.0 }] }],
  "standings": [{ "slot": 5, "wins": 15, "losses": 9, "pointsFor": 9876.5, "place": 3 }],
  "userFinish": 3,
  "lineups": { "5": [{ "week": 1, "starters": [{ "playerId", "slot": "PG", "points": 52.5, "gameDate": "1995-11-07" }] }] },
  "hindsight": { "label": "greedy hindsight, bots unchanged", "pointsFor": 11020.0, "userPointsFor": 9876.5,
                 "picks": [{ "pickNo": 5, "player": HistoricalPlayerCard, "seasonValue": 1234.5 }] },
  "biggestBust": { "pickNo": 20, "player": ..., "seasonValueRank": 88 } | null,
  "bestStealPassedOn": { "pickNo": 44, "taken": ..., "takenValue": 600.0, "passedOn": ..., "passedOnValue": 1100.0 } | null
}
```
`gameDate` on each starter shows *which* game was locked. That is the hand-check
hook for SC-003.

## Admin: `POST /api/admin/historical/import`
Admin-only (`config/AdminAccess`). Body `{ "part": "KAGGLE" | "SLEEPER" | "ALL" }`.
Idempotent: it replaces rows per season inside one transaction per season.

The response is the import report:
```json
{ "seasons": [{ "seasonStart": 1970, "source": "KAGGLE", "games": 697,
                "playerSeasons": 229, "countedStats": [...] }],
  "unmatchedFantasyRelevant": [], "uncoveredCollisions": [] }
```
The import refuses to commit a season with non-empty `unmatchedFantasyRelevant` or
`uncoveredCollisions` (research R5). It returns 422 with the lists.
