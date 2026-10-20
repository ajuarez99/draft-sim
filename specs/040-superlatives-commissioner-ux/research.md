# Research: spec 040 — awards commissioner's list, every player, fairer Embiid cost

All file:line references are to `main` at 7736114 (this branch's base).

## R1 — How big is the Embiid change, measured (2026-10-20, local DB, NBA 2025)

Season-wide over every walked NBA player (not yet scoped to rostered regular contributors), weeks 1–18,
`basis <> 'UNCLASSIFIED'`:

| | count |
|---|---|
| player-weeks with ≥1 missed game | 5,104 |
| missed games in those weeks (today's cost units) | 11,569 |
| weeks with **zero** games played (new cost units) | 2,579 |
| missed games inside those zero-play weeks | 7,578 |
| partial weeks (missed ≥1, played ≥1) — cost nothing under the new rule | 2,525 (3,991 games) |

So today's rule charges ~4.5 cost units for every one the new rule charges. With the per-week value ~1.15× the
per-game value (R2), NBA totals should land around **a quarter** of today's. That is an indicator, not a
prediction of any roster's number — the real per-roster before/after is measured in verification (SC-002).

## R2 — What a missed week is worth

> **Amended 2026-10-20 after plan review F1 + Allan's answer.** Decision is now the existing **per-game mean**
> (`pointsPerGame`, re-scored `player_game` in the window), charged once per counted week. The credited-weekly
> mean below was rejected: it can only sample weeks the player was rostered in this league, so on NFL league 5 it
> moved 9 of 12 rosters by > 0.1 pt (review's proxy; up to -11.43), some per-week figures resting on one week.
> Measured credited-vs-per-game gap in NBA 2025: +11.9% (review), not "14-19%". The original text stays below.

- **Original decision (superseded)**: the player's **mean credited weekly score** — the mean of this league-season's stored
  `players_points[pid]` over regular-season weeks in which he played ≥1 game (a `player_game` row in the window)
  and appeared in any roster's `players_points`.
- **Rationale**: it is the league's own number. In basketball Sleeper credits one game per week and
  `starters_points` = Σ starters' `players_points` in 540/540 roster-weeks (memory, measured 2026-10-05); the
  credited game beats the week's average game by ~14–19%, so a per-game mean would understate a missed week.
  It also matches Allan's words ("the average of those many points in a week").
- **Alternatives**: keep `pointsPerGame` (re-scored `player_game` mean) — exact for football, but the wrong unit
  for basketball's credited week. Rejected for basketball; see R3 for football.
- **Football (FR-003)**: one game per week, so a credited week *is* that game. The two means should agree except
  where the stored `players_points` and the re-scored `player_game` stats disagree (stat corrections after ingest,
  or a scoring key `GameScoring` doesn't cover). **Not measured yet** — verification computes both on both NFL
  2025 leagues. If they drift by more than 0.1 on any roster, SC-003 is amended visibly with the measured
  numbers; the rule is not forked per sport (one implementation per rule — the multi-sport landmine class).

## R3 — Which weeks count

- **Decision**: a (roster, player, week) counts iff the roster held him that week (key in its `players_points`),
  he is a regular contributor (unchanged `AbsenceCost.isRegularContributor`), the week is in the regular-season
  window, he has ≥1 non-UNCLASSIFIED `player_absence` row that week, and **zero** `player_game` rows that week.
  Cost per counted week = R2's value, once.
- **Rationale**: Allan's clarification (2026-10-20): a partial basketball week costs nothing. Football: an
  absence row already implies no game that week, so the counted weeks are identical to today's.
- All-Star break / no game scheduled: no absence row exists (absence rows mean "his team played and he didn't"),
  so it never counts — unchanged.
- A player with a mean of 0 / no credited weeks (season-long injury from week 1): not a regular contributor
  today, still excluded.
- **Superseded rule** (spec 008 R10, Allan 2026-09-23: "as a player you want every single game anyways"):
  `AbsenceCost`'s class comment gets a dated "superseded 2026-10-20 by spec 040" note, and the project memory
  that says "don't re-propose week-level costing" gets corrected — Allan reversed it himself.

## R4 — Commissioner's list: player picker source

- **Decision**: candidates = union of `roster_season.players` (V28, current rosters; present for NBA 2026 league
  210: 187 players across 12 rosters, fetched 2026-10-11) and every player id in this league-season's
  `roster_week_points.players_points`, each with name/position/NBA-or-NFL team from `player`, and the fantasy
  team currently holding him (from `roster_season.players`) where known. Sent on the existing
  `GET /conduct-list` response — no new search endpoint, filtered client-side (≤ ~250 rows).
- **Rationale**: the award can only ever charge a player a roster held; a whole-sport search is out of scope
  (spec). Both sources are needed: `players` covers a new season with no scored week; `players_points` covers
  players dropped before the latest roster fetch.
- **Alternative**: a server-side typeahead over `player` — rejected, more surface for no reachable benefit.

## R5 — Editing the new season's list before its first scored week

- Today the backend already accepts writes to any visible league row; the lock is the frontend addressing
  `data.leagueSleeperId` (the resolved, older season) (`Superlatives.tsx:155-165`).
- **Decision**: the panel edits the league row the **URL** names (the season the user navigated to). When the
  resolver walked back (`requestedSeason != null`), the panel header names its own season and says the award
  cards above still show the older season and this list starts counting once the new season has a scored week.
  When viewing an older season via a season-scoped link, the URL row *is* that season — unchanged behaviour.
- Rationale: the bug spec 008's code review fixed (editing a season nothing on screen showed) is avoided by
  *labelling* which season the panel edits rather than by locking it.

## R6 — End week and editing

- **Decision**: V38 adds `applies_through_week int null` with `check (applies_through_week is null or
  applies_through_week >= applies_from_week)`. Null = rest of season, so existing rows keep today's meaning.
  New `PUT /conduct-list/{entryId}` edits reason/from/through in place (player is fixed; changing the player is
  remove + add). POST keeps its upsert-on-player semantics.
- V38 is free: no branch, local or `origin/*`, carries a V38+ migration (checked 2026-10-20; 038 has none).

## R7 — Unethical player-weeks double count (found while planning)

- `unethicalSuperlative` sums `weeks().size()` over every `ConductQualifyingWeeks` row
  (`SeasonSuperlativesService.java:1242`), and a player can have one SUSPENDED and one COMMISSIONER row for the
  same weeks — the same player-week counts twice. Harmless while no rostered player was ever suspended, but a
  commissioner entry *for* a suspension (the list's main use) would double it the moment Sleeper also tags him.
- **Decision**: totals and the players view count distinct (player, week) per roster; each row keeps listing
  its sources. Flagged as a behaviour change for review, with a preference-ordering test.

## R8 — Players view payload

- **Decision**: a new `playerContributions` list on `Superlative`, populated for JOEL_EMBIID,
  WAIVER_WIRE_WARRIOR and UNETHICAL: every (roster, player) with a non-zero figure, competition-ranked across the
  league; `[]` for every other kind. JABARI_SMITH_JR's existing `playerStandings` drops its top-10 cap
  (`SeasonSuperlativesService.java:832`); the frontend's own 10-row fold in `SuperlativeStandings.tsx` stays
  as the "Show all" control.
- Payload size: NBA 2025 has ~180 rostered players; worst case a few hundred rows across three kinds. Fine.
- Card detail (top 3 per holder) is unchanged in shape, re-pointed at the new Embiid fields.
