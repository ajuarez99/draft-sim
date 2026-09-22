# Data Model: A history deep enough to argue with

One new column and no new tables. Everything else in this feature is derived on read from rows that
already exist for both sports.

---

## Stored changes

### `league.status` — new column (V21)

```sql
alter table league add column status text;
```

| Field | Type | Notes |
|---|---|---|
| `status` | `text`, nullable | Sleeper's own top-level league `status`: `pre_draft`, `drafting`, `in_season`, `complete`. Nullable because rows ingested before V21 have none until the next ingest walk. |

**Why it is stored** (research R2): `status` is a top-level field on Sleeper's league object and is
dropped today by `LeagueMapper`, which forwards only `settings` and `scoring_settings`. Verified:
`select settings_json ? 'status' from league` is false for all nine rows. Without it the only way to
ask "has this season finished" is positional — `history()` passes `i == 0` for "newest in the chain" —
and FR-007 makes that question load-bearing for every career average.

**Nullability rule**: a null `status` means *not yet known*, and is treated as **not complete**. It
must never be treated as complete, because that is the failure mode this feature exists to fix.

### `roster_season.final_placement` — write rule changed, schema unchanged

| Today | After |
|---|---|
| `final_placement = 1` when `metadata.latest_league_winner_roster_id` matches the roster | `final_placement = 1` only when that matches **and** `league.status == 'complete'`; otherwise `null` |

The upsert must still write the row so that
`on conflict … do update set final_placement = excluded.final_placement` **clears** the two wrong
values already stored. A skip would leave them forever — the `adp_at_time` and `league_matchup`
lessons in one sentence.

---

## Derived entities

None of these are persisted. Each is computed on read, the same discipline
`RosterManagementService` and `LeagueAnalysisService` already follow: nothing here is a claim about a
moment, so nothing is snapshotted.

### ManagerSeason

One roster-season as it belongs to a manager. This is `RosterSeasonRepository.StandingRow` with two
fields added.

| Field | Type | Source | New? |
|---|---|---|---|
| `sport` | `Sport` | `league.sport` | **yes (US1)** |
| `leagueName` | `string` | `league.name` | **yes (US1.4)** |
| `season` | `int` | `league.season` | existing |
| `sleeperLeagueId` | `string` | `league.sleeper_id` | existing |
| `leagueId` | `long` | `roster_season.league_id` | existing |
| `rosterId` | `int` | `roster_season.roster_id` | existing |
| `managerId`, `managerName`, `avatarId` | | `manager` join | existing |
| `wins`, `losses`, `ties` | `Integer` | `roster_season` | existing |
| `pointsFor`, `pointsAgainst` | `Double` | `roster_season` | existing |
| `finalPlacement` | `Integer` | `roster_season` | existing, write rule changed |
| `complete` | `boolean` | `league.status == 'complete'` | **yes (US2, FR-007)** |

**Validation**: `sport` is non-null. `forLeague` continues to supply `season`/`sleeperLeagueId`/`sport`
through the existing `withSeason` flag on `mapRow` rather than growing a second row type — one shape
for "a manager's standings row", per R1.

### CareerProfile

One manager in one sport. Never spans sports (FR-002).

| Field | Type | Derivation |
|---|---|---|
| `managerId`, `manager`, `avatarId` | | `manager` |
| `sport` | `Sport` | the grouping key |
| `seasonsCounted` | `int` | roster-seasons with at least one scored week |
| `seasonsListed` | `ManagerSeason[]` | every roster-season, including uncounted ones |
| `wins`, `losses`, `ties` | `int` | sum over counted seasons |
| `winRate` | `Double` | `wins / (wins + losses + ties)`; null when no games played |
| `pointsFor`, `pointsAgainst` | `double` | sum over counted seasons |
| `pointsPerSeason` | `Double` | `pointsFor / seasonsCounted`; null when zero |
| `averageEfficiency` | `Double` | weeks-weighted mean of per-season efficiency from `RosterManagementService`; **never** from `points_possible` (FR-006) |
| `weeksCounted` | `int` | total weeks behind `averageEfficiency` |
| `weeksExcluded` | `int` | weeks dropped for want of a per-player breakdown, carried through from `RosterManagementService.TeamRow` |
| `winsAboveExpected` | `Double` | sum of per-season `ExpectedWinsService` figures |
| `titles` | `int` | counted seasons with `finalPlacement == 1` **and** `complete` |
| `playoffAppearances` | `Integer` | null until placement beyond the champion is parsed — omitted with a reason, not zero (FR-011) |
| `ranks` | `Rank[]` | see below |

**Validation rules**:

- A season with no scored weeks contributes to nothing and is excluded from `seasonsCounted`
  (US3.4). NBA 2026 is the live case: ingested, 0 weeks.
- `averageEfficiency` is null, never 1.0, when there is no potential to divide by — the same rule
  `RosterManagementService.TeamRow.efficiency` already states.
- The league-wide sum of `winsAboveExpected` stays zero (US3.5), which is the conservation check that
  proves the aggregation did not double-count a season.

### Rank

A ranked figure always names its population (FR-008, R5).

| Field | Type | Notes |
|---|---|---|
| `figure` | `string` | `winRate`, `pointsPerSeason`, `averageEfficiency` |
| `position` | `int` | 1-based |
| `population` | `int` | managers in this league chain and sport |
| `leagueName` | `string` | the chain the rank is within |
| `sleeperLeagueId` | `string` | the chain's head |

A manager in two football chains gets two ranks for the same figure. That is the true answer, not a
chosen one — the same reasoning the per-sport `draftHistory` list already applies.

### HeadToHead

Two managers, one sport.

| Field | Type | Derivation |
|---|---|---|
| `sport` | `Sport` | grouping key |
| `aWins`, `bWins`, `ties` | `int` | per meeting, by `starters_points` |
| `meetings` | `Meeting[]` | every scored pairing |
| `seasonsExcluded` | `Excluded[]` | seasons whose fixtures are missing, each with its reason (US4.4) |

**Meeting**: `season`, `week`, `leagueName`, `aPoints`, `bPoints`, `winner` (`A` / `B` / `TIE`).

**Derivation**: join `league_matchup` (pairing, via `matchup_id` within a `league_id, season, week`) to
`roster_week_points` (scores). A meeting counts only when **both** sides have a stored
`starters_points` — the 2026 chains hold 168 and 196 *scheduled* fixtures with no scores.

**Validation**: summed across every pair in a season, meetings must equal that season's paired fixture
count (SC-006).

### LeagueRecord

An all-time extreme or streak over one league chain. Extends what `LeagueRecordService` already
produces (highest/lowest weeks, closest/biggest margins).

| Field | Type | Notes |
|---|---|---|
| `kind` | `enum` | `POINTS_LEADER`, `WIN_STREAK`, `LOSS_STREAK` |
| `manager` / `rosterId` | | an unowned roster still renders, as the record book already handles |
| `points` / `length` | `double` / `int` | **As shipped**: a points leader carries `points`, a streak carries `length`. This table originally specified one shared `value` field; the implementation named each for what it actually measures, which the "label the axis" rule prefers to a number whose unit you have to infer from its list. |
| `spanSeasons` | `int[]` | the seasons it crosses |
| `startWeek`, `endWeek` | `int` | named, per US5.1 |
| `withinSeasonOnly` | `boolean` | the rule stated on the page, per R7 / US5.2 |

### WaiverTendency

One manager, one sport.

| Field | Type | Notes |
|---|---|---|
| `movesPerSeason` | `Double` | `WAIVER` + `FREE_AGENT` rows ÷ seasons counted |
| `seasonsCounted` | `int` | seasons with transactions ingested |
| `faab` | `FaabTendency?` | null when no season used FAAB |
| `faabExcludedSeasons` | `Excluded[]` | each with reason `"used waiver priority, not FAAB"` |
| `trades` | *omitted* | with reason: trades are not attributed to a manager (FR-011) |

**FaabTendency**: `typicalBidPct`, `largestBidPct`, `spentPerSeasonPct`, `claimsPerSeason`,
`bidSuccessRate` — all percentages of **each season's own** `settings_json.waiver_budget`, aggregated
after normalisation (FR-010, R8). Budgets in the database range 100 → 10000, so a dollar figure is not
comparable across a single manager's seasons.

---

## State transitions

`league.status` is the only stateful field, and it moves one way:

```
pre_draft → drafting → in_season → complete
```

The only transition this feature reacts to is `in_season → complete`, at which point the next ingest
walk writes the champion for that season. Nothing requires a manual correction pass when the 2026
seasons finish — that is the point of storing the status rather than gating on chain position.
