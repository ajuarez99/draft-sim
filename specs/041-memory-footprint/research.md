# Research: Memory Footprint (spec 041)

Every finding is labelled **measured** (I ran or read something on 2026-10-20 and the number is
quoted) or **estimated** (read from code, nothing executed).

## R0. What production uses: measured, read-only

`railway metrics -p <joyful-unity> -e production` (read-only CLI, run 2026-10-20 ~16:40 UTC):

| Service | Memory (7-day summary) | Limit |
|---|---|---|
| draft-sim (backend) | **455 MB** | 2.00 GB |
| Postgres | 211 MB | 3.00 GB |
| perpetual-vitality (frontend, `serve`) | **20 MB** | 1024 MB |

The backend's raw 14-day series (4-hour buckets, in `scratchpad/mem14d.json`, not committed)
and two 1-minute windows show:

- **After each deploy the backend sits at ~300–370 MB** (e.g. 10-10 19:04–20:54: flat 364–366 MB).
- **Draft night, 10-10 20:55–22:00 UTC: it rose to a peak of 827 MB** (21:15), then oscillated
  between 476 and 692 MB for the rest of the hour.
- **It ratchets.** Between deploys the 4-hour readings step up and stay up: 10-08 319 → 980 MB,
  10-16 357 → 983 MB, 10-13 383 → 675 MB, held for 8–12 hours. Nothing in the app holds that much
  live data at rest (R2–R5 estimates sum to well under 100 MB). The likeliest reading is the JVM
  keeping heap it committed during a spike. **That is an inference**, and R1's measurement tests it.
- **The 10-09 "2.24 GB" bucket is not a real peak.** At 1-minute resolution, 10-09 had 1149 MB at
  15:08 and 1344 MB at 15:52, and deploys landed at 15:10 and 15:51. Old and new containers
  overlapping during a deploy are counted together. Production has never been near its limit from load.

**Consequence for the spec.** H8 (frontend) is measured at **20 MB**, below the FR-004 threshold
(R7). US4 is therefore resolved by measurement as "leave alone", and no frontend work is planned.

### R0 amended after review (2026-10-20, plan-review F1, re-measured by the parent session)

**The "ratchet" above is wrong.** It was read off 4-hour buckets, which carry a single deploy-overlap
sample forward. At ~4-minute resolution, 10-16 22:52 → 10-17 08:52 is **362–421 MB, flat 371 MB
overnight**. The only high sample is 912 MB at 23:00, during the 23:00/23:02 deploys. The review
found the same on 10-08 (peak 776 MB, not 980) and 10-13 (517 MB, not 675). Corrected picture:
**production rests at ~300–420 MB and spikes only transiently** (draft night 827 MB for minutes).
The R1 inference built on the ratchet ("retained heap held for hours") is withdrawn. Locally the review
measured a **30 MB live heap after full GC**, and 236 MB committed at rest of which heap is only 75 MB.
So **most of the at-rest footprint is non-heap**.

## R1. The JVM ceiling and memory that never comes back (H1): the main lever

- **Measured**: the limit is 2 GB and the service has 2 vCPU. With `-XX:MaxRAMPercentage=75`
  (`Dockerfile:26`), the maximum heap is about **1.5 GB**. With 2 CPUs and ≥1792 MB, JDK 21
  picks G1 by default.
- **Known JDK behaviour, not measured here**: G1 gives committed heap back to the OS only after a
  concurrent cycle or a full GC. A mostly idle service may never trigger either, so a spike's
  committed heap stays resident for hours. That fits R0's ratchet. `-XX:G1PeriodicGCInterval=<ms>`
  (JDK 12+, JEP 346) makes G1 run a periodic cycle when idle and uncommit what it doesn't need.
- **Decision**: (a) add `-XX:G1PeriodicGCInterval` so committed heap is returned after a spike;
  (b) replace the implicit 75% with an **explicit maximum heap**, sized from the measured
  draft-night peak heap × 2 headroom and rounded up. The number is chosen in T-baseline, not
  here (FR-007). (c) Log the effective max heap and GC at startup. Production's flags can then be
  read from the deploy log rather than inferred. That also covers the open question of whether a
  Railway variable overrides `JAVA_OPTS`, which is not checked here because listing variables
  would print secrets.
- **Rationale**: this targets the at-rest number that's billed every hour, not only the peak.
- **Alternatives**: SerialGC (smaller footprint, but stop-the-world pauses on a 2-vCPU box with 12
  live users; rejected unless measurement shows G1's own overhead is the problem). Lower
  `MaxRAMPercentage` alone (still implicit and still ratchets; rejected). Lower the Railway limit
  (out of scope; that's a plan change, and Allan's call).

**Resolved 2026-10-20 (plan-review F8): Allan confirmed there is no `JAVA_OPTS` variable on the
Railway backend service.** The Dockerfile's `JAVA_OPTS` is what production runs with. The startup log
line in (c) stays: it is how a future override would be noticed.

## R2. Monte Carlo keeps every iteration (H2)

- **Code read**: `MonteCarloRunner.run` adds every `RunResult` to `results`, and the `futures`
  list also keeps every completed `Future`, which holds its result, until `run` returns. That's
  two references to all N results. `aggregate` then reads only `picked[]` and `availableAtMyPicks`.
  It never reads `rosters` (its only reader is `DraftSimulatorTest:159`).
- **Estimated**: 10–30 KB per run, so **~50–150 MB for 5,000 iterations**, × 2 permits on 2 vCPU
  (`SimulationPermits`: `max(2, cpus/4)`). That is consistent with draft night's +460 MB, but not proven by it.
- **Decision**: fold each run into running tallies as soon as it's read (in index order, the order
  `aggregate` reads them today), and clear the future's slot. Peak memory then grows with the
  tallies (bounded by players × picks), not with iterations.
- **Identity argument**: the tallies are `HashMap`s merged with `Integer::sum`. Feeding the same keys
  in the same order produces the same maps and the same iteration order, so `BoardAssembler`'s tie
  breaks and the `survived` stream's stable sort see identical input. C1 tests this as a golden
  result on the **old** code before the change, which is stronger than this argument.
- **Alternatives**: per-worker partial tallies merged at the end. Rejected: the merge order would
  differ from today's, so HashMap iteration order could differ in a collision, and ties could break
  differently. That's a real risk to FR-003 for no extra saving at 2 workers. Dropping
  `RunResult.rosters`: after folding, each run is garbage within one iteration, so removing it saves
  only allocation churn. Left as is.

## R3. Season box-score cache (H3)

- **Code read**: no entry limit. Callers ask for `league.season()` and a fallback `dataSeason`
  (`PlayerStatsService:267,278,388,397`) and the trends season (`PlayerTrendsService:213,222`).
- **Measured** (local DB, 2026-10-20): `player_game` holds NBA 2024 (28,798 rows), NBA 2025
  (29,143), NFL 2025 (26,490) and NFL 2026 (6,229). So **at most 4–5 entries** can exist today. The
  natural bound is "seasons with stored games".
- **Estimated**: ~5 MB per season (spec 022 R6, never measured), so ~20–25 MB total.
- **Decision**: measure the per-season retained size in the baseline. If the cache's total is below
  the FR-004 threshold, record the natural bound at the declaration (FR-006, satisfied by
  documentation) and change nothing. If it's above, add an access-ordered cap of the most recent N
  seasons, with eviction that respects `markRefreshing` (spec edge case "evicted while in use":
  entries are immutable, so a holder keeps a valid view).

## R4. Value memo in the sport rules (H4)

- **Code read**: `Map<Double, Double>`, one entry per distinct ADP double, with no limit and never cleared.
- **Estimated**: a few hundred distinct ADPs per board capture. New captures add new doubles, so
  it grows by hundreds of entries a day (~100 B each), meaning **tens of KB a day**. Every deploy resets it.
- **Decision**: below any threshold. FR-006 requires a stated limit. The implementation decides by
  microbenchmark: if computing `Math.exp` directly is no slower than the boxed lookup, remove the memo
  (no cache is the simplest limit); otherwise document the growth rate at the declaration. Either way,
  the outputs are bit-identical because the same function produces the same double.

## R5. Player-dump ingest peak (H5)

- **Code read**: `PlayerIngestService:82` parses the whole ~5 MB Sleeper dump into
  `Map<String, Map<String, Object>>`. `DEPLOY.md:312` says 256 MB may OOM, which puts the transient
  peak at tens to low hundreds of MB. **Estimated**, never measured.
- **Decision**: measure the peak (used heap during `POST /api/ingest/players`). Only if it clears the
  threshold: switch to streaming parse, one player object at a time. It's an admin path that runs
  once a day, so it's last in priority. It matters only because it can coincide with draft night.

## R6. Bounded or tiny holders (H6, H7)

The LRU of 64 and the per-draft lock and listener maps are bounded and documented. **No change.**
They're listed only so FR-006's audit is complete.

## R7. Threshold, recomputed from R0

FR-004's threshold is "5% of warmed resident memory, or 25 MB, whichever is smaller". Production's
warmed rest is ~365 MB (R0), so the threshold is **~18 MB**. The local baseline replaces this with
its own warmed number. The 5% and 25 MB remain guesses.

## R8. How to measure without touching production

- **Fidelity**: build the production `Dockerfile` image and run it locally with
  `--memory=2g --cpus=2`, against the local `draftsim-pg` (:5433, NBA 2024/25 + NFL 2025/26). Read
  memory with `docker stats`, which uses the same cgroup accounting Railway reports. Windows process
  RSS isn't comparable to a Linux container's, so it's used only for quick relative checks during development.
- **Per-holder retained size**: `jcmd <pid> GC.run`, then `GC.heap_info`, before and after the
  request that fills one holder. The delta is that holder's retained size. Native memory:
  `-XX:NativeMemoryTracking=summary` + `jcmd VM.native_memory summary`, so H1's non-heap share
  (metaspace, code cache, threads) is measured, not assumed.
- **Draft-night load**: 12 concurrent `POST /api/sims/stream` against a real NBA draft, plus the
  live poller tracking it, repeated 3 times.
- **Production after deploy**: the same `railway metrics` read, 24 h and 7 d after, compared with R0.

## R9. Equivalence oracle

- **Simulations**: a golden JSON of `SimulationResult` for fixed seeds (NFL and NBA contexts, with
  and without locked picks, 1 and 5,000 iterations), written and green on the **old** code before
  `MonteCarloRunner` changes (C1). Reuses `api/GoldenJson` for comparison.
- **Pages**: the H3 change, if built, is covered by `SeasonBoxCache`'s existing tests plus an
  evict-and-reload test. The JVM flags change no code path. A live sweep of the main endpoints before
  and after on the Docker image covers the rest (C2).
