# Research: A history deep enough to argue with

Everything below was measured on 2026-09-21 against the local Postgres (`draftsim-pg`, healthy), the
backend running on `:8080`, and Sleeper's public API. Numbers quoted here are what those calls
returned, not estimates.

---

## R1 — Where the sport goes on a manager's season row

**Decision**: add `sport` (and the league's name) to `RosterSeasonRepository.StandingRow`, sourced from
`league.sport`, and group by it in both the endpoint and the page. No new table, no new endpoint.

**Rationale**: the column already exists on `league` and the query already joins that table.

```sql
-- RosterSeasonRepository#forManager, today
select rs.league_id, rs.roster_id, rs.manager_id, m.display_name, m.avatar_id,
       rs.wins, rs.losses, rs.ties, rs.points_for, rs.points_against, rs.final_placement,
       l.season, l.sleeper_id
from roster_season rs
join league l on l.id = rs.league_id      -- l.sport is right here and unselected
```

`StandingRow` is shared with `forLeague`, which supplies `season`/`sleeperLeagueId` as null via a
`withSeason` flag on `mapRow`. Adding `sport` the same way keeps one row type; adding a second row type
would be the second implementation of "a manager's standings row", which is the class of bug this repo
has now shipped under four different names.

Verified this is a live defect rather than a theoretical one — `GET /api/managers/7/history` returns
six seasons across three leagues and two sports, three of them labelled 2026, with NBA `pointsFor`
of 5146.00 in the same column as NFL's 2042.84, and `ManagerHistory.tsx` sums all six into its header.

**Alternatives considered**:

- *Infer the sport from the league name.* Rejected: "Ball Knowers" and "(Foot) Ball Knowers" differ by
  a parenthesis, and a third league could be named anything.
- *A separate `/managers/{id}/history?sport=nba` endpoint.* Rejected: it makes the caller know a sport
  to ask a question about a person, which is the exact mistake the existing `draftHistory` list was
  written to avoid (its comment says so: *"a manager is not a football manager"*).

---

## R2 — How to know a season has finished, and how to un-crown a champion

**Decision**: store Sleeper's league `status` on the `league` row (migration **V21**), gate the champion
write on `status == "complete"`, and make the standings upsert able to write `final_placement = null`
over a previously-stored value.

**Rationale**: three separate facts forced this.

1. `LeagueHistoryIngestService#ingestStandings` sets `final_placement = 1` from
   `league.metadata.latest_league_winner_roster_id`. Sleeper carries that key on the **new** season's
   league object, meaning *most recent winner*. Verified live:

   ```
   GET https://api.sleeper.app/v1/league/1346366555759341568
     "season": "2026", "status": "in_season",
     "metadata": { "latest_league_winner_roster_id": "1" }
   ```

   Roster 1 is popsharky, who won 2025. He is stored as champion of 2026 after one week. Same for
   gregmullen in West Coast. Two of the six stored placements in the database are wrong.

2. **`status` is not stored anywhere today.** `league.settings_json` holds Sleeper's `settings`
   sub-object; `status` is a top-level field on the league object and is dropped by `LeagueMapper`.
   Confirmed: `select settings_json ? 'status' from league` is false for all nine rows.

3. The only current proxy for "this season is still being played" is positional — `history()` passes
   `i == 0` (newest in the chain) to `PowerRankingService.finalRankForSeason`. That is a heuristic that
   silently becomes wrong the moment a chain's newest season completes, and FR-007 needs a real
   per-season completeness flag anyway, at query time, for the "how many seasons does this figure
   cover" rule that every career number depends on.

The upsert must **clear**, not skip: `roster_season` already carries the wrong value, and
`RosterSeasonRepository.upsertAll`'s `on conflict ... do update set final_placement = excluded.final_placement`
will do it correctly once the computed value is null — but only if the ingest still writes the row. This
is the same shape as the `adp_at_time` and `league_matchup` lessons: a skip gate in front of a column
means re-running never repairs it.

**Alternatives considered**:

- *Read `status` from the in-flight league map at ingest and store nothing.* Rejected: it fixes the
  write but leaves every reader with no way to ask whether a season finished, which FR-007 requires.
- *Parse `winners_bracket` for full placement (1st, 2nd, 3rd…).* Rejected for this feature, as 002
  rejected it: it needs the whole bracket tree, and nothing here asks for anything but the champion.
  Storing `status` is what makes it addable later without a second correction pass.
- *Treat `status` as advisory and infer completeness from "the last scored week equals the league's
  final week".* Rejected: it is a derived guess standing in for a fact the source already states.

---

## R3 — Which efficiency, and the one-implementation rule

**Decision**: career efficiency is computed by summing per-week optimal-lineup points through
`RosterManagementService` / `RealizedLineupService`, season by season. `roster_season.points_possible`
stays stored and stays unread.

**Rationale**: there are three candidate answers today, and they disagree. popsharky, (Foot) Ball
Knowers 2025:

| Source | Total | Potential | Efficiency |
|---|---|---|---|
| `roster_season.points_possible` (Sleeper `ppts`) | 2042.84 | 2204.34 | 92.7% |
| `RosterManagementService`, 17 stored weeks | 2468.50 | 2695.18 | 91.6% |
| ffwrapped's Manager Profiles | — | — | 87.9% |

The totals differ because the app sums every stored week and Sleeper's `fpts`/`ppts` cover the regular
season only — the same reconciliation already recorded for NBA weekly points, where playoff weeks
19–21 were counted locally and excluded from Sleeper's season `fpts`.

`points_possible` is populated for every played roster-season in both sports (12/12, 12/12, 12/12,
14/14, …) and is read by **no service in the codebase** — `grep` finds it only in
`RosterSeasonRepository`'s own upsert and row mapper. It is therefore the cheap, tempting path, and
taking it would create a second implementation of "what could this roster have scored" sitting beside
the one 004 built and proved optimal. That is the defect class this repo has shipped repeatedly.

The page must also say which weeks a figure covers, because 91.6% over 17 weeks and 92.7% over 14 are
answers to different questions, and neither is wrong.

**Alternatives considered**:

- *Use `points_possible` for the career average because it is one column and one query.* Rejected on
  the above; the performance argument it rests on does not survive R4's measurement.
- *Restrict every figure to the regular season so it matches Sleeper.* Rejected as a silent scope
  change to a shipped view; the Roster Management page already reports all weeks, and changing what it
  counts to make a new page agree would move the disagreement rather than resolve it. The rule is: one
  computation, and the page names the weeks.

---

## R4 — How to aggregate across leagues, and what it costs

**Decision**: the career profile reuses the existing per-league-season services in a loop over the
manager's roster-seasons, with the per-sport player map hoisted out of the loop. No new aggregate
tables, no materialized rollup.

**Rationale**: both services are keyed on one league-season —
`RosterManagementService.forLeague(sleeperLeagueId)` and `ExpectedWinsService.forLeague(sleeperLeagueId)`
each resolve a single league via `LeagueSeasonResolver` and read `breakdownsFor(leagueId, season)`.
A career figure is a loop over those calls.

Measured per-call latency, warm:

| League | roster-management | expected-wins |
|---|---|---|
| (Foot) Ball Knowers 2025 (nfl, 17 wks) | 120 ms | 27 ms |
| Ball Knowers 2025 (nba, 21 wks) | 49 ms | 20 ms |
| Ball Knowers 2024 (nba, 24 wks) | 48 ms | 15 ms |
| West Coast 2025 (nfl, 18 wks) | 29 ms | 15 ms |

The deepest manager in the database (popsharky) has six roster-seasons across three leagues, so a
naive career rollup is roughly 6 × (50–120 ms) ≈ 300–600 ms. That is over budget for a page load, and
the reason is visible in the code: `RosterManagementService.forLeague` calls
`players.findAll(sport)` once per invocation — **4386 NFL players or 2066 NBA players, reloaded per
season**. Hoisting that map across the loop is the whole optimisation, and it is the difference between
"reuse the service" being a good idea and a bad one.

This is not a call for a cache. Nothing here is a claim about a moment — the same reasoning
`RosterManagementService`'s own javadoc gives for not snapshotting — and the data set is small.

**Alternatives considered**:

- *A single SQL rollup over `roster_season`.* Rejected: it can produce record, points and win rate but
  not efficiency or wins above expected, so it would answer half the profile with one rule and half
  with another.
- *Store a career snapshot table.* Rejected: it is a second copy of a truth that is cheap to derive,
  and V9's own migration comment already records why this repo does not do that.

---

## R5 — What a rank is a rank *of*

**Decision**: a ranked figure is ranked within **one league chain and one sport**, the population is
stated beside it ("#2 of 12 in (Foot) Ball Knowers"), and a manager who plays several leagues gets one
rank per league they are in.

**Rationale**: the database holds three distinct league chains, two of them football. A manager's
"win rate #2" is meaningless without naming the twelve people it beat. ffwrapped prints a bare `#2`
and `#13` — and `#13` in a twelve-team league is only explicable once you realise it is ranking across
every roster-season rather than every manager. That ambiguity is precisely what
`feedback_label_the_axis_spell_out_the_number` and FR-008 exist to prevent.

**Alternatives considered**:

- *Rank across every manager in the database.* Rejected: it compares people who have never played each
  other, across leagues with different scoring and different budgets.
- *Omit ranks.* Rejected: the rank is a large part of why the reference page is readable, and it is
  cheap once the population is named.

---

## R6 — Head to head, from data that now exists

**Decision**: compute head-to-head by joining `league_matchup` (the pairing) to `roster_week_points`
(the scores), per sport, over every league chain the two managers have shared.

**Rationale**: this is the finding that changed most since 002. That spec measured **8 paired rows per
season** — pairings only for the final week — because the fixture upsert sat inside a loop that skipped
weeks already present in `roster_week_points`. That gate has since been fixed and the seasons
backfilled:

| League | Season | paired rows | paired weeks |
|---|---|---|---|
| (Foot) Ball Knowers | 2025 | 196 | 17 |
| (Foot) Ball Knowers | 2026 | 168 | 14 (scheduled) |
| Ball Knowers (nba) | 2024 | 280 | 24 |
| Ball Knowers (nba) | 2025 | 244 | 21 |
| West Coast | 2025 | 204 | 18 |

So head-to-head is buildable today for every played season in both sports, which is what makes US4
worth ranking above the league-side record book. Note the 2026 rows are *scheduled* fixtures with no
scores yet; a meeting only counts once both sides have a stored `starters_points`.

The conservation check that proves the join: summing every pair's meetings in a season must equal the
number of paired fixtures in that season (SC-006) — the same style of check 004 used for expected wins.

**Alternatives considered**:

- *Derive pairings from equal opponent scores.* Rejected: it is a guess where a stored `matchup_id`
  exists, and it breaks on two matchups with identical totals.

---

## R7 — Streaks across a season boundary

**Decision**: streaks are computed within a season by default, and a cross-season streak is shown only
with the rule stated on the page.

**Rationale**: with one or two played seasons per chain (R8), the difference between "longest streak"
and "longest streak within a season" is large in relative terms and invisible to the reader. Stating
the rule is cheaper and more honest than choosing one silently — the same discipline the record book's
existing empty-state sentences already apply.

---

## R8 — FAAB is a season's format, not a manager's habit

**Decision**: aggregate bids as a percentage of that season's own starting budget, and exclude seasons
whose `waiver_type` is not FAAB with that reason stated.

**Rationale**: measured across the six played league-seasons:

```
waiver_type  budget   season
0              100    nfl 2025 (Foot) Ball Knowers; nfl 2025 + 2026 West Coast; nba 2024
2              200    nfl 2026 (Foot) Ball Knowers
2            10000    nba 2025 Ball Knowers
2             1000    nba 2026 Ball Knowers
```

`waiver_type 0` is waiver priority, with no bidding at all. That is why NFL 2025 holds 321 waiver
transactions and **zero** `faab_bid` values — correct league format, not an ingest defect. This is the
same shape of finding as the NBA one-game-per-week reconciliation, and it is recorded here so nobody
"fixes" it later.

Budgets differ by 50× across seasons a single manager has played (100 → 10000), so a dollar figure is
not comparable across seasons. ffwrapped reaches the same conclusion — it prints "$51 · 26% of starting
budget" — and the percentage is the only figure that survives the comparison.

**Trades**: 25 `TRADE` rows exist (2 in nba 2024, 18 in nba 2025, 5 in nfl 2025) and **every one has
`manager_id` NULL**, because `league_transaction` carries a single `roster_id` and a Sleeper trade names
several. Waivers and free agents are fully attributed (565/565, 1423/1423, 516/516, 321/321). So a
trades-per-manager figure is omitted with its reason (FR-011) rather than shown as zero, and fixing it
is a schema change that belongs in its own feature.

---

## R9 — A draft tendency both sports can answer

**Decision**: no football-shaped tendency labels in this feature. If a tendency ships, it is the
positional shape of a manager's first N picks, rendered with each sport's own positions.

**Rationale**: both played NBA seasons have a complete, fully manager-attributed draft (168 picks each,
336 total), so basketball has the raw material. What it does not have is football's questions —
"AVG FIRST QB", "AVG FIRST TE", "Waits on QB" — and, as already recorded, it has **no reach signal at
all**: every NBA manager's `picksScored` is zero, so `reachBias` is the league mean wearing their name.

Inventing an NBA analogue of "waits on QB" on speculation is exactly what a defaulted `reversalRound`
and a throwing `startingLineup` each did, under FR-005 of 004 and this repo's own lessons file. The
positional shape of the first five picks is a question both sports answer with their own vocabulary,
and it needs no threshold to be true.

---

## R10 — Where the deeper history lives in the UI

**Decision**: the manager-side work lands on the existing `/managers/:id/history` page, which already
separates "what happened" from "what this app thinks". The comparison (US4) is a second page registered
in `destinations.ts`. The league-side record book (US5) extends `/leagues/:id/history` in place.

**Rationale**: `/managers/:id/history` is already the manager-keyed surface, already portals a rail
section, and already renders one block per sport for `draftHistory` — so US1's per-sport split has a
pattern to follow inside the same file rather than a new one to invent. `destinations.ts` is the single
declaration the rail, palette and league switcher all read, and FR-013/FR-015 restate its existing
non-defaulted `sports` rule.

**Alternatives considered**:

- *A new `/managers/:id/profile` page.* Rejected: it splits one person across two URLs and forces a
  choice about which one a standings row links to.
