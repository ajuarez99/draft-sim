# Contract: ADP compare API (spec 046)

> **Amended after review (2026-10-23).** These items supersede the text below where they differ:
>
> - **Identity (F13).** The path is `GET /api/leagues/{sleeperLeagueId}/adp-compare` with the
>   `X-Sleeper-User` header (via `apiFetch`), not `?user=`. 404 means only "not visible". There's
>   no platform gate: ESPN NBA leagues get the compare half, and `draft` is null for them.
> - **Unmatched Yahoo players (F5).** They appear in `players` with `playerId: null` and
>   `sleeperId: null`, and only Yahoo fields filled. `sources[yahoo].unmatched` is their count.
> - **Reliability (F7).** `sources[yahoo]` adds `minPercentDrafted: 0.5` (the default the client
>   uses) and keeps `deepestAdp` as context only. Each player already carries `yahooPctDrafted`.
> - **Team state (F15).** `teamsState` has one entry per `slot` 1..`teams`. `managerId`, `name`
>   and `avatar` are null when the slot has no manager yet.
> - **Sales (F4).** `sales[].name` comes from the player, else the stored `draft_pick.player_name`,
>   else "Unknown player". `sales[].priceMissing` is true when `amount` is null.
> - **`costCurve` (F9).** Each cost is floored at 1.0 before sorting. If there's no Yahoo capture
>   it's an empty array, and the client shows Blend $ as "unavailable".
> - **Refusals (F1, F3).** The table at the bottom is replaced by this one:
>
>   | Endpoint | Auction behaviour |
>   |---|---|
>   | `GET /api/drafts/{id}/board` (real completed board) | unchanged |
>   | `POST /api/sims`, `/api/sims/stream` | 409 `{"error":"auction_draft","message":…}` |
>   | `POST /api/mocks/from-draft/{id}` | 409 `auction_draft` |
>   | `POST /api/drafts/{id}/picks` | 409 `auction_draft` |
>   | draft grades | `available: false, reason: "AUCTION_DRAFT"` (F8) |
>   | stats leaderboard join | `pickNo`/`round` null for auction picks |
>
> - **Drafts list (F2).** `DraftSummary` gains `draftType` (Java record, `DRAFT_SUMMARY_COLUMNS`,
>   and `api.ts`, in the same change).
> - **Daily refresh (F11, F16).** The steps are `ADP_CAPTURE` (Sleeper dynasty/redraft, gated on
>   "an NBA league exists") and `YAHOO_ADP`. Both are best-effort and gated by `latestCapture ==
>   today`, not `daily_capture`.

## GET `/api/leagues/{sleeperLeagueId}/adp-compare?user={sleeperUserId}`

Visibility: `LeagueMembership.canSee`, the same as every league page. 404 if the user can't see
the league, or it's an ESPN league (`LeagueAvailability` page key `adp`).

Response 200 (field names are final; `web/src/api.ts` mirrors them exactly):

```json
{
  "sport": "nba",
  "sources": [
    { "key": "sleeper_dynasty", "label": "Sleeper dynasty ADP", "capturedOn": "2026-10-23", "ranked": 835, "scoring": "dynasty" },
    { "key": "sleeper_redraft", "label": "Sleeper redraft ADP", "capturedOn": "2026-10-23", "ranked": 790, "scoring": "redraft" },
    { "key": "yahoo", "label": "Yahoo ADP", "capturedOn": "2026-10-23", "ranked": 186, "scoring": "redraft",
      "unmatched": 0, "deepestAdp": 123.7 }
  ],
  "players": [
    { "playerId": 1, "sleeperId": "6430", "name": "Victor Wembanyama", "team": "SAS", "positions": ["C"],
      "yearsExp": 3, "dynasty": 1.3, "redraft": 1.3, "yahooAdp": 2.0, "yahooCost": 80.9, "yahooPctDrafted": 1.0,
      "sale": { "amount": 112, "managerId": 42, "pickNo": 7 } }
  ],
  "costCurve": [80.9, 77.0, 70.7],
  "draft": {
    "sleeperDraftId": "1415422395308609536", "type": "auction", "status": "complete",
    "teams": 10, "rounds": 23, "budget": 200,
    "teamsState": [
      { "managerId": 42, "name": "popsharky", "avatar": "abc123", "slot": 9,
        "spent": 200, "filled": 23, "spotsLeft": 0, "left": 0, "maxBid": 0, "priceMissing": 0 }
    ],
    "sales": [
      { "pickNo": 230, "amount": 1, "managerId": 7, "playerId": 99, "name": "Some Player" }
    ]
  }
}
```

Rules:

- `sources[i]` is **always present** for all three keys. If a source has no capture, it has
  `capturedOn: null, ranked: 0`, and the UI says "unavailable". A null capture date never
  disappears.
- Any player value may be `null` (unranked by that source). Don't use `Map.of` on this path: team,
  sale, and every ADP field are nullable.
- `costCurve` is Yahoo's `avg_cost` values from the latest capture, sorted descending. Rescaling
  happens client-side (it depends on the slider-driven blend order) using `draft.teams`,
  `draft.rounds` and `draft.budget`.
- `draft` is null when the league has no draft row. `teamsState` and `sales` are present only
  when `type = "auction"`. For snake drafts they're omitted, not empty arrays, so a snake draft
  can't render an empty budget grid.
- `sales` is ordered by `pickNo` descending. A `name` is always present: the player's name, or
  Sleeper's raw name, or "Unknown player".
- `user` follows the existing pattern: a public, unverified Sleeper user id
  (`LeagueController.liveStream` comment).

## Live updates

There's no new stream. The page opens `GET /api/drafts/{sleeperDraftId}/live-stream` (existing)
when `draft.status` is `pre_draft` or `drafting`, and refetches `adp-compare` when an `event:
state` frame's `picksMade` changes.

## Refusals added for auction drafts (US4 / research R5)

| Endpoint | Auction behaviour |
|---|---|
| `GET /api/drafts/{id}/board` and the simulation endpoints the live room calls | `409 {"error": "auction_draft", "message": "This is an auction draft. The simulator models snake pick order, which an auction doesn't have."}` |
| Draft grades for an auction draft | the existing grades payload with `graded: false, reason: "auction"`, never a computed grade |

## Ingest

| Endpoint | Change |
|---|---|
| `POST /api/ingest/adp/yahoo?sport=nba` (admin) | new: one capture; returns `{fetched, rows, matched, unmatched[], rejectedReason}` |
| Season projection refresh (existing endpoint and daily step) | also writes the `sleeper_dynasty` / `sleeper_redraft` captures; its `Result` gains `adpRows` |
| Daily refresh | new best-effort step `YAHOO_ADP` (NBA), the same pattern as `SEASON_PROJECTIONS` |
