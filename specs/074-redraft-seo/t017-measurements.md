# T017 and T018: season summary measurements (2026-11-17)

Measured, not estimated. Every number below came from the backend built from this worktree, running on port 8081 against the feature's own clone `draftsim_074` (Postgres 5433). The server was started with `./gradlew bootRun`, which runs the JVM with `-XX:TieredStopAtLevel=1` (the C1 compiler only) and the JVM's default maximum heap of 4.2 GB, **not** production's `-Xmx1024m`. So the times are probably a little slower than a production JVM would give, and the heap numbers say nothing about whether 1 GB is enough under a load of other requests; they say how much the summaries themselves cost.

## What existed locally

- NBA: 47 offered seasons, 1970-71 to 2016-17 (`GET /api/history/public/seasons?sport=NBA` returned 47). 991,583 player-game rows. The largest season by rows is 2016-17 (26,049).
- **NFL: no seasons imported.** Nothing in this file says anything about football pages measured against real data. Football logic is covered by unit tests on constructed data only (`SeasonSummaryServiceTest`, `HistoricalBoardBuilderRankedCountTest`).

## T017: cost of a cold summary

Cold means the first request after the backend started for that season (the cache is in memory).

| What | Result |
|---|---|
| Cold `GET /api/history/public/seasons/NBA/2016-17` (largest local season) | 1,267 ms for curl (1,340 ms wall); the server logged 1,196 ms to compute. Response body 8,613 bytes. |
| Same request again | 6 ms (served from the cache) |
| The other 46 seasons, one after another, each cold | 39,749 ms wall in total for the 47 sequential requests (2016-17 was already cached, so 46 of them were cold); no failures |
| Sum of the server's own "computed in" log lines, all 47 seasons | 38,646 ms; mean 822 ms; max 1,196 ms (2016-17); min 476 ms (1972-73) |
| Per-season compute times (ms) | 1970-71 481, 1971-72 478, 1972-73 476, 1973-74 507, 1974-75 499, 1975-76 557, 1976-77 669, 1977-78 645, 1978-79 637, 1979-80 643, 1980-81 701, 1981-82 721, 1982-83 729, 1983-84 714, 1984-85 716, 1985-86 722, 1986-87 751, 1987-88 761, 1988-89 796, 1989-90 875, 1990-91 818, 1991-92 864, 1992-93 878, 1993-94 897, 1994-95 930, 1995-96 914, 1996-97 900, 1997-98 936, 1998-99 570, 1999-00 963, 2000-01 922, 2001-02 954, 2002-03 972, 2003-04 850, 2004-05 1084, 2005-06 1029, 2006-07 947, 2007-08 1049, 2008-09 976, 2009-10 942, 2010-11 973, 2011-12 761, 2012-13 1158, 2013-14 1091, 2014-15 1016, 2015-16 978, 2016-17 1196 |

The dream roster (the greedy search) is inside those numbers. It was not timed on its own.

### Heap (`jcmd <pid> GC.heap_info`, after `jcmd <pid> GC.run`)

| Moment | Used heap |
|---|---|
| Before the single 2016-17 request | 33.7 MB |
| After it | 34.4 MB |
| Before the 46-season fill | 34.4 MB |
| Peak seen while the fill was running (no GC forced) | 120.8 MB (this includes garbage) |
| After the fill and a forced GC | 35.0 MB |

So the 47 cached summaries retain about 0.6 MB, which is the design intent (only the small records are cached, never boards or stat lines). The 120 MB peak is short-lived garbage from boards and stat lines; production's 1 GB heap was not exercised.

### What this says and does not say

- A whole-build warm-up of the NBA seasons is about 40 s on a laptop's local database. Football is **not measured** (no data); it has about 6k week rows a season against about 26k for a modern NBA season, which suggests it is cheaper, but that is a guess. Production's database is across a network and is not the local one.
- The concurrency bound is two seasons at a time (`SeasonSummaryService.MAX_CONCURRENT`, hand-set). Single flight (eight threads asking for one cold season get one page object) is proved by `RedraftPublicMvcIT.manyCallersForOneColdSeasonShareOneComputation`. It was not load-tested with other traffic at the same time.
- Not measured: production's 56 NBA seasons (1970 to 2025, nine more than local), football, and anything under `-Xmx1024m`.

## T018: the page's numbers against a real game of that season

Method (`t018.py`, scratchpad, run once against the live server): for each season, read the public season page; then play 5 real games of that season (slots 1, 4, 6, 9, 12; created with `seasonStart`, so `chosenSeason` is true; auto-finished). From each finished game take every player the game reports a season total for (`hindsight.picks[].seasonValue`, `pickFinishes[].seasonValue`, `biggestBust.seasonValue`) and compare it to the points the page prints for the same player id wherever the page names him. For the positional rank, compare each `topByPosition` row's `finishRank` with the same player's `pickFinishes[].finishPositionRank` in the game.

| Season | Players the page names | Points comparisons (a player can appear in several games) | Distinct players compared | Positional-rank comparisons | Mismatches | Board (ranked) |
|---|---|---|---|---|---|---|
| NBA 1985-86 | 35 | 59 | 23 | 13 | 0 | 325 (298) |
| NBA 2000-01 | 33 | 52 | 16 | 11 | 0 | 441 (406) |
| NBA 2016-17 | 36 | 44 | 19 | 11 | 0 | 486 (433) |

Every compared number agreed exactly (tolerance 1e-9). The 15 test games were deleted afterwards.

What this does **not** show:

- Football: not run (no local NFL seasons).
- The overlap is the part of the page that a game happens to also report, mostly the hindsight roster and the user's picks. It is 16 to 23 distinct players per season out of 33 to 36 the page names. The finish rank among all players is not compared, because no game reports one (PR-15): it is pinned by `SeasonSummaryServiceTest` instead.
- The positional comparison covers only game picks that are also in a position's top five (11 to 13 per season).
- Three seasons of one sport is a spot check, not an audit of 82.
- Games and page use the same board builder and scoring code, so this proves they agree and that the page's own listing logic did not corrupt the numbers; it does not prove the scoring is right.
