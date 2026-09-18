# Phase 0 Research: ffwrapped feature parity, both sports

**Date**: 2026-09-18 | **Branch**: `004-ffwrapped-feature-parity`

Everything below was read out of this repo or measured live against ffwrapped on 2026-09-18. Where a
finding contradicts what the code's own comments imply, that is called out — those are the expensive ones.

---

## R1. The full ffwrapped surface (measured)

Read off ffwrapped's sidebar on 2026-09-18, signed out, against league `1346366555759341568`. Nineteen
views in four groups. View slugs follow `?leagueId=...&view=<slug>`:

- **Top level**: `league-hub`, `dashboard`, `start-sit`, `weekly-report`
- **League Analysis**: `standings`, `power-rankings`, `expected-wins`, `roster-management`, `playoffs`,
  `season-forecast`, `draft`
- **My Team**: `player-values`, `trade-lab`, `rate-my-team`, `player-comparison`
- **History & Highlights**: `league-history`, `manager-profiles`, `historian`, `wrapped`

The `roster-management` view the user linked contains six blocks: a Total / Potential / Efficiency
standings table; a Points vs Potential grouped bar chart; a League Transactions chart broken out by
type (waiver claims, free agents, trades, league manager); League Trades with post-trade positional
grading; Waivers & Free Agent Adds with FAAB spend and failed bids; and a Best Adds ranking.

**Decision**: Treat the sidebar as the roadmap, `roster-management` as the first substantial deliverable.

---

## R2. Per-player weekly points are already stored (the finding that changes the estimate)

`roster_week_points` (migration V5) stores, per league-season-week-roster:

- `starters_points numeric(8,2)` — the roster's actual scored total for that week
- `players_points jsonb` — `sleeper_player_id -> points`, for **every** player on the roster

The migration comment states the intent outright: *"players_points is kept alongside it ... in case a
future view wants a per-player breakdown, not just the roster total."* That future view is this one.

This single fact is why Roster Management, Expected Wins and most of Weekly Report need **no new
ingest**. Potential points is a pure function of `players_points` plus the league's roster slots plus
per-player position eligibility — all of which the app has for both sports.

**Decision**: Build all backward-looking views on `roster_week_points`. Do not add a new ingest for them.

**Alternatives considered**: Recomputing from Sleeper on demand (rejected: slower, rate-limited, and
would produce numbers that drift from the stored ones the power rankings already use). Storing a
precomputed `potential_points` column (rejected for now: it is cheap to compute, and a stored
aggregate would go stale silently the way `points_possible` can — see R3).

---

## R3. `points_possible` already exists, and is Sleeper's number, not ours

`roster_season.points_possible` is populated from Sleeper's `ppts`/`ppts_decimal` roster settings during
league-history ingest. It is a season aggregate computed by Sleeper under Sleeper's rules.

This is a trap worth naming: it would be tempting to render `points_possible` directly as "Potential
Points" and declare the feature done. Two reasons not to:

1. It is a season total with no per-week breakdown, so it cannot drive the Points vs Potential chart per
   week, cannot support the Weekly Report awards, and cannot exclude a bad week (FR-007).
2. It is Sleeper's optimal-lineup rule, not this app's `SportRules` rule. For basketball especially,
   where multi-position eligibility makes "optimal" a matroid problem, silently adopting another
   system's answer means the efficiency number and every lineup-based view disagree about what a
   starting lineup is.

**Decision**: Compute potential per week through `SportRules`. Keep `points_possible` as a cross-check
in tests — if the computed season total diverges wildly from Sleeper's, that is a signal worth reading,
not a failure.

---

## R4. The basketball blocker is mis-scoped, and the hard part is already built

`SportRules.startingLineup(roster, settings, valueOf)` has a default implementation that throws
`UnsupportedOperationException`. Its javadoc explains why:

> *"Unimplemented for a sport until that sport has a projection source to value a lineup with. This
> throws rather than quietly falling back to `value`, which would answer a projection question with a
> draft-board answer and look like it worked."*

That reasoning is sound **for projections** and wrong for this roadmap, because Roster Management asks a
backward-looking question. Realized weekly points are not a projection — Sleeper reports them for NBA
exactly as for NFL, and the app already stores them (R2). The documented blocker does not apply.

Verified implementation status:

| | `startingLineup` (names players) | `startingLineupValue` (sums) |
|---|---|---|
| `FootballRules` | implemented | delegates to `startingLineup` |
| `BasketballRules` | **not implemented — inherits the throwing default** | standalone: `lineupValue(prepareLineup(roster, settings, this::value))` |

Football's javadoc on this exact relationship:

> *"Two callers, one rule... Writing the second one separately would be two implementations of 'what is
> this roster's starting lineup', which is the shape of bug that has shipped three times in this repo
> under a different name each time."*

Basketball currently has the shape football was explicitly refactored away from.

**The fix is small, and this is the key estimate in the whole plan.** `BasketballRules.prepareLineup`
**already accepts an arbitrary `valueOf` function**, and already solves the genuinely hard problem: the
optimal lineup under multi-position eligibility across nine nested slots (`PG,SG,G,SF,PF,F,C,UTIL,UTIL`)
as a maximum-weight independent set over a transversal matroid, provably optimal by the matroid greedy
theorem. That work is done, measured (`claude/scripts/nba-greedy-optimality.py`) and in production.

What is missing is only the accessor that reads the seated players and their slots off the `Lineup`
that `prepareLineup` already builds, instead of summing them.

**Decision**: Implement `BasketballRules.startingLineup` by reading out `prepareLineup`'s result, then
redefine `startingLineupValue` to delegate to it — matching FootballRules exactly. Then delete the
throwing default from the interface so no future sport can inherit it silently (FR-001).

**Alternatives considered**: Leaving the default and having services branch on sport (rejected — that
*is* the bug class). Implementing a separate "realized lineup" solver for basketball (rejected — two
implementations of one rule, and the existing one is provably optimal).

---

## R5. Greedy optimality depends on sorting by the value being maximized

`FootballRules.startingLineup` carries a warning that generalizes to this feature:

> *"The sort is not decoration. `RosterState.at` returns a position's players ordered by ADP, and the
> version of this method that only ever ran on `value` could therefore take the first `slots` of that
> list as 'the best ones' — true only because board value is monotone in ADP. It is not true of
> projected points."*

It is even less true of realized weekly points: a player drafted in round 12 routinely outscores a
round-2 pick in a given week. The matroid greedy theorem likewise guarantees optimality only for a pass
taken in **non-increasing order of the value being maximized**.

**Decision**: Every call site passing realized points must sort by realized points, and the basketball
read-out must not assume `prepareLineup` was fed a board-monotone function. A test feeds a deliberately
ADP-inverted value function to both sports and asserts the lineup is optimal for *that* function.

---

## R6. The ingest skip gate will silently defeat a new per-week column

`LeagueHistoryIngestService.ingestWeeklyPoints` caches aggressively:

```java
boolean settled = stored.contains(week) && paired.contains(week);
if (settled && week != lastScoredLeg) continue;
```

The gate tests whether **rows exist**, not whether a particular column is populated. This has already
caused one production bug in this repo, and the javadoc documents it:

> *"While the gate was keyed on stored scores alone, every season ingested before that date was skipped
> wholesale on every subsequent run, so the pairing was never written and re-running could never repair
> it: the skip was keyed on the table that was already full. Measured on (Foot) Ball Knowers 2025 —
> 204 scores over 17 weeks, and pairings for exactly one week."*

Adding a `starters` column (US5) walks into exactly this. Re-running ingest will skip every settled
week, the column stays null forever, and the failure is silent.

This is a different failure from the `adp_at_time` one: Sleeper still serves historical matchup payloads
for past weeks, so the data *is* recoverable — unlike ADP, which is gone outside its snapshot window.
The problem is purely that the app will not ask for it.

**Decision**: Any migration adding a per-week column ships with (a) the skip gate extended to test the
new column's presence, following the pattern already used for pairings, and (b) a verification step in
quickstart that counts populated rows after re-ingest rather than trusting `BUILD SUCCESSFUL`.

---

## R7. Transactions: the client method exists, nothing calls it

`SleeperClient.transactions(String leagueId, int week)` is implemented and has **zero callers** in the
codebase. There is no table, no ingest step and no repository for transactions.

Sleeper's transaction payload carries type (`waiver` / `free_agent` / `trade`), status, `adds` and
`drops` as `player_id -> roster_id` maps, `roster_ids`, `settings.waiver_bid` for FAAB, `leg` (week) and
`status_updated` (epoch ms). Failed waiver bids appear as transactions with a failed status, which is
what ffwrapped's "Failed bids" section reads.

**Decision**: US6 adds one table plus an ingest step wired into the existing per-league ingest, walking
weeks 1..`last_scored_leg` the same way weekly points does, with the same skip discipline (R6) keyed on
the transactions table's own stored weeks.

**Alternatives considered**: Fetching transactions on page load (rejected — a season is up to 18 calls
per view load, and it breaks the app's existing "nothing is computed on a page load" rule).

---

## R8. The playoff simulation already computes what two ffwrapped views display

`PlayoffOddsSimulator` runs 10,000 simulated seasons; `PlayoffOddsService` assembles inputs and stores
the result. Today the UI surfaces a single playoff-odds percentage in the Record cell.

ffwrapped's Season Forecast (projected race, win ranges at the 10th–90th percentile, average seed, No. 1
seed odds) and Playoffs (seed odds, bracket path, threat map) are both readings of that same
distribution. The app is already computing the distribution and discarding everything but its marginal.

Two existing rules must survive, both documented in `PlayoffOddsService`:

- A league with divisions or a non-default `playoff_seed_type` gets **no snapshot** — the page keeps
  rendering "--" because *"a wrong 78% is not"* the honest answer.
- **Nothing is computed on a page load.** Snapshots are written on the commissioner's recompute.

**Decision**: Extend the snapshot to record per-seed counts and a win histogram, and serve those from
the existing endpoint. Do not add a second computation path, and do not let a new endpoint become a
back door around either refusal rule (FR-008, FR-009).

**Alternatives considered**: Computing the forecast on demand for responsiveness (rejected — it breaks
the single-snapshot rule and would let two views disagree, which memory records as a bug already fixed
once under a different name).

---

## R9. Which views are sport-blocked, and by what

| View | Blocker | Both sports after this plan? |
|---|---|---|
| Roster Management | `startingLineup` (R4) | Yes |
| Expected Wins | none — pure scores | Yes |
| Weekly Report | `startingLineup` + `starters` column (R6) | Yes |
| Season Forecast / Playoffs | none — scores + pairings | Yes, where seeding is modelled |
| Transactions | none — same Sleeper shape | Yes |
| Start/Sit | no NBA projection source | **No** — NFL only |
| Rate My Team | no NBA projection source | **No** — NFL only |
| Player Values / Trade Lab | no NBA value model | **No** — NFL only |

**Decision**: The projection-bound views declare `sports: ['nfl']` explicitly in `destinations.ts`, never
by default. Memory records that a defaulted sport list has shipped this bug three times.

---

## R10. RESOLVED 2026-09-18: NBA is weekly, and real scored data already exists

**Measured against the real NBA league** (`GET /league/1229352720222134272`, "Ball Knowers", nba, 2025):

| Setting | Value | Consequence |
|---|---|---|
| `status` | `complete` | a full season is scored and ingestable |
| `last_scored_leg` / `leg` | `21` | **weekly, not daily** — a daily-lineup league would number legs in the hundreds |
| `playoff_week_start` | `19` | 18 regular-season weeks, consistent with weekly legs |
| `roster_positions` | `PG,SG,G,SF,PF,F,C,UTIL,UTIL` + 5 `BN` | exactly the nine slots `BasketballRules` hardcodes |
| `playoff_seed_type` | `0` (default) | seeding **is** modelled — US4 can forecast for this league |
| `divisions` | `0` | same |

And the week-1 matchup payload (`/matchups/1`) is shape-identical to football's:
`players_points` (15 entries), `starters` (9 ids), `points`, `starters_points`, `matchup_id`.

**Both halves of the open question are answered, and more favourably than the plan assumed:**

1. **Cadence**: weekly. The "refuse daily-lineup leagues" escape hatch is not needed for this league. It
   is still worth keeping as a guard, because nothing stops a future NBA league from being daily, but it
   is not on the critical path.
2. **Test data**: a live NBA league with 21 scored weeks already exists. NBA acceptance does not have to
   be fixture-only — the fixtures remain useful for deterministic unit tests, but the service-level and
   live checks can run against real basketball data from the start.

The one correction to the plan: memory recorded that "NBA managers can't be fitted until the 2026 draft
happens." That is about *manager profile fitting*, which needs a completed NBA **draft**. It does not
apply to any story here — every backward-looking view needs scored **weeks**, which this league has.

### Original question, kept for the record

Two things could not be settled from the repo:

1. **Cadence.** Sleeper NBA leagues can run daily lineups rather than weekly. If a league does, the
   meaning of a "week" in `roster_week_points` and of "optimal lineup" both change — an optimal *weekly*
   lineup is not well defined when starters are set daily.
2. **Test data.** Memory records that NBA managers cannot be fitted until the 2026 draft happens, so
   there may be no ingested NBA league with scored weeks to verify against.

**Decision**: Resolve (1) by inspecting a real NBA league's `settings` during US1, and scope the first
release to weekly-lineup NBA leagues, refusing daily-lineup leagues explicitly rather than computing a
number whose definition does not hold. Resolve (2) by building the NBA acceptance tests against a
fixture derived from a real Sleeper NBA matchup payload, so US1–US3 are verifiable now and a live NBA
league only confirms what the fixture already asserts.

This is the one genuine NEEDS CLARIFICATION in the plan, and it is confined to US1's first task. It does
not block any football work, and it does not block the shared code path.

---

## R11. Constitution

`.specify/memory/constitution.md` is the unmodified Spec Kit template — every principle is still a
`[PRINCIPLE_N_NAME]` placeholder. There are no ratified project gates to check against.

**Decision**: Record the Constitution Check as not applicable and fall back to the conventions this
repo actually enforces, which are stricter than the empty template: one declaration per rule, honest
refusal over a confident wrong number, verify by running rather than by building, and explicit sport
lists. These are drawn from existing code comments and prior specs, not invented here.
