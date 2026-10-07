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
