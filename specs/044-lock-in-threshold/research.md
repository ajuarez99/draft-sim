# Research: Lock-in threshold (spec 044)

All numbers here were **measured on 2026-10-21** against the local `draftsim` DB (5433), leagues 212 (NBA 2024) and
211 (NBA 2025), using a throwaway Python script. The script reimplements `GameScoringService.score` key-by-key and
lives in the session scratchpad, not the repo. Where a figure is a guess, it says so.

## R1 — Lock-in mode is identifiable from league settings

- **Decision**: A league is a lock-in league when `sport = nba` and `settings_json.game_mode = 1`. Any other league
  gets `status: NOT_LOCK_IN` (FR-011).
- **Evidence**: All three Ball Knowers NBA seasons (210/211/212) carry `game_mode: 1`, and they're the leagues
  whose credited points are one game per starter-week (540/540 roster-weeks, memory
  `project-nba-one-game-per-week-is-correct`). Sleeper's matchup payload has no lock flag. Its keys are only
  `points, players, roster_id, custom_points, matchup_id, starters, starters_points, players_points` (checked on
  2025 week 5).
- **Unverified**: That `game_mode: 1` *means* lock-in in Sleeper's own vocabulary. No Sleeper documentation was read;
  this is an inference from settings plus data. No NBA league with any other `game_mode` exists locally to
  contrast against.

## R2 — The credited game can be inferred, measured

Each multi-game starter-week was classified by matching `players_points[pid]` against each of that week's
`player_game` rows scored under the league's `scoring_json`, with a tolerance of 0.005.

| | 2024 | 2025 |
|---|---|---|
| multi-game starter-weeks | 2,429 | 2,171 |
| provable lock (unique match, not last game) | 1,356 | 1,195 |
| rode it out (unique match, last game) | 945 | 890 |
| tie (≥2 games score the same) | 84 (69 include the last game, 15 don't) | 72 (62 / 10) |
| unmatched | 44 | 14 |
| single-game weeks (not a choice) | 121 | 89 |
| no game rows that week | 36 | 1 |
| **unique-match share** | **94.7%** | **96.0%** |

- **Decision**: Reuse the matching rule `PlayerTrendsService.oneGameShare` already applies, with the same 0.005
  tolerance and scoring through `GameScoringService`. That way "which game was credited" has one implementation.
  Extract it into a shared helper rather than writing a second copy.
- **Ties whose candidates are all non-last games (15 + 10)** are provably locks, because every candidate had a later
  game. The game itself is unknown, though, so its baseline and games-left are unknown. **Decision**: count them in
  the provable-lock total and in a `tieNonLast` count, and exclude them from every margin. Ties that include the
  last game stay "ambiguous".
- **Unmatched** rows cluster in late/playoff weeks. In 2024, 20 of 44 fall in weeks 21–24, and the local DB is known
  to hold partial last weeks (memory `project-sleeper-nba-2025-matchup-drift`). They're counted and surfaced
  (FR-010), never guessed.

### SC-002, amended after measurement (2026-10-21)

The spec's original bar, "≥95% unique match", was a guess written before measuring. It's met by 2025 (96.0%) and
**missed by 2024 (94.7%)**. That bar mixed two different things:

- **Ties** (3.5%): genuine ambiguity that half-point scoring makes common. Excluding them is correct behaviour, not
  an inference failure.
- **Unmatched** (1.8% / 0.6%): the inference failure the bar was actually meant to catch.

The criterion is restated in spec.md as *unmatched ≤ 3% of multi-game starter-weeks, with ties published
separately*. The original wording stays visible there with a strike-through. This is an amendment to what is
measured, made after seeing the number. Allan should accept or reject it explicitly.

## R3 — Is "credited a non-last game" really a manager's choice?

The default for an unlocked player is unknown. The spec's premise is that, whatever the default is, a non-last
credited game was chosen.

- **Evidence for**: Among "rode it out" weeks, the last game was the week's max only 54.7% (2024) and 59.4% (2025)
  of the time. So the default is **not** "best game of the week", which is the default that would break the premise
  (a "best" default would put non-last games in the credited slot with nobody choosing). Among provable locks, the
  locked game was the week max 69.6% / 75.8% of the time, and a later game beat it 27.9% / 22.6% of the time. That's
  consistent with humans deciding under uncertainty, not with an oracle.
- **Not ruled out**: A default of "first game" or some other rule. The credited-position distribution (2024, 3-game
  weeks: first 302, middle 269, last 380) shows no single position dominating. Status: **assumed, partially
  supported**. It's written down as an assumption in the UI caveat ("locks are inferred").

## R4 — `player_game` only holds games played

- **Measured**: 0 rows with `sp = 0` in any NBA season (28,798 / 29,143 / 43 rows). Minutes are `sp` (seconds), not
  `min`. A DNP has no row at all.
- **Consequence**: "A later game" means a later *played* game. A player who locked Monday and then sat Wednesday has
  no visible later game, so he classifies as "rode it out" and is excluded. That's conservative: it can undercount
  locks, never invent one.
- **Spec amendment**: FR-007 / US3 scenario 3 ("a missed later game counts as not beating the lock") can't be
  implemented from `player_game`. `sport_schedule` has no 2024 rows (2025: 1,235, 2026: 1,200), and `player_game`
  stores no team to join a schedule on. So **missed later games aren't visible, and a lock followed only by DNPs
  isn't counted as a lock at all**. The payoff table therefore reads "how often a later game the player actually
  played scored more", and its caption says so.

## R5 — Baselines

- **Projection (preferred)**: the `projection_snapshot` row for that `game_id` (spec 036), scored under the
  league's scoring. It has the same restricted-map problem spec 036 amendment B3 solved: projected lines carry no
  bonus categories (`dd`, `td`, `bonus_*`), so scoring the projection without them while the credited points
  include them would push every margin upward by construction. **Decision**: for projection-baseline margins, score
  *both* sides on spec 036's restricted map (`ProjectedCategories`), re-scoring the locked game's own stat line.
  Don't use the raw `players_points`. The excluded categories ride in the payload.
- **Stand-in**: the player's mean league-scored points over his games played in the same season, dated strictly
  before the locked game's date. It's the full-scoring value against full-scoring credited points, so it needs no
  restricted map. A player with no prior game has no baseline (counted as `noBaseline`).
- **Never pooled**: each summary is reported per baseline kind (FR-004).
- **Measured availability**: `projection_snapshot` is empty locally, and prod has had it since the 2026-10-20 deploy.
  So the PROJECTION series will be TOO_EARLY for at least the first scored week of 2026 (week 1 closes ~2026-10-26)
  and for 2024/2025 forever.

### What the stand-in measures (preview, not a deliverable)

Provable locks with a stand-in baseline: median margin **+35.7%** (IQR +18.8% to +57.1%, n = 1,316) in 2024 and
**+39.3%** (IQR +20.5% to +65.6%, n = 1,160) in 2025. Per roster, the medians range from +27.9% to +44.6% (2024) and
+28.3% to +59.2% (2025), on 54–145 locks each.

Payoff (share of locks a later played game beat), 2025:

| margin \ later games left | 1 | 2 | 3+ |
|---|---|---|---|
| < 0% | 0.68 (34) | 0.67 (12) | 0.75 (4) |
| 0–10% | 0.29 (48) | 0.50 (18) | 0.71 (7) |
| 10–25% | 0.27 (119) | 0.29 (97) | 0.53 (30) |
| 25–50% | 0.14 (164) | 0.30 (124) | 0.39 (66) |
| 50%+ | 0.06 (210) | 0.10 (165) | 0.15 (62) |

The bands were picked by hand and are labelled arbitrary in config. They were not tuned after seeing this table.

## R6 — Where it lives

- **Decision**: A new read-only endpoint, `GET /api/leagues/{sleeperId}/lock-ins`, one league row = one season,
  modelled on `ProjectionAccuracyController` (same league lookup, same refusal shape). A new `LockInService` is
  cached on a fingerprint of its inputs (stamp of the `roster_week_points` + `player_game` season token + snapshot
  stamp + scoring hash), like `ProjectionAccuracyService`. The per-player lock list is served by the same endpoint
  (`?player=<sleeperId>`), so the player page and the analysis section read one computation.
- **Season coverage (FR-009)**: Each season is its own league row and `sleeperId`. The analysis page is already
  season-scoped by the rail (spec 011). The section reads whatever league row the page is on, so 2024/2025/2026 are
  reached through the existing season switch. No new selector is needed.
- **UI**: A `LockInPanel` on `LeagueAnalysis.tsx` (manager table, player table, payoff grid), next to
  `ProjectionAccuracyPanel`, plus a "Lock-ins" block on the player page (`PlayerStatsController`'s page).
- **Alternatives rejected**: Folding it into `/analysis`. That payload is already large and cached separately, and
  this result is a different fingerprint. Adding a stored table was also rejected: the read measured fine in Python
  (≪1 s for 2.4k starter-weeks), so a migration isn't justified. **No migration.**

## R7 — Constants (hand-set, labelled arbitrary)

These go in `config/weights.yml` under `draftsim.lock-in`, each commented as an arbitrary choice:

- `min-locks: 10` is the smallest sample that gets a median per manager or player.
- `min-cell: 15` is the smallest payoff cell that gets a rate.
- `margin-bands: [0, 0.10, 0.25, 0.50]` are the band edges, giving < 0, 0–10, 10–25, 25–50 and 50%+.
- `games-left-cap: 3`, so 3 or more later games share one column.

---

## Amended after plan review (2026-10-21)

The plan review (`plan-review.md`, F1–F14) found that the first research run used the wrong game set
and a misread minutes field. The script was fixed and re-run as `measure_v2.py`, `breakeven.py` and
`stopping.py` in the session scratchpad. **The numbers below supersede R2 and R5's tables above.**
Those tables are kept as first written, with these corrections:

- ~~R2: "Unmatched rows cluster in late/playoff weeks ... partial last weeks"~~ was a guess
  presented as a finding. **Measured (F5)**: every unmatched starter credit (44/44, 14/14) is
  exactly 0.0, which means the default landed on a last scheduled game the player missed. Only 6
  and 4 of them fall in a season's last week. Real inference failures (nonzero, matching no game):
  **0** in both seasons.
- ~~R3: "assumed, partially supported"~~. **Measured (F5)**: bench players, who can't be locked, are
  credited their last game in 860/864 (2024) and 848/865 (2025) uniquely matched weeks. The default
  is the last scheduled game. This also corrects memory `project-nba-one-game-per-week-is-correct`
  ("not first, last, or highest"), which pooled locked starters into "default".
- ~~R2 and plan step 2: "minus the excluded-game-ids, same filter as other readers"~~. **Measured
  (F1, F2)**: the All-Star game is never credited (0/30), so it's dropped, using `NbaGameLines`'
  rule (opponent not a season team code). The Cup final **is** credited (1 unique credit per
  season), so it's kept. The matcher's game set is "raw `player_game` minus the All-Star game".
- ~~R4: "minutes are `sp`" was right, but the script's `played()` read `min`~~ (F11). The re-run
  uses `sp > 0` (a no-op: every stored row has `sp > 0`).
- ~~R6: "≪1 s in Python"~~ was never timed (F3). It's a guess, to be measured in verification.

### R2 v2 — classification under the final game set

| | 2024 | 2025 |
|---|---|---|
| multi-game starter-weeks | 2,428 | 2,168 |
| provable lock (unique, non-last) | 1,351 | 1,188 |
| rode it out (unique, last) | 949 | 894 |
| credited 0 onto a missed game | 44 | 14 |
| tie, all non-last / including last | 14 / 70 | 10 / 62 |
| real unmatched | 0 | 0 |
| single-game | 122 | 92 |
| no stand-in baseline (< 5 prior games) | 130 | 127 |

### R5 v2 — stand-in margins (min-prior-games = 5)

- 2024: median **+35.8%**, IQR +19.0% to +56.3%, n = 1,221. Per-manager medians run from +27.3%
  to +45.5% (n 48–137).
- 2025: median **+39.3%**, IQR +20.7% to +65.3%, n = 1,061. Per-manager medians run from +29.3%
  to +59.3% (n 76–108).
- Players at or above `min-locks` (10): 51 of 202 (2024) and 30 of 220 (2025).

## R8 — The lock threshold (Allan: "you should lock this based on previous locks and averages")

**Rejected: the naive break-even.** Over every candidate game (a starter's non-last played game in
a multi-game week, locked or not, n ≈ 6–7k per season), "riding it out beat it" falls below 50% in
the 0–10% band for 1 and 3 games left, and in 10–25% for 2 left. In effect it says "lock anything
above average" for every games-left count. That's wrong: it values waiting as "take the last game",
ignoring that the player can lock a later game.

**Decision: optimal stopping over the league's per-game margin distribution.** Let *m* be a game's
margin vs the player's stand-in, over every starter game with ≥ 5 prior games in the league's
completed seasons. Let *p* be the measured rate at which the default landed on a missed game
(credited 0 / (rode out + credited 0); 2024: 44/993, 2025: 14/908). Then:

- V₁ = (1 − p)·E[m] + p·(−1). The last game is worth its expected margin, except when it's missed
  and scores 0.
- V₍ₖ₊₁₎ = E[max(m, Vₖ)]. With k + 1 games left, you either take this game or keep the option.
- **Lock with k games left iff margin ≥ Vₖ.**

| season | 1 left | 2 left | 3 left | 4+ left | games |
|---|---|---|---|---|---|
| 2024 | +4.4% | +22.8% | +33.7% | +41.5% | 6,885 |
| 2025 | +9.8% | +27.5% | +38.6% | +46.8% | 6,052 |

Sanity check, **in-sample** (the thresholds were fitted on the same season they were tested on, so
it's not evidence the rule works): following the rule would have averaged 26.84 vs 26.17 actually
credited points per starter-week in 2024, and 26.63 vs 26.42 in 2025. **Out-of-sample** (fit on
2024, apply to 2025) is owed in verification. This is a sanity check on an advice rule, not a
backtest of the draft engine (AGENTS.md's "no backtesting" decision is about the engine), and no
gain figure is shown in the UI.

Known simplifications, all named in the payload's caveats:

- One league-wide distribution. Per-player volatility is ignored; a streaky player's real threshold
  is higher.
- Games are treated as independent draws.
- *p* is applied only to the last game.
- Live calls use the thresholds learned from **all completed seasons in the league's chain, pooled**.
  For 2026 that's 2024 + 2025.

## R9 — Live calls (US4)

- **Week**: `LeagueRepository.currentLeg(leagueId)`. `sport_schedule.week` aligns with the fantasy
  week (2026 week 1 = 10-20..10-25, measured).
- **Games left**: scheduled `sport_schedule` games for the player's current `player.team`, in the
  current week, dated after the player's best game so far, with `status` not complete/postponed.
  A DNP can't be foreseen, and *p* already prices it into V₁.
- ~~**Best game so far**: the max-margin played game this week.~~ **Latest game**: the most recent
  played game this week. Allan confirmed (2026-10-22) that only the latest game can be locked, which
  is also what R8's recursion assumes.
- **Baseline**: the stand-in, using this season's games before today. Below `min-prior-games`, it
  uses the previous season's full average from `chainBySleeperId` (labelled "last season's
  average"). With neither, the call is "too early".
- **Starters**: the current roster lineup. The current week has no `roster_week_points` row yet
  (memory spec 022 review). The plan must name the source; the candidate is the live matchup
  fetch's starters, verified in plan.

## R10 — Digest card (US5)

- `AdminDigestService` is sport-wide, and lock-in calls are per league. The card is a list with one
  block per current-season NBA lock-in league (gate as in R1/F8), built from the same `LockInService`
  live-call method for the digest's target night. The tweet text is built from the top entries by
  margin and truncated by whole entries to 280 characters.

## R11 — Out-of-sample check of the lock rule (measured 2026-10-22, after the build)

This is R8's owed check. I fitted the thresholds on **2024 alone**, using the service's per-game population
(n = 7,018) with the DNP rate mixed into V1. That gave +4.3%, +22.6%, +33.4% and +41.3%. I then applied them to
**2025's** starter-weeks in game order, locking the first game that clears the bar for its games left and
otherwise taking the last game. Starter-weeks without a baseline at every decision point were left out.

- 2025 starter-weeks: 1,923. The mean actually credited was **26.42**, and the rule's mean is **26.75**
  (**+0.33 points per starter-week**).
- This is small and positive, from one season. It says the rule isn't worse than what this league's managers did.
  It doesn't promise a gain, and the UI shows no gain figure.
- One limitation: the replay can't know injuries or rest that a manager knew about, and that is exactly the
  information a real lock decision uses. Script: `oos.py` (scratchpad).
