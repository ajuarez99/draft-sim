# Contract: `GET /api/leagues/{sleeperLeagueId}/player-spotlight`

Scoped like every league route: `X-Sleeper-User` header required. A missing identity, or one that is not a
member, gets **404**, the same as a missing league (`LeagueMembership.visibleLeague`). It does not fail open
(audit 2026-09-28 root cause).

`web/src/api.ts` gets a hand-written `PlayerSpotlight` type mirroring this field for field, **in the same
change** as the Java record (hard rule).

## Response 200

```jsonc
{
  "applies": true,                       // false => the home renders nothing for these sections
  "reason": null,                        // when applies=false: "PAST_SEASON"
  "season": 2026,
  "sport": "nba",
  "playersPlayMultiplePerPeriod": true,  // stated by the server; the client never infers it

  // What the sections are scored against. Exactly one shape:
  "period": { "kind": "NIGHT", "date": "2026-10-21", "gamesCount": 11 },
  //   or   { "kind": "WEEK", "week": 3, "weekFinal": true }
  //   or   null  -> nothing to score against yet; see periodUnavailable
  "periodUnavailable": null,             // "NO_GAMES_YET" | "NO_COMPLETE_NIGHT_YET" | "NO_WEEK_SCORED"
  "laterNightInProgress": null,          // "2026-10-22" when a later date has rows but is not complete (R6)
  "seasonStartDate": "2026-10-20",       // null when unknown; never guessed

  // Basketball only: absent (not null, not []) for football, whose top list is
  // the weekly report's topPerformers (research R9)
  "topOfNight": {
    "entries": [ Performance ],
    "unavailable": null                  // "NO_PERIOD" | "NO_ROSTERED_PLAYED" | "SECTION_FAILED"
  },

  "trending": {
    "entries": [ TrendingEntry ],
    "lookbackHours": 24,
    "fetchedAt": "2026-10-21T14:05:11Z", // null = never fetched
    "stale": false,                      // fetchedAt older than lookbackHours
    "omittedUnknownPlayers": 0,
    "unavailable": null                  // "NEVER_FETCHED" | "SECTION_FAILED"
  },

  "rookieWatch": {
    "entries": [ Performance ],
    "unavailable": null                  // "NO_PERIOD" | "NO_ROOKIE_PLAYED" | "SECTION_FAILED"
                                         // NO_ROOKIE_PLAYED also covers a period with no scoreable rows
  }
}
```

### `Performance`
```jsonc
{ "playerId": "12345", "name": "AJ Dybantsa", "position": "SF", "team": "WAS",
  "opponent": "CHA", "isAway": false,     // opponent/isAway nullable: "unknown", never guessed
  "points": 41.5,
  "ownership": { "rostered": true, "teamName": "Dunk Tank", "isMe": false } }
// unrostered: "ownership": { "rostered": false }
```

### `TrendingEntry`
```jsonc
{ "rank": 1, "playerId": "2265", "name": "Damian Lillard", "position": "PG", "team": "POR",
  "addCount": 602,
  "ownership": { "rostered": false },
  "outcome": "PLAYED",                   // "PLAYED" | "DID_NOT_PLAY" | "NO_GAME" | "NO_PERIOD"
  "points": 28.0, "opponent": "SAC", "isAway": true }   // points/opponent/isAway present only when PLAYED
```

## Invariants the tests pin

1. `points` appears only on `PLAYED` entries and on `Performance`. A non-game is never `0`.
2. When `period.kind == "WEEK"`, `period.week` equals what `GET /weekly-report/0` returns as `week` for the
   same league at the same moment.
3. `topOfNight` is present iff `playersPlayMultiplePerPeriod`. Decided by `SportRules`, with no sport-name
   comparison in the service or the component (FR-013).
4. Two calls with unchanged data return identical orderings (FR-014).
5. Absent vs empty: an absent section means "does not apply to this sport"; `entries: []` with an
   `unavailable` reason means "applies, nothing to show, and here is why". An empty list never comes
   without a reason (FR-010).
6. Every `Performance.points` for a night equals the same game's `points` in that week's
   `weekly-report` `bestNights`, when the player appears there (SC-003).

## Not part of this contract

The football **Top players of the week** list is not served here. The home renders `topPerformers` from the
`weekly-report/0` payload it already loads (research R9).

## Refresh side (internal, no new public route)

The existing league refresh (spec 009) gains a sport-wide, best-effort step: when the sport's
`sport_trending_fetch.fetched_at` is null or older than `STALE_AFTER` (1 h), it fetches
`/players/{sport}/trending/add?lookback_hours=24&limit=25` and `/state/{sport}`, then writes per
data-model.md. A failure records `last_failure*` and does **not** fail the league refresh. The existing
data-version bump makes an open home refetch the spotlight when a refresh finishes.

## Amended after build (2026-10-01)

- **`topOfNight.unavailable` gains `NO_ROSTERED_PLAYED`**: the period exists, but no player rostered in
  this league has a game in it (for example, rosters not stored yet). Before this, that case returned
  `entries: []` with a null reason, which broke invariant 5. Found by the build pass of T017.
- **`rookieWatch`'s `NO_ROOKIE_PLAYED`** also covers a period with no scoreable rows at all, not only
  rows that hold no rookie. Same reason: invariant 5.
- **`TrendingEntry.outcome` `BYE` renamed `NO_GAME`**, and it is emitted only for a settled period (a NIGHT, or
  a WEEK with `weekFinal`). Found by the T041 review: the original rule mislabelled NBA off-nights and
  not-yet-played football games as byes. See research.md "R3 — amended after review".

## Amended after design review (2026-10-01, decisions by Allan)

- **The WEEK period is now the newest complete week, not the matchup block's week.** `period.week` is the newest
  week whose `sport_week_stats.final` is true (sport-wide stats settled, 48 h after the week's last game), with
  `weekFinal: true`. With no final week, it is the newest stored week, with `weekFinal: false`. **Invariant 2 is
  replaced**: Trending and Rookie watch no longer share the weekly report's week. Why: Sleeper finalizes a
  league's week later than its stats settle, so "(Foot) Ball Knowers" showed Week 2 on 2026-10-01 while week 3 was
  complete. The newest *stored* week was rejected because a Thursday game alone would make every other trending
  player read "did not play". The football **Top players** list still comes from `weekly-report/0` and is labelled
  from that payload's own `week`, so each section names the week its numbers come from.
- **Rookie watch excludes positions the sport's rules exclude**: `SportRules.rookieWatchEligible(Player)`.
  Football excludes K and DEF; basketball excludes none.
- **`ownership` gains `avatarId`** (nullable; present only when `rostered`), the owning manager's Sleeper avatar.
- **`weekly-report` `topPerformers[]` gains `team`, `opponent`, `isAway` and `avatarId`** (all nullable), so the
  home's Top players rows match the other two lists. `opponent`/`isAway` come from that week's `player_game` row;
  null means unknown, never guessed.
