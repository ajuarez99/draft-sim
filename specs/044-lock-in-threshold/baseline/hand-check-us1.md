# Hand check, US1 (T017, SC-001 / SC-002)

Run 2026-10-22 against the local DB (Postgres 5433), this worktree's backend on port 8144, leagues 212 (NBA 2024)
and 211 (NBA 2025). **Executed**: `GET /api/leagues/{sleeperId}/lock-ins` for both, and `GET ...?player={id}` for
every listed player. The comparison script (`compare_us1.py`, in the session scratchpad, not the repo) re-derives
every figure from the exported jsonl files with the same rules as `measure_v2.py` (All-Star game dropped, Cup final
kept, `sp > 0`, stand-in needs 5 prior games, exclusive quantiles via `statistics.quantiles`) and compares to 1e-9.
`measure_v2.py` itself was also run, and its printed totals, per-manager (n, median) pairs, pooled median/IQR and
"players with >= 10 margins" were read against the endpoint.

**Result: every figure matches. No mismatch, no tolerance widened.**

## SC-002: totals against research R2 v2

| | 2024 endpoint | 2024 R2 v2 | 2025 endpoint | 2025 R2 v2 |
|---|---|---|---|---|
| multi-game starter-weeks | 2,428 | 2,428 | 2,168 | 2,168 |
| lock | 1,351 | 1,351 | 1,188 | 1,188 |
| rodeOut | 949 | 949 | 894 | 894 |
| rodeOutMissed (credited 0) | 44 | 44 | 14 | 14 |
| tieNonLast / tieWithLast | 14 / 70 | 14 / 70 | 10 / 62 | 10 / 62 |
| unmatched (real) | 0 | 0 | 0 | 0 |
| singleGame | 122 | 122 | 92 | 92 |
| noGames | 36 | 36 | 1 | 1 |
| no stand-in baseline | 130 | 130 | 127 | 127 |

The partition lock + rodeOut + rodeOutMissed + tieNonLast + tieWithLast + unmatched = multiGame holds in both
(1351+949+44+14+70+0 = 2428; 1188+894+14+10+62+0 = 2168). Weeks covered: 24 (2024), 21 (2025). `noSnapshot` equals
`lock` in both, as expected: `projection_snapshot` is empty locally for those seasons, so the PROJECTION path is
covered by unit tests only, not by this check.

## Pooled stand-in margin (every player's lock list from `?player=`, pooled)

| | endpoint | `measure_v2.py` |
|---|---|---|
| 2024 | n 1,221, median +0.3582, IQR +0.1900..+0.5632 | n 1,221, median 0.358, IQR 0.190..0.563 |
| 2025 | n 1,061, median +0.3928, IQR +0.2074..+0.6530 | n 1,061, median 0.393, IQR 0.207..0.653 |

Players with at least 10 stand-in margins: 51 (2024) and 30 (2025), the same as `measure_v2.py`. Players listed
(any lock or tieNonLast): 204 and 227. Every per-player n, and every median for n >= 10, equals the recomputation.

## SC-001: per manager, median / q1 / q3 / n / rodeOut / tieNonLast (python vs java)

### 2024 (league 212, sleeper 1141438340626231296)

| manager id | n (py/java) | median py | median java | q1 py | q1 java | q3 py | q3 java | rodeOut py/java | tieNonLast py/java | match |
|---|---|---|---|---|---|---|---|---|---|---|
| 7 | 101/101 | 0.405018 | 0.405018 | 0.245406 | 0.245406 | 0.678436 | 0.678436 | 81/81 | 1/1 | OK |
| 32 | 121/121 | 0.316489 | 0.316489 | 0.182195 | 0.182195 | 0.505292 | 0.505292 | 62/62 | 2/2 | OK |
| 43 | 76/76 | 0.295893 | 0.295893 | 0.171313 | 0.171313 | 0.562151 | 0.562151 | 97/97 | 2/2 | OK |
| 45 | 133/133 | 0.376518 | 0.376518 | 0.206936 | 0.206936 | 0.573103 | 0.573103 | 59/59 | 4/4 | OK |
| 46 | 105/105 | 0.349862 | 0.349862 | 0.207520 | 0.207520 | 0.583918 | 0.583918 | 77/77 | 0/0 | OK |
| 48 | 97/97 | 0.393032 | 0.393032 | 0.192782 | 0.192782 | 0.539861 | 0.539861 | 90/90 | 0/0 | OK |
| 49 | 122/122 | 0.371325 | 0.371325 | 0.222806 | 0.222806 | 0.561070 | 0.561070 | 71/71 | 1/1 | OK |
| 50 | 98/98 | 0.362916 | 0.362916 | 0.165798 | 0.165798 | 0.551806 | 0.551806 | 87/87 | 2/2 | OK |
| 51 | 90/90 | 0.390977 | 0.390977 | 0.206109 | 0.206109 | 0.602948 | 0.602948 | 91/91 | 0/0 | OK |
| 52 | 93/93 | 0.454545 | 0.454545 | 0.271963 | 0.271963 | 0.697771 | 0.697771 | 97/97 | 1/1 | OK |
| 588 | 137/137 | 0.273123 | 0.273123 | 0.118534 | 0.118534 | 0.479042 | 0.479042 | 49/49 | 1/1 | OK |
| 611 | 48/48 | 0.345517 | 0.345517 | 0.136602 | 0.136602 | 0.484374 | 0.484374 | 88/88 | 0/0 | OK |
Sum of manager stand-in n: py 1221, java 1221
Players with a lock: py 204, java 204; players with >= 10 stand-in margins: py 51, java 51; per-player n mismatches 0, median mismatches 0

### 2025 (league 211, sleeper 1229352720222134272)

| manager id | n (py/java) | median py | median java | q1 py | q1 java | q3 py | q3 java | rodeOut py/java | tieNonLast py/java | match |
|---|---|---|---|---|---|---|---|---|---|---|
| 7 | 79/79 | 0.468493 | 0.468493 | 0.236593 | 0.236593 | 0.849387 | 0.849387 | 89/89 | 0/0 | OK |
| 32 | 80/80 | 0.460324 | 0.460324 | 0.272260 | 0.272260 | 0.693197 | 0.693197 | 85/85 | 0/0 | OK |
| 43 | 80/80 | 0.329360 | 0.329360 | 0.160369 | 0.160369 | 0.524648 | 0.524648 | 76/76 | 1/1 | OK |
| 45 | 84/84 | 0.333500 | 0.333500 | 0.158015 | 0.158015 | 0.644140 | 0.644140 | 74/74 | 2/2 | OK |
| 46 | 102/102 | 0.292842 | 0.292842 | 0.135641 | 0.135641 | 0.542498 | 0.542498 | 66/66 | 1/1 | OK |
| 48 | 82/82 | 0.400000 | 0.400000 | 0.233840 | 0.233840 | 0.572949 | 0.572949 | 78/78 | 0/0 | OK |
| 49 | 94/94 | 0.452253 | 0.452253 | 0.220913 | 0.220913 | 0.842289 | 0.842289 | 72/72 | 1/1 | OK |
| 50 | 76/76 | 0.593274 | 0.593274 | 0.328515 | 0.328515 | 0.846102 | 0.846102 | 88/88 | 2/2 | OK |
| 51 | 108/108 | 0.340484 | 0.340484 | 0.150414 | 0.150414 | 0.526690 | 0.526690 | 50/50 | 0/0 | OK |
| 52 | 91/91 | 0.379310 | 0.379310 | 0.258427 | 0.258427 | 0.632594 | 0.632594 | 76/76 | 0/0 | OK |
| 55 | 97/97 | 0.460076 | 0.460076 | 0.298377 | 0.298377 | 0.707561 | 0.707561 | 67/67 | 2/2 | OK |
| 588 | 88/88 | 0.309342 | 0.309342 | 0.189235 | 0.189235 | 0.537478 | 0.537478 | 73/73 | 1/1 | OK |
Sum of manager stand-in n: py 1061, java 1061
Players with a lock: py 227, java 227; players with >= 10 stand-in margins: py 30, java 30; per-player n mismatches 0, median mismatches 0

## US2 hand check (T025), 2026-10-22

Independent recomputation: `handcheck_us2.py` in the build scratchpad (measure_v2.py's rules: All-Star dropped, Cup final kept,
>= 5 prior games, exclusive quartiles), compared with `GET /api/leagues/1229352720222134272/lock-ins?player=<id>&live=false`
(league 211, 2025) on a fresh server on port 8144.

| Player | what it covers | script n / median / q1 / q3 | endpoint n / median / q1 / q3 | equal |
|---|---|---|---|---|
| 1845 Donovan Mitchell | the 2025 player with the most locks (15), locked by managers 52 and 55 | 15 / 0.3255451713395638 / 0.10084925690021228 / 0.4228013029315961 | identical to every digit | yes |
| 1698 Brandon Ingram | 13 locks, managers 45 and 52 | 13 / 0.19540229885057472 / 0.17501332309167178 / 0.26821642111435723 | identical to every digit | yes |

Both are also a player locked by two managers: the lock list attributes each lock to its manager (weeks 3-12 manager 55, 13-21 manager
52 for Mitchell). **The lock list is longer than `n`**: Mitchell's list has 16 locks and `n` is 15, because the 16th (week 1, manager 48)
is a provable lock with no stand-in (fewer than 5 earlier games), so it has no margin and is not in `n`; the table prints it as
"no margin". So "the lock list's length equals n" holds for the locks that have a margin, not for all provable locks.

## US3 hand count (T033), 2026-10-22

The whole stand-in payoff grid of both seasons equals `measure_v2.py`'s grid cell for cell (n, rate-last, rate-any; the cells below
`min-cell` 15 are null on both sides). One cell counted by hand from the script's lock list (band 25-50%, 2 later games):

| Season | n | riding it out beat the lock | any later game beat it | endpoint rateLast / rateAny |
|---|---|---|---|---|
| 2024 (league 212) | 141 | 26 | 39 | 26/141 = 0.18439716312056736 / 39/141 = 0.2765957446808511 |
| 2025 (league 211) | 113 | 21 | 35 | 21/113 = 0.18584070796460178 / 35/113 = 0.30973451327433627 |
