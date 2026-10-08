# Spec 021: pull requests

Four **stacked** PRs. Branch `021-codebase-cleanup` is linear, so each PR is the commits up to
one phase boundary, based on the PR before it. **Merge none of them before the freeze lifts**
(nothing to main 2026-10-09 through the end of the 10-10 NBA draft). Every merge auto-deploys
both Railway services, so check both report the merged commit afterwards (DEPLOY.md).

| PR | Branch to push (cut at) | Base | Contents |
|---|---|---|---|
| 1 | `021-us1-removals` @ `01c1468` | `main` | spec/plan/review/tasks, baseline + GoldenJson oracle, US1 removals, constitution, prune record |
| 2 | `021-us2-helpers` @ `3dd0292` | PR 1 | one rounding helper, one ordinal, the labelled "21th" → "21st" fix |
| 3 | `021-us3-typed-responses` @ `1129ba4` | PR 2 | four controllers to records, characterization goldens, live verification |
| 4 | `021-us4-splits` @ `72923da` (current head) | PR 3 | CSS / api.ts / superlatives / page splits, docs index, final end-to-end check, HANDOFF |

> **Deviation from tasks.md, stated:** the plan said one PR per US4 file. These are four PRs, one
> per user story, because every merge restarts production during draft season. The US4 commits
> are still one per file (`6f65ee9`, `eb35636`, `ae86bb7`, `e243971`), so each can be reviewed
> or reverted alone. If you'd rather have one PR per split, cut at those commits instead.

---

## PR 1: Spec 021 US1: remove dead dev artifacts (and the spec itself)

**What.**
- Adds the full spec 021 trail: spec, plan, adversarial plan review, cross-artifact analysis,
  tasks and measured baseline.
- Removes the dev-only Power Rankings verify harness (379 lines), its DEV-gated route, and the
  18 lines of `.verify-*` CSS.
- Removes the two mockup PNGs that `web/public` was **serving in production**
  (`GET /pr-reference/2a-front-page-desktop.png` → `200 image/png`, 627 KB, measured).
- Replaces the placeholder speckit constitution with AGENTS.md's six hard rules, quoted
  verbatim (checked by diff).

**How it was proven.**
- FR-003 order: the four builders only the harness exercised are pinned by
  `PowerRankings.builders.test.ts` first, green across the deletion.
- Evidence for every removal is in `removal-evidence.md`.
- Backend 1346 / 0 skipped; web 1083; `tsc` clean; migrations untouched.

**Also here:** the `GoldenJson` test oracle (and its self-test) that PR 3 builds on.

**After deploy:** check `/pr-reference/*.png` no longer returns `image/png`. Production's SPA
fallback answers 200 HTML, so check the content type, not the status (T016).

🤖 Generated with [Claude Code](https://claude.com/claude-code)

---

## PR 2: Spec 021 US2: one rounding helper, one ordinal

**What.**
- `util/Rounding` replaces 21 private `roundN` helpers in 17 files and the literal inline copies.
  `PlayoffOddsService:422` stays inline on purpose: it is a different function (5005/10000 gives
  0.501 vs 0.5, measured).
- `DraftGradesService`'s boxed overload becomes `round2OrNull`, mapped call by call, so no call
  changes which method it binds to.
- `format.ts` is the one `ordinal`.

**How it was proven.**
- `RoundingTest`: bit-for-bit equality with the old formulas over 100,000 seeded doubles plus
  edge values.
- `format.test`: the two old ordinal algorithms agree everywhere (0..1000, negatives, NaN, ±∞).
- Built CSS byte-identical; backend 1351 / 0 skipped; web 1086.

**The one visible change, isolated in `3dd0292`:** Power Rankings' own ordinal printed "21th";
it now says "21st". It's reachable only at rank ≥ 21, and is revertable alone.
`PowerRankings.ordinal.test.ts` fails on the old code and passes on the new.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

---

## PR 3: Spec 021 US3: typed responses for the four biggest controllers

**What.**
- LeagueHistoryController, SuperlativesController, LeagueController and WeeklyReportController
  now send success bodies as records in `api/dto/`, grouped by response family.
- 59 of 100 in-scope map builds converted (SC-004 target 50%; counted multi-line-aware in
  `baseline.md`).
- Error bodies and SSE payloads stay maps, as scoped.

**How it was proven, per controller.**
1. A characterization commit pins the JSON (goldens, standalone MockMvc, no DB), green on the
   map code.
2. The conversion commit may not touch those files (checked: 0 every time).
3. Injected mutations of the review's failure modes, **12 in total, all caught**, then reverted.

Gaps found and pinned before converting:
- `compute()`'s `realizedSkipped` has three wire states (absent, null, reason), so it is an
  `Optional` under `NON_NULL`;
- the live SSE frame carries the same pick rows as the REST board.

A `GoldenJson` long/int bug and a vacuous-write-mode bug were found and fixed along the way.

**Live:** origin/main vs this branch on the same DB returned **37/37 identical JSON**, and every
affected page was clicked through.

**`api.ts` drift fixed (types only; no caller read these):**
- `submitBallot.saved` is a boolean, not a number;
- `SeatsResponse.status` can be null;
- `TrackResponse.draftId` was missing;
- `computePowerRankings` lacked `playoffOdds`, `week0Skipped` and a nullable `realizedSkipped`.

**Out of scope, deliberately not converted (T043):** `ErrorHandler`; `MemberSetupController`
(its bodies are mostly errors); `MockDraftController`; `SimulationController` (SSE);
`IngestController`; `SleeperUserController`; `HealthController`; and every controller in
`recap/` and `refresh/`. The optional six (T037–T042) are not done.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

---

## PR 4: Spec 021 US4: split the oversized files

**What, one commit per split:**
- `styles.css` → a 7-piece `@import` index (`6f65ee9`);
- `api.ts` → 25 domain files behind the same module path (`eb35636`);
- SeasonSuperlativesService 1,586 → 852 lines plus 5 package-private classes (`ae86bb7`);
- LeagueAnalysis.tsx → 260 lines plus 7 components, and PowerRankings.tsx's logic →
  `powerRankingsStory.ts` (`e243971`).

Plus a status index of all 53 `claude/` docs, the final end-to-end check, and HANDOFF.

**How it was proven.**
- **CSS:** pieces reassemble byte-identically, and the built CSS sha256 is unchanged.
- **`api.ts`:** the same 194 exports; 0 of 108 importers changed; all 26 `vi.mock('../api')`
  tests pass.
- **Superlatives service:** a line-multiset diff shows only the 8 intended lines changed; live
  parity, pre-split vs split, is 9/9 leagues and all 13 kinds identical.
- **Pages:** byte-identical rendered DOM, pre-split vs split, on 5 pages.
- **Final tree vs origin/main:** **37/37 JSON identical and 10/10 pages byte-identical DOM.**
- Backend 1386 / 0 skipped; web 1087.

**Decisions:**
- PowerRankings.tsx stays at 1,244 lines (owner's call): the SC-005 exception, recorded in the
  spec.
- LeagueHistoryController was not split; PR 3 already took it to 713 lines.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
