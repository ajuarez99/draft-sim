# 11 — Every NFL manager "reaches": the reach figure is mostly a league-wide baseline offset

**Severity: Medium (display honesty; bug class 5). Measured, cause not yet known.**
This is an investigation plan, not a fix. "Change nothing, label it" is a valid outcome.

## What's there now

- `ProfileService.java:86-106`: `empiricalReachBias` = unshrunk mean of
  `adp_at_time - pick_no` over a manager's scoreable picks. Positive means "took him
  earlier than the board". `effectiveReachBias` is that value shrunk toward the league
  mean (`:110-114`, `:146-151`). `ManagerController.java:33-40` serves both.
- `adp_at_time` is a dense 1..N rank on the blended board. `BoardService.backfillAdpAtTime`
  (`:271-289`) stamps it from **any** blend snapshot within +/-`maxBoardLagDays` (30) of the
  draft date. Two consequences:
  - A draft that matches several snapshots gets whichever row Postgres joins first. That
    choice is arbitrary.
  - The window includes boards captured *after* the draft.
- The blend rescales each source to `referenceTeams: 14` (`BoardService.java:225, 242`).
  It also blends in this league's own 2026 draft (`weights.yml` `observedDrafts`,
  `observedWeight: 0.5`). So a post-draft board partly measures the league against itself.

**Measured (read-only GETs, `/api/managers?sport=nfl` default):**

| | managers w/ empirical | mean | min | max | negative |
|---|---|---|---|---|---|
| local (caller's measurement; server down when re-checked 2026-09-29) | 37 | +9.31 | -10.4 | 33.13 | 5 |
| production (re-measured 2026-09-29; board `capturedOn` 2026-09-12, 598 scored picks) | 37 | +5.6 | -8.13 | 23.4 | 7 |

- Almost every production manager has `picksScored` = 15, which is one draft.
- On `/managers`, nearly everyone reads as "reaches", and nobody reads as "waits".
- The league and the draft history are the same in both environments, but the numbers
  disagree. That is unexplained.

Side note: production answered `/api/managers` with no identity. That belongs to audit
01/02, not to this one.

## Questions to answer, each with a measurement

1. **Is the offset real behaviour or an artifact?** Take one draft and plot
   `adp_at_time - pick_no` against round.
   - A roughly flat mean suggests real league behaviour.
   - A mean that grows with round suggests an artifact. Board ranks would be drifting
     past pick numbers, for example because players on the board never get drafted
     (injured, K/DST ranked differently, `sleeper_search_rank` filler).
   - Also count how many board ranks at or below 180 no pick in any draft took.
2. **Timing.** For each draft, list the snapshots that fall inside +/-30 days, and which
   one each pick actually got. Then recompute each manager's mean against:
   - the snapshot closest *before* the draft;
   - the snapshot closest *after* it.

   Report how much the league mean moves between the two.
3. **League size and format.** Compare each draft's `teams` and scoring against the 14-team
   reference and FFC's configured cell. Check whether the offset scales with team count
   across leagues. `popsharky` has 4 drafts, which makes a within-manager comparison.
4. **Why the two environments differ.** Run the same SQL against both DBs; production is
   read-only, so ask Allan to run it there:
   - the list of `adp_snapshot` capture dates;
   - scored picks per draft;
   - `adp_at_time` for the same 20 picks.

   Hypotheses:
   - The two DBs hold different snapshot histories.
   - The arbitrary snapshot choice resolved differently in each.
   - The same `sleeper_draft_id` sits under a different draft date or team count.
5. **Does the offset matter to the sim?**
   - For simulation, a uniform offset may be self-consistent. It shifts every seat's
     `valueDelta` alike, so it may not change who picks whom. That claim is **unverified**.
     Test it: run fixed-seed sims with every seat's reach de-meaned, and compare the
     predicted boards.
   - For display, the offset is misleading whatever the sim result is. That half is the
     finding.

## Candidate outcomes (Allan decides after the measurements)

- **A. Relative display:** show "reaches 4 picks more than this league". Use
  `empirical - leagueMean`, where the mean is already computed at `ProfileService:108`.
  The engine is untouched. Mirror any new field in `web/src/api.ts`.
- **B. Fix the baseline:** make snapshot choice deterministic, pre-draft only, or change
  the rank basis. That changes the engine inputs, so re-verify sims too.
- **C. Change nothing:** keep the numbers and label them on the page ("measured against the
  market board; this whole league drafts ~N picks ahead of it").

**Not allowed:** tuning `weights.yml` (`referenceTeams`, `maxBoardLagDays`,
`observedWeight`) until the mean looks centred. That is retuning a constant to match a
guess (AGENTS.md hard rule).

## Acceptance criteria

- [ ] Each of Q1-Q5 has a measured answer, labelled measured or assumed, with the query or
      command used.
- [ ] The local-vs-production gap is explained by a named cause, or explicitly left
      "unexplained" with the evidence collected.
- [ ] The write-up says which claim concerns the display and which concerns the simulation.
- [ ] Whatever option ships: in a real browser, `/managers` no longer implies that nearly
      every manager is an individual reacher, or it states plainly why the figure says so.

## Findings (2026-09-29)

Investigation only: no production code, config or data changed. Local DB read with
`SELECT` only (`docker exec draftsim-pg psql -U draftsim -d draftsim`); production read
with GETs only (`/api/board?limit=2000&sport=nfl`, `/api/managers?sport=nfl`,
`/api/health`). Analysis scripts were throwaway Python over psql exports in the session
scratchpad; the sim harness was a throwaway JUnit class, run once and deleted, never
committed. Labels: **measured** = computed from real data in this pass. **Inferred** =
follows from code or measurements but was not run directly. **Guessed** = neither.

Acceptance criteria 1-3 above are met by this section. Criterion 4 is for whatever ships.

### Amended after investigation: three statements above are wrong or incomplete

- **"A draft that matches several snapshots gets whichever row Postgres joins first."**
  That is right, and the arbitrary choice is not hypothetical. **Measured:** locally,
  all 600 scored picks carry the **oldest** in-window blend (2026-09-07). This query
  matches 210/210, 180/180 and 210/210 picks against 09-07, and only 14-76 per draft
  against 09-09, 09-13 or 09-28:
  `select dp.draft_id, s.captured_on, count(*) filter (where s.adp = dp.adp_at_time) from draft_pick dp join adp_snapshot s on s.player_id=dp.player_id and s.sport='nfl' and s.source='blend' where dp.adp_at_time is not null group by 1,2`.
  So the `BoardService.backfillAdpAtTime` javadoc is wrong when it says repeated calls
  "only refresh values against the newest in-range snapshot". Nothing in the SQL picks
  the newest.
- **"The window includes boards captured *after* the draft."** It is worse than that.
  **Measured:** every NFL blend snapshot in the local DB (09-07, 09-09, 09-13, 09-28) is
  dated after all three scored drafts (08-28, 09-01, 09-03). No pre-draft board exists
  locally, so the plan's "closest snapshot *before* the draft" cannot be computed here.
  The local DB is a fresh rebuild.
- **"The league and the draft history are the same in both environments, but the
  numbers disagree. That is unexplained."** It is now explained; see Q2.
- **Display, not stated before:** `/managers` draws its bar from `effectiveReachBias`
  (`ManagerTendencies.tsx:174`), not from the empirical figure in the table above.
  Effective reach is shrunk *toward the league mean*. **Measured on prod:** the minimum
  `effectiveReachBias` is +2.97 and the maximum +10.4, with 0 of 37 negative. On
  production, then, every bar points at "reaches", by construction. The 7 negative
  empirical values never reach the bar.

### Q1. How much of the offset is real behaviour, and how much is baseline?

**The league-wide mean is almost entirely a property of the baseline, not of the
managers. Measured.** Four results support this.

**1. An identity, inferred from the definition and confirmed on data.** For one draft,
mean(`adp_at_time - pick_no`) = (mean board rank of the players drafted) - (mean pick
number). This is exactly 0 whenever the room drafts the board's top N players, *in any
order*. A positive draft mean therefore does not mean "the room took players early". It
means the board ranks players inside its top N that the room never drafted, and fills the
gap from deeper in the board.

**Measured on 09-07:**

| draft | teams | offset | mean board rank drafted / mean pick | board top-N players undrafted |
|---|---|---|---|---|
| (Foot) Ball Knowers 2026 | 12 | +13.05 | 103.55 / 90.5 | 31 (RB 9, WR 11, QB 4, TE 2, K 3, DEF 2) |
| fantasy😍 2026 | 14 | +8.59 | 114.09 / 105.5 | 26 |
| West Coast 2026 | 14 | +9.15 | 114.65 / 105.5 | 29 |

13 players inside the board's top 180 were drafted in *none* of the three drafts. The
highest is James Conner at board rank 113; the list also includes Jerry Jeudy, Keenan
Allen and Calvin Ridley. That is a board problem, not a manager behaviour.

**2. The offset grows with round.** The plan named this as the artifact signature.
**Measured**, mean `adp_at_time - pick_no` grouped by `round`, on 09-07:

- BK: 0.5, 2.8, -1.5, -0.8, 1.4, 0.0, 0.5, 5.2, -3.6, 0.8, 4.9, 13.2, **46.4, 51.9, 73.9**
- fantasy😍: ~0-6 through round 9, then 21.8, 27.5, 39.1, 35.4, 1.3, -11.6
- West Coast: ~0-8 through round 9, then 7.9, 15.5, 31.6, 35.5, 10.8, 4.0

Rounds 12-15 carry 95% / 50% / 60% of each draft's total offset. Over rounds 1-11 the
means are +0.9 (BK), +5.9 and +5.0. Taking each manager's median instead of mean drops the
league average from +9.31 to +1.81.

**3. Per-candidate recomputation.** I re-implemented `BoardService.rebuild()` in Python
from the stored source snapshots:

- weights as configured, with the per-player normalisation;
- dense re-rank;
- the drop-off-roster rule.

It reproduces the stored 09-07 and 09-28 blends exactly (250/250 of the top 250 ranks). On
09-13 it is exact on 227 and within 2 ranks on all 250, because the drop-off-roster check
uses today's `player.team`. I then rebuilt under each alternative baseline and recomputed
per-manager empirical reach (37 managers, 600 picks). ρ is the Spearman rank correlation
of the per-manager values against the as-stored (09-07) ranking.

| baseline | mean | min / max | negative | ρ vs as-stored |
|---|---|---|---|---|
| as stored = 09-07 blend | **+9.31** | -10.4 / 33.1 | 5 | 1.00 |
| (a) 09-09 blend | +8.24 | -10.7 / 31.3 | 6 | 0.98 |
| (a) 09-13 blend | +4.48 | -8.8 / 26.4 | 8 | 0.84 |
| (a) 09-28 blend | +5.14 | -16.1 / 22.6 | 6 | 0.51 |
| (a) prod's 09-12 board (from `/api/board`) | +5.64 | -8.1 / 23.4 | 7 | 0.93 |
| (b) 09-07 without the league's own draft (weights as configured, so FFC-first) | +5.47 | -9.7 / 28.5 | 6 | 0.59 |
| (b) 09-07, `observedWeight` 0 (sr/FFC 50/50) | +5.23 | -6.9 / 36.7 | 6 | 0.52 |
| (c) 09-07, `referenceTeams` 12 | +8.92 | -10.7 / 32.5 | 5 | 1.00 |
| (b)+(c) 09-07, no observed and ref 12 | +5.19 | -10.2 / 28.3 | 8 | 0.59 |
| (b) 09-28 without observed | +2.16 | -15.1 / 44.6 | 15 | 0.33 |

These are hypothetical rebuilds for measurement. Nothing was retuned.

- **(a) Timing: measured, large.** Choosing a different post-draft board moves the mean
  between +4.5 and +9.3 and moves the ranking as low as ρ 0.51. A pre-draft comparison
  is impossible locally because no pre-draft snapshot exists. Every board in play is
  post-draft.
- **(b) The league's own draft in the blend: measured, large.** On 09-07 it accounts for
  ~3.8 of the 9.3. It also makes BK's own rounds 1-11 read as ~0, because the board
  partly *is* that draft. Removing it re-orders managers (ρ 0.52-0.59).
- **(c) League size vs `referenceTeams: 14`: measured, small.** Changing to 12 moves the
  mean by -0.4 on 09-07, -0.9 on 09-13 and -1.7 on 09-28. On 09-07 the ranking is
  unchanged (ρ 1.00). The offset does not scale with team count: the 12-team draft has
  the *larger* offset under every baseline (+7.4 vs +4.9/+5.3 with no observed draft).
  popsharky's three scored drafts (one 12-team, two 14-team) give raw +18.3 / +18.5 /
  +33.0 and relative-to-room +5.3 / +9.9 / +23.8. That is consistently above his rooms,
  but one manager cannot test a team-count effect. **Inferred:** not the cause.
- **(d) Scoring format: measured, not applicable.** All three leagues are full PPR
  (`rec` 1.0), 4-point pass TD, and use the same 1QB slot list. The FFC cell is 14-team
  `ppr`. There is no format mismatch to recompute against. Side observation (measured):
  the FFC rows from 09-13 and 09-28 are `derived=true`, and 09-28 has only 29 players,
  so the current local board is mostly search_rank plus observed order. That is out of
  scope here, but it matters for anything that reads the current board.

**4. Even relative figures are mostly noise at 15 picks. Measured.** The per-pick
standard deviation is 30.9 picks, so the median standard error of one manager's mean is
7.3 picks. I shuffled manager labels *within each draft* 500 times. The null standard
deviation of seat means was 7.96, against 9.29 observed (p ≈ 0.05). With the offset
removed per draft, 5 of 37 managers sit more than 2 SE from their room on 09-07 (3 of 37
on prod's board). Chance alone predicts about 2. So what separates managers is real but
thin; popsharky (+) and gassybois / erikk808 (-) survive on both baselines.

**Answer to Q1.** The *league-wide* offset should not be read as behaviour.
Measured: it moves by 4-7 picks with baseline choices nobody made on purpose (which
snapshot; whether the league's own draft is blended in), and it is concentrated in the
late rounds. Inferred: it is mechanically produced by board-top players that no room
drafts. How much league-wide early-taking is *real* cannot be separated with this data.
**Guessed:** somewhere between 0 and ~+5 picks over rounds 1-11, and I would not put
that number on a page. Per-manager differences *relative to the same draft* are the part
that can carry meaning. Even those are baseline-sensitive (ρ 0.47-0.97 across snapshots,
de-meaned per draft) and noisy.

### Q2. Why local and prod disagree

**Named cause, measured: the two environments stamped `adp_at_time` from different blend
snapshots.** Local uses 09-07 (above). For prod I mapped `/api/board`'s 09-12 board (834
entries; the one it reports as `capturedOn`) onto local player IDs by (name, position),
and recomputed each manager's empirical reach from local picks. That reproduces prod's
`/api/managers` `empiricalReachBias` **to the hundredth for 33 of the 35**
managers matched by name. The prod mean is +5.64 against the reported +5.6.

The two misses are omarkadry (1.86 vs 0.67) and Bartner (-4.64 vs -5.13). They line up
with 596 picks mapped here against the 598 prod reports scoring: two picks prod scored
that my name mapping missed. Two of prod's 37 display names did not match local names.
The draft data is the same; only the baseline differs.

**What cannot be determined from outside (read-only GETs):**

- whether prod holds other in-window blend snapshots, and so whether 09-12 was prod's
  only candidate or an arbitrary choice among several;
- prod's `adp_snapshot` capture dates and source row counts;
- whether prod ever had a pre-draft board.

A read-only query for Allan to run against prod settles all three:
`select source, captured_on, count(*) from adp_snapshot where sport='nfl' group by 1,2 order by 2`.

### Q3. Is the offset harmless to the simulation?

**Mostly, but not exactly, and for a reason the plan did not anticipate.** The
reason is inferred from code; the effect was measured with the harness.

**Mechanism (inferred from `PickScorer.valueDelta` and `PickDecider.choose`):** a seat's
`reachBias` is added identically to every candidate's `valueDelta` at that pick. Softmax
does not change when the same constant is added to every score. So reach, uniform or
per-seat, changes nothing *except* where `valueDeltaClamp` (±3, i.e. ±36 picks)
truncates. Mostly that means players who have fallen far past their board rank. In
practice those are K/DEF, which the `latestRounds` gate holds back until late while the
board ranks them earlier.

A corollary, flagged separately and not fixed here: the `PickScorer` javadoc's "a manager
with a bias of +10 treats a ten-pick reach the way the room treats an on-board pick" does
not hold. Per-seat reach differences barely move the sim at all.

**Harness (measured):**

- **Inputs:** local 09-28 blend board (843 players, exported read-only), and the
  (Foot) Ball Knowers 2026 seats (12 teams × 15 rounds, real `slot_to_manager`).
- **Reach:** each seat's effective reach recomputed exactly as `ProfileService` does it
  (k=4 toward the pick-weighted league mean +10.125).
- **Held constant:** uniform priors and neutral tilt, so only reach differs between arms.
- **Run settings:** `MonteCarloRunner`, 2000 iterations, temperature 1.0, seed 20260929.
  "Noise floor" is the same arm run with seed+1.

| comparison | board cells with a different player | max Δ prob, same-player cell | max Δ availability at my picks (slot 1 / 4 / 7 / 12) | availability Δ > 0.05 |
|---|---|---|---|---|
| as-is vs de-meaned (same seed) | 67/180, all in rounds 3-15 | 0.089 | 0.030 / **0.192** (Brandon Aubrey, K, pick 148) / 0.094 (Cameron Dicker, K, pick 151) / 0.033 | 0-4 of ~1520 |
| noise floor (as-is, seed+1) | 139/180 | 0.019 | 0.047 / 0.058 / 0.075 / 0.066 | 0-6 of ~1550 |
| de-meaned vs every seat at 0 | 22/180, all in rounds 11-15 | 0.016 | 0.019 / 0.021 / 0.018 / 0.016 | 0 |
| stress: unshrunk raw vs raw de-meaned | 98/180 | 0.082 | 0.039 (slot 1) | 0 |
| stress: as-is vs as-is +30 (deep in the clamp) | 175/180 | 0.159 | 0.410 (slot 1) | 578/2142 |

How to read the table:

- "Cells with a different player" is dominated by modal cells with low probability
  flipping. Even the seed+1 noise floor changes 139 of 180 cells, so that column
  overstates the practical effect.
- The same-player probability shifts, 0.089 against 0.019 for noise, are real and
  beyond noise.
- At the user's own picks the offset is within Monte Carlo noise, except for kickers in
  the late rounds (up to 0.19).

**Answer to Q3.** For the simulation, the offset is close to harmless for rounds 1-2 and
for skill positions. Its measurable effect runs through the clamp, onto K/DEF timing in
the late rounds. Removing the offset is **not** validated as more correct, because there
is no backtesting (a deliberate choice). The engine should stay as it is. The finding
that matters is about the display.

### Which claim concerns what

- **Display (the finding):** the reach number shown on `/managers`, in the seat popover
  and on manager history is mostly baseline, not behaviour. On prod the bar says "reaches"
  for 37 of 37 managers, because shrinkage pulls every one of them toward a positive mean.
  Measured.
- **Simulation:** a uniform offset affects the sim only through `valueDeltaClamp`. The
  harness measured K/DEF timing shifts up to 0.19 at one user pick and within noise
  elsewhere. Nothing should change in the engine on this evidence.

### Recommendation: A, relative display, anchored to the room, plus the deterministic half of B

- **A, but de-meaned per draft, not by the league mean.** Each draft carries its own
  offset (+8.6 / +9.2 / +13.1 on 09-07; +5.0 / +5.7 / +8.5 on prod's board). A single
  league mean would still tilt managers from the 12-team draft toward "reaches". Show
  "N picks earlier/later than the rest of their draft room". Mark anything within ~1
  standard error as "drafts like the room". Leave the engine's `reachBias` untouched.
- **B-lite: make the snapshot choice deterministic** (nearest `captured_on` to the draft
  date, ties to the earlier date) and correct the javadoc. This does not make the baseline
  *right*: no pre-draft board exists for 2026. It makes the number reproducible, and the
  same in both environments once each is re-stamped. It changes engine inputs slightly;
  per Q3, the effect runs only through the clamp.
- **Not recommended now:**
  - removing observed order from the reach baseline, or excluding rounds 12-15. Both are
    defensible, but each is a baseline redesign that would re-rank managers (ρ ~0.5), and
    there is nothing to validate it against. Record it as an option, not a fix.
  - retuning `weights.yml`. That is forbidden, and none of these results call for it.

"Change nothing and label it" (C) was considered and rejected. A label cannot fix a bar
that points right for 37 of 37 managers.

### Follow-up fix brief (for a Sonnet build agent; not built)

**Goal:** the reach display compares each manager with the other managers in the same
draft room. It says plainly when the difference is within noise. Engine behaviour is
unchanged.

**Files and changes**

1. `backend/src/main/java/com/ballknowers/draftsim/profile/ProfileService.java`, in `fit()`:
   - Compute the mean of `adpAtTime - pickNo` per `draftId` over the scoreable picks.
   - For each manager, compute `relativeReachBias` = the mean over their scoreable picks
     of `(adpAtTime - pickNo) - draftMean[draftId]`.
   - Also compute `relativeReachStdErr` = the sample standard deviation of those per-pick
     residuals / √n. It is null when n < 2.
   - Add both to `Fit`, as `Map<Long, Double>` fields next to `empiricalReachBias`.
   - Do **not** touch `reach`, `leagueMeanReach`, the shrinkage, or anything
     `ManagerProfile.reachBias` feeds.
2. `api/ManagerController.java`, `describe()`: add `relativeReachBias` and
   `relativeReachStdErr`, rounded to 2 dp and nullable. Build the map mutably, as it is
   now; per the `Map.of` rule, never `Map.of` with a nullable value.
3. `api/LeagueController.java` (the seats route, beside `seat.put("reachBias", ...)`) and
   `api/LeagueHistoryController.java` (beside `derived.put("reachBias", ...)`): expose the
   same two fields.
4. `web/src/api.ts`: mirror the new fields on `ManagerSummary`, the seat type and the
   manager-history type, in the same change.
5. `web/src/pages/ManagerTendencies.tsx`:
   - `ReachAxis` plots `relativeReachBias` when it is present.
   - When it is absent, keep today's no-number message. Do not fall back to effective
     reach, because that is the offset this brief removes.
   - Caption: "X picks earlier than their draft room" / "later than". When |rel| ≤ SE,
     say "drafts like the room".
   - The sort by |reach| uses |relative|.
   - Add a one-line note on the page: "Measured against the other managers in the same
     draft, not the market board. The board itself runs several picks off for every room;
     see audit 11."
6. `web/src/managerBehaviour.ts` `behaviourText`: use the relative value and the same
   noise band, so the seat popover and `/managers` cannot disagree. This is one function,
   per that file's own rule.
7. Optional, separate commit (B-lite): in `BoardService.backfillAdpAtTime`, select exactly
   one snapshot per draft. Use a `distinct on (d.id)` subquery ordered by
   `abs(captured_on - start_time::date), captured_on`, then join it. Fix the javadoc's
   "newest" claim. Test it with an IT that has two in-window snapshots holding different
   ranks, asserting that the nearer one wins. Run the SQL: it has shipped the
   UPDATE/JOIN trap before (bug class 2).

**Acceptance criteria**

- **Unit test, preference ordering (bug class 1):** a fixture with two drafts that have
  different offsets. A manager who picks exactly at their room's average gets relative
  ≈ 0. One who picks 10 earlier than their room gets +10, whatever the room's own offset.
- **The engine is untouched.** With a fixed seed, a `MonteCarloRunner` result on a
  fixture is byte-identical before and after (no change to `ManagerProfile.reachBias`).
- **Live, against local data** (without re-ingest): `/api/managers?sport=nfl` returns
  `relativeReachBias` for 37 managers.
  - Mean ≈ -0.8. It is not exactly 0 because managers are averaged per manager, not per
    pick.
  - 22 negative, 15 positive (measured here against the 09-07 stamping).
- **In a real browser,** `/managers` shows bars on both sides of zero, and a "drafts like
  the room" label for most managers. The seat popover wording agrees with the page.
- `npx tsc -b`, the web tests and `./gradlew test` all pass. Check the skipped count:
  ITs skip silently when Postgres is down.
