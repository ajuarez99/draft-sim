# draft-sim — handoff

Last updated 2026-08-29 by a Claude session that was running low on context.
**Read this first.** `DEPLOY.md` covers deployment; `README.md` covers running it.
A Claude session should then read `claude/` — orientation, sandbox recipes and bug
post-mortems that are not worth rediscovering.

---

## Where things actually stand

Repo `ajuarez99/draft-sim`, branch `main`, tree clean. **Push `76d661d` and `e7ea1a1`.**

    76d661d  manager tendencies: stated beliefs as shrinkage prior, per-seat unpredictability
    e7ea1a1  add HANDOFF.md
    0b54d2d  deploy prep: env-var config, shared-token auth, Dockerfile, runbook
    ef17f3e  fix: backfillAdpAtTime UPDATE..FROM join, createArrayOf binding
    77f34bb  v0: ingest, board derivation, engine, Monte Carlo, SSE, React UI

**The application has never been started.** Not once. Everything below labelled
"verified" was verified by compiling or executing pieces in isolation, never by
running the Spring app.

### Verified by actually executing it

| What | How |
|---|---|
| Engine core, 35 assertions | Compiled standalone with `javac 21` against stubbed Spring annotations, then run |
| Schema + every repository query | Applied to a real PostgreSQL 16; each query re-run as `PREPARE`/`EXECUTE` so bind params behave as JDBC drives them |
| Frontend build | `npm install`, `tsc -b`, `vite build` clean under `strict` |
| SSE parser, 19 assertions | `api.ts` bundled with esbuild, run against a mock Node SSE server at chunk sizes 1, 7, 64, 100000 |
| Token auth logic, 29 assertions | Compiled and run standalone |
| Manager tendencies, 53 assertions total | Stated-as-prior blending, clamping, per-seat unpredictability |
| `manual_json` / `feature_json` isolation | Postgres 16: proved neither upsert clobbers the other |

### Never executed

The Spring container itself: context startup, bean wiring, Flyway's migration
bookkeeping, `@ConfigurationProperties` binding, Jackson record deserialization,
pgjdbc, the `ApiTokenFilter` in a real servlet container, CORS, and the Docker build.
Also the real Sleeper ingest end to end.

Highest remaining risk: `optional:file:${WEIGHTS_FILE:../config/weights.yml}` resolves
against the process working directory and **fails silently** when it doesn't resolve.
`GET /api/health` reports `weightsLoaded` and echoes the weights. Check it first.

---

## Do this next

### 1. Manager tendencies — DONE, but no UI

`manager_profile` was dead; it now carries two separately-owned columns.
`feature_json` is fitted and written only by ingest. `manual_json` is what you say
about a seat and is written only through the API. Neither upsert touches the other.

A stated reach bias is the **shrinkage target**, not an override: no history means
your number is used exactly, two drafts means one third data and two thirds you.
That is what makes fantasy(heart) — zero history, fourteen otherwise-identical
league-average bots — actually worth simulating.

You can set `reachBias`, `unpredictability` (a multiplier on run temperature for
that seat alone) and a free-text `note`. Positional tilt stays fitted-only.

**There is no UI for this.** Drive it with Postman or curl:

    GET    /api/managers
    PUT    /api/managers/{managerId}/tendencies
           {"reachBias": 8, "unpredictability": 1.6, "note": "drafts his own Bengals"}
    DELETE /api/managers/{managerId}/tendencies

`GET /api/managers` shows `stated` (what you typed) next to `effectiveReachBias`
(what the engine will use), so the blending is visible rather than mysterious.
Seat cards in the UI would be the natural next frontend job.

Provenance — `NEUTRAL` / `STATED` / `FITTED` / `BLENDED` — rides out to
`/api/drafts/{id}/seats` and the confidence panel, so a seat running on your
opinion is never displayed as though it were evidence.

**Schema note:** this was folded into `V1__init.sql` because nothing has booted yet.
Once `bootRun` succeeds once, Flyway records V1's checksum and further schema
changes must be a V2.

### 2. Get it running

    git pull
    cd backend && gradle wrapper --gradle-version 8.14   # wrapper is not committed
    ./gradlew test                                        # pure logic, no Spring
    docker compose up -d
    ./gradlew bootRun
    curl localhost:8080/api/health                        # weightsLoaded must be true

Open `backend/` as the IDE project root, not the repo root. Needs **JDK 21**.
IntelliJ IDEA Community will fetch one; there's no confirmed JDK on the Windows box.

### 3. Read the board before trusting anything

    curl -X POST localhost:8080/api/ingest/all/1391509063170293760
    curl localhost:8080/api/board?limit=40

Read those 40 names as a fantasy player. This is the highest-value hour in the project:
the engine is verified, the SQL is verified, and none of that says whether the board is
any good. If the top 12 don't look like a real first round, everything downstream is
confidently wrong in the same direction and the UI will not tell you.

Check `picksWithContemporaneousBoard` in that response — expect ~180. If it's 0, the
`maxBoardLagDays` window is catching nothing and every reach profile is empty.

### 4. Then, in order

- **Real ADP import.** Retires the biggest caveat, touches one class. CSV from
  FantasyPros or FFC → `adp_snapshot` with `source = 'ffc_ppr_14'`, matched on
  name + position + team. Log name-match misses; a gap at the top of the board matters
  enormously, one at pick 180 doesn't.
- **First real simulation.** 2000 iterations, T=1.0. Three sanity checks: availability
  must decrease monotonically across your picks (worth an assertion); round-1 modal
  probabilities should sit in the 20–60% band; and your 1.11 menu should look like
  something you'd recognise.
- **Live draft polling.** The real draft-day gap — see below.
- **Frontend `VITE_API_BASE` + auth header.** ~20 lines. Blocks any remote deploy.

---

## Context a new session needs

### League facts

    fantasy(heart)       2026  league 1391509063170293760  draft 1391509064357273600  14 teams, pre_draft, NO history
    West Coast FF        2026  league 1389361939561332736  draft 1389361939561332737  14 teams, 2025 predecessor
    (Foot) Ball Knowers  2026  league 1346366555759341568  draft 1346366555776126976  12 teams, complete
    (Foot) Ball Knowers  2025  league 1254190892974084096  draft 1254190894563729408  12 teams, complete

All PPR, snake, 15 rounds. Starters QB/RB/RB/WR/WR/TE/FLEX/FLEX/K/DEF + 5 bench.
Sleeper user `popsharky` = `1122386008709910528`. Allan is **slot 11 of 14** in
fantasy(heart), which drafted 2026-08-31 21:15 CDT.

### Corrections to `draft-simulator-plan.md`

The plan doc is the design of record but three things in it are now wrong:

1. **Football has two seasons of history, not one.** Ball Knowers drafted 2025 *and*
   2026, both complete. The no-backtest decision stands as instructed — but its stated
   premise ("one season, can't validate") is factually off by one.
2. **`reachBias` sign is inverted in the plan.** The plan writes reach as
   `pick_no - adp` and calls positive a reach. The code uses
   `boardPosition - pickNumber`, so positive means reaches.
3. **Ball Knowers 2026 already drafted**, so the league the plan was written around is
   not the one the tool can help with.

### The bug worth remembering

`valueDelta` was originally `(boardPosition - pickNo)` — inverted, making the engine
prefer the **worst** available player. Every structural test passed anyway: 210 picks
made, no duplicates, kickers gated. None asserted that good players go first. Caught by
running a modal draft and seeing the 1.01 pick come back as the last man on the board.
`PickScorerTest.aPlayerWhoFellPastHisBoardSlotIsValueAndReachingIsNot` and
`DraftSimulatorTest.theModalBoardStartsWithTheBestPlayerAndStaysNearTheTop` pin it.
**Keep them.**

### Known warts

| What | Weight |
|---|---|
| No UI for setting tendencies — API only | open |
| `ProfileService.fit()` runs per simulate call. Deliberate: fitting ~360 picks is cheap and a cache would only add staleness | low |
| `available.remove(choice)` is a linear scan — fine at 2k iterations, possibly minutes at 10k | medium |
| Positional priors fit on ~360 picks with `alpha = 8`; smoothing dominates | by design |
| 2025 picks excluded from reach fitting (no contemporaneous board) | by design |
| No backtesting; probabilities are internally consistent, not calibrated | by design |

### Deferred by design

`ideas/` holds speculative directions that are explicitly not planned work — see
`ideas/README.md` for the convention. Nothing there is scheduled or half-built.

`ideas/player-affinity.md` — whether manager tendencies and players belong in a vector store.
Summary: player-to-player similarity built from *features* is worth building and slots
into the scorer as the `w_aff` term the plan already reserves. Embedding notes and
players into a *shared* text space is the appealing wrong answer and the doc says why.
Notes want a one-off LLM extraction into structured attributes, not vector search.
Anything at corpus scale is blocked on an unverified assumption: that public Sleeper
drafts can be enumerated at volume. Check that with a small script first.
Use `pgvector` in the existing Postgres if it ever happens — not a second datastore.

### The honest headline

**The board is the weakest link.** There is no true 14-team PPR ADP feed. It is
Sleeper's `search_rank` (popularity, not size- or scoring-aware) blended at weight 0.5
with observed pick order from completed drafts, rescaled by team count. That weight is
a coin flip wearing a parameter's clothes. Everything downstream reads `BoardEntry`, so
a real ADP source replaces it in one class.

### Draft-day gap

Resume-from-state works, but only against picks already in the database. There is no
polling, so mid-draft you'd re-run ingest by hand. A scheduled job on
`/draft/{id}/picks` every ~10s while status is `drafting` is small work and the largest
remaining payoff. `startState` also exists on the API and is unexposed in the UI.

---

## Environment notes for a future Claude session

Moved to `claude/environment.md` and expanded — what is reachable from the sandbox,
how to execute engine code without Maven, how to stand up Postgres to test SQL, the
`device_stage_files` depth limit, and the git lock-file problem.

`claude/lessons.md` has the bug post-mortems. Read it before trusting a green test
run: the `valueDelta` sign inversion passed every structural test in the suite.

### Working style that fits this project

Allan is a backend engineer — skip framework tutorials. He pushed back usefully when
told something couldn't be verified, and that push found a real bug. Prefer testing a
claim over asserting it, and say plainly which is which.
