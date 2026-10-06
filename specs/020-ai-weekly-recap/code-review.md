# Code review: spec 020 (AI weekly recap)

**Date:** 2026-10-06
**Stage:** bug-hunting code review, run cold against the uncommitted working tree in the worktree
`020-ai-weekly-recap` (base `origin/main` @ 36f77ca). This is not a style pass. No source file
was edited.

**What I reviewed:**
- The `git diff`: build.gradle.kts, DraftSimApplication, WebConfig, WeeklyReportService (the
  `competitionRank` swap), application.yml, AccessControlMvcIT, api.ts, WeeklyReport.tsx(+test),
  styles.css.
- The untracked files: everything under `recap/`, LeagueFeatureRepository,
  LeagueRecapRepository, WeeklyAwards, V29, `recap/system-prompt.md`, RecapCard.tsx and the new
  tests. I also read `refresh/SingleFlight.java` and the parts of WeeklyReportService the input
  is built from (`recordAfter`, `Side`, `forWeek`).

I read these against contracts/api.md, data-model.md, research R4/R5/R7 (with the review and
build amendments), plan-review.md F1–F12 and N1–N13, verification.md and AGENTS.md.

**What I ran (measured):**
- A scratch probe of `GroundingCheck.check` (scratchpad only, compiled against the worktree's
  current `build/classes`, which are newer than the sources): 28 adversarial bodies against a
  two-matchup NFL input with a lowercase username team (`jpelwell`), a hyphenated name
  (`Puka-Boo`) and a number-word name (`Three Amigos`). Results are quoted per finding.
- `javap` on the SDK's `LoggingHttpClient` (anthropic-java-core 2.68.0) for its header
  redaction list.
- `git ls-tree` on every local branch and `origin/main`: no other V29 exists; V28 is the highest.

I did not run the backend suite, the ITs, vitest, or a browser. Nothing here touched the
Anthropic API.

**Checked and found sound** (read unless noted):
- **Key leakage.** No path found. The key goes env → `RecapConfig` → SDK builder and is never
  stored in a field or a properties record. `AnthropicRecapClient.call` maps SDK exceptions to
  messages holding only a status code or a class name. `RecapService` logs only
  `getClass().getSimpleName()`. `failure_detail` holds grounding tokens or a parse message. The
  SDK's own logger redacts `authorization`, `api-key`, `x-api-key`, `cookie` and `set-cookie`
  (measured via `javap`). One caveat: `ANTHROPIC_LOG=debug` would log full request and response
  bodies, meaning league data, though not the key. Don't set it in prod.
- **Gate order** matches the contract table: FEATURE_OFF → NOT_ENTITLED → WEEK_NOT_FINAL →
  READY → FAILED (deterministic, or transient before `retry_after`) → refresh guard →
  RATE_LIMITED → GENERATING. Except for the race in R6, no path calls the client when the
  contract says it shouldn't.
- **Attempt/READY split.** `writeFailure` never touches `ready_*`, headline, sections or
  revision (`LeagueRecapRepository.java:150-168`). READY is decided by `key.equals(readyKey)`,
  so a FAILED attempt on a new key still serves the old body as stale.
- **SingleFlight.** `run` on a key already in flight returns the same future. The concurrency
  cap (2) queues on a semaphore rather than rejecting, and `isRunning` is true while a run is
  queued. `pending` entries are removed in `whenComplete`, so they don't leak.
- **UTC cap day.** `LocalDate.ofInstant(now, UTC).atStartOfDay().toInstant(UTC)` is
  independent of the server's TZ. `Timestamp.from(Instant)` follows the repo's convention and
  is round-tripped by `LeagueRecapRepositoryIT`, including null `?::jsonb` binds (lines 124
  and 143).
- **`Map.of`.** None appears in a response path. `RecapController.body` is a `LinkedHashMap`.
- **api.ts parity.** `RecapResponse` has the same 11 keys as `RecapController.body`. The
  `state`, `revisionReason` and `failureReason` unions match the Java enum and the string
  literals the service writes.
- **RecapCard lifecycle.** The 1 s delay is keyed on `[sleeperLeagueId, week]`. Cleanup aborts
  the request and clears the timer. A late response after an abort is dropped. A fetch error,
  FEATURE_OFF and NOT_ENTITLED render nothing. No state renders body text without the
  "Written by AI (model)" line, because the footer sits inside the `hasBody` block.
- **Grounding cite handling** (measured): `/matchups/00`, `/matchups/0/`, `/matchups/0/home` and
  `/matchups/9` are all badCites. Thousands separators (`1,205`) are parsed and checked. The
  week number never backs an ordinal (`3rd` fails).

## Findings

### R1. High, measured: section titles are never checked, so an invented number or name in a title passes

`GroundingCheck.java:108-137` tokenizes `s.body()` and binds names in `s.body()` only.
`s.title()` is never read. The NBA basis guard (lines 133-136) is also body-only, and so is the
headline (lines 100-105 check numbers but bind no names and apply no basis guard).
`RecapCard.tsx:100` renders every title as a teal small-caps heading, directly above the footer
"numbers checked against this report".

- **Measured:** the title `"jpelwell's 7th straight win, Mahomes 40"` with the body
  `"jpelwell scored 120.5."` cited `/matchups/0` → **PASS**. Neither 7 nor 40 is in the input.
  The title `"Puka-Boo's revenge"` on a section citing only jpelwell's matchup → **PASS**.
- **Failure scenario:** models routinely put the hook in the title ("A 42-point blowout",
  "Third straight loss for X"). Any of those reaches the page as checked text.
- **Fix:** check `title + " " + body` as one string per section, for numbers, names and the
  NBA basis rule. For the headline, at least apply the basis guard. Add a GroundingCheckTest row
  for a title-only invented number.

### R2. High, measured: name binding is case-sensitive, so a capitalized username escapes it and its score can be misattributed

`containsWhole` / `wholeName` (`GroundingCheck.java:261-267`) compile `Pattern.quote(name)`
without `CASE_INSENSITIVE`. `withoutNames` (line 248) uses the same pattern. R6's amendment
measured 21 rosters whose team name is the owner's lowercase-ish Sleeper display name
(`jpelwell` in the week-3 sample).

- **Measured:** `"Jpelwell put up 131.1."` cited `/matchups/1` (Puka-Boo's game, where 131.1 is
  Puka-Boo's score) → **PASS**. That's a wrong team credited with another team's number.
  `"PUKA-BOO scored 120.5."` cited `/matchups/0` → **PASS**, with jpelwell's score given to
  Puka-Boo.
- **Failure scenario:** the model capitalizes a lowercase handle at the start of a sentence,
  which is ordinary English. The name is then invisible to binding, and the number check only
  asks whether 131.1 occurs somewhere in the cited items, which it does.
- **Fix:** compile `wholeName` with `CASE_INSENSITIVE | UNICODE_CASE`, for both blanking and
  binding. Keep the pool-text match case-insensitive too. Add the "Jpelwell" row to the test
  table.

### R3. Medium, measured: an en-dash record isn't a record token, so a false record passes on small integers

The `rec` group (`GroundingCheck.java:64`) accepts only ASCII `-`. `"2–7"` and `"7–2"` (U+2013)
split into the bare numbers 2 and 7. Those are matched against any 2 and 7 in the cited items,
such as scoreRanks, and records are atomic in the pool, so they never help.

- **Measured:** `"jpelwell moved to 7–2."` cited `/matchups/0` (true record `2-1`) → **PASS**.
  The same text with a hyphen, `7-2`, → FAIL (as it should).
- **Failure scenario:** Claude models often typeset records and scores with en-dashes. A wrong
  record is a likely hallucination, and this is the form it would take.
- **Fix:** accept `[-–—]` in `rec` (and in `iso` for symmetry) and normalize to `-` in the
  token key. Add a test row.

### R4. Medium, read: "Revised after a scoring correction" fires for changes that aren't scoring corrections

`RecapService.writeReady` (`RecapService.java:283`) labels `NUMBERS_CHANGED` whenever the
numbers-only hash differs. That hash (`RecapInputBuilder.numbersHash`) blanks names and nothing
else, so it also moves when any of these change:
- a performer's `position`, which is the player row's *current* position;
- `opponent` / `isAway` going from null to set when a schedule is ingested after the week was
  first recapped;
- the top-10 set changing because a missing `player` row arrives (`pl == null` is skipped in
  `forWeek`);
- award `kind`/`detail` wording, `basis`, `reason` codes or `sectionsUnavailable` changing in a
  deploy.

`RecapCard.tsx:105-107` then prints "Revised after a scoring correction."

- **Failure scenario:** a schedule backfill (the spec 017/019 kind) lands after week 3 was
  recapped. Week 3 regenerates on its next view (a paid call) and tells the league a scoring
  correction happened. None did.
- **Fix:** hash only the scoring projection (points, margins, records, scoreRanks,
  gamesPlayed, week high/low) for `NUMBERS_CHANGED`. Label any other input change with a
  neutral reason (`INPUT_CHANGED` → "Rewritten after this report's data changed"), or say
  nothing. api.ts's union changes with it.

### R5. Medium, read: a stale body is still labelled "numbers checked against this report" and "from before the latest scores"

`RecapCard.tsx:111-115` shows the footer "numbers checked against this report" on every body,
stale ones included. `RecapCard.tsx:95` labels every stale body "Previous version, from before
the latest scores."

- **Failure scenario 1 (contradiction on the page):** a stat correction changes a score. The
  regeneration fails UNGROUNDED, or the cap is spent. The card shows the old body, whose
  numbers now differ from the matchups directly below it, under "numbers checked against this
  report". That claim is false for exactly the bodies where it matters most.
- **Failure scenario 2 (wrong cause):** a model or prompt change, or a rename, makes every
  opened week stale while it regenerates or while the caps are spent. Its copy then says the
  scores changed, and they didn't.
- **Fix:** when `stale`, change the footer to "numbers checked against the report when it was
  written" (or drop "this report"). Make the stale line cause-neutral: "An earlier version;
  this week's data has changed since."

### R6. Medium, read: switching `RECAP_MODEL` to a thinking model makes every generation TRUNCATED

R8 says the model is config and "the request builder sends effort only to models that accept
it, so switching to Sonnet or Opus for the bake-off is a config change". `generate()`
(`RecapService.java:218`) always passes `props.maxTokens()` (2,048) and `sendLowEffort=false`,
whatever `props.model()` is. Only `preview` branches (lines 188-191). R9's amendment says Sonnet
5.5's adaptive thinking is on by default, and the build raised preview's limit to 16,000
precisely because thinking counts against `max_tokens`.

- **Failure scenario:** set `RECAP_MODEL=claude-sonnet-5-5` after the bake-off. Thinking
  consumes the 2,048 tokens. `stop_reason=max_tokens` → `TRUNCATED`, which is deterministic, so
  every week stores FAILED and stays there until the key changes. Each one is a paid call.
- **Second, smaller problem:** preview's `thinks = !NO_EFFORT_MODEL.equals(model)` treats a dated
  Haiku id (`claude-haiku-4-5-20251001`) as a thinking model and sends `effort: low`, which
  Haiku doesn't accept → 502.
- **Fix:** one helper, `requestShape(model)` → (maxTokens, effort), used by both paths, matching
  the Haiku family by prefix. Or reject a non-Haiku `draftsim.recap.model` at startup until
  that's built.
- *Not executed:* whether Sonnet 5.5 truncates at 2,048 is inferred from R9's own amendment, not
  measured.

### R7. Low–medium, read: an exception after `logCall` that isn't from the client writes no attempt row, so every poll re-pays

In `generate()`, only `client.call` is inside a try (`RecapService.java:217-226`). If
`GroundingCheck.check` (e.g. an NPE on a null `Section` element), `writeReady` (a DB error, or
`JsonProcessingException` → `IllegalStateException`) or `writeFailure` throws, the flight
completes exceptionally, and only the class name is logged. No FAILED row exists, so the card's
next 3 s poll starts a new flight and a new paid call. That repeats until the per-league cap (10)
is spent.

- **Fix:** wrap the loop body. On an unexpected exception after a successful call, write
  `MALFORMED` (or a new deterministic `INTERNAL`) with the exception class in `failure_detail`.
  A related point: a non-Anthropic `RuntimeException` from `client.call` is classed `API_ERROR`
  (transient, line 222). If its cause is deterministic, such as a parse-path bug after a billed
  response, it is retried and paid for every 15 minutes up to the cap.

### R8. Low, read: a GET racing a flight's completion can start a duplicate paid call, and the revision count then rises for no reason

`view()` reads the row (line 109), then checks `flight.isRunning` (line 122), then calls
`flight.run` (line 137). SingleFlight releases the key before completing. A flight that finishes
between `find` and `run` lets this GET start a second flight for the same key. `generate()`
doesn't re-read the row before calling. The second READY write then bumps `revision` to 2 with
`MODEL_OR_PROMPT_CHANGED`, although nothing changed. The window is a few ms (a refresh-status
lookup plus two count queries) per poll per viewer. A cross-instance version during a rolling
deploy (N8) can also let an *older* key's flight overwrite a newer READY body.

- **Fix:** at the top of `generate()`, re-read the row and return if `readyKey == key`, or if
  `attemptKey == key` with a deterministic FAILED. In `writeReady`, refuse to overwrite when
  the stored `ready_key` already equals the key being written.

### R9. Low, read: the caps can be overshot, and preview isn't blocked by them

`capsSpent` + `logCall` aren't atomic (`RecapService.java:213-214`). Two flights for
*different* leagues (the SingleFlight cap is 2) can both read 49 and both insert, giving 51. The
per-league cap can likewise go one over across two weeks of the same league. `preview`
(lines 189-191) logs a call but never checks `capsSpent`, so the global cap counts it but never
stops it. The contract only says "counts against", so this is admin-only and acceptable if
intended, but worth saying in the contract.

- **Fix (if wanted):** `insert … select … where (select count(*) …) < cap` returning a row
  count, or a `pg_advisory_xact_lock` around check-and-insert. In preview, check caps before
  calling.

### R10. Low, read: the resolved row is resolved twice

`view()` resolves via `resolver.resolve(sleeperId)` (line 90) for entitlement, storage and caps.
`weeklyReport.forWeek` resolves again internally for the content and `season`. If a new season
gets its first score between the two calls, the entitlement and storage row (A) and the
content (B) differ, and a B recap is stored under A. The window is tiny.

- **Fix:** have `forWeek` accept a pre-resolved row, or return the resolved league id in
  `Result`.

### R11. Low, measured: smaller tokenizer escapes (all near-vacuous or unlikely given the prompt)

| Body text | Verdict | Why |
|---|---|---|
| `jpelwell scored 157pts.` | PASS (false) | an integer glued to letters produces no token at all |
| `scored 2.7x what Fent Machines did` | PASS (false) | backtracking yields `2` (the decimal is dropped because `x` follows); 2 is a scoreRank |
| `157.3pts` | checks `157` only | same backtracking |
| `lost by -22.2` | PASS | the sign is dropped; inputs can be negative (D/ST, kickers) |
| `topped a hundred and fifty` | PASS (false) | no words past `twenty`; `hundred`/`thousand` aren't tokens |
| `scored １５７` (fullwidth) | PASS (false) | `\d` is ASCII-only |
| `120.504` | PASS | rounded to 2 dp before matching |

- **Fix:** for the `num` group, require a decimal not to be followed by a letter (fail closed:
  treat `\d+(\.\d+)?[A-Za-z]+` as a token that must match its numeric part). Add `thirty`–`ninety`,
  `hundred`, `thousand` as "always unmatched" words. Use `UNICODE_CHARACTER_CLASS` or reject
  non-ASCII digits. Low priority, since the prompt mandates digits.

### R12. Low, read: there's no way to pull or regenerate a READY recap that the human read finds wrong

`regenerate` only clears `attempt_*` (`AdminRecapController.java:139-145`). When `ready_key`
equals the current key, `view()` returns READY before looking at the attempt, so a current READY
recap can't be re-rolled or hidden short of revoking the whole league or editing SQL. V3's human
read is the stated backstop for wrong-but-passed recaps (SC-001), and its remedy is unbuilt. The
behaviour matches the contract, so this is a design gap, not a deviation.

- **Fix:** let `regenerate` also null `ready_key` (keeping the body, which is then served as
  stale), or add `DELETE …/recap/{week}`.

### R13. Low, read: edge renders

- `body()` (`RecapService.java:333-338`) turns unreadable stored sections into an empty list.
  `hasBody` in the card is then true, and it renders a headline with no sections above the
  "numbers checked" footer. Return `sections = null`, so no body is shown.
- A READY with a null headline (structured output should prevent it) makes `hasBody(row)`
  false. The card would then render an empty "Week N recap" box with no notice.
- `RecapInputBuilder.build` (lines 72-73) names only the first team on a tie for the week's
  high or low, so "X had the week's high" can be half-true. Consider a list, or omitting
  `weekHigh` on a tie.

## Plan-review items: implemented?

| Item | Status |
|---|---|
| F1 transient retry / regenerate route | implemented (`retry_after`, `clearAttempt`); see R12 for READY |
| F2 model + prompt in key; preview route | implemented; preview shape issue in R6 |
| F3/F4 pinned grounding rule, item cites, name binding | implemented for bodies; titles/headline not covered (R1), case-sensitive (R2) |
| F5 resolved row everywhere | implemented, with a narrow double-resolution race (R10) |
| F6 BigDecimal 2 dp, one rank rule | implemented (`WeeklyAwards.competitionRank`) |
| F7 drop pro team, NAMES_CHANGED, single Result, refresh guard | implemented; NUMBERS_CHANGED over-fires (R4) |
| F9 async GET + polling | implemented |
| F10 stop-reason before parse; 3..5 in Java | implemented (`AnthropicRecapClient.interpret`, `RecapService:230-246`) |
| F11 READY/attempt split; stale keeps model | implemented |
| F12 custom condition, key not in properties | implemented |
| N1 403 admin gate | implemented + IT |
| N4 NBA basis guard | body only (R1) |
| N5 delimited block, `</` escaped | implemented |
| N7 per-call log, UTC day, global cap | implemented, not atomic (R9) |

## Summary

- **R1 (High, measured):** titles, and the headline's names and basis wording, are never
  grounded. An invented "7th straight win, Mahomes 40" title passes.
- **R2 (High, measured):** name binding is case-sensitive. "Jpelwell put up 131.1" passes while
  crediting another team's score.
- **R3 (Medium, measured):** en-dash records aren't records. A false "7–2" passes on scoreRanks.
- **R4 (Medium, read):** NUMBERS_CHANGED, shown as "scoring correction", fires on
  position, opponent, top-10 membership and award-wording changes.
- **R5 (Medium, read):** stale bodies still say "numbers checked against this report" and
  "from before the latest scores".
- **R6 (Medium, read):** `generate()` never adapts `max_tokens` or effort to the model, so
  `RECAP_MODEL=sonnet` → TRUNCATED. Preview misclassifies dated Haiku ids.
- **R7 (Low–medium, read):** a non-client exception after a paid call writes no attempt row, so
  each poll re-pays up to the cap.
- **R8 (Low, read):** a GET racing a flight's completion starts a duplicate paid call and bumps
  the revision.
- **R9 (Low, read):** cap check-and-insert isn't atomic. Preview is counted but never blocked.
- **R10 (Low, read):** the league row is resolved twice (entitlement vs content).
- **R11 (Low, measured):** tokenizer escapes: `157pts`, `2.7x`, negatives, `hundred`, fullwidth
  digits.
- **R12 (Low, read):** no operator route can pull or re-roll a current READY recap.
- **R13 (Low, read):** edge renders: unreadable sections, null headline, tied week high.

No key-leak path found. The gate order, attempt/READY split, SingleFlight join semantics, UTC
cap day, JDBC binds and api.ts parity check out.

## Fixes applied (2026-10-06)

Fix pass on the working tree, decisions made by the parent session. Backend suite: 1338 tests, 0
failed, 0 skipped (Postgres 5433 up, so the ITs ran). Web: tsc and `npm run build` clean, vitest 1073
passed. Tests were written alongside each fix and run green; I did not run each one red against the
old code first, so "fails before the fix" is by construction of the review's scenario, not measured.

| # | Outcome | Test(s) |
|---|---|---|
| R1 | Fixed. Titles checked like bodies against their section's cites; headline checked against the union of all sections' cited items (numbers, names, NBA basis) | `GroundingCheckTest`: `anInventedNumberInATitleFails`, `aNameInATitleIsBoundToTheSectionsCites`, `theHeadlineIsCheckedAgainstTheUnionOfCitedItems`, `aHeadlineNameNoSectionCitesIsUnbound`, `anNbaHeadlineIsHeldToTheBasisRule` |
| R2 | Fixed. Name binding and blanking are `CASE_INSENSITIVE \| UNICODE_CASE` | `aCapitalizedHandleIsStillBoundToItsCites` ("Jpelwell put up 111.84", "PUKA-BOO") |
| R3 | Fixed. NFKC plus en/em dash and U+2212 to `-` before tokenizing | `anEnDashRecordIsOneRecordToken` |
| R4 | Fixed, no migration. Numbers hash is a multiset projection of numeric content only; reasons computed from the stored `ready_input_json` (numbers, then identical-input, then names-blanked hash, else new `REPORT_CHANGED`). Propagated to api.ts, RecapCard, contracts/api.md | `RecapServiceTest.aPositionOrOpponentChangeIsReportChangedNotAScoringCorrection`, `aRenameThatAddsADigitInsideAwardProseIsStillNamesChanged`; `RecapInputBuilderTest.aPositionOrOpponentChange...`, `aRenameKeepsTheNamesBlankedHash...`; `RecapCard.test` revision-reason test |
| R5 | Fixed. Stale footer "Older version · numbers were checked against the report as it was on <date>"; cause line by failure/RATE_LIMITED/GENERATING; "from before the latest scores" removed | `RecapCard.test`: stale body with a failure reason, `a stale body while updating or rate limited names the right cause` |
| R6 | Fixed. One rule `isHaikuModel` (prefix `claude-haiku`) for generate and preview: Haiku gets configured max-tokens and no effort, anything else gets effort low and 16000 | `generateWithASonnetModelSendsLowEffortAndTheBigTokenBudget`, `aDatedHaikuIdIsStillHaikuForGenerate`, `AdminRecapControllerTest.aDatedHaikuIdGetsNoEffortInPreviewEither` |
| R7 | Fixed. Any exception escaping a flight records a transient `API_ERROR` attempt; only the class name is logged | `aPersistenceErrorAfterTheCallRecordsATransientAttemptSoPollsDoNotRepay` |
| R8 | Fixed. The flight re-reads the row before each call and stops if `ready_key` already equals its key | `aFlightWhoseKeyIsAlreadyReadyMakesNoCallAndNoRevisionBump` |
| R9 | Fixed. Cap check plus `logCall` is one JVM-locked step (single replica assumed); preview is refused with 429 `rate_limited` when caps are spent | `twoFlightsRacingForTheLastCallOnTheGlobalCapMakeOnlyOne` (barrier in the race window), `AdminRecapControllerTest.previewIsRefusedWith429WhenTheCapsAreSpent` |
| R10 | Accepted. Noted in research.md R7 | none |
| R11 | Fixed except number words past "twenty" (left, noted in research R5 limits): `157pts`/`2.7x` checked, leading `-` is part of the token, fullwidth digits via NFKC | `unitSuffixesAreStillChecked`, `aNegativeMustMatchANegative`, `fullwidthDigitsAreNormalized` |
| R12 | Fixed. `POST /api/admin/leagues/{id}/recap/{week}/reroll` clears ready and attempt columns, keeps revision; 204. In the AccessControlMvcIT 403 list; documented in contracts/api.md | `AdminRecapControllerTest.rerollPullsAReadyBodySoTheNextGetGeneratesFresh`, `LeagueRecapRepositoryIT.rerollClearsTheReadyBodyAndTheAttemptButKeepsTheRevision`, `AccessControlMvcIT.adminRoutesRefuseWithoutTheAdminToken` |
| R13 | Fixed in the card (READY/stale with nothing to show renders nothing; headline-only when sections are null) and in `RecapService.body` (unreadable sections are null, not `[]`). Tied week high/low accepted, noted in research R5 | `RecapCard.test`: `a READY response with no headline and no sections renders nothing`, `null sections with a headline renders the headline only` |
| Extra | `application.yml` comment: never set `ANTHROPIC_LOG=debug` in production | none |
