# Implementation Plan: AI weekly recap (premium, behind a flag)

**Branch**: `020-ai-weekly-recap` | **Date**: 2026-10-06 | **Spec**: [spec.md](spec.md)

**Input**: roadmap Phase 5 ([claude/competitor-gap-roadmap.md](../../claude/competitor-gap-roadmap.md)).
Allan, 2026-10-06:
- Haiku 4.5, recap only, no API key yet.
- "Feature flag anything behind the Claude dependency and have it as a premium feature for later."
- "Write me an excerpt of each agent and an example for me to compare" (see
  [Example excerpts](#example-excerpts-for-comparison) below).

**Status: planned and reviewed; nothing built.** The adversarial review's findings are all
addressed below. The next stage is `/speckit-tasks`, then the build (AGENTS.md pipeline). The roadmap ordered Phase 5 last because "it reads Phase 2–4 data". The
recap-only scope reads only the weekly report, which already exists, so Phases 3–4 aren't
prerequisites. The historian is the part that would want them.

## Summary

A recap card at the top of the Weekly Report for a **final** week:
- a headline and 3–5 short sections;
- written by one Haiku 4.5 call with a structured-output schema;
- generated in the background on the first view and stored, while the card polls (amended, F9);
- regenerated when the week's numbers, team names, model or prompt change, with the reason shown
  (amended, F2, F7);
- a failed regeneration keeps the last good recap, and transient failures retry (amended, F1, F11);
- **shown only if every number in it appears in the input fields it cites.** That check runs in
  production, not only in tests.

It's off by default, globally (`RECAP_ENABLED`) and per league (admin-granted `league_feature`
row). That's the stand-in for a premium tier, with no billing or paywall UI.

Measuring before planning changed two things from the design doc:
- **The input is ~5 KB, not ~20K tokens** (R1). The cost estimate drops about 10×, to roughly a cent
  per league-week on Haiku (R2, still an estimate).
- **Two meanings of "final"** (R4). A recap written when a week is counted can be contradicted by
  a stat correction the following week. So the input is hashed, and a changed hash regenerates and
  labels the recap "revised".

## Amended after review (2026-10-06)

[plan-review.md](plan-review.md) read the plan cold. It checked the plan against the code, the DB,
the captured weekly-report JSON and Spring Boot's bytecode, and ran the grounding rule as written
in a scratch script. It confirmed the input sizes, every excerpt number and index, V29 being free,
and the Haiku 4.5 API facts. It found 12 problems and 13 notes. Allan asked for all of them to be
addressed.
- data-model.md, contracts/api.md and quickstart.md are rewritten in place, with dated notes at the
  top.
- research.md and spec.md carry inline amendments.

| # | Finding | Disposition |
|---|---|---|
| F1 | A stored FAILED row blocks retries forever, transient failures included | **Fixed.** Deterministic failures (`UNGROUNDED`, `REFUSED`, `TRUNCATED`, `MALFORMED`) stay on their key. Transient ones (`API_ERROR`, `RATE_LIMITED_UPSTREAM`) get `retry_after` = +15 min (ARBITRARY). Admin `regenerate` clears any failure. V6 tests |
| F2 | The hash leaves out model and prompt, so V4 would return one stored recap 15 times and a prompt fix never lands | **Fixed.** The cache key is input + model + `prompt_version` (prompt file + derived schema). V4 runs through an admin `preview` route that never touches storage. A model or prompt change counts as a spend event in R2 |
| F3 | The input side of the number match was undefined; the plan's own examples flipped verdict by reading | **Fixed.** R5 pins it: numeric leaves plus number tokens inside strings, records and ISO dates atomic, `season`/`week` in every pool, 2 dp. Number and ordinal words map to values instead of being banned, and month-name dates normalize. The unit test is the review's table, row for row |
| F4 | A broad cite turns the check into a whole-input check, and numbers aren't tied to the subject | **Fixed.** Cites must be items, at most 4 per section. A team or player name in a body must be in that section's cited items. R5 says what the check proves and what it doesn't |
| F5 | Which row is entitled and which stores the recap was unspecified; NBA 2026's id resolves to 2025 | **Fixed.** Entitlement, storage, flight key and caps all use the **resolved** row. `season` is in the response. "Grant the season being played" is stated. A test shows two ids give one row and one call |
| F6 | Derived margins are raw doubles (`67.57999999999998`), and `scoreRank` would be a second rank rule | **Fixed.** `BigDecimal` 2 dp. One `competitionRank` helper extracted from `deservedBetter`. A margins unit test |
| F7 | The hash covered current fields (team names, a player's current pro team), and torn reads mid-refresh | **Fixed.** Pro team dropped. Names kept, since the recap must match the page, and a rename is labelled `NAMES_CHANGED` via a numbers-only hash. `weekFinal` comes from the same `Result`. No generation while the chain is refreshing |
| F8 | "No usernames sent" is false for 21 real rosters | **Fixed, by wording.** FR-001 and R6 say a nameless team goes out under its owner's display name, as on the page. Substitution was rejected because the recap would disagree with the page |
| F9 | A blocking GET can run for minutes, and the week stepper burns the cap | **Fixed.** Async: GET returns `GENERATING`, and the card polls every 3 s. A new `SingleFlight` instance (cap of 2 inherited, stated). The card asks only after a week has been on screen for 1 s |
| F10 | No test builds the real SDK request; `minItems/maxItems` unsupported; refusal must be checked before `.text()` | **Fixed.** A request-construction test with a dummy key, no network. 3..5 sections enforced in Java → `MALFORMED`. Canned-`Message` tests pin the stop-reason order |
| F11 | `revised` was seen only by the triggering viewer; a stale body had no model name; a failure overwrote a good recap | **Fixed.** `revisionReason` is stored and every viewer sees it. A stale body carries its `model` and `generatedAt`. The row keeps the last READY body apart from the last attempt, so a failure never erases it |
| F12 | `@ConditionalOnProperty` matches an empty key; no injection when the bean is absent; a record would print the key | **Fixed.** A custom `Condition` (flag and a non-blank key). `ObjectProvider<RecapClient>`, with `FEATURE_OFF` from bean absence alone. The key is read from the environment, never in a properties record. Three context tests |

Notes taken:
- **Admin routes and storage**
  - N1: the admin gate answers 403, not 401. Both `AccessControlMvcIT` lists get the new routes.
  - N2: `ready_input_json` is `text`, so it re-hashes exactly. The timestamp-bind warning is
    softened to convention.
  - N13: the V29 IT leaves tables in the shared dev DB; stated in the quickstart.
- **Cost and the bake-off**
  - N3: the measured reduced input is 3.6 KB (NFL) / 3.0 KB (NBA). The Sonnet row omits thinking.
    Preview uses `max_tokens` 16,000 for models that think. V2 times a second call.
    - **The review was wrong on one point:** it said Haiku's $1 / $5 isn't in the skill docs, but
      the skill's model table lists it. Recorded in R2.
- **Prompt and grounding**
  - N4: an NBA "counted/credited" guard, as a basis violation and a retry.
  - N5: the input goes in a delimited `<league_data>` block, names are opaque labels, and V3 has an
    injection probe.
  - N6: SC-001 rewritten. The human read's wrong-but-passed count is the bar.
- **Caps**
  - N7: calls, not generations. UTC day. Check-and-insert inside the flight. Global cap of 50/day
    (ARBITRARY).
- **Unmeasured, stated**
  - N8: one replica assumed.
  - N9, N10: the second report build and virtual-thread pinning are timed in V2.
  - N11: no NBA in-season grant before `last_scored_leg` is measured after 10-21.
  - N12: record the SDK version used. The construction test catches a Jackson clash.

## Technical Context

**Language/Version**: Java 21 / Spring Boot 3.5; TypeScript + React + Vite.

**Primary Dependencies**:
- **New:** `com.anthropic:anthropic-java` **2.68.0** (latest on Maven Central 2026-10-06; the skill
  docs' 2.34.0 was only an example). Measured at T002: it asks for `jackson-databind 2.19.4`, and
  Spring Boot 3.5.5's managed version pins it to **2.19.2**, a patch downgrade. T019's
  request-construction test is the check that this is harmless.
- **Existing:** `WeeklyReportService`, `ScoredWeeks`, `LeagueMembership`, `AdminGateInterceptor`,
  `SingleFlight` (refresh package).

**Storage**: V29 adds `league_feature`, `league_recap` and `league_recap_attempt`
([data-model.md](data-model.md)).

**Testing**:
- **JUnit:**
  - grounding checker: the R5 table, row for row
  - input builder: no user fields, hash independent of the viewer, margins at 2 dp
  - request construction with a dummy key (F10)
  - canned-`Message` response handling (F10)
  - service with a fake `RecapClient`:
    - refusal, max_tokens, MALFORMED
    - ungrounded → retry → fail
    - key change → regenerate with a reason
    - transient `retry_after`
    - a failure keeps the READY body
    - the resolved-row dedupe
    - both caps
    - flag off → zero calls
- **IT:** V29 round-trip (lessons class #3), plus the `AccessControlMvcIT` additions
- **Context tests:** off, on with a blank key, on with a key (F12)
- **Vitest:** card states including `GENERATING` polling and the 1 s on-screen delay
- **Live:** quickstart V1 now, V2–V5 once a key exists

**Target Platform**: Railway backend + static web (existing).

**Performance Goals**: GET with a stored, matching row costs one weekly-report computation, the
same as the page itself. That's **unmeasured** and not trivial (N9), so V2 times it. GET never
blocks on the model (F9). Generation latency is unmeasured (3–8 s is a guess, and V2 times it).

**Constraints**:
- The key stays server-side (FR-008).
- No call is ever made unless both gates hold (FR-007).
- No user identifiers leave the server (R6).

**Scale/Scope**: ~6 leagues × ~20 weeks/season. That's ~$1/season on Haiku (estimate) plus
regenerations, and the per-league daily cap bounds the worst case.

No NEEDS CLARIFICATION remains: model, scope, gating and key status were answered by Allan, and the
rest is resolved in research R1–R9.

## Constitution Check

The constitution is still the template, so the gates are AGENTS.md's, as in plans 009–019.

| Gate | Status |
|---|---|
| Honesty over apparent confidence | ✅ The card says it's AI-written, names the model and says numbers were checked. Ungrounded text is never shown. A revised recap says so. Failures show a reason, never a blank |
| Verified vs. assumed | ✅ R1 measured; R2 and R4's latency labelled estimates; R5's limits (small integers, names) stated; the excerpts below labelled hand-written |
| Migrations append-only | ✅ New V29 only |
| `api.ts` mirrors records | ✅ Same change (FR-010), closed `state` union |
| `Map.of` with nulls | ✅ The recap response has nullable fields on every state, so it's a mutable map |
| Two implementations of one rule | ✅ Finality from `ScoredWeeks.isFinal`, numbers from `WeeklyReportService`. Derived margin/rank is computed once, in the input builder, not by the model |
| Optional params encoding rules | ✅ Model id, cap and flag are explicit config. Entitlement per **season** is stated as a rule (R7), not left to a default |
| Ask before committing | ✅ Nothing committed. The plan sits uncommitted in worktree `.claude/worktrees/020-ai-weekly-recap` |
| Coding subagents on Sonnet | Build stage, when it comes |

Post-design re-check: same table. The design added an outbound dependency, and FR-007 and the
context test keep it inert by default.

## Project Structure

### Documentation (this feature)

```text
specs/020-ai-weekly-recap/
├── spec.md
├── plan.md              # this file
├── research.md          # R1–R9
├── data-model.md        # V29, records, api.ts
├── quickstart.md        # V1 (no key) … V5
├── contracts/api.md     # recap GET, admin grant, outbound call
└── tasks.md             # /speckit-tasks, not yet
```

### Source Code (repository root)

```text
backend/src/main/java/com/ballknowers/draftsim/
├── recap/                          # new package
│   ├── RecapProperties.java        # draftsim.recap.{enabled, model, caps, transient-retry-minutes, timeout}; NO key (F12)
│   ├── RecapEnabledCondition.java  # enabled == true AND non-blank ANTHROPIC_API_KEY (F12)
│   ├── RecapConfig.java            # @Conditional(RecapEnabledCondition) → AnthropicRecapClient bean; key read from env
│   ├── RecapClient.java            # interface; AnthropicRecapClient (real) / fake in tests; stop_reason before parse (F10)
│   ├── RecapInputBuilder.java      # Result (viewer null) → RecapInput, BigDecimal derived fields, cache key + numbers hash
│   ├── GroundingCheck.java         # pure; R5 as amended
│   ├── RecapService.java           # ObjectProvider<RecapClient>; gates → resolved row → finality → key → stored? → caps → background flight
│   ├── RecapController.java        # GET /api/leagues/{id}/recap/{week}
│   └── AdminRecapController.java   # grant/revoke, regenerate, preview (F1, F2)
├── engine/WeeklyAwards.java        # competitionRank extracted from deservedBetter (F6)
├── config/WebConfig.java           # + "/api/admin/**" on AdminGateInterceptor
└── store/LeagueFeatureRepository.java, LeagueRecapRepository.java
backend/src/main/resources/
├── db/migration/V29__league_recap.sql
├── recap/system-prompt.md
└── application.yml                 # recap block, RECAP_ENABLED default false
web/src/
├── api.ts                          # RecapResponse
├── components/RecapCard.tsx (+ .test.tsx)   # 1 s on-screen delay, polls GENERATING, stale/revised/model lines
└── pages/WeeklyReport.tsx          # renders RecapCard above matchups; nothing for FEATURE_OFF / NOT_ENTITLED
```

## Example excerpts for comparison

> **Read this first.** No API key exists on this machine, so **none of the text below is model
> output.** The planning session wrote it by hand, on the **real** (Foot) Ball Knowers 2026 week 3
> report, to show the shape and what the grounding check accepts and rejects. The differences in
> voice between the three model columns are **a guess** at typical behavior: Haiku more literal and
> shorter, Opus more likely to connect awards to matchups. **Don't pick a model from these.**
> Quickstart V4 replaces them with real outputs from all three models on this same input.

The input facts used are all in the week 3 report:
- **Matchups:**
  - Master Bates 188.48 (3-0) d. Khatt Stafford 120.9 (1-2)
  - Dart has hit anotha Bower 147.54 (2-1) d. Likely Have Downs 114.9 (0-3)
  - jpelwell 126.84 (3-0) d. Torta Pounder with Cheese 112.42 (2-1)
- **Top performer:** Jahmyr Gibbs, 41.4 vs NYJ
- **Awards:**
  - GOT_AWAY_WITH_IT: 86% of optimal, 23.10 left on the bench
  - DESERVED_BETTER: 6 of 11
  - ONE_PLAYER_CARRY: Smith-Njigba 35.36 of 114.90, 31%
  - SELF_INFLICTED_WOUND: lost by 14.42

**Derived and put in the input by Java (FR-002), so the model may say them:** Master Bates' margin is
67.58. Dart's margin is 32.64. The week high is 188.48 and the low 88.9.

### Haiku 4.5 (chosen): illustrative

> **Master Bates hits 188.48 and stays perfect**
>
> **Week's best.** Master Bates scored 188.48, the top total of week 3, and beat Khatt Stafford by
> 67.58 to move to 3-0. Jahmyr Gibbs led every player with 41.4 against NYJ.
> *cites: /matchups/3, /weekHigh, /topPerformers/0*
>
> **Tough break.** Khatt Stafford outscored 6 of 11 other teams and still lost. They're 1-2.
> *cites: /awards/1, /matchups/3*

### Sonnet 5.5: illustrative

> **Three still unbeaten, and Master Bates is the loudest**
>
> **The statement game.** Master Bates put up 188.48, the week's high, and turned a matchup with
> Khatt Stafford into a 67.58-point rout. Jahmyr Gibbs carried a big share with 41.4 against NYJ.
> That's the kind of week that makes a 3-0 start look earned rather than lucky.
> *cites: /matchups/3, /weekHigh, /topPerformers/0*

~~The headline says "Three", a number word, so the check rejects it (R5 rule 2).~~ *Amended after
review (F3):* number words now map to values instead of being banned. "Three" = 3, and the headline
is checked against the whole input, where `week` = 3. So it **passes**, for the wrong reason: the
input has no count of unbeaten teams. That's the small-integer weakness R5 names, and it's why V3's
human read is the success measure (SC-001).

### Opus 5.5: illustrative

> **Got away with it, and paid for it**
>
> **Dart's lucky win.** Dart has hit anotha Bower beat Likely Have Downs 147.54 to 114.9 while
> playing only 86% of their best lineup and leaving 23.10 on the bench. Likely Have Downs had no
> answer beyond Jaxon Smith-Njigba, whose 35.36 was 31% of their starters' points. They're 0-3.
> *cites: /matchups/0, /awards/0, /awards/2*
>
> **The bench decided it.** Torta Pounder with Cheese lost to jpelwell by 14.42, and the Broncos
> sitting on their bench outscored the Packers they started by 16.00.
> *cites: /matchups/5, /awards/3*

### What a rejected one looks like (any model)

> Master Bates' 188.48 beat the next-best score in the league by 33.32.

`33.32` is 188.48 − 155.16, a number the model computed. It's not in the input, so the result is
`UNGROUNDED` and one retry runs with that token listed. If the retry repeats it, the card shows
"Recap unavailable this week (numbers couldn't be checked against the report)", and the text is
never shown. A second near-miss that **passes**:

> Gibbs found the end zone three times.

*Amended after review (F3):* the first draft said "three" fails the number-word rule and "3 times"
would pass. Under the pinned rule both read as 3. With cite `/topPerformers/0` alone, the pool has
`week` = 3, so it **passes while being unsupported**: touchdowns aren't in the input at all.
That's R5's named limit, and V3's human read exists to catch it.

*Amended after review (F3, F4):* under the pinned rule both the Haiku and Opus excerpts above pass.
Every number is in its cited items or is `week`, and every team or player named is in a cited item.
They're rows in the GroundingCheck test.

### Roast tone (not in v1): illustrative

> **Likely Have Downs, unlikely to have wins.** 0-3, and Jaxon Smith-Njigba scored 35.36 of their
> 114.90, 31% of the lineup. The rest of the starters should be paying him rent.

It's grounded, and it's the kind of line Allan's leagues might want. It's out of v1 because a roast
is the likeliest refusal path, especially on team names like the real ones in this league. Decide
after V3 and V4.

## Complexity Tracking

| Addition | Why it's needed | Simpler alternative rejected because |
|---|---|---|
| First paid outbound dependency | The feature is LLM text | No non-LLM way to write prose recaps |
| Runtime grounding check | Never show a number the data doesn't have (the project's core value) | Test-only checking proves 5 weeks, not every week in production |
| Three tables (feature, recap, attempt log) | Entitlement must outlive recaps; the cap must count overwritten attempts | Config allowlist (a deploy per grant); counting from `generated_at` undercounts |
| Input hash + regeneration | Stat corrections land after `isFinal` (R4) | Generating at `mayStopRefetching` makes the NFL recap a week late |
