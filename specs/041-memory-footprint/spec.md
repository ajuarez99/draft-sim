# Feature Specification: Memory Footprint — Measure, Bound and Shrink What the Backend Holds

**Feature Branch**: `041-memory-footprint` (worktree `.claude/worktrees/041-memory-footprint`, from `origin/main` at `c987b92`)
**Created**: 2026-10-20
**Status**: Draft
**Input**: User description: "lets look into our systems and caching and see where we can save memory"

## Context: what holds memory today (read from the code, 2026-10-20)

This section exists so the plan starts from the code and not from a guess. **Every size below is an
estimate from reading the code. None has been measured.** Measuring them is User Story 1.

| # | Holder | Where | Bound today | Estimated size (unmeasured) |
|---|--------|-------|-------------|-----------------------------|
| H1 | JVM heap ceiling | `Dockerfile:26` `-XX:MaxRAMPercentage=75` | 75% of whatever memory limit the Railway container reports. Not a fixed number | Unknown. The heap can grow toward that ceiling under load and may stay committed afterwards |
| H2 | Monte Carlo iteration results | `engine/MonteCarloRunner.java:63,83` keeps every `RunResult` until `aggregate` runs | `MAX_ITERATIONS = 5000` per run (`SimulationRequest.java:42`) × concurrent permits (`SimulationPermits`) | Each run holds `picked` (~1.5 KB), the availability snapshots (~15 picks × 75 ids) and a 12-seat `rosters` map. That is roughly 10–30 KB, so **~50–150 MB for one 5,000-iteration run**. `RunResult.rosters` has no reader outside `DraftSimulator` (grep, 2026-10-20) |
| H3 | Season box-score cache | `engine/SeasonBoxCache.java:79` | No entry limit. Every (sport, season) read during the process's life stays | Spec 022 research R6 estimated ~5 MB per season. Never measured (`specs/022-.../verification.md` has no heap number) |
| H4 | Value memo in sport rules | `sport/FootballRules.java:54`, `sport/BasketballRules.java:108` | No limit. One entry per distinct ADP `double` ever scored | Small per entry, and it only grows |
| H5 | Player dump during ingest | `ingest/PlayerIngestService.java:82` parses Sleeper's ~5 MB dump into a map of maps | Transient, one ingest at a time | `DEPLOY.md:312` says "512MB is comfortable, 256MB may OOM", so the **peak** is well above the file size. Not retained afterwards (the `players-cache-ttl` key in `application.yml` has no reader) |
| H6 | Projection-accuracy memo | `engine/ProjectionAccuracyService.java:120` | LRU, 64 leagues | Bounded. Listed for completeness |
| H7 | Per-draft poller and lock maps | `ingest/LiveDraftPoller.java:107,122`, `ingest/espn/EspnLeagueIngestService.java:109` | Never pruned, by design (documented), bounded by drafts or leagues per process | Tiny |
| H8 | Frontend static server | `web/Dockerfile`: a Node `serve` process serving `dist/` | One process | Unknown. It is a whole Node runtime that serves static files |

**Rows added after plan review (2026-10-20, F4/F10). Measured locally on Windows, labelled:**

| # | Holder | Where | Bound | Size |
|---|--------|-------|-------|------|
| H9 | Allocation churn per simulation | `DraftSimulator` / `PickScorer` | Per run | **1.1 MB allocated per iteration** (541–568 MB per 500-iteration resim) against 8.5 MB retained. Drives draft-night *peaks* through GC timing. Engine work, out of scope |
| H10 | JVM non-heap | GC structures 39 MB, metaspace 52, symbols 21, code cache 19, CDS 13, class 9 | Fixed by the app's size | **~236 MB committed at rest, of which heap is 75 MB.** Most of the resting footprint. Glibc arenas aren't counted by NMT |
| — | Missed small holders | `DraftAndAdpJoin.memo` (`:93`), `SeasonBoxCache` side maps (`:80-83`), `DraftSimulator.WARNED_DUPLICATES` | Unbounded but tiny | Estimated: KBs |

### Amended after measurement (2026-10-20, planning, research R0)

Production was read with the read-only `railway metrics` command after the table above was written:
- **H8 measured: 20 MB** (frontend service, 7-day). That's below the FR-004 threshold, so **US4 is
  resolved as "leave alone"** and no frontend work is planned. The table's "unknown" stands as the
  original guess.
- **H1 is the likely main lever.** The backend's limit is 2 GB / 2 vCPU (max heap ≈ 1.5 GB). It sits
  at ~300–370 MB after a deploy, **peaked at 827 MB on draft night** (10-10, 1-minute data), and
  between deploys ratchets to 675–983 MB and stays there. That the ratchet is retained JVM heap is
  an inference, which US1 tests.
- A 4-hour bucket reading 2.24 GB on 10-09 is two containers overlapping during a deploy
  (1-minute data: 1149/1344 MB at the 15:10/15:51 deploys), not load.

### Amended after plan review (2026-10-20, `plan-review.md` F1/F4)

The "ratchets to 675–983 MB and stays there" bullet above is **wrong**. It was a 4-hour-bucket
artefact of deploy overlaps. At minute resolution, production rests at **~300–420 MB** between
deploys. The live heap is ~30 MB (measured locally); most of the at-rest number is non-heap. The
live room resimulates at 500 iterations (8.5 MB retained per run, measured), so H2's 5,000-iteration
case is rare. Draft night's peak came from allocation rate, not retained results.

### Rescoped 2026-10-20 (Allan's decision after the plan review)

**This spec is now "JVM settings only".** In scope: US1 (measure, rebuilt per review F2/F6/F7/F12)
and the part of US2 reachable through runtime settings: how much heap the JVM may take, and how
quickly it gives memory back after a spike. Also a written record of the real numbers.
**Out of scope**, recorded rather than built:
- **US3 (cache limits).** Every holder is small (H3 ≈ seasons × a few MB, H4 off the hot path,
  review F9). The holder table stays as documentation.
- **FR-005 (streaming Monte Carlo fold).** The live room retains 8.5 MB per run (review F4). This
  is a candidate follow-up, together with H9 (1.1 MB allocated per iteration, measured).
- **US4 (frontend).** Measured at 20 MB.
Confirmed by Allan: no `JAVA_OPTS` variable is set on the Railway backend service (review F8).

## User Scenarios & Testing *(mandatory)*

The "user" here is mostly the operator (Allan), who pays for production memory and who needs the
backend to survive draft night. The league members are the second audience: for them, nothing they
see may change.

### User Story 1 - Know what the backend uses and what holds it (Priority: P1)

The operator can read, in one place, how much memory the production backend uses at rest, at its
peak during a draft-night load, and during a player ingest, and which holders (H1–H8) account for it.
Each number is labelled measured or estimated.

**Why this priority**: Without a baseline, any "saving" can't be told apart from noise, and this repo's
rule is that a guess is never reported as a measurement. It also decides which of H1–H8 are worth
changing at all.

**Independent Test**: Run the measurement procedure against a locally running backend with production-shaped
data, and read production's own memory graph for the same period. The output is a baseline table
that gives every holder a number or an explicit "not measurable, because …".

**Acceptance Scenarios**:

1. **Given** a freshly started backend, **When** the operator runs the baseline procedure, **Then** they
   get its resident memory and heap use at rest, after warming every page once, at the peak of a
   simulated draft-night load, and at the peak of a player ingest.
2. **Given** the baseline, **When** a holder from H1–H8 is listed, **Then** its retained size is
   shown as measured, or as estimated with the reason it could not be measured.
3. **Given** a holder measured below the "worth changing" threshold (FR-004), **When** the baseline
   is written, **Then** that holder is recorded as "leave alone", and no change is built for it.

---

### User Story 2 - The backend uses less memory, and every answer stays the same (Priority: P1)

The production backend needs less memory at rest and at peak, so it costs less to run and has more
headroom on draft night. Every simulation, board, page and API response is identical to the one it
replaces.

**Why this priority**: This is the point of the work. It shares P1 with the baseline because the
baseline alone saves nothing.

**Independent Test**: Run the same fixed-seed simulations and the same page requests before and after,
compare the outputs byte for byte, and compare the before/after memory numbers taken with the
US1 procedure.

**Acceptance Scenarios**:

1. **Given** a 5,000-iteration simulation with a fixed seed, **When** it runs before and after the
   change, **Then** the two results are identical, and the memory the run adds at its peak is lower
   by the amount SC-002 states.
2. **Given** the draft-night load (SC-003), **When** it runs after the change, **Then** the peak
   memory is lower than the baseline's, and no request fails or is turned away that the baseline accepted.
3. **Given** a backend at rest after warm-up, **When** its memory is read after the change, **Then**
   it is lower than the baseline's at rest.
4. **Given** any page or endpoint, **When** it is requested before and after, **Then** the response
   bodies match. Live and time-dependent fields are excluded by a named list in the verification doc.

---

*Amended 2026-10-20 (rescope + analyze I2):* scenario 1 is deferred with SC-002/FR-005. Scenario 3
("lower at rest") is superseded by SC-004′ (recovery after a spike) and SC-006′, because non-heap
dominates at rest and JVM heap settings aren't expected to move it much. Scenarios 2 and 4 stand.

### User Story 3 - Every in-memory cache has a stated limit (Priority: P2) — *deferred 2026-10-20 (Rescoped)*

Each in-process cache or memo states its own limit: entries, size or lifetime. A long-running process
can no longer grow without bound because it was asked about more seasons, more leagues or more
distinct values over time.

**Why this priority**: H3 and H4 are slow leaks, not spikes. They matter for an instance that runs
for weeks between deploys. They don't drive draft-night peaks, so they come second.

**Independent Test**: Request more distinct seasons (or values) than a cache's limit and check that
its entry count stays at the limit and that an evicted entry reloads correctly on the next request.

**Acceptance Scenarios**:

1. **Given** the season cache at its limit, **When** a new season is requested, **Then** an older
   entry is dropped, the new one is served correctly, and a later request for the dropped season reloads it with
   identical results.
2. **Given** a season being refreshed (the existing "refreshing" mark), **When** eviction would drop
   it, **Then** the refresh still behaves as it does today (no reload storm, no stale entry).
3. **Given** the code after this feature, **When** someone reads a cache's declaration, **Then** its
   limit and the reason for it are stated there, and any number that was picked rather than measured is labelled a guess.

---

### User Story 4 - The frontend service uses less memory, if it's worth it (Priority: P3)

Look at whether the static frontend service (H8) can serve the same files, with the same headers and
routes, on less memory. Change it only if the measured saving clears the FR-004 threshold.

**Why this priority**: It's a separate Railway service with its own bill. But it's probably small,
and its security headers (`serve.json`) and SPA fallback must not regress. That makes it the most
optional piece.

**Independent Test**: Compare the before/after service memory and the response headers for `/`, a
deep client route and a static asset.

**Acceptance Scenarios**:

1. **Given** the frontend service, **When** its memory is measured, **Then** the result is recorded,
   with a decision: change it, or leave it alone.
2. **Given** a change is made, **When** `/`, a deep route and an asset are requested, **Then** status
   codes, SPA fallback and every security header match what is served today.

### Edge Cases

- **Draft night**: 12 members open the live room and resimulate at once while the poller ticks. A lower
  memory ceiling must not turn this into out-of-memory errors or long GC pauses that the members notice.
- **Player ingest during a live draft**: the ingest's transient peak (H5) lands on top of the
  draft-night load.
- **Cold start**: after a deploy every cache is empty. A tighter limit must not make the first page
  loads slower than they are today.
- **Cancelled or superseded simulation**: memory held by a run that is cancelled part-way is released.
  It does not wait for the run to finish.
- **Evicted while in use**: a request that holds an entry the cache then evicts keeps a valid,
  unchanged view of it (entries are immutable and shared today, and must stay so).
- **Container limit changes**: the memory ceiling must stay safe if the Railway plan's memory limit is
  raised or lowered. A setting that is only safe at one limit must say so.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The feature MUST produce a written baseline of production-shaped memory use (at rest,
  warmed, draft-night peak, ingest peak) and a per-holder breakdown for H1–H8. Each figure is labelled
  measured or estimated.
- **FR-002**: The baseline procedure MUST be repeatable. A documented set of steps produces the same table
  again, so the after-numbers are taken the same way as the before-numbers.
- **FR-003**: Every change MUST keep outputs identical. Fixed-seed simulations, boards, availability curves and
  page responses are the same before and after. Fields that vary on their own (timestamps, live data) are excluded by an explicit list.
- **FR-004**: A holder MUST NOT be changed unless its measured share clears a stated threshold:
  default **5% of warmed resident memory, or 25 MB, whichever is smaller**. That threshold is a guess
  and is labelled one. A holder below it is recorded as "leave alone".
- **FR-005**: A simulation MUST NOT keep per-iteration data that its result does not need. Peak memory
  for one run grows with what the result reports, not with iterations × everything a run produced.
- **FR-006**: Every in-process cache or memo MUST have a stated limit (entries, size or lifetime), with
  the reason given at its declaration. Entries a reader has already received stay valid after eviction.
- **FR-007**: The backend's memory ceiling MUST be a stated, reasoned setting. It is checked against
  the measured draft-night peak with headroom, not left as an implicit share of the container limit.
- **FR-008**: The feature MUST NOT reduce what the backend accepts today: the same simulation sizes,
  concurrent simulations, live-room subscribers and ingest operations.
- **FR-009**: Anything a reviewer or the verification pass finds wrong in this spec's estimates (the
  Context table) MUST be corrected in place with an "amended after measurement" note. Earlier numbers are not rewritten silently.
- **FR-010**: If the frontend service is changed (US4), its routes, SPA fallback and security headers
  MUST match today's, header for header.

**Requirements after the rescope (2026-10-20):**
- FR-001, FR-002, FR-003, FR-007, FR-008 and FR-009 stand.
- ~~FR-005~~ and ~~FR-006~~ are deferred (out of scope; see the Rescoped note). ~~FR-010~~ is moot
  (no frontend change).
- FR-004 now governs only which optional JVM settings ship. A setting ships only if its measured
  saving is at least the threshold, and every required setting is named in the plan.
- **FR-011 (new, review F3)**: the heap ceiling MUST hold the worst case the backend accepts: two
  concurrent 5,000-iteration simulations plus the daily player ingest plus warm caches, with no
  out-of-memory error. Ceiling + measured non-heap MUST stay below the container limit.
- **FR-012 (new, review F8)**: the required JVM settings MUST survive a future `JAVA_OPTS` variable
  (it adds to them, not replaces them), and the backend MUST log its effective heap ceiling and
  collector at startup.

### Key Entities

- **Memory holder**: something that keeps memory: a cache, a memo, a per-request buffer or the
  runtime ceiling. Attributes: where it lives, its limit, its measured or estimated size, and the decision (change or leave alone).
- **Baseline**: a dated table of memory readings taken by the documented procedure, each labelled
  measured or estimated, against a named data set.
- **Load profile**: the named scenarios measured: at rest, warmed, draft-night peak, ingest peak.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A baseline table exists. Every holder H1–H8 has a measured figure or a stated reason it has none, and the
  procedure reproduces the at-rest and draft-night numbers within ±10% on a second run.
- **SC-002**: The extra memory one 5,000-iteration simulation holds at its peak falls by **at least 50%**
  against the baseline, with identical results for the same seed.
- **SC-003**: During a 12-member draft-night load (12 concurrent resimulations plus the live poller),
  peak backend memory is lower than the baseline's, and no request that succeeded in the baseline fails.
- **SC-004**: Backend memory at rest after warm-up is lower than the baseline. The exact target is set
  in the plan **after** the baseline exists, not guessed here.
- **SC-005**: No cache's entry count grows past its stated limit when it is asked for 2× that many
  distinct keys. An evicted key's next answer is identical to its first.
- **SC-006**: Cold-start page loads (first request after a restart) are no slower than the baseline's,
  within ±10%.
- **SC-007**: Fixed-seed simulation results and the response bodies of the full page sweep match
  before and after, apart from the named exclusions. 100% match.

**Success criteria after the rescope (2026-10-20, review F4/F6/F7/F15).** These replace SC-001 to SC-007 above, which are kept for the record:
- **SC-001′**: the baseline gives every holder a figure or a reason. Container-MB and committed-heap
  columns are the median of ≥3 runs and reproduce within **±20%**. Heap-used-after-GC reproduces within **±10%**. Both tolerances are guesses.
- ~~SC-002~~: deferred with FR-005.
- **SC-003′**: under the *realistic* and *stress* profiles (plan D1), the new settings' peak
  container memory is ≤ the old settings'. 2xx count ≥ old and 429 count ≤ old, median of 3.
- **SC-004′**: **5 minutes after the stress profile ends, container memory is back within 20% of its
  pre-load rest** with the new settings. The old settings are measured the same way for
  comparison. 5 minutes and 20% are guesses.
- ~~SC-005~~: deferred with US3.
- **SC-006′**: cold first-page latency, plus **resim wall clock p50/p95 under both profiles, are no
  worse than +10%** against the old settings (review F15).
- **SC-007′**: the backend suite passes with 0 skips. The C2 sweep (including fixed-seed `/api/sims`)
  is identical between the old and new settings.
- **SC-008 (new)**: the worst case (FR-011) runs to completion with no OOM, and GC time is stated
  from `-Xlog:gc`.
- **SC-009 (new)**: after deploy, minute-resolution `railway metrics` on a normal day shows the
  backend's resting level and post-spike recovery. They are compared with R0 and reported as measured.

## Assumptions

- **The goal is production cost and draft-night headroom.** Railway bills for memory used, and the
  backend is the large service. Local developer memory and the browser's memory are out of scope.
- **Measuring production is read-only.** Production memory is read from Railway's metrics or logs.
  The draft-night load is reproduced locally with production-shaped data, never by load-testing the
  live site. Linking the Railway CLI or reading metrics needs Allan's go-ahead if it changes any
  project state.
- **No schema change is expected.** If one turns out to be needed, it takes the next free `V<n>`
  checked across every open branch (lesson #43).
- **No new runtime dependency** (for example a cache library) unless the plan shows the built-in
  structures can't express the limit. The repo has none today.
- **Lower memory must not cost visible speed.** A trade that saves memory but slows a page past
  SC-006 is rejected, not accepted with a note.
- **The FR-004 threshold, the 50% in SC-002 and the ±10% tolerances are guesses**, set before any
  measurement exists, and labelled as such. The plan may revise them after the baseline, visibly.
- **Out of scope**: rewriting the simulation engine's algorithm, changing the default or maximum
  iteration counts, changing the Railway plan, and the database's own memory (Postgres is a separate
  managed service).

### Amended after measurement (2026-10-20, T008, FR-009; see `baseline.md`)
- **H1 is real locally**: after a stress burst the container sat at ~760 MB for 10 min (rest 245 MB) with 612 MB heap committed and 56 MB live.
- **H5**: NFL ingest live heap peak was a few MB over a 55 MB base, so the `DEPLOY.md` "256MB may OOM" guess is not supported.
- **H2**: ~89 MB for one 5,000-iteration run (measured, noisy), transient. **H3**: ~831 B/row measured once, ~140 MB projected at full seasons (estimate).
- **Threshold** recomputed from local warm (584 MB): 25 MB. Local sizes are ~1.6-1.9x production's; the gap is unexplained.
