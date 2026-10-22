# Code review: spec 041 (JVM settings), bug hunt (T018)

**Reviewed**: 2026-10-21. Covers the `Dockerfile` diff, `config/JvmSettingsLogger.java` and its test, `scripts/memory/*`, and the claims in
`baseline.md`, `verification.md`, `spec.md`, `plan.md` and `tasks.md`. All checks ran against the existing images `bk041:main`, `bk041:041` and
`bk041:041-jdk`. Containers were named `bk041-cr-*`, ran on 8098/8099 against `draftsim_041` only, and were removed afterwards. I committed nothing and did not touch Railway.

## Verdict

**No blocker, and nothing high. The change is safe to ship once the two MEDIUM doc fixes (findings 1 and 2) are made.** The entrypoint behaves
correctly. I measured an empty `JAVA_OPTS`, an unset one, `-Xmx` override precedence, SIGTERM reaching the JVM (graceful shutdown, exit 143),
the HEALTHCHECK with a non-default `$PORT`, and the startup log line. The logger cannot leak a `-D` value. `verification.md` reports its
failures honestly: SC-003′ stress peak, SC-004′ and the SC-007′ suite bar are all marked FAIL. The defects I found are in what the docs and
the Dockerfile comment *claim*: one table row labelled "after GC" that was read before the GC, a missing warning for a smaller container limit,
an "override" rule that is wrong for the collector flag, and a C2 sweep that is not repeatable on live-sourced data.

## Findings

### 1. MEDIUM: `verification.md`'s "+10 min, after GC.run" row shows new-image committed heap and NMT read *before* the GC. This is the same defect `baseline.md` already corrected for the old image
- **Evidence (measured, from the recorded rows).** `scripts/memory/measure.sh:63-65` reads `hc` (committed) and `nmt` first, then runs `GC.run`,
  then reads `used`. The script is unchanged since the baseline correction, so the new rows have the same problem. `verification-rows.md`: every
  `recovery+10min-afterGC` row repeats the `recovery+10min` row's committed and NMT exactly (rep1 214.0/406.3, rep2 200.0/393.2, rep3 207.0/399.2).
  The `verification.md` table row "+10 min, after GC.run" nevertheless prints new committed **207** and NMT **399** under that label. Its old-side
  cell says "(old row read before the GC; see baseline correction)", which implies the new side was read after. It wasn't.
- **Fix.** In `measure.sh snap`, read `heap_field committed` and `nmt_total` again after `GC.run` and write both (or label the columns "before GC").
  In `verification.md`, mark the new 207/399 as "read before GC.run" with a dated amendment note, the way `baseline.md` did. Optionally re-take
  one after-GC reading on `bk041:041-jdk`.

### 2. MEDIUM: the Dockerfile does not warn that `-Xmx1024m` is only safe at a container limit of about 1.5 GB or more. The spec's "Container limit changes" edge case requires that warning
- **Evidence (measured).** `docker run --memory=1g -e JAVA_OPTS="-XX:+PrintFlagsFinal -version" bk041:041` gives `MaxHeapSize = 1073741824 {command line}`:
  the heap ceiling equals the whole 1 GB container. The old image in the same 1 GB container gave `MaxHeapSize = 805306368 {ergonomic}` (it scaled down).
  With about 225–260 MB of measured non-heap under `worst` (NMT committed minus heap committed: 909−684, 972−746, 839−621 in `verification-rows.md`),
  a full heap in a 1 GB box would be OOM-killed. That last point is argued: nothing filled the heap to 1024 MB. The highest heap committed measured was 746 MB.
- The comment at `Dockerfile:39-41` says "leaves headroom under 2 GB" but never says the value is tied to the 2 GB plan. Spec edge case:
  "A setting that is only safe at one limit must say so."
- **Fix.** Add one line to the `-Xmx1024m` comment: "Sized for the 2 GB Railway limit. If the limit drops below ~1.5 GB, lower -Xmx (or set
  -Xmx via JAVA_OPTS, which overrides): heap + ~250-300 MB non-heap must stay under the limit."
  Mention the same rule in the DEPLOY.md "Memory" paragraph (T015).

### 3. LOW: "override one (the last -XX wins)" is wrong for the collector. Setting another GC in `JAVA_OPTS` stops the JVM from starting
- **Evidence (measured).** `docker run -e JAVA_OPTS="-XX:+UseSerialGC" bk041:041` printed `Error occurred during initialization of VM / Multiple garbage collectors selected`
  and exited with 1. `-Xmx` and `G1PeriodicGCInterval` do follow last-wins (measured: `JAVA_OPTS="-Xmx512m -XX:G1PeriodicGCInterval=0"` gives
  `MaxHeapSize=536870912`, `G1PeriodicGCInterval=0`, both `{command line}`).
- **Fix.** Reword the comment at `Dockerfile:26-28`: "...override one (last wins), except the collector: to change it pass `-XX:-UseG1GC -XX:+Use<Other>GC`."

### 4. MEDIUM: the C2 sweep is not repeatable. One NFL field comes from live Sleeper data cached at container start, so "39/39 identical, exclusions: none" held for that run only
- **Evidence (measured).** I re-ran `python -I scripts/memory/c2-compare.py http://localhost:8098 http://localhost:8099` on fresh `bk041:main`/`bk041:041` containers
  and got **38/39**: `DIFF 200 GET /api/leagues/1346366555759341568/analysis` at `.projections.rosters[].total` (1156.6 vs 1156.7, same byte length).
  Each container returns the same value on every call. Across restarts, though, the **old image flips too**: `bk041:main` gave 1156.6, 1156.6, then 1156.7, and `bk041:041`
  gave 1156.7 twice, changing with *when* the container started. `LeagueAnalysisService` builds rosters through `LeagueRosterSource` from the
  live Sleeper client (`LeagueAnalysisService.java:98`). The likely cause is NFL roster data changing mid-day. That is argued: I did not
  identify the exact source. **The JVM flags do not cause it.**
- The "sanity: main against itself" check in `verification.md` used the same two running containers, so it could not catch this.
- **Fix.** In `verification.md`, name live-sourced fields or endpoints (Sleeper rosters → `analysis.projections`, and anything else read through
  `SleeperClient`) as exclusions, or note that old and new must be started together and compared at once. Record this re-run's 38/39 with
  that explanation. Optionally make `c2-compare.py` restart-robust by running old-vs-old across two container starts as the sanity arm.

### 5. LOW: `c2-compare.py` passes identical error bodies, and several of the 39 endpoints compare near-empty bodies
- **Evidence (code + measured).** `c2-compare.py:44-53` treats any equal status as comparable, so a 401/404/500 on both sides counts as SAME. In my
  re-run every status was 200, so that did not happen. Several bodies carry almost nothing, though: the NFL league's `stats?window=*` and `players/7525` return
  `"available":false,"reason":"NOT_BASKETBALL"` (309–375 bytes), and NBA `power` returns `"entries":[]` and `availability` returns `"unavailable":{}`.
  "39 of 39 identical" overstates how much output was compared.
- **Fix.** Make the script count non-2xx as a failure, and print a count of bodies under ~500 bytes or with empty top-level collections.
  `verification.md` should say how many of the 39 carry real payload.

### 6. LOW: an `-X` flag *can* carry a secret. `JvmSettingsLogger` assumes only `-D` can
- **Evidence (argued).** `-XX:OnOutOfMemoryError=<cmd>` and `-XX:OnError=<cmd>` take shell commands (for example a `curl` to a webhook with a token
  in the URL or a header), and `-XX:Flags=`/`-XX:VMOptionsFile=` name files. All of them start with `-X` and would be logged verbatim. Nothing sets
  them today. `-Xlog:...:file=` paths are harmless.
- The rest checks out. **Component scan works**: the class is in `com.ballknowers.draftsim.config` under `DraftSimApplication`'s package, and the line
  appears in `docker logs` (measured, `PORT=9090` run: `grep -c "JVM settings:"` = 1). **It runs once per context.** `ApplicationReadyEvent` fires once per
  `SpringApplication.run`, and there is no management child context (`application.yml` has no `management.server.port`). In tests it logs once
  per cached `@SpringBootTest` context, which is harmless.
- **Fix (optional).** Redact the value of `-XX:OnOutOfMemoryError`/`-XX:OnError` (log `=<redacted>`), and add a test row for one of them. The current
  test covers the `-D` filter, which is the case that matters. It does not cover the log line itself, which is fine for this size.

### 7. LOW: `JAVA_OPTS` is word-split, with no quote handling and glob expansion on. This is pre-existing
- **Evidence (measured).** `JAVA_OPTS='-Dfoo="a b"'` fails with `Could not find or load main class b"`, and fails identically on `bk041:main`. So this
  is not a regression. An empty value and an unset one both expand to nothing and start normally (measured with `-version` and PrintFlagsFinal: both give
  `UseG1GC`, `MaxHeapSize=1073741824`, `G1PeriodicGCInterval=60000` from `{command line}`).
- **Fix.** Add a doc note in DEPLOY.md: `JAVA_OPTS` takes space-separated flags with no quoting.

### 8. LOW: recovery depends on 60 s with *no* GC. One of three new JRE reps had not recovered by +5 min
- **Evidence (measured, recorded rows).** In `verification-rows.md`, rep 3 JRE reads +1 min 519.9 and +5 min 520.6: no periodic cycle ran within 5 min of an
  idle service. Reps 1 and 2 dropped (522→437, 562→465). Plan D2′ already argues that any young GC within the interval postpones the periodic
  cycle. In production, background schedulers and traffic could keep doing that.
- **Fix.** None in code. Make sure SC-009's post-deploy read (T019) checks recovery specifically, and say in `verification.md` that the +5 min median
  hides one rep with no recovery at all.

### 9. LOW: doc inconsistencies (no visible amendment)
- `plan.md` D2′ still lists `-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30` under "Required set", and `tasks.md` T010 (checked) still prescribes
  them. The Dockerfile correctly leaves them out, per `baseline.md` "Sweep". Add an "amended after sweep" note to both.
- The Dockerfile comment says an idle service held ~600 MB "indefinitely". The measurement covered 10 min. Say "for the 10 min measured".
- `verification.md` "Ceiling check: ... the highest container reading measured was 1,009 MB" is true of the new JRE image only. The old JDK image
  reached 1,182 MB (`measure-rows.md` worst-end rep 1) and the new JDK image 993 MB. Name the image.
- `verification.md` says "Raw rows: verification-rows.md", but the cold-start timings, the `-Xlog:gc` pause table, the C2 output and the
  HEAD-only re-run of the two failing ITs are in no file I could find (repo or scratchpad). I checked the suite counts independently from
  `backend/build/test-results/test/*.xml`: 2,058 tests, 1 failure (`BoardRepositoryLatestBeforeIT`, expected `2026-10-10T19:15:08Z` but was
  `21:16:19.144Z`), 1 skipped (`PlayerStatsLeaderboardReadIT`, assumption "the 2026 draft has been held since this was written"), 0 errors. Those match the claim.
  The "pre-existing" claim is well supported by argument: the change touches no code either test exercises, and both messages are data and date drift.
  The HEAD-only re-run itself has no recorded output. Save the raw outputs, or label those sections "output not retained".

### 10. LOW: `measure.sh start` adds `-XX:MaxRAMPercentage=75` to every container unless `BASE_OPTS` is set. Harmless on `:041`
- **Evidence.** `measure.sh:47` uses `${BASE_OPTS--XX:MaxRAMPercentage=75}`. Neither `baseline-rep.sh` nor the scratchpad's `runall.sh` sets `BASE_OPTS`, so the
  `:041` runs probably had it appended (not provable: the containers are gone). Measured: `bk041:041` with `JAVA_OPTS="-XX:MaxRAMPercentage=75 ..."`
  still gives `MaxHeapSize = 1073741824 {command line}`, because `-Xmx` takes precedence. The numbers are unaffected, but the startup log on those runs would
  have listed the extra flag.
- **Fix.** Default `BASE_OPTS` from the image tag, or require it explicitly.

## Checks that passed (measured, this review)

| check | command (abridged) | result |
|---|---|---|
| empty `JAVA_OPTS` (image default) | `bk041:041`, `... $JAVA_OPTS -XX:+PrintFlagsFinal -version` | `JAVA_OPTS=[]`; G1, 1 GiB, periodic 60000 from `{command line}` |
| unset `JAVA_OPTS` | `unset JAVA_OPTS; exec java ... $JAVA_OPTS ...` | same as above |
| last-wins override | `JAVA_OPTS="-Xmx512m -XX:G1PeriodicGCInterval=0 ..."` | `MaxHeapSize=536870912`, `G1PeriodicGCInterval=0` |
| G1 pinned in a small box | `--memory=1g --cpus=1` | `UseG1GC=true {command line}`, `UseSerialGC=false` |
| SIGTERM | `docker stop -t 30` on the real entrypoint | PID 1 is `java` (`exec` works); log shows `Commencing graceful shutdown` → `Graceful shutdown complete` → Hikari closed; exit **143**, stop took <1 s |
| HEALTHCHECK + `$PORT` | `-e PORT=9090 -p 8098:9090` | app listened on 9090; Docker health `healthy`, check exit 0 |
| startup log | `docker logs` | one line: `maxHeapMb=1024 gc=[G1 Young Generation, G1 Concurrent GC, G1 Old Generation] availableProcessors=2 flags=[-XX:+UseG1GC, -Xmx1024m, -XX:G1PeriodicGCInterval=60000]` |
| ceiling vs 2 GB | rows in `verification-rows.md` | highest new reading 1,009 MB (JRE), heap committed ≤ 746 of 1,024, no OOM. 1,024 + ~260 non-heap ≈ 1.3 GB < 2 GB (arithmetic; heap never filled) |
| medians in `verification.md` | recomputed from `verification-rows.md` | rest 267, stress peak 834, stress-end 522, +5 min 464, +10 min 414, worst 956: all match |
| SC verdicts | read against the data | SC-003′ (stress peak) and SC-004′ correctly FAIL; SC-007′ suite correctly FAIL; SC-008 PASS supported; SC-006′ PASS stated with the not-interleaved caveat |
| GC.run correction | `baseline.md` | made visibly in place (struck through + dated) and consistent with the Sweep's after-GC numbers; only the new-side repeat (finding 1) is missing |


## Fixes applied (2026-10-21, parent session)

| # | Severity | Fix |
|---|---|---|
| 1 | MEDIUM | `verification.md`: an "amended after code review" note under the row says committed 207 / NMT 399 are pre-GC readings. No post-GC figure exists for the new image. Not re-measured. |
| 2 | MEDIUM | `Dockerfile`: the `-Xmx1024m` comment now says it's only safe at a ~1.5 GB+ limit and to lower it with the limit. Same warning in `DEPLOY.md` "Memory". |
| 3 | LOW | `Dockerfile`: "last -XX wins" corrected. It holds for values, not for the collector (a second GC flag refuses to start). It also says values with spaces can't be quoted. |
| 4 | MEDIUM | `verification.md`: names the flaky path `.projections.rosters[].total` on `/analysis` (live Sleeper data, same flip on old image) as an exclusion for future runs, and records the 38/39 re-run. |
| 5 | LOW | `verification.md`: notes the comparator treats matching error bodies as equal and that ~7 bodies are near-empty. Comparator not changed. |
| 6 | LOW | `JvmSettingsLogger`: drops `-XX:OnError` / `-XX:OnOutOfMemoryError`. New test `dropsTheFlagsThatTakeAShellCommand`, and `JvmSettingsLoggerTest` passes 3/3. |
| 7 | LOW | Not fixed. Pre-existing on main. Documented in the Dockerfile comment (#3). |
| 8 | LOW | Not fixed. It's a production question. Carried into T019 / SC-009: check post-spike recovery in minute-resolution metrics, not only the median. |
| 9 | LOW | plan.md and tasks.md T010 marked: ratio flags dropped. Dockerfile "indefinitely" changed to "for 10+ min (all we measured)". The unsaved raw outputs stay unsaved, as recorded here. |
| 10 | LOW | Not fixed. Measured harmless (`-Xmx` overrides it). |

Images `bk041:041` and `bk041:041-jdk` predate fixes #2, #3 and #6. Those are Dockerfile comments plus the
logger filter, and none of them changes JVM flags or memory behaviour. So the measurements stand,
but the images are no longer byte-for-byte this tree.
