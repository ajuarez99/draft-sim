# Research: The Trending Tony Award (spec 051)

All references read from `main` at 7168753 in worktree `051-trending-tony-award`.

## R1 — Which trending snapshot judges a pickup

**Decision** *(amended after review, plan-review F3 — the original text said "closest" but specified
before-first, two different rules)*: For a pickup at time `P` in sport `s`, the judging snapshot is the
**earliest snapshot in `[P, P+lookback]`** (its window `[S-lookback, S]` contains `P`); when there is none,
the **latest snapshot in `[P-lookback, P)`**. A snapshot with **zero entries judges nothing** and is skipped
(F13: an empty body is stored as a success). No usable snapshot → **not judged**. A test pins the choice with
snapshots on both sides at unequal distances.

**Rationale**: A snapshot taken at `S` lists adds over `[S-24h, S]` (`TrendingRefresh.LOOKBACK_HOURS` = 24).

- A snapshot *before* the pickup (`S ≤ P`, `P-S ≤ 24h`) says the player was trending in the day leading up
  to the pickup — the hype the manager chased.
- A snapshot *after* the pickup (`S > P`, `S-P ≤ 24h`) has a window that literally contains `P`: the player
  was trending at the moment of the pickup (and the pickup itself is one of the adds counted — negligible
  against platform-wide counts in the thousands).

Accepting the after-side is what makes the rule work at the cadence production actually has (R2): with one
guaranteed snapshot a day, a before-only rule leaves pickups made just before the daily fetch unjudged
whenever that fetch is a few minutes late.

**Alternatives considered**:
- *Before-only, ≤24h* (the spec's original FR-002). Rejected after R2's measurement: at a daily cadence its
  judged share depends on the cron's minute-level timing.
- *Nearest snapshot at any distance*. Rejected: a three-day-old list says nothing about "at the time"; the
  "not judged" class exists precisely so a gap isn't called "not trending".
- *Interpolate rank between two snapshots*. Rejected: invents data.

**Amends spec FR-002** (visibly, see spec amendment A1).

## R2 — How often trending is actually fetched in production (measured by reading code, not by DB query)

**Finding**: `TrendingRefresh.refreshIfStale` has exactly two callers:

1. `DailyRefreshService.trending` — the daily route, called by `.github/workflows/daily-refresh.yml` at
   **11:00 UTC once a day** (with up to 15 min of cold-start retries).
2. `LeagueRefreshService` (`:240`) — inside a league refresh, which is triggered by page visits (spec 009),
   gated at one fetch per `RefreshProperties.STALE_AFTER` = 1 h per sport.

There is **no in-app `@Scheduled`** anywhere in `backend/src/main` (grep: zero hits). Railway idles the app;
an in-process scheduler would not fire while it sleeps.

**Consequence**: snapshot density is **at least one per sport per day as the intended floor** (amended after
review, F13: GitHub scheduled runs can be delayed or skipped, so not a guarantee — SC-006 measures it), plus up to one per hour
during hours when someone visits. The spec's SC-006 ("≥20 snapshots per sport per day") was a guess that
assumed an hourly schedule which doesn't exist. **It was wrong**; SC-006 is amended visibly (spec amendment
A2) to "≥1 per sport per day, with the measured density reported in verification".

**Decision**: v1 adds **no new fetch schedule**. R1's ±24h rule judges essentially every pickup at a daily
cadence. A denser schedule (e.g. a 3-hourly GitHub Actions call) would wake Railway more often — a cost
question for Allan, not this feature; listed under "Not built".

## R3 — Where the history lives

**Decision**: New migration **V42** (main's highest is V41; no remote branch carries a V42+ — checked with
`git ls-tree` across every `origin/*` branch on 2026-10-27). Two tables:

- `sport_trending_snapshot (id bigserial pk, sport text, fetched_at timestamptz not null, lookback_hours int not null)`
  with an index on `(sport, fetched_at)`.
- `sport_trending_snapshot_entry (snapshot_id fk on delete cascade, rank int, sleeper_player_id text, add_count int, pk (snapshot_id, rank))`.

Written inside the **same transaction** as `SportTrendingRepository.replace` — one successful fetch is one
snapshot, and the existing current-list tables (`sport_trending`, `sport_trending_fetch`) are untouched in
shape and behaviour (FR-001, home page unchanged). A failed fetch writes no snapshot.

**Alternatives considered**:
- *Add a `fetched_at` column to `sport_trending` and stop deleting*. Rejected: changes the home page's read
  path and its primary key — a riskier change to a shipped feature for no gain.
- *jsonb array per snapshot*. Viable, but the matching query wants `(snapshot, player) → rank`; relational
  rows keep it a plain join and match how `sport_trending` already stores entries.

**Retention**: indefinite; no pruning in v1. **Bound** (amended after review, F4 — three different figures
had been stated as fact): ≤ 25 × 24 ≈ 600 entry rows per sport per day at the hourly ceiling, ≈ 220k per
sport-year; real density is visit-driven and is measured in verification.

**Read path (amended after review, F4)**: never materialise every entry in the range. Load snapshot
**headers** (`id, fetched_at, lookback_hours, entry_count`) for the sport in the range, then entries only
`where rank <= 15 and sleeper_player_id = any(:pickedUpIds)` for those snapshot ids. At most
(snapshots × 15) rows, in practice far fewer.

**Timestamp skew (F10, documented not changed)**: from a league refresh, `fetched_at` is the refresh's start
time, which can precede the actual Sleeper call by minutes on a cold chain refresh. Harmless at ±24h; not
changed here because it would alter spec 014's staleness gate.

## R4 — Which pickups count

**Decision** *(amended after review, plan-review F1 HIGH — the original said `completedAdds` "is" the
WAIVER/FREE_AGENT set; it is not: it returns completed adds of every type, TRADE and COMMISSIONER included,
and `MostAddedPlayers:102-103` filters for itself)*: start from `WaiverPickupAttribution.completedAdds(txRows)`
and filter **`type ∈ {WAIVER, FREE_AGENT}` and `week >= 1`** through one shared predicate with
`MostAddedPlayers` (extracted, not copied — two implementations of one rule is this repo's recurring bug).
Fixtures include a trending player arriving by trade and by commissioner move; both must not count.
Each `CompletedAdd` carries `createdAt`. Exclude football DEF the same way `mostAddedSuperlative` does (`:824-827`).
A pickup with a null `createdAt` is **not judged** (it can't be placed in time).

Bound to the award's scored window: pickups with `week <= throughWeek`, matching Jabari Smith Jr.'s
`MostAddedPlayers.rank(..., throughWeek, ...)`.

## R5 — Platform and sport

**Decision**: Unavailable with a reason for `league.platform() == ESPN` ("Trending data comes from Sleeper;
this league is on ESPN."). `LeagueRow.platform()` exists and ESPN leagues do reach `forLeague`, so the check
is needed.

**Rationale — amended after review (plan-review F5)**: the original said ESPN rows "never match Sleeper
trending ids". **That was wrong**: `EspnLeagueIngestService.keyed` stores the Sleeper id whenever the player
resolves, and `espn:<id>` only when not. The exclusion is a **product choice**: Sleeper's platform-wide list
says little about an ESPN league's waiver pool, and ESPN's `createdAt` is bid time (`proposedDate`) where
Sleeper's is processing time, so the same rule would mean something different. Sport comes from `league.sport()`; snapshots are filtered
by that sport's code.

## R6 — "Unavailable" vs "nobody yet" vs coverage

**Decision**, in order:

1. ESPN → unavailable (R5).
2. Transactions not loaded (`txRows.isEmpty()`) → unavailable, same reason text as Jabari Smith Jr.
3. **Zero judged pickups** → **unavailable** (amended after review, F2 — the original also required zero
   snapshots in range, which allowed an available card with a null `judgingSince`). Two reasons:
   - no snapshot for the sport at or before the season's last counted pickup + 24h → "Trending history
     wasn't being recorded yet for this season." (every past season; SC-003)
   - otherwise → "No pickups since trending history began on <date>." (the current season on ship day)
4. Judged pickups exist but the top count < 2 → available, nobody named, "nobody has chased the trending
   list twice yet" (FR-006).
5. Otherwise → winner(s) by count.

In cases 4 and 5 the card carries a **trending coverage** block: the first snapshot's date that judged any
pickup of this season, and the counts judged / not judged / total (FR-007, SC-002).

## R7 — Payload shape

**Decision**:

- `Kind.TRENDING_TONY` appended **last** to the enum (order is display order — `forLeague` iterates
  `Kind.values()`; last keeps every existing card where it is). Not early-eligible (a count of events,
  like CLOSE_WINS / Jabari).
- Unit `"PICKUPS"`.
- `standings`: every roster, value = trending-pickup count (zeros included, competition ranks), note =
  `"N of M judged pickups"` (the share — the "count wins, share shown alongside" clarification).
  A roster with no judged pickups at all still has value 0 and the note "no judged pickups" — it was
  measured (its pickups, if any, are known), so it is not a `hasValue=false` row. *Exception*: none —
  every roster's count is knowable once the award is available.
- `detail`: new `TrendingPickupDetail(playerId, playerName, position, rosterId, week, addType,
  trendingRank, addCount, snapshotAt)` rows for the holders' counted pickups (card shows them).
- `playerContributions`: one row per counted trending pickup, **every** roster (FR-009) — feeds the
  existing Teams/Players toggle in `SuperlativeStandings.tsx`. `value` = trending rank, ordered by trending
  rank ascending then week; `note` = `"week 5 · waiver"`. **Amended after review (F6)**: the component
  applies one `figure` prop to team rows *and* contribution rows, so a rank would print as "2 trending
  pickups". `SuperlativeStandings` gains a separate optional `contributionFigure` prop (structural, small);
  Trending Tony passes `v => '#' + v + ' trending'`. Tests pin both views' text.
- `unit` union in `api.ts` gains `'PICKUPS'`; `formatValue` (Superlatives.tsx) and Home's `unitLabel`
  (`LeagueHome.tsx:127-140`) gain a PICKUPS case (F7).
- `judgingSince` is sent as an **instant** (`judgingSinceAt`) and formatted as a local date in the browser
  (F13: a UTC date can read as tomorrow for US viewers).
- New nullable record component **`trendingCoverage`** on `Superlative`:
  `TrendingCoverage(OffsetDateTime judgingSinceAt, int judged, int notJudged, int totalPickups)`; null for
  every other kind. Requires: a new **14-arg overload** defaulting it to null (so the existing direct
  14-arg builders compile), **both rebuilders (`withUsernames`, `withStandings`) switched to the 15-arg
  canonical constructor**, and a test running a TRENDING_TONY superlative through **each** rebuilder
  (amended after review, F12: once the overload exists, the rebuilders would compile unchanged and silently
  drop the field). Plus the controller's DTO map, and `web/src/api.ts` in the same change (constitution
  rule 2) — including all five fixture files that build `Superlative` objects (F7: `TrophyCase.test.tsx`,
  `LeagueHome.test.tsx`, `AwardWinners.test.tsx`, `SuperlativeStandings.test.tsx`, `Superlatives.test.tsx`;
  47 fixtures), via a shared fixture factory where practical.

**Alternatives considered**: reusing `Coverage(weeksCovered, weeksExcluded, reasons)` — rejected: its
fields are weeks and the UI renders "N of M weeks"; pickups dressed as weeks would read wrong (lesson
class 5).

## R8 — Hand-set constants

`TRENDING_TOP_N = 15` (clarified), `MATCH_WINDOW = 24h` (equal to the stored lookback, not tuned),
`MIN_TO_NAME = 2` (FR-006). Held as labelled constants in one pure class, `TrendingPickups`, with a
comment saying they are hand-set. Not in `weights.yml` — that file is the draft engine's.
