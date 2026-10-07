# Research: Codebase Cleanup (spec 021)

Every finding is tagged **measured** (a command was run, on 2026-10-07, against
`origin/main` @ `7b7238e` or against production) or **assumed** (not executed).

## R1. Does the verify page or its PNGs reach production?

- **Decision**: Remove both. Only the PNGs are a production issue.
- **Measured**: `GET https://www.ballknowers.co/pr-reference/2a-front-page-desktop.png`
  returns `200 image/png 626914B`, so the PNGs are publicly served. The production
  `assets/index-*.js` bundle (513,414 B) has 0 matches for `BANNED_TERMS`,
  `pr-reference` or `2a-front-page`, but 2 matches for ballot/power-ranking code. The
  verify route is gated on `import.meta.env.DEV` (`App.tsx:148`) and tree-shaken out
  of the bundle.
- **Consequence**: The spec was amended visibly (see its Inventory note). Removing
  the verify page is source hygiene. It also frees `PowerRankings.tsx` from
  exporting builders purely for the harness. **Measured**: `PowerRankings` also has
  real importers (`LeagueHome.tsx` and three `PowerRankings.*.test` files). So the
  build task un-exports only the builders whose sole importer was the verify page,
  and must not assume all 7 can go private.
- **Alternatives considered**: Keeping the harness and moving the PNGs out of
  `public/` and into `specs/013-*/`. Rejected, because the harness's iframe
  comparison needs them served. If the harness goes, the PNGs have no consumer.

## R2. Are the duplicated helpers actually identical? (Prerequisite for "byte-identical".)

- **Decision**: One backend `Rounding` utility and one frontend `format.ts` `ordinal`.
- **Measured, backend**: all 17 rounding copies have the form
  `Math.round(x * 10^k) / 10^k` with k ∈ {1, 2, 3, 4}. Two of them compute the factor
  as `Math.pow(10, places)`. That gives the same semantics: half-up via `Math.round`,
  and the same floating-point product, since `Math.pow(10, 2) == 100.0` exactly for
  small k.
- **Measured, frontend**: there are two different algorithms.
  `draftGrades.ts` and `rankOrder.ts` use the 11–13 special-case switch, while
  `ExpectedWins.tsx` and `Superlatives.tsx` use the `s[(v-20)%10] ?? s[v] ?? s[0]`
  table idiom. By hand-tracing they agree for non-negative integers (`v=0` hits
  `s[-0]`, which is `s[0]`, giving "th"; 11–13 fall through to "th"; 21 gives "st").
  **Not yet executed.** The P2a task must assert that both algorithms agree for
  n ∈ [0, 1000] *before* deleting either one, and must record what each does for
  non-integers and negatives, because they may differ there.
- **Alternatives considered**: `BigDecimal` HALF_UP rounding. Rejected: it would
  change outputs at binary-representation edges such as 1.005, which breaks FR-001.

## R3. Can a `Map<String,Object>` body be replaced by a record without changing the JSON?

- **Decision**: Yes. Convert one controller at a time, with this rule set:
  1. A key always `put`, even with a null value, becomes a plain record component.
     Jackson's default includes nulls, and no global inclusion config exists
     (**measured**: no `jackson` key in `application*.yml`).
  2. A key `put` *conditionally* (`if (x != null) m.put(...)`, or a sport branch)
     becomes a component annotated `@JsonInclude(NON_NULL)` or `NON_ABSENT`. The
     characterization test must include the absent case, because that is the exact
     shape `WeeklyReportShapeTest` documents having shipped broken once (a
     `@JsonInclude` on a record that the map path ignored).
  3. Number types are kept exactly. An `int` put as `Integer` must not become
     `long` or `double` (`1` vs `1.0` is a byte diff).
  4. Key order is ignored by the comparison (it's semantically irrelevant, and record
     component order is declaration order), but a record's component order should
     match the old insertion order anyway, so diffs stay readable.
- **Assumed**: no frontend code relies on key iteration order (`Object.keys` on a
  response). This needs a grep at build time.
- **Alternatives considered**: (a) Leave the maps alone and only add `Map.of` null
  guards. Rejected: that does nothing for the missing-record half of the `api.ts`
  rule. (b) Convert all controllers in one change. Rejected: about 141 sites, with
  silent-failure risk, conflicting with concurrent sessions.

## R4. How do we prove equivalence without live-data drift?

- **Decision**: Characterization tests that call the controller's existing static
  `body(...)` or row-builder seams (which `WeeklyReportShapeTest` and
  `PlayerSpotlightShapeTest` already use) with fixed service results. They serialize
  with the application's `ObjectMapper` and compare against a golden JSON file. The
  test is committed and green on the **old** code first, and the conversion commit
  must leave it green without edits.
- **Measured**: 6 existing test classes already call controller `body`/row seams
  directly. Where a controller has no static seam, the first commit extracts one,
  a pure move that the existing ITs cover. Only then is the golden test written.
- **Alternatives considered**: `claude/scripts/football-parity-hash.py`-style live
  hashing. Rejected as the primary gate: its own docstring records that a board
  rebuild moved 791 of 850 ADP entries and made both baselines report MOVED with no
  code change. It's fine as an extra smoke check, but not as the gate.

## R5. What breaks if `styles.css` is split?

- **Decision**: Turn `styles.css` into an `@import` index of `styles/*.css`, keep
  the import in `main.tsx` unchanged, and keep each `@media` rule next to the
  selectors it modifies.
- **Measured**: `web/src/useNarrow.test.ts:10` reads `src/styles.css` from disk and
  asserts that it contains `@media ${NARROW}` and is over 1,000 characters. An
  `@import` index fails both checks. That test must read the concatenated sheets
  instead, in the same commit.
- **Measured**: `main.tsx:5` is the only import of the stylesheet.
- **Risk (assumed)**: cascade order. `@import` preserves source order only if the
  index lists the files in the original top-to-bottom order. The split must cut on
  contiguous ranges, with no reordering, and this has to be checked visually at 1280
  and 375 px on every page.

## R6. Splitting `api.ts` (108 importers)

- **Decision**: Move the code into `web/src/api/<domain>.ts` and turn `api.ts` into
  `export * from './api/<domain>'`.
- **Measured**: 108 files import from `./api` or `../api`.
- **Note**: a type-only re-export through `export *` is fine under the current
  tsconfig. Whether `isolatedModules` or `verbatimModuleSyntax` is on is **assumed**
  to be compatible; `npx tsc -b` is the check.

## R7. Worktrees and branches

- **Decision**: Generate a candidate list by command, show it, and delete only after
  explicit confirmation.
- **Measured**: 37 worktrees (`git worktree list`) and 54 local branches already
  merged into `origin/main`.
- **Rule**: a worktree is a candidate only if its HEAD is an ancestor of
  `origin/main` (`git merge-base --is-ancestor`) **and** `git -C <wt> status
  --porcelain` is empty. Detached-HEAD worktrees, such as `018-draft-grades`, are
  checked the same way. Any worktree that fails either test is reported but left
  alone.

## R8. Constitution

- **Decision**: Fill `.specify/memory/constitution.md` from the AGENTS.md hard rules
  (7 principles, already written as rules), with AGENTS.md named as the source of
  truth so the two cannot drift. Delete the file only if the owner prefers. This is
  an owner choice, so it is flagged in tasks and not decided here.
