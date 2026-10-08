# Adversarial plan review: spec 021

This review read the plan cold on 2026-10-07, before any code existed. It checked
the plan against branch `021-codebase-cleanup` @ `452072a` (code identical to
`origin/main` @ `7b7238e`), against production, and with throwaway scripts kept
in the session scratchpad, never in the repo. Each finding is labelled
**MEASURED** (a command was run, and its output is quoted) or **INFERRED** (read
from code, not executed).

## Claims confirmed

| Claim | Result |
|---|---|
| No global Jackson config, so nulls are included (R3.1) | MEASURED: there is no `spring.jackson`, no `Jackson2ObjectMapperBuilderCustomizer` and no `@JsonComponent` in `src/main`. `@JsonInclude` is only used on `recap/RecapInput` |
| 141 map builds in `api/` | MEASURED: C2's own grep, summed over `api/*.java`, gives 141 |
| Engine maps are internal | MEASURED: `engine/` has 46 map builds, but no engine file builds a `Map<String, Object>`, so they are correctly out of scope |
| 17 backend rounding files, all `Math.round(x*10^k)/10^k` | MEASURED: 17 files with 21 methods, k ∈ {1,2,3,4}. `Math.pow(10,k)` is exact for these k (the JDK spec guarantees it) |
| The two ordinal algorithms in the plan agree | MEASURED (node, n ∈ [0,1000] plus negatives, non-integers, NaN, ±Infinity, 1e21): **0 differences**. Both give `-1th`, `1.5th`, `NaNth` and so on. But see finding 9: a fifth copy exists |
| The only other file in `web/public` is `favicon.svg` | MEASURED: nothing else dev-only ships |
| `isMe`/`isAway` record components keep their names | MEASURED with jackson-databind 2.19.2 (from the Gradle cache): `{"isMe":null,"isAway":true}`, the same as the map |
| NaN serializes identically from a map and from a record | MEASURED: both give `"score":"NaN"` |
| The golden comparison can be order-insensitive and type-strict | MEASURED: `JsonNode.equals` treats `{"a":1,"b":[1.0]}` as equal to the same keys reordered, and treats `1` as not equal to `1.0` |
| A CSS `@import` index keeps the cascade order (R5) | MEASURED with the repo's Vite 6.4.3 on a scratch project: it produces one bundle in source order, `@media` stays in place, and the lazy chunk's CSS is unaffected. Vite also hoists a remote `@import url(...)` placed in a *non-first* split file |
| `api.ts` has no module-level state, and the barrel is type-safe (R6) | MEASURED: the only top-level values are the `const`s `API_BASE`/`API_TOKEN` (`api.ts:185,191`). There is no default export, `isolatedModules: true` is set, and `verbatimModuleSyntax` is not. R6's "assumed" can be relabelled measured |

## Findings

1. **BLOCKER: R3 rule 2 classifies by syntax, and that drops a real null.**
   - *Plan:* "A key `put` *conditionally* (`if (x != null) m.put(...)`) becomes a component annotated `@JsonInclude(NON_NULL)`" (research.md R3.2).
   - *Evidence:* `attachPlayoffOdds` does `if (pct != null) entry.put("makesPlayoffsPct", pct)` (LeagueHistoryController.java:596). But `entryRow` already put `makesPlayoffsPct` as null on every entry (:657), so the key is always present. Applying rule 2 as written makes it absent. MEASURED: a record with `@JsonInclude(NON_NULL) Double makesPlayoffsPct = null` omits the key on 2.19.2.
   - *Amendment:* classify each key by its **final emitted state on every path**, not by the `put` that looks conditional. The golden cases must include `makesPlayoffsPct` as null.

2. **BLOCKER: R3 has no encoding for row builders that are shared and then mutated.**
   - *Plan:* rules 1 and 2 assume each key is either always present or conditionally absent (research.md R3).
   - *Evidence (INFERRED, from reading the code):*
     - `standingRow` (:290) is the base for two different shapes. History rows get `rankStatus`, `finalRank` and `finalRankWeek` through `withFinalRank` (:186-189), plus `teamName` and `isMe` (:135-138).
     - Career rows from `careerSeasonRow` (:483-486) get `counted`, and have *no* `finalRank`/`teamName`/`isMe`. So `finalRank` is a present null in one shape and absent in the other, and neither a plain component nor `NON_NULL` reproduces both.
     - Power entries are read back through casts while they are being built (`(Long) e.get("managerId")` at :559-561, `(Integer) entry.get("week")` at :593).
     - `SuperlativesController.detailRow` (:300+) builds 8 sealed subtypes, each with a hand-written `"type"` discriminator.
   - *Amendment:* add R3 rule 5. One record type per **emitted shape**, not per builder. A shape that one path extends becomes its own record, and a value that is read back during the build becomes a typed local. Polymorphic detail rows get an explicit `String type` component per record, not `@JsonTypeInfo`, unless the golden test proves the latter is byte-identical.

3. **BLOCKER: existing tests cast bodies to `Map`, and the ITs among them skip silently.**
   - *Plan:* "the conversion commit must leave it green without edits" (R4), and the gate is "skip count is not above the baseline" (C2).
   - *Evidence:* MEASURED, 37 `(Map<String, Object>)` casts across 17 test files. They include `LeagueHistoryCanCommissionIT`, `SuperlativesControllerIT`, `SuperlativesStandingsIT` and `LeagueControllerSeats{Owner,Unset}OwnerIT`. `WeeklyReport`, `PlayerSpotlight` and `SeasonForecastShapeTest` call `body(...).get()` and `.containsKey()`. After a conversion, an unchecked cast compiles and then throws `ClassCastException`. If Postgres is down, the ITs skip and the suite stays green, and a baseline that already includes those skips passes the gate.
   - *Amendment:*
     - P2b requires **0 IT skips** (Postgres up), not "≤ baseline".
     - Each controller's task lists the tests that touch its body.
     - Those tests are moved to JSON-level assertions in the *characterization* commit, so the conversion commit edits no test.

4. **BLOCKER: the "static `body()` seam" mostly doesn't exist for the three controllers P2b targets, and error bodies are out of its reach.**
   - *Plan:* "Where a controller has no static seam, the first commit extracts one, a pure move" (R4).
   - *Evidence:*
     - `LeagueController` has 0 static seams. `seats()` (:123-232) interleaves `profiles.fit`, the reversal lookup and `membership.canCommission` with the map building.
     - `LeagueHistoryController.history()` calls `power.finalRankForSeason` and `rosterSeasons.forLeague` *per season* inside its loop (:105-150).
     - `SuperlativesController.body` is `private static` (:179), and `conductListBody` is an instance method that hits the repositories (:142-156).
     - Extracting any of these means building a parameter record and prefetching, which is a restructure, not a move.
     - MEASURED: 22 of the 77 map builds in the worst three are inline error bodies `body(Map.of(...))` sitting after auth checks (LHC 12, LC 6, SC 4). No seam reaches them, yet SC-004 says the "three worst reach 0".
   - *Amendment:* characterize these three at the **MockMvc** level with stubbed services. The pattern already exists in `ManagerControllerMvcIT` and `ScheduleControllersMvcIT`, and it covers status codes and error bodies as well. Alternatively, scope error bodies out and amend SC-004 to say so.

5. **SHOULD-FIX: "the Spring-configured `ObjectMapper`" isn't available to the tests C2 describes.**
   - *Plan:* C2 step 2 says "serializes with the Spring-configured `ObjectMapper`".
   - *Evidence:* MEASURED, the existing seam tests use `new ObjectMapper()` (LeagueControllerPoolTest:120, LeagueControllerTrackTest:85, ManagerControllerMvcIT:82). A plain unit test has no Spring bean, and `new ObjectMapper()` lacks the Jdk8 and JavaTime modules that Boot registers. Today the response maps stringify their dates (WeeklyReportController:116), so this is latent, not live.
   - *Amendment:* name the mechanism, either `Jackson2ObjectMapperBuilder.json().build()` or `@JsonTest`, and use it in every characterization test.

6. **SHOULD-FIX: a record's helper methods leak into the JSON.**
   - *Plan:* this isn't covered in data-model.md's record conventions.
   - *Evidence:* MEASURED, a record with `isEmpty()` and `getLabel()` serialized extra `"empty":true,"label":"x"` keys.
   - *Amendment:* add a convention: response records have no `get*`/`is*` methods beyond their components, or those methods carry `@JsonIgnore`.

7. **SHOULD-FIX: key order is user-visible for dynamic-key maps, and the comparison ignores it.**
   - *Plan:* "Key order is ignored by the comparison (it's semantically irrelevant…)" (R3.4). Also "Assumed: no frontend code relies on key iteration order".
   - *Evidence:* MEASURED by grep.
     - `ManagerHistory.tsx:292` renders `Object.entries(h.positionalTilt)` **unsorted**. `positionalTilt` is a `Map<Position, Double>` (ManagerProfile.java:30).
     - `seedOdds` is keyed by `String.valueOf(seed)` (SeasonForecastController:103).
     - Neither can be a record. A conversion that copies either through `Map.copyOf` or a `HashMap` reorders the page, and an order-insensitive golden test passes anyway.
     - The rest of the R3 grep: one key-presence check (`'rosterId' in d`, Superlatives.tsx:433, on the `SuperlativeDetail` union), and `body.error ?? body.message` (api.ts:171).
   - *Amendment:* add rule 6. A dynamic-key map stays a `Map` and keeps its concrete type, and the golden comparison is order-sensitive *inside* those maps. Record the grep result in R3 and drop the "assumed".

8. **SHOULD-FIX: two error-key conventions, plus SSE payloads with no stated scope.**
   - *Plan:* this is silent.
   - *Evidence:* MEASURED.
     - `"error"` is used by ErrorHandler, MemberSetupController and MockDraftController. `"message"` is used by LHC, LC and SC.
     - The SSE `error` event is read as `payload.message` (api.ts:1622).
     - SSE payloads are `Map.of` too: LeagueController heartbeat (:565), and SimulationController started/progress/error (:98-130).
     - A shared `ErrorBody` record is the obvious "cleanup", and it would silently rename one of the two keys.
   - *Amendment:* state that error and SSE bodies are either out of scope, or converted with per-key records that keep their current key. Unifying `error`/`message` is a behavior change and belongs in a separate spec.

9. **SHOULD-FIX: there are five `ordinal` implementations, not four, and the fifth disagrees.**
   - *Plan:* "4 frontend `ordinal()` copies" (spec Inventory, plan Summary).
   - *Evidence:* MEASURED.
     - `PowerRankings.tsx:139` exports a third algorithm: `n===1?'st':n===2?'nd':n===3?'rd':'th'`. It has about 20 call sites in that file.
     - Against the switch algorithm it differs on **267 of n ∈ [0,1000]**, starting 21, 22, 23, 31, ….
     - `RankBoard.tsx` imports `ordinal` from `rankOrder`, so it isn't a separate copy.
   - *Amendment:* list it. Merging it changes "21th" to "21st", which is reachable only by a rank ≥ 21, but it is still a change under FR-001. Either record it as a deliberate fix in its own commit, or exclude it explicitly.

10. **SHOULD-FIX: rounding has a boxed overload and inline copies the plan doesn't count.**
    - *Plan:* "all 17 rounding copies have the form `Math.round(x * 10^k) / 10^k`" (R2), with "exactly 1 definition" (SC-003).
    - *Evidence:* MEASURED.
      - `DraftGradesService:519` has a null-preserving `Double round2(Double)`. A primitive-only `Rounding` would throw an unboxing NPE there.
      - There are at least 10 inline copies outside the helpers: ManagerController:162-173, GameScoringService:54, PlayoffOddsService:414/422, Ranker:54, TransactionAnalysisService:390 and WeeklyReportService:384.
      - `PlayoffOddsService:422` computes `Math.round(v * 1000.0 / total) / 1000.0`, which is not `round3(v / total)`. Checked over v ∈ [0, total] for six totals, the two differ 24 times (e.g. 5005/10000 gives 0.501 against 0.5).
    - *Amendment:*
      - `Rounding` gets the boxed overload.
      - Inline copies are replaced only where the expression is literally `round_k(expr)` with operator order unchanged. `:422` keeps its order, or is excluded.
      - SC-003 says which of these two choices applies. The frontend's own `Math.round(v*10)/10` (`draftGrades.ts` `signedPoints`) is also a rounding rule under FR-004. Scope it explicitly.

11. **SHOULD-FIX: the P1 gate says 404, but production returns 200 HTML.**
    - *Plan:* "prod PNG URL 404s after deploy" (plan.md phase table). Spec AS-1 says "the server returns not-found".
    - *Evidence:* MEASURED, `GET https://www.ballknowers.co/pr-reference/does-not-exist.png` returns `200 text/html; charset=utf-8 1036B`. That is `serve -s` doing its SPA fallback (web/Dockerfile CMD). C1 already gets this right.
    - *Amendment:* the gate becomes "content-type is not `image/png`". Amend spec AS-1 visibly.

12. **SHOULD-FIX: P1 leaves four builders untested, leaves dead CSS, and misses an orphan directory.**
    - *Plan:* "the build task un-exports only the builders whose sole importer was the verify page" (R1). FR-003 says a wanted check "MUST be converted to an automated test first".
    - *Evidence:* MEASURED.
      - `ballotBlockState`, `buildDeck`, `recordLabel` and `roomTakeSentence` have no importer except verify, and no test. Once verify goes, nothing exercises them.
      - `styles.css` has 12 `.verify-*` rules (from :3141) that become dead.
      - `.claude/worktrees/phase1-normalization` (dated 2026-09-02) is not a registered worktree, so R7's `git worktree list` never sees it.
    - *Amendment:* P1 adds tests for those four builders (or records that they aren't wanted), deletes the `.verify-*` rules, and lists the orphan directory for the owner.

13. **SHOULD-FIX: R7's rule would select the main checkout, `main`, and brand-new session worktrees.**
    - *Plan:* "a worktree is a candidate only if its HEAD is an ancestor of `origin/main` **and** … `status --porcelain` is empty" (R7).
    - *Evidence:* MEASURED.
      - The main checkout's HEAD `51549c8` is an ancestor. Only its untracked `pr-description.md` keeps it out today.
      - `main-merge-006` has `main` itself checked out.
      - `git branch --merged origin/main` lists **55** branches including `main`, and 33 of them are checked out in a worktree.
      - A worktree a new session has just created (HEAD at `origin/main`, nothing committed yet) passes both tests. MEASURED: `7b7238e` is an ancestor of itself. Peer sessions on this machine are a known hazard (memory note, 2026-09-22).
      - Squash merges fail safe: `merge-013` is ahead by 1 with `git cherry` 0, so it goes to the owner.
      - Ignored files (`status --porcelain` hides them, and `git worktree remove` deletes them): across worktrees, the only non-build one is `.specify/feature.json`.
    - *Amendment:*
      - Exclude the main worktree and the `main` branch by name.
      - Exclude any worktree whose last reflog entry or mtime is under N days old, or that a live session is using.
      - Remove worktrees before branches (`branch -d` refuses a checked-out branch).
      - Show the `--ignored` list next to each candidate.

14. **SHOULD-FIX: the plan doesn't say whether LeagueHistoryController is converted or split first, and either order collides with the dto convention.**
    - *Plan:* P2b goes "worst first", so LHC goes first. P3 splits LHC into three. data-model.md says "one `dto/<Controller>Responses.java` per controller … not shared across controllers".
    - *Evidence:* INFERRED. `standingRow` serves both `history()` and `managerHistory()`, and `entryRow` serves power. The split puts these in different controllers, which forces either a shared dto (against the convention) or a duplicate (two implementations of one rule).
    - *Amendment:* state the order: P2b on LHC first, then the P3 split. Name dto files by **response family**, not by controller.

15. **SHOULD-FIX: C3's oracle for the SeasonSuperlativesService split doesn't touch the service, and the planned package breaks its tests.**
    - *Plan:* "the C2 characterization test for it … passes unedited" and "existing tests compile untouched" (C3). The new units go in `engine/superlatives/` (plan Project Structure).
    - *Evidence:*
      - INFERRED: C2 starts from a fixed `SeasonSuperlativesService.Result`, so it never runs the service.
      - MEASURED: 22 package-private statics are called 64 times from 10 test classes as `SeasonSuperlativesService.x(...)`. Moving them to another package breaks that access.
    - *Amendment:* keep the units in package `engine`, or keep forwarding statics on the service. Before the split, add a service-level golden test: stubbed repositories in, `Result` JSON out.

16. **SHOULD-FIX: about 25 merges means about 25 production restarts, and the plan has no deploy step.**
    - *Plan:* this is silent. Its phase table implies roughly 18 controller PRs, 6 split PRs, plus P1 and P2a.
    - *Evidence:* MEASURED: "Both services auto-deploy from GitHub" (DEPLOY.md:42), and neither `railway.toml` sets watch paths. INFERRED: a backend restart drops in-memory live-draft pollers and SSE streams. The NBA draft is on 2026-10-10.
    - *Amendment:*
      - Add a merge freeze around live drafts.
      - Batch the backend-only PRs.
      - After each merge, check that both services are on the merged commit (DEPLOY.md's "Redeploy re-runs the same commit" and "auto-deploy can be switched off" traps).

17. **NIT: there's a cheaper and stronger CSS gate than screenshots.**
    - *Plan:* the visual check at 1280 and 375 px on every route is the gate (C3).
    - *Evidence:* MEASURED: Vite inlines the `@import` index in source order (see the table above).
    - *Amendment:* make the primary gate "built `dist/assets/index-*.css` is byte-identical before and after (after P1's `.verify-*` removal)". Keep the screenshots as a smoke check. Keep the Google Fonts `@import` as line 1 of the index anyway.

18. **NIT: `api.ts` split hazards.**
    - *Evidence:* MEASURED.
      - 26 test files use `vi.mock('../api', factory)`. They only keep working while every importer goes through the barrel.
      - If any `api/*.ts` imports `'../api'`, that creates a cycle, and a top-level `const` such as `ALL_POWER_RANKING_KINDS` (:1278) can then hit the temporal dead zone (TDZ).
      - "Mirror `web/src/api.ts`" is written into AGENTS.md:124, SeasonSuperlativesService:112 and :1205, and RecapView:16.
    - *Amendment:* add a rule that `api/*` never imports the barrel. Update those references in the same PR.

19. **NIT: constitution.**
    - *Plan:* "7 principles" (R8).
    - *Evidence:* MEASURED: AGENTS.md "Hard rules" has **6** bullets. `speckit-analyze` treats the constitution as "non-negotiable" (`.claude/skills/speckit-analyze/SKILL.md:66`). A copied rule set is itself two implementations of one rule.
    - *Amendment:* fix the count. Prefer a short constitution that quotes the six rules verbatim, with a test or a note that diffs it against AGENTS.md. Alternatively, delete it.

20. **NIT: scope edges.**
    - *Evidence:* MEASURED.
      - There are 25 `@RestController`s. `recap/AdminRecapController` (5), `recap/RecapController` (1) and `refresh/RefreshController` (6) build maps but fall outside the 141.
      - R3.3 overstates one risk: `Integer` and `Long` serialize identically. The real hazard is an int or long becoming a double.
    - *Amendment:* state whether the out-of-`api/` controllers are in scope, and reword rule 3.

## Verdict

**Proceed after amendments.**

- **P1 and P2a** can go to `/speckit-tasks` once findings 9–13 are folded in.
- **P2b must not** go to tasks until R3, R4 and C2 absorb findings 1–8: final-state classification, per-shape records, the Map-cast tests and zero IT skips, MockMvc-level characterization for the three worst controllers, a named mapper, and order-sensitive dynamic maps.
- **P3** needs findings 14 and 15 resolved before the LHC and superlatives splits get tasks.
