# Backend records as built (spec 074, Phase 2)

The final Java signature of every new or changed record on a JSON path, in field order, which is what `web/src/api.ts` mirrors. Package `com.ballknowers.draftsim.historical` unless stated. Where this file and the plan's "Data model, amended after review" differ, this file is what was built; the differences are listed under "Where this differs from the plan".

Everything below the signatures is a real response from the built backend (port 8081, database `draftsim_074`, NBA), with long arrays cut to the first one or two entries and a `"... N more"` marker. Football has **no** real example: no NFL season was imported locally, so no football response was captured.

## Public routes (`/api/history/public/**`, no identity header is read)

| Route | 200 body | Other |
|---|---|---|
| `GET /seasons?sport=NBA\|NFL` | `SeasonList` | 400 missing, blank or unknown sport. `Cache-Control: public, max-age=3600` |
| `GET /seasons/{sport}/{slug}` | `SeasonPage` | 404 unknown sport, unknown season, a bare year, an unoffered season. Sport is case-insensitive. `Cache-Control: public, max-age=3600` |
| `GET /daily/yesterday` | `YesterdayDaily[]`, one per sport, registry order (NBA, NFL) | Read-only: never deals a puzzle. `Cache-Control: no-store` |
| `GET /games/{id}` | `PublicGame` | 404 unless COMPLETE (drafting, abandoned and missing are one answer, an empty body). `Cache-Control: no-store` |

## Records

```java
// SeasonPage.java
public record SeasonPage(String sport, int seasonStart, String label, String slug, String prevSlug /*nullable*/,
        String nextSlug /*nullable*/, int teams, int rounds, List<String> rosterPositions, String scoringNote,
        String pointsNote, String rankingLabel, List<String> dataNotes /*never null*/,
        Map<String, String> excludedStats, Map<String, Double> statCoverage, String attribution /*nullable: NBA*/,
        int boardPlayers, int rankedPlayers, List<Finisher> topOverall /*12*/,
        Map<String, List<Finisher>> topByPosition /*<=5 each, keyed by PRIMARY position, sport position order*/,
        List<Mover> busts /*<=5*/, List<Mover> steals /*<=5*/, int unrankedLeftOut,
        List<DreamPlayer> dreamRoster /*rounds*/, double dreamRosterPoints)

public record SeasonPage.Finisher(long playerId, String name, List<String> positions, String team /*nullable*/,
        int finishRank, double points, int weeksPlayed, Integer goingInRank /*nullable: unranked*/)

public record SeasonPage.Mover(long playerId, String name, List<String> positions, String team /*nullable*/,
        int goingInRank, int finishRank, double points, int weeksPlayed)

public record SeasonPage.DreamPlayer(long playerId, String name, List<String> positions, double points)

// SeasonList.java
public record SeasonList(int schema /*= 1*/, String sport, List<SeasonRef> seasons)
public record SeasonList.SeasonRef(String sport, int seasonStart, String label, String slug /*== label*/)

// HistoricalDailyService.java
public record HistoricalDailyService.YesterdayDaily(String sport, String date /*ISO*/, String seasonLabel /*nullable*/,
        String slug /*nullable; == seasonLabel*/)

// PublicGame.java
public record PublicGame(HistoricalGameState state, String reducedUntil /*nullable ISO-8601 instant*/)

// ClaimResult.java
public record ClaimResult(int moved, int leftBehind)

// HistoricalGameState.java (chosenSeason appended LAST)
public record HistoricalGameState(long id, String sport, String status, SeasonView season, int teams, int rounds,
        List<String> rosterPositions, int userSlot, List<Integer> myPicks, int currentPickNo, Integer onTheClockSlot,
        boolean isUsersTurn, List<PickView> picks, List<HistoricalPlayerCard> available, JsonNode result,
        int reversalRound, String dailyDate, boolean chosenSeason)

// HistoricalGameState.GameSummary (chosenSeason appended LAST)
public record GameSummary(long id, String sport, String seasonLabel, String status, int userSlot, Integer finish,
        Integer formatVersion, String outcome, OffsetDateTime createdAt, Integer draftScore, String dailyDate,
        boolean chosenSeason)

// HistoricalAchievementService.java (notCounted appended LAST)
public record AwardView(long gameId, boolean settled, boolean fromSavedResult, int xp, List<XpLine> lines,
        List<BadgeView> badges, List<Moment> moments, boolean momentsSeen, int total, boolean notCounted)

// api/HistoricalGameController.java
public record CreateRequest(String sport, Integer userSlot, Integer seasonStart /*nullable: random season*/)
```

### The error body carrying `GUEST_LIMIT`

`ErrorHandler` answers `GuestLimitException` with **403** and, in this controller's existing shape (`error` holds the sentence the page may show), plus a `code`. It is the `{"error": ...}` family, not the lock's `{"code","message"}` family, because that is what the history controllers already return for their 400 and 409. A `LinkedHashMap`, never `Map.of`:

```json
{"error": "<sentence>", "code": "GUEST_LIMIT"}
```

`web/src/api.ts`'s `ApiError` should read `code` from this body. Other 4xx bodies from these routes still have no `code`.

### Other behaviour the web needs to know

- **Identity.** `X-Sleeper-User` wins; else `X-Guest-Id` (a UUID, any case, stored lowercase as `guest:<uuid>`); else 401. A Sleeper header starting with `guest:` (any case) is 400. Blank counts as absent.
- **`POST /api/history/games`** body `{sport, userSlot?, seasonStart?}`. With `seasonStart`: that season, `chosenSeason: true`; 400 if the season is not in the sport's offered list or outside its configured first..last range. A guest may do this while they own no non-daily game in any status (an abandoned one still counts), else 403 `GUEST_LIMIT`. A guest without `seasonStart` (a random season): 403 `GUEST_LIMIT`. `GET /seasons`, `/games`, `/games/{id}`, `pick`, `auto`, `abandon`, `daily` accept a guest.
- **Achievements routes** (`/profile`, `/games/{id}/award`, `/profile/seen`) with only a guest id: 403 `GUEST_LIMIT` (after the 401 check: no identity at all is still 401). `award` for a chosen-season game: 200 with `settled: false` and `notCounted: true`; every other field empty or zero.
- **`POST /api/history/claim`**: both headers required and well formed, else 400. Returns `ClaimResult`. Idempotent. `leftBehind` is the number of games still under the guest key afterwards (a daily game the Sleeper id already played that day).
- **The reduced public game.** While the finished game's `daily_date` is today in `America/New_York`, `reducedUntil` is the next midnight there (ISO-8601 instant) and: `state.picks` is `[]` (as `available` already is for any finished game); `state.result` holds only `userFinish`, `userOutcome`, `format`, `standings` (the player's own row only), `hindsight` (`label`, `pointsFor`, `userPointsFor`, `userBeatIt`, and `picks: []`), `pickFinishes` (each row only `pickNo`, `position`, `draftedPositionRank`, `finishPositionRank`, `positionPlayers`: no `player`, `seasonValue` or `weeksPlayed`; the key is absent for a result stored before spec 065), `weeks: []`, `lineups: {}`, `finalPlaces: []`. `biggestBust`, `bestStealPassedOn`, `playoffs` and `hindsightSeason` are absent. The rest of `state` (season disclosure, `userSlot`, `myPicks`, `dailyDate`, `chosenSeason`) is as for the full game. From the next day `reducedUntil` is `null` and the state is the full one. No response carries an owner, Sleeper or guest id (the state record has no such field).
- **`topByPosition` rows:** `finishRank` is the rank WITHIN that position (the number a game reports as `finishPositionRank`: 1 + how many at that position scored strictly more); `goingInRank` stays the overall board rank. In `topOverall`, `busts` and `steals`, `finishRank` is the overall rank among every board player (1 + how many scored strictly more; level scores share a rank).

## Where this differs from the plan

1. `Finisher.finishRank` means two things depending on the list (overall in `topOverall`; within the position in `topByPosition`). The plan's record has no second field; T018's amendment says each `topByPosition` rank equals the game's `finishPositionRank`, so the position rows carry that. If the page wants both numbers, `Finisher` needs a `positionFinishRank` (PR-15 suggested it); that is a record change on both sides.
2. `HistoricalDailyService.YesterdayDaily` and `SeasonList.SeasonRef` and `SeasonPage.{Finisher,Mover,DreamPlayer}` are nested records; the JSON is identical to top-level ones.
3. `YesterdayDaily` has `slug` equal to `seasonLabel`; both null together.
4. `PublicGame` is the plan's `(state, reducedUntil?)`. In the reduced view `state.result` is a pruned copy of the stored result, not a different type.
5. `GuestLimitException` is answered with the controller's existing `{"error"}` shape plus `code`, not the lock's `{"code","message"}` shape (contracts/public-api.md allowed either; this controller's other errors use `error`).

## Real examples (NBA, from the running server)

### `GET /api/history/public/seasons?sport=NBA`

```json
{
 "status": 200,
 "cacheControl": "public, max-age=3600",
 "body": {
  "schema": 1,
  "sport": "NBA",
  "seasons": [
   {
    "sport": "NBA",
    "seasonStart": 1970,
    "label": "1970-71",
    "slug": "1970-71"
   },
   {
    "sport": "NBA",
    "seasonStart": 1971,
    "label": "1971-72",
    "slug": "1971-72"
   },
   "... 45 more"
  ]
 }
}
```

### `GET /api/history/public/seasons/NBA/2016-17`

```json
{
 "status": 200,
 "cacheControl": "public, max-age=3600",
 "body": {
  "sport": "NBA",
  "seasonStart": 2016,
  "label": "2016-17",
  "slug": "2016-17",
  "prevSlug": "2015-16",
  "nextSlug": null,
  "teams": 12,
  "rounds": 14,
  "rosterPositions": [
   "PG",
   "SG",
   "... 12 more"
  ],
  "scoringNote": "App standard 12-team NBA roster \u00b7 Legacy Ball Knowers scoring",
  "pointsNote": "Points are fantasy points under this season's scoring, counting only each player's best single game of each week and adding the weeks up. That is a lock-in best-ball measure, not the sum of every game he played.",
  "rankingLabel": "ranked by last season's points, rookies by NBA draft pick",
  "dataNotes": [],
  "excludedStats": {
   "tf": "not recorded in historical box scores",
   "ff": "not recorded in historical box scores"
  },
  "statCoverage": {
   "pts": 1.0,
   "reb": 1.0,
   "ast": 1.0,
   "stl": 1.0,
   "blk": 1.0,
   "to": 1.0,
   "tpm": 1.0
  },
  "attribution": null,
  "boardPlayers": 486,
  "rankedPlayers": 433,
  "topOverall": [
   {
    "playerId": 33795,
    "name": "Russell Westbrook",
    "positions": [
     "PG"
    ],
    "team": "OKC",
    "finishRank": 1,
    "points": 1185.0,
    "weeksPlayed": 25,
    "goingInRank": 2
   },
   {
    "playerId": 33995,
    "name": "James Harden",
    "positions": [
     "SG"
    ],
    "team": "HOU",
    "finishRank": 2,
    "points": 1107.0,
    "weeksPlayed": 25,
    "goingInRank": 4
   },
   "... 10 more"
  ],
  "topByPosition": {
   "PG": [
    {
     "playerId": 33795,
     "name": "Russell Westbrook",
     "positions": [
      "PG"
     ],
     "team": "OKC",
     "finishRank": 1,
     "points": 1185.0,
     "weeksPlayed": 25,
     "goingInRank": 2
    },
    {
     "playerId": 35676,
     "name": "Giannis Antetokounmpo",
     "positions": [
      "PG"
     ],
     "team": "MIL",
     "finishRank": 2,
     "points": 958.0,
     "weeksPlayed": 25,
     "goingInRank": 25
    },
    "... 3 more"
   ],
   "SG": [
    {
     "playerId": 33995,
     "name": "James Harden",
     "positions": [
      "SG"
     ],
     "team": "HOU",
     "finishRank": 1,
     "points": 1107.0,
     "weeksPlayed": 25,
     "goingInRank": 4
    },
    {
     "playerId": 34784,
     "name": "Jimmy Butler",
     "positions": [
      "SG"
     ],
     "team": "CHI",
     "finishRank": 2,
     "points": 839.0,
     "weeksPlayed": 25,
     "goingInRank": 24
    },
    "... 3 more"
   ],
   "SF": [
    {
     "playerId": 31303,
     "name": "LeBron James",
     "positions": [
      "SF"
     ],
     "team": "CLE",
     "finishRank": 1,
     "points": 934.0,
     "weeksPlayed": 24,
     "goingInRank": 7
    },
    {
     "playerId": 34986,
     "name": "Kawhi Leonard",
     "positions": [
      "SF"
     ],
     "team": "SAS",
     "finishRank": 2,
     "points": 786.5,
     "weeksPlayed": 25,
     "goingInRank": 18
    },
    "... 3 more"
   ],
   "PF": [
    {
     "playerId": 35350,
     "name": "Draymond Green",
     "positions": [
      "PF"
     ],
     "team": "GSW",
     "finishRank": 1,
     "points": 784.5,
     "weeksPlayed": 25,
     "goingInRank": 10
    },
    {
     "playerId": 32746,
     "name": "Paul Millsap",
     "positions": [
      "PF"
     ],
     "team": "ATL",
     "finishRank": 2,
     "points": 673.0,
     "weeksPlayed": 24,
     "goingInRank": 14
    },
    "... 3 more"
   ],
   "C": [
    {
     "playerId": 35290,
     "name": "Anthony Davis",
     "positions": [
      "C"
     ],
     "team": "NOP",
     "finishRank": 1,
     "points": 1014.0,
     "weeksPlayed": 24,
     "goingInRank": 6
    },
    {
     "playerId": 34358,
     "name": "DeMarcus Cousins",
     "positions": [
      "C"
     ],
     "team": "SAC",
     "finishRank": 2,
     "points": 976.0,
     "weeksPlayed": 24,
     "goingInRank": 3
    },
    "... 3 more"
   ]
  },
  "busts": [
   {
    "playerId": 35482,
    "name": "Khris Middleton",
    "positions": [
     "SG"
    ],
    "team": "MIL",
    "goingInRank": 54,
    "finishRank": 309,
    "points": 203.5,
    "weeksPlayed": 10
   },
   {
    "playerId": 34978,
    "name": "Brandon Knight",
    "positions": [
     "SG"
    ],
    "team": "PHO",
    "goingInRank": 53,
    "finishRank": 274,
    "points": 255.5,
    "weeksPlayed": 17
   },
   "... 3 more"
  ],
  "steals": [
   {
    "playerId": 36043,
    "name": "Dennis Schr\u00f6der",
    "positions": [
     "PG"
    ],
    "team": "ATL",
    "goingInRank": 169,
    "finishRank": 57,
    "points": 579.5,
    "weeksPlayed": 25
   },
   {
    "playerId": 37066,
    "name": "Myles Turner",
    "positions": [
     "C"
    ],
    "team": "IND",
    "goingInRank": 142,
    "finishRank": 30,
    "points": 674.0,
    "weeksPlayed": 25
   },
   "... 3 more"
  ],
  "unrankedLeftOut": 0,
  "dreamRoster": [
   {
    "playerId": 33795,
    "name": "Russell Westbrook",
    "positions": [
     "PG"
    ],
    "points": 1185.0
   },
   {
    "playerId": 33995,
    "name": "James Harden",
    "positions": [
     "SG"
    ],
    "points": 1107.0
   },
   "... 12 more"
  ],
  "dreamRosterPoints": 9243.0
 }
}
```

### `GET /api/history/public/seasons/NBA/1950-51`

```json
{
 "status": 404
}
```

### `GET /api/history/public/daily/yesterday`

```json
{
 "status": 200,
 "cacheControl": "no-store",
 "body": [
  {
   "sport": "NBA",
   "date": "2026-11-16",
   "seasonLabel": "2001-02",
   "slug": "2001-02"
  },
  {
   "sport": "NFL",
   "date": "2026-11-16",
   "seasonLabel": null,
   "slug": null
  }
 ]
}
```

### `POST /api/history/games (guest, seasonStart 2016) -> 201`

```json
{
 "status": 201,
 "body": {
  "id": 455,
  "sport": "NBA",
  "status": "DRAFTING",
  "season": {
   "label": "2016-17",
   "countedStats": [
    "pts",
    "... 12 more"
   ],
   "excludedStats": {
    "tf": "not recorded in historical box scores",
    "ff": "not recorded in historical box scores"
   },
   "statCoverage": {
    "pts": 1.0,
    "reb": 1.0,
    "ast": 1.0,
    "stl": 1.0,
    "blk": 1.0,
    "to": 1.0,
    "tpm": 1.0
   },
   "rankingSource": "PRIOR_SEASON_POINTS",
   "rankingLabel": "ranked by last season's points, rookies by NBA draft pick",
   "settingsSource": "App standard 12-team NBA roster \u00b7 Legacy Ball Knowers scoring",
   "dataNotes": [],
   "attribution": null
  },
  "teams": 12,
  "rounds": 14,
  "rosterPositions": [
   "PG",
   "... 13 more"
  ],
  "userSlot": 5,
  "myPicks": [
   5,
   "... 13 more"
  ],
  "currentPickNo": 5,
  "onTheClockSlot": 5,
  "isUsersTurn": true,
  "picks": [
   {
    "pickNo": 1,
    "round": 1,
    "draftSlot": 1,
    "seatType": "BOT",
    "player": {
     "id": 33918,
     "name": "Stephen Curry",
     "positions": [
      "PG"
     ],
     "team": "GSW",
     "age": 27,
     "rank": 1,
     "positionalRank": 1,
     "prior": {
      "seasonLabel": "2015-16",
      "games": 79,
      "perGame": {
       "pts": 30.1,
       "reb": 5.4,
       "ast": 6.7,
       "stl": 2.1,
       "blk": 0.2,
       "to": 3.3,
       "tpm": 5.1
      },
      "fantasyPoints": 2451.5,
      "bestOfTwo": "2015-16",
      "totals": {}
     },
     "rookiePick": null,
     "rookie": false
    }
   },
   "... 3 more"
  ],
  "available": [
   {
    "id": 34358,
    "name": "DeMarcus Cousins",
    "positions": [
     "C"
    ],
    "team": "SAC",
    "age": 25,
    "rank": 3,
    "positionalRank": 1,
    "prior": {
     "seasonLabel": "2015-16",
     "games": 65,
     "perGame": {
      "pts": 26.9,
      "reb": 11.5,
      "ast": 3.3,
      "stl": 1.6,
      "blk": 1.4,
      "to": 3.8,
      "tpm": 1.1
     },
     "fantasyPoints": 2007.0,
     "bestOfTwo": "2015-16",
     "totals": {}
    },
    "rookiePick": null,
    "rookie": false
   },
   "... 481 more"
  ],
  "result": null,
  "reversalRound": 0,
  "dailyDate": null,
  "chosenSeason": true
 }
}
```

### `POST /api/history/games (same guest, second chosen season) -> GUEST_LIMIT`

```json
{
 "status": 403,
 "body": {
  "error": "You have used your one season of choice. Sign in to draft any season you like, or play the game of the day.",
  "code": "GUEST_LIMIT"
 }
}
```

### `POST /api/history/games (guest, random season) -> GUEST_LIMIT`

```json
{
 "status": 403,
 "body": {
  "error": "Sign in to draft a random season. Without signing in you can play the game of the day, or one season you choose.",
  "code": "GUEST_LIMIT"
 }
}
```

### `GET /api/history/profile (guest) -> GUEST_LIMIT`

```json
{
 "status": 403,
 "body": {
  "error": "Sign in to earn achievements.",
  "code": "GUEST_LIMIT"
 }
}
```

### `GET /api/history/games (guest) item`

```json
{
 "status": 200,
 "body": [
  {
   "id": 455,
   "sport": "NBA",
   "seasonLabel": "2016-17",
   "status": "COMPLETE",
   "userSlot": 5,
   "finish": 1,
   "formatVersion": 2,
   "outcome": "CHAMPION",
   "createdAt": "2026-11-17T16:11:56.783878Z",
   "draftScore": 88,
   "dailyDate": null,
   "chosenSeason": true
  }
 ]
}
```

### `GET /api/history/public/games/{id} (finished, free play => full)`

```json
{
 "status": 200,
 "cacheControl": "no-store",
 "body": {
  "state": {
   "id": 455,
   "sport": "NBA",
   "status": "COMPLETE",
   "season": {
    "label": "2016-17",
    "countedStats": [
     "pts",
     "... 12 more"
    ],
    "excludedStats": {
     "tf": "not recorded in historical box scores",
     "ff": "not recorded in historical box scores"
    },
    "statCoverage": {
     "pts": 1.0,
     "reb": 1.0,
     "ast": 1.0,
     "stl": 1.0,
     "blk": 1.0,
     "to": 1.0,
     "tpm": 1.0
    },
    "rankingSource": "PRIOR_SEASON_POINTS",
    "rankingLabel": "ranked by last season's points, rookies by NBA draft pick",
    "settingsSource": "App standard 12-team NBA roster \u00b7 Legacy Ball Knowers scoring",
    "dataNotes": [],
    "attribution": null
   },
   "teams": 12,
   "rounds": 14,
   "rosterPositions": [
    "PG",
    "... 13 more"
   ],
   "userSlot": 5,
   "myPicks": [
    5,
    "... 13 more"
   ],
   "currentPickNo": 169,
   "onTheClockSlot": null,
   "isUsersTurn": false,
   "picks": [
    {
     "pickNo": 1,
     "round": 1,
     "draftSlot": 1,
     "seatType": "BOT",
     "player": {
      "id": 33918,
      "name": "Stephen Curry",
      "positions": [
       "PG"
      ],
      "team": "GSW",
      "age": 27,
      "rank": 1,
      "positionalRank": 1,
      "prior": {
       "seasonLabel": "2015-16",
       "games": 79,
       "perGame": {
        "pts": 30.1,
        "reb": 5.4,
        "ast": 6.7,
        "stl": 2.1,
        "blk": 0.2,
        "to": 3.3,
        "tpm": 5.1
       },
       "fantasyPoints": 2451.5,
       "bestOfTwo": "2015-16",
       "totals": {}
      },
      "rookiePick": null,
      "rookie": false
     }
    },
    "... 167 more"
   ],
   "available": [],
   "result": {
    "weeks": [
     {
      "week": 1,
      "phase": "REGULAR",
      "matchups": [
       {
        "awaySlot": 9,
        "homeSlot": 6,
        "awayPoints": 242.5,
        "homePoints": 266.5
       },
       "... 5 more"
      ],
      "startDate": "2016-10-24"
     },
     "... 21 more"
    ],
    "format": {
     "version": 2,
     "shortened": false,
     "seasonWeeks": 25,
     "unusedWeeks": 3,
     "playoffWeeks": [
      20,
      "... 2 more"
     ],
     "regularSeasonWeeks": 19,
     "configuredRegularSeasonWeeks": 19
    },
    "lineups": {
     "5": [
      {
       "week": 1,
       "starters": [
        {
         "slot": "PG",
         "points": 20.0,
         "gameDate": "2016-10-28",
         "playerId": 36504
        },
        "... 8 more"
       ]
      },
      "... 20 more"
     ]
    },
    "playoffs": {
     "seeds": [
      {
       "seed": 1,
       "slot": 5
      },
      "... 5 more"
     ],
     "rounds": [
      {
       "name": "ROUND_ONE",
       "week": 20,
       "round": 1,
       "byeSlots": [
        5,
        "... 1 more"
       ],
       "matchups": [
        {
         "lowSeed": 6,
         "lowSlot": 4,
         "highSeed": 3,
         "highSlot": 10,
         "lowPoints": 240.5,
         "highPoints": 266.0,
         "winnerSlot": 10,
         "decidedBySeed": false
        },
        "... 1 more"
       ]
      },
      "... 2 more"
     ],
     "championSlot": 5
    },
    "hindsight": {
     "label": "greedy hindsight, bots unchanged",
     "picks": [
      {
       "pickNo": 5,
       "player": {
        "id": 31303,
        "age": 31,
        "name": "LeBron James",
        "rank": 7,
        "team": "CLE",
        "prior": {
         "games": 76,
         "totals": {},
         "perGame": {
          "to": 3.3,
          "ast": 6.8,
          "blk": 0.6,
          "pts": 25.3,
          "reb": 7.4,
          "stl": 1.4,
          "tpm": 1.1
         },
         "bestOfTwo": "2015-16",
         "seasonLabel": "2015-16",
         "fantasyPoints": 2139.5
        },
        "rookie": false,
        "positions": [
         "SF"
        ],
        "rookiePick": null,
        "positionalRank": 2
       },
       "seasonValue": 934.0
      },
      "... 13 more"
     ],
     "pointsFor": 7235.0,
     "userBeatIt": false,
     "userPointsFor": 6424.5
    },
    "standings": [
     {
      "slot": 5,
      "ties": 0,
      "wins": 14,
      "place": 1,
      "losses": 5,
      "pointsFor": 4891.5
     },
     "... 11 more"
    ],
    "userFinish": 1,
    "biggestBust": {
     "pickNo": 53,
     "player": {
      "id": 32606,
      "age": 29,
      "name": "Rudy Gay",
      "rank": 51,
      "team": "SAC",
      "prior": {
       "games": 70,
       "totals": {},
       "perGame": {
        "to": 2.0,
        "ast": 1.7,
        "blk": 0.7,
        "pts": 17.2,
        "reb": 6.5,
        "stl": 1.4,
        "tpm": 1.1
       },
       "bestOfTwo": "2015-16",
       "seasonLabel": "2015-16",
       "fantasyPoints": 1378.0
      },
      "rookie": false,
      "positions": [
       "SF"
      ],
      "rookiePick": null,
      "positionalRank": 8
     },
     "seasonValue": 305.5,
     "seasonValueRank": 145
    },
    "finalPlaces": [
     {
      "slot": 5,
      "place": 1,
      "outcome": "CHAMPION"
     },
     "... 11 more"
    ],
    "userOutcome": "CHAMPION",
    "pickFinishes": [
     {
      "pickNo": 5,
      "player": {
       "id": 34358,
       "age": 25,
       "name": "DeMarcus Cousins",
       "rank": 3,
       "team": "SAC",
       "prior": {
        "games": 65,
        "totals": {},
        "perGame": {
         "to": 3.8,
         "ast": 3.3,
         "blk": 1.4,
         "pts": 26.9,
         "reb": 11.5,
         "stl": 1.6,
         "tpm": 1.1
        },
        "bestOfTwo": "2015-16",
        "seasonLabel": "2015-16",
        "fantasyPoints": 2007.0
       },
       "rookie": false,
       "positions": [
        "C"
       ],
       "rookiePick": null,
       "positionalRank": 1
      },
      "position": "C",
      "seasonValue": 976.0,
      "weeksPlayed": 24,
      "positionPlayers": 89,
      "finishPositionRank": 2,
      "draftedPositionRank": 1
     },
     "... 13 more"
    ],
    "hindsightSeason": {
     "ties": 0,
     "wins": 18,
     "finish": 1,
     "losses": 1,
     "outcome": "CHAMPION",
     "pointsFor": 5443.0,
     "takenFromBots": 10,
     "regularSeasonRank": 1
    },
    "bestStealPassedOn": {
     "taken": {
      "id": 34802,
      "age": 27,
      "name": "Norris Cole",
      "rank": 161,
      "team": "NOP",
      "prior": {
       "games": 45,
       "totals": {},
       "perGame": {
        "to": 1.7,
        "ast": 3.7,
        "blk": 0.1,
        "pts": 10.6,
        "reb": 3.4,
        "stl": 0.8,
        "tpm": 0.7
       },
       "bestOfTwo": "2015-16",
       "seasonLabel": "2015-16",
       "fantasyPoints": 582.0
      },
      "rookie": false,
      "positions": [
       "PG"
      ],
      "rookiePick": null,
      "positionalRank": 33
     },
     "pickNo": 164,
     "passedOn": {
      "id": 36043,
      "age": 22,
      "name": "Dennis Schr\u00f6der",
      "rank": 169,
      "team": "ATL",
      "prior": {
       "games": 80,
       "totals": {},
       "perGame": {
        "to": 2.3,
        "ast": 4.4,
        "blk": 0.1,
        "pts": 11.0,
        "reb": 2.6,
        "stl": 0.9,
        "tpm": 1.0
       },
       "bestOfTwo": "2015-16",
       "seasonLabel": "2015-16",
       "fantasyPoints": 1006.0
      },
      "rookie": false,
      "positions": [
       "PG"
      ],
      "rookiePick": null,
      "positionalRank": 34
     },
     "takenValue": 36.5,
     "passedOnValue": 579.5
    }
   },
   "reversalRound": 0,
   "dailyDate": null,
   "chosenSeason": true
  },
  "reducedUntil": null
 }
}
```

### `POST /api/history/claim`

```json
{
 "status": 200,
 "body": {
  "moved": 1,
  "leftBehind": 0
 }
}
```

### `POST /api/history/claim (again)`

```json
{
 "status": 200,
 "body": {
  "moved": 0,
  "leftBehind": 0
 }
}
```

### `GET /api/history/games/{id}/award (chosen season)`

```json
{
 "status": 200,
 "body": {
  "gameId": 455,
  "settled": false,
  "fromSavedResult": false,
  "xp": 0,
  "lines": [],
  "badges": [],
  "moments": [],
  "momentsSeen": true,
  "total": 0,
  "notCounted": true
 }
}
```

### `POST /api/history/claim (Sleeper header starting guest:)`

```json
{
 "status": 400
}
```
