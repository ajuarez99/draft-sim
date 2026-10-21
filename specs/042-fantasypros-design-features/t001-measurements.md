# T001 measurements (2026-10-20, local DB `draftsim-pg` on 5433, flyway V38)

These were measured against the **local** DB as it stood, without a fresh player re-ingest. Statuses are
as of the last local ingest, so they may lag prod.

## (a) Status vocabulary: `player.injury_status` × `player.status`

NBA: `injury_status` ∈ {DTD, GTD, Out, IR, null}. Suspension is `status = 'SUS'` (1 player) with a null
`injury_status`. That **confirms review F4 by measurement**: a rule that reads only `injury_status` misses
it. Other NBA `status` values: FA, ACT, RET, TWO-WAY, NWT, M-LEAGUE, DUP.

NFL: `injury_status` ∈ {IR, Out, Questionable, NA, PUP, DNR, null}.

Consequences:
- `isUnavailable` (Out/IR/Sus) is OUT. NBA DTD/GTD and NFL Questionable/PUP/DNR are **LISTED**.
- **NFL `NA`** (35 players, mostly Inactive) has no documented meaning. Show it as "Listed: NA" rather than
  inventing a meaning. It's flagged here as unexplained.

## (b) `sport_schedule` rows

| sport | season | rows | last fetched |
|---|---|---|---|
| nba | 2025 | 1235 | 2026-10-06 |
| nba | 2026 | 1200 | 2026-10-20 16:16 UTC |
| nfl | 2026 | 273 | 2026-10-07 |

**NFL rows exist**, so `gameCheck` is true for football too (bye weeks). Research R5's "no game check for
football" fallback stays only as the path for a season with no rows. The NFL schedule was last fetched
on 10-07, so print its `fetchedAt`.

## (c) Starter order vs starting `roster_positions` (NBA 2025, league 1229352720222134272, V18)

Slots: `{PG,SG,G,SF,PF,F,C,UTIL,UTIL}`. 2,268 starter entries: 7 empty (`"0"`), 0 unknown players, and **117 not
eligible by today's `player.positions`**. The mismatches are spread one per lineup across all slots. They
come from a few named players whose eligibility changed since 2025 (Miles Bridges PF in SF ×7, Jamal Murray PG
in SG ×6, Myles Turner C in F ×6, …). **Interpretation, not proven**: this is position drift, not a
misordered array, because a misordered array would break many slots per lineup. So slot labels can follow
`roster_positions` order. Not stopped.

## (d) As-of sources

- `status_capture`: NFL max = 2026-10-20 16:36 UTC. **NBA has no rows**: every NBA ingest so far was preseason,
  so capture was skipped (review F2, now measured).
- `daily_capture`: **0 rows locally** (the cron runs only in prod).

So locally the NBA as-of is **null**, and the UI must say "status time unknown". From today's regular season
on, the next ingest writes an NBA capture.

## (e) Lineup lock cadence

`settings_json` has no key that names a lineup lock. `bench_lock = 0`, `daily_waivers = 0` and
`waiver_after_game_start = 1` are all about waivers or bench, not lineup locks. **Not established.** The
lock-cadence line in T016 is therefore **not** shown. Options are listed as facts, and the copy doesn't claim
they can be acted on today.
