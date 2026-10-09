# Contracts: Player stats in the draft room

There are no new endpoints. There are three added response fields and one browser storage key.

## C1. `GET /api/drafts/{sleeperDraftId}/seats`: one added field

```jsonc
{
  "draftId": "1339351318128517120",
  // ...every existing field unchanged...
  "sleeperLeagueId": "1339351318115946496"   // NEW. string | null (null only if the league row is missing)
}
```

- **Java**: `LeagueController.seats()` puts `league.map(LeagueRow::sleeperId).orElse(null)` into
  the existing `LinkedHashMap`.
- **TS**: `SeatsResponse.sleeperLeagueId?: string | null`. It's optional because a frontend
  deployed ahead of the backend gets `undefined`. The room then hides the stats view with "Stats
  aren't available on this server yet". It must never throw.
- **Scoping**: unchanged. `seats` is already gated by `membership.visibleDraft`, and the
  leaderboard is gated by `visibleLeague`, so the room exposes no league that the caller couldn't
  already open.

## C2. `GET /api/leagues/{sleeperLeagueId}/stats?window=…`: two added fields

```jsonc
{
  "season": 2025,
  "requestedSeason": 2026,             // existing: non-null means the stats fell back
  "scoringSeason": 2025,               // NEW. int | null. The season whose league scoring scored every fantasy figure. Null when available=false
  "scoringMatchesRequested": true,     // NEW. boolean | null. Null when there was no fallback; otherwise whether that scoring equals the requested season's
  // ...every existing field unchanged...
}
```

- **Java**: two components are added to the `StatLeaderboard` record, after `requestedSeason`.
  `unavailableBoard(...)` passes nulls. The comparison is `Map.equals` on the two `scoringOf`
  maps (key and value equality on `Double`).
  (Amended after code review: the maps are compared after dropping entries weighted 0.0, since the
  scorer treats an absent key and a zero the same; and the flag is null, not false, when either
  map is empty, i.e. the scoring isn't stored. See `PlayerStatsService.sameScoring`.) It's computed only when `resolved.requestedSeason()
  != null`.
- **TS**: `scoringSeason?: number | null`, `scoringMatchesRequested?: boolean | null`.
  `undefined` (an older backend) is treated as unknown, so the room names no scoring season and
  shows no warning.
- **Existing callers**: the leaderboard page ignores both. Its tests don't change.

## C3. Browser storage: `bk.draftStats.v1`

```json
{ "v": 1, "columns": ["gp", "min", "fp", "pts", "reb", "ast", "stl", "blk", "tpm", "tov", "fgPctMA", "ftPctMA"] }
```

See data-model.md, StatChoice, for the read and write rules. It's per device, and shared by
every basketball draft room.

## C4. Player page link

`/leagues/{sleeperLeagueId}/players/{sleeperPlayerId}` is 022's existing route. It opens in a
new tab (research R8).
