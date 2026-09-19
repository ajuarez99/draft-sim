# Phase 1 Data Model: Best nights, beside best weeks

**Feature**: `005-daily-weekly-top-players` | **Date**: 2026-09-19

Migration version: **`V20`** — latest applied is `V19__league_transaction.sql`. Flyway's `outOfOrder` is
unset in `application.yml` and therefore false, so a lower version added after a higher one has been
applied fails at boot. Take the next free number at merge time rather than reserving one.

---

## Stored: `player_game`

One row per player per game. **Not league-scoped** — see [research.md](research.md) R4. Two leagues in
the same sport and season share every game and differ only in how they score it, so the row stores the
stat line and the reading league supplies the scoring.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `bigserial` | no | primary key |
| `sport` | `text` | no | `'nba'`; the table is sport-general even though only basketball reads it today |
| `season` | `int` | no | Sleeper's season label, e.g. `2025` for the 2025–26 season |
| `week` | `int` | no | the fantasy week the game belongs to, taken from the entry's own `week` field (R6), **never derived from the date** |
| `sleeper_player_id` | `text` | no | keys on the id already in `player.sleeper_id`, following V15's measured precedent; a row for a player this app has never ingested is still worth storing |
| `game_id` | `text` | no | Sleeper's own game id; the natural key's distinguishing part |
| `game_date` | `date` | no | the night, as shown in Best Nights |
| `opponent` | `text` | yes | team abbreviation, e.g. `'CHI'`; nullable because a payload without it must still store the game |
| `is_away` | `boolean` | yes | nullable for the same reason |
| `stats` | `jsonb` | no | the raw stat line as returned, scored at read time |
| `fetched_at` | `timestamptz` | no | when this row was last written; a stat correction rewrites it |

### Constraints and indexes

- `unique (sleeper_player_id, game_id)` — the natural key. A game is one player's appearance in one
  fixture, so re-running the backfill upserts rather than duplicates. This is the idempotency guarantee
  the quickstart checks by running the ingest twice.
- `index on (sport, season, week)` — the read pattern. Both sections load one week of one season.
- `index on (sport, season, sleeper_player_id)` — the backfill's own "what do I already have" check.

### Why `stats` stays raw

`scoring_json` differs per league, and the same game is worth different numbers in different leagues.
Storing a precomputed figure would either bake one league's settings into a shared cache — the defect
`V15__player_projection.sql`'s comment exists to warn about — or require a row per league per game.
Scoring at read time costs one multiply-add per scoring key over ~600 rows for a week, which is nothing.

### What is deliberately not stored

- **No per-league points column.** See above.
- **No `week_shard`.** The payload carries it and it appears to encode a day index, but its meaning was
  not confirmed (R6) and nothing in this feature needs it. Storing an unexplained field invites someone
  to rely on it.
- **No roster or ownership.** Who owned a player is a league-and-week fact that `roster_week_points`
  already answers; duplicating it here would be a second source for it.

---

## Computed: not stored

These are assembled per request from `player_game` plus the reading league's `scoring_json`. None is
persisted, because each is a reading of stored facts rather than a fact.

### Game performance

One player's single game, scored for the asking league.

| Field | Derivation | Rule |
|---|---|---|
| `points` | `sum(scoring_json[k] * stats[k])` over scoring keys | generic key-by-key, never Sleeper's `pts_std` (R3) |
| `date` | `player_game.game_date` | the night |
| `opponent`, `isAway` | direct | may be absent; render as unknown rather than guessed (FR-006) |
| `playerName`, `position` | resolved from `player` | a player the app has not ingested is shown by whatever identity is available, not dropped silently |
| `teamName` | the roster that held the player that week, via `roster_week_points` | ownership at scoring time, matching how `topPerformers` already attributes |

### Player week

One player's whole fantasy week.

| Field | Derivation | Rule |
|---|---|---|
| `totalPoints` | sum of that player's game performances for the week | **includes games the league's scoring did not count** (R5), which FR-005 requires the page to state |
| `gamesPlayed` | count of those games | shown beside the total, so the number is never read without its denominator |
| `complete` | whether per-game detail was obtained for the whole week | false forces the honest-refusal path in FR-006 rather than a quiet undercount |

A player with zero games in the week is **absent**, not present with `totalPoints: 0` — a spec edge case.

### Ranking entry

A placed row in either section: the player, the owning team, the figure ranked, and which measure it is.

**Ordering (FR-009)**: by points descending, then by `sleeper_player_id` ascending as the tiebreak, then
— for Best Nights only, where one player can hold two rows — by `game_id` ascending. Ties are exact
often enough in a half-point scoring system that leaving order to the database would make two loads of
the same week disagree.

---

## Behaviour rule, not data: scoring-period cadence

`SportRules` gains one method answering whether a player can play more than once per scoring period.
It has **no default implementation** (research R7), so a future sport must answer it to compile —
the same forcing function feature 004 applied to `startingLineup`.

| Sport | Answer | Consequence |
|---|---|---|
| Football | cannot | Weekly Report renders exactly today's `topPerformers` list; neither new section appears |
| Basketball | can | the pair replaces `topPerformers` |

This is the only place the two forms are chosen between. No service or component compares a sport name
(FR-004).
