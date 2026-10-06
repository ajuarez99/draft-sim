# Verification: spec 017

Run 2026-10-05 evening (CDT), i.e. 2026-10-06 ~01:20–01:40 UTC, on branch
`017-nba-schedule-grid` with the uncommitted build of T005–T039. Local Postgres on 5433
(V27 applied). Backend on `draft-sim-api-8084-admin` (`ADMIN_TOKEN` set,
`CORS_ORIGINS=http://localhost:5183`), and web on `draft-sim-web-8084` (port 5183,
proxied to 8084). Both entries are in `.claude/launch.json`, and both servers were
started fresh after the Java changes. Reader: `popsharky` (`1122386008709910528`).

Each step is **run** (with output) or **not run** (and why).

## V1. Suites: run

- Backend `./gradlew test`: **1,130 tests, 0 failed, 0 errors, 0 skipped**, totals
  summed from `build/test-results/test/*.xml` (by the build agent). Postgres was up, so
  the new `SportScheduleRepositoryIT` (6) and `ScheduleControllersMvcIT` (4) ran.
- Web `npx tsc -b && npm test && npm run build`: tsc exit 0, **79 files, 998 tests, 0
  failed**, build OK (by the frontend agent).
- Caveat, recorded plainly: the US1–US3 backend agent wrote its tests alongside the
  code and **did not watch them fail first**. Phase 2's tests were seen failing
  (compile errors) before implementation.

## V2. Stored by the normal refresh (SC-001): run, pass

`POST /api/leagues/1339351318115946496/refresh` with the header, then polled the GET
until it wasn't `RUNNING` (~12 s):

```
nba|2026|1200|1200|1|25|2026-10-06 01:21:21.394682+00
fresh fetch 1200
```

## V3. Empty answer doesn't wipe (FR-002): run (tests only)

`SportScheduleRepositoryIT.emptyListIsANoOp…` and
`PlayerGameWeekIngestTest.anEmptyScheduleIsHandedToTheRepositoryAsAnEmptyList` pass.
Not exercised against a live Sleeper outage.

## V4. 2025 counting rule on real data (SC-003): run, pass

`POST /api/ingest/player-games/1229352720222134272?season=2025` (admin token) →
`{"weeksFetched":0,"gamesStored":0,"weeksFailed":0,"absencesStored":160,"weeksUnclassified":0,"scheduleStoreFailed":false}`.
Then `GET …/1229352720222134272/schedule`:

```
season 2025, currentWeek 21, lastLeagueWeek 21, seasonOver True,
playoff {startWeek 19, endWeek 21, reason None}, excluded {postponed 3, canceled 1}
teams 30 {82: 28, 83: 2} ['NYK', 'SAS']  STP/STR present: False
```

Side observation, not this feature: the re-run reported `absencesStored: 160` for a
finished season. That's the existing no-entry pass, which this change didn't touch.
It's worth a look separately, in case it rewrites the same rows on every admin run.

## V5. Week-1 grid and playoff window (SC-002, SC-004): run, pass

```
season 2026, currentWeek 1, lastLeagueWeek 22, seasonOver False,
playoff {20, 22, null}, excluded {0, 0}
teams 30, weeks 25, week1 {3: 24, 2: 5, 4: 1} → PHI, totals {80}
```

Timing: **35 ms cold, 15 ms warm** (curl `time_total`, local). Football league →
`available:false`, with the "basketball leagues" reason. No header → 404.

## V6. Schedule week = league week (spec assumption): run, pass

League weeks vs schedule weeks (the F6 query), NBA 2025, league 211:

```
league-vs-schedule|0|3223      (mismatched | player-weeks that scored)
stats-vs-schedule|0|29143      (separate sanity check: stats week vs schedule week)
```

The assumption is now **measured**: every one of 3,223 scoring player-weeks has a game
in the same schedule week.

## V7. Next matchup: run, pass (basketball "paired" not yet possible)

```
nba 2026: {"week":1,"available":false,"reason":"Pairings for week 1 aren't out yet. …","me":null,"opponent":null}
nfl 2026: {"week":4,"available":true,"me":{"rosterId":1,…"popsharky"…},"opponent":{"rosterId":4,"teamName":"Khatt Stafford","username":"ImReallyHarry",…}}
nba 2025: {"week":21,"available":false,"reason":"The regular season is over.",…}
no header: 404
```

- NBA 2026 answers 2026/week 1, not 2025's "season over": **F1 verified live**.
- NFL: Sleeper `/matchups/4` pairs roster 1 with roster 4 (matchup_id 2), which
  matches. The analysis endpoint (today's NFL home source) also says week 4 vs roster
  4 (ImReallyHarry), so the two week rules agree on this Monday.
- **Not run:** the basketball paired path (T041). It needs pairings, which come after
  the NBA draft on 2026-10-10 21:15 UTC.

## V8. Browser: run, pass

Through vite on 5183, signed in as popsharky via the real sign-in form (the real
`X-Sleeper-User` header path):

1. The NBA league rail shows "Schedule grid" (active on the page). The NFL league
   rail doesn't.
2. The grid has 30 rows, PHI first on "Next 1 week", and columns weeks 1–22 with "this
   week (incl. played)" on week 1 and "playoffs" on 20–22. The "Playoff weeks" view
   shows 20–22 with totals: NOP 12, then the 11s, down to CHA/MIL/MIN/NYK at 9. That
   matches an independent count from Sleeper's payload.
   - **F8:** sport_schedule rows for nba/2026 were deleted and the refresh aged 2 h.
     Opening the page showed "The 2026 NBA schedule hasn't been loaded yet…", and
     within ~10 s the grid filled (PHI row present, message gone) **without a
     reload**.
3. 375 px (mobile preset): `document.scrollWidth` 375 = viewport. `.sg-wrap` is
   341 px wide, scrolls to 2,293 px with `overflow-x: auto`, and the first column is
   `position: sticky`.
4. Themes: the app ships only dark-theme variables (frontend agent's finding), so
   there's no light theme to check. Dark rendered legibly (screenshot taken).
5. NBA league home: "Next opponent" shows the "Pairings for week 1 aren't out yet…"
   reason, and its only link is "Schedule grid" → `/leagues/1339351318115946496/schedule`
   (F9). NFL league home is unchanged: "Projected 123.9 to 131.1. A projection, not a
   result.", and it doesn't call `/next-matchup`.
6. Console: four 403s on `POST …/refresh`, from the **first** backend start, whose
   `CORS_ORIGINS` defaulted to 5173 only, while this test web ran on 5183. That's a
   test-setup issue, not the feature. After restarting with
   `CORS_ORIGINS=http://localhost:5183`, every `/api/` request was 200 (network log).

## Not run yet (dated or gated)

| Task | Why |
|---|---|
| T004 | NFL `leg`/`last_scored_leg` boundary curls, due 2026-10-06 and 10-07 |
| T041 | basketball paired check, due 2026-10-11 to 10-19 (after the NBA draft) |
| T042–T044 | football switch, gated on T004 |
| T049 | production deploy and prod V8.1–2, needs Allan's go-ahead, before 2026-10-20 |

## After code review fixes (R1–R3): run

Backend restarted on 8084 after the fixes:

```
2024 available False seasonOver True  24/24  "The 2024 NBA schedule wasn't saved for this season: it finished before the app started storing schedules."
2025 available True  seasonOver True  21/21  30 teams
2026 available True  seasonOver False 1/22   30 teams
```

R2's unknown-playoff-end path and R3's disabled buttons are covered by unit/Vitest
cases only. No NBA league here has an unknown playoff end, so there was no live case.
