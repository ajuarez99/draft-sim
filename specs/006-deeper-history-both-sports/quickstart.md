# Quickstart: validating a history deep enough to argue with

How to run this feature's scenarios end to end and confirm each success criterion. Commands are the
ones actually used to produce the measurements in [research.md](research.md), so they work today —
before implementation they show the *current* (wrong) answers, which is the point.

## Prerequisites

```bash
docker compose up -d
```

Postgres on `localhost:5433` (`draftsim` / `draftsim`), container `draftsim-pg`. Then run the backend
from your IDE (or `./gradlew bootRun` in `backend/`) and `npm run dev` in `web/`.

**Data**: the scenarios below use the leagues already ingested on this machine. If yours is empty,
ingest the chains first:

```bash
curl -X POST "http://localhost:8080/api/ingest/league-history/1346366555759341568"   # nfl
curl -X POST "http://localhost:8080/api/ingest/league-history/1229352720222134272"   # nba
```

**Reference figures** come from ffwrapped's Manager Profiles view for the same league:
`https://ffwrapped.com/?leagueId=1346366555759341568&view=manager-profiles`.

---

## Baseline: see the two defects before fixing them

Run these first. They are the measurements the spec rests on, and they double as the regression tests.

### The sport is missing from a manager's seasons (US1)

```bash
curl -s "http://localhost:8080/api/managers/7/history" \
  | python -c "import sys,json; [print(s['season'], s['sleeperLeagueId'], s['wins'], s['pointsFor'], s.get('sport','NO SPORT')) for s in json.load(sys.stdin)['seasons']]"
```

**Before**: six rows, `NO SPORT` on each, three of them `2026`, `pointsFor` 5146.0 beside 2042.84.
**After US1**: every row carries `nfl` or `nba` and a `leagueName`.

### A season one week old has a champion (US2)

```bash
docker exec draftsim-pg psql -U draftsim -d draftsim -c "
select l.sport, l.season, l.name, rs.roster_id, m.display_name, rs.wins, rs.losses, rs.final_placement
from roster_season rs join league l on l.id = rs.league_id
left join manager m on m.id = rs.manager_id
where rs.final_placement is not null order by l.sport, l.name, l.season;"
```

**Before**: six rows, two of them seasons with a 1-0 record.
**After US2**: four rows. The two in-progress seasons are gone, and re-running the ingest keeps them
gone (the upsert must *clear*, not skip).

Confirm the source of the error at any time:

```bash
curl -s "https://api.sleeper.app/v1/league/1346366555759341568" \
  | python -c "import sys,json; d=json.load(sys.stdin); print(d['season'], d['status'], d['metadata'].get('latest_league_winner_roster_id'))"
# → 2026 in_season 1
```

---

## Scenario 1 — US1: a manager's seasons say which sport they were

1. Open `http://localhost:5173/managers/7/history`.
2. Expect football seasons and basketball seasons in separate blocks, each labelled with its sport.
3. Expect no displayed record, points total or season count that spans both — the header must not read
   a single combined W-L.
4. Expect the two 2026 football rows (West Coast and (Foot) Ball Knowers) to be distinguishable by
   league name.

**Proves**: SC-001, US1.1–US1.4.

---

## Scenario 2 — US2: the trophy only goes to a finished season

1. Re-run the ingest for both football chains (see Prerequisites).
2. Re-run the baseline placement query above — four rows.
3. Open `/leagues/1346366555759341568/history`: the 2026 standings show no 🏆; 2025 still shows one.
4. Open `/managers/7/history`: titles reads **1**.

**Proves**: SC-003, US2.1–US2.5. Note ffwrapped shows `TITLES 2` for the same manager, so the reference
page is not a check here — the Sleeper call above is.

---

## Scenario 3 — US3: the career profile, per sport

```bash
curl -s "http://localhost:8080/api/managers/7/history" \
  | python -m json.tool | sed -n '/"careers"/,/"draftHistory"/p'
```

Check, for `sport: "nfl"`:

| Figure | Expected | Why |
|---|---|---|
| `titles` | 1 | not 2 |
| `seasonsCounted` | stated beside every average | SC-008 |
| `averageEfficiency` | ≈ 0.916 for (Foot) Ball Knowers 2025 | the optimal-lineup figure, **not** 0.927 |
| `ranks[].population` | 12 | never a bare `#2` |
| `unavailable[]` | names `tradesPerSeason` and `playoffAppearances` | FR-011 |

**Reconciliation against ffwrapped** — scope to (Foot) Ball Knowers alone and the figures must match
its published profile exactly:

```
record     11-4        (10-4 in 2025) + (1-0 in 2026)
pointsFor  2200.24     2042.84 + 157.40
winRate    73.3%       11 / 15
```

**The efficiency check that matters (SC-004)**. These two must not both be reachable as "efficiency":

```bash
# Sleeper's stored ppts — must stay unread by any service
docker exec draftsim-pg psql -U draftsim -d draftsim -c "
select rs.roster_id, m.display_name, rs.points_for, rs.points_possible,
       round(rs.points_for / nullif(rs.points_possible,0) * 100, 1) as pct
from roster_season rs join league l on l.id = rs.league_id
left join manager m on m.id = rs.manager_id
where l.sleeper_id = '1254190892974084096' order by rs.roster_id;"
# roster 1 → 92.7%

# the app's one implementation
curl -s "http://localhost:8080/api/leagues/1254190892974084096/roster-management" \
  | python -c "import sys,json; t=[x for x in json.load(sys.stdin)['teams'] if x['rosterId']==1][0]; print(t['totalPoints'], t['potentialPoints'], round(t['efficiency']*100,1))"
# → 2468.5 2695.18 91.6
```

The career figure must trace to the second. A test asserts no service reads
`roster_season.points_possible`.

**Both sports**: repeat for `sport: "nba"` and confirm every figure renders through the same code path
(SC-005). NBA 2026 is ingested and unplayed — it must appear in `seasons` with `counted: false` and
contribute to no average (US3.4).

**Proves**: SC-002, SC-004, SC-005, SC-008.

---

## Scenario 4 — US4: head to head

```bash
curl -s "http://localhost:8080/api/managers/7/versus/43" | python -m json.tool
```

1. Expect one `sports[]` entry per sport the two have shared, never a combined record.
2. Each meeting's `winner` matches the higher `starters_points` for that week.
3. The 2026 seasons appear in `seasonsExcluded` with "fixtures are scheduled but no week is scored yet".

**Conservation check (SC-006)** — meetings across every pair must equal the season's paired fixtures:

```bash
docker exec draftsim-pg psql -U draftsim -d draftsim -c "
select l.name, m.season, count(*) as paired_rows, count(*)/2 as meetings
from league_matchup m join league l on l.id = m.league_id
where m.matchup_id is not null
group by l.name, m.season order by l.name, m.season;"
```

(Foot) Ball Knowers 2025 → 196 rows → 98 meetings.

**Proves**: SC-006, US4.1–US4.5.

---

## Scenario 5 — US5: the deeper record book

Open `/leagues/1346366555759341568/history` and `/leagues/1229352720222134272/history`.

1. All-time points leaders, longest winning streak and longest losing streak, each naming the manager,
   the span and the seasons crossed.
2. The rule for whether seasons join a streak is stated on the page, not left to the reader.
3. The basketball chain renders identically.
4. With one played season in a chain, an all-time figure says it covers one season.

**Proves**: US5.1–US5.4.

---

## Scenario 6 — US6: waiver and FAAB tendencies

The format differences are the test here. Confirm the ground truth first:

```bash
docker exec draftsim-pg psql -U draftsim -d draftsim -c "
select sport, season, name, settings_json->>'waiver_type' as waiver_type,
       settings_json->>'waiver_budget' as budget
from league order by sport, name, season;"
```

| Expectation | League |
|---|---|
| FAAB block absent, reason "used waiver priority, not FAAB" | nfl 2025 (Foot) Ball Knowers (`waiver_type 0`, 321 waivers, 0 bids) |
| Bids shown as % of 10000 | nba 2025 Ball Knowers (`waiver_type 2`) |
| Bids shown as % of 200 | nfl 2026 (Foot) Ball Knowers (`waiver_type 2`) |
| `tradesPerSeason` omitted with its reason, never 0 | every league |

Confirm the trade gap is real, not a rendering choice:

```bash
docker exec draftsim-pg psql -U draftsim -d draftsim -c "
select l.sport, l.season, count(*) as trades, count(t.manager_id) as attributed
from league_transaction t join league l on l.id = t.league_id
where t.type = 'TRADE' group by l.sport, l.season order by l.sport, l.season;"
# attributed is 0 everywhere
```

**Proves**: SC-007, US6.1–US6.4.

---

## Running the test suites

```bash
cd backend && ./gradlew test
```

```bash
cd web && npm test
```

**Do not read `BUILD SUCCESSFUL` as a pass.** The backend suite reports success with integration tests
silently **skipped** when Postgres is down. Check the skip count:

```bash
grep -ho 'skipped="[0-9]*"' backend/build/test-results/test/*.xml | sort | uniq -c
```

A non-zero total means the Postgres-backed tests — which are the ones that prove SC-001, SC-003 and
SC-006 — did not run.

## A note on shared state

Several Claude sessions share this working tree, this database and this server. Re-run the baseline
queries before trusting a verification result rather than assuming the state an earlier step left
behind is still there.

---

## Results — walked end to end 2026-09-22

Backend and web both served from the `006-complete-rule` worktree (the main checkout had moved on to
another branch), Postgres on 5433, signed in as `popsharky`.

| Scenario | Result |
|---|---|
| Baseline: sport on every row | **Pass.** `/api/managers/7/history` returns `sport` and `leagueName` on all six rows; the two 2026 football rows are told apart by league name. |
| Baseline: a live season has a champion | **Already repaired.** The placement query returns four rows, not six, before any step here — an earlier ingest on this branch cleared them. The gate was re-proved instead (below). |
| 2 — the trophy only goes to a finished season | **Pass.** Sleeper still answers `2026 in_season latest_league_winner_roster_id=1`; re-ingesting all three chains left exactly four placements, all on `complete` seasons. `/leagues/1346366555759341568/history` shows 🏆 on 2025 only, and the manager page reads `1 title`. |
| 3 — the career profile, per sport | **Pass.** `titles` 1, `unavailable[]` names `playoffAppearances` and `tradesPerSeason` with reasons, NBA 2026 is listed with `counted: false` and moves no average. Every figure on screen carries "over N seasons". |
| 3 — the efficiency check (SC-004) | **Pass.** Sleeper's stored `ppts` gives 92.7% for popsharky 2025; the app's one implementation gives 2468.5 / 2695.18 = 91.6%, and the career figure traces to the second. |
| 4 — head to head | **Pass.** 2-1 in football, 2-2 in basketball, never combined; each meeting's winner matches the higher `starters_points`. (Foot) Ball Knowers 2025 holds 196 paired rows → 98 meetings, as stated. |
| 5 — the deeper record book | **Pass.** All-time points leaders and longest win/loss streaks on both chains, each naming the manager, the exact value, the span and the seasons covered; the page states that a streak never crosses the offseason. |
| 6 — waiver and FAAB tendencies | **Pass.** Ground truth matches: FBK 2025 and both West Coast seasons are `waiver_type 0`, NBA 2025 is type 2 on 10000, FBK 2026 type 2 on 200. NBA shows bids as % of each season's own budget; excluded seasons are named with "used waiver priority, not FAAB". Trades are unattributed in all three seasons that have any, and `tradesPerSeason` is omitted with that reason rather than shown as 0. |
| Test suites | **Pass, with the skip count read.** 83 classes, 565 tests, `skipped="0"`, `failures="0"` with Postgres up. The same run with Docker down reports BUILD SUCCESSFUL and 86 silently skipped. Web: 43 files, 474 tests. |

### Where the live answers differ from what this file predicted

Four, all explained, none a defect:

1. **The champions were already cleared** before this walk, so the "before" half of Scenario 2 could
   not be observed. What was proved instead is the stronger property: a fresh ingest, with Sleeper
   still returning `in_season` and a winner id, does not re-crown them.
2. **The reconciliation figures moved with the season.** Scoped to (Foot) Ball Knowers alone,
   popsharky is now 11-5 with 2259.83 points (2042.84 + 216.99), not the 11-4 / 2200.24 written here
   on 2026-09-21 — 2026 has played another week since. The football career spans both leagues: 13-5,
   2577.89.
3. **Rank population is 13 and 17, not 12.** It counts every manager who has held a roster in that
   chain across its seasons (`select count(distinct manager_id)` per chain), not the team count of one
   season, and each rank names the league it is within — "2nd of 13 in (Foot) Ball Knowers".
4. **Football 2026 is no longer a head-to-head exclusion.** A week has been scored since this file was
   written, so it contributes a real meeting (2026 Wk 2). Basketball 2026 is still excluded, with the
   reason "fixtures haven't been loaded for this league's season yet".

One figure worth not misreading: football `faab` is null for popsharky although FBK 2026 runs FAAB.
He has placed no bid there — `select count(faab_bid)` is 0 for his 2026 rows — so this is "no bids to
average", and the seasons that ran waiver priority are listed separately with that reason.
