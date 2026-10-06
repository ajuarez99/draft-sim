# Research: AI weekly recap (spec 020)

Each item says whether it was **measured**, **read** (from code or docs), or **guessed**.

## R1. The input already exists, and its size (measured 2026-10-06)

Local `bootRun` on `origin/main` @ `36f77ca`, with `X-Sleeper-User` set to a league member:

| League | Week | Final | Bytes | Shape |
|---|---|---|---|---|
| (Foot) Ball Knowers NFL 2026 | 3 (default; latest final, latest scored 4) | yes | 5,176 | 6 matchups, 10 top performers, 4 awards, 0 omitted |
| Ball Knowers NBA 2025 | 10 | yes | 4,054 | 6 matchups, 5 best nights, 5 best weeks, basis `ALL_GAMES_PLAYED`, 1 award |

- **Decision:** the input is `WeeklyReportService.forWeek(id, week, null)`, reduced (R6) and
  given derived fields (R5).
- **Rationale:** it's the page's own numbers. A recap reading anything else could disagree with the
  page under it (the two-implementations rule).
- **Alternatives:** add standings and record-book entries, as the design doc suggested. Rejected
  for v1. Records are already in `Side.record`, and the record book is a second source to keep in
  step. It's a follow-up.

## R2. Cost (estimated, not measured)

Token counts come from bytes and are **guesses** until `count_tokens` runs (quickstart V2):

- **Input:** ~1.5–2K tokens of report plus ~1K of system prompt and schema, so ~3K.
- **Output:** a headline and 3–5 sections, ~500–800 tokens.

| Model | Price in / out per MTok | ≈ per league-week | ≈ per season (6 leagues × 20 weeks) |
|---|---|---|---|
| **Haiku 4.5 (chosen)** | $1 / $5 | **~$0.007** | **~$0.85** |
| Sonnet 5.5 | $2 / $10 | ~$0.014 | ~$1.70 |
| Opus 5.5 | $4 / $20 + always-on thinking | ~$0.03+ (thinking unmeasured) | ~$3.60+ |

Regenerations (stat corrections, R4) and UNGROUNDED retries add to this, up to the daily cap.

> **Amended after review (N3, F2, F7):**
> - **Input size, measured:** the reduced canonical input measures **3,613 B (NFL) / 3,044 B
>   (NBA)**, about 0.9–1.2K tokens. The table above overestimates, in the safe direction.
> - **Sonnet thinking:** the Sonnet row leaves out adaptive thinking, which is on by default. Its
>   real cost is higher, by an unmeasured amount.
> - **Other spend sources:** three more things cost calls beyond stat corrections and retries, all
>   bounded by the caps (10 calls/league/day, 50/day globally, ARBITRARY):
>   - changing the model or prompt (every opened week regenerates once);
>   - a team rename (past weeks regenerate when opened);
>   - preview calls.
> - **Haiku price:** the review said the $1 / $5 Haiku price isn't in the skill docs. That's wrong:
>   the skill's "Current Models" table lists Claude Haiku 4.5 at $1.00 / $5.00. It's still the
>   cached 2026-09-25 table, not a live price check.

**Amended (design doc):** its ~$0.11 per league-week assumed 20K input tokens. Measured bytes say
about a tenth of that. The doc gets a dated correction note in the build PR.

## R3. Haiku 4.5 API facts (read: claude-api skill, cached 2026-09-25)

- **Model id:** `claude-haiku-4-5`. 200K context, 64K max output.
- **Structured outputs:** supported (`output_config.format`). In Java, `.outputConfig(RecapOutput.class)`
  gives a typed `StructuredMessageCreateParams`. Jackson annotations shape the schema.
- **Effort:** `output_config.effort` **errors on Haiku 4.5**. Don't send it. That's one reason
  the model id is config and the request builder branches on it (R8).
- **Thinking:** off unless `{type: enabled, budget_tokens}` is sent. v1 sends none. The grounding
  check, not reasoning, is what keeps numbers honest.
- **Prompt caching:** the minimum cacheable prefix on Haiku 4.5 is **4,096 tokens**. Ours is ~3K, so
  it would silently not cache. Not used.
- **Refusals:** check `stop_reason == refusal` before reading content. The server-side `fallbacks`
  parameter is documented for Opus/Sonnet 5.5 and Fable 5.1, not Haiku 4.5, so v1 doesn't use it.
  A refusal is a stored failure.
- **Java SDK:** `com.anthropic:anthropic-java`, 2.34.0 in the skill's docs. Check for the latest
  release at build time and record which one was used.

> **Amended after review:**
> - **Effort (confirmed-claims row):** the docs say `effort` isn't a Haiku 4.5 parameter. They
>   don't show a 400 for `low`, so "errors" was stronger than the source. Either way, it isn't
>   sent.
> - **Refusals (F10):** with the typed path, `.text()` deserializes, so it throws on a refusal or a
>   truncated body. `stop_reason` is branched on **before** parsing. A canned-`Message` unit test
>   pins the order.
> - **Section count (F10):** the docs list complex array constraints as unsupported in structured
>   outputs, so `RecapOutput` carries no `minItems`/`maxItems`. 3..5 sections is enforced in Java,
>   and outside that range is `MALFORMED`.
> - **SDK version (N12):** 2.34.0 is the docs' example, not a checked latest. Record the version
>   used. A request-construction test (F10) catches a Jackson clash with Spring Boot 3.5.5's
>   managed version.
- **Key:** `ANTHROPIC_API_KEY`, backend only. `AnthropicOkHttpClient.fromEnv()` reads it, but the
  code checks it explicitly so a blank key fails closed (FR-007) instead of throwing on the first call.

## R4. When to generate, and stat corrections (read: `WeekFinality.java`)

`isFinal` ("counted as decided") arrives a week before `mayStopRefetching` ("frozen") in NFL, so a
stat correction can change a final week's report.

- **Decision:** generate **lazily on the first GET** of a week with `ScoredWeeks.isFinal(week)`.
  Store the SHA-256 of the canonical input JSON. Each GET rebuilds the input (cheap, since it's the
  page's own query) and compares hashes. On a mismatch it regenerates, and the response carries
  `revised: true`.
- **Rationale:**
  - Lazy means weeks nobody opens cost nothing. That includes the historical backfill: 2025 and
    2024 seasons are only paid for if someone reads them.
  - The hash catches every input change, from stat corrections to a renamed team, without hooking
    the refresh chain.
- **Alternatives:**
  - (a) Generate in `LeagueRefreshService.refreshChain`. That couples a paid call into the refresh,
    whose failure semantics (`loaded_complete`) are delicate, and it pays for unread weeks.
  - (b) Generate only at `mayStopRefetching`. In NFL the recap would land a week late.
- **Cost of the choice:** the first viewer of a week waits for the call. Haiku latency for ~700
  output tokens is **unmeasured** (guess 3–8 s). The card shows a "writing" state, and V2 times it.

> **Amended after review (F7, F9, N8, N9, N10, N11):**
> - **Async, not blocking (F9).** A worst case of 3 attempts × 60 s × 2 calls is minutes, and
>   `api.ts:466` already treats 30–60 s as a platform timeout risk. So GET starts or joins a
>   background flight and returns `GENERATING`, and the card polls every 3 s.
>   - The flight uses a **new** `SingleFlight` instance keyed `resolvedLeagueId:week`. Its javadoc
>     warns against sharing one with refresh. Its concurrency cap of 2 is inherited, not chosen.
>   - The card sends its first recap GET only after the week has been on screen for 1 s. Stepping
>     through weeks then doesn't spend the cap on weeks nobody read.
> - **What the hash sees (F7).**
>   - The performer's current pro team is dropped from the input.
>   - Team names stay, since the recap must match the page above it. A rename regenerates and is
>     labelled `NAMES_CHANGED`, never "scoring correction", by comparing a numbers-only hash.
>   - `weekFinal` comes from the same `Result` the input is built from, not a second snapshot.
> - **Torn reads (F7).** The refresh upserts week points one roster at a time without a
>   transaction (`LeagueHistoryIngestService:276-299`). Generation is skipped while this chain's
>   refresh is running, and the stored body is served stale. Anything that slips through is
>   bounded by the caps.
> - **One replica (N8).** Single flight is in memory, so it's correct for one replica. A rolling
>   deploy can briefly run two, and the worst case is one duplicate call.
> - **Unmeasured costs, timed in V2:**
>   - one more `forWeek` per page view (N9: per-roster `pairedWithScores` × 12, `players.findAll`
>     4,387 NFL rows);
>   - whether a 60 s OkHttp call on virtual threads pins carriers (N10, a guess).
> - **NBA finality (N11).** NBA in-season finality is unmeasured until `last_scored_leg` is checked
>   after 2026-10-21. Don't grant `RECAP` to an in-season NBA league before then.

## R5. The grounding check (design)

The design doc's acceptance #1 becomes a runtime gate, not only a test.

1. **Derived numbers move into the input (FR-002):**
   - `margin` per matchup
   - `weekHigh` / `weekLow`, with team names
   - each team's `scoreRank` (1 = top)

   The prompt says to use only numbers present in the input and never to compute new ones.
2. **Numbers are digits:** the prompt says so. The checker treats the words zero–twenty, "dozen" and
   "half" in a body as violations.
3. **Each section cites JSON pointers** into the input (`/matchups/3`, `/awards/1`). The checker:
   - resolves every cite and fails on an unknown pointer;
   - extracts every number token from `headline` and `body` (decimals, integers, `%`, records like
     `3-0`, ordinals like `6th`);
   - requires each one to equal, after normalising (`114.9` = `114.90`; `31%` = `31`), some number in
     the cited subtrees. The headline is checked against the whole input.
4. **Retry once** with the failures listed back to the model. A second failure stores `UNGROUNDED`
   with the offending tokens.

> **Amended after review (F3, F4, F6, N4): the rule is pinned below and supersedes points 2–3
> above.** The first draft never said how numbers are collected from the input. The review ran
> three readings on the plan's own excerpts: one rejected every award sentence, one let a false
> "3rd" and a wrong date pass, and one rejected the plan's own Haiku example. A citation of a
> whole array also passed three false claims.
>
> **Input pool, per section:**
> - every numeric leaf under the cited items;
> - plus number tokens found **inside string leaves** under them (award `detail` text holds 86%,
>   23.10 and 6th only as prose);
> - records (`3-0`) and ISO dates (`2025-12-25`) are each **one atomic token**, never split into
>   bare integers;
> - plus the top-level `season` and `week` in every pool, because "week 3" is in most recaps.
>
> Decimals compare at 2 dp, and the input's derived numbers are `BigDecimal` at 2 dp (F6: raw
> doubles gave `67.57999999999998`). The headline's pool is the whole input. That's stated as
> near-vacuous for small integers, since every 0–12 is present.
>
> **Body tokens:**
> - digits, decimals, `%`, records, ordinals (`6th`);
> - number words `one`–`twenty`, ordinal words `first`–`twentieth`, `half` = 0.5, `dozen` = 12;
> - month-name dates (`December 25`, `Dec 25`), normalized to month and day and matched against an
>   ISO date token.
>
> Words map to values and are checked like digits rather than banned (the draft's ban made "no one"
> and "first half" false failures). Tokenizing uses word boundaries, so `49ers` and
> `FentMachines5` contribute nothing.
>
> **Cites (F4):**
> - Each must resolve to an **item**: `/matchups/N`, `/awards/N`, `/topPerformers/N`,
>   `/bestNights/N`, `/bestWeek/N`, `/weekHigh`, `/weekLow`, `/awardsOmitted/N` or
>   `/sectionsUnavailable/N`.
> - At most 4 per section (ARBITRARY).
> - A collection pointer or `""` is a `badCite`.
>
> **Name binding (F4):** every value of a `teamName` or `playerName` field, if it appears in a
> body, must occur in that section's cited items, matched on the whole string, either as a name
> field or inside a cited string such as an award `detail`. A name
> that isn't in the input at all (a "Mahomes") still isn't detected.
>
> **NBA basis guard (N4):** in an NBA body, `counted`, `credited` or `scored for` → a basis
> violation and a retry.
>
> **What it proves:** these numbers occur in the data cited for this subject. It doesn't prove the
> sentence is true.
>
> **The unit test is the review's table, row for row, with expected verdicts:**
>
> | Case | Verdict |
> |---|---|
> | Haiku excerpt | PASS |
> | Opus excerpt | PASS |
> | false "3rd" | FAIL |
> | wrong "December 12" | FAIL |
> | "the one bright spot" | PASS only if a cited item holds a 1, in practice a `scoreRank` of 1, since records are atomic. *(Corrected while writing tasks: the first version also said "a record", which atomic records can't supply.)* With only `/topPerformers/0` cited it FAILs. The test pins both, and V3 counts how many retries this idiom causes |
> | false "finished second", cite `/awards/1` | FAIL |
> | `/matchups` collection cite | badCite |
> | "Puka-Boo" named under cite `/matchups/3` | unbound name |
> | "all of it counted" (NBA) | basis violation |

> **Amended during build (T022, 2026-10-06):** the check is stricter than the review's version in
> two places. Both are found by tests, not guessed.
> - **`season` and `week` back plain numbers only.** "week 3" and "three" still match `week`, but
>   ordinals don't: `3rd` and `third` aren't backed by it. Without this, NFL week 3 would let the
>   table's false "finished 3rd" pass. The headline still uses the whole input.
> - **Names are blanked from a body before tokenizing.** A team named "Three Amigos" or "Team 12"
>   would otherwise demand a 3 or a 12. Names are still bound to the cited items.
>
> Also from the build: `</` inside the input is escaped, so a team name can't close
> `<league_data>`. A section with zero cites is a badCite.

> **Amended after the code review (R1, R2, R3, R11, R13, 2026-10-06).** The gate is stricter again:
> - **Titles are checked like bodies** (numbers, name binding, the NBA basis guard) against their
>   own section's cites. The **headline** is checked against the **union of the items every section
>   cites**, not the whole input, so a headline can't use a figure or a name no section backs.
> - **Names match case-insensitively**, both in the binding and in the blanking before tokenizing
>   ("Jpelwell" is the handle `jpelwell`).
> - **Text is NFKC-normalized, and en/em dashes and U+2212 become `-`** before tokenizing, so a record
>   typeset "7–2" is one atomic record token and fullwidth digits are ordinary digits.
> - **A leading `-` belongs to the number**, so a negative must match a negative in the pool.
> - **`pts`/`points`/`x` glued to a number** ("157pts", "2.7x") no longer hide it: the number is checked.
> - **Left alone, on purpose:** "hundred", "thousand" and number words past "twenty" are still not
>   tokens. The prompt mandates digits; V3's human read is the backstop.
> - **Tied extremes.** `weekHigh`/`weekLow` name one team on a tie (the first seen), so "X had the
>   week's high" can be half-true. Accepted as a known limit.

**Known limits, stated rather than hidden:**
- Small integers (records, `6 of 11`) are near-vacuous checks even inside a subtree.
- **Names aren't checked.** A body that says "Mahomes" when he isn't in the input passes this check,
  as long as the numbers beside the name are ones the input has.
- Quickstart V3's human read of 5 weeks is the backstop for both. A name check is a candidate
  follow-up if V3 finds one.

## R6. What leaves the server (privacy)

- **Sent to Anthropic:** team names, player names, positions, NFL/NBA team codes, points, records,
  award text and reason codes.
- **Not sent:** Sleeper user ids, usernames, avatar ids, roster ids, `isMe`, and any request header.
- **Rationale:** the recap needs team names and nothing that identifies a person's account.
  `rosterId` isn't identifying, but it isn't needed either, and dropping it keeps the hash
  stable if roster ids are ever reassigned.

> **Amended after review (F8, N5).**
> - **"No usernames sent" was false.** A team with no name of its own is shown under its owner's
>   Sleeper display name (`WeeklyReportService:222-224`). Measured: 21 rosters across 5 leagues
>   have a team name equal to the display name, including `jpelwell` in the week-3 sample. The
>   honest wording, now in FR-001: *a team without its own name is shown, and sent, under its
>   owner's Sleeper display name, exactly as on the page.* Substituting "Team 9" was rejected
>   because the recap would then disagree with the page.
> - **Team names are member-written text sent to the model (N5).** The input goes in a delimited
>   `<league_data>` block, and the system prompt says names are opaque labels. V3 adds a test
>   league input with an instruction-shaped team name.

## R7. Flag and "premium" (design, from Allan's 2026-10-06 instruction)

- **Global:** `draftsim.recap.enabled` (`RECAP_ENABLED`, default `false`). Off, or a blank key,
  means `FEATURE_OFF`, and the Anthropic client bean is never constructed. That's asserted by a
  context test, not just "never called".
- **Per league:** a new `league_feature` table, `(league_id, feature)` primary key, where
  `league_id` is the **season row**. Entitlement is per season, which is the natural shape for a
  premium tier ("this season's league"), so it doesn't carry to next season by itself. That's
  stated, not defaulted.
- **Granted by:** `POST`/`DELETE /api/admin/leagues/{sleeperId}/features/RECAP` behind the existing
  `X-Admin-Token` gate.
- **Later:** billing would write the same row, so nothing downstream changes. There's no paywall
  UI in v1, and a non-entitled league sees no trace of the feature.
- **Alternative rejected:** a hard-coded allowlist in config. It's a deploy per grant, and it can't
  become billing-driven.

> **Amended after review (F5, F12).**
> - **"Season row" means the resolved row.** `WeeklyReportService.forWeek` walks back to the
>   newest *played* season (`LeagueSeasonResolver:57-69`). Measured: NBA 2026's id (row 210,
>   pre-draft) shows 2025's data (row 211). Entitlement, storage, single-flight key and caps all
>   use the resolved row. That means:
>   - a grant on 210 does nothing until 2026 has scores, so grant the season being played;
>   - 2025 week 10 is one row and one call whichever id reaches it;
>   - the response carries `season`.
> - **Fail closed, for real (F12).** `@ConditionalOnProperty` treats an empty string as a match
>   (`OnPropertyCondition$Spec.isMatch` in Boot 3.5.5, read via `javap`), so it can't express
>   "non-blank key".
>   - A custom `Condition` checks `draftsim.recap.enabled == true` **and** a non-blank
>     `ANTHROPIC_API_KEY`.
>   - `RecapService` takes `ObjectProvider<RecapClient>`, and `FEATURE_OFF` is decided by
>     `getIfAvailable() == null` alone, so there's one rule.
>   - The key is read from the environment in `RecapConfig`, never held in a
>     `@ConfigurationProperties` record, whose generated `toString()` would print it.
>   - There are three context tests: off, on with a blank key, on with a key.

> **Amended after the code review (R10).** `RecapService.view` resolves the league row once (for
> entitlement, storage and caps) and `WeeklyReportService.forWeek` resolves it again for the
> content. The two can disagree only if a new season's first score lands between the two calls, so a
> recap for the new season could be stored under the old row. Accepted: the window is a refresh
> landing between two resolver calls, a few ms wide, and the stored body is still grounded in the
> content it was written from.

## R8. Model is config, not a constant

`draftsim.recap.model` defaults to `claude-haiku-4-5` (Allan's choice). The request builder sends
`effort` only to models that accept it, so switching to Sonnet or Opus for the bake-off (V4) is a
config change. Storing `model` on each row means a recap always says which model wrote it.

## R9. Model comparison: needs a key, so it's a quickstart step (V4)

No credential exists on this machine: `ANTHROPIC_API_KEY` is unset and the `ant` CLI isn't
installed. Real side-by-side excerpts can't be produced in planning. The plan carries **hand-written
illustrative excerpts** for the shape and the checks. V4 replaces them with real outputs from all
three models on the same input. That costs about 15 calls at R2's rates, well under $1 (an
estimate).

> **Amended after review (F2, N3):**
> - **V4 needs its own route.** As first written, V4 would have returned one stored recap 15 times.
>   It now runs through the admin `preview` route, which never reads or writes `league_recap`.
> - **Higher `max_tokens` for thinking models.** Preview uses `max_tokens` 16,000 for Sonnet and
>   Opus, because their thinking counts against it.
> - **Time the second call.** The first structured-output call compiles the schema, so its latency
>   isn't representative.
