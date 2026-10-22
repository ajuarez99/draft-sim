# Tasks: Memory Footprint, JVM settings only (spec 041)

**Input**: `spec.md` (with its "Rescoped" note), `plan.md` § "Amended after review and rescope" (D1′–D4′), `plan-review.md`, `contracts/`
**Scope**: US1 (measure) and US2 (JVM settings). US3, FR-005 and US4 are out of scope (spec "Rescoped").
**Tests**: one unit test, for the startup log's flag filter (D3′). Everything else is verified by measurement and equivalence.

Paths are relative to the worktree root `.claude/worktrees/041-memory-footprint`.
Shared-state rules for every task:
- Use only the `draftsim-pg` container (:5433) and the scratch DB `draftsim_041`. Never use :5432.
- Docker containers are named `bk041-*` and removed when done.
- Use ports 8098 and 8099 only.
- No commits without Allan's go-ahead.

## Phase 1: Setup (measurement tooling)

- [X] T001 Create the scratch DB. Run `pg_dump` on `draftsim` inside `draftsim-pg` and restore it into a new database `draftsim_041` in the same container. Record `select sport, season, count(*) from player_game group by 1,2` and the dump time in the header of `specs/041-memory-footprint/baseline.md`.
- [X] T002 [P] Write `scripts/memory/Dockerfile.jdk`: `ARG APP_IMAGE`, then `FROM ${APP_IMAGE} AS app`, then `FROM eclipse-temurin:21-jdk-jammy`. Copy `/app` and the `app` user setup over, with the same `WORKDIR`, `USER`, `EXPOSE` and `ENTRYPOINT` as the source image. A comment says it is for measurement only (it adds `jcmd`, review F2).
- [X] T003 [P] Write `scripts/memory/load.py` (Python stdlib only, run with `python -I`):
  - Arguments: `--base`, `--league`, `--draft`, `--members <comma ids>`, `--iterations`, `--seed`, `--mode realistic|stress|worst`, `--rounds`, `--interval`.
  - Each call is `POST /api/sims/stream` with its own `X-Sleeper-User`, read to the final event.
  - Record per call: member, HTTP status, wall clock ms, and whether a final result arrived.
  - Output: a CSV, plus a summary of 2xx/429/other counts and wall clock p50/p95.
  - Read `SimulationController` and `SimulationRequest` for the exact request body and the SSE event names. Do not guess them.
- [X] T004 Write `scripts/memory/measure.sh` (Git Bash). Given a container name and a profile, it records:
  - container MB from `docker stats --no-stream`;
  - for JDK containers only: `jcmd 1 GC.heap_info` (committed and used), then `jcmd 1 GC.run` and `GC.heap_info` again (used after GC), and `jcmd 1 VM.native_memory summary` (total committed).
  - It appends one row to a C3-format table (`contracts/C3-baseline-table.md`).
- [X] T005 Build `bk041:main` from `origin/main` (`c987b92`) using its unchanged Dockerfile, via a throwaway `git worktree` or `git archive`. Build `bk041:main-jdk` from it with T002.
  - Run both with `--memory=2g --cpus=2`, `DB_URL=jdbc:postgresql://host.docker.internal:5433/draftsim_041`, a local `ADMIN_TOKEN`, and `-XX:NativeMemoryTracking=summary` added via `JAVA_OPTS` (JDK variant only).
  - Check `/api/health` returns `weightsLoaded: true`.
  - **(analyze I1)** On the old images, `JAVA_OPTS` *replaces* the image default. Every `:main`/`:main-jdk` container MUST therefore get `JAVA_OPTS="-XX:MaxRAMPercentage=75 <extras>"`, or the baseline runs on JVM defaults instead of production's settings. Confirm with `jcmd 1 VM.flags` (MaxHeapSize = 1610612736) on `:main-jdk`. On `:041` images, extras go in `JAVA_OPTS` alone, because the required flags sit in the entrypoint.

## Phase 2: User Story 1, the baseline (P1)

**Goal**: a reproducible C3 baseline of the old settings, with every holder measured or explained.
**Independent test**: running T006 again reproduces container MB and committed heap within ±20%, and heap-used-after-GC within ±10% (SC-001′).

- [X] T006 [US1] Look up 12 distinct member Sleeper ids for the NBA 2026 league that owns draft `1339351318128517120` (`league_member`, `draftsim_041`). Then run every profile from plan D1′ (`rest`, `warm` via the C2 endpoint list, `realistic`, `stress`, `recovery` at +1/+5/+10 min, `worst`) **×3** on `bk041:main` (container MB) and `bk041:main-jdk` (heap and NMT). Write medians and spreads to `specs/041-memory-footprint/baseline.md`. The `worst` profile's ingest is `POST /api/ingest/players?sport=nfl` (the large dump; analyze U1), then `?sport=nba`, with `X-Admin-Token`. Record each one's peak. It calls the real Sleeper API and writes only to `draftsim_041`; say so in the doc.
- [X] T007 [US1] Per-holder figures in `baseline.md` (C3 per-holder table):
  - H2: heap used after GC sampled mid-run of a 5,000-iteration sim minus `rest`.
  - H3: bytes per `player_game` row from NBA 2025, projected to NFL 2025/26 + NBA 2024/25/26 at full season + one reload in flight (review F11).
  - H5: ingest live peak.
  - H10: the NMT breakdown.
  - Calibration: local `warm` against production's 300–370 MB plateau, with the gap stated (review F12).
  - Each figure is labelled measured or estimated.
- [X] T008 [US1] Compute the FR-004 threshold from the measured `warm` (min of 5% and 25 MB). Fill in the decision column (`change via JVM setting` / `leave alone / out of scope`) for H1–H10 in `baseline.md`. Write any estimate that measurement contradicts into `spec.md` as an "amended after measurement" note (FR-009).

**Checkpoint**: the baseline exists. US2 compares against these numbers, never against estimates.

## Phase 3: User Story 2, JVM settings (P1)

**Goal**: memory comes back after spikes, the ceiling is explicit and safe, and outputs and latency are unchanged.
**Independent test**: plan D1′ profiles on `bk041:041` against `bk041:main` meet SC-003′, SC-004′, SC-006′, SC-007′ and SC-008.

- [X] T009 [US2] Sweep the manageable G1 flags with `jcmd 1 VM.set_flag` on a running `bk041:main-jdk` after `stress`, one value at a time, each repeated ×3:
  - `G1PeriodicGCInterval` ∈ {15000, 60000, 300000}
  - `MaxHeapFreeRatio`/`MinHeapFreeRatio` ∈ {30/10, 50/20, 70/40 (default)}
  - Record `recovery` MB and the next round's resim p50/p95.
  - Choose values and write the reasoning to `baseline.md` § "Sweep". Label the chosen values as measured choices, not principled ones.
- [X] T010 *(Amended 2026-10-21: the ratio flags were dropped by the T009 sweep; see plan D2′ note.)* [US2] Edit `Dockerfile`:
  - Replace `ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"` with `ENV JAVA_OPTS=""`.
  - Change the `ENTRYPOINT` to `exec java -XX:+UseG1GC -Xmx1024m -XX:G1PeriodicGCInterval=<T009> -XX:MinHeapFreeRatio=<T009> -XX:MaxHeapFreeRatio=<T009> $JAVA_OPTS -jar /app/app.jar`.
  - Add a comment explaining each flag, that the values come from `specs/041-memory-footprint/baseline.md`, and that `JAVA_OPTS` adds to these flags and can override any one of them but cannot remove them (FR-012).
- [X] T011 [P] [US2] Add `backend/src/main/java/com/ballknowers/draftsim/config/JvmSettingsLogger.java`:
  - A `@Component` listening for `ApplicationReadyEvent`.
  - It logs one INFO line: max heap MB (`Runtime.maxMemory`), the GC `MemoryPoolMXBean`/`GarbageCollectorMXBean` names, `availableProcessors`, and the `RuntimeMXBean.getInputArguments()` entries starting with `-X` (this includes `-XX:` and `-Xmx`).
  - Every other argument (`-D…`, `-javaagent`, anything else) is dropped.
  - The filter is a package-private static method. Add `backend/src/test/java/com/ballknowers/draftsim/config/JvmSettingsLoggerTest.java`: it keeps `-Xmx1024m` and `-XX:+UseG1GC`, and drops `-Dspring.datasource.password=x` and `-javaagent:/a.jar`.
- [X] T012 [US2] Run an A/B of `MALLOC_ARENA_MAX=2` (as `docker run -e`) on the T010 image, comparing `rest` and `recovery` ×3. Add `ENV MALLOC_ARENA_MAX=2` to `Dockerfile` **only if** the saving is at least the T008 threshold. Record the result either way in `baseline.md`.
- [X] T013 [US2] Build `bk041:041` (and `bk041:041-jdk` via T002). Run every D1′ profile ×3. Write `specs/041-memory-footprint/verification.md` with the same C3 tables side by side with the baseline, and a pass/fail line per SC:
  - SC-003′: peak and 2xx/429 counts.
  - SC-004′: recovery within 20% of rest at +5 min.
  - SC-006′: cold first page and resim p50/p95 within +10%.
  - SC-008: `worst` with no OOM; GC pause total from `-Xlog:gc` added via `JAVA_OPTS` for that run only.
  - Check that the startup log line (T011) shows the intended flags.
- [ ] T014 [US2] Check equivalence (SC-007′):
  - `cd backend && ./gradlew test` with `draftsim-pg` up: 0 failed, **0 skipped**. Quote the counts.
  - C2 sweep (`contracts/C2-response-sweep.md`, plus `POST /api/sims` with a fixed `seed` and 500 iterations, review F14) against `bk041:main` and `bk041:041` on the same `draftsim_041`. Bodies must be identical apart from the named exclusions.
  - Record both in `verification.md`.

## Phase 4: Polish and cross-cutting

- [X] T015 [P] Rewrite the "Memory" paragraph at `DEPLOY.md:312`: the measured rest, peak and recovery numbers, the flags and why, the `JAVA_OPTS`-adds rule, and an "amended 2026-10-20" note replacing the "256MB may OOM" guess with the T007 ingest figure.
- [X] T016 [P] Add a lesson to `claude/lessons.md`. Coarse-bucket metrics (Railway's 4 h raw series) carry one deploy-overlap sample forward and look like sustained memory. Read at minute resolution and line up against deploy times before calling something a level. Include the 10-16 numbers.
- [X] T017 [P] Add a spec 041 entry at the top of `HANDOFF.md`: what shipped, what was measured, what is deferred (FR-005 fold, H9 allocation churn, cache limits), and the owed post-deploy check (SC-009).
- [X] T018 Bug-hunting code review of the diff (Dockerfile entrypoint quoting and `sh -c` expansion of an empty `$JAVA_OPTS`, flag precedence, the logger filter, script correctness) → `specs/041-memory-footprint/code-review.md`. Fix findings and re-run the affected T013/T014 checks.
- [ ] T019 **Allan's go-ahead required.** Commit, push, open a PR and deploy. Then, ≥24 h after deploy, read minute-resolution `railway metrics -s draft-sim --memory` across a normal day, plus the first post-deploy spike, and add the results to `verification.md` § "Production" (SC-009). Drop `draftsim_041` and the `bk041:*` images once Allan confirms they're no longer needed.

## Dependencies

- T001 → T005 → T006 → T007 → T008 → T009 → T010 → T012 → T013 → T014 → T018 → T019.
- T002, T003 and T004 can run in parallel once T001 is done. T005 needs T002.
- T011 is independent of measurement (can run any time before T013).
- T015, T016 and T017 need T013's numbers.

## Parallel example

T002, T003 and T011 touch different files and have no shared inputs: Dockerfile.jdk, load.py, and the logger with its test.

## Implementation strategy

MVP = Phase 2 (the baseline). On its own it answers "is there memory to save". If T006–T008 show the old
settings already recover well and nothing clears the threshold, stop there, report it, and skip Phase 3.
That is a legitimate outcome under the spec's honesty rule.
