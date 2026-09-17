# Phase 0 Research: League history record book

**Date**: 2026-09-16 · **Branch**: `002-league-history-record-book`

Every number below was measured against the local Postgres (`draftsim-pg`, up and healthy) on
2026-09-16, not estimated. The reference screenshot of ffwrapped's League History page for
(Foot) Ball Knowers is used as an independent check on those numbers, and it agrees — which is how
we know we are reading the right column.

---

## Measurement 1 — What is actually stored, per league-season

```sql
select l.id, l.sport, l.season, l.name,
       (select count(*) from roster_week_points w where w.league_id=l.id) as week_pts,
       (select count(distinct w.week) from roster_week_points w where w.league_id=l.id) as weeks,
       (select count(*) from league_matchup m where m.league_id=l.id and m.matchup_id is not null) as fixtures,
       (select count(*) from power_ranking p where p.league_id=l.id) as pr_snaps
from league l order by l.name nulls last, l.season;
```

| id | sport | season | name | week_pts | weeks | paired fixtures | pr snapshots |
|---|---|---|---|---|---|---|---|
| 5 | nfl | 2025 | (Foot) Ball Knowers | 204 | 17 | **8** | 3 |
| 4 | nfl | 2026 | (Foot) Ball Knowers | 12 | 1 | 168 | 2 |
| 212 | nba | 2024 | Ball Knowers | 288 | 24 | **8** | **0** |
| 211 | nba | 2025 | Ball Knowers | 252 | 21 | **8** | **0** |
| 210 | nba | 2026 | Ball Knowers | 0 | 0 | 0 | **0** |
| 2 | nfl | 2025 | West Coast Fantasy Football | 216 | 18 | **0** | **0** |
| 1 | nfl | 2026 | West Coast Fantasy Football | 0 | 0 | 0 | **0** |

Scores are complete. Pairings and power rankings are not.

---

## Decision D1 — Score extremes ship first, with no backend risk

**Decision**: build the high/low weekly-score panels directly over `roster_week_points`, spanning
every league id in the chain, with no ingest and no migration.

**Rationale**: the data is complete for every played season (17, 21, 24 weeks). Measured against
the reference screenshot, it is also *correct*:

```sql
select w.season, w.week, w.roster_id, w.starters_points
from roster_week_points w join league l on l.id=w.league_id
where l.name like '(Foot)%' order by w.starters_points desc limit 5;
```

| season | week | roster | starters_points |
|---|---|---|---|
| 2025 | 8 | 7 | **205.04** |
| 2025 | 7 | 5 | 203.24 |
| 2025 | 12 | 5 | 192.46 |
| 2025 | 13 | 8 | **191.38** |
| 2025 | 16 | 8 | **190.00** |

The screenshot's high-score list (scrolled partway) reads `191.38 / 2025 / 13`, `190 / 2025 / 16` —
exact matches. Its biggest blowout card reads "Justice for Wags **205.04**" in 2025 week 8, which is
our top row. On the low side the screenshot shows `76.9 / 2025 / 17` and `77.02 / 2025 / 5`; our
query returns 76.90 at 2025 week 17 and 77.02 at 2025 week 5.

`starters_points` is therefore the right column, and it is the same number the reference product
displays. No further verification of the source column is needed.

**Alternatives considered**: recomputing totals from the `players_points` JSONB — rejected, it
would be a second implementation of a number already stored, and the ingest comments record that
`points` was verified live to equal `sum(starters_points)`.

**Note for implementation**: our low list would surface `40.68` (2025 week 17) as the record low,
which the reference page does not show in the visible region. Week 17 is a week where eliminated
managers stop setting lineups. Whether to include the final week in the low-score record is a
product judgment; this plan includes it (it is a real score) and does not special-case it, because
special-casing a week count is exactly the multi-sport trap in FR-011.

---

## Decision D2 — Matchup margins are blocked by a cache gate, not by missing data upstream

**Decision**: repair `LeagueHistoryIngestService#ingestWeeklyPoints` before building the margins
panel, and prove the repair by query before writing any UI.

**Rationale**: pairings exist for exactly one week per past season:

```sql
select l.name, l.season, m.week, count(*) from league_matchup m join league l on l.id=m.league_id
where m.matchup_id is not null group by 1,2,3 order by 1,2,3;
```

| league | season | weeks with pairings |
|---|---|---|
| (Foot) Ball Knowers | 2025 | week 17 only (8 rosters) |
| (Foot) Ball Knowers | 2026 | weeks 1–14 (12 rosters each) |
| Ball Knowers (NBA) | 2024 | week 24 only (8 rosters) |
| Ball Knowers (NBA) | 2025 | week 21 only (8 rosters) |
| West Coast Fantasy Football | 2025 | none |

The cause is a single line:

```java
Set<Integer> stored = weekPoints.storedWeeks(leagueId);
for (int week = 1; week <= lastScoredLeg; week++) {
    if (stored.contains(week) && week != lastScoredLeg) continue;   // <-- gate
    ...
    weekPoints.upsert(...);
    fixtures.upsert(...);   // <-- lives inside the gated loop
}
```

`league_matchup` was added on 2026-09-14 for playoff odds. Every season ingested before that date
already had complete `roster_week_points`, so on every subsequent run the gate skips every week
except `lastScoredLeg` — which is precisely the one week that has pairings. **Re-running the ingest
does not repair this and never will**, because the skip is keyed on the table that is already full.
The 2026 season is the exception only because it was first ingested after the table existed, and
its future weeks come from `ingestRemainingFixtures`, a different path.

This is the same failure class the repo has already recorded for `adp_at_time`: a cache check
standing in front of a column that did not exist when the cache was filled.

The data is obtainable — the reference page shows a 2025 **week 4** margin of 0.36 and a 2025 week 17
margin of 0.16, so Sleeper still serves those pairings.

**Chosen repair**: gate the two upserts independently. A week should be fetched when its points are
missing **or** its pairing is missing, using `LeagueMatchupRepository#scheduledWeeks` (which already
exists and already refuses to count all-null weeks as cached) as the second condition.

**Alternatives considered**:
- *Drop the gate entirely.* Rejected — it re-fetches every week of every season from Sleeper on
  every ingest, which is the cost the gate was added to avoid.
- *One-off backfill script.* Rejected — it fixes this database and leaves the bug in place for the
  next league anyone ingests.

---

## Decision D3 — Season final rank needs a backfill, and the week-0 baseline is a trap

**Decision**: source the final rank from the `COMPUTED_REALIZED` snapshot at a season's **last
scored week**, and add a user-triggered backfill for seasons that have none.

**Rationale**: only one played season has any power rankings at all:

| league | season | week | kind | entries |
|---|---|---|---|---|
| (Foot) Ball Knowers | 2025 | 0 | COMPUTED_REALIZED | 12 |
| (Foot) Ball Knowers | 2025 | 8 | COMPUTED_REALIZED | 12 |
| (Foot) Ball Knowers | 2025 | 17 | COMPUTED_REALIZED | 12 |
| (Foot) Ball Knowers | 2026 | 0 | COMPUTED_REALIZED | 12 |
| (Foot) Ball Knowers | 2026 | 1 | COMMISSIONER | 12 |

Three of four played seasons have zero. The cause is structural, not accidental:
`POST /leagues/{sleeperId}/power/compute` is the only writer, and it resolves **one** league row —
so it has only ever been run against the current season's Sleeper id. A predecessor season is a
different `league.id` and was never a target.

The backfill is cheap and pure: `PowerRankingService#computeRealized(leagueId, season, week)` reads
only `weekPoints.through(leagueId, week)`, which is complete for every played season. No Sleeper
call is required to produce a past season's final rank.

**Two traps the measured data contains**, both of which must be handled in code:

1. **Week 0 is not a final rank.** It is the preseason baseline, written once by
   `computeWeek0IfMissing`. 2026 has *only* a week-0 snapshot. Naïvely selecting `max(week)` for a
   season would be correct for 2025 and would silently present the preseason baseline as 2026's
   final standing. Selecting the final rank MUST require `week > 0`.
2. **`COMMISSIONER` snapshots share the table.** 2026 week 1 is a commissioner ranking. A
   kind-agnostic `max(week)` would return an opinion as a result. The lookup MUST filter to
   `COMPUTED_REALIZED` (FR-004, and spec US2 scenario 4).

**Alternatives considered**:
- *Compute the rank on read.* Rejected — this repo has an explicit, repeated convention that
  rankings and odds are never computed on a page load.
- *Show the standalone Power Rankings page's data.* Rejected — `PowerRankingService#snapshots` is
  scoped to a single `league.id`, i.e. a single season, so it cannot answer a cross-season question.

---

## Decision D4 — Records attribute to Sleeper usernames, not team names (fidelity gap)

**Decision**: attribute record rows to `manager.display_name` and the existing avatar, consistent
with every other page in this app. **Do not** block the record book on team names.

**Rationale, and the gap this leaves**: the reference page labels every record with a *team name* —
"Puka-Boo", "Justice for Wags", "Torta Pounder with Cheese". This database stores no team name
anywhere. `manager` holds `sleeper_user_id / display_name / avatar_id`, and `roster_season` holds no
name column at all. Mapping the screenshot back:

| roster | our `display_name` | reference team name |
|---|---|---|
| 7 | `GraftonCarlson` | Justice for Wags |
| 8 | `theadambomb98` | Puka-Boo |
| 12 | `GrandmasBeefRagu` | Torta Pounder with Cheese |

So a record book built today reads "GraftonCarlson — 205.04", where the reference reads "Justice for
Wags — 205.04". The records are correct; the labels are the app's existing identity rather than the
league's chosen one.

**UPDATE 2026-09-16, during implementation — this decision is now final, and Slice D is
WITHDRAWN.** Two measurements, both of which contradict the paragraph this replaces:

**1. No migration was ever needed.** `league_member` already has a `team_name` column and is keyed
`(league_id, manager_id)` — and `league_id` is a league-season. The ingest already populates it via
`LeagueMapper.teamName(u, display)`, including the fall-back-to-display-name rule. So the storage
Slice D proposed adding already existed.

**2. But the stored names are the CURRENT ones, not that season's, and Sleeper cannot give us the
historical ones.** Queried live against the 2025 league's own Sleeper id
(`GET /v1/league/1254190892974084096/users`):

| roster | display_name | Sleeper's team_name *today* | reference page shows for 2025 |
|---|---|---|---|
| 7 | GraftonCarlson | `i Chase Brown kids` | **Justice for Wags** |
| 8 | theadambomb98 | `Republic of Azer-Bijan` | **Puka-Boo** |
| 12 | GrandmasBeefRagu | `Kraft Mac n' Cheese` | **Torta Pounder with Cheese** |
| 1 | popsharky | `TBD` | **Dart has hit anotha Bower** |
| 11 | njerickson | `Generational St. Brown` | **Likely Have Downs** |

`metadata.team_name` is mutable and carries the present value even when you ask about a past
league. The 2025 names are simply gone. The reference product shows them because it captured them
while that season was live; nothing in this database or in Sleeper's API can reconstruct them now.

**Consequence**: Slice D's acceptance criterion (quickstart "roster 7 in 2025 renders as `Justice
for Wags`") is **unreachable**, and implementing it anyway would be worse than leaving it: it would
label a 2025 record with a 2026 team name as though that were the team's name at the time —
attributing the league's biggest-ever week to a team that did not exist under that name. That is
the same class of quiet wrongness as the pre-repair margins panel, which confidently reported a
blowout of 67.6.

**Decision**: records attribute to `manager.display_name`, which is stable, is the identity every
other page in this app uses, and is never wrong. Slice D is not implemented.

**If the league wants historical names later**, the fix is forward-only: snapshot `team_name` onto
`roster_season` at ingest time so that *future* seasons freeze their labels. It cannot recover
2024 or 2025.

**Alternatives considered**: deriving a team name from the draft board or league settings —
rejected, neither carries it. Using today's name as a historical label — rejected on the
misattribution grounds above.

---

## Decision D5 — Extend the existing history endpoint rather than add new ones

**Decision**: attach records, margins and final rank to `GET /api/leagues/{sleeperId}/history`.

**Rationale**: that method already does the one hard thing every panel needs — it walks
`LeagueRepository#chainBySleeperId`, which follows `previous_league_id` backwards to assemble the
chain, and it already holds each season's internal `league.id`. A separate `/records` endpoint would
re-resolve the same chain, repeat the same visibility check (`visibleLeague`, which enforces league
scoping added during multi-user onboarding), and turn a one-request page into three. Aggregate
volume is trivial — the largest per-league population measured is 288 rows.

**Alternatives considered**:
- *Separate `/records` and `/margins` endpoints.* Rejected on the round-trip and duplicated-scoping
  grounds above. Worth revisiting only if the record book grows expensive enough to want its own
  cache, which 288 rows is not.
- *Compute in the frontend from raw week points.* Rejected — it would ship every roster-week to the
  browser and put the aggregation where it cannot be unit-tested against Postgres.

---

## Resolved unknowns

| Unknown from Technical Context | Resolution |
|---|---|
| Is per-week score data present for past seasons? | Yes — complete for all played seasons (D1) |
| Is `starters_points` the displayed total? | Yes — matches the reference product exactly (D1) |
| Are matchup pairings available for past seasons? | No — one week per season, and re-ingest cannot fix it (D2) |
| Do past seasons have power rankings? | No — 3 of 4 played seasons have zero; backfillable (D3) |
| How is "final rank" identified? | `COMPUTED_REALIZED`, `week > 0`, max week for the season (D3) |
| Are team names available? | No — not stored; needs a migration (D4) |
| New endpoint or extend? | Extend `/leagues/{id}/history` (D5) |
| Does a migration exist for this work? | Only for D4/Slice D. Slices A–C need none. Head is `V15` |

## Open risk

The record book's correctness depends on `roster_week_points` row counts being complete rather than
merely present. West Coast Fantasy Football 2025 has 216 rows over 18 weeks for 12 rosters — that is
exactly 12×18, so complete. (Foot) Ball Knowers 2025 has 204 over 17 weeks for 12 rosters — exactly
12×17. NBA 2024 has 288 over 24 for 12 — exact. No partial weeks were found in this database, but
nothing enforces it, so the aggregation must tolerate a week that is missing rosters rather than
assuming a full slate.
