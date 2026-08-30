# draft-sim — handoff

Last updated 2026-08-30 by a Claude session running directly on Allan's Windows
machine. **Read this first.** `DEPLOY.md` covers deployment; `README.md` covers
running it. A Claude session should then read `claude/` — orientation, sandbox
recipes and bug post-mortems that are not worth rediscovering.
`claude/environment.md`'s recipes are written for a different (cloud-sandbox)
environment; this machine has real internet, real JDK/Node/Postgres installs,
and does not need most of them — see the new note at the top of that file
before assuming something is blocked.

**Time-sensitive:** fantasy(heart)'s real draft — 14 teams, league
`1391509063170293760`, draft `1391509064357273600` — is scheduled 2026-08-31
21:15 CDT. See "Draft-day gap" below; a checklist only Allan can run before
then is in `claude/live-poller-plan.md`'s "Manual pre-draft-night checklist."

---

## Where things actually stand

Repo `ajuarez99/draft-sim`, branch `main`. Working tree has uncommitted work from
this session (below) — **not yet committed, ask before committing.** Multiple
Claude Code sessions have been running concurrently against this same working
directory (one on this live-poller work, others on separate frontend work per
`git status`) — if you're picking this up, diff carefully before assuming the
working tree matches what this doc describes.

    f8df93d  Designs in and future work that needs to be done
    795975b  frontend: pick-by-pick board reveal, manager tendencies UI
    c4bf479  Add in simulation tests and booting up app locally first time
    4b5da75  claude/environment: record the UI screenshot and slf4j-stub recipes
    3d65302  fix: coherent predicted board, stale frontend types, memo deps
    39f4a72  frontend: seat cards branch on provenance, not pick count
    e58094a  handoff: point at claude/ instead of duplicating environment notes
    3297ee2  add claude/ notes: orientation, sandbox recipes, bug post-mortems
    76d661d  manager tendencies: stated beliefs as shrinkage prior, per-seat unpredictability
    e7ea1a1  add HANDOFF.md
    0b54d2d  deploy prep: env-var config, shared-token auth, Dockerfile, runbook
    ef17f3e  fix: backfillAdpAtTime UPDATE..FROM join, createArrayOf binding
    77f34bb  v0: ingest, board derivation, engine, Monte Carlo, SSE, React UI

### This session: live draft poller built (D, Phase 0 of `claude/next-features-roadmap.md`)

`claude/next-features-roadmap.md` (written 2026-08-29, reconciling four parallel
feature plans) prioritized D — live Sleeper draft polling — first, ahead of the
other three features, specifically because of tomorrow's deadline. Built this
session via a plan → build → code-review pipeline (three separate agent passes);
full design and reasoning in `claude/live-poller-plan.md`. Summary:

- `LiveDraftPoller` (new) — one virtual thread per tracked draft, polls Sleeper's
  `/draft/{id}` + `/draft/{id}/picks` every 10s while `status` is `drafting`,
  writes into the existing `draft`/`draft_pick` tables via a new
  `DraftRepository.upsertPicks()` (idempotent `ON CONFLICT` upsert, replacing the
  need for the destructive `replacePicks()` on the polling path).
- `POST /api/drafts/{sleeperDraftId}/track` (new, on `LeagueController`) starts
  tracking; safe to call any time before the draft goes live (no-ops on
  `pre_draft`), idempotent if called twice.
- `PickMapper` (new) — the raw-Sleeper-pick-to-`PickRow` mapping logic extracted
  out of `LeagueIngestService.ingestDraft` so both the batch ingest path and the
  poller share one implementation instead of two.
- Tests: 14 new (`PickMapperTest`, `LiveDraftPollerTest` with mocked
  `SleeperClient`/repositories, `DraftRepositoryUpsertPicksIT` and
  `DifferentialReplayIT` against a real Postgres + real Sleeper API, gated to
  skip cleanly with no Postgres reachable). Full suite: **74/74 passing**,
  verified independently by both the build pass and a separate code-review pass
  (which also killed Postgres mid-run to confirm the integration tests actually
  skip rather than silently pass).
- **What was NOT independently re-verified**: a fourth "test and verify" agent
  pass (booting the real server, hitting `/track` live, checking logs, clean
  shutdown) was planned but not run this session — Allan reviewed the plan +
  build + code-review results and judged them sufficient, and moved on to
  starting the next roadmap phase in a separate session. The one thing genuinely
  unverified by any of this — per `claude/live-poller-plan.md`'s own "Open
  risks" — is real `drafting`-status behavior; nothing in this codebase has ever
  observed a truly live Sleeper draft. Budget the disposable-mock-draft dry run
  in the manual checklist before trusting this against fantasy(heart) tomorrow.
- **Still uncommitted.** Ask before committing — this doc may be read while that
  decision is still pending.

**The application has been started, for the first time, this session — end to end,
against real Sleeper data, through the real UI.** Everything below labelled
"verified" was verified by actually running it, not by inspection.

### Verified by actually executing it, this session

| What | How |
|---|---|
| Spring context, bean wiring, Flyway migration, config binding, Jackson, pgjdbc | `./gradlew bootRun` against a real local PostgreSQL. Boots clean, `GET /api/health` returns `weightsLoaded: true` |
| Full backend test suite, 60 assertions | `./gradlew test`, real JUnit under real JDK 21 (Gradle toolchain auto-provisioned it — see "Build tooling" below) |
| FFC ADP source, live | Real ingest against fantasy(heart)'s board: 271/271 players matched, board rebuilt, re-simulated. See "Real ADP import" below |
| Real Sleeper ingest, all 4 leagues in "League facts" below | `POST /api/ingest/all/{leagueId}` — found and fixed a real bug, see "Bugs fixed this session" |
| The board, by eye | `GET /api/board?limit=40` after ingest — top of the board is Bijan/Gibbs/Allen/Chase-tier, reads like a real 2026 first round |
| `picksWithContemporaneousBoard` | 180, matching the ~180 HANDOFF predicted |
| A real 2000-iteration simulation, T=1.0, fantasy(heart) slot 11 | `POST /api/sims` — see "First real simulation" below for both the pass and the open question it surfaced |
| Availability monotonicity across a manager's own picks | Checked over the real 2000-iter run (0 violations / 1292 pairs) **and** now pinned by a new `MonteCarloRunnerTest` — this was flagged in HANDOFF as untested; it no longer is |
| Frontend build | `npm install`, `tsc -b`, `vite build` clean under `strict` |
| Frontend against the real backend, in a real browser | Not the mock-server workaround — `vite dev` proxied to the live `bootRun` instance, real ingested data. Seat cards, predicted board, availability panel, SSE progress streaming all confirmed correct. Zero console errors |
| SSE parser, 19 assertions | `api.ts` bundled with esbuild, run against a mock Node SSE server at chunk sizes 1, 7, 64, 100000 |
| Schema + every repository query | Applied to a real Postgres; each query re-run as `PREPARE`/`EXECUTE` so bind params behave as JDBC drives them |
| Manager tendencies, 53 assertions total | Stated-as-prior blending, clamping, per-seat unpredictability |

### Bugs fixed this session

1. **`DraftRepository.upsert` timestamp binding.** Bound a `java.sql.Timestamp`
   against `Types.TIMESTAMP_WITH_TIMEZONE` for the `timestamptz` `start_time`
   column. pgjdbc rejects this outright: *"Cannot cast an instance of
   java.sql.Timestamp to type Types.TIMESTAMP_WITH_TIMEZONE."* First real ingest
   call, 500 every time. Fixed by binding `OffsetDateTime.ofInstant(startTime,
   ZoneOffset.UTC)` instead — same class of bug as the `text[]` binding fixed in
   `ef17f3e`, caught the same way: by actually calling it.
2. **`FfcClient` — FFC serves valid JSON as `Content-Type: text/html`.**
   Confirmed with plain `curl -D -`, so it's genuinely the server, not Spring.
   `RestClient.body(Map.class)` refuses to parse JSON out of a body declared
   `text/html`. Fixed by fetching as `String` and parsing with Jackson
   directly. See `claude/lessons.md` #11.
3. **`LeagueController.board()` / `SimulationController` / `ErrorHandler` —
   `String.valueOf(null)` is `"null"`, not null.** `Map.of()` rejects a null
   value outright, and these all used `String.valueOf(...)` to route around
   that for a genuinely-nullable field (a player's team; an exception's
   message). It doesn't throw, but the literal four-character string `"null"`
   is valid JSON and indistinguishable from real data to a consumer that isn't
   specifically checking. Found via a real retired player (Todd Gurley, still
   sitting in Sleeper's static dump) surfacing a `"team":"null"` in the live
   board response. Fixed: board uses a mutable map so the field can be a real
   `null`; the error paths fall back to the exception's class name instead of
   a null message, which is more useful than either. See `claude/lessons.md`
   #12.
4. **`DraftSimulatorTest.higherTemperatureProducesMoreVariedBoards` was flaky by
   construction**, not by chance. It counted distinct boards out of 25 trials,
   which saturates at 25 the moment every trial differs from every other — and at
   this pool size that happens well before T=0.2. Both T=3.0 and T=0.2 hit the
   ceiling (25/25), so the assertion compared 25 to 25 and failed. Rewritten to
   measure average Hamming distance from the T=0 modal board instead, which keeps
   discriminating past the point a distinct-count ceiling stops being able to.
   Same lesson as `claude/lessons.md` #7: a statistic can be well-formed and still
   be the wrong thing to assert on.

### Build tooling note

No JDK 21 was preinstalled on this machine (only JDK 24) and the Gradle wrapper
was never committed. Fixed both: added the `foojay-resolver-convention` plugin to
`settings.gradle.kts` (lets Gradle auto-provision JDK 21 via its toolchain, no
manual JDK install needed) and generated + this-session-only-staged the wrapper
files (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) — **not yet committed**, they
should be, since HANDOFF's own "get it running" instructions assume they exist.

Local Postgres: this machine has a Postgres 14 service already running on 5432
for other things. Rather than touch it or need its credentials, this session
initialized a **throwaway** Postgres 14 cluster in a temp dir, listening on 5433
with trust auth for user/db `draftsim` — which happens to match
`application.yml`'s local-dev defaults exactly. That data directory will not
survive a reboot; `docker compose up -d` (once Docker is available on this
machine — it currently is not) or a real local install is still the durable
answer. See `claude/environment.md` for the exact commands used.

### First real simulation — the pass, and the open question it surfaced

Ran 2000 iterations, T=1.0, fantasy(heart), slot 11 (Allan). Two of HANDOFF's
three predicted sanity checks held: availability decreases monotonically (0
violations), and the 1.11 menu is recognizable (Gibbs/Bijan/Chase/CMC-tier).

**The third did not.** HANDOFF predicted round-1 modal probabilities in the
20–60% band. The actual run: 3.5%–8.8%, every round-1 pick, no exceptions — pick
1.01 goes to the modal player (Jahmyr Gibbs) only 8.8% of the time. This is not
noise; it is what the math in `PickScorer` produces at the current
`weights.yml` values: `adpScale: 12.0` means the ADP gap between the #1 and #4
overall players (a 3-pick spread) is worth `0.25` in score, and at `temperature:
1.0` a softmax over a candidate pool that flat is close to uniform among the top
handful. **The 20–60% figure was a guess, never run, and it was wrong** — per
this project's own convention, that gets corrected here rather than left to look
authoritative. Whether the *model* is wrong (T=1.0 is too flat for round 1 of a
real draft, where consensus is usually much sharper) or the *guess* was just off
is a real open question, not yet resolved, and it matters before Allan trusts the
round-1 output on draft day. Options, undecided: lower default temperature,
increase `adpScale`'s bite at the very top of the board specifically, or accept
that this league's real behavior is this flat and the guess was simply wrong.
**Worth 20 minutes before 2026-08-31.**

**Follow-up after building the FFC ADP source (below) and re-running the same
sim on the improved board: the numbers didn't move** (still 3.5%–9.4%,
monotonicity still 0/1368 violations). That isolates the flatness to
`weights.yml`'s scoring math, not board quality — useful, and still open.

### Never executed

CORS from a real browser origin other than the dev proxy, the Docker build, and
the `ApiTokenFilter` with a token actually set (ran this session with auth off,
the local-dev default).

---

## Do this next

**Schema is now live: `V1__init.sql` is applied and Flyway has recorded its
checksum (verified this session, real Postgres).** Any further schema change —
including the `adp_snapshot` columns in `claude/adp-sources.md` and the
`league`/`manager_profile` changes in `claude/borrowed-drafts.md` — is a **V2**
migration from here on. Do not edit V1 again.

### 1. Manager tendencies — DONE, both display and editing, verified live

Seat cards correctly **display** provenance (`3d65302`, `39f4a72`, verified live
in a browser). **Setting** `reachBias` / `unpredictability` / `note` now has a UI
too — added in a later pass this same session via an architect → coder →
reviewer → verification pipeline, `claude/live-reveal-and-tendencies-ui.md` has
the full design. Each seat card is now editable in place. That pass also found
and fixed a real, previously-invisible bug: `WebConfig`'s CORS config never
allowed `PUT`/`DELETE`, so save/clear silently 403'd from any browser the entire
time this feature existed — worked fine over curl (no preflight), never worked
from the UI it was built for. See `claude/lessons.md` #14.

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

Provenance — `NEUTRAL` / `STATED` / `FITTED` / `BLENDED` — rides out to
`/api/drafts/{id}/seats` and the confidence panel, so a seat running on your
opinion is never displayed as though it were evidence. **Verified this session,
live in a browser**, against real fantasy(heart) seats: `njerickson` and
`popsharky` (Allan) show `FROM HISTORY` with real fitted reach/position text;
the other 12 correctly show the neutral "drafts like the room" copy.

### 2. Get it running — DONE this session, here's what it actually took

The steps below are what HANDOFF said to do; here is what was different in
practice, since the next session (or Allan, on a different machine) will hit
the same gaps:

    git pull
    cd backend
    ./gradlew test          # wrapper is now committed — see "Build tooling" above
    ./gradlew bootRun       # needs a Postgres reachable per application.yml's
                             # defaults (localhost:5433, db/user/pass "draftsim");
                             # docker compose up -d if Docker is available, otherwise
                             # see "Build tooling" above for the throwaway-cluster recipe
    curl localhost:8080/api/health          # weightsLoaded: true — confirmed

No JDK 21 needs to be manually installed — the toolchain auto-provisions it now.
Open `backend/` as the IDE project root, not the repo root, still applies.

### 3. Read the board before trusting anything — DONE, it looks real

    curl -X POST localhost:8080/api/ingest/all/1391509063170293760
    curl localhost:8080/api/board?limit=40

**Done this session.** Top of the board: Bijan Robinson, Jahmyr Gibbs, Josh
Allen, Jonathan Taylor, Ja'Marr Chase, James Cook, Puka Nacua, CMC, Jaxon
Smith-Njigba, Drake Maye... — reads like a real 2026 14-team PPR first round.
`picksWithContemporaneousBoard` came back 180, matching the ~180 predicted.
**This does not mean the board is good enough** — it is still `search_rank`
blended with observed order at a coin-flip weight, per the honest-headline
section below and `claude/adp-sources.md`. It means the pipeline that produces
it is not broken.

### 4. Then, in order

- **Real ADP import — DONE (§3 + §5 + §9), this session.** `claude/adp-sources.md`
  has the full writeup, including a real finding: FFC's `teams` parameter is
  scoring-aware but not yet team-count-aware this early in the preseason (verified
  live — see that doc), so the full 8-14-team matrix from §4 was deliberately not
  built; one cell (14-team PPR) was, and it self-corrects once FFC's data actually
  differentiates. 271/271 players matched, zero misses. QB1 moved from pick 3
  (search_rank alone) to pick 28 blended with FFC — the kind of move that confirms
  the new source is actually doing something. FantasyPros CSV, ESPN, and Yahoo
  are still not built — lower priority now that FFC is real market data rather
  than a popularity proxy.
- **First real simulation — DONE, see above.** Two of three sanity checks passed;
  the round-1 modal-probability one didn't and is now an open question, not a
  guess — see "First real simulation" above. Worth resolving before the ADP
  import, since a real ADP source will change these numbers again and you want
  to know whether today's flatness is temperature or the board.
- **Borrowed drafts + variable league size — planned in detail, nothing built.**
  `claude/borrowed-drafts.md`. Lower urgency than the ADP import for
  2026-08-31 specifically (fantasy(heart) already has enough shared-league
  history that this mainly helps the *next* league you point the tool at), but
  its normalization pass (round index → `pick_pct`) is flagged in that doc as a
  fix to *existing* behavior worth doing on its own, independent of borrowed
  data ever landing.
- **Live draft polling — built this session**, see "This session" above and
  "Draft-day gap" below. Not yet committed; not yet dry-run against a real
  `drafting`-status draft.
- **Frontend `VITE_API_BASE` + auth header.** ~20 lines. Blocks any remote deploy.
  Not needed for draft night if running locally against `localhost`.

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
| ~~No UI for setting tendencies~~ — DONE, seat cards are editable in place, verified live in a browser (see below) | closed |
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

**The board is stronger than it was, and still not calibrated.** As of this
session it's a three-way blend — Sleeper's `search_rank` (popularity, not size-
or scoring-aware), observed pick order from completed drafts, and real FFC
market ADP (scoring-aware, not yet team-count-aware — see
`claude/adp-sources.md`) — at weights that are still coin flips wearing
parameters' clothes, just better-informed ones than a single popularity number.
The round-1 modal-probability finding above says the remaining gap between
"the board is real" and "the numbers are trustworthy" is now more likely in
`weights.yml`'s scoring math than in the board itself.

### Draft-day gap

**Built this session — see "This session: live draft poller built" above.**
`LiveDraftPoller` now does the ~10s polling job on `/draft/{id}/picks` while
status is `drafting` that this section used to describe as missing. Resume-
from-state still works the same way it always did (against whatever's in the
DB), it's just now kept current automatically once `/track` is called, instead
of needing ingest re-run by hand mid-draft.

**What's still actually open:**
- Uncommitted — needs a commit decision.
- No dry run has happened against a real `pre_draft → drafting → complete`
  transition. The differential-replay test only proves the pick-mapping logic
  is stable against already-`complete` drafts; it says nothing about whether
  Sleeper's `status` field or pick-object shape behaves as assumed mid-draft.
  See `claude/live-poller-plan.md`'s "Manual pre-draft-night checklist" — the
  disposable-mock-draft dry run is the only way to close this before
  2026-08-31 21:15 CDT.
- `startState` still exists on the API and is still unexposed in the UI —
  unrelated to the poller, still an open gap if you want a manual override for
  a pick the poller hasn't caught up to yet.
- In-memory-only tracking state: an app restart mid-draft silently drops
  polling with no alert. Keep the process alive through the real draft.
- No `/untrack` endpoint — if `/track` is ever called against the wrong draft
  id, the only recovery in this version is an app restart.

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
