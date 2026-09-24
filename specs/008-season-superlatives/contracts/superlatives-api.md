# Contract: Season superlatives and the commissioner's list

**Feature**: `008-season-superlatives` | **Date**: 2026-09-22

All endpoints:
- take the caller in `X-Sleeper-User`;
- return **404** for a league the caller can't see (`LeagueMembership.visibleLeague`, the same
  answer as an unknown league, so league existence isn't leaked);
- take **no `?season=` parameter**. A season is chosen by its own Sleeper league id: each season of
  a league chain is a separate `league` row with its own id (NFL 2025 is `1254190892974084096`, NFL
  2026 is `1346366555759341568`).
  - **Superlatives** resolve the id through `LeagueSeasonResolver.resolve(sleeperLeagueId)`, like
    `ExpectedWinsController`. That walks back to the newest *played* season, and `requestedSeason`
    reports when it moved.
  - **The conduct list** does **not** use the resolver. It addresses the league row for that exact
    id (`LeagueRepository.bySleeperId`), so a commissioner editing a new season's list before week 1
    edits that season, not last season's.

    *(Amended after code review, 2026-09-23: the page no longer calls the conduct list with the
    URL's id. It uses the payload's `leagueSleeperId`, the league-season the superlatives were
    actually computed for, so the list shown is always the one that feeds the award on screen.
    The endpoints still address an exact league row; what changed is which id the page sends. A
    new season's list therefore can't be edited from this page until that season has a scored
    week. That's spec amendment 15.)*

  *(Amended after analysis, 2026-09-23: this line originally promised a `?season=` resolved "the
  way `ExpectedWinsController` does". That controller takes no season parameter, and nothing in the
  resolver accepts one.)*

Field lists mirror the Java records one-for-one, and `web/src/api.ts` mirrors them in the same
change (AGENTS.md).

---

## `GET /api/leagues/{sleeperId}/superlatives`

One payload for the whole page, so every superlative is computed against the same season window
and can't disagree about which weeks it covers.

```json
{
  "available": true,
  "reason": null,
  "season": 2026,
  "requestedSeason": null,
  "leagueSleeperId": "1346366555759341568",
  "sport": "nfl",
  "throughWeek": 6,
  "weeksScored": 6,
  "regularSeasonEnd": 14,
  "early": false,
  "earlyThresholdWeeks": 4,
  "closeGameMargin": 10.0,
  "suspensionWeeksObserved": [3, 4, 6],
  "commissionerListAvailable": true,
  "superlatives": [
    {
      "kind": "CLOSE_WINS",
      "available": true,
      "reason": null,
      "early": false,
      "value": 3,
      "unit": "WINS",
      "holders": [ { "rosterId": 4, "managerId": 17, "teamName": "…", "avatarId": "…" } ],
      "emptyReason": null,
      "detail": [
        { "type": "GAME", "week": 2, "rosterId": 4, "opponentRosterId": 9,
          "opponentTeamName": "…", "points": 118.4, "opponentPoints": 112.1, "margin": 6.3 }
      ],
      "coverage": null
    }
  ]
}
```

### Top-level rules

- **`available: false`**, with `reason`, only when there's no fully scored regular-season week
  ("no week of this season has been scored yet"). `superlatives` is then `[]`.
- **`regularSeasonEnd`** is null when the league has no `playoff_week_start`. The page then says
  every stored week counts.
- **`closeGameMargin`** is the exact number the close-game counts used (FR-011). The page prints this
  field; it never hard-codes 10 or 15.
- **`suspensionWeeksObserved`** lists the regular-season weeks that had a `status_capture` (FR-019).
  The page shows the first of them as "tracking began week N".

### `superlatives[]`: always all twelve kinds, in this order

`HIGHEST_WEEK`, `LOWEST_WEEK`, `BIGGEST_BLOWOUT`, `CLOSEST_GAME`, `CLOSE_WINS`, `CLOSE_LOSSES`,
`LUCKIEST`, `UNLUCKIEST`, `MOST_BENCH_POINTS`, `WAIVER_WIRE_WARRIOR`, `JOEL_EMBIID`, `UNETHICAL`.

A kind is never omitted. It's `available: false` with a `reason`, or `holders: []` with an
`emptyReason`. That way an absent section can't read as a broken one (the `SectionUnavailable`
convention from spec 005).

| Field | Rule |
|---|---|
| `holders` | every tied team (FR-003); `[]` iff `emptyReason` non-null or `available` false |
| `value` / `unit` | `POINTS` (2 dp), `WINS` (count or wins-above-expected, 2 dp), `GAMES` |
| `early` | true only for `LUCKIEST`, `UNLUCKIEST`, `MOST_BENCH_POINTS`, `WAIVER_WIRE_WARRIOR`, `JOEL_EMBIID`, `UNETHICAL` while the window is `early` (FR-006 as amended names the same six) |
| `coverage` | null when every scored week was usable. Otherwise `{ "weeksCovered": 5, "weeksExcluded": 1, "reasons": ["week 3: no pairings stored"] }` |

### `detail[]` shapes, by `type`

| `type` | Used by | Fields |
|---|---|---|
| `WEEK_SCORE` | HIGHEST_WEEK, LOWEST_WEEK | `week`, `rosterId`, `points` |
| `GAME` | BIGGEST_BLOWOUT, CLOSEST_GAME, CLOSE_WINS, CLOSE_LOSSES | as the example above |
| `LUCK` | LUCKIEST, UNLUCKIEST | `rosterId`, `actualWins`, `expectedWins`, `winsAboveExpected`, `swingWeeks` (each `{week, result, points, weeklyRank, opponent}`), all copied unmodified from the bounded expected-wins row; `fromWeek`, `throughWeek` (the span, FR-002 as amended); `reading`, a one-line sentence built from `winsAboveExpected` -- e.g. "2.40 more wins than their scores earned" / "1.30 fewer wins than their scores earned" (added T032, not in the original contract; `web/src/api.ts`'s `SuperlativeDetail` LUCK variant must mirror it) |
| `BENCH_TOTAL` | MOST_BENCH_POINTS | `rosterId`, `pointsLeft`, `weeksCounted`, `fromWeek`, `throughWeek`, `biggestWeek` (`{week, pointsLeft}`, the single worst week) |
| `PICKUP` | WAIVER_WIRE_WARRIOR | `rosterId` (added T040: every other per-holder detail type -- `WEEK_SCORE`, `GAME`, `LUCK`, `BENCH_TOTAL`, `CONDUCT` -- carries it, and without it a tied holder's pickups can't be told apart from another holder's), `playerId`, `playerName`, `position`, `addedWeek`, `addType` (`WAIVER` \| `FREE_AGENT`), `startedWeeks`, `points`. Top 3 for each holder |
| `ABSENCE` | JOEL_EMBIID | `rosterId` (added T049, same reasoning as PICKUP's T040 fix: every other per-holder detail type carries it), `playerId`, `playerName`, `position`, `gamesMissed` (the headline; in football one per week), `weeksAffected` (a **count** of distinct weeks touched, not a list -- equals `gamesMissed` in football, can be fewer in basketball when two missed games land in the same week; clarified T049, `web/src/api.ts`'s ABSENCE variant had guessed `number[]`), `pointsPerGame` (his mean per game played, league scoring), `estimatedPointsLost` (= `gamesMissed` × `pointsPerGame`), `estimated: true` |
| `CONDUCT` | UNETHICAL | `playerId`, `playerName`, `rosterId`, `source` (`SUSPENDED` \| `COMMISSIONER`), `weeks` (the weeks it counted for this team), `reason` (commissioner's text, or null for `SUSPENDED`) |

### Availability reasons (examples; exact strings are the implementation's)

- `WAIVER_WIRE_WARRIOR`: "no transactions stored for this season — run POST /api/ingest/transactions/{id}".
- `JOEL_EMBIID`: "per-game records not ingested — run POST /api/ingest/player-games/{id}". Coverage
  also reports unclassified football weeks.
- `UNETHICAL`: always available. With no suspension captures and no commissioner entries it's
  `holders: []` with an `emptyReason` that names both sources.

---

## `GET /api/leagues/{sleeperId}/conduct-list`

Readable by every manager who can see the league (FR-017).

**Per season.** The list belongs to one league-season. A new season's league row starts with an empty
list, and last season's entries stay attached to last season (FR-017, amended; spec amendment 9).

```json
{
  "canEdit": false,
  "commissionerKnown": true,
  "entries": [
    { "id": 12, "playerId": "4034", "playerName": "…", "reason": "…",
      "appliesFromWeek": 6, "addedBy": "…", "createdAt": "2026-10-14T19:02:11Z" }
  ]
}
```

`canEdit` comes from `LeagueMembership.canCommission`, the same method the write endpoints enforce
with, so display and enforcement can't disagree.

## `POST /api/leagues/{sleeperId}/conduct-list`

Body: `{ "playerId": "4034", "reason": "…", "appliesFromWeek": 6 }`. Upserts on `(league, player)`.

| Status | When |
|---|---|
| 200 | saved; body is the entry |
| 400 | `reason` blank or > 140 chars after trim; `appliesFromWeek` < 1; `playerId` unknown for this sport |
| 403 | caller isn't the commissioner. `{ "message": …, "commissionerKnown": bool }`, same shape as `POST /power/commissioner` |
| 404 | league not visible |

## `DELETE /api/leagues/{sleeperId}/conduct-list/{entryId}`

204 on success. 403 and 404 as above. 404 also when `entryId` belongs to a different league: an
entry id can't be used to reach across leagues.

---

## Browser check owed (lessons #6)

`POST` with a JSON body and `DELETE` both trigger a CORS preflight from a real browser. The
multi-user onboarding work caught a real CORS header bug this way. The quickstart drives both from
the page, not from curl.
