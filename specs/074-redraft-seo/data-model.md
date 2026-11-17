# Data Model: 074 Redraft SEO

## Stored

### `historical_game` (V50 adds one column)

| Column | Type | Note |
|---|---|---|
| `chosen_season` | `boolean not null default false` | true when the player picked the season from a season page. Existing rows: false. |
| `sleeper_user_id` (existing) | text | now holds either a Sleeper user id or `guest:<uuid>`. Name kept; V50 carries a comment saying so. |

Indexes unchanged. `historical_game_daily_once (sleeper_user_id, sport, daily_date)` is what makes a claim skip a daily the target already played.

No new table. Season summaries are computed and cached in memory, not stored.

## Computed (Java records, each with its `web/src/api.ts` twin, same field names and order)

### `SeasonPage`

```text
SeasonPage(
  String sport,                 // "NBA" | "NFL"
  int seasonStart,
  String label,                 // "2016-17" | "2007"
  String slug,                  // == label; the address segment
  String prevSlug,              // nullable: no earlier offered season
  String nextSlug,              // nullable
  int teams, int rounds,
  List<String> rosterPositions,
  String scoringNote,           // sport.settingsSource()
  String rankingLabel,          // sport.rankingLabel(); never "ADP"
  List<String> dataNotes,       // never null
  String attribution,           // nullable (basketball)
  List<Finisher> topOverall,    // 12
  Map<String, List<Finisher>> topByPosition,   // LinkedHashMap, 5 each, roster order
  List<Mover> busts,            // up to 5
  List<Mover> steals,           // up to 5
  int unrankedLeftOut,          // players skipped for having no going-in rank
  List<DreamSlot> dreamRoster,
  double dreamRosterPoints)

Finisher(long playerId, String name, String position, String team /*nullable*/,
         int finishRank, double points, Integer goingInRank /*nullable: unranked*/)

Mover(long playerId, String name, String position, String team /*nullable*/,
      int goingInRank, int finishRank, double points)

DreamSlot(String slot, long playerId, String name, String position, double points)
```

Rules:
- `finishRank` = 1 + count of board players with strictly more points. Equal points share a rank.
- `goingInRank` = 1-based index among ranked board cards, seed 0.
- Bust candidates: `goingInRank ≤ teams × 5`. Order by `finishRank − goingInRank` descending, then player id.
- Steal candidates: `finishRank ≤ teams × 5` and ranked. Order by `goingInRank − finishRank` descending, then player id.
- A bust has `finishRank > goingInRank`; a steal has `goingInRank > finishRank`. A list may be shorter than 5; it is never padded.
- `team` may be null: no `Map.of` on this path.

### `SeasonRef` (the season list)

`SeasonRef(String sport, int seasonStart, String label, String slug)`

### `YesterdayDaily`

`YesterdayDaily(String sport, String date, String seasonLabel /*nullable*/, String slug /*nullable*/)`, null fields when no row was dealt for that day.

### `PublicGame`

`PublicGame(HistoricalGameState state, HistoricalResult result)` where `state` is produced by the existing mapper. `HistoricalGameState` is confirmed in T-read to carry no owner id; if it does, a separate view record is introduced rather than nulling a field.

### Changed records

- `HistoricalGameState` + `boolean chosenSeason`
- `HistoricalGameState.GameSummary` + `boolean chosenSeason`
- `HistoricalGameController.CreateRequest` + `Integer seasonStart` (nullable)
- `ClaimResult(int moved, int leftBehind)` new

## Browser

| Key | Value | Note |
|---|---|---|
| `bk.user.v1` (existing) | `BkUser` | unchanged |
| `bk.guest.v1` | `{ id: uuid }` | made on first need; removed after a successful claim |

## State

Game status is unchanged (`DRAFTING` → `COMPLETE` | `ABANDONED`). Ownership can change once: `guest:<uuid>` → Sleeper id, by claim. Never back.

## Amended after review (2026-11-17)

The review and the designer changed these records. The amended shapes are in plan.md, section "Data model, amended after review", and win over the shapes above: SeasonPage gains excludedStats, statCoverage, boardPlayers, rankedPlayers and pointsNote; rows carry positions (a list) and weeksPlayed; DreamSlot becomes DreamPlayer with no slot; PublicGame carries the state only plus reducedUntil; the season list is wrapped with a schema number; AwardView gains notCounted.
