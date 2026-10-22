# Baseline: the CURRENT JVM settings (spec 041, T001-T008)

Every number is labelled **measured** (run in this session, raw rows in `measure-rows.md`) or **estimated**
(arithmetic or extrapolation from a measurement). Nothing here comes from production except where it says so.

## Header (C3)

- **Date**: 2026-10-20 (evening, local time; the three kept repetitions ran 22:16 to 23:27 CDT).
- **Code under test**: `origin/main` at `c987b92` (the commit the task named), built with its unchanged `Dockerfile`
  (`bk041:main`, `eclipse-temurin:21-jre-jammy`). `origin/main` has since moved to `afc9b54`; not rebuilt.
  Heap and NMT readings come from `bk041:main-jdk` (same `/app`, `eclipse-temurin:21-jdk-jammy`; the base differs, review F2).
- **Image `JAVA_OPTS`**: `-XX:MaxRAMPercentage=75` (analyze I1: on `:main`, `JAVA_OPTS` replaces the image default, so every
  container was started with it explicitly; `-XX:NativeMemoryTracking=summary` added on the JDK container only).
  **Verified** with `jcmd 1 VM.flags` on `bk041:main-jdk`: `MaxHeapSize=1610612736`, `UseG1GC`, `MaxRAMPercentage=75`.
  Defaults in force (not set by us, read from the review's `PrintFlagsFinal`): `G1PeriodicGCInterval=0` (off), `MaxHeapFreeRatio=70`.
- **Container**: `--memory=2g --memory-swap=2g --cpus=2`, JRE on :8098, JDK on :8099, both running the same load at the same time,
  except `worst`, which ran one container after the other. Host: Docker Desktop VM, 12 CPUs, 8 GB, cgroup v2.
- **Database**: scratch DB `draftsim_041` in `draftsim-pg` (:5433), restored from `pg_dump` of `draftsim` at **2026-10-20T23:01:49Z** (UTC
  stamp taken just before the dump; the restore finished a minute later). Nothing touched `draftsim`, :5432, Railway or production.
  `select sport, season, count(*) from player_game group by 1,2` (**measured**, identical in the copy and the source):
  nba 2024 = 28,798; nba 2025 = 29,143; nfl 2025 = 26,490; nfl 2026 = 6,229.
- **Side effects, stated**: the `worst` profile calls the **real Sleeper API** (`POST /api/ingest/players?sport=nfl`, then `nba`)
  and writes only to `draftsim_041` (`player` rows: 4,387 nfl, 2,133 nba; search-rank board; status capture).
- **Workload**: draft `1339351318128517120` (NBA 2026 "Ball Knowers", status `complete`) with the first 30 recorded picks
  passed as `startState` (otherwise a complete draft replays all its picks and there is nothing to simulate),
  `mySlot=7`, fixed `seed=41`. 12 distinct league members (`league_member`, league id 210) as `X-Sleeper-User`.
  The live poller is **left out** (no drafting draft exists locally).
- **Repetitions**: 3 full repetitions of every profile (medians and [min-max] below). Discarded earlier attempts, so nobody wonders:
  a pilot repetition (peak sampler too coarse), and a second attempt where the machine stalled ~3 h mid-run and the streaming sampler read 0.0.
- **`realistic` was shortened to 10 min per repetition** (permitted by the task; the spec says 20): 20 calls of 500 iterations, one every 30 s.
- **Container-MB peaks** come from a streaming `docker stats` sampler (~1-2 s cadence): a spike shorter than that can be missed.
  The JDK container's MB runs ~100 MB above the JRE's under load (NMT overhead, JDK base), so container MB is quoted from the JRE image.

## Headline table (C3 format; medians of 3, [min-max])

Container MB is from the JRE image `bk041:main`. Heap and NMT columns are from `bk041:main-jdk`. "After GC" is only taken at `rest`,
`warm` and the final forced-GC row (a `GC.run` mid-recovery would hide what the JVM does on its own); the other rows show heap used **before** any forced GC.

| profile | container MB (docker stats) | heap used MB | heap committed MB | NMT committed MB | note |
|---|---|---|---|---|---|
| rest (60 s after ready) | **245** [241-262] | 26 [26-26] after GC | 93 [80-93] | 254 [241-254] | measured |
| warm (after the C2 sweep, 38 GETs) | **584** [527-639] | 55 [55-55] after GC | 414 [321-672] | 612 [515-875] | measured; the sweep alone pushes the container +340 MB |
| realistic-end (10 min) | 456 [420-470]; peak 739 [704-766] | 94 [74-176] before GC | 480 [210-535] | 681 [404-736] | 60/60 calls 2xx over 3 reps; p50 ~0.9 s, p95 1.1-2.0 s; measured |
| stress peak (12 members x 500 it x 5 rounds) | **peak 761 [722-869]** | | | | calls 2xx/429 per rep: 21/39, 20/40, 18/42 (JDK container: 20/40 in all 3) |
| stress-end | 756 [712-763] | 139 [139-208] before GC | 612 [611-738] | 816 [815-947] | JRE p50 3.1-3.7 s, p95 4.3-5.7 s |
| recovery +1 min | **756** [712-763] | 139 [139-208] before GC | 612 [611-738] | 816 [815-947] | measured |
| recovery +5 min | **757** [712-763] | 139 [139-208] before GC | 612 [611-738] | 816 [815-947] | measured |
| recovery +10 min | **762** [712-763] | 140 [139-208] before GC | 612 [611-738] | 846 [816-947] | measured |
| recovery +10 min, after forced GC | (JDK container 836) | **56 [56-56]** | 612 [611-738] | 816 [815-947] | measured: the live set. **Amended 2026-10-21 (T009): the committed/container/NMT cells on this row were read BEFORE the `GC.run` (`measure.sh snap` reads them first), so they do not show the effect of the GC. A forced `GC.run` does uncommit: see "Sweep" below.** |
| worst (2 x 5,000 it + ingest nfl then nba) | **peak 1,044 [966-1,048]**; end 1,040 [969-1,046] | used peak ~575 [518-579], includes garbage | 864 [834-958] | 1,078 [1,064-1,172] | no OOM, 0 restarts in all 3; both sims 2xx; sims p50 15.5-16.6 s (JRE) |

The container does **not** come back down on its own: the +1 min reading equals the +10 min reading.

### Spreads (the SC-001' check)

- Container MB at `rest` [241-262] and `recovery` [712-763]: well inside +/-20% of the median. `warm` [527-639] is -10%/+9%.
- Committed heap is the loose column, as review F7 predicted: `warm` 321-672 (-22%/+62%), `realistic-end` 210-535.
  **It does not meet +/-20%**; quote the median but do not trust one run's value.
- Heap used after GC at rest (26 MB) and warm (55 MB) was identical in all 3 runs (+/-10% holds).

## Do the current settings return memory after a spike? **No (measured).**

- The JRE container sat at 756 / 757 / 762 MB at +1 / +5 / +10 min after `stress`; rest was 245 MB (**+517 MB, about 3x rest**).
  SC-004' (within 20% of rest at +5 min) is missed by a wide margin.
- Inside the JVM: after `stress`, **committed heap stayed at 612 MB for ten minutes while live data was 56 MB** (about 556 MB of
  heap the JVM holds and does not need). With `G1PeriodicGCInterval=0` G1 uncommits only after a concurrent cycle or a full GC; an
  idle service triggers neither. ~~A forced `GC.run` freed used heap (140 to 56 MB) but left committed at 612 MB, so the unused heap is held, not just uncollected.~~ **Wrong, corrected 2026-10-21 (T009):** that row's committed figure was read before the `GC.run`. Re-measured: after a stress run, `GC.run` took the heap from 833 MB committed to 102 MB and the JDK container from 1,006 MB to 290 MB (single probe), and 9 sweep trials gave a container of 256-312 MB after `GC.run`. The memory is held only because nothing triggers a GC on an idle service; one GC releases it.
- Caveat on the earlier "ratchet" story: the *mechanism* (committed heap stays after a spike) is confirmed locally, but production's
  fine-resolution series (review F1) rests at 300-420 MB and spikes at deploys, and the local numbers are larger (see Calibration).

## FR-004 threshold (T008)

Warm resident memory (measured median, JRE container) = **584 MB**. 5% = 29 MB; the fixed cap is 25 MB; the smaller is **25 MB**.
(5% and 25 MB remain the spec's labelled guesses. Using production's ~365 MB rest instead would give 18 MB.)

## Per-holder table (C3)

Threshold 25 MB. The decision is bounded by the rescope (JVM settings only): anything that is not a JVM setting is "leave alone / out of scope".

| holder | retained MB | measured/estimated | method | threshold MB | decision |
|---|---|---|---|---|---|
| H1 JVM heap policy (committed-but-unused heap after a spike) | ~556 (612 committed - 56 live) | **measured**, 3 reps (committed 611-738) | `jcmd GC.heap_info` at +10 min, then `GC.run` | 25 | **change via JVM setting** (periodic GC + free ratios; sweep in T009) |
| H2 Monte Carlo results, one 5,000-iteration run | ~89 (median live used 144 mid-run vs 55.2 base; samples 118-310) | **measured**, noisy: 9 samples, `GC.run` while the run allocates. Review F3 measured 87.5 MB by direct probe, which agrees | `jcmd GC.run; GC.heap_info` at 1.5-4 s into a 5,000-iteration run; base = after a 500-it sim + GC | 25 | **leave alone / out of scope** (FR-005 deferred). Transient: garbage once the run ends |
| H3 Season box-score cache | 23.1 for NBA 2025 (29,143 rows) = ~831 B/row. Projection (NFL 2025 + 2026 and NBA 2024/25/26 at full season, ~140k rows): ~116, plus one reload in flight ~24: **~140** | per-row figure **measured once** (an upper bound: everything the first stats page left resident); the ~140 is an **estimate** (F11) | heap after GC before/after `GET /stats?window=SEASON`. The NFL stats call retained +0.0 MB; unexplained, not re-measured | 25 | **leave alone / out of scope** (cache limits deferred). Large enough to matter later |
| H4 value memo | not measured | **estimated** tens of KB/day (R4) | none | 25 | leave alone / out of scope |
| H5 player ingest, nfl | live peak ~+0 to +4 over a 55.7 base (55.5-59.9); allocation peak 89-155 incl. garbage | **measured** (3 plain-sampling + 2 GC-loop runs, 30 samples each; ingest 2.4-2.8 s) | tight `jcmd GC.heap_info` loop, and a `GC.run` loop, during `POST /api/ingest/players` | 25 | **leave alone / out of scope**. The old "256 MB may OOM" guess is not supported (below) |
| H5 player ingest, nba | live peak ~+3 to +11 (58.5-67.0); allocation peak 93-103 | **measured** (same; ingest 0.7-0.8 s) | same | 25 | leave alone |
| H6 projection memo (LRU 64) | not measured | bounded by code | none | 25 | leave alone |
| H7 poller/lock maps | not measured | tiny by code | none | 25 | leave alone |
| H8 frontend `serve` | 20 | **measured in production** earlier (R0), not re-measured here | `railway metrics` (read-only) | 25 | leave alone / out of scope |
| H9 allocation churn | not re-measured (spec: 1.1 MB/iteration) | **estimated** (review) | none | 25 | out of scope |
| H10 JVM non-heap (NMT) | rest: total committed 250 MB = heap 89 + metaspace 52 + GC 39 + symbol 21 + code 19 + shared class space 13 + class 9 + NMT 4 + thread 2 + internal 1. Warm: 379 MB = heap 167 + metaspace 64 + GC 42 + code 24 + symbol 23 + shared 13 + class 11 + arena 16 + NMT 4 + thread 3 | **measured** on a fresh JDK container (rest 45 s after ready; then after the warm sweep) | `jcmd VM.native_memory summary` | 25 | non-heap is ~160 MB at rest and ~210 MB warm: **not changeable by the in-scope settings** except the optional `MALLOC_ARENA_MAX` (T012). glibc arenas are not in NMT |

H5 reading: against a 55 MB base, one NFL dump adds at most a few MB of live data at a time; it allocates 35-100 MB of garbage per run, the same
young-gen churn as everything else. Under `worst` the NFL ingest took 7-10 s with two 5,000-iteration sims running, with no OOM.

## Calibration against production (F12)

- Local `warm`, JRE container, median **584 MB** (527-639). Production's post-deploy plateau (R0/F1): **300-370 MB**. The local container is
  **~1.6-1.9x larger** and the gap is **not explained**. Candidates, none tested: the 38-request sweep hits the heaviest pages of both sports
  in a short burst so G1 sizes young gen up (committed heap went 93 to 414 MB during warm while live data went only 26 to 55 MB); production's
  mix is gentler; `docker stats` vs Railway's metric are unverified to match; this DB may hold more data than production did.
  So absolute container MB here must not be quoted as production's.
- What carries over: the mechanism (committed heap far above live data after a burst, never returned) and ratios between profiles on the same
  image. Rest here (245 MB) is close to the 236 MB the review measured on Windows.

## What contradicted the spec or plan

1. **`DEPLOY.md:312` "256MB may OOM" (R5/H5)**: not supported. NFL ingest's live peak is a few MB over base and it allocates <=~100 MB; it ran fine
   inside a 2 GB container during `worst`. (Nothing was tried at 256 MB, so that exact claim is untested.)
2. **Plan D1' "recovery"**: nothing recovers. The +1 min reading equals +10 min.
3. **Stress**: 12 callers against 2 permits and a 3 s wait cap give only ~20 of 60 calls 2xx and ~40 429s in every repetition. The `stress`
   profile therefore behaves as two sims at a time. That is the baseline 429 count SC-003' must compare against.
4. **H3 NFL**: the NFL stats page left +0.0 MB retained, unlike NBA (+23.1 MB). Unexplained.
5. **Heap sampler**: the 2 s `jcmd` sampler returned only a few lines during the ~25 s stress burst, so "used peak" there is a lower bound (the
   stress-end `before GC` and committed figures are single snapshots).
6. **Local sizes are ~1.6-1.9x production's** (Calibration). R7's 18 MB threshold used production's rest; this baseline's is 25 MB (the fixed cap).
7. `origin/main` moved from `c987b92` to `afc9b54` during the session; the baseline is of `c987b92` as instructed.

## Decision for the implementation strategy

H1 clears the threshold by about 20x (556 MB vs 25 MB) and the current settings do not return it, so Phase 3 (T009 onward) is justified.


## Sweep (T009, run 2026-10-21)

**Method (measured).** One fresh `bk041:main-jdk` container per trial (old settings: `-XX:MaxRAMPercentage=75`, NMT on; JDK image, so container MB runs
about 45 MB above the JRE image; compare within this table only). Rest 45 s, then `stress` (12 members x 500 it x 5 rounds), then the flag is set with
`jcmd 1 VM.set_flag`, then container MB and heap (committed/used MB) at +1 and +6 min, then a second `stress` for the resim p50/p95 (2xx calls only; 60 calls, 12 callers
against 2 permits, so the number of 2xx per round also varies). Two trials ran at once (8098/8099). 3 trials per setting; medians below, raw rows in
`scripts/memory/sweep.sh` output (scratchpad `sweep-rows.md`, `sweep-both.md`). `G1PeriodicGCInterval`, `MaxHeapFreeRatio` and `MinHeapFreeRatio` all accepted `VM.set_flag`
(they are `manageable`). Rest in every trial: 243-284 MB (median ~254), heap committed 73-108 MB. Stress-end 734-977 MB.

| setting (runtime) | container MB +1 min | container MB +6 min | heap committed/used at +6 min | next-round resim p50 ms (3 trials) | p95 ms (median) |
|---|---|---|---|---|---|
| none: ratio 70/40 (the defaults; periodic off) | 761 [738-763] | 762 [738-764] | 582/119, 561/78, 537/230 | 2093 [2074-2252] | 3537 |
| G1PeriodicGCInterval=15000 | **328** [319-334] | **308** [305-311] | 114/32 | 2620 [2499-2887] | 4216 |
| G1PeriodicGCInterval=60000 | 395 [378-397] | 335 [327-340] | 137/35 | 2265 [2135-2330] | 3571 |
| G1PeriodicGCInterval=300000 | 776 [750-838] | 365 [362-408] | 167/51 | 2235 [2030-2281] | 3591 |
| ratio 50/20, no periodic | not read separately | 840 [789-859] (nothing changes without a GC) | 602-655/145-281 | 2097 [2082-2215] | 3649 |
| ratio 30/10, no periodic | not read separately | 868 [811-885] | 612-685/151-466 | 2171 [2003-2247] | 3514 |
| periodic 60000 + ratio 30/10 | 395 [393-410] | 359 [335-372] | 134/36 | 2180 [2023-2295] | 3638 |

Container MB after one forced `GC.run` at +6 min, ratio trials only (heap committed after): 70/40 -> 302 [301-312] (100-104 MB); 50/20 -> 261 [256-265] (60 MB); 30/10 -> 298 [295-299] (100 MB).

**Reading (measured, n=3 per row; spreads are wide, so small gaps are not findings).**
- The mechanism is "nothing triggers a GC when idle": with the default settings the container stays at ~760 MB for 6 min; one GC (periodic or forced) brings it to 300-335 MB.
- **`G1PeriodicGCInterval` works as a runtime flag.** 15000 recovers fastest (+1 min 328) but its next stress round was the slowest in all 3 trials (p50 2.5-2.9 s vs 2.1-2.3 s; 28/28/28 calls got a 429 versus 12-21 for the others), a plausible cost of a concurrent cycle every 15 s during load (not separately measured). 300000 only recovers once the 5 min interval elapses (still 750-838 MB at +1 min). 60000 recovered to 335 MB at +6 min with resim p50 +8% and p95 +1% against the no-flag rows.
- **`Max/MinHeapFreeRatio` make no measurable difference to idle recovery** (they only act when a GC runs, and the idle service runs none), and combined with periodic 60000 they gave no gain (+6 min 359 vs 335, within spread). They set the post-GC heap size (60-104 MB after `GC.run`) but not in a monotonic way (50/20 smaller than 30/10), so I do not ship them.
- Rest on this JDK image is ~254 MB, so even the best setting leaves the container at +21% (15000) to +32% (60000) above rest at +6 min. The remaining gap is heap committed around 114-137 MB against ~80 MB at rest plus warmed-up non-heap, not recoverable by these flags. **That is relevant to SC-004' (within 20% of rest at +5 min); it is judged in verification.md on the JRE image, not here.**

**Chosen values (measured choices, not principled ones):** `-XX:G1PeriodicGCInterval=60000`; ratios left at the defaults. Why 60000 and not 15000:
15000 recovers ~25 MB further but was the only value to slow the next round's resims in all three trials. This was a judgement call between the two on 3 trials each; the numbers do not separate them cleanly.
`-Xmx1024m` and `-XX:+UseG1GC` are not part of the sweep (plan D2'); `-Xmx` is validated by the `worst` profile in `verification.md`.
The combined 60000 + 30/10 row used the runtime flag after the stress run; all other ratio and periodic trials had the other flag at its default.

## MALLOC_ARENA_MAX A/B (T012, run 2026-10-21, on `bk041:041`, JRE image)

Arm A (no env) on 8098, arm B (`-e MALLOC_ARENA_MAX=2`) on 8099, same stress load at once, 3 reps (container MB; the shipped flags in both arms):

| rep | A rest | A +5 min | B rest | B +5 min |
|---|---|---|---|---|
| 1 | 248 | 321 | 241 | 326 |
| 2 | 236 | 329 | 274 | 311 |
| 3 | 233 | 334 | 236 | 326 |
| median | **236** | **329** | **241** | **326** |

Saving at rest: -5 MB (B larger); at +5 min: 3 MB. The T008 threshold is 25 MB, so **`MALLOC_ARENA_MAX` is not added** (measured). Two earlier A/B attempts were discarded because overlapping background loops reused the container names and port; their rows were mixed and not used.
