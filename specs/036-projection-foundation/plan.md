# Implementation Plan: Projection snapshots and a measured accuracy number

**Branch**: `036-projection-foundation` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/036-projection-foundation/spec.md`

## Summary

A daily step captures Sleeper's weekly projection rows before each game and freezes them at a
per-game cutoff (US1, which must be deployed before NBA tip-off on 2026-10-20). A read-time
service pairs each frozen snapshot with the same player's realized game by `game_id`, scores
both with `GameScoringService` under the asking league's scoring, and reports MAE, lean and
did-not-play with sample counts. It reads "too early" below a hand-set N (US2). Football's
weekly projection cache also starts keeping stat lines, and an admin parity check decides
whether football valuation can switch from the PPR column pick to exact league scoring (US3,
behind a flag that defaults off).

Research is in [research.md](research.md). R3 (the double pre-game guard), R6 (also grading the
season-average line the page actually shows) and R7 (scored / did-not-play / pending by
`game_id`) are the decisions everything else rests on.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5, virtual threads), TypeScript (React + Vite)
**Primary Dependencies**: Spring JDBC `JdbcClient`, Flyway, RestClient; React Router; Vitest
**Storage**: Postgres. New `projection_snapshot` table, plus a nullable `stats` column on `player_projection`, both in V36.
**Testing**: JUnit 5 + Testcontainers-style ITs against local Postgres (`./gradlew test`; check the skip count, see memory), Vitest for web
**Target Platform**: Railway (backend + web services), GitHub Actions cron for the daily refresh
**Project Type**: web application (`backend/`, `web/`)
**Performance Goals**: the accuracy read for one league with a full NBA season (~25k samples, ~50k `score` calls) responds in under 1 s. It's measured in verification, not assumed.
**Constraints**: US1 deployed before 2026-10-20. Flyway `outOfOrder=false` (see Risks). There's no second scoring implementation.
**Scale/Scope**: ~1,000 NBA snapshot rows/week, ~450 NFL rows/week, 2–3 upstream calls per sport per day

## Constitution Check

| Rule | How this plan complies |
|---|---|
| Migrations append-only | One new file `V36__projection_snapshot.sql`. Nothing already applied is edited. |
| `api.ts` mirrors Java records | `ProjectionAccuracy` record ↔ `ProjectionAccuracy` type, and the new `accuracy` field on `Projections` is added in the same change (contracts/accuracy-api.md lists every field). |
| Never retune hand-set constants to a measurement | R10's constants are set now, before any sample exists, and FR-014 forbids feeding the result back. |
| `Map.of` null hazard | The accuracy payload is records, not maps. Nullable fields (`mae` below N, `seasonAvg` for NFL) are record components, not `Map.of` entries. |
| Ask before committing | Commits and deploy wait for Allan. US1 needs a deploy before 10-20, so it's flagged early. |
| Planning doc ≠ verified spec | Applied already: roadmap 3.2 was found built, and the NBA "sum the week" rule was found wrong (spec Background). |

**Gate: PASS.** There are no violations to justify.

## Project Structure

### Documentation (this feature)

```text
specs/036-projection-foundation/
├── spec.md
├── plan.md              # this file
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── accuracy-api.md
│   ├── snapshot-refresh.md
│   └── parity.md
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
backend/src/main/resources/db/migration/
└── V36__projection_snapshot.sql                    # new table + player_projection.stats

backend/src/main/java/com/ballknowers/draftsim/
├── config/ProjectionAccuracyProperties.java        # new: R10 constants (draftsim.projection-accuracy / -snapshot)
├── ingest/ProjectionSnapshotService.java           # new: R3/R4/R5 capture
├── ingest/SleeperProjectionClient.java             # unchanged (week() reused)
├── ingest/SleeperPlayerStatsClient.java            # reuse its schedule call (/schedule/{sport}/regular/{season})
├── ingest/ProjectionIngestService.java             # store stats (R9)
├── store/ProjectionSnapshotRepository.java         # new: guarded upsert + accuracy read
├── store/PlayerProjectionRepository.java           # Row gains stats
├── engine/ProjectionAccuracyService.java           # new: R7/R8 pairing + summary
├── engine/LeagueAnalysisService.java               # Projections.accuracy; flag-gated football exact scoring
├── refresh/DailyRefreshService.java                # PROJECTION_SNAPSHOT step
├── api/ProjectionAccuracyController.java           # new: GET accuracy (member-scoped)
└── api/IngestController.java                       # admin: snapshot run + parity check

backend/src/test/java/...                           # see tasks; preference/ordering + freeze tests

web/src/
├── api.ts                                          # ProjectionAccuracy types, Projections.accuracy
├── projectionCopy.ts                               # provenanceLine: "RotoWire projection via Sleeper", measured/too-early
├── components/ProjectionAccuracyPanel.tsx          # new: the breakdown table
└── pages/LeagueAnalysis.tsx                        # mounts the panel

config/weights.yml                                  # R10 block, labelled arbitrary
.github/workflows/daily-refresh.yml                 # unchanged: the step rides the existing endpoint
```

**Structure Decision**: this is the existing web-application layout. Nothing new at the top
level. The accuracy breakdown sits on the Analysis page because that's where the projected
numbers it grades are. The one-line summary rides inside the existing `Projections` payload, so
the preseason strength block on League home shows it too, from the same computation.

## Design

### US1: capture (ships alone if needed)

`ProjectionSnapshotService.capture(Sport sport, Instant now)` does this:
1. Gets the current season (the same "newest season, not-complete league" rule as
   `DailyRefreshService.seasonProjections`).
2. Runs `statsClient.schedule(sport, season)` and builds a map from game_id to (status, date).
3. Picks the weeks with a `pre_game` game dated in [today ET, today ET + 7].
4. For each week, runs `projectionClient.week(...)` and keeps rows with a usable stat line:
   NBA `pts` non-null, NFL `pts_ppr` non-null. That matches the existing stub filter.
5. For each row, takes `game_id` and `date`. `cutoffAt` is `date` at the sport's cutoff time
   in America/New_York. The row is pre-game iff the schedule status is `pre_game` and
   `now < cutoffAt`. If it isn't, it counts as `rowsNotPreGame` and isn't written.
6. NBA only: it copies `player_season_projection.stats` for the player as `season_avg_stats`.
   The row is read once per run as a map.
7. `repo.upsertPreGame(rows)` does `insert … on conflict (sport, season, sleeper_player_id,
   game_id) do update … where excluded.fetched_at < projection_snapshot.cutoff_at`. The
   affected count separates written from frozen-skipped.
8. It returns `Result(weeks, rowsWritten, rowsFrozenSkipped, rowsNotPreGame)`.

`now` is a parameter so tests can drive the cutoff without a clock mock. The admin route
`POST /api/ingest/projection-snapshot?sport=` runs the same method (contracts/snapshot-refresh.md).

### US2: accuracy

`ProjectionAccuracyService.forLeague(LeagueRow league)` does this:
- Loads the pre-game snapshots for (sport, season) left-joined to `player_game` on
  (player, game_id), plus an `exists` for any `player_game` with that game_id. That's one SQL
  statement that returns the raw stat lines.
- Classifies each snapshot as scored, did-not-play or pending (R7).
- For scored samples: `proj = score(scoring, stripped(snapshot.stats))`,
  `actual = score(scoring, pg.stats)`, error = proj − actual. For NBA the same is done with
  `season_avg_stats` when it's non-null (R6), as a second series.
- Aggregates overall, per position and per band (bands judged on the projected value),
  giving n, MAE and mean signed error. A cell with n < min-cell shows the count and no figure.
  Overall n < min-samples gives `status = TOO_EARLY` and no figures anywhere.
- Adds `scoredThrough` (the latest realized game_date among samples), `snapshotsFrom`, the
  thresholds it was judged against, and a fixed caveat list: "morning-of line, late
  inactives not reflected", and for NBA "Sleeper credits one game per week; this grades
  individual games".

It's computed on read, and `LeagueAnalysisService` gets its summary from the same service:
`Projections.accuracy = accuracyService.summary(league)`, which is overall status, n, MAE and
lean only. There's one computation and two views of it (memory: count and label, one source).
If the read measures over 1 s, cache by (league, max snapshot fetched_at, max player_game
fetched_at). That's decided in verification, not up front.

### US3: football exact scoring

- `ProjectionIngestService.toRows` keeps `stats` (stripped of `adp_*`) on the row it already
  builds. `PlayerProjectionRepository` upserts it.
- `GET /api/admin/projection-parity?sport=nfl&season=&week=` (admin token) scores every stored
  row's `stats` with the full-PPR map (R9) and compares to `pts_ppr` at 0.01. It returns
  counts, the scoring source used, and the first 50 mismatches with their stats.
- `LeagueAnalysisService`'s football points supplier: if `nflExactScoring` and the row has
  `stats`, use `gameScoring.score(league.scoring, stats)`. Otherwise use the existing column
  pick. The flag is in `application.yml` (`draftsim.projections.nfl-exact-scoring: false`),
  not weights.yml, because it's a switch, not a model constant.

### Web

- `provenanceLine` renders `"RotoWire projection via Sleeper"` when `source === 'rotowire'`,
  and `"<source> projection via Sleeper"` otherwise. Then comes "fetched <age>", then:
  - when `accuracy.status === 'MEASURED'`: `"off by ±X.X pts a game on average, n games"`
    (NBA) or `"… a week …"` (NFL), linking to `#projection-accuracy`.
  - when `TOO_EARLY`: `"accuracy: too early to measure (n of N games)"`.
  - when accuracy is null (no snapshots for this sport/season yet): the existing "accuracy
    for this league not measured".
- `ProjectionAccuracyPanel` on the Analysis page is a table with rows for overall, then
  positions, then bands. The columns are n · typical miss · lean · did-not-play. Projected
  and realized are never in one column: this table shows only errors and counts. For NBA
  there are two series tabs: "Game projection" and "Season average (what the rankings use)".
  The lean is signed with words ("runs 2.1 high") and never color-coded green/red (FR-011,
  memory: label the axis, spell out the number).

## Risks

1. **Flyway ordering (R11, amended).** `outOfOrder` is unset. This spec is V36; 033 is V34
   and 035 is V35. V36 applied in prod before V34/V35 means those can never apply without a
   renumber. Local verification uses a separate `draftsim_036` database so the shared one
   isn't touched. **Action:** before the US1
   deploy, check prod's `flyway_schema_history`. If 033 isn't deployed yet and 035 is still
   uncommitted, coordinate: either 033 lands first, or this file takes the next free number
   at merge time. The number is decided at merge, not now.
2. **Deploy before 2026-10-20** needs Allan's go-ahead for a push and a Railway deploy. US1
   is a separate commit so it can ship without US2/US3.
3. **The cutoff and the date's timezone are assumptions (R3).** Fail-safe by design. The
   first-night production check (SC-001) reports how many games were captured, and a gap
   says the guard was too strict, not that it passed.
4. **Prod has no NBA realized games until 10-20+**, so US2 can only be verified locally
   against fixture data until the season starts. TOO_EARLY is the expected production state
   at deploy.

## Complexity Tracking

There are no constitution violations. The only added moving part is the second NBA series
(R6). It's kept because grading only the per-game rows would measure a number no page shows.

## Amended after plan review (2026-10-14)

An adversarial review read this plan cold and ran its own queries and curls. Every amendment
below changes the design above. Where they conflict, **this section wins**. Each item names
what was wrong.

**B1. Migration is V36, not V35.** See research R11, which was amended. 035 holds V35 and 033
was renumbered to V34. Local verification uses its own `draftsim_036` database.

**B2. The CHECK runs before ON CONFLICT.** It was measured in a temp table: an incoming row with
`fetched_at >= cutoff_at` throws, even on a key conflict, and it aborts the whole batch.
- The service drops late rows **before** the SQL. It counts them as `rowsNotPreGame`. They
  never reach the upsert.
- Rows are deduplicated on (sport, season, player, game_id) before the batch, because two
  identical keys in one statement also throw (review N1: per-position calls return 703 dupes).
- The freeze moves into the table as a `BEFORE UPDATE` trigger. When `now() >= OLD.cutoff_at`
  it returns NULL, so the update is skipped silently and nothing aborts. That makes FR-003's
  "by any path" structural (review N4), and it's also what produces `rowsFrozenSkipped`.
- `check (fetched_at < cutoff_at)` stays as the wall for inserts.
- **Tests:**
  1. `capture(now past cutoff)` leaves stats unchanged, and the row counts as `rowsNotPreGame`.
  2. A direct UPDATE on a frozen row changes nothing.
  3. A direct INSERT of a late row is rejected.

**B3. Projected lines lack bonus categories that realized lines have.** That would have built
"runs low" into the lean. Measured: NBA bonus keys (`bonus_pt_40p` etc.) add +0.69 pts per
game to realized under 2.0 weights. NFL leagues score `bonus_rec_yd_100` and others that
projections never carry.
- **Both sides of every sample are scored with the same restricted scoring map**: the league
  map minus every category no projection line in that sport-season carries.
- That set is the same thing `LeagueAnalysisService` already computes as
  `unprojectedCategories` (`:606-612`). Extract it into one shared helper and call it from
  both places. Don't write it twice.
- The accuracy payload lists the excluded categories as a caveat ("Not graded: 40-point
  bonus, …").
- **Ordering test:** a realized line identical to the projection plus a bonus category gives
  lean 0, not negative.

**S1. The NBA summary comes from the SEASON_AVERAGE series.** That's what the NBA rankings
actually show. NFL's summary comes from the GAME series. The summary's `label` says which
series it is.

**S2. The NBA series compare the same population.** Both NBA series are computed over the
snapshots that have a `season_avg_stats` line (about 529 of about 936 players have one). The
payload also gives `withoutSeasonAverage: n` so the gap is stated, not hidden.

**S3. The preseason strength block has its own record.** Add `SeasonStartService.SeasonStrength`
(`SeasonStartService.java:77`, built at `:145`, `:244`, `:262`), `SeasonStrength` in
`api.ts` (~`:3032`) and `Footnoted` in `projectionCopy.ts:96` to the change list. Each one
carries `accuracy` copied from the same `Projections.accuracy` object. It's never recomputed.

**S4. Only finished games are graded.** A sample counts as scored or did-not-play only when
`sport_schedule.status = 'complete'` for its game_id. `sport_schedule` is rewritten by
`PlayerGameIngestService` in the same run that writes `player_game`, so the two are equally
fresh. Anything else is pending. This replaces R7's "doesn't depend on sport_schedule". The
on-visit refresh can store partial box scores mid-slate (unverified upstream, but not
excluded by the code).

**S5. A postponed game never reappears under its old id.** Measured: NBA 2025's 3 postponed
games have 0 `player_game` rows under their original id. The rescheduled games got new ids.
- A snapshot whose game_id is `postponed` or `canceled` in `sport_schedule` is counted as
  `voided`, separately, and excluded from everything else. The new id's game gets its own
  snapshot from a later capture if it's still ahead.
- The spec's "postponed game" edge case and research R4 were wrong about this. Both are
  corrected in place.

**S6. The parity tolerance is set before running.** Measured on NFL 2026 week 6, 402 rows:
241 rows off by more than 0.01, 27 by more than 0.5, max 2.11. Input stats are rounded to 2
decimals, so the 0.01 tolerance would fail on rounding alone.
- **The tolerance per row is Σ |weight_k| × 0.005 over the keys the row carries, plus 0.005.**
  That's the worst case from rounding, fixed now.
- The check reports `withinTolerance` and `beyondTolerance` separately. The ~27 large misses
  need a cause in the verification notes before the flag can flip.

**N1. Now measured, no longer assumed.** `date` is the local/Eastern game date: GSW@LAL
2025-10-21 tipped about 02:00 UTC on 10-22 and is dated 10-21. NFL week-6 projection game_id
and date match the schedule 402/402. NBA week 1 is 10-20..10-25, and the All-Star gap is
02-19..02-24. Research R3's "assumed Eastern" now reads as measured, though the double guard
stays.

**N3. Measure the cost before 10-20.** The daily workflow's curl has `--max-time 180`, and a
timeout re-runs the whole refresh. Verification measures `runAll` wall-clock with the new
steps locally. If it adds more than 30 s, the snapshot step moves to its own route and its
own cron line. The accuracy summary is computed on every `/analysis` and League home read, so
its read time is measured. Above 200 ms, cache it by (league, max snapshot `fetched_at`, max
`player_game.fetched_at`).

**N4. Expect conflicts with 035.** 035's uncommitted tree also edits `LeagueAnalysisService`.
