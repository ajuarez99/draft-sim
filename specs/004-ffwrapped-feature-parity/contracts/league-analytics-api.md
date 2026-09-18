# Contract: League analytics HTTP API

**Stories**: US2, US3, US4, US5, US6. All read-only, all under the existing `/api` prefix, all keyed by
Sleeper league id like every current league-scoped endpoint.

Shared conventions are in [README.md](README.md). The one that governs the shapes below: **a refusal is
a distinct response, not a zero.** Every endpoint that can decline to answer carries an explicit reason,
so the UI can say why rather than rendering a confident 0.0 or a 100% efficiency.

---

## `GET /api/leagues/{sleeperId}/roster-management` — US2

```jsonc
{
  "season": 2026,
  "sport": "nfl",
  "weeksScored": 1,
  "teams": [
    {
      "rosterId": 4,
      "managerId": 17,
      "teamName": "Master Bates",
      "totalPoints": 164.96,
      "potentialPoints": 174.0,
      "efficiency": 0.943,      // null when potentialPoints is 0 — never 1.0
      "weeksCounted": 1,
      "weeksExcluded": []       // weeks dropped for missing players_points (FR-007)
    }
  ]
}
```

- Sorted by `totalPoints` descending (US2.1).
- `weeksScored: 0` returns `teams: []` with `"reason": "no scored weeks"` — the app says so rather than
  printing zeros (US2.4).
- `weeksExcluded` is carried to the client on purpose: an excluded week must be **visible**, not silently
  absent from a total (FR-007, US2.5).
- Identical shape and code path for `"sport": "nba"` (US2.3, SC-002).

---

## `GET /api/leagues/{sleeperId}/expected-wins` — US3

```jsonc
{
  "season": 2026,
  "leagueAveragePpg": 130.1,
  "teams": [
    {
      "rosterId": 6, "teamName": "jpelwell",
      "expectedWins": 0.45, "actualWins": 1, "winsAboveExpected": 0.55,
      "strengthOfSchedule": -12.4,       // opponents' PPG minus league PPG; negative = easier
      "luckSource": "SWING_WEEKS",       // or "CONSISTENT_OPPONENT_SCORING"
      "swingWeeks": [
        { "week": 1, "result": "WON", "points": 146.16, "weeklyRank": 7, "opponent": "She Hocken on my Staff" }
      ]
    }
  ]
}
```

- `luckSource` is a discriminator, and `swingWeeks` is non-empty only for `SWING_WEEKS`. The two
  explanations are alternatives, never both (US3.4).
- **Invariant asserted in test, not just documented**: `sum(expectedWins) == sum(actualWins)` within
  floating-point tolerance (SC-004). A response violating it is a bug in the model, not a rounding issue.
- Sport-neutral — nothing in this endpoint touches positions (US3.5).

---

## `GET /api/leagues/{sleeperId}/forecast` — US4

Served **entirely from the stored snapshot**. Never computed on read (FR-009, R8).

```jsonc
{
  "snapshotAt": "2026-09-18T14:41:00Z",
  "iterations": 10000,
  "teams": [
    {
      "rosterId": 4, "teamName": "Master Bates",
      "playoffOdds": 0.954,          // the SAME number the Record cell shows (FR-008)
      "averageWins": 9.92,
      "winRange": { "p10": 8.0, "p90": 12.0 },
      "averageSeed": 2.5,
      "seedOdds": { "1": 0.41, "2": 0.19, "3": 0.12 },
      "championshipOdds": 0.32
    }
  ]
}
```

Refusals, both preserving existing behaviour rather than routing around it:

```jsonc
{ "available": false, "reason": "UNMODELLED_SEEDING" }   // divisions or non-default playoff_seed_type
{ "available": false, "reason": "NO_SCORED_WEEKS" }
```

`UNMODELLED_SEEDING` is the existing rule — *"a wrong 78% is not"* the honest answer. A new endpoint must
not become a back door around it (US4.3).

---

## `GET /api/leagues/{sleeperId}/weekly-report/{week}` — US5

```jsonc
{
  "week": 1,
  "matchups": [
    { "home": { "teamName": "Master Bates", "record": "1-0", "points": 164.96 },
      "away": { "teamName": "Likely Have Downs", "record": "0-1", "points": 134.46 } }
  ],
  "topPerformers": [
    { "playerId": "4881", "playerName": "Caleb Williams", "teamName": "🕯️AJ🕯️Brown🕯️...", "points": 37.26 }
  ],
  "awards": [
    { "kind": "GOT_AWAY_WITH_IT", "teamName": "Dart has hit anotha Bower",
      "detail": "Won while using 83% of their optimal lineup, leaving 32.30 points unused." }
  ],
  "awardsOmitted": [
    { "kind": "SELF_INFLICTED_WOUND", "reason": "STARTERS_NOT_STORED" }
  ]
}
```

`awardsOmitted` is the contract's honesty mechanism. Awards naming a specific bench-for-starter swap
need `roster_week_points.starters`, which is null for any week ingested before US5's migration and never
refetched (R6). Such an award is **omitted with a stated reason, never guessed** (US5.4).

---

## `GET /api/leagues/{sleeperId}/transactions` — US6

```jsonc
{
  "season": 2026,
  "byManager": [
    { "managerId": 17, "teamName": "Master Bates",
      "counts": { "WAIVER": 2, "FREE_AGENT": 3, "TRADE": 1, "COMMISSIONER": 0 } }
  ],
  "trades": [],                          // empty array + "no trades" is a real answer (US6.4)
  "adds": [
    { "week": 1, "teamName": "Torta Pounder with Cheese",
      "added": { "playerId": "12345", "playerName": "Tyler Loop", "position": "K",
                 "postMovePositionalRank": 4, "weeksCounted": 1 },
      "dropped": { "playerId": "6794", "playerName": "Najee Harris" },
      "faabBid": 0 }
  ],
  "rankDirection": "LOWER_IS_BETTER"     // stated, not assumed by the reader (US6.3)
}
```

- `postMovePositionalRank` is the player's average positional rank over weeks **played** since the move,
  computed from `players_points` across the league, resolved through the sport's own positions (US6.5).
- `weeksCounted` accompanies every rank so a one-week sample is not read as a season verdict.
- `rankDirection` is explicit because "4" means good here and that is not self-evident.

---

## Not in this contract

`Start/Sit`, `Rate My Team`, `Player Values`, `Trade Lab` and `Player comparison` are projection-bound
and out of scope (R9). When they are built they must declare `sports: ['nfl']` explicitly — never by
default, which is the bug this repo has shipped three times (FR-005).
