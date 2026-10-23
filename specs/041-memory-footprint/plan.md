# Implementation Plan: Memory Footprint

**Branch**: `041-memory-footprint` | **Date**: 2026-10-20 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `specs/041-memory-footprint/spec.md`

## Summary

Production's backend uses ~300–370 MB after a deploy and peaked at **827 MB on draft night**
(measured, research R0). Between deploys it **ratchets up to ~675–983 MB and stays there** for hours.
The work, in order of expected payoff:

1. **Measure** (US1): a repeatable baseline in a production-shaped container (2 GB, 2 vCPU), with
   per-holder retained sizes. It decides which of the steps below get built.
2. **JVM ceiling + giving memory back** (H1, US2): an explicit max heap sized from the measured
   peak, G1 periodic collection so a spike's heap is released, and a startup log of the effective
   settings. **No Java logic changes.**
3. **Stream the Monte Carlo fold** (H2, US2): tally each iteration as it's read instead of keeping
   all 5,000. Results are identical, proven by a golden test written on the old code first.
4. **State every cache's limit** (H3/H4, US3): documented natural bounds where measurement says
   they're tiny. Code changes only where a holder clears the FR-004 threshold.
5. **Frontend (US4)**: **resolved by measurement, no work.** 20 MB in production (R0), below the threshold.

## Amended after review and rescope (2026-10-20). This supersedes Summary and Design below

The plan review (`plan-review.md`) found the "ratchet" premise was a bucket artefact (F1), the harness
couldn't run (F2), and the heap-sizing rule was undefined (F3). Allan then cut the scope to **JVM
settings only**. D3 (streaming fold), D4 (cache limits) and D5 (ingest streaming) are **not built**.
The Summary and Design sections further down are kept as written, for the record.

### D1′. Measurement (fixes F2, F6, F7, F12)
- **Images.** `bk:main` is built from `origin/main`'s Dockerfile, unchanged. `bk:041` is built from
  this branch. For `jcmd`, `scripts/memory/Dockerfile.jdk` re-bases either image's `/app` onto
  `eclipse-temurin:21-jdk-jammy` (`ARG APP_IMAGE`), with the same entrypoint. JDK-based images are
  used for heap and NMT readings. `docker stats` (container MB) is read on the real JRE images. The
  base difference is labelled.
- **Data.** Run against a scratch database `draftsim_041` in the `draftsim-pg` container, restored
  from a `pg_dump` of `draftsim`. Both images see an identical snapshot, and the ingest's writes
  don't touch the shared DB. Nothing touches :5432.
- **Container.** `--memory=2g --cpus=2` (real CPU limit, F6), plus `ADMIN_TOKEN` set locally for the ingest step (F12).
- **Profiles** (each ×3, median; F7 tolerances):
  - `rest`: 60 s after ready
  - `warm`: after the C2 sweep
  - `realistic`: one 500-iteration resim every 30 s for 20 min, with distinct members rotating
  - `stress`: 12 distinct league members × 500 iterations × 5 rounds, concurrent within a round
  - `recovery`: container MB at +1, +5 and +10 min after stress
  - `worst` (FR-011/SC-008): 2 concurrent 5,000-iteration sims + `POST /api/ingest/players?sport=nba`
  - Member ids come from `league_member` for the NBA 2026 league that owns draft `1339351318128517120`.
  - The live poller is **left out and labelled**: there is no drafting draft locally.
- **Calibration** (F12): the local `warm` reading is compared with production's post-deploy plateau
  (300–370 MB). The gap is stated, not assumed to be zero.
- **Scripts**: `scripts/memory/measure.sh` (profiles → C3 table rows) and `scripts/memory/load.py`
  (stdlib only: concurrent SSE `POST /api/sims/stream` with distinct `X-Sleeper-User`, recording
  wall clock and status per call).

### D2′. JVM settings (H1; fixes F3, F5, F8)
- **`Dockerfile` ENTRYPOINT** puts the required flags literally ahead of `$JAVA_OPTS`
  (`exec java <required> $JAVA_OPTS -jar /app/app.jar`). A future variable then adds or overrides
  flag by flag and can't remove them all (FR-012). `ENV JAVA_OPTS=""`, so the old
  `MaxRAMPercentage=75` goes.
- **Required set, starting values (guesses, each measured):**
  - `-XX:+UseG1GC`: pinned, so a smaller container doesn't silently switch to Serial
  - `-Xmx1024m`: explicit and generous. 1024 + ~250 MB measured non-heap < 2 GB. Validated by the `worst` profile (SC-008); lowered only if `worst` shows it's safe *and* lowering saves measurable memory
  - `-XX:G1PeriodicGCInterval=60000`: return heap after spikes (F5: 675 → 144 MB in 20 s, measured)
  - `-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30`: how far the heap shrinks (F5)
- **Optional set (FR-004 gated: ships only if it saves ≥ the threshold in `recovery`/`rest`):**
  `MALLOC_ARENA_MAX=2` (env; F10's glibc arenas).
- **Sweep method**: the three G1 flags are `manageable`, so values are tried with `jcmd VM.set_flag`
  on one running JDK container. A rebuild is needed only for `-Xmx` and the env var. The final set is
  then confirmed on the real JRE image.
- Caveat recorded (F5, argued): a periodic cycle is skipped while any GC ran within the interval,
  so during a live draft (2 s poller) memory is given back after the draft, not during it.

> **Amended after build (2026-10-21, T009 sweep + code-review #9):** `Min/MaxHeapFreeRatio` were swept and **not shipped**. They made no measurable difference, because an idle service runs no GC for them to act on. The shipped set is `-XX:+UseG1GC -Xmx1024m -XX:G1PeriodicGCInterval=60000`. `MALLOC_ARENA_MAX=2` was also not shipped (saved under 5 MB, below the 25 MB threshold).

### D3′. Startup log (FR-012)
`config/JvmSettingsLogger`: one INFO line on `ApplicationReadyEvent` with max heap MB, collector
names, available processors, and the `-X`/`-XX` input arguments only. `-D` and everything else is
filtered, so a future secret passed as `-D` never reaches the log. A unit test covers the filter.

### D4′. Documentation
- `specs/041-memory-footprint/baseline.md`: C3 tables, before and after.
- `DEPLOY.md` "Memory" paragraph: measured numbers, the flags and why, and the "`JAVA_OPTS` adds, doesn't replace" rule.
- `claude/lessons.md`: a new lesson on coarse-bucket metrics and deploy overlap (this spec's F1).
- spec.md Context table: H9 (allocation, 1.1 MB/iteration) and H10 (non-heap, ~236 MB at rest), with an "amended after review" note.

### Not built (recorded in spec "Rescoped"): D3 streaming fold, D4 cache limits, D5 ingest streaming, frontend.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5, virtual threads); TypeScript untouched
**Primary Dependencies**: none added (no cache library; R3)
**Storage**: Postgres. **No migration** (highest on main is V38; none needed)
**Testing**: JUnit 5 (`./gradlew test`, 0 IT skips with `draftsim-pg` up); golden JSON via `api/GoldenJson`
**Target Platform**: Railway Linux container, 2 GB / 2 vCPU, `eclipse-temurin:21-jre-jammy`
**Project Type**: web service (backend) + static SPA (out of scope after R0)
**Performance Goals**: no slower. A 5,000-iteration sim's wall clock and cold first page loads within ±10% of baseline (SC-006)
**Constraints**: byte-identical results (FR-003); same accepted load (FR-008); production read-only
**Scale/Scope**: one league of 12 on draft night; 4 seasons of `player_game`; 2 sim permits

## Constitution Check

| Rule | Status |
|---|---|
| Migrations append-only | ✅ None planned. If one appears, take the next free `V<n>` across all branches (lesson #43) |
| `api.ts` mirrors Java records | ✅ No record changes. `SimulationResult` is unchanged by design |
| No retuning constants to match guesses | ✅ The FR-004/SC thresholds are labelled guesses. The heap size comes from a measured peak and is never tuned to make a number look good. `weights.yml` is untouched |
| `Map.of` null trap | ✅ No new response maps |
| Ask before committing | ✅ Worktree branch. Commit/push/PR wait for Allan |
| Planning doc ≠ verified spec | ✅ Spec's H-table sizes are estimates. R0 already corrected one (H8) and R0's 10-09 reading. FR-009 notes go into spec.md |

**Post-design re-check**: unchanged. The design adds no record, endpoint, migration or dependency.

## Design

### D1. Baseline harness (US1) → `specs/041-memory-footprint/baseline.md`
- `scripts/memory-baseline.sh` (Git Bash): build the image, run it with `--memory=2g --cpus=2`,
  `JAVA_OPTS` + `-XX:NativeMemoryTracking=summary`, against `draftsim-pg`. Steps: at rest → warm
  sweep (C2 endpoint list) → 12 concurrent sims + track (load profile) → player ingest. At each
  step it records `docker stats` memory, `jcmd GC.heap_info` after `GC.run`, and the NMT summary.
- Per-holder deltas (R8): heap after GC, before and after the request that fills H2 (sim in
  flight: sampled mid-run), H3 (one season's stats page), H4 (entry count via a one-off
  debug read in the harness, not product code) and H5 (mid-ingest).
- Output: the C3 table format. Run it twice to check reproducibility (SC-001).

### D2. JVM settings (H1)
- `Dockerfile`: `JAVA_OPTS` becomes an explicit `-Xmx<N>m` (N = measured warm-sweep + load-profile
  peak heap × 2, rounded up to 64 MB; recorded in baseline.md with its arithmetic), plus
  `-XX:G1PeriodicGCInterval=60000` (60 s, a guess, labelled) and `-XX:+UseG1GC` (pinned explicitly,
  so a smaller Railway limit can't silently switch the collector to Serial).
- Startup log line (`DraftSimApplication` or a small `ApplicationReadyEvent` listener): max heap,
  collector names, available processors. One line at INFO.
- `DEPLOY.md:312` "Memory" paragraph rewritten with measured numbers and an "amended" note.

### D3. Streaming fold (H2)
- `MonteCarloRunner`: extract the two tally loops of `aggregate` into a small accumulator
  (`counts`, `survived`, `bestAvailCounts`) with `add(RunResult)`. In the consume loop, read the
  future at index i, call `add`, and set `futures.set(i, null)`. `aggregate` then reads the
  accumulator instead of `List<RunResult>`. Lock-scrubbing and assembly code are unchanged.
- Cancellation path unchanged: the accumulator is discarded with the exception.
- Guard: C1 golden test committed **before** the change (green on old code); the conversion commit
  touches 0 golden files.

### D4. Cache limits (H3, H4, H6, H7)
- Every holder gets a declaration comment naming its limit and why (natural bound + measured size
  or explicit cap). Already-documented H6/H7 are left as they are.
- H3: conditional on baseline. Below threshold → comment only. Above → access-ordered cap (N from
  the number of seasons callers actually request), honouring `markRefreshing`; tested with
  2× N distinct seasons (SC-005).
- H4: microbenchmark (JMH not in the build; a timed JUnit loop labelled rough) of memo vs direct
  `Math.exp`. If direct is within noise, remove the memo; otherwise comment the growth rate.

### D5. Player ingest (H5): conditional
Built only if the baseline's ingest delta clears the threshold: Jackson streaming over the dump's
top-level object, mapping each player as it's read. Otherwise recorded as "leave alone".

## Project Structure

### Documentation (this feature)
```text
specs/041-memory-footprint/
├── spec.md, plan.md, research.md, data-model.md, quickstart.md
├── contracts/  C1-simulation-equivalence.md, C2-response-sweep.md, C3-baseline-table.md
├── baseline.md        # US1 output (implementation)
├── plan-review.md     # adversarial review
├── tasks.md, code-review.md, verification.md
```

### Source Code
```text
Dockerfile                                         # D2
DEPLOY.md                                          # D2 docs
scripts/memory-baseline.sh                         # D1
backend/src/main/java/com/ballknowers/draftsim/
├── engine/MonteCarloRunner.java                   # D3
├── engine/SeasonBoxCache.java                     # D4 (comment or cap)
├── sport/FootballRules.java, BasketballRules.java # D4
├── ingest/PlayerIngestService.java                # D5 (conditional)
└── config/ or DraftSimApplication.java            # D2 startup log
backend/src/test/java/.../engine/MonteCarloGoldenTest.java  + resources/golden/montecarlo/*.json
```

**Structure Decision**: existing backend layout. No frontend changes (R0/R7).

## Complexity Tracking

None. No new dependency, module or abstraction beyond one package-private accumulator.
