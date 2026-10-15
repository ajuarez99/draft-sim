# Rivalry week names a week, with no pair meeting more than twice (spec 037)

## What

Until now, the Rivalries page's rivalry week (spec 034) recommended pairings but never said
which week to play them. This change makes it pick the week and the pairings together, under
Allan's rule: **no pair of managers meets more than twice in the regular season.**

How it works:
- **Which weeks.** Every regular-season week after the current one is considered.
- **Which pairings.** For each week, spec 034's exact matching runs again with every pair that
  would reach a third meeting removed. The cap is `draftsim.rivalry.max-regular-season-meetings: 2`,
  labelled as a league rule, not a fitted number.
- **Ranking.** Total score first, then history-best pairs kept as they are, then earliest week.
  Allan chose this tie-break after the plan review showed that "earliest" alone picks the most
  disruptive of the tied weeks.
- **What the page shows:**
  - the week and why, in concrete numbers, with "as of week N"
  - pairs the rule held back, and the weeks they already meet
  - "already on the schedule" tags
  - the matchups the week replaces, and whether those pairs still meet
  - every other eligible week, with its own pairings
- **When no week can be offered,** a status sentence explains why, and spec 034's pairings
  still show. The statuses are:
  - `CONFIG_MISSING`
  - `NO_SEASON_FORMAT`
  - `NOT_PUBLISHED` (including a partly stored schedule)
  - `UNSUPPORTED_ROSTERS`
  - `NO_ELIGIBLE_WEEK`
  - `NO_WEEK_FITS`

There's no migration and no new query. Spec 034's `pairings` are unchanged, and a test asserts
that.

## Measured (local, scratch copy of the dev DB)

| League | Recommended | History-best pairs already meeting twice |
|---|---|---|
| "(Foot) Ball Knowers" 2026 | Week 13 (12.75 of 13.25, 3 held back) | 5 of 6 |
| West Coast 2026 | Week 14 (all 7 best pairs fit) | 1 of 7 |
| NBA "Ball Knowers" 2026 | Week 8 (tied with 19, earlier wins) | 4 of 6 |

- **Invariants.** An independent script (`specs/037-rivalry-week-pick/check-week-pick.mjs`)
  recounts from the season grid. It checks the cap, everyone paired once, held-back and
  displaced pairs, and eligibility. It passes on all three leagues once the data is fresh (see
  verification.md for the stale-`leg` case).
- **Latency.** About 100 ms per page, inside spec 034's 25–250 ms.

## Tests

- **Rivalry tests:** 127, 0 skipped.
- **Full backend suite:** 1846 tests, 1 failure. `BoardRepositoryLatestBeforeIT` fails
  identically on untouched `origin/main`, so it's pre-existing.
- **Web:** `tsc -b` clean, vitest passes 2147 tests, the build passes.
- **Browser:** checked for both sports. The NBA page was checked at 375 px.

## Pipeline

Spec → plan → adversarial plan review (16 findings, all accepted, amendments shown in
plan/spec/research) → build (Sonnet) → bug-hunting review (B1 and S1–S3, fixed) → live
verification.

## Not done

- Not deployed.
- NBA eligibility needs a re-check after the 10-20 tip-off.
- `config/weights.yml` will textually merge with `adp-multi-source`, which edits a different
  block of the same file.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
