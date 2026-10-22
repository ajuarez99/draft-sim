# Adversarial plan review: spec 041 (memory footprint)

This review read spec.md, plan.md, research.md, data-model.md, quickstart.md and contracts/C1–C3
cold on 2026-10-20. It checked them against the worktree `041-memory-footprint` at `c987b92`
(code identical to `main`), the local `draftsim-pg` on :5433, and production through the
read-only `railway metrics` command only. It ran before any production code was written.

Every item is labelled **measured** (run today, output quoted) or **read** (from the code, not
executed). Where a measurement was taken on the Windows dev machine rather than in a Linux
container, it says so. Those numbers are good for ratios and mechanisms, not for absolute
container MB.

**What this review ran, so nobody is surprised later:**
- `./gradlew testClasses bootJar` in the worktree. The output is in the gitignored `backend/build/`.
- Two scratch probes (in the session scratchpad, not the repo) that call `DraftSimulator` and
  `MonteCarloRunner` directly.
- The worktree jar on **:8097** against the shared `draftsim-pg`, with `-Xmx1536m
  -XX:ActiveProcessorCount=2 -XX:NativeMemoryTracking=summary`. It ran 120 `POST /api/sims` calls
  of 500 iterations each against the "Ball Knowers" NBA 2026 draft and was then stopped. The
  simulation path only reads from the DB. Flyway found V38 already applied, so nothing migrated.
- `railway metrics` (read-only) for 10-08, 10-10, 10-13, 10-15, 10-16/17 and 10-19.

## Verdict

**The direction is right, but the plan's premise and its measurement harness both need fixing
before tasks are written.**

- **The big claim in R0 is a bucket artefact.** At fine resolution, production does not ratchet
  to 675–983 MB and hold it for hours (F1).
- **The harness can't run as written.** The production image has no `jcmd` (F2).
- **The `-Xmx` rule is undefined, and on one reading it is dangerous** (F3).
- **The draft-night peak is driven by allocation rate, not by retained iterations**, so D3 won't
  move SC-003 (F4).
- **The central JVM idea does work.** I measured it: G1 periodic GC shrank committed heap from
  675 MB to 144 MB within 20 s. MaxHeapFreeRatio sets the floor that remains (F5).
- **The D3 identity argument holds** (F13).

## Claims confirmed

| Claim | Where checked | Result |
|---|---|---|
| Max heap ≈ 1.5 GB, G1 chosen at 2 GB / 2 vCPU (R1) | `docker run --memory=2g --cpus=2` on the existing `ballknowers-backend:main-check` image (Temurin 21.0.12), `java -XX:MaxRAMPercentage=75 -XX:+PrintFlagsFinal` (**measured**) | ✅ `MaxHeapSize=1610612736`, `UseG1GC=true {ergonomic}`, `ParallelGCThreads=2`, `ConcGCThreads=1`, `InitialHeapSize=32 MB`, `G1PeriodicGCInterval=0 {manageable}`, `MaxHeapFreeRatio=70 {manageable}`, `SoftMaxHeapSize` = max (G1 in 21 ignores it) |
| `JAVA_OPTS` reaches the JVM | `Dockerfile:26,33` `sh -c "exec java $JAVA_OPTS -jar"` (read) | ✅ unless a Railway variable named `JAVA_OPTS` overrides it (F8) |
| `RunResult` ≈ 10–30 KB (H2) | scratch probe, NFL 12×15, 400-player board, 15 own picks, Windows JDK 21, `System.gc()` deltas (**measured**) | ✅ **17.9 KB/run** (87.5 MB for 5,000; 8.5 MB for 500). Without `rosters`: 11.2 KB/run, so `rosters` is ~37% of it |
| `rosters` has no production reader (R2) | grep (read) | ✅ only `DraftSimulator` builds it |
| Two permits on 2 vCPU | `SimulationPermits.java:62-64` (read) | ✅ `max(2, cpus/4)` = 2; wait cap 3 s (`:57`); max waiting = 16 (`:67`) |
| No result retention outside the runner | grep for `SimulationResult` holders; `SimulationService.java:95` returns directly (read) | ✅ |
| The container reaches the host DB | `docker run … bash -c 'echo > /dev/tcp/host.docker.internal/5433'` (**measured**) | ✅ reachable. `draftsim-pg` is also on the `draft-sim_default` network, so `--network draft-sim_default` + `postgres:5432` works as well |
| Highest migration V38, no migration needed | `ls db/migration`; `flyway_schema_history` (**measured**) | ✅ |
| No startup or scheduled jobs that would perturb a baseline | grep `@Scheduled`, `ApplicationReadyEvent`, `@PostConstruct`, runners (read) | ✅ none |
| Local DB contents (R3) | `player_game` (**measured**) | ✅ NBA 2024 28,798 · NBA 2025 29,143 · NFL 2025 26,490 · NFL 2026 6,229 |
| G1 gives memory back only after a concurrent cycle or a full GC (R1) | local server, below (**measured**) | ✅ committed heap stayed at 560 MB through 60 s idle and fell to 100 MB only after `GC.run` |

## Findings

Ranked by severity.

**F1 (CRITICAL). R0's "ratchet held for 8–12 h at 675–983 MB" comes from coarse-bucket
aggregation. At fine resolution, production sits at 200–400 MB.**
Evidence (**measured**, `railway metrics --memory --raw`):
- The 7-day hourly series shows `10-16T23:00 … 10-17T06:00` flat at **981 MB** for 8 hours.
- The same window over 9 hours (135 points, ~4 min apart) shows **912 MB at 10-16 23:00** and
  **371 MB** for the rest of the night.
- At 30 s resolution, 10-16 22:55–23:30 runs 262–373 MB. Deploys landed at **23:00 and 23:02**
  (the deployments list), so this is the same old-and-new-container overlap R0 already found for
  10-09.
- Per-day histograms at ~4 min resolution:
  - **10-08**: max 776 (R0 says 980). 151 of 360 points in the 200s, 86 in the 300s, 83 in the
    400s, 26 in the 600s.
  - **10-13**: max 517 (R0 says 675). 212 points in the 300s.
  - **10-15**: max 693. Mostly the 200s and 300s.
- The 1-hour and 4-hour buckets appear to be per-bucket maxima or carried values. They are not
  means.

Consequence: the plan's Summary, R0's third bullet, R1's rationale ("targets the at-rest number
that's billed every hour") and the spec's "Amended after measurement" block all overstate the
at-rest problem. The real pattern is short spikes, mostly around deploys and sims, returning to
~300–400 MB. Two parts of R0 still stand: draft night's 827 MB at 21:15 (the draft started at
21:16:19, per the `draft` row) and its 476–692 MB tail.

**Amendment**:
- Add an "amended after review" note to spec Context and R0. It should give the fine-resolution
  numbers and withdraw "ratchets and stays".
- Restate the expected saving as spike height and spike duration, not as a permanent plateau.
- SC-004's target is still set after the baseline. Its premise ("memory that never comes back")
  needs a production time-weighted mean at ≤5-minute resolution, computed over a 7-day window
  with deploy windows excluded.
- Every later production comparison (quickstart §5) must use `--raw` at that resolution, never
  the summary or hourly series.

**F2 (CRITICAL). The production image has no `jcmd`, so D1's harness can't run.**
Evidence (**measured**): `ls /opt/java/openjdk/bin` in the backend image lists `java jfr jrunscript
jwebserver keytool rmiregistry`, and `which jcmd` finds nothing. `eclipse-temurin:21-jre-jammy` is
a JRE. The backend also has no actuator (`backend/build.gradle.kts`, read). D1, R8 and the
quickstart all depend on `jcmd GC.run`, `GC.heap_info` and `VM.native_memory summary` inside the
container.
**Amendment**: in D1/R8, pick one of these and state it:
- (a) A measurement-only image that runs the same jar on `eclipse-temurin:21-jdk-jammy`, pinned
  to the same 21.0.x build. Label the base difference (JDK vs JRE: CDS archive, a few MB of
  mapped files).
- (b) Mount a Linux JDK's `bin/jcmd` (for example from the Dockerfile's `gradle:8.14-jdk21`
  build stage via `--target build`) and run it with `docker exec`.
- (c) Use `-Xlog:gc*,gc+heap=debug:file=…` plus `-XX:+UnlockDiagnosticVMOptions
  -XX:+PrintNMTStatistics` (NMT printed at exit) in place of jcmd. This loses mid-run
  `GC.run`.

My recommendation is (a), with `docker stats` still taken on the real JRE image for the
container-MB columns.

**F3 (CRITICAL). D2's `-Xmx = peak heap × 2` is undefined. On one reading it would OOM, and it
leaves out the daily ingest.**
Evidence (**measured**, Windows, 2 active processors, 12 users × 500 iterations, 5 rounds):

| Reading | Heap used | Heap committed |
|---|---|---|
| Sampled right after load | 411 MB | 675 MB |
| After a full GC | 30 MB | — |
| After the periodic cycle | 43 MB | — |

Two readings of "peak heap":
- **Sampled peak used.** It is mostly garbage, so ×2 gives ~820 MB. Plausible, but it is a
  number about the GC's timing and says nothing about what the app needs.
- **Live set after GC.** 30–43 MB ×2 gives ~90 MB, which **cannot hold one 5,000-iteration run**:
  87.5 MB retained, measured. FR-008 says 5,000 is still accepted (`SimulationRequest.java:42`),
  and two permits can hold two such runs.

Either reading also misses H5:
- `DailyRefreshService.java:109-130` (read) calls `PlayerIngestService.ingest` for **both sports
  every day at 11:00 UTC** (`.github/workflows/daily-refresh.yml:10`).
- That ingest parses the whole Sleeper dump into a map of maps (`PlayerIngestService.java:82`).
- D2's formula uses "warm-sweep + load-profile peak" and omits `ingest-peak`.
- The dump's size ("~5 MB") is itself unverified. I did not download it.

And `-Xmx` is not what Railway bills. RSS follows **committed** heap, which F5 shows is governed
by GC cycles and `MaxHeapFreeRatio`, not by the ceiling.

**Amendment**:
- Define the floor as the worst case FR-008 accepts. That is 2 × the retained size of a
  5,000-iteration run (after D3, the tallies only), plus the ingest live peak, plus warm caches
  (H3, H6), plus young-gen room for ~560 MB per resim of allocation (F4).
- Validate the chosen `-Xmx` with a stress run at that worst case: 2 concurrent 5,000-iteration
  sims plus `POST /api/ingest/players?sport=nba` plus the realistic profile. Pass means no OOM,
  GC time from `-Xlog:gc` stated, and resim latency within F15's bound.
- Prefer an explicit but still generous ceiling (for example `-Xmx1024m`, labelled a guess).
  Take the saving from F5's flags, not from a tight `-Xmx`.
- State that `-Xmx` plus ~250 MB of non-heap (F10) must stay below the Railway limit. The edge
  case "Container limit changes" otherwise becomes an OOM kill.

**F4 (HIGH). The draft-night peak comes from allocation rate. D3 can't move SC-003, and R2's
"consistent with draft night's +460 MB" is wrong.**
Evidence:
- The live room resimulates at **500 iterations** (`web/src/pages/LiveDraftView.tsx:66`). The
  draft view offers at most **2,000** (`web/src/pages/DraftView.tsx:833`, `[500, 1000, 2000]`).
  5,000 is reachable only by calling the API directly (read).
- One 500-iteration run **allocates 541–568 MB**, about **1.1 MB per iteration**, but retains only
  **8.5 MB** (**measured**, `com.sun.management.ThreadMXBean.getTotalThreadAllocatedBytes`, 3
  repeats). That is ~65× more churn than retention.
- On the local server, 60 such sims took heap committed from **75 to 560 MB**, and in a second
  run to **675 MB**. The live set after a full GC was **30 MB** (**measured**, Windows).
- That matches production's 827 MB right at draft start, on a light load (F6).

**Amendment**:
- R2: strike the draft-night link. D3's real payoff is the worst case (5,000 iterations through
  the API, or 2,000 from the UI: ~36 MB per run at 17.9 KB), and FR-005 compliance.
- Plan Summary: reorder the payoff, putting D2 (+F5) first for both peak and rest.
- SC-003: attribute any reduction to D2 and F5's flags.
- SC-002: keep it, but define its metric as **heap used after a full GC, sampled mid-run** (see
  F2 for how). Quickstart §4's "heap delta (mid-run minus rest)" without a GC would mostly measure
  garbage.
- Add H9 "allocation per iteration (1.1 MB measured)" to the holder table. Mark it out of scope
  for this spec, or as a candidate for a follow-up. Cutting it is not "rewriting the algorithm",
  but it is engine work.

**F5 (MEDIUM). G1 periodic GC works, but `MaxHeapFreeRatio` decides how far it shrinks. Add it,
and use the fact that both flags can be changed at runtime.**
Evidence (**measured**, local server):
- After load, committed heap was 675 MB.
- `jcmd VM.set_flag G1PeriodicGCInterval 15000` brought it to **144 MB within 20 s**, and it
  stayed there.
- NMT total committed went from 766 MB to 320 MB.
- Used after the cycle was 43 MB, and 43 / (1 − 0.70) = 143 MB. That is exactly the
  `MaxHeapFreeRatio=70` bound.
- Separately, a full GC took the heap to 100 MB committed, 30 MB used.

**Amendment**:
- D2: add `-XX:MaxHeapFreeRatio` / `-XX:MinHeapFreeRatio` (for example 30/10). Label them guesses
  and measure them in the baseline.
- Both flags and `G1PeriodicGCInterval` are `{manageable}`. The baseline harness can sweep
  values with `jcmd VM.set_flag` on one running container instead of a rebuild per value.
- Note the JEP 346 caveat (argued, not measured): a periodic cycle fires only if no GC of any
  kind ran in the interval. A steadily allocating service, such as the 2 s live poller during a
  draft (`LiveDraftPoller.java:50`), can postpone it. The shrink comes after the draft, not
  during it.

**F6 (HIGH). The draft-night load profile can't be reproduced as written, and SC-003's
per-request comparison isn't deterministic.**
- **The poller can't be exercised.** "Plus the live poller tracking it" needs a draft in
  `drafting`. The poller stops when it sees `complete` (`LiveDraftPoller.java:30-31`), and every
  local draft is `complete` or `pre_draft` (**measured**).
- **Real draft night was light.** For 20:50–22:10 on 10-10, Railway reports **186 HTTP requests,
  90 of them POSTs, all 2xx, and peak CPU of 0.19 vCPU** (**measured**). Railway gives one
  identical p50/p99 for that window, so the HTTP metric may be sampled; its completeness is
  unverified. Twelve truly concurrent resims are therefore a stress test, not a replay of draft
  night.
- **Requests need twelve distinct members.** Each caller needs a distinct `X-Sleeper-User` that
  belongs to the league (`SimulationController.java:48-51`). A repeated identity cancels its own
  earlier run and inherits the permit (`SimulationPermits.java:96-103`).
- **429s depend on timing.** On 2 real vCPUs a resim takes longer than on the dev box's 12 cores.
  Twelve callers against 2 permits and a 3 s wait cap will 429 some of them, depending on timing.
  The client retries (`streamSimulationQuietly`), and the curl harness won't.

**Amendment**:
- Define two profiles:
  - **realistic**: one 500-iteration resim every ~30 s for 60 min (matching the 10-10 rate);
  - **stress**: 12 distinct members × 500 iterations per round, ≥5 rounds.
- Both run against `draft 1339351318128517120` with `seed` fixed.
- The poller is either replayed through a stub at `SLEEPER_BASE_URL` or explicitly left out and
  labelled.
- SC-003 becomes "2xx count ≥ baseline's and 429 count ≤ baseline's, median of 3 repetitions",
  not "no request that succeeded in the baseline fails".
- `docker run --cpus=2` (not `-XX:ActiveProcessorCount`, which I used and which still leaves 12
  real cores) is what makes latency and 429s representative.

**F7 (MEDIUM). SC-001's ±10% reproducibility will fail on `load-peak`.**
Evidence (**measured**): two identical 5-round stress runs on the same JVM gave committed heap
of **560 MB and 675 MB** (+20%). G1's young-gen sizing depends on GC timing.
**Amendment**:
- Report the median of ≥3 runs for container-MB and committed columns, with a tolerance of
  ±20% (labelled a guess).
- Keep ±10% only for heap-used-after-GC, which was stable (26 MB at rest, 30 MB after load).

**F8 (MEDIUM). A Railway `JAVA_OPTS` variable may override the Dockerfile, and the plan finds
out only after deploying.**
`DEPLOY.md:183` records that a stray `JAVA_OPTS` variable once broke boot. So the variable has
existed in production, and an `ENV` in the image loses to it.
**Amendment**:
- Before T-deploy, Allan checks whether a variable *named* `JAVA_OPTS` exists. That means the
  name only, read in the dashboard, not a CLI listing that prints values.
- Consider putting the required flags in the `ENTRYPOINT` ahead of `$JAVA_OPTS`
  (`exec java -XX:G1PeriodicGCInterval=… $JAVA_OPTS -jar`). A variable then adds to the image's
  flags and replaces them only flag by flag, with the last flag winning.
- The startup log line stays as the check after deploy.

**F9 (MEDIUM). FR-004 conflicts with FR-005, FR-006 and D4's H4 change. H4's microbenchmark
measures a path the simulation doesn't use.**
- **FR-005 against FR-004.** At draft-night iterations, H2 is 8.5 MB per run × 2 permits ≈ 17 MB
  (measured). That is below R7's ~18 MB threshold, yet FR-005 requires D3 regardless.
- **D4 against FR-004.** D4 may remove the H4 memo, which is a code change to a holder R4 itself
  puts far below the threshold.
- **H4 is off the hot path.** The hot path never touches the memo. `DraftContext` precomputes
  `rules.value(e)` once per board entry (`DraftContext.java:79-81`), and
  `FootballRules.java:46-53` says so. The memo grows by one entry per distinct ADP per board
  build, roughly 100–200 KB/day (estimated).

**Amendment**:
- FR-004 governs optional reductions only. Name FR-005 and FR-006's documentation as explicit
  exceptions.
- Measure H2 at **5,000** (the maximum accepted), not only at draft-night size.
- H4 becomes a comment-only change. Drop the microbenchmark from D4.

**F10 (MEDIUM). Holders and costs the spec missed. Non-heap is most of the at-rest number.**
- **Non-heap at rest** (**measured**, Windows NMT, cold start): 236 MB committed in total, of which
  the Java heap is only 75 MB.

  | Component | Committed |
  |---|---|
  | GC structures | 39 MB |
  | Metaspace | 52 MB |
  | Class | 9 MB |
  | Symbol | 21 MB |
  | Code cache | 19 MB |
  | CDS | 13 MB |

  After load: GC 50 MB, code 25 MB, metaspace 57 MB. Not counted by NMT, so estimated: glibc
  malloc arenas (up to 8 × cores on Linux) and mapped jar pages. None of D1–D5 touches these.
  At minimum, state them in the baseline. Candidates, each labelled a guess:
  `MALLOC_ARENA_MAX=2`, a smaller `ReservedCodeCacheSize`, and the GC-structure cost of a lower
  `-Xmx`.
- **`DraftAndAdpJoin.memo`** (`engine/DraftAndAdpJoin.java:93`): a `ConcurrentHashMap<Long, Memo>`
  per league holding `DraftGrades`. No limit, never pruned. It isn't in the H table or the FR-006
  audit. Estimated tens of KB per league.
- **`SeasonBoxCache` side maps** (`:80-83` `generations`, `refreshing`, `inFlight`): one entry per
  key, tiny, but they belong in the FR-006 audit next to H3.
- **`DraftSimulator.WARNED_DUPLICATES`** (`:24`): static and unbounded, but limited to
  distinct-duplicate player ids. Tiny.
- **Already pruned** (read), so record them as such: `LiveDraftPoller` `:87,:94,:99` (removed on
  stop, `:443-448`; H7 lists only `:107,:122`), `SimulationPermits.byKey` (`:53`, removed `:159`),
  `RecapService.pending` (`:80`, removed `:166`), `SingleFlight.inFlight` (removed on
  completion).
- **Transient per-request buffers**: the stat leaderboard is ~1.4 MB of JSON before gzip
  (`application.yml` comment). It's allocation, not retention. List it under H9.

**Amendment**: add H9 (allocation rate) and H10 (non-heap), and the rows above, to the Context
table with an "amended after review" note.

**F11 (MEDIUM). H3 will be measured on a DB without the season that is about to fill.**
NBA 2026 tips off around 10-20/10-21. The local DB has no NBA 2026 `player_game` rows, and
production will add ~29k rows over the season. A reload on a token change builds the new entry
while the old one is still reachable (`SeasonBoxCache.get`, read), so for a moment two copies
exist.
**Amendment**:
- R3: measure bytes per row from NBA 2025.
- Project H3 to "NFL 2025/2026 + NBA 2024/2025/2026 at full season, + one reload in flight".
- The natural bound is "seasons with stored games, including the in-progress one".

**F12 (MEDIUM). Harness details the quickstart gets wrong or leaves out.**
- **The Railway metric is not known to match `docker stats`.** R8 says `docker stats` "uses the
  same cgroup accounting Railway reports". Nothing verifies that. Calibrate instead: the local
  container's `warm` reading should land near production's post-deploy plateau (~300–370 MB) or
  the gap should be stated.
- **The ingest step will be refused.** The quickstart's `docker run` sets no `ADMIN_TOKEN`, so
  every `/api/ingest/**` route refuses (`application.yml` `admin.token`, blank = disabled), and
  `ingest-peak` can't be taken. Set a local `ADMIN_TOKEN` and send `X-Admin-Token`.
- **The ingest has side effects.** `POST /api/ingest/players` calls the real Sleeper API and
  **writes to the shared `draftsim-pg`** that other sessions use. Say so, or run it against a
  scratch DB (the ESPN spec used :5435).
- **Equivalence needs the same data.** Both images must run on an identical DB snapshot (C2
  says so). A peer session's ingest between the `:main` and `:041` runs breaks equivalence.
  Snapshot with `pg_dump` first.

**F13 (LOW, confirmation). D3's identity argument is correct.**
- **Determinism (read).** Every tally is a `HashMap` built by `merge` in run-index order. Its
  bucket layout and iteration order are a pure function of that operation sequence, so the
  stream fold gives identical maps. BoardAssembler breaks ties by id (`BoardAssembler.java:49-51`).
  Ties in `availability` (`MonteCarloRunner.java:220`, sorted by ADP) and `bestAvailable`
  (`:226`, sorted by count) fall back to HashMap iteration order. That order is unchanged
  because the sequence is unchanged. The `counts` and `survived` loops touch separate maps, so
  interleaving them per run is safe. Lock-scrubbing still runs after all of `counts`.
- **Retention (read).** A completed `FutureTask` keeps its `outcome` until the `futures` slot is
  dropped. The executor's queue releases a task once a worker takes it. Nulling the slot (or
  keeping no list at all) therefore frees the result. Workers can run ahead of the consumer by
  only a few results.
- **Cancellation (read).** A cancelled task returns `null` (`:87`), so `add` must skip null, as
  the code does today at `:102`.
- **Small amendments**:
  - C1 should name `EngineOutputFrozenTest`, which already holds a SHA golden of a 400-iteration
    run (`toString`, positions stripped). It must stay green, untouched, through the conversion
    commit.
  - `GoldenJson` ignores object key order. That's fine here, because every ordered output is an
    array or a `TreeMap`. Say so in C1.
  - The conversion may make case 2 faster (aggregate was 423 of 1,877 ms, **measured**), so
    "within ±10%" should read "no slower than +10%".

**F14 (LOW). C2 can cover simulations end to end.**
`SimulationRequest.seed` (`SimulationRequest.java:33`) makes `/api/sims` reproducible.
**Amendment**: add `POST /api/sims` with a fixed seed and 500 iterations to the C2 sweep.

**F15 (MEDIUM). Nothing guards resim latency under a smaller heap.**
SC-006 covers only cold page loads. A lower `-Xmx` means a smaller young gen and more young GCs
under ~560 MB of allocation per resim (F4). On 2 vCPU, `ParallelGCThreads=2` (measured) compete
with the 2 sim workers. The spec's draft-night edge case ("long GC pauses that the members
notice") has no SC.
**Amendment**: add an SC for resim wall clock under the realistic and stress profiles: p50/p95
within +10%, a guess. Read it from the `MonteCarloRunner` log line (`:126`) and the client-side
timing.

**F16 (LOW). The raw series has no-data stretches.**
At 30 s resolution, 10-19 10:50–10:58 and 10-17 03:00–03:02 read 0. 57 of 360 points on 10-13
are 0 (**measured**). They are either metric gaps or App Sleeping.
**Amendment**: Allan confirms whether the service sleeps. If it does, at-rest cost is partly
solved already, and SC-006 (cold start) carries more weight.

## Severity summary

- **CRITICAL**: F1 (the ratchet is a bucket artefact), F2 (no `jcmd` in the image), F3 (the
  `-Xmx` rule is undefined and could OOM; it omits the daily ingest).
- **HIGH**: F4 (allocation rate, not retention, drives draft night; D3 doesn't move SC-003),
  F6 (the load profile can't be reproduced and SC-003 isn't deterministic).
- **MEDIUM**: F5 (add MaxHeapFreeRatio; runtime-settable flags), F7 (SC-001 ±10% fails on the
  peak), F8 (Railway `JAVA_OPTS` override), F9 (FR-004 vs FR-005/006 and H4), F10 (missed
  holders; non-heap is the at-rest majority), F11 (H3 will grow with NBA 2026), F12 (harness
  gaps: ADMIN_TOKEN, shared-DB writes, metric calibration), F15 (no resim-latency SC).
- **LOW**: F13 (D3 identity confirmed, small C1 notes), F14 (seeded sims in C2), F16 (no-data
  stretches; App Sleeping?).
