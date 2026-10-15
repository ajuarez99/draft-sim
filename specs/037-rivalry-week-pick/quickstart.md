# Quickstart: verify spec 037

Same setup as spec 034's verification.md:
- the worktree backend on **8081**, against a scratch copy of the shared DB, with
  `--refresh.on-visit.enabled=false` so nothing writes Sleeper data
- the worktree vite on **5174**
- signed in as popsharky

No migration and no ingest are needed (research R1). Check that 8081/5174 aren't another
session's servers first (AGENTS.md: concurrent sessions).

## §1 — Unit suite, constraint and ordering first

```bash
cd backend && ./gradlew test --tests "*RivalryMatching*" --tests "*RivalryWeekPicker*" --tests "*RivalryProperties*"
```

These must pass, and the skip count must be **0**. A Postgres-down run reports success with
the ITs skipped (memory: backend suite skips ITs silently).

The tests pin SC-003:
- a pair at 2 meetings outside week W is never paired in W
- a pair whose 2nd meeting **is** W may be paired in W, and is marked already scheduled
- among feasible weeks, the higher total wins
- ties resolve to the earliest week
- an odd league's sit-out is unconstrained
- infeasible weeks land in `unfitWeeks`, and an all-infeasible league gives `NO_WEEK_FITS`
- existing `RivalryMatchingTest` cases pass **unchanged**, so the overload didn't move spec
  034's answers

Then the full suite: `./gradlew test`, again with the skip count checked.

## §2 — Live payload invariants (SC-001, SC-002, SC-004)

For leagues 4, 210, 9465 and 3 (2026), `GET /api/leagues/{sleeperId}/rivalries`. A script
(kept in the spec folder) recomputes from the payload's own `seasonGrid.cells`, and nothing
else:

1. **Cap.** For `recommended` and every entry in `others`, substitute its pairings into its
   week, then count every pair's regular-season meetings. **0 pairs above 2.**
2. **Eligibility.** No offered week is `≤ seasonGrid.currentWeek`, a played week, or an
   `isPlayoffWeek` column.
3. **Held back.** Each `heldBack` pair really is at 2 meetings outside that week, and
   `meetsInWeeks` matches the grid.
4. **Displaced.** These are exactly that week's scheduled pairs minus the chosen ones.
   `stillMeetsInWeeks` matches the grid.
5. **Shape.** Everyone is paired once (one sit-out when odd), and `reason.numbers` matches
   `reason.text`.

Record the following, and don't predict them:
- per league: the recommended week, `heldBack` count, `tiedWeeks`, and how many eligible weeks
  are unfit
- how many history-best pairs are in the "meets twice" column (research R2's open number,
  checked against R2's labelled guess)
- the endpoint's latency, against spec 034's 25–250 ms

## §3 — Edge statuses

- **Past season**, e.g. "(Foot) Ball Knowers" 2025: `recommendation.status` is `NO_HISTORY`,
  so `week` is null.
- **A completed season with history** (pick the earliest one that has a predecessor): `week.status`
  is `NO_ELIGIBLE_WEEK`, and the history-best pairings still render.
- **`max-regular-season-meetings` removed from a scratch weights.yml:** the response is
  `CONFIG_MISSING`. Restore it afterwards.

## §4 — Browser (real UI, both sports)

Open `/leagues/{id}/rivalries` for "(Foot) Ball Knowers" 2026 and NBA "Ball Knowers" 2026 on
5174, and check:
- the headline week and reason match §2's payload
- held-back sentences name real weeks
- "What this replaces" opens and lists names
- "Other weeks" expands to a runner-up's own pairings
- the "doesn't change your Sleeper schedule" note is present
- phone width (375): no horizontal scroll in the panel

Take a screenshot of each sport for verification.md.
