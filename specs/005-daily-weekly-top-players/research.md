# Phase 0 Research: Best nights, beside best weeks

**Feature**: `005-daily-weekly-top-players` | **Date**: 2026-09-19

All findings below were measured against the live Sleeper API and this repo's database on 2026-09-19,
not inferred. Where a number appears, the command that produced it is named.

---

## R1. Per-game data exists, and one call covers a whole season

**Decision**: Source per-game NBA data from the per-player stats endpoint, grouped by week.

**Measured**: `GET api.sleeper.app/stats/nba/player/{playerId}?season_type=regular&season=2025&grouping=week`
returns an object keyed by fantasy week (`"1"`..`"25"`), each value a **list of per-game entries**. Each
entry carries everything this feature needs:

| Field | Example | Used for |
|---|---|---|
| `date` | `"2025-11-17"` | the night in Best Nights |
| `opponent` | `"CHI"` | who it was against |
| `is_away_team` | `true` | home/away |
| `week` | `5` | which fantasy week the game belongs to |
| `game_id` | `"1261029460694540288"` | natural key, dedupe |
| `stats` | full box score | league-scored points (R3) |

**Rationale**: the retrieval cost is far lower than the spec feared. The endpoint returns the *entire
season* in one response, so the cost is **one call per player per season**, not per player per week.

**Alternatives considered**:

- `GET /v1/stats/nba/regular/{season}/{week}` (bulk, all players for a week). Rejected: measured 557
  players and **no `date` field** — week-aggregated stats only. It cannot answer "which night".
- `GET /v1/stats/nba/regular/{season}?date=YYYY-MM-DD`. Rejected: the `date` parameter is **ignored**.
  Two requests for different dates returned byte-identical bodies (same md5), both season totals.
- `?grouping=day`. Rejected: returns a single collapsed object with `"date": null` and `gp: 65` — season
  totals, not days.

## R2. The cost, in actual numbers

**Decision**: Treat a league-season backfill as a bounded batch job of a few hundred calls.

**Measured** (distinct players appearing in `players_points` across the season):

```
league 1229352720222134272 (nba 2025) -> 331 distinct players
league 1141438340626231296 (nba 2024) -> 280 distinct players
```

**Rationale**: ~331 calls backfills an entire season of per-game detail for a league. That is a batch
ingest, comparable to the transaction walk, and nothing like a per-page-load cost. FR-008 forbids doing
this while a reader waits, and nothing here requires it.

**Note**: the player set is league-scoped only in how it is *chosen*. The rows themselves are not — see
R4.

## R3. League points are reproducible from raw stats, exactly

**Decision**: Compute each game's points as `sum(league.scoring_json[k] * stats[k])` over the scoring
keys, generically — never by reading Sleeper's precomputed `pts_std`.

**Measured**: the league's `scoring_json` is
`{pts: 0.5, reb: 1.0, ast: 1.0, stl: 2.0, blk: 2.0, tpm: 0.5, to: -1.0, dd: 2.0, td: 3.0, ff: -2.0,
tf: -2.0, bonus_pt_40p: 2.0, bonus_pt_50p: 2.0, bonus_ast_15p: 2.0, bonus_reb_20p: 2.0}`.

Applying it to per-game `stats` reproduced the stored weekly value **exactly, to the decimal, in 18 of
18 weeks across two players**:

```
Jokić  wk5  stored 58.5  -> games [58.5, 34.0, 44.0, 45.5]
Harden wk1  stored 29.5  -> games [21.5, 29.5, 31.0]
Harden wk8  stored 25.0  -> games [25.0]
```

**Rationale**: the arithmetic is settled, and the generic key-by-key form means a league with different
scoring categories (field goals, three-point attempts, a different bonus set) is served without code
changes. `pts_std` is Sleeper's *standard* scoring and does not match this league — for Jokić's four
week-5 games it gives 56.5/32.0/43.0/44.5 against the league's 58.5/34.0/44.0/45.5.

**Alternatives considered**: store precomputed points per league. Rejected on R4's grounds.

## R4. Storage follows V15's precedent, not `roster_week_points`'s

**Decision**: One row per (sport, season, player, game), holding the raw stat line and the game's
identity. **Not league-scoped.** Points are computed at read time from the reading league's scoring.

**Rationale**: this is exactly the argument `V15__player_projection.sql` already makes in its own
comment — *"Storing only the one today's league uses would bake a league setting into a cache shared by
every league."* Two leagues in the same sport and season share every game; only their scoring differs.
Keying by `sleeper_player_id` follows the same precedent, which measured that the stats endpoints key on
the id already in `player.sleeper_id` (confirmed again here: `player_id: "1658"` matches the
`players_points` key `"1658"`).

Scoring at read time is cheap: a week is ~600 game rows for a 12-team league, and the sum is one
multiply-add per scoring key.

**Alternatives considered**:

- Per-league precomputed points. Rejected: duplicates every row per league and re-introduces the bug
  class V15 was written to avoid.
- Extending `roster_week_points`. Rejected: that table is one row per roster-week, and this is one row
  per player-game — a different grain entirely.

## R5. Which games belong in Best Nights — all of them

**Decision**: Best Nights ranks **every game played in the week**, not only the one the league's scoring
counted.

**Rationale**: this is forced, not preferred. The mechanism by which this league's scoring selects one
game per player per week **is not understood** — measured across 18 weeks it is not the first, not the
last, not the highest. Ranking "the counted game" would mean ranking a set chosen by a rule nobody can
explain, which is worse than showing all games and saying so. FR-005 already requires the page to state
that these figures are real-world production rather than points that decided a matchup, and that
disclosure covers both sections.

**Consequence for the spec's framing**: the Problem section describes Best Nights as "largely naming
what is already on screen". That is true of the *data path* — the points come from the same games — but
the list itself will contain entries the current Top performers list does not, because a player's best
night may be a game the league did not count. This is the intended behaviour, not drift.

**Alternatives considered**: restrict to the league-counted game. Rejected as above. It would also make
Best Nights and Best Week trivially related for this league, since the counted game is exactly one game.

## R6. Week membership comes from Sleeper's own label

**Decision**: A game belongs to the fantasy week in the entry's own `week` field.

**Measured**: the stored league value matched a game inside the same-numbered stats week in 18 of 18
cases, so the league's leg numbering and the stats `week` numbering agree for this league.

**Rationale**: trusting the field rather than deriving a week from the date also settles the spec's
postponement edge case for free — a game moved into another week carries the week it was actually
played. Entries additionally carry a `week_shard` (e.g. `"1_5"`, `"1_0"`) which appears to encode a day
index; it is **not** used here, because nothing in this feature needs it and its meaning was not
confirmed.

## R7. "Can a player play more than once per scoring period" is a sport rule

**Decision**: Add a non-defaulted method to `SportRules` and implement it in both sports.

**Measured**: `SportRules` today declares `sport()`, `prepareLineup`, `rosterNeed`, `lineupValue`,
`value`, `startingLineupValue`, `startingLineup`, `isDraftable`, `isEligible`. There is no concept of
scoring-period cadence anywhere in the interface.

**Rationale**: FR-004 requires the choice of which form to render to follow from the sport's rules, never
a sport name compared in a service or component. This repo's memory records the same defect three times
over — *"a defaulted reversalRound/sport asserts a rule, not a value"* — so the method takes **no default
implementation**: a future sport must answer it to compile, exactly as US1 of feature 004 forced
`startingLineup` to be answered.

**Alternatives considered**: infer it from `roster_positions` or from observed game counts. Rejected:
both are data about one league, being used to assert a rule about a sport.

## R8. Football is untouched, and that is a testable property

**Decision**: The new sections are additive and gated by R7's rule; the existing `topPerformers` payload
and its rendering are not modified.

**Rationale**: the user's decision (2026-09-19) is that football keeps its current list exactly. The
cheapest way to guarantee that is to not touch the code path — the weekly report keeps emitting
`topPerformers`, and the new fields are simply absent for a sport whose players play once per period.
SC-004 is then verifiable by asserting the football response shape is unchanged.

---

## Resolved unknowns

| Spec question | Status |
|---|---|
| Is per-game data with dates obtainable? | **Yes** (R1) |
| What does retrieval cost? | ~331 calls per league-season, one per player (R2) |
| Can league-scored per-game points be derived? | **Yes, exactly** (R3) |
| Where do the rows live? | New table, not league-scoped, V15's pattern (R4) |
| Which games count toward Best Nights? | All games in the week (R5) |
| How is a game assigned to a week? | Sleeper's own `week` field (R6) |
| How is the sport decision made? | New non-defaulted `SportRules` method (R7) |

**No NEEDS CLARIFICATION remain.**

## Risks

1. **Stat corrections move settled numbers.** Measured: two rosters' season totals differ from Sleeper's
   own `fpts` by ~25–50 points, consistent with corrections landing after ingest. A "fetch once, never
   refetch" rule would freeze stale games. Mitigation: follow `roster_week_points`'s discipline of
   always refetching the most recent scored week, and make the backfill re-runnable per season.
2. **331 calls is polite but not free.** A full-season backfill should be a deliberate batch operation,
   not part of the per-league ingest chain that a user triggers casually. Sequencing matters more here
   than in 004's transaction walk, which was bounded by weeks rather than players.
3. **The per-player endpoint is undocumented**, like the projections endpoint V15 depends on. It may
   change shape without notice. Mitigation: the same one V15 accepted — store what it returns, and fail
   visibly rather than silently when the shape stops matching.
4. ~~**Only one NBA league-season has been examined in depth.**~~ **CLOSED 2026-09-19 by T004.** R3 and
   R6 were re-asserted against `1141438340626231296` (season 2024) and both hold: the stored weekly value
   equalled a computed game in **10 of 10 weeks**, including a week where the player's only two games
   were both 0.0.

   The check was stronger than a repeat, because that league's scoring is **different**: `dd: 1.0` and
   `td: 2.0` against the 2025 league's `2.0` and `3.0`, and it has no `bonus_ast_15p` or
   `bonus_reb_20p` at all. Reproducing both leagues with one generic key-by-key sum is evidence the
   approach is not fitted to a single league's settings — which is exactly what R3 claimed and had not
   yet earned.
