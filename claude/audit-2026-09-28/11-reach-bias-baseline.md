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
