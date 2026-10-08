# C2: Response equivalence (P2b, one controller per change)

The interface being protected is each endpoint's JSON body, the thing `web/src/api.ts`
types describe.

## Commit sequence (per controller)

1. **Seam commit** (only if needed): extract the body-building code into a
   package-private static method that takes the service result(s) and returns the
   body. This is a pure move; the existing ITs and tests must pass unchanged.
2. **Characterization commit**: `<Controller>CharacterizationTest` builds fixed
   inputs covering:
   - each sport branch (NFL and NBA) where the controller branches on sport;
   - every nullable field both **null** and **present**;
   - every conditionally-omitted key both **absent** and **present**;
   - an empty-list case for every list.

   It serializes with the Spring-configured `ObjectMapper` and compares against
   `src/test/resources/golden/<controller>/<case>.json` using a key-order-insensitive
   but otherwise exact comparison: number types must match (`1` ≠ `1.0`), and `null`
   is not the same as an absent key. This commit is green **on the old code**.
3. **Conversion commit**: the maps become `dto` records and `api.ts` is updated if
   needed. The characterization test and its golden files are **not edited** in this
   commit (reviewers check this with `git show --stat`).

## Pass criteria

- Steps 2 and 3 are both green, and the step-3 diff touches 0 files under
  `golden/` or `*CharacterizationTest*`.
- The backend suite's skip count is not above the baseline taken at step 1.
- `grep -c "new LinkedHashMap\|Map\.of("` for that controller drops to 0, or the PR
  explains each survivor.

## Amended after review (2026-10-07)

Per [plan-review.md](../plan-review.md) findings 1–8, the following supersede the
steps above:
- **Step 1 (seam)** applies only to controllers that already have, or trivially
  expose, a pure body builder. **LeagueHistoryController, LeagueController and
  SuperlativesController are characterized via MockMvc with stubbed services**
  (status code and body), following `ManagerControllerMvcIT`.
- **Step 2** uses `Jackson2ObjectMapperBuilder.json().build()`, not "the Spring
  `ObjectMapper`" (which a unit test doesn't have). It also covers each key's
  final-state variants (e.g. `makesPlayoffsPct` both null and present), and compares
  **order-sensitively inside dynamic-key maps**. Existing tests that cast the body to
  `Map` are rewritten to `JsonNode` assertions **in this commit**.
- **Pass criteria:** **0 IT skips** (Postgres up) replaces "≤ baseline". The
  map-count criterion counts success bodies only; error bodies and SSE payloads stay
  maps.
