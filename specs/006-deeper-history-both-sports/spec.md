# Feature Specification: A history deep enough to argue with

**Feature Branch**: `006-deeper-history-both-sports`

**Created**: 2026-09-21

**Status**: Draft

**Input**: User description: "https://ffwrapped.com/?leagueId=1346366555759341568&view=manager-profiles
lets go more indepth on our history for football and basketball"

## Problem

ffwrapped's **Manager Profiles** view is one page of *career-grain* numbers: what a manager has done
across every season the league has loaded. Ball Knowers already computes nearly every figure on that
page — win rate, efficiency, wins above expected, transaction counts, draft tendencies — and computes
**none of them at career grain**. Every analysis endpoint in this app is keyed on one league-season:

```
/api/leagues/{sleeperId}/roster-management      one season
/api/leagues/{sleeperId}/expected-wins          one season
/api/leagues/{sleeperId}/weekly-report/{week}   one week
/api/leagues/{sleeperId}/power                  one chain, snapshots per week
/api/managers/{managerId}/history               the only manager-keyed endpoint
```

That last one is the whole manager-side surface today, and it answers with a W/L/PF/PA table plus
fitted draft numbers. It is the page the user is pointing at, and going "more in depth" on it turns
out to be blocked by two live defects before any new number can be trusted.

### What ffwrapped's Manager Profiles actually contains

Read off the reference page on 2026-09-21 against league `1346366555759341568`:

| Section | Fields |
|---|---|
| **Manager Profiles** | seasons, badges, win rate (+league rank), playoff appearances, titles, avg efficiency (+rank), record, points for, points against, points/season (+rank), trades/season, waivers/season |
| **Draft Tendencies** | drafts, a label ("Waits on QB"), the first five picks' positions per draft, avg first-QB round, avg first-TE round, historical pick-performance rank |
| **FAAB Tendencies** | typical bid, % of starting budget, spent/season, claims/season, largest bid, % paid claims, bid success rate |
| **Manager Comparison** | two managers side by side: championships, record, total points, points/game, wins above expected, manager efficiency, head-to-head, recent performances chart |

### What was measured, and why it decides the shape of this feature

Measured 2026-09-21 against local Postgres (`draftsim-pg`, healthy 3 days) and the running API on
`:8080`, with the reference page open beside it.

| League | Sport | Season | roster_week_points | weeks | with `starters` | paired fixtures | transactions | drafts |
|---|---|---|---|---|---|---|---|---|
| (Foot) Ball Knowers | nfl | 2025 | 204 | 17 | 204 | 196 | 471 | 180 picks |
| (Foot) Ball Knowers | nfl | 2026 | 12 | 1 | 12 | 168 | 16 | 180 picks |
| Ball Knowers | nba | 2024 | 288 | 24 | 288 | 280 | 635 | 168 picks |
| Ball Knowers | nba | 2025 | 252 | 21 | 252 | 244 | 1618 | 168 picks |
| Ball Knowers | nba | 2026 | 0 | 0 | 0 | 0 | 0 | pre-draft |
| West Coast Fantasy Football | nfl | 2025 | 216 | 18 | 216 | 204 | 378 | 180 picks |
| West Coast Fantasy Football | nfl | 2026 | 14 | 1 | 14 | 196 | 29 | 210 picks |

The 002 baseline is gone: **pairings are now backfilled for every played season in both sports**
(002 measured 8 paired rows per season; it is now 196–280), `starters` is populated on every stored
week, and transactions are ingested for both sports. The ingest-side blockers that shaped 002 and 004
no longer apply. What blocks this feature is different, and smaller, and already on screen.

#### Finding 1 — A manager's history mixes football and basketball in one table

`GET /api/managers/7/history` (popsharky), run live:

```
2026  1-0   PF  189.90   (West Coast, nfl)
2026  0-0   PF    0.00   (Ball Knowers, nba)
2026  1-0   PF  157.40   (Foot) Ball Knowers, nfl)   champion: true
2025  8-10  PF 4370.00   (Ball Knowers, nba)
2025 10-4   PF 2042.84   ((Foot) Ball Knowers, nfl)  champion: true
2024  9-11  PF 5146.00   (Ball Knowers, nba)
```

Six rows, three leagues, two sports, **no sport on any row**. Three of them say "2026" and two say
"2025". `RosterSeasonRepository.forManager` selects `l.season` and `l.sleeper_id` but never
`l.sport`, and `StandingRow` has no field to put it in. The page renders one column headed *Season*,
so a reader sees 2026 three times with no way to tell which is which; the PF column puts 5146.00
beside 2042.84 as though they were the same unit; and `ManagerHistory.tsx` computes its own header by
summing every row — *"29-25 across 6 seasons"* — adding basketball wins to football wins.

Eleven of the twelve Ball Knowers managers are the same Sleeper user in both leagues, so this is not
an edge case; it is what every populated manager page in the app currently shows.

This is the repo's own recurring class again — one shape serving two sports with the sport left off
it — the same class as `latest_league_winner_roster_id` below, as `startingLineup` in 004, and as the
defaulted `reversalRound` the lessons file already records.

#### Finding 2 — A season one week old already has a champion

`LeagueHistoryIngestService#ingestStandings` sets `final_placement = 1` from
`league.metadata.latest_league_winner_roster_id`. Sleeper carries that key on the **new** season's
league object, where it means *the most recent winner* — last season's. Verified against the live
source:

```
GET https://api.sleeper.app/v1/league/1346366555759341568
  season 2026, status in_season, metadata.latest_league_winner_roster_id = "1"
```

So `final_placement = 1` is stored for roster 1 of a season that has played one week. Every roster-season
in the database that carries a placement:

```
nba 2024 Ball Knowers        roster  2  njerickson      17-4   → 1
nba 2025 Ball Knowers        roster 12  GraftonCarlson  10-8   → 1
nfl 2025 (Foot) Ball Knowers roster  1  popsharky       10-4   → 1
nfl 2026 (Foot) Ball Knowers roster  1  popsharky        1-0   → 1   ← wrong
nfl 2025 West Coast          roster  6  gregmullen       7-7   → 1
nfl 2026 West Coast          roster  6  gregmullen       1-0   → 1   ← wrong
```

The 🏆 renders on the in-progress season of League History, and `ManagerHistory.tsx` counts it in
`championships`. ffwrapped's own profile prints popsharky **TITLES 2**, which is exactly what this
mistake produces — so the reference page is not a check on it. Any career "titles" number is wrong
until this is fixed, which is why it is sequenced ahead of the profile itself.

#### Finding 3 — There are already three different efficiencies for one manager-season

popsharky, (Foot) Ball Knowers 2025:

| Source | Total | Potential | Efficiency |
|---|---|---|---|
| `roster_season.points_possible` (Sleeper `ppts`) | 2042.84 | 2204.34 | **92.7%** |
| `RosterManagementService` (per-week optimal lineup, 17 weeks) | 2468.50 | 2695.18 | **91.6%** |
| ffwrapped's Manager Profiles | — | — | **87.9%** |

The totals disagree because the app sums all 17 stored weeks while Sleeper's `fpts` is regular season
only — the same reconciliation lesson already recorded for NBA weekly points. `points_possible` is
populated for **every played roster-season in both sports** and is read by no service in the codebase.

This matters because a career efficiency is a weighted average of season efficiencies, and the cheap
path (sum the stored `points_possible` column) and the correct path (the optimal-lineup service that
004 built and proved) give different answers. Picking one silently would be two implementations of
"what could this roster have scored" — the exact bug 004's `startingLineup` work was done to prevent.

#### Finding 4 — Where the inputs agree, career rollups reconcile exactly

ffwrapped, popsharky: **POINTS FOR 2200.2**, **RECORD 11-4**, **WIN RATE 73.3%**.

```
2042.84 + 157.40 = 2200.24          (2025 + 2026, football only)
10-4    +  1-0   = 11-4
11 / 15          = 73.33%
```

So the aggregation rule is not in question and neither is the data. The grain is — ffwrapped sums
football seasons only, and this app's manager endpoint would sum both sports into one figure.

#### Finding 5 — Trades exist and cannot be attributed to a manager

25 `TRADE` rows across both sports (2 in NBA 2024, 18 in NBA 2025, 5 in NFL 2025), and **every one has
`manager_id` NULL**. `league_transaction` carries a single `roster_id`; a Sleeper trade names several.
Waivers and free-agent adds are fully attributed (565/565, 1423/1423, 516/516, …). So "waivers per
season" is available today and "trades per season" is not, for either sport.

#### Finding 6 — FAAB is a per-season league format, not a manager trait

```
waiver_type  budget   league
0            100      nfl 2025 (Foot) Ball Knowers, nfl 2025 + 2026 West Coast, nba 2024
2            200      nfl 2026 (Foot) Ball Knowers
2          10000      nba 2025 Ball Knowers
2           1000      nba 2026 Ball Knowers
```

`waiver_type 0` is waiver priority, with no bidding. That is why NFL 2025 has 321 waiver rows and zero
`faab_bid` values — **correct league format, not an ingest defect**, the same shape of finding as the
NBA one-game-per-week reconciliation. Budgets differ by 50× across seasons a single manager has played,
so a raw dollar figure is not comparable across seasons and only *percentage of starting budget* is.

#### Finding 7 — Basketball has the draft history; it does not have football's questions

Both played NBA seasons have a complete, fully manager-attributed draft (168 picks each, 336 total).
So draft tendencies are computable for basketball. But ffwrapped's tendencies are football-shaped —
*AVG FIRST QB*, *AVG FIRST TE*, *"Waits on QB"* — and basketball has no quarterback and, as already
recorded, **no reach signal at all**: every NBA manager's `picksScored` is zero, so `reachBias` is the
league mean wearing their name. A sport-agnostic tendency has to be a question both sports can answer.

#### Finding 8 — The depth available is two seasons, and that is the real constraint

One played NFL season plus one in progress per football league; two played NBA seasons plus one
unplayed. Every "career" number in this feature is a two-season average at best. That is not a reason
not to build it — it is the reason the page must always say how many seasons a figure rests on, and
the reason ffwrapped's invented badges ("Final Boss Energy", "Lineup Whisperer") are out of scope.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A manager's seasons say which sport they were (Priority: P1)

A manager who plays both leagues opens their own page and can tell their basketball seasons from their
football ones. Today they cannot: three rows say 2026, the PF column mixes two scales, and the header
adds the two records together.

**Why this priority**: it is a live wrong number on every populated manager page, it is the
prerequisite for every career figure in this feature (a career win rate computed across both sports is
meaningless), and it is the repo's recurring defect class in a new place.

**Independent Test**: call `GET /api/managers/7/history` and assert every season row carries a sport;
render the page and assert the football rows and basketball rows are separated, and that no displayed
total spans both. Ships without any new computation.

**Acceptance Scenarios**:

1. **Given** a manager with seasons in both sports, **When** their history is fetched, **Then** every
   season row carries its sport, sourced from `league.sport` rather than inferred from the league name.
2. **Given** the same manager, **When** the page renders, **Then** football and basketball seasons are
   presented separately and no record, points total or season count spans both.
3. **Given** a manager who plays one sport only, **When** the page renders, **Then** it shows that
   sport's block without an empty second one.
4. **Given** two leagues of the same sport in the same season (West Coast and (Foot) Ball Knowers,
   both 2026), **When** the rows render, **Then** each names its league, because the season alone no
   longer identifies the row.

---

### User Story 2 - The trophy only goes to a season that finished (Priority: P2)

League History stops showing a champion for a season one week old, and a career "titles" count stops
counting it.

**Why this priority**: it is a wrong number on two pages today, it is cheap, and every titles figure in
US3 is wrong until it lands. It is deliberately *not* merged into US3, because it is a correction to
shipped behaviour that stands on its own.

**Independent Test**: re-ingest (Foot) Ball Knowers and assert `final_placement` is set for the 2025
season and null for the in-progress 2026 one; assert the page shows one trophy, not two.

**Acceptance Scenarios**:

1. **Given** a league season Sleeper reports as `in_season` or `pre_draft`, **When** standings are
   ingested, **Then** no roster is recorded as champion for that season.
2. **Given** a league season Sleeper reports as `complete`, **When** standings are ingested, **Then**
   the champion is recorded as it is today.
3. **Given** a database already carrying the wrong placement, **When** the corrected ingest runs,
   **Then** the stored value is cleared, not merely skipped — the upsert must be able to remove a
   placement it previously wrote.
4. **Given** the corrected data, **When** a manager's career titles are counted, **Then** popsharky
   shows one, not two.
5. **Given** an NBA league, **When** the same rule is applied, **Then** it behaves identically; nothing
   in this story is sport-specific.

---

### User Story 3 - A manager profile that spans their whole career, per sport (Priority: P3)

The named request. A manager's page gains the profile ffwrapped shows: seasons played, record, win
rate with its rank in the league, points for and against, points per season, average efficiency with
its rank, playoff appearances and titles — **one block per sport**, each stating how many seasons it
rests on.

**Why this priority**: it is what the user asked for, and after US1 and US2 every input is already
stored for both sports. The only genuinely new work is aggregation and ranking.

**Independent Test**: for popsharky, assert the football block reports 11-4, 2200.24 points for and
73.3% win rate — reconciling exactly with ffwrapped's published figures for the same league — and that
the basketball block reports its own separate totals.

**Acceptance Scenarios**:

1. **Given** a manager with seasons in one sport, **When** the profile loads, **Then** record, win
   rate, points for, points against and points per season are the sums and ratios of that sport's
   seasons only, and the season count is stated beside them.
2. **Given** a league of managers, **When** a ranked figure is shown, **Then** the rank names its
   population ("#2 of 12 in this league") rather than printing a bare `#2`.
3. **Given** average efficiency, **When** it is shown, **Then** it is computed through the same
   optimal-lineup service the Roster Management view uses, not from `roster_season.points_possible`,
   and the page states which weeks it covers.
4. **Given** a season with no scored weeks (NBA 2026), **When** the profile aggregates, **Then** that
   season contributes nothing and is excluded from the stated season count rather than averaged as
   zero.
5. **Given** wins above expected, **When** it is shown, **Then** it comes from the existing expected-wins
   computation summed across seasons, and the league-wide sum of the figure remains zero.
6. **Given** an NBA-only manager, **When** the profile loads, **Then** every figure renders through
   the same code path as football, with no sport branch in the service layer.

---

### User Story 4 - Head to head, and the rivalry record (Priority: P4)

Two managers compared side by side — championships, record, total points, points per game, wins above
expected, efficiency — and, the number ffwrapped leaves at `0-0`, **their actual head-to-head record**
across every season they have shared, with the meetings listed.

**Why this priority**: it is the argument the league actually has, the fixtures needed for it are now
backfilled for every played season in both sports (Finding: 196–280 paired rows per season, against
002's 8), and it is the one place this app can be straightforwardly ahead of the reference page.

**Independent Test**: for two managers who shared (Foot) Ball Knowers 2025, assert the head-to-head
record sums to the number of weeks they were paired, and that each listed meeting's winner matches the
higher `starters_points` for that week.

**Acceptance Scenarios**:

1. **Given** two managers who have shared at least one league season, **When** the comparison loads,
   **Then** it shows their head-to-head record and every meeting with season, week and both scores.
2. **Given** two managers who have shared seasons in both sports, **When** the comparison loads,
   **Then** head-to-head is reported per sport, never as one combined record.
3. **Given** two managers who have never shared a league, **When** the comparison loads, **Then** it
   says so rather than printing `0-0`.
4. **Given** a season whose fixtures are missing, **When** head-to-head is computed, **Then** that
   season is named as excluded rather than silently contributing nothing.
5. **Given** a tied meeting, **When** the record is tallied, **Then** ties are counted and shown, not
   folded into losses.

---

### User Story 5 - The league's all-time record book, deeper (Priority: P5)

League History's record book grows past "highest and lowest weeks": all-time points leaders, the
longest winning and losing streaks with their dates, every season's final standings order rather than
only its champion, and the biggest single-week margin per season.

**Why this priority**: it is the league-side half of "more in depth on our history", it reads the same
stored weeks and now-complete fixtures, and it is the part that keeps working as seasons accumulate.
It ranks behind the manager-side stories because the user pointed at Manager Profiles.

**Independent Test**: for (Foot) Ball Knowers, assert the longest streak's weeks are contiguous in the
fixture data and that its start and end weeks are named.

**Acceptance Scenarios**:

1. **Given** a league chain, **When** the record book loads, **Then** all-time points leaders, longest
   winning streak and longest losing streak are shown, each naming the manager, the span and the
   seasons it crosses.
2. **Given** a streak that runs across a season boundary, **When** it is computed, **Then** the rule
   for whether seasons join is stated on the page, not left to the reader.
3. **Given** an NBA chain, **When** the record book loads, **Then** it computes identically.
4. **Given** a league with one played season, **When** an all-time figure is shown, **Then** it says it
   covers one season rather than presenting a season record as a career one.

---

### User Story 6 - Waiver and FAAB tendencies, honestly scoped (Priority: P6)

Per manager, per sport: waiver and free-agent moves per season, and — for the seasons that actually ran
FAAB — typical bid, largest bid, spend and claim rate, all expressed as a percentage of that season's
starting budget.

**Why this priority**: last, because it is the only story with a known unattributable input (Finding 5)
and the only one whose availability varies season by season. Everything ahead of it ships without it.

**Independent Test**: for NBA 2025 (`waiver_type 2`, budget 10000), assert every manager's bid figures
are percentages of 10000; for NFL 2025 (`waiver_type 0`), assert the FAAB block is absent with a stated
reason rather than showing zeros.

**Acceptance Scenarios**:

1. **Given** a manager, **When** transaction tendencies load, **Then** waiver and free-agent moves per
   season are shown per sport, with the season count they rest on.
2. **Given** a season whose `waiver_type` is not FAAB, **When** bid figures are requested, **Then** the
   season is excluded and the exclusion says "this season used waiver priority, not FAAB".
3. **Given** seasons with different budgets, **When** bids are aggregated, **Then** they are aggregated
   as a percentage of each season's own starting budget, and the page says so.
4. **Given** trades, **When** a trades-per-season figure would be shown, **Then** it is omitted with the
   stated reason that trades are not attributed to a manager, rather than shown as zero.

---

### Edge Cases

- A roster-season with no manager (Sleeper leaves rosters unowned mid-season) must still appear in
  league-side records, as the existing record book already handles.
- A manager who appears in two leagues of the same sport in the same season — both 2026 football
  leagues have overlapping members — must have those seasons distinguishable by league.
- A season ingested but never played (NBA 2026) contributes to no average and to no season count.
- The 2026 in-progress seasons will become `complete`; nothing may require a manual correction pass at
  that point for the champion to appear.

### Out of scope for this feature

- **Badges** ("Final Boss Energy", "Regular Season Royalty", "Lineup Whisperer"). Every career figure
  here rests on one or two seasons (Finding 8); a threshold over that much data is a label, not a
  finding. Revisit when a third season exists.
- **The Rivalry Report prose.** ffwrapped gates it behind premium, and it is an LLM write-up over the
  data US4 produces — the same kind of feature 004 deferred as *Historian*, for the same reason.
- **Historical pick performance rank.** It needs a realized value per drafted player over the season,
  which is a player-grain join this app has never built, and for basketball it interacts with the
  per-game/per-week distinction 005 measured. It gets a seam, not an implementation.
- **Football-shaped tendency labels** ("Waits on QB", avg first-QB round). A tendency has to be a
  question both sports can answer (Finding 7); inventing an NBA analogue on speculation is what
  `reversalRound` and `startingLineup` each did.
- **Trade attribution.** Named in US6 as a stated omission. Fixing it is a `league_transaction` schema
  change (a trade has several rosters) and belongs in its own feature.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Every season row returned for a manager MUST carry its sport, read from `league.sport`.
- **FR-002**: No displayed record, points total, average or season count MUST span more than one sport.
- **FR-003**: A season row MUST be identifiable when a manager played two leagues of the same sport in
  the same season; the season alone is not an identifier.
- **FR-004**: A champion MUST only be recorded for a league season Sleeper reports as complete.
  `latest_league_winner_roster_id` on an in-season league names the previous season's winner.
- **FR-005**: The corrected standings ingest MUST clear a placement it previously wrote, not merely
  decline to write a new one.
- **FR-006**: Efficiency MUST have exactly one implementation in this app. A career average MUST be
  built from the same per-week optimal-lineup computation the Roster Management view uses, and MUST
  NOT be derived from `roster_season.points_possible`.
- **FR-007**: Any figure derived from an incomplete or unplayed season MUST exclude that season and
  MUST state the number of seasons it does cover.
- **FR-008**: A rank MUST name its population; a bare `#2` is not a rank.
- **FR-009**: Head-to-head MUST be computed per sport, from stored fixtures and weekly points, and a
  season whose fixtures are missing MUST be named as excluded.
- **FR-010**: FAAB figures MUST be aggregated as a percentage of each season's own starting budget, and
  a season whose `waiver_type` is not FAAB MUST be excluded with that reason stated.
- **FR-011**: A figure that cannot be attributed — trades per manager — MUST be omitted with its reason,
  never rendered as zero.
- **FR-012**: Every new view MUST work for NFL and NBA through the same code path, reaching any
  sport-specific behaviour through `SportRules` rather than a branch in a service.
- **FR-013**: A view that declares which sports it supports MUST do so explicitly and non-defaulted, as
  `destinations.ts` and `LeagueDestination.sports` already require.
- **FR-014**: Every chart MUST carry the exact value beside the mark and a labelled axis; one encoding
  per mark.
- **FR-015**: Any new league-scoped page MUST be registered in `destinations.ts`.

### Key Entities

- **Manager season** — one roster-season as it belongs to a manager: sport, league, season, record,
  points for and against, whether the season is complete, and the roster it was.
- **Career profile** — one manager in one sport: seasons counted, record, win rate, points for and
  against, points per season, average efficiency, wins above expected, playoff appearances, titles,
  and each figure's rank within its league population.
- **Head-to-head record** — two managers in one sport: wins, losses, ties, and every meeting with
  season, week and both scores.
- **League record** — an all-time extreme or streak over a league chain: the manager, the value, and
  the span of seasons and weeks it covers.
- **Waiver tendency** — one manager in one sport: moves per season by type, and, for FAAB seasons only,
  bid figures as a percentage of that season's budget.

## Success Criteria *(mandatory)*

- **SC-001**: `GET /api/managers/{id}/history` returns a sport on every season row, and a test fails if
  a career figure is computed across two sports.
- **SC-002**: popsharky's football career profile reads 11-4, 2200.24 points for and 73.3% win rate —
  reconciling exactly with ffwrapped's published figures for league `1346366555759341568`.
- **SC-003**: popsharky's titles count reads 1. No roster carries a placement for a season Sleeper
  reports as in-season.
- **SC-004**: A test asserts there is exactly one implementation of potential points in the codebase,
  and that `roster_season.points_possible` is not read by any service that reports efficiency.
- **SC-005**: Every profile figure renders for an NBA-only manager through the same code path, with no
  sport branch in the service layer.
- **SC-006**: Head-to-head records for a season sum, across all pairs, to the number of paired fixtures
  in that season.
- **SC-007**: For NFL 2025 (`waiver_type 0`) the FAAB block is absent with a stated reason; for NBA 2025
  (`waiver_type 2`, budget 10000) bids are shown as percentages of 10000.
- **SC-008**: Every career figure on screen states the number of seasons it covers.

## Assumptions

- Sleeper's `status` field on a league object (`pre_draft` / `in_season` / `complete`) is the authority
  for whether a season has finished. It reads `in_season` for both 2026 football leagues today.
- The two 2026 seasons will become `complete` through normal ingest, with no manual correction.
- Career figures rest on one or two seasons for every manager in the database today; the design must
  stay correct, not merely look full, at that depth.
- `roster_season.points_possible` stays stored (it is Sleeper's own figure and cheap to keep) but stays
  unread by this app's services, per FR-006.
