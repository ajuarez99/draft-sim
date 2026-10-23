# Hand check, US4 thresholds (T030 / SC-007), 2026-10-22

**What the thresholds are fitted on.** `LockInService.thresholds(league)` pools every completed MEASURED lock-in season in the league's
chain, *the league's own season included when it is complete*. League 210 (2026, in progress) therefore learns from 2025 + 2024, and so
does league 211 (2025) itself; league 212 (2024) learns from 2024 alone. The seasons used are published as `thresholds.from`.
The population is every played game of a starter's final multi-game week with >= 5 prior games and a positive stand-in, locked or not;
`pMissed = missed / (rodeOut + missed)` over the same seasons (research R8, with p mixed into V1 only).

**Independent recomputation.** `threshold-hand-check.py` (this directory; it runs in the build scratchpad on top of `measure_v2.py` and
its jsonl exports of the local DB). It prints two populations: *per-game* (what the service does: a game with no stand-in is skipped
on its own) and *weeks* (`stopping.py`'s rule, which drops a whole week when any of its games lacks one).

| Population | 2024 alone n | 2025 alone n | 2024 alone thresholds (with p) | 2025 alone thresholds (with p) |
|---|---|---|---|---|
| weeks (stopping.py) | 6885 | 6052 | +0.044 +0.228 +0.337 +0.415 | +0.098 +0.275 +0.386 +0.468 |
| per-game | 7018 | 6192 | +0.043 +0.226 +0.334 +0.413 | +0.097 +0.274 +0.385 +0.467 |

The "weeks" row reproduces research R8's table (n 6885 / 6052 and the thresholds) exactly, so the script is the same computation. The
service uses the **per-game** population, which is slightly larger (it keeps the later games of a week whose first game had too few prior
games), so its numbers sit about 0.001-0.002 under R8's. That is a deliberate difference, not a drift; the "weeks" pooled result is printed too.

**Pooled 2024 + 2025, with p mixed into V1:**

| | n | pMissed | 1 left | 2 left | 3 left | 4+ left |
|---|---|---|---|---|---|---|
| script, per-game | 13210 | 0.030510257759074171 | 0.068457527230548071 | 0.24848821301198545 | 0.35787342719113946 | 0.43812871081889132 |
| endpoint, league 210 (2026) | 13210 | 0.030510257759074171 | 0.068457527230548182 | 0.2484882130120214 | 0.35787342719116649 | 0.43812871081891269 |
| endpoint, league 211 (2025) | 13210 | 0.030510257759074171 | identical to 210 | identical | identical | identical |
| script, weeks (stopping.py rule), for comparison | 12937 | 0.030510257759074171 | 0.069582269448572792 | 0.24989433305164524 | 0.35944173176404792 | 0.43987179316169428 |

Script and endpoint agree on n and pMissed exactly and on every threshold to better than 1e-13 (the residue is floating-point
summation order: the Python takes `statistics.mean`, the Java a running sum). At 3 dp (SC-007): +0.068 +0.248 +0.358 +0.438.

**League 212 (2024 chain only):** endpoint n 7018, pMissed 0.044310171198388724, thresholds +0.042893082066974168 +0.2261681399788037
+0.33438635383521426 +0.41273875444353031, equal to the script's per-game 2024-alone row (+0.043 +0.226 +0.334 +0.413).

Not done: the out-of-sample check (fit on 2024, apply to 2025) from the plan's verification additions.
