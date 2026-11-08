# API Contract: Football Time Machine (spec 056)

This is a **delta on `specs/053-historical-draft-game/contracts/api.md`**. Everything
not listed here is unchanged: the headers, 401/404/409 behavior, pick/auto/abandon
bodies, and the `result` shape. Every changed type is mirrored in `web/src/api.ts` in the
same task.

**`sport` is required wherever it appears in a request, with no default.** A defaulted
sport asserts a rule, and that bug has shipped three times here (memory:
optional-params-that-encode-rules). 053 is not deployed, so there is no old client to
stay compatible with.

## `GET /api/history/seasons?sport=NBA|NFL`
`sport` is required. Missing or unknown → 400. Each row gains `"sport"` and `"dataNotes": [string]`.

## `POST /api/history/games`
Request: `{ "sport": "NFL", "userSlot": 5 }`. `sport` is required (400 without it), and
`userSlot` stays optional, 1..teams for that sport.

## `GET /api/history/games`
Each row gains `"sport": "NBA" | "NFL"`. The list is still the caller's games of both
sports, newest first (FR-015: the web page labels each one).

## `HistoricalGameState`
- New top-level **`"sport": "NBA" | "NFL"`**.
- `season` gains:
  - `"dataNotes": ["246 of 248 regular-season games are in the data", ...]`. Empty
    when there is nothing to say. Never null.
  - `"attribution": "Stats: nflverse (nflverse-data), CC BY 4.0" | null`. Null for NBA.
  - `settingsSource` for NFL: `"Hand-set 12-team, 15-round roster: QB, 2 RB, 2 WR, TE, FLEX + 8 bench · full PPR"`.
- For NFL, `season.label` is `"2007"`, `excludedStats` and `statCoverage` are `{}`, and
  `countedStats` lists the 13 scoring keys.

`HistoricalPlayerCard`, NFL:
`{ "positions": ["RB"], "team": "SD" | null, "age": null, "prior": { "seasonLabel": "2006", "games": 16,
   "perGame": {"rush_yd": 115.8, "rec": 3.5, ...}, "fantasyPoints": 476.9, "bestOfTwo": "2006" } }`.
`perGame` keys are the scored counting keys in `StatOrder` order. Zeros are kept (a WR's
`pass_yd: 0`), and the web side hides the zero keys.

## `result`, NFL
The shape is unchanged. `weeks[].startDate` is **null** (R4: the files have no dates), and
`lineups[].starters[].gameDate` is **null**. The web page shows "Week N".

## Admin: `POST /api/admin/historical/import`
`part` gains **`"NFLVERSE"`**. `"ALL"` keeps its 053 meaning (basketball only), so an NBA
import never downloads 27 NFL files by surprise. The job report for NFLVERSE:
```json
{ "seasons": [{ "seasonStart": 2000, "source": "NFLVERSE", "games": 246, "expectedGames": 248,
                "playerWeeks": 5260, "playerSeasons": 470, "droppedRows": 0,
                "fileSha256": "…", "fileBytes": 7268743 }] }
```
It **fails a season loudly** on two REG rows for one player in one week, or on a header
missing an expected column (no silent zeros from a renamed column).

## Amended after review (2026-11-05)

- **F1**: 053 is deployed. `sport` stays required, and **the web service deploys first**.
  053's backend ignores the unknown `sport` field and param, so an NBA game still starts
  from the new web. Football waits for the new backend. The tasks spell out the order.
- **F16**: card `prior` gains `"totals": {"st_td": 2, "fum_lost": 3}`, with only nonzero
  keys, `{}` when there are none, and never null.
- **F14**: the card (`HistoricalPlayerCard`) gains a top-level `"rookiePick": 5 | null`,
  the overall NFL draft pick of a rookie with no prior line. It is always null for NBA
  and for veterans. A rookie's `prior` stays null, as in 053.
- **F4**: for NFL, `starters[].gameDate` is null. The web shows "Week N" in that column.
- `rankingLabel` for NFL: `"ranked by last season's points over replacement"`. Never "ADP".
