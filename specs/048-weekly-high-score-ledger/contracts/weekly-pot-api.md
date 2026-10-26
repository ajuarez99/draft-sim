# Contract: Weekly pot API (spec 048)

> **Amended after review (2026-10-25)**, plan A1–A12: `shortcutIsPairwise` removed; `Balance` uses
> `name` + `isMe` + `noManager`; week `status` gains `NOT_FINAL` / `MISSING`; ESPN leagues get the
> platform refusal via `LeagueAvailability` (`weeklyPot` key); live leader from `peek`.

Both routes are league-scoped like every other league route: no identity, or an identity
not in this league, is a 404 (`LeagueMembership.visibleLeague`). The league row is the
URL's exact row — no season fallback (research R8).

## `GET /api/leagues/{sleeperId}/weekly-pot`

Always 200 for a visible league. When off, no money fields are present.

```json
{
  "season": 2026,
  "sport": "nfl",
  "enabled": true,
  "stakeCents": 100,
  "canEdit": false,
  "commissionerKnown": true,

  "regularSeasonKnown": true,
  "regularSeasonThroughWeek": 14,
  "throughWeek": 7,
  "latestDecidedWeek": 7,
  "seasonFinal": false,
  "tieCentRule": "When a stake doesn't split evenly, the extra cent goes to co-winners in roster order. A house convention, not a league rule.",

  "weeks": [
    { "week": 1, "status": "DECIDED", "winners": [4], "winningPoints": 152.34,
      "runnerUpPoints": 140.10, "payers": 11, "potCents": 1100 },
    { "week": 8, "status": "NOT_FINAL", "winners": [], "winningPoints": null,
      "runnerUpPoints": null, "payers": 0, "potCents": 0 }
  ],
  "balances": [
    { "rosterId": 4, "managerId": 12, "name": "Gridiron Gang", "avatarId": "abc",
      "isMe": false, "noManager": false,
      "weeksWon": 2, "collectedCents": 2200, "paidCents": 500, "netCents": 1700 }
  ],
  "pairwise": [ { "fromRosterId": 7, "toRosterId": 4, "cents": 200 } ],
  "shortcut":  [ { "fromRosterId": 7, "toRosterId": 4, "cents": 300 } ],

  "liveLeader": { "week": 8, "rosterIds": [9], "points": 88.2 },
  "liveUnavailable": false
}
```

Field rules:
- `enabled: false` → the response has only `season`, `sport`, `enabled`, `stakeCents`,
  `canEdit`, `commissionerKnown` (SC-005: nothing to render as money).
- `throughWeek`: highest week such that every week up to it is `DECIDED`/`NO_SCORES`, 0 when
  none. `seasonFinal`: every week `1..regularSeasonThroughWeek` is `DECIDED`/`NO_SCORES`; when
  `regularSeasonKnown` is false, the league row is complete.
- `latestDecidedWeek`: highest week whose status is `DECIDED`, 0 when none. Balances and both settle-up
  lists include every DECIDED week, so this (not `throughWeek`) is the honest "as of" label when a gap
  (NOT_FINAL/MISSING) sits below it. *Amended after code review 2026-10-26 (S1).*
- `regularSeasonThroughWeek`: null when `regularSeasonKnown` is false.
- `balances`: ordered by `netCents` desc, then `weeksWon` desc, then `rosterId` asc. Every
  roster in the season's `roster_season` appears, zeros before any decided week.
- `liveLeader`: null when there is no live week, it is outside the regular season, it is
  already final, the week hasn't started (no roster above 0, or no stored player games) or the top live score is not above 0, or nothing fresh is cached
  (`peek`); `liveUnavailable` is true only when the cached answer is FAILED.
- `weeks[].status`: `DECIDED` (moves money), `NO_SCORES` (rows, all exactly 0), `NOT_FINAL`
  (stored, not final), `MISSING` (no rows). Only `DECIDED` carries winners/points/pot.
- Nullable fields (`managerId`, `avatarId`, `winningPoints`, `runnerUpPoints`,
  `liveLeader`, `regularSeasonThroughWeek`) — the body is built with records/mutable maps,
  never `Map.of` (constitution).

## `PUT /api/leagues/{sleeperId}/weekly-pot/settings`

Body: `{ "enabled": true, "stakeCents": 100 }` — both required.

- 404: league not visible.
- 403: not `canCommission`; body `{ "message", "commissionerKnown" }`, same shape as the
  conduct list's 403 (`SuperlativesController.forbidden`).
- 400: missing field, or `stakeCents` outside 1..100000; body `{ "message" }`.
- 200: the same body `GET` returns after the save.

Web types in `web/src/api.ts` mirror these field-for-field, in the same task as the Java
records (constitution).
