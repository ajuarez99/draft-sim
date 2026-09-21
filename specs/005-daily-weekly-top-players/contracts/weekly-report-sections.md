# Contract: Weekly Report sections

**Feature**: `005-daily-weekly-top-players` | **Date**: 2026-09-19

Extends the existing `GET /api/leagues/{sleeperId}/weekly-report/{week}`. The week is a **path
segment**, not a query parameter — a detail worth stating because it has already cost one round of
404s.

The existing response keys (`available`, `season`, `requestedSeason`, `week`, `sport`, `matchups`,
`topPerformers`, `awards`, `awardsOmitted`) are unchanged. This feature adds fields; it removes none,
so any reader that ignores the new keys keeps working.

---

## Why one endpoint and not two

Both sections describe one week of one league and are read together, on one page, in one glance. A
second endpoint would mean a second round trip for the same week and a second chance for the two lists
to disagree about which week they describe. The repo's existing shape — one controller per page — puts
them on the endpoint the page already calls.

## Sport decides the shape

The response carries **either** `topPerformers` (football's existing list) **or** the pair
`bestNights` + `bestWeek`, never both. Which one is decided by the sport rule in
[data-model.md](../data-model.md), never by a sport name compared in the controller.

`playersPlayMultiplePerPeriod` is included so the client renders from a stated fact rather than
inferring the sport's rules from which arrays happen to be populated.

### Football — unchanged (SC-004)

```json
{
  "available": true,
  "season": 2026,
  "week": 5,
  "sport": "nfl",
  "playersPlayMultiplePerPeriod": false,
  "matchups": [ "... unchanged ..." ],
  "topPerformers": [
    { "playerId": "4034", "playerName": "…", "position": "RB", "teamName": "…", "points": 31.4 }
  ],
  "awards": [],
  "awardsOmitted": []
}
```

`bestNights` and `bestWeek` are **absent**, not empty arrays. An empty array would say "we looked and
there were none"; absence says "this measure does not apply to this sport".

### Basketball — the pair

```json
{
  "available": true,
  "season": 2025,
  "week": 5,
  "sport": "nba",
  "playersPlayMultiplePerPeriod": true,
  "matchups": [ "... unchanged ..." ],
  "bestNights": [
    {
      "playerId": "1658",
      "playerName": "Nikola Jokić",
      "position": "C",
      "teamName": "FentMachines5:SoFkingOver",
      "points": 58.5,
      "date": "2025-11-17",
      "opponent": "CHI",
      "isAway": false
    }
  ],
  "bestWeek": [
    {
      "playerId": "1658",
      "playerName": "Nikola Jokić",
      "position": "C",
      "teamName": "FentMachines5:SoFkingOver",
      "totalPoints": 182.0,
      "gamesPlayed": 4
    }
  ],
  "basis": "ALL_GAMES_PLAYED",
  "sectionsUnavailable": [],
  "awards": [],
  "awardsOmitted": []
}
```

`topPerformers` is **absent** here: the pair replaces it, and emitting all three would put three
rankings of overlapping data on one page.

---

## Assertions

A contract test must hold each of these. They are assertions, not descriptions.

1. **Football's shape is unchanged.** For a league whose sport says players play once per period, the
   response contains `topPerformers` and contains neither `bestNights` nor `bestWeek`.
2. **Basketball's shape is the pair.** For a league whose sport says otherwise, the response contains
   `bestNights` and `bestWeek` and does **not** contain `topPerformers`.
3. **A night is one game.** Every `bestNights` entry's `points` equals that one game's league-scored
   value; no entry is a sum. Verified against a fixture where a player played more than once.
4. **A week total is the sum of its games.** For every `bestWeek` entry, `totalPoints` equals the sum of
   that player's games in the week and `gamesPlayed` equals their count (FR-002, SC-003).
5. **`basis` is explicit.** `ALL_GAMES_PLAYED` states that totals include games the league's scoring did
   not count. It is a required field on the basketball shape, never defaulted or omitted — the page's
   FR-005 disclosure is driven by it rather than by prose hardcoded in a component.
6. **Zero-game players are absent.** A player rostered all week who played no games appears in neither
   array — never with `totalPoints: 0` or `gamesPlayed: 0`.
7. **Ordering is deterministic.** Two successive requests for the same week return identical orderings,
   including where points tie exactly (FR-009).
8. **Missing detail is stated, not substituted.** When per-game data could not be obtained,
   `sectionsUnavailable` names the section and a reason, and the affected array is empty rather than
   populated from the stored single-game value (FR-006).
9. **Nothing is fetched on read.** Two successive requests make no upstream calls; the endpoint reads
   stored rows only (FR-008).
10. **An unscored week refuses.** Unchanged from today: the response says the week has not been scored
    rather than returning empty rankings (FR-010).

### `sectionsUnavailable` shape

```json
{ "section": "BEST_WEEK", "reason": "PER_GAME_DETAIL_MISSING" }
```

`section` is one of `BEST_NIGHTS`, `BEST_WEEK`. `reason` is a discriminator, not a sentence — the words
shown to a reader belong in the page, so that changing them is not a contract change.

---

## Ingest: the backfill

```
POST /api/ingest/player-games/{sleeperLeagueId}?season={season}
```

Walks the players appearing in that league-season's stored weekly points (measured: 331 for
`1229352720222134272`, 280 for `1141438340626231296`) and fetches each player's season in one call.

**Response**: `{ "playersWalked": 331, "gamesStored": 4180, "playersFailed": 0 }`

Counted and returned rather than logged, because the count is how anyone running a backfill learns
whether it worked — the same argument feature 004's `transactionsIngested` settled.

### Assertions

1. **Idempotent on the natural key.** Running it twice leaves the same row count; the second run
   updates `fetched_at` and rewrites stats that changed, and inserts nothing new.
2. **Not on the read path.** No page load triggers it (FR-008).
3. **Not in the league ingest chain.** `POST /api/ingest/league-history/{id}` must not invoke it —
   ~331 calls do not belong in a routine per-league ingest. This is the deliberate opposite of the
   decision made for transactions in feature 004, for reasons plan.md records.
4. **Partial failure is reported, not swallowed.** A player whose fetch fails increments `playersFailed`
   and leaves the others stored.
