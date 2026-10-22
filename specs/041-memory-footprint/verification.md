# Verification: the new JVM settings against the old (spec 041, T012-T014)

Every number is **measured** unless it says otherwise. Raw rows: `verification-rows.md` (new) and `measure-rows.md` (old). Method, workload, container limits and DB are
those of `baseline.md` (same scripts, `scripts/memory/baseline-rep.sh`, 3 repetitions, medians with [min-max]). Old = `bk041:main` / `-jdk` (`c987b92`, `-XX:MaxRAMPercentage=75`);
new = `bk041:041` / `-jdk` (this branch). **Caveats stated up front:** the old and new repetitions ran about 14 hours apart on the same Docker Desktop VM, not interleaved,
so host noise is not cancelled; n=3 with wide spreads (old stress peak 722-869). Container MB is from the JRE image, heap and NMT from the JDK image.

## What shipped (the flags)

`Dockerfile` entrypoint: `exec java -XX:+UseG1GC -Xmx1024m -XX:G1PeriodicGCInterval=60000 $JAVA_OPTS -jar /app/app.jar`, `ENV JAVA_OPTS=""`. `MALLOC_ARENA_MAX` and the
`Min/MaxHeapFreeRatio` flags are **not** added (measured no effect, `baseline.md` "Sweep" and "MALLOC_ARENA_MAX A/B"). The periodic interval is a measured choice between 15000 and 60000, not a principled one.

**Startup log line (FR-012), copied from `docker logs` of `bk041:041` with `JAVA_OPTS` empty:**

    JVM settings: maxHeapMb=1024 gc=[G1 Young Generation, G1 Concurrent GC, G1 Old Generation] availableProcessors=2 flags=[-XX:+UseG1GC, -Xmx1024m, -XX:G1PeriodicGCInterval=60000]

With no `-e JAVA_OPTS` at all the same flags appear (`availableProcessors=12` there because no `--cpus`). `JAVA_OPTS="-Xmx512m"` **adds** to the entrypoint flags and the last one wins:
`maxHeapMb=512 ... flags=[-XX:+UseG1GC, -Xmx1024m, -XX:G1PeriodicGCInterval=60000, -Xmx512m]` (measured). An empty `JAVA_OPTS` expands to nothing and the command starts normally.

## C3 table, side by side (medians of 3 [min-max])

Container MB = JRE image. Heap columns = JDK image. "before GC" = read without forcing a GC.

| profile | container MB old | container MB **new** | heap used MB old | heap used MB **new** | heap committed MB old | heap committed MB **new** | NMT committed MB old | NMT committed MB **new** |
|---|---|---|---|---|---|---|---|---|
| rest (60 s) | 245 [241-262] | **267** [240-281] | 26 (after GC) | 26 (after GC) | 93 [80-93] | 77 [76-94] | 254 [241-254] | 238 [237-255] |
| warm (C2 sweep) | 584 [527-639] | **607** [534-657] | 55 (after GC) | 55 (after GC) | 414 [321-672] | 364 [290-390] | 612 [515-875] | 562 [484-586] |
| realistic-end (10 min) | 456 [420-470]; peak 739 [704-766] | **445** [423-457]; peak **704** [568-707] | 94 before GC | 105 before GC [72-255] | 480 [210-535] | 227 [224-534] | 681 | 420 [416-734] |
| stress peak | **761** [722-869] | **834** [652-837] | | | | | | |
| stress-end | 756 [712-763] | **522** [520-562] | 139 before GC | 96 before GC [85-114] | 612 [611-738] | 297 [280-632] | 816 | 492 [475-835] |
| recovery +1 min | 756 | **522** [520-562] | 139 | 96 | 612 | 297 [280-632] | 816 | 492 |
| recovery +5 min | 757 [712-763] | **464** [436-521] | 139 | 70 before GC [58-74] | 612 | 254 [204-277] | 816 | 448 [398-471] |
| recovery +10 min | 762 [712-763] | **414** [405-416] | 140 | 60 before GC [56-62] | 612 | 207 [200-214] | 846 | 399 [393-406] |
| +10 min, after GC.run | | | 56 | 46 | (old row read before the GC; see baseline correction) | 207 | | 399 |

> **Amended after code review (2026-10-21, code-review #1):** the new-image *committed 207* and *NMT 399* in the row above were read by `measure.sh` **before** its `GC.run`. They are the pre-GC values, the same ordering defect `baseline.md` corrected for the old image. Only the heap-used-after-GC figures (56/46) are post-GC. No post-GC committed or NMT figure exists for the new image.
| worst (2 x 5,000 it + ingest nfl, nba) | peak **1,044** [966-1,048] | peak **956** [936-1,009] | used peak ~575 | used peak 544, 570, 456 (3 reps) | 864 | 684 [621-746] | 1,078 | 909 [839-972] |

Stress call mix (JRE image, 2xx/429): old 21/39, 20/40, 18/42; new 30/30, 30/30, 26/34.

## Success criteria

**SC-003' (peak <= old; 2xx >= old; 429 <= old; median of 3): FAIL on the stress peak, pass on the rest.**
- realistic peak: new 704 [568-707] vs old 739 [704-766]: pass. Realistic 2xx 20/20 and 429 0 in both.
- stress peak: new **834** [652-837] vs old **761** [722-869]: the new median is **higher (+73 MB, +10%)**, so the criterion as written fails. The ranges overlap
  (new min 652 is below old min 722) and the old set has an 869 outlier, so this may be noise at n=3; it is not shown to be. The stress peak is the transient before the
  periodic GC acts; the flag can lower recovery, not a peak.
- stress 2xx: new 30 [26-30] vs old 20 [18-21]: pass. 429: new 30 [30-34] vs old 40 [39-42]: pass.

**SC-004' (container back within 20% of pre-load rest 5 min after stress): FAIL, but much better than the old settings.**
- new JRE: +5 min **464** [436-521] against rest **267** [240-281]: **+74%**. At +10 min 414 (+55%). The old settings: +5 min 757 against rest 245: +209%, and no movement by +10.
- JDK image (heap view): committed heap at +5 min 254 [204-277] (old 612) and at +10 min 207 (old 612) against 77 at rest; heap used 70 and 60 (old 139).
- Where the remainder lives (measured): at +10 min the JDK container is ~426 MB while committed heap is 207 MB and NMT total committed is 399 MB; at rest NMT is 238 MB. So the non-heap
  side (metaspace, code cache, GC structures, arenas) grew after the load and is not given back by this flag. In the T009 sweep, with the flag set after the stress run, +6 min was 335 MB
  (rest 254, +32%), i.e. also not within 20%. **No setting measured in the sweep met the 20% bar (best: 15000 at +21%).** 5 min and 20% are the spec's own guesses; I did not change them.
- Recovery is slower in this profile than in the sweep. Likely reason (a reading of the numbers, not separately tested): the periodic cycle here follows a 10-minute realistic phase and a warm cache, and the JRE image carries more resident non-heap.

**SC-006' (cold first page and resim p50/p95 no worse than +10%): PASS (faster, within the caveat that old and new did not run interleaved).**
- Cold first request after a restart, JRE images, 3 fresh containers each, seconds (median [min-max]): `/api/drafts/{nba}/board` old 0.466 [0.438-0.500], new 0.431 [0.411-0.563] (-8%);
  `/analysis` old 0.785 [0.719-1.106], new 0.728 [0.683-0.897] (-7%); `/stats?window=SEASON` old 2.88 [2.56-3.20], new 2.94 [2.78-2.96] (+2%).
- realistic resim (JRE, 20 calls x 3): p50 old 921 [913-956] ms, new **684** [636-780] (-26%); p95 old 1,482 [1,129-1,617], new **1,012** [931-1,552] (-32%).
- stress resim (2xx calls only): p50 old 3,181 [3,128-3,718], new **2,575** [2,513-2,850] (-19%); p95 old 4,878 [4,261-5,741], new **4,343** [4,086-5,401] (-11%).
  The new runs admitted more calls (30 vs 20), so the populations differ, and old and new are hours apart; I do not claim the flags made resims faster.
- worst-case sims (2 concurrent 5,000-iteration, JRE): old p50 15.5-16.6 s, new 12.5-14.5 s.

**SC-007' (suite 0 failed, 0 skipped; C2 sweep identical): C2 PASS; suite FAIL as written (1 failed, 1 skipped, both pre-existing and unrelated).** See T014 below.

**SC-008 (worst case, no OOM, GC time stated): PASS.**
- `worst` x3 in the profile runs (JRE and JDK): `OOMKilled=false`, `restarts=0` in all 6 containers; both sims and both ingests returned 2xx. JRE container peak 956 [936-1,009] MB of the 2 GB limit
  (old: 1,044 [966-1,048]). Heap committed peak 621-746 MB of the 1,024 MB ceiling.
- Separate runs with `JAVA_OPTS=-Xlog:gc` (that run only), 3 each, same workload (2 x 5,000 iterations, ingest nfl then nba), pauses summed from the `Pause` lines:

  | image | run | GC pauses | total pause | longest | Full GCs | OOM | container at end |
  |---|---|---|---|---|---|---|---|
  | new | 1 | 97 | 1,371 ms | 10.0 ms | 0 | no | 910 MiB |
  | new | 2 | 101 | 1,218 ms | 93.4 ms | 0 | no | 865 MiB |
  | new | 3 | 96 | 1,462 ms | 9.8 ms | 0 | no | 840 MiB |
  | old (with -Xlog:gc) | 1 | 74 | 1,242 ms | 93.0 ms | 0 | no | 973 MiB |
  | old | 2 | 93 | 1,162 ms | 9.7 ms | 0 | no | 925 MiB |
  | old | 3 | 69 | 1,186 ms | 9.7 ms | 0 | no | 972 MiB |

  Each run lasted roughly 12-16 s, so total pause is about 8-10% of wall clock in both; the new median total (1,371 ms) is 15% above the old (1,186 ms), spread over a few more young collections under the smaller ceiling.
  "Container at end" is a single reading after the run, not a peak.
- Ceiling check: 1,024 MB heap + ~240-250 MB measured non-heap at rest stays below 2 GB; the highest container reading measured was 1,009 MB.

**FR-012: PASS** (startup log above; `JAVA_OPTS` adds, does not replace; empty value works).

## T012: MALLOC_ARENA_MAX=2 (summary; table in `baseline.md`)

Rest 236 vs 241 MB and +5 min 329 vs 326 MB (A vs B medians, 3 reps). Saving 3 MB at +5 min and none at rest, against the 25 MB threshold: **not added** (measured).

## T014: equivalence

**Backend suite** (`./gradlew test`, 2,058 tests across 263 result files; counts from `build/test-results/test/*.xml`).
Environment: the ITs hardcode `jdbc:postgresql://localhost:5433/draftsim` and write fixtures, and the brief forbids writing the shared `draftsim`, so the suite ran in a Linux Gradle container
(`gradle:8.14-jdk21`, worktree mounted) sharing the network namespace of a **throwaway Postgres 17 (`bk041-testpg`, listening on 5433 inside its own namespace)** that held a `pg_dump` copy of `draftsim_041`.
`draftsim` and host :5433 were never touched. The throwaway container was removed.
- First run, empty schema (Flyway only): 2,058 tests, 12 failed, 41 skipped. Those were ITs that need real league data. Not used.
- Second run, with the `draftsim_041` data: **2,058 tests, 1 failed, 1 skipped.** `JvmSettingsLoggerTest`: 2 tests, 0 failed, 0 skipped.
  - failed: `BoardRepositoryLatestBeforeIT.theDraftRowCarriesTheScheduledStartTimeFromBothQueries` (hard-coded `2026-10-10T19:15:08Z` start time that this data no longer holds);
  - skipped: `PlayerStatsLeaderboardReadIT.aSeasonThatFellBackReadsTheRequestedSeasonsDraftAndAdp` (assumption "the 2026 draft has been held since this was written").
  - Both are **pre-existing**: the same two classes run on a clean `git archive` of `HEAD` (`c987b92`, none of this change) against the same data gave the same 1 failed and 1 skipped (16 tests).
    They are data/date drift, not caused by the JVM settings. **The bar (0 failed, 0 skipped) is not met**; fixing those two tests is outside this spec and I did not touch them.

**C2 sweep** (`scripts/memory/c2-compare.py`): every URL in `c2-urls.txt` (38 GETs, both sports) plus `POST /api/sims` (`draftSleeperId` 1339351318128517120, `mySlot` 7, `iterations` 500, `seed` 41,
`startState` = first 30 picks), run against `bk041:main` (:8098) and `bk041:041` (:8099) at the same time on the same `draftsim_041`.
- **39 of 39 endpoints identical**: same status code (200 everywhere) and equal parsed JSON bodies. **Excluded JSON paths: none** (no timestamp or tick field differed between the two runs).
- **Amended after code review (2026-10-21, code-review #4):** that sweep doesn't repeat cleanly. A re-run gave 38/39: NFL `/analysis` `.projections.rosters[].total` came back 1156.6 vs 1156.7. The old image flips the same way across its own restarts, so the cause is live Sleeper roster data cached at container start, not the JVM flags. Named exclusion for any future run: `.projections.rosters[].total` on `/analysis` (live upstream data, FR-003). Also (#5): the comparator counts matching error bodies as identical. All 39 were 200 in the recorded run, but ~7 endpoints return near-empty bodies, so they prove little.
- Sanity: comparing `bk041:main` against itself also gave 39 of 39 identical, so the fixed-seed sim is deterministic across calls (the `/api/sims` body is 209,747 bytes).

## Tasks
T012 and T013 done. T014's C2 half is done and passes; its suite half did not meet the 0-failed/0-skipped bar (above), so T014 is left unchecked in `tasks.md`.
