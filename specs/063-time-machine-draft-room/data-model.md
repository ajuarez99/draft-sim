# Data model: Time Machine draft room and playoffs

No table or column changes. Two JSON shapes change and two browser keys are added.

## `HistoricalResult` (stored JSON in `historical_game.result`, mirrored in `web/src/api.ts`)

Existing fields keep their order and meaning except where noted. New fields are appended.

| Field | Type | Notes |
|-------|------|-------|
| `weeks` | `Week[]` | Now regular-season weeks followed by playoff weeks. Weeks after the final are absent. |
| `standings` | `Standing[]` | **Regular season only.** `place` is the regular-season rank (the seed for places 1 to 6). |
| `userFinish` | int | **Meaning changes**: the user's final place after playoffs. In an old result it is the standings rank. |
| `lineups` | map | The user's lineups for the weeks the user played. |
| `hindsight`, `biggestBust`, `bestStealPassedOn` | unchanged | Whole real season. |
| `format` | `Format` or absent | Absent ⇒ old format. |
| `playoffs` | `Playoffs` or null | Null when `format.playoffWeeks` is empty. |
| `finalPlaces` | `FinalPlace[]` or absent | All teams, ordered by place. |
| `userOutcome` | string or absent | `CHAMPION`, `LOST_FINAL`, `LOST_SEMIFINAL`, `LOST_ROUND_ONE`, `MISSED_PLAYOFFS`, `NO_PLAYOFFS`. *(Amended after review 2026-11-13, PR-08.)* |

`Week` gains `phase`: `"REGULAR"` or `"PLAYOFF"`; absent in old results.

```
Format      { version: 2, seasonWeeks, configuredRegularSeasonWeeks, regularSeasonWeeks,
              playoffWeeks: int[], unusedWeeks, shortened: boolean }
Playoffs    { seeds: Seed[], rounds: Round[], championSlot }
Seed        { seed (1..6), slot }
Round       { round (1..3), week, name: "ROUND_ONE"|"SEMIFINAL"|"FINAL", byeSlots: int[], matchups: PlayoffMatchup[] }
PlayoffMatchup { highSeed, lowSeed, highSlot, lowSlot, highPoints, lowPoints, winnerSlot, decidedBySeed }
FinalPlace  { slot, place, outcome }   // outcome uses the userOutcome vocabulary
```

Invariants (each has a test):

- `playoffs.seeds[i].slot == standings[i].slot` for i in 0..5.
- Round 1 pairs seeds (3,6) and (4,5); `byeSlots` are seeds 1 and 2.
- Round 2 pairs seed 1 with the larger surviving seed number and seed 2 with the other.
- `highPoints`/`lowPoints` equal that team's best-ball week score for `week`.
- `winnerSlot` is the higher score; level to the hundredth ⇒ `highSlot` and `decidedBySeed`.
- `finalPlaces` is a permutation of all slots with places 1..teams; places 7.. follow `standings`.
- `userFinish == finalPlaces[user].place`.
- Same input ⇒ byte-identical serialised output from the replay. The column is `jsonb`, so stored key order is not ours.
- Every playoff matchup in `playoffs.rounds[]` appears with the same scores in the `weeks[]` entry for its week.

## `HistoricalGameState`

Adds `reversalRound: int` (the game's stored value; 0 today). Optional in `api.ts`: an old backend omits it and the web reads 0. *(Amended after review 2026-11-13, PR-05.)*

## `GameSummary` (`GET /api/history/games` rows)

Adds `formatVersion: Integer|null` and `outcome: String|null` after `finish`. Null for unfinished and old-format games.

## Configuration

| Key | Value | Provenance |
|-----|-------|------------|
| `historical.regular-season-weeks` | 19 | League-format choice, Allan 2026-11-13. Not measured. |
| `historical-nfl.regular-season-weeks` | 14 | League-format choice, Allan 2026-11-13. Not measured. |

## Browser storage

| Key | Value | Fallback |
|-----|-------|----------|
| `bk.tm.revealSpeed` | `SLOW`, `NORMAL`, `FAST` or `INSTANT` | `NORMAL` when missing, unreadable or unknown |
| `bk.tm.queue.<gameId>` | JSON array of player ids, in queue order | empty; ids no longer available are dropped on read |

## Reveal (in memory only)

`revealedThrough: number` — the highest pick number shown. `view` = server state cut at that pick. Never sent to the server.
