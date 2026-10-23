# C3: Baseline table format

`baseline.md` (before) and `verification.md` (after) use the same table so they can be diffed.

| profile | container MB (docker stats) | heap used after GC MB | heap committed MB | NMT committed MB | note |
|---|---|---|---|---|---|

Profiles: `rest` (60 s after ready), `warm` (after the C2 sweep), `load-peak` (max during 12
concurrent sims + track), `load-after-60s`, `load-after-10min`, `ingest-peak`.

Per-holder table:

| holder | retained MB | measured/estimated | method | threshold MB | decision |
|---|---|---|---|---|---|

Header lines: date, git sha, image `JAVA_OPTS`, DB contents (`select sport, season, count(*) from player_game group by 1,2`).
Each profile is run twice. SC-001 compares the two runs (±10%).
