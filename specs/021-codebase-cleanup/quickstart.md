# Quickstart: proving spec 021 changed nothing

All commands run from the `../draft-sim-021` worktree. Never use the main checkout:
other sessions share it.

## 0. Baseline (once per phase, before the first change)

```bash
cd backend && ./gradlew test 2>&1 | tail -5     # record tests run AND skipped
cd web && npx vitest run && npx tsc -b && npm run build
ls -la web/dist/assets/*.js                     # record bundle sizes
```

If the backend skip count is in the dozens, Postgres or Docker is down. Start it
before trusting anything (see the memory note on silent IT skips).

## P1: removal

1. Record the C1 evidence for each item ([C1](contracts/C1-removal-evidence.md)).
2. Delete the items, then rerun the baseline commands. The tests and skip count
   should match; a test that imported a verify-only export will fail loudly, and
   that's the point.
3. `npm run build`, then confirm `web/dist/pr-reference/` doesn't exist.
4. After deploy, check that the production PNG URL no longer returns `image/png`.

## P2a: helpers

1. Add the agreement test: the old ordinal algorithms against the new one for
   n ∈ [0, 1000], and the old `round_k` against `Rounding.round(x, k)` over a
   fixed seed of 10⁵ doubles, including 1.005, 2.675 and negative values.
2. Swap the call sites and rerun the baseline.

## P2b: one controller

Follow [C2](contracts/C2-response-equivalence.md) exactly. To review it:

```bash
git log --oneline -3                       # seam? → characterization → conversion
git show --stat HEAD | grep -E "golden/|Characterization" && echo "FAIL: conversion edited the oracle"
```

Optional smoke check: hit the converted endpoint on a running local backend, before
and after, **without re-ingesting in between**, and diff with `jq -S`.

## P3: one split

Follow [C3](contracts/C3-split-equivalence.md). For CSS, take screenshots at 1280
and 375 px on every route, before and after.
