# Contract changes: spec 040

Base: `specs/008-season-superlatives/contracts/superlatives-api.md` (+ spec 010's standings fields). Only deltas.

## `GET /api/leagues/{sleeperId}/superlatives` — additive + one changed detail shape

Each `superlatives[]` item gains:

```json
"playerContributions": [
  { "rank": 1, "playerId": "1308", "playerName": "Joel Embiid", "position": "C",
    "team": { "rosterId": 4, "managerId": 12, "teamName": "…", "username": "…", "avatarId": "…" },
    "value": 185.6, "note": "4 weeks missed · 46.4 pts/game" }
]
```

`[]` unless kind ∈ {JOEL_EMBIID, WAIVER_WIRE_WARRIOR, UNETHICAL}. JABARI_SMITH_JR's `playerStandings` is no longer
capped at top 10 + ties.

`detail[]` rows of `type: "ABSENCE"` change shape:

```json
{ "type": "ABSENCE", "playerId": "1308", "playerName": "Joel Embiid", "position": "C", "rosterId": 4,
  "weeksMissed": 4, "gamesMissed": 11, "pointsPerGame": 46.4, "estimatedPointsLost": 185.6, "estimated": true }
```

(`weeksAffected` removed — it equals `weeksMissed` now.) JOEL_EMBIID `value` and `standings[].value` are now weekly-rule totals.

## `GET /api/leagues/{sleeperId}/conduct-list` — additive

```json
{
  "season": 2026,
  "canEdit": true,
  "commissionerKnown": true,
  "entries": [
    { "id": 7, "playerId": "1308", "playerName": "Joel Embiid", "position": "C", "proTeam": "PHI",
      "reason": "3-game suspension", "appliesFromWeek": 2, "appliesThroughWeek": 3,
      "addedBy": "Allan", "createdAt": "2026-10-20T…Z",
      "charges": [ { "team": { "rosterId": 4, "managerId": 12, "teamName": "…", "username": "…", "avatarId": null }, "weeks": [2, 3] } ],
      "playerWeeks": 2,
      "alsoFlaggedBySleeperWeeks": [3] }
  ],
  "candidates": [
    { "playerId": "1308", "playerName": "Joel Embiid", "position": "C", "proTeam": "PHI",
      "fantasyTeam": { "rosterId": 4, "managerId": 12, "teamName": "…", "username": "…", "avatarId": null } }
  ]
}
```

`alsoFlaggedBySleeperWeeks` (int[], sorted, `[]` when none): the charged weeks that Sleeper's own suspension
capture also flags. Those weeks count once (the entry and Sleeper don't double-charge); the panel says so. Like
`charges`/`playerWeeks` it is GET-only. *(Added 2026-10-20 after code review: the field was on the wire and in
`web/src/api.ts` but missing here.)*

`fantasyTeam` null for a candidate known only from past `players_points` and no longer rostered. Nullable fields
mean the response is built with mutable maps (hard rule: no `Map.of` with a possible null).

## `POST /api/leagues/{sleeperId}/conduct-list` — one optional field

Body gains `appliesThroughWeek: int|null`. 400 when non-null and `< appliesFromWeek`. Everything else as today
(upsert on player, 403 for non-commissioner, 400 for unknown player / bad reason).

## `PUT /api/leagues/{sleeperId}/conduct-list/{entryId}` — new

Body `{ "reason": string, "appliesFromWeek": int, "appliesThroughWeek": int|null }`. Same validation and same
403 rule as POST. 404 when the id isn't in this league row. 200 with the updated entry row (same shape as a list
entry, **without** `charges`/`playerWeeks` — those are GET-only; the client refetches). `added_by_manager_id`
untouched. 400 (not 500) when through < from: validated in the controller before the V38 CHECK.

*Amended 2026-10-20 after plan review F2/F10: Holder wire shape corrected; POST's upsert also sets
`applies_through_week`; POST/PUT return the plain row.*

## CORS

`WebConfig.java:51` already allows PUT. Live verification must still exercise the PUT from the real browser
(lesson class 6 — curl-only endpoints have shipped broken before).
