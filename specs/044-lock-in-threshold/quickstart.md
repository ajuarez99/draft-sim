# Quickstart / validation: spec 044

## Prerequisites

- The local Postgres on 5433 (`draftsim`) with NBA leagues 211 (2025) and 212 (2024) ingested.
- The worktree backend, run on its own port through a `.claude/launch.json` entry. Per memory
  `worktree-preview-serves-main-checkout`, check that the bootRun classpath is this worktree.

## Checks

1. **Hand computation (SC-001, SC-003)**. Rerun the research script (research R2/R5) for one manager, one player and
   one payoff cell of 2025. Then `curl localhost:<port>/api/leagues/<2025 sleeperId>/lock-ins` and compare `n`,
   `median` and the cell's `n`/`rate`. Both should agree exactly at 3 dp.
2. **Inference totals (SC-002 as amended)**. Check that `totals` matches R2's table for 2024 and 2025, that
   unmatched is ≤ 3% of `multiGame`, and that the invariant sum holds.
3. **Gate (SC-006, FR-011)**. Run the same call on the football league. It should give `status NOT_LOCK_IN`, and its
   analysis page should show no panel and no console errors.
4. **UI (SC-004)**. Open `/leagues/<2025>/analysis`. Every margin shown should carry its baseline kind and n. The
   baseline toggle should swap the whole panel. On 2026, the Projection series should say too few or empty, not 0%.
5. **Player page**. Open a player with ≥10 locks. The lock list's count should equal the summary's `n`.
6. **Timing (SC-005)**. Measure the cold and warm response time of `/lock-ins` for 2024 (the largest season), and
   the analysis page load. The page should load in under 2 s.
7. **Regression**. The existing `PlayerTrendsService.oneGameShare` tests should still pass unchanged after it
   delegates to `CreditedGames`.
